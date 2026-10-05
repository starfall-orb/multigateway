# Media references between tools

Tool inputs use `tool-file:<name>` URIs. User attachments are imported into the
app-private `ToolFiles` store before a tool-enabled model request, with their MIME
type and reusable URI included next to the message text. The original attachment
still uses the selected LLM's normal vision wire format. Local Android paths are
not included in the attachment metadata sent to the model.

`generate_image` accepts optional `input_images` (1–16 stored image URIs) for
editing/reference input. `generate_video` accepts an optional `input_image` string:

```json
{"prompt":"Animate the image with a slow camera pan","input_image":"tool-file:abc123.png"}
```

The same URI can come from an attachment, `generate_image`, or a stored MCP media
result. Generated media results and shortened tool results retain these URIs.
Saved tool activities also expose their available file references when rebuilding
conversation history. `send_file` delivers an existing file to the chat; it is
not required to reuse the file as a tool input. System image and video generation
continue to display their output automatically.

## Direct image/video modes

The composer accepts image attachments in both direct generation modes. An image
request can include up to 16 reference images; a video request can include one.
The selected files are retained on the stored user message and imported into
`ToolFiles` before the request. The resulting tool activity records safe input
URIs, and output media continues to appear in chat automatically.

The picker requests images in these modes. Unsupported file contents and model
capabilities return a generation error before uploading. A text prompt remains
required. The default system tools use the same adapters and validation as the
direct modes. MCP argument transport is unchanged; an internal URI alone does
not make a remote MCP server able to read an app-private file.

## Request adapters

- OpenAI-compatible image requests without attachments use `/images/generations`.
  With attachments, `/images/edits` receives multipart `image[]` parts plus the
  selected model, prompt, and image options. The existing SSE reader also handles
  streamed image edit completions. GPT image inputs are capped below 50 MiB each.
  DALL·E 2 uses one square PNG under 4 MiB and the `image` part; DALL·E 3 editing
  is rejected. See the [image edits reference](https://developers.openai.com/api/reference/resources/images/methods/edit).
- Gemini image editing uses `contents[].parts[].inlineData` alongside the prompt,
  preserving image configuration and output modalities. The complete inline
  request is capped at 20 MiB, with an aggregate size check before reading image
  bytes into memory. The Imagen generation endpoint is rejected for reference
  images. See the [Gemini image editing guide](https://ai.google.dev/gemini-api/docs/image-generation?hl=en).
- OpenAI-compatible `/videos` requests send the resolved image as the multipart
  `input_reference` file part, with a storage filename and verified MIME type.
  H3 gateways implementing this interface use the same adapter, without hostname
  detection. See the [H3-compatible endpoint reference](https://docs.aiupnode.com/api/video/hailuo.html).
- Google/Veo requests include `instances[].image.inlineData` containing `mimeType`
  and base64 `data`. See the [Gemini Veo REST examples](https://ai.google.dev/gemini-api/docs/veo).
- Omitting `input_image` preserves text-to-video requests. Other provider protocols
  return an image-to-video capability error before attempting an upload. Compatible
  servers can still reject inputs their selected video model does not support;
  those API errors return to the model as normal tool errors.

`VideoRequests.kt` owns provider payload construction. `ToolChat` only transports
the provider-neutral URI. Additional image fields or capability configuration can
be added when an actual endpoint requires them.

## Input validation

The resolver accepts only storage references, rejects traversal and external
symlinks, checks a non-empty file against the selected request size limit, and checks PNG, JPEG, or WebP
magic bytes. It does not trust the extension or caller-provided MIME type. It
rejects arbitrary paths, `file:`, `content:`, HTTP URLs, missing files, and other
formats for media image inputs before request construction. Video image inputs
are capped at 20 MiB. The general storage limit
remains 256 MiB for generated/downloaded files.

## Verification

`MediaInputReferenceTest` covers schema/argument validation, MIME and size checks,
path and symlink rejection, OpenAI-compatible and H3 multipart uploads,
text-to-video, Google payload/upload behavior, unsupported providers, and retained
references in shortened tool results.

`MediaToolChainingTest` runs both attachment-to-video and
image-generation-to-video through the real tool loop with a mock HTTP server. It
checks that image bytes reach the video request, file paths stay out of model
requests, and video files appear in the successful tool activity without a
`send_file` call. Live generation with paid provider credentials is not part of
these tests.

`DirectMediaAttachmentTest` verifies direct OpenAI image editing (multiple images,
options, and SSE), direct video reference uploads, native Gemini image parts, and
invalid attachment errors before network calls. `MediaToolChainingTest` also
checks that the model can reuse an uploaded image through the default image tool.
