# MultiGateway Library Migration Plan

## Goal

Reduce maintenance cost, eliminate recurring UI/gesture edge cases, and simplify the app by replacing selected hand-written infrastructure with focused external libraries where those libraries provide a clear reliability or maintenance advantage.

The migration should preserve MultiGateway's existing architecture, visual language, and app-specific business logic. Libraries should replace implementation plumbing, not dictate product behavior.

## Priority order

1. Reorderable
2. Bottom sheet
3. Coil
4. Markdown
5. Media3
6. AppAuth

---

## 1. Replace custom drag/reorder infrastructure

### Current state

The app currently maintains two separate reorder implementations:

- `ui/components/LazyListReorder.kt`
- `ui/components/ReorderableItem.kt`

They are used across Providers, provider models, MCP servers, Speech, Profiles, list layouts, and grid layouts.

This creates a large surface for gesture, position, animation, autoscroll, keyed-item, and nested-folder bugs.

### Proposed library

Use `Calvin-LL/Reorderable`.

### Migration scope

Delegate the following to the library:

- Long-press drag gesture handling.
- Dragged-item positioning.
- Reorder calculations.
- Edge autoscroll.
- LazyColumn/LazyRow/LazyGrid integration.
- Placement animation coordination.

Keep MultiGateway-specific logic in the app:

- Provider folder membership.
- Moving providers into/out of folders.
- Root-vs-folder constraints.
- Persistent ordering.
- Search-mode restrictions.
- Save/update behavior.

### Expected result

Remove most custom pointer-coordinate and reorder calculations while keeping the current UX and folder behavior.

---

## 2. Simplify bottom sheets

### Current state

`ui/components/AppBottomSheet.kt` currently owns substantial low-level behavior including:

- Dialog/window configuration.
- Edge-to-edge handling.
- Status/navigation bar interaction.
- IME padding.
- Safe drawing insets.
- Custom resize behavior.
- Nested scroll interception.
- Entrance/dismiss animation.
- Drag handling.
- Accessibility semantics.

This makes system-bar and layout regressions easy to introduce.

### Proposed approach

Prefer standard Material 3 `ModalBottomSheet` wherever free-form resizing is not required.

If the app still needs multiple/custom expansion states, evaluate `FlexibleBottomSheet` instead of maintaining the full implementation internally.

### Migration rules

- Do not introduce a library merely to handle safe areas; Compose `WindowInsets` is sufficient.
- Minimize direct manipulation of dialog windows/system bars.
- Preserve MultiGateway's visual styling and shared bottom-sheet API where useful.
- Remove custom behavior that duplicates platform/library functionality.

### Expected result

Fewer status bar/navigation bar/IME regressions and a much smaller custom bottom-sheet implementation.

---

## 3. Move image loading and bitmap caching to Coil 3

### Current state

Image decoding/caching is implemented manually in several places, including:

- `IconStore`
- `EntityIcon`
- `MediaPreview`
- `AttachmentPreview`
- `FaviconService`
- `FaviconDialog`
- `StorageScreen`

The app directly uses `BitmapFactory`, manual sampling, bitmap storage, and Compose bitmap conversion.

### Proposed library

Use Coil 3 with Compose integration.

### Migration scope

Delegate to Coil:

- Bitmap decoding.
- Request lifecycle.
- Cancellation.
- Downsampling.
- Memory cache.
- Disk cache.
- Compose image rendering.

Keep MultiGateway-specific icon logic:

- Shared icon registry.
- Regex matching.
- Provider/model/MCP name matching.
- Model-name normalization rules.
- User-imported icons.
- Dark/light variants.
- Favicon acquisition rules.

The preferred flow becomes:

`IconStore.resolve(...) -> URI/File/request data -> Coil ImageLoader -> Compose`

rather than:

`IconStore.loadIcon(...) -> BitmapFactory -> Bitmap -> Image`

### Expected result

Less bitmap-management code, better lifecycle behavior, less repeated decoding, and a single image-loading/cache stack.

---

## 4. Replace most custom Markdown parsing/rendering

### Current state

The chat currently maintains its own Markdown stack, including:

- `MarkdownParser.kt`
- `MarkdownRenderer.kt`
- `MarkdownInlineText.kt`
- `MarkdownCodeBlock.kt`
- `CodeSyntaxHighlighting.kt`
- Table/list/task-list rendering.
- Inline links and formatting.
- Streaming rendering behavior.

This duplicates a large amount of generic Markdown functionality and creates edge cases as AI output becomes more complex.

### Proposed library

Evaluate `mikepenz/multiplatform-markdown-renderer` as the primary candidate.

### Migration scope

Delegate generic Markdown parsing and rendering primitives to the library, including where suitable:

- Headings.
- Paragraphs.
- Lists.
- Block quotes.
- Tables.
- Task lists.
- Inline formatting.
- Links.
- Fenced code recognition.
- Syntax highlighting.

Preserve custom MultiGateway UI/behavior for:

- Code-block toolbar.
- Copy button.
- Wrap toggle.
- App typography/colors.
- Streaming fade behavior.
- Tool/thinking blocks.
- Message-specific interactions.

Prefer incremental/streaming parsing APIs if they avoid reparsing the entire response on every token update.

### Expected result

Substantially less parser/rendering code and better handling of real-world Markdown generated by LLMs.

---

## 5. Use AndroidX Media3 for media playback

### Current state

Media playback is split across lower-level Android APIs such as:

- `MediaPlayer`
- `VideoView`
- `MediaMetadataRetriever`
- Manual `AudioFocusRequest`

The app also contains multiple playback-related implementations.

### Proposed library

Use AndroidX Media3 / ExoPlayer as the shared playback engine.

### Migration scope

Use Media3 for:

- Audio playback.
- Video playback.
- Playback lifecycle.
- Audio focus behavior.
- Player state.
- Duration/position tracking.
- Error reporting.
- Future media-session integration if needed.

Keep MultiGateway's existing Compose UI instead of adopting a stock player UI unless that UI is specifically useful.

Image preview should remain an image-loading concern and can use Coil.

### Expected result

One consistent playback engine with fewer lifecycle/audio-focus edge cases and less duplicated media code.

---

## 6. Use AppAuth for standard OAuth transactions

### Current state

OAuth currently includes substantial custom browser/session/token-flow handling, including:

- `OAuthBrowserActivity.kt`
- Custom Tabs lifecycle.
- Browser service binding.
- Close/callback timing workarounds.
- PKCE handling.
- Authorization-code exchange.
- Refresh-token handling.

MCP OAuth additionally contains protocol-specific discovery and registration logic in `McpOAuthService.kt`.

### Proposed library

Use `AppAuth-Android` for standard OAuth 2.0/OIDC transaction mechanics.

### Important constraint

Do **not** replace MCP-specific OAuth discovery logic.

MultiGateway still needs its own handling for:

- MCP Protected Resource Metadata.
- Authorization Server discovery.
- MCP authorization challenges.
- Dynamic Client Registration.
- Resource indicators.
- MCP-specific compatibility behavior.

The intended architecture is:

`MCP discovery / DCR -> AppAuth authorization transaction -> token -> McpOAuthTokenStore`

AppAuth should own the generic OAuth transaction where possible, while MultiGateway retains protocol-specific orchestration.

### Expected result

Less custom Custom Tabs/callback/token machinery without losing MCP OAuth compatibility.

---

## Libraries/frameworks not recommended

### Dependency injection

Keep the existing `AppContainer`.

The dependency graph is currently small and explicit. Adding Hilt or Koin would add framework complexity without enough benefit.

### Navigation

Keep AndroidX Navigation Compose.

There is no strong reason to migrate to another navigation framework.

### State management

Keep ViewModel + StateFlow.

Do not add Orbit, Mavericks, Molecule, or another MVI framework merely to restructure already-working state management.

### Networking

Keep the current combination of:

- Official OpenAI SDK.
- Official Anthropic SDK.
- Official Google GenAI SDK.
- Ktor.
- Official MCP Kotlin SDK.

Do not introduce Retrofit unless a future use case specifically requires it.

### Safe-area handling

Do not add a separate safe-area library.

Use Compose `WindowInsets` and reduce custom Window/Dialog manipulation instead.

### Text fields

Keep Compose text input APIs and the existing IME-aware approach.

Do not introduce a full text-editor framework merely to solve selection or composing-IME behavior.

---

## Migration principles

For every migration:

1. Preserve existing user-visible behavior unless the old behavior is itself a bug.
2. Replace only generic infrastructure; keep MultiGateway-specific business rules.
3. Do not migrate multiple high-risk subsystems in one change.
4. Add or retain tests around behavior before deleting the old implementation.
5. Remove old code only after the replacement is exercised by all current call sites.
6. Avoid adding libraries that duplicate capabilities already supplied well by AndroidX or existing project dependencies.
7. Prefer mature libraries with active maintenance and strong Compose support.
8. Keep UI operations responsive and avoid moving business/network work onto the main thread.
9. Measure dependency/APK impact before accepting a library with a large transitive graph.
10. Keep the architecture simple: fewer custom primitives, not more framework layers.

## Recommended execution sequence

### Phase 1 — Reorderable

Replace the two custom reorder systems and validate:

- Provider root reorder.
- Provider-in-folder reorder.
- Moving providers between folder/root where supported.
- Grid/list modes.
- Models.
- MCP servers.
- Speech services.
- Profiles.
- Edge autoscroll.
- Persistence after drop.

### Phase 2 — Bottom sheets

Migrate sheets to Material3 or FlexibleBottomSheet and validate:

- Status bar.
- Navigation bar.
- Gesture navigation.
- Three-button navigation.
- IME open/close.
- Scrolling content.
- Short content.
- Tall content.
- Landscape.

### Phase 3 — Coil 3

Create a shared `ImageLoader`, migrate icon/media thumbnails, and remove manual bitmap caching/decoding only after all icon-matching behavior remains intact.

### Phase 4 — Markdown

Migrate block types incrementally, keeping MultiGateway's custom code-block and streaming UX. Validate partial/incomplete Markdown during streaming, not only completed messages.

### Phase 5 — Media3

Replace lower-level playback paths with a shared player abstraction and remove duplicate MediaPlayer/VideoView logic.

### Phase 6 — AppAuth

Extract generic OAuth transaction mechanics behind an abstraction, then migrate supported OAuth providers one path at a time. Keep MCP discovery/DCR as MultiGateway-owned logic.

## Completion criteria

The migration is complete when:

- Old generic subsystem engines are removed. Narrow compatibility adapters are permitted only for a documented product/protocol requirement that the selected library cannot reproduce.
- Provider dragging, positioning, move targeting and edge autoscroll use Calvin on one keyed lazy surface across folder/root boundaries; the app owns membership, persistence and cancellation rollback.
- Arbitrary sheet height remains a product requirement. AppBottomSheet may retain the content-height/handle adapter and directional scroll boundary, while Material exclusively owns its dialog, insets, scrim and lifecycle. Direct Window/system-bar ownership is prohibited.
- Registered HTTP loopback redirects, JSON token bodies, cleartext MCP endpoints and device-code/provider-specific protocols may retain explicit OAuth compatibility paths. Standard HTTPS form code exchange/refresh uses AppAuth; registered app-scheme redirects use AppAuth browser/redirect handling. No provider redirect registration is silently changed.
- Existing product behavior is preserved.
- Relevant unit/instrumentation tests pass.
- UI interactions remain non-blocking.
- No known regression exists in system bars, IME, drag/drop, streaming Markdown, icon resolution, media playback, or OAuth.
- Dependency additions demonstrably reduce custom code or bug surface rather than merely adding abstraction.
