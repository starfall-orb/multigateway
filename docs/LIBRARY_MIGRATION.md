# Library migration

The six migration paths in `PLANS.md` are implemented without changing AppContainer,
Navigation Compose, ViewModel/StateFlow or the existing network clients.

| Area | Dependency | Integration |
| --- | --- | --- |
| Reordering | Calvin Reorderable 3.1.0 | Models, MCP, speech, profiles, provider root/group items; keyed lazy list/grid items, long press, placement and edge scrolling |
| Sheets | Existing Material 3 | ModalBottomSheet owns dialog, insets, scrim, animation and dismissal |
| Images | Coil 3.0.4, Compose, OkHttp, video | Application ImageLoader, icons, imported images, favicons and image/video thumbnails |
| Markdown | mikepenz renderer M3 0.34.0 | GFM parser and block/inline components; app code toolbar, typography, LaTeX and streaming fades |
| Playback | Media3 ExoPlayer 1.8.0 | Shared player factory; video, audio previews and synthesized speech |
| OAuth | AppAuth 0.11.1 | PKCE/state, authorization/code/refresh request models, client authentication, response validation and browser service lifecycle |

Markdown 0.34.0 was selected to fit the existing Compose generation. Its newer release
would also require a Compose upgrade. The optional Highlights renderer was evaluated;
the existing app lexer is retained because the candidate does not cover the app's
HTML, SQL, JSON, YAML, CSS and JSX/TSX language set.

## Compatibility adapters retained

- Provider transfers across folder/root boundaries retain `ProviderDrag` and the lifted
  overlay. These implement membership changes, cancellation rollback and transactional
  placement saving across nested folder surfaces. Library dragging handles root/group
  ordering; provider transfers still use the existing hit testing and do not gain
  library edge autoscroll. This is a remaining migration limitation, not a claim that
  every custom drag implementation has been eliminated.
- Material sheets use a small content sizing/handle adapter for arbitrary intermediate
  heights, with the existing list-edge boundary. FlexibleBottomSheet was evaluated;
  its segmented expansion states cannot preserve this interaction. Material draws its
  background behind navigation bars while safe insets protect interactive content.
- ICO's DIB container decoder remains because Coil does not decode this format. PNG
  entries and ordinary raster images go through Coil. Icon matching, import persistence,
  dark/light variants and favicon acquisition remain app-owned. Compatibility bitmap
  APIs are used only on IO paths; Compose icons and full image previews use AsyncImage.
- Streaming Markdown parsing runs on Default and coalesces updates. It still parses a
  complete received snapshot; the selected library does not offer an incremental AST
  suitable for these interactions. Custom code highlighting and fade tracking remain.
- MediaMetadataRetriever remains only for metadata such as attachment duration, not
  playback. Media3 owns focus, noisy-output handling and playback errors. The Compose
  player pauses when its lifecycle pauses, and releases on disposal. Speech file writes
  run on IO and are cancellation-safe.
- Registered providers use HTTP loopback redirects. Their listener and cancellation
  orchestration remain; switching to AppAuth's redirect Activity would require changing
  provider registrations. AppAuth supplies standard request/token/browser mechanics;
  the app's cancellable HTTP transport also supports nonstandard JSON token endpoints.
  MCP PRM/discovery/DCR/resource indicators, encrypted token storage, enrichment and
  device-code providers remain app-owned.

## Validation

Automated coverage includes persistent order and folder constraints, Coil icon/favicons,
all prefixes of incomplete Markdown, fences and reference links, OAuth discovery/DCR,
resource indicators, refresh, PKCE, client-secret fields and JSON token compatibility.
The instrumentation suite includes drag/drop, sheet resizing/scrolling/insets, media
layout and OAuth loopback behavior.

No emulator or physical device was attached in this environment. Compiling the
instrumentation suite does not establish that device behavior passes. Before accepting
all completion criteria, run `connectedDebugAndroidTest` and manually exercise gesture
and three-button navigation, keyboard open/close, landscape sheets, edge autoscroll,
provider folder transfers/cancellation, inline/fullscreen playback, background/foreground,
audio focus, unavailable media, browser return/cancel and live provider OAuth refresh.

Build results and APK size are recorded below after the validation run.
