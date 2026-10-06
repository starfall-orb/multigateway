# Library migration

The six library integrations are present. Completion uses the compatibility-adapter
definition in `PLANS.md` and also requires successful device validation. That device
acceptance remains pending; this document does not claim the migration is complete.

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

- Provider membership preview and transactional saving remain business logic. All root,
  folder and provider cards now share a flattened keyed Calvin lazy grid, including
  list mode. The independent ProviderDrag pointer engine, nearest-slot hit testing,
  nested folder layout and floating overlay are removed. Calvin supplies long press,
  physical positioning, targets and edge autoscroll across folder boundaries. Its
  DragInteraction Stop/Cancel signals distinguish commit from exact snapshot rollback;
  disposal also rolls back an active transaction. Root start/end drop slots permit
  leaving a folder when no root provider is available as a target.
- Material sheets use a small content sizing/handle adapter for arbitrary intermediate
  heights, with a boundary that passes downward scroll/fling at the list start to Material
  and contains upward overscroll at the list end. FlexibleBottomSheet was evaluated;
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
- OAuth standard and compatibility execution are separate. AppAuth executes all standard
  HTTPS form token requests, including Codex code exchange/refresh, account adapters
  and MCP. It owns request encoding, client authentication and response/error parsing.
  StandardAppAuthAuthorization uses performAuthorizationRequest and AppAuth's redirect
  receiver for the registered `multigateway-oauth` scheme; AppAuthResultActivity only
  delivers the library result to the waiting app operation. Existing provider redirect
  registrations are not changed to activate this scheme without server registration.

### OAuth compatibility inventory

| Path | Reason for compatibility | Retained app work |
| --- | --- | --- |
| OpenAI Codex | Registered HTTP loopback redirect | Bounded loopback listener/browser session; account token persistence and claims; code/refresh execution is AppAuth |
| Antigravity | Registered 127.0.0.1 loopback redirect | Listener/browser session; discovery/enrichment and encrypted storage; HTTPS form tokens use AppAuth |
| Claude Code | Registered loopback and JSON token bodies | Listener/browser session plus explicit CompatibilityOAuth JSON exchange and Bearer fallback |
| GitHub Copilot | Device authorization/polling protocol | Provider device-code polling/browser lifetime and Copilot token enrichment |
| MCP | Registered loopback, PRM/discovery/DCR/resource semantics | Listener, discovery/DCR/resource and persistence; HTTPS form token execution is AppAuth |
| MCP HTTP token endpoint | Local/cleartext server protocol support | Explicit CompatibilityOAuth form transport and compatible Bearer fallback |

CompatibilityOAuth is selected only for JSON tokens or non-HTTPS endpoints. Network
failures from the standard AppAuth path do not silently switch back to custom transport.
The loopback listener is explicitly named awaitLoopbackAuthorizationCode in
OAuthLoopbackCompatibility.kt. Browser cancellation for those listener/device-code
sessions retains the app's explicit cancellation and timeout behavior.

## Validation

Automated coverage includes persistent order and folder constraints, Coil icon/favicons,
all prefixes of incomplete Markdown, fences and reference links, OAuth discovery/DCR,
resource indicators, refresh, PKCE, client-secret fields and JSON token compatibility.
The instrumentation suite includes drag/drop, sheet resizing/scrolling/insets, media
layout and OAuth loopback behavior.

No emulator or physical device was attached during the initial migration validation. Compiling the
instrumentation suite does not establish that device behavior passes. Before accepting
all completion criteria, run `connectedDebugAndroidTest` and manually exercise gesture
and three-button navigation, keyboard open/close, landscape sheets, edge autoscroll,
provider folder transfers/cancellation, inline/fullscreen playback, background/foreground,
audio focus, unavailable media, browser return/cancel and live provider OAuth refresh.

The test environment now uses MockWebServer 5.3.2 to match the OkHttp version
resolved by the existing Ktor client, and includes Android resources for OkHttp's
public-suffix assets. Network fixtures advertise valid MCP initialization metadata
and reject optional GET/SSE requests explicitly. The app retains compatibility
adapters for legacy SSE fallback, negotiated protocol headers and reconnecting on the
next operation after a missing session, without replaying the failed tool call.

Validated with Android SDK 36 and JDK 21:

```sh
ANDROID_HOME=/opt/android-sdk ./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug --console=plain
```

Migration validation result: **BUILD SUCCESSFUL**, 302 unit tests, zero failures/errors/skips.
Instrumentation tests compiled successfully; they were not executed on a device.
The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

At that validation run, the pre-existing debug APK was 52,640,694 bytes; the resulting APK was 57,775,808
bytes, an aggregate increase of 5,135,114 bytes (9.75%). This comparison includes
other changes already present in the shared workspace and is not an isolated
per-library benchmark. Both are unminified debug artifacts.

The runtime dependency report confirms Compose UI remains at 1.7.5; the migrations
reuse existing Material 3, OkHttp and coroutine dependencies. New library families
are Reorderable, Coil's Compose/network/video modules, the Markdown renderer and
JetBrains parser, Media3's seven playback modules, and AppAuth. No DI, navigation,
state-management or additional networking framework was added.

### Follow-up: list-to-sheet gesture handoff

Unused downward scroll and fling at the list start now pass to Material's sheet
nested-scroll connection. Upward overscroll at the list end remains contained.
The two boundary tests and four height tests pass; the debug APK builds. Added
instrumentation regressions cover dragging at the start, reaching the start during
the same gesture, and consuming list motion before moving the sheet. Device execution
was pending at the initial validation; device follow-up is recorded below.

### Follow-up: continuous scrolling without stretch lock

Android stretch overscroll is disabled inside sheet content using the Compose 1.7
LocalOverscrollConfiguration. Lists consume their normal scroll first, with unused
downward motion reaching Material at the start. An instrumentation regression pulls
from the bottom to the exact start using only repeated downward gestures, checks that
each gesture makes progress, and then checks immediate sheet movement. It also
exercises excess dragging at the bottom before returning to the start.

### Follow-up: model picker startup and branch toggles

The picker keeps one LazyListState for its lifetime. The selected-model position is
applied once when rows first arrive; folder/provider toggles and subsequent catalog
updates no longer recreate the scroll state. Search still starts at the first result.
Dynamic model maps are passed as immutable snapshots so memoized picker rows update
as soon as discovery completes.

Ollama discovery reads the last successful catalog from disk before starting parallel
provider requests. LlmService reuses fresh entries for five minutes, coalesces requests
for the same endpoint, and retains stale entries on network failure. A successful
empty response clears removed models. Unchanged endpoints retain their displayed
models when providers are edited. Startup configuration migration and icon pruning
now run on IO rather than the main thread. These address observed code paths; no
controlled before/after startup timing benchmark has been recorded.

Latest validation: 305 unit tests pass, debug and instrumentation APKs build. Both
APKs have been installed through ADB on the Android 11 Star 4 device. Targeted instrumentation was attempted on the device. Initial execution was blocked
by the keyguard; subsequent attempts were interrupted by Android's lowmemorykiller
(`reason: device is not responding`) and an input-dispatch ANR. A later isolated gesture retry passed `draggingDownAtListStartMovesTheSheet`
using the inner drag-handle position. The following picker test was interrupted
by another process termination; the full targeted suite has not passed. A gesture test also exposed that the outer Material modifier's
bounds stay at y=0; the test now measures the inner drag handle to track actual
sheet movement. Gesture and picker regressions still need a successful device run.

The ordinary MainActivity launch did complete, with a reported 16.860-second display
time during the same device session. This is one debug-device observation under
system pressure, not a controlled model-catalog performance comparison.

### Problem.md follow-up

Provider drag migration now uses one library lifecycle in both layouts. Arbitrary
sheet height is explicitly allowed as a product compatibility adapter in PLANS.md.
OAuth execution and the compatibility inventory above use the same completion
definition. New provider instrumentation covers stationary-edge scrolling through a
long folder, reversing direction, and cancellation without persistence in both
layouts. A Robolectric network test exercises the actual AppAuth form refresh path,
including Basic client authentication, resource fields and extension-token fields.
Device acceptance must be recorded from successful execution, not inferred from
compilation or these code changes.
