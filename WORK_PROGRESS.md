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

### Bottom sheet content safe-area correction

- [x] Keep sheet surface edge-to-edge behind the navigation bar
- [x] Restore navigation-bar bottom inset for sheet content only
- [x] Re-run compile/tests/lint/build

- 2026-10-03: Restored `WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)` as `contentWindowInsets` in shared AppBottomSheet so controls stay above the navbar while the sheet background still reaches the window bottom.

### Provider editor cleanup

- [x] Allow saving Provider with an empty Base URL
- [x] Keep URL validation only when Base URL is non-empty
- [x] Remove Custom Headers description text
- [x] Fix new Provider IDs to use a unique UUID instead of a literal interpolated string
- [x] Move expanded Provider Group overflow menu to the right and reduce header height
- [ ] Run validation and reinstall on ADB

- 2026-10-03: Found the one-provider bug in the device DB: every new Provider used the literal primary key `custom_${System.currentTimeMillis()}`. Replaced it with a UUID-based ID and added regression coverage.

### Provider Group icon interaction

- [x] Tapping the group icon while a group is expanded collapses that group

- 2026-10-03: Wired the expanded group icon tile to the same collapse action as the group header.

### Bottom sheet anchor and content safe area

- [x] Keep Material3 surface constraints at the full dialog height
- [x] Limit content height below the status bar, including the measured drag handle and IME inset
- [x] Apply consumed safe-area padding to content only; keep the surface behind the navbar
- [x] Verify short/expanded sheets and IME insets with three device instrumentation tests
- [x] Verify the actual navbar pixels match the sheet color
- [x] Verify Model Picker with Gboard manually on the connected Android 11 device
- [x] Build debug APK/test APK, lint (0 errors), and check the diff

- 2026-10-03: The surface-level `heightIn` reduced the constraints Material3 uses for its bottom anchor, leaving a 60px scrim-colored band at the bottom and placing a tall sheet behind the status bar. Moved the height cap into the content and removed manual navigation-bar padding in favor of consumed safeDrawing insets. The installed debug build now fills the area behind the 3-button navbar while controls remain above it.

### Provider folder drag and drop

- [x] Drag a provider outside its expanded folder to clear folder membership
- [x] Drop on an open/closed folder to add membership without moving the folder during the drag
- [x] Transfer between folders and preserve provider reordering on drop
- [x] Draw the dragged provider above folder surfaces so it remains visible outside the folder
- [x] Fix interpolated root item keys for multiple providers/folders
- [x] Verify 11 gesture cases on Android 11 in list/grid layouts, 4 ProviderGroup unit tests, debug/test APK builds, lint (0 errors), and diff checks
- [x] Install the debug build on the connected device

- 2026-10-03: Provider drags now resolve folder membership before reordering when released. Moving outside all folders clears membership; cancellation makes no changes. The device low-memory killer interrupted the first instrumentation batch after six passing cases; the five remaining cases passed in a separate batch.

### Unified General Settings

- [x] Merge Menu and Settings into General Settings with Configuration and System Settings sections
- [x] Move Software Update into About with an explicit check-for-updates action
- [x] Share rounded cards, section headings, icon tiles, spacing and colors across settings pages and Default Models
- [x] Replace chat toolbar icons with a left menu and right settings gear; remove the duplicate sidebar Menu entry
- [x] Preserve General Settings in the back stack when opening Configuration pages; redirect old saved Menu routes
- [x] Build debug/test APKs, pass four GeneralSettings device tests, check the diff, and install the debug APK

- 2026-10-03: The four device tests cover all Configuration callbacks, About/update placement and back navigation, a single preference-toggle callback, and both chat toolbar actions. Existing provider dragging and bottom-sheet safe-area changes are preserved.

### Model picker collapse/expand all

- [x] Remove the provider filter bar from the model picker bottom sheet
- [x] Add a 48dp collapse/expand-all button to the left of model search
- [x] Persist collapse state for every folder and provider, including nested providers
- [x] Let manual collapse override automatic expansion of search results
- [x] Verify four ProviderGroup unit tests and a device test covering nested/root providers, both toggle directions, persistence callbacks and active search
- [x] Build debug/test APKs, check the diff and install the latest debug APK on the connected phone

- 2026-10-03: The global button collapses all sections when any section is open, then expands all on the next tap. Search still opens matching sections automatically when its query changes, while the global and individual toggles remain effective during search.

- 2026-10-03: Renamed the System Settings entry Data & Storage to App Data, with a description of conversation history/application reset. Configuration retains the single Storage entry for generated tool files. Removed the unsupported backup claim from the description.

### Theme consistency, monochrome preset, Content API MCP, and final audit

- [x] Replace remaining fixed UI/media colors with Material color roles
- [x] Complete preset color roles used by dialogs, sheets, containers, outlines, inverse content and errors
- [x] Add a shared monochrome preset and responsive, accessible palette selector
- [x] Preserve the selected/dynamic accent palette when AMOLED replaces dark surfaces with black
- [x] Add the removable Content API MCP preset with a hidden default endpoint
- [x] Resolve Content API names (`Content API`, `contentapi`, `content-api`) case-insensitively while preserving explicit URLs
- [x] Add a separate one-time migration marker so both new and existing installations receive the preset without recreating it after deletion
- [x] Add endpoint/name/explicit-URL regression coverage
- [x] Audit production sources for test credentials and remove the temporary unfinished-task tracker
- [x] Document token counting as an estimate rather than a provider-specific tokenizer
- [ ] Device-only Helix provider instrumentation rerun (environment unavailable: Android SDK and ADB are not installed)
- [ ] Install the final APK on a device (environment unavailable: Android SDK and ADB are not installed)

- 2026-10-03: Centralized palette previews in `Theme.kt`, added monochrome light/dark schemes, filled Material color roles, and changed AMOLED into a surface-only transformation that retains the active palette.
- 2026-10-03: Media preview backgrounds, playback/error content, and selection marks now use theme roles instead of fixed black/white values.
- 2026-10-03: Added Content API endpoint resolution and a dedicated one-time initializer marker. The stored preset URL remains empty, user-entered URLs take priority, and deletion is permanent.
- 2026-10-03: Added unit coverage for all supported Content API spellings, hidden default resolution, unrelated names, and explicit URL priority.
- 2026-10-03: Production credential scan found no test API key or test provider credential. The only new endpoint is the requested Content API preset constant.
- 2026-10-03: Build/test/lint and device validation could not run in this container because no Android SDK or ADB executable is installed. `git diff --check` passed.
- 2026-10-03: Token/context figures remain deliberately documented in the UI and implementation as estimates based on character heuristics; exact counts vary by provider tokenizer. Existing context-window unit coverage remains in the tree.

### Version 1.0.6

- [x] Bump app version to `1.0.6` / versionCode `16`
- [x] Commit the release metadata update
- [ ] Push the completed changes to GitHub (blocked in this container: the HTTPS CONNECT tunnel returns 403)

- 2026-10-03: Increased the application version for the theme and Content API MCP release. The local `work` branch is ready, but the GitHub push is blocked by this container's HTTPS CONNECT tunnel (HTTP 403).
