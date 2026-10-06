# MultiGateway Library Migration — Remaining Problems

This document records the gaps found while auditing the implementation against `PLANS.md`.

The six planned library families are present in the project, and the project currently compiles and passes unit tests. However, the migration is **not yet fully complete according to the completion criteria in `PLANS.md`**, because some app-owned infrastructure that the plan intended to retire is still active.

## 1. Provider drag/drop is still partly custom

### Status

**Incomplete**

The old generic reorder helpers were removed:

- `ui/components/LazyListReorder.kt`
- `ui/components/ReorderableItem.kt`

Calvin Reorderable is now used for normal list/grid reordering in Models, MCP, Speech, Profiles, and provider root/group ordering.

However, provider transfer between folder/root surfaces still uses the custom implementation:

- `ui/providers/ProviderDrag.kt`
- Custom `detectDragGesturesAfterLongPress`
- Custom pointer tracking.
- Custom card bounds tracking.
- Custom floating overlay positioning.
- Custom hit testing in `ProviderScreen.kt`.
- Custom cancellation/rollback behavior.

The migration documentation also explicitly notes that provider transfers do not receive library edge autoscroll.

### Why this matters

`PLANS.md` states that the reorder library should own:

- Long-press drag gesture handling.
- Dragged-item positioning.
- Reorder calculations.
- Edge autoscroll.
- Lazy list/grid integration.

It also defines completion as removing old custom infrastructure after all call sites are migrated.

Provider folder/root transfer still violates that goal.

### Required follow-up

Refactor provider transfer so that the library owns as much of the physical drag lifecycle as possible.

MultiGateway should continue owning only business rules such as:

- Folder membership.
- Root/folder transfer legality.
- Transactional persistence.
- Cancellation rollback.
- Final target selection.

If Calvin Reorderable cannot support cross-container transfer directly, introduce a small adapter around the library rather than retaining a second independent drag engine.

Edge autoscroll must also work during provider transfer, including while crossing root/folder boundaries.

---

## 2. Bottom sheet migration still retains a custom layout/resize engine

### Status

**Partially complete**

`AppBottomSheet.kt` now correctly delegates the following to Material 3 `ModalBottomSheet`:

- Dialog ownership.
- Scrim.
- Show/hide lifecycle.
- System insets.
- Base accessibility behavior.
- Material sheet state.

This is a major improvement over the previous custom Dialog/Window implementation.

However, the app still owns:

- A custom draggable resize handle.
- Manual requested/measured height state.
- Custom height bounds.
- A custom `Layout`.
- Custom nested-scroll handoff.
- Overscroll suppression.
- Custom resize accessibility progress semantics.

### Why this matters

This no longer has the dangerous custom Window/system-bar ownership that caused the original problems, so it is not equivalent to the old implementation.

But it still means the bottom-sheet migration does not fully satisfy the broad completion criterion:

> The old custom infrastructure for each migrated subsystem is removed.

The remaining adapter is justified only if free-form sheet resizing is an actual product requirement that Material 3 or a suitable library cannot reproduce.

### Required follow-up

Decide explicitly which behavior is required:

1. If arbitrary free-form resizing is not necessary, remove the custom resize/layout layer and use Material 3 sheet states directly.
2. If arbitrary resizing is required, keep the adapter but update `PLANS.md` completion criteria to explicitly permit this small compatibility layer.

Do not return to direct Dialog Window/system-bar manipulation.

---

## 3. AppAuth integration does not own the complete OAuth transaction

### Status

**Partially complete**

AppAuth is integrated and currently provides:

- `AuthorizationRequest`.
- `TokenRequest`.
- PKCE/request model behavior.
- Client authentication helpers.
- Response validation.
- Browser discovery/binding through `AuthorizationService`.

However, MultiGateway still owns important generic OAuth plumbing:

- `OAuthBrowserActivity` still launches the Custom Tab itself.
- Authorization callback collection still uses the custom HTTP loopback listener.
- `awaitOAuthAuthorizationCode` remains app-owned.
- Token requests are serialized and sent through `ToolHttp` rather than AppAuth's normal token execution path.
- Browser completion/cancellation orchestration remains custom.
- OpenAI Codex still uses the loopback flow.
- MCP OAuth still uses the loopback flow.

Some of this is necessary because existing provider registrations use HTTP loopback redirects and some compatible token servers require nonstandard JSON bodies.

### Why this matters

The plan says AppAuth should own standard OAuth transaction mechanics where possible.

The current implementation uses AppAuth substantially, but it is closer to an AppAuth-backed compatibility layer than a full AppAuth transaction migration.

### Required follow-up

Separate OAuth flows into two categories:

#### Standard AppAuth-compatible flows

Where provider registration and token endpoint behavior permit it, allow AppAuth to own:

- Browser authorization launch.
- Redirect handling.
- Authorization response parsing.
- Code exchange.
- Refresh.

#### Compatibility flows

Keep custom transport/listener logic only where required for:

- HTTP loopback redirects.
- JSON token endpoints.
- Provider-specific behavior.
- MCP discovery/DCR/resource semantics.

Document every remaining compatibility path explicitly so custom OAuth code cannot silently become the default again.

---

## 4. Reorder migration still lacks full behavior parity for edge scrolling

### Status

**Incomplete for provider transfer**

Normal Calvin Reorderable lists/grids receive library edge scrolling.

Provider cross-folder/root transfer does not.

### Required follow-up

Test and implement:

- Drag provider toward top edge.
- Drag provider toward bottom edge.
- Drag out of a long folder while scrolling.
- Drag from root into a folder that is partially off-screen.
- Reverse direction during autoscroll.
- Cancel after autoscroll and ensure ordering/membership rollback is exact.

This is part of Phase 1 acceptance, not an optional enhancement.

---

## 5. Completion criteria and implementation documentation disagree

### Status

**Needs reconciliation**

`docs/LIBRARY_MIGRATION.md` says:

> The six migration paths in `PLANS.md` are implemented.

The same document then lists compatibility limitations for:

- Provider cross-container drag.
- Bottom-sheet resizing.
- Streaming Markdown parsing.
- ICO decoding.
- OAuth loopback handling.

Several of those limitations are valid compatibility decisions, but `PLANS.md` currently has stricter wording that says old infrastructure should be removed.

### Required follow-up

After resolving the functional gaps, make the documentation use one definition of "complete":

- Either complete means no old generic infrastructure remains, or
- Complete means only narrowly justified compatibility adapters remain.

The current documents use both definitions.

---

## 6. Device instrumentation validation is not yet confirmed complete in this audit

### Status

**Pending at audit time**

The following validation command completed successfully:

```sh
ANDROID_HOME=/opt/android-sdk ./gradlew testDebugUnitTest compileDebugAndroidTestKotlin assembleDebug
```

Result:

- BUILD SUCCESSFUL.
- Unit tests/build artifacts compile successfully.

An ADB device is connected and `connectedDebugAndroidTest` was started during this audit, but its final result had not yet returned when this file was written.

Therefore this audit must not claim device behavior is fully validated yet.

### Device behaviors that must pass

- Provider drag/reorder.
- Provider cross-folder/root transfer.
- Edge autoscroll.
- Bottom-sheet resize.
- Bottom-sheet list-to-sheet gesture handoff.
- Gesture navigation.
- Three-button navigation.
- IME open/close.
- Landscape sheets.
- Model picker scrolling/toggling.
- Media playback lifecycle.
- Audio focus/noisy-output handling.
- OAuth loopback return/cancel.
- OAuth refresh where practical.

---

# Areas that appear fully migrated

The following areas currently look substantially complete from code inspection.

## Coil 3

Coil is the shared image loader and cache.

`BitmapFactory`-based general-purpose loading is gone. Remaining bitmap conversion is mainly for APIs/components that still require a `Bitmap`, and ICO DIB decoding remains as an explicit compatibility decoder.

No major migration blocker was found here.

## Markdown renderer

The mikepenz Markdown renderer and parser now own generic Markdown parsing/rendering.

MultiGateway retains intentional custom presentation for:

- Code toolbar.
- Streaming fade.
- LaTeX.
- Error blocks.
- Code syntax highlighting where the candidate renderer does not cover the required language set.

No remaining independent block Markdown parser was found.

## Media3

No active `android.media.MediaPlayer` or `VideoView` playback implementation was found.

Media3/ExoPlayer is the shared playback engine for media and synthesized speech.

No major migration blocker was found here.

---

# Current conclusion

The migration is **mostly implemented but not fully complete**.

The primary blocker is provider drag/drop across folder/root boundaries, because it still maintains a second custom drag engine and lacks library edge autoscroll.

Bottom-sheet and OAuth work are partially migrated with compatibility adapters. Those adapters may be valid, but the implementation and `PLANS.md` must agree explicitly on whether retaining them is acceptable.

The migration should not be marked fully complete until:

1. Provider cross-container drag is resolved.
2. Provider edge autoscroll is covered.
3. Bottom-sheet compatibility behavior is either removed or formally accepted.
4. OAuth compatibility paths are clearly separated from standard AppAuth flows.
5. Device instrumentation tests finish successfully.
6. `PLANS.md` and `docs/LIBRARY_MIGRATION.md` use the same completion definition.
