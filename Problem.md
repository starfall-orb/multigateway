# MultiGateway Library Migration — Current Problems

This file reflects the **current implementation**, not the earlier migration snapshot.

The six planned library integrations are present. The architectural migration is now substantially aligned with `PLANS.md`, including its explicit compatibility-adapter rules.

Current build validation passes, but device acceptance is **not complete** because the provider drag autoscroll device regression test fails and several remaining UI regressions are being interrupted by severe device memory pressure.

## Outstanding issues — updated 2026-10-06

Work is incomplete. Investigation stopped at the status report without an isolated cause or a verified fix for the autoscroll test failure. This remains unfinished work.

| Area | Current status | Required follow-up |
| --- | --- | --- |
| Provider edge autoscroll in grid mode | Completed test failed: `before=0.0 after=0.0` | Isolate the cause, fix it and rerun; test coordinates or clock timing have not been ruled out |
| List autoscroll, folder/root transfers, drop and cancellation | Insufficient results on the current APK | Validate scrolling in both directions, persistence on drop and rollback on cancellation |
| Model picker folder/provider toggles | Scroll-state retention implemented; device validation incomplete | Verify that the viewport does not jump back to the selected model |
| Slow model-picker loading after app startup | Caching and parallel discovery implemented; no before/after measurement | Measure cold startup, cache hits/misses and time to display the model list |
| Continuing a list drag into the sheet at the list start | One drag-at-start test passed; continuous gestures not fully validated | Verify handoff within the same gesture and scrolling without a reverse swipe to unlock it |
| Instrumentation suite and manual acceptance | Full acceptance not achieved | Update outdated UI test assumptions and execute the remaining checks |

A successful build and **305 passing unit tests** do not establish acceptance of these UI behaviors. The autoscroll assertion failure and interrupted test processes are separate results. Memory pressure and concurrent APK installation explain some interruptions; they do not yet explain the completed autoscroll result of `0.0`.

## 1. Provider edge autoscroll fails in grid mode

### Status

**Confirmed failing device regression; underlying cause not yet isolated**

The previous independent provider pointer engine has been removed.

Current provider dragging uses Calvin Reorderable on the flattened keyed lazy grid/list surface:

- `ProviderDrag.kt` is gone.
- No provider UI code uses `detectDragGesturesAfterLongPress` or a custom `pointerInput` drag engine.
- `PackedProviderGrid.kt` uses `rememberReorderableLazyGridState`.
- Provider membership changes remain app business logic through `ProviderDragLayout` and `moveProviderDrag`.
- Root/folder movement is previewed transactionally and rolled back on cancellation.

This now matches the architectural requirement in `PLANS.md`.

However, the device regression for library edge autoscroll fails.

Test:

```
ProviderDragBehaviorTest.libraryAutoscrollCrossesFolderBoundaryAndCancellationRestoresGridOrder
```

Current APK result on the Android 11 Star 4 device:

```
java.lang.AssertionError:
Library must scroll through folder members while the finger stays at the edge:
before=0.0 after=0.0
```

The pointer is moved to the lower edge and held there while the Compose test clock advances, but the provider surface remains at scroll position `0.0`.

### Why this blocks completion

`PLANS.md` explicitly requires provider dragging, positioning, move targeting, and **edge autoscroll** to use Calvin across folder/root boundaries.

The migration is structurally in place, but the grid device test does not demonstrate the required behavior. A production bug versus an input/clock issue in the test has not yet been isolated.

### Required follow-up

Investigate why Calvin's edge autoscroll is not activating for the flattened provider grid.

Check at least:

- Whether the drag pointer coordinates received by the reorder state are relative to the correct lazy-grid viewport.
- Whether adding/removing/folding cells during `onMove` invalidates the active dragged item or its scroll target.
- Whether `ScrollMoveMode.INSERT` interacts badly with the root start/end sentinel cells.
- Whether the dynamically changing `displayCells` list interferes with the library's active drag/autoscroll state.
- Whether the dragged provider remains keyed and visible while folder membership changes.
- Whether test-clock-driven frames are sufficient for the library's autoscroll coroutine.

Validate both grid and list modes after the fix.

The later diagnostic test explicitly waits for the lifted drag marker before moving to the edge, allows real coroutine time alongside Compose frames, checks that the marker survives edge movement, and always cancels the gesture in `finally`. Two attempts with that diagnostic test ended with `Process crashed` before producing an assertion result; they do not supersede the earlier completed failure or establish that the revised timing passes.

---

## 2. Provider list-mode autoscroll is not yet validated

### Status

**Blocked by device process termination**

The corresponding test is:

```
ProviderDragBehaviorTest.libraryAutoscrollCrossesFolderBoundaryAndCancellationRestoresListOrder
```

On the current APK, Android's low-memory killer terminated the app process before the test produced an assertion result.

The log shows:

```
lowmemorykiller: Kill 'org.starfall.multigateway' ... reason: device is not responding
```

Therefore list-mode autoscroll must currently be treated as **unverified**, not passed and not failed.

---

## 3. Lifted provider rendering/tracking is not yet validated on the current APK

### Status

**Blocked by device process termination**

The current regression test:

```
ProviderDragBehaviorTest.liftedProviderRemainsVisibleAndTracksFingerOutsideFolderInGrid
```

was started against the rebuilt current APK, but Android's low-memory killer terminated the app before the test returned an assertion.

Older runs produced lifted-card assertions, but those runs occurred while provider drag code was changing and must not be used as evidence for the current implementation.

The current implementation must therefore still be validated for:

- Dragged provider remains visibly rendered outside its original folder region.
- Dragged provider center follows the finger.
- Grid/list transitions do not clip the dragged item.
- Cancel returns the provider exactly to its previous placement.
- No placement write occurs on cancellation.

---

## 4. Device instrumentation acceptance is still incomplete

### Status

**Not passed**

The current source passes build/unit validation:

```sh
ANDROID_HOME=/opt/android-sdk ./gradlew   testDebugUnitTest   compileDebugAndroidTestKotlin   assembleDebug
```

Result:

```
BUILD SUCCESSFUL
```

The current app and instrumentation APKs also build and install successfully.

A focused bottom-sheet system-insets regression was executed successfully on the current APK:

```
BottomSheetInsetsTest.expandedSheetStopsBelowStatusBarAndAboveNavigationBar
```

Result:

```
OK (1 test)
```

So the earlier claim that this bottom-sheet inset behavior was currently failing is no longer valid.

However, the complete instrumentation acceptance cannot pass yet because:

1. Provider grid edge autoscroll has a confirmed assertion failure.
2. Other isolated provider tests are repeatedly terminated by Android's low-memory killer.
3. The full `connectedDebugAndroidTest` run has not completed successfully.

### Device condition

The Android 11 Star 4 device is under severe system pressure during these runs.

The low-memory killer is terminating multiple unrelated processes, including:

- Google Play services.
- Gboard.
- Play Store.
- Launchers.
- The MultiGateway instrumentation process.

This means a `Process crashed` result by itself must not be treated as an app crash unless an app exception/assertion appears before the termination.

At least one failure is independent of this device-pressure problem: the grid edge-autoscroll assertion above completed normally and failed with `before=0.0 after=0.0`.

---

## 5. Model picker: viewport and startup performance still need acceptance

**Code changes present; device behavior/performance not yet fully verified.**

`ModelPickerSheet.kt` now keeps its lazy-list state and applies the selected-model opening position once. Folder/provider toggles and later dynamic discovery should not recreate that state. Ollama discovery has disk caching, parallel requests, request coalescing and stale-cache fallback; startup maintenance runs on IO.

Remaining checks:

- Run `ModelPickerToggleTest.togglingBranchesKeepsTheViewportInsteadOfReturningToTheSelectedModel` successfully on the current APK.
- Run `ModelPickerToggleTest.lateDynamicDiscoveryAppearsWithoutReopeningThePicker` successfully.
- Exercise opening/closing folders and providers after scrolling away from the selected model, including when discovery updates arrive.
- Measure time to show the model list after cold app start, with fresh cache, expired cache and unavailable providers. A reported MainActivity display time of **16.860 seconds** was observed on the pressured debug device; this does not isolate model discovery time and is not a before/after benchmark.

The originally reported slow loading and viewport jump must not be marked accepted based only on code inspection or unit tests.

---

## 6. Continuous list-to-sheet gesture and scroll-lock regressions remain partly unverified

**One targeted gesture test passed; the complete requirement is not yet accepted.**

`BottomSheetScrollBehaviorTest.draggingDownAtListStartMovesTheSheet` passed in an isolated earlier run. The expanded-sheet system-insets test also passed. Neither proves the remaining continuous-scroll cases.

Still require successful device results:

- `theSameDragContinuesIntoTheSheetAfterReachingListStart`: reach the list start and continue moving the sheet within the same gesture.
- `draggingDownInTheMiddleScrollsTheListBeforeMovingTheSheet`: consume list scrolling before handing unused motion to the sheet.
- `repeatedDownwardDragsReachTheStartWithoutAReverseDrag`: reach the exact start without a reverse swipe and move the sheet immediately.
- `fetchModelsKeepsScrollingTowardTheEndWithoutAReverseSwipe`: avoid the corresponding lock at the list end.
- Intermediate sheet heights, short/tall content, IME opening/closing and both navigation modes from `PLANS.md`.

---

## 7. Test infrastructure and remaining integration acceptance

**Incomplete validation, with a known outdated test assumption.**

The existing `ModelPickerToggleTest.selectedModelSurvivesCollapsedParentsAndReasoningCanBeDisabledIndependently` still expects the `reasoning-enabled` UI tag. That tag is absent from current production UI. The test must be aligned with the current reasoning controls while retaining meaningful behavioral assertions; this mismatch is not evidence of a runtime reasoning bug.

Several older provider instrumentation tests also assume the previous whole-folder/floating-overlay layout. Review their coordinates and assertions against the Calvin layout before treating their results as current product regressions.

The optional `MigrationUiTestRunner` avoids production startup for synthetic UI tests. Passing those tests would still not validate production initialization or real provider persistence. The default runner and manual acceptance must cover real app startup, persistence after drop, navigation/system bars/IME, icon resolution, streaming Markdown, media playback/audio focus and supported live OAuth flows.

Known execution interference:

- Android's low-memory killer and input-dispatch ANR interrupted runs on the Android 11 Star 4 device.
- A concurrent APK installation interrupted at least one instrumentation run (`installPackageLI` in ActivityManager logs).
- Two later grid diagnostics ended with `Process crashed` before returning an assertion. Their root cause was not independently established; do not label every such result as a confirmed memory kill.
- The original Gradle UTP setup requested APK uninstallation after testing. `android.injected.androidTest.leaveApksInstalledAfterRun=true` has now been configured; preservation after a complete future Gradle run still needs verification. Direct ADB replacement installs are used for focused runs.

No full `connectedDebugAndroidTest` pass has been recorded for this revision. Execute tests without simultaneous deployment and retain assertion/crash/system logs so infrastructure interruptions are distinguished from app failures.

---

# Items from the previous Problem.md that are now resolved

## Provider drag architecture

**Resolved architecturally**

The previous custom `ProviderDrag.kt` pointer engine, floating overlay implementation, and custom provider drag gesture have been removed.

Calvin now owns the physical drag lifecycle on one keyed lazy surface.

`ProviderDragLayout` remains, but it now represents business-level membership/order transformation rather than a second gesture engine. This is allowed by `PLANS.md`.

## Bottom-sheet compatibility adapter

**Accepted by the current plan**

`PLANS.md` now explicitly permits the bounded content-height/drag-handle adapter because arbitrary intermediate sheet height is a product requirement.

Material 3 still exclusively owns:

- Dialog/window.
- Insets.
- Scrim.
- Sheet lifecycle.

The app-owned layout/resize adapter is therefore no longer a migration-completion violation by itself.

The targeted expanded-sheet inset test passes on the current device build.

## OAuth compatibility paths

**Resolved architecturally**

The current implementation clearly separates standard AppAuth execution from compatibility paths.

Standard flows use:

- `StandardAppAuthAuthorization`.
- `AuthorizationService.performAuthorizationRequest`.
- `AuthorizationService.performTokenRequest`.
- AppAuth client authentication and response parsing.

Explicit compatibility code remains for documented cases such as:

- Existing HTTP loopback registrations.
- JSON token bodies.
- Cleartext/local MCP token endpoints.
- Device-code provider protocols.
- MCP discovery/DCR/resource semantics.

This matches the revised completion criteria in `PLANS.md`.

## Documentation completion definition

**Resolved**

`PLANS.md` and `docs/LIBRARY_MIGRATION.md` now both use the same rule:

> Old generic subsystem engines must be removed, while narrowly documented compatibility adapters may remain when required by product/protocol behavior that the selected library cannot reproduce.

The documentation also no longer claims device acceptance merely because instrumentation compiled.

---

# Current conclusion

The migration is **architecturally almost complete**, but it cannot yet be marked fully accepted.

The concrete remaining blocker found in this audit is:

1. **Calvin provider edge autoscroll does not activate in the tested grid cross-folder scenario.**

The following still require successful device validation:

2. Provider list-mode edge autoscroll.
3. Lifted provider rendering/tracking.
4. Provider cancellation/rollback during long edge scrolling.
5. Model-picker viewport stability and controlled startup/model-list timing.
6. Continuous list-to-sheet handoff and scrolling without a reverse swipe.
7. The remaining instrumentation suite and manual acceptance cases listed in `PLANS.md`.

Bottom-sheet architecture and OAuth compatibility separation are no longer architectural blockers. This does not establish full device acceptance for sheet gestures, Coil, Markdown, Media3 or live OAuth flows; the checks above remain outstanding.
