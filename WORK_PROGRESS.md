# MultiGateway – Work Progress

Updated: 2026-10-03

## Scope

Track and complete the unfinished work currently present in the local working tree.

## Status

- [x] Icon cache lifecycle / orphan image cleanup
- [x] Reasoning fallback consistency (`Auto` vs `Off`)
- [x] Remove remaining hardcoded UI strings in the touched Provider / MCP / model flows
  - [x] Model editor
  - [x] Provider screens
  - [x] MCP screens
- [x] Review uncommitted feature batch for incomplete wiring
- [x] Fix Antigravity Claude reasoning budget wiring
- [x] Bump app version to `1.0.3` / versionCode `13`
- [x] Run final unit test suite
- [x] Run final Android test compilation
- [x] Run lint
- [x] Run debug build
- [x] Final git diff/status review

## Notes

- Current branch: `main`
- Local working tree contains the completed feature/fix batch and is not committed yet.
- Icon cleanup is reference-driven: startup pruning keeps images referenced by Provider, Model, MCP or shared matching rules and removes orphaned imported PNGs / stale import temp files.
- Unknown conversation reasoning values now fall back to `Auto`, matching the model picker instead of silently selecting `Off`.
- Provider, Model editor and MCP visible/accessibility strings now use Android resources with English as the source language and Vietnamese translations. Technical example values remain literal.
- Review found an additional wiring bug in Antigravity Claude requests: reasoning was enabled per selected effort but the thinking budget stayed hardcoded at 1024. It now uses the shared `reasoningBudget()` mapping and token cap.
- Runtime instrumentation cannot be started in this ModelScope environment because the ADB server reports a protocol fault; the Android test APK compiles successfully.

## Validation

- `testDebugUnitTest` — PASS
- `assembleDebugAndroidTest` — PASS
- `lintDebug` — PASS
- `assembleDebug` — PASS
- `git diff --check` — PASS
- Provider/Model/MCP visible hardcode scan — only technical OAuth example placeholders remain

## Change log

- 2026-10-03: Created this progress tracker and started cleanup work.
- 2026-10-03: Added safe orphan icon pruning and coverage for retained rule/entity references.
- 2026-10-03: Unified unknown reasoning fallback to `Auto`.
- 2026-10-03: Migrated Provider and Model editor UI strings to resources; compile check passed.
- 2026-10-03: Migrated MCP UI strings to resources; compile check passed.
- 2026-10-03: Fixed Antigravity Claude reasoning budget wiring and added budget mapping coverage.
- 2026-10-03: Final unit tests, Android test compilation, lint and debug build passed.
- 2026-10-03: Bumped app version to `1.0.3` (versionCode `13`) and rebuilt successfully.

---

## Provider Groups / Model Picker Tree

- [x] Add persistent Provider Group model + Room schema/migration
- [x] Add repository/ViewModel operations for create, rename, delete and move Provider to group
- [x] Providers screen: collapsible group tree + ungrouped section
- [x] Providers screen: group management and move Provider between groups
- [x] Chat model picker: collapsible tree (group → provider → model)
- [x] Keep search useful by automatically exposing matching branches
- [x] Add/adjust tests
- [x] Run unit tests, Android test compilation, lint and debug build

### Change log

- 2026-10-03: Started Provider Groups / model-picker tree work.
- 2026-10-03: Added Room v6 Provider Group persistence, migration, repository and ViewModel operations.
- 2026-10-03: Added collapsible Provider groups, rename/delete/move-to-group UI, and per-section Provider reordering.
- 2026-10-03: Reworked chat model picker into a collapsible group → provider → model tree; search/filter forces matching branches open.
- 2026-10-03: Added ProviderGroupTest covering persistence, delete→ungroup behavior, group/provider collapse, and search-driven expansion.
- 2026-10-03: Provider Groups validation passed: unit tests, Android test compilation, lint, debug build and diff check.
### Collapse state persistence follow-up

- [x] Persist Providers page collapsed sections
- [x] Persist model picker collapsed provider groups
- [x] Persist model picker collapsed providers
- [x] Wire persisted state through MainScreen → ProviderScreen / Chat model picker
- [x] Add tests and rerun validation

- 2026-10-03: Started persistence follow-up so collapse/expand state survives navigation and app restarts.
- 2026-10-03: Added DataStore-backed collapse state for Providers sections and model-picker group/provider nodes.
- 2026-10-03: Added repository-instance persistence coverage and cleanup of stale collapsed IDs when groups/providers are deleted.
- 2026-10-03: Persistence validation passed: full unit tests, Android test compilation, lint, debug build and diff check.

### Bottom sheet system-bar inset fix

- [x] Fix shared AppBottomSheet so the sheet surface only avoids the status bar at the top
- [x] Remove navigation-bar bottom inset that lifts all sheets above the navbar
- [x] Verify every bottom sheet still uses AppBottomSheet
- [x] Run compile/tests/lint/build

- 2026-10-03: Started correcting the shared bottom-sheet inset behavior.
- 2026-10-03: AppBottomSheet now caps only against the status bar/display cutout and overrides Material3's default safeDrawing.bottom inset so the surface can extend through the navigation-bar area.
- 2026-10-03: Removed per-sheet navigationBarsPadding from chat/tool bottom sheets; all modal sheets still route through AppBottomSheet.
- 2026-10-03: Validation passed: compileDebugKotlin, testDebugUnitTest, assembleDebugAndroidTest, lintDebug, assembleDebug and git diff --check.

### Release 1.0.4

- [x] Bump version to `1.0.4` / versionCode `14`
- [x] Final build validation
- [x] Commit completed work
- [x] Push `main` to `origin`

- 2026-10-03: Started release pass for 1.0.4.
- 2026-10-03: Release validation passed: testDebugUnitTest, assembleDebug and git diff --check.

### Provider root drag/drop refinement

- [x] Remove the rendered Ungrouped pseudo-section from Providers
- [x] Make Provider Group a root item at the same hierarchy level as ungrouped Providers
- [x] Persist one shared root order across groups and ungrouped Providers
- [x] Expand a group by inserting its Providers immediately after the group item
- [x] Render expanded group contents inside a distinct bordered/background container
- [x] Drag root Provider onto a group to move it into that group
- [x] Drag grouped Provider onto another group to move between groups
- [x] Keep drag reorder for Providers inside an expanded group
- [x] Add persistence tests for mixed root order and group membership moves
- [x] Final release validation, commit and push 1.0.4

- 2026-10-03: Reworked Providers root into mixed group/provider items; expanded group children are inserted directly after their group inside a distinct container.
- 2026-10-03: Added drop-target handling so Provider cards can be long-pressed and dropped onto group cards/expanded group regions.
- 2026-10-03: Added repository persistence for shared root ordering and tested group membership moves.

- 2026-10-03: Final 1.0.4 validation passed: testDebugUnitTest, assembleDebugAndroidTest, lintDebug, assembleDebug and git diff --check.

### Software Update button visibility

- [x] Hide APK download/update actions when the installed version is already up to date
- [x] Compile/build validation

- 2026-10-03: Update screen now only renders download/release actions when `updateAvailable` is true.
- 2026-10-03: compileDebugKotlin, assembleDebug and git diff --check passed.

### Provider Group visual redesign

- [x] Collapsed group card matches Provider card dimensions in grid/list and shows only icon + name
- [x] Add persistent custom icon to Provider Group with shared icon matching/cache behavior
- [x] Expanded group becomes one full-width bordered container with header (name + overflow menu)
- [x] Expanded body places group icon in the leading cell/area and Providers in distinct child slots
- [x] Update model-picker group icon to use the same shared icon system
- [x] Add Room migration/schema/tests and run validation

- 2026-10-03: Started redesign from annotated Provider screenshots.
- 2026-10-03: Collapsed groups now use the same fixed list/grid dimensions as Provider cards and show only icon + name.
- 2026-10-03: Expanded groups now render as one full-width bordered container: left-fixed overflow menu + group name header, group icon tile, then Provider child slots.
- 2026-10-03: Provider Groups gained persistent custom icons via Room schema v7; shared icon cache/matching and model-picker group icons use the same system as Provider/MCP.
- 2026-10-03: Validation passed: compileDebugKotlin, testDebugUnitTest, assembleDebugAndroidTest, lintDebug, assembleDebug and git diff --check.

### Bottom sheet inset correction from device screenshot

- [x] Compensate ModalBottomSheet surface position by navigation-bar bottom inset
- [x] Keep expanded top edge capped at fullWindow - status/status-cutout inset
- [x] Configure the actual Material3 dialog window edge-to-edge via DialogWindowProvider
- [x] Force dialog status/navigation bars transparent and disable contrast scrims
- [x] Final tests/lint/build validation

- 2026-10-03: Device screenshot confirmed Material3 sheet anchor ended above 3-button navbar while max height used full-window height, shifting the expanded top behind the status bar. Fixed both at the shared AppBottomSheet layer.

### Version 1.0.5

- [x] Bump app version to `1.0.5` / versionCode `15`

- 2026-10-03: Increased app version after Provider Group and bottom-sheet fixes.
