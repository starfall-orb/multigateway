# Future Plans

Updated: 2026-10-04

This document tracks deferred features and remaining engineering work. Unchecked items are planned work, not implemented functionality.

## Folder Logo as a Chat Background

**Status: Deferred at the user's request.**

- [ ] Add a toggle in the folder editor to use the folder's custom logo as the chat background when the selected model belongs to that folder (through its provider).
- [ ] Enable this feature only for folders with an explicitly configured custom logo. Automatically matched logos, generated initials and default folder icons must not qualify.
- [ ] Persist the per-folder preference and resolve the selected model's folder when the user changes models.
- [ ] When switching from a model outside an enabled folder to a model inside it, gradually reveal the folder logo with a smooth, cinematic transition.
- [ ] Fade out the background when switching to an ungrouped model, a disabled folder, or a folder without a custom logo. Crossfade when switching between eligible folders.
- [ ] Preserve readable messages, reasoning blocks, file controls and the composer in light, dark and AMOLED themes. Respect system animation settings and avoid restarting transitions during unrelated recompositions.
- [ ] Handle logo replacement/removal, folder deletion and provider reassignment without stale backgrounds.
- [ ] Verify eligibility, persistence, model switching and transitions on device before considering this feature complete.

## Remaining Responsibility Refactoring

The provider editor/catalog/connection tests, MCP UI sections, Markdown parsing/rendering, speech coordination and conversation summarization already have extracted boundaries. Continue with the remaining responsibilities:

- [ ] Narrow `ui/chat/ChatViewModel.kt` dependencies and extract conversation mutation/persistence responsibilities into focused coordinators or use cases.
- [ ] Split `ui/settings/SettingsScreen.kt` into category/navigation, preferences, application-data management, and update/download presentation.
- [ ] Separate the speech-service editor from list/navigation in `ui/speech/SpeechScreen.kt`.
- [ ] Separate provider-specific request/stream handlers in `data/service/OfficialLlmSdk.kt`; retain the existing attachment conversion boundary.
- [ ] Separate MCP OAuth discovery, authorization/callback handling and token exchange in `data/service/McpOAuthService.kt`; retain the existing encrypted token store.
- [ ] Separate Codex authorization, model discovery, request preparation and streaming normalization in `data/adapter/codex/OpenAICodexAdapter.kt`; retain the existing token parser.
- [ ] If provider/group navigation continues to grow, extract its card/dialog and drag/drop responsibilities while preserving mixed root ordering and folder membership behavior.

Verify each extraction with relevant existing tests and builds before marking it complete. Prefer clear responsibility boundaries over reducing line counts alone.

## Device and Integration Verification

- [ ] Complete a broader device regression pass for provider authentication, catalog selection, folder drag/drop, chat attachments and generated media. Run cases individually when the device's low-memory behavior interrupts combined instrumentation runs.
- [ ] Add repeatable real-video fixtures for portrait, landscape, rotated and unusually narrow videos. The current portrait regression uses a recording prepared on the connected device.
- [ ] Extend playback verification to fullscreen entry/exit, position preservation, seeking, pause/resume and lifecycle changes while keeping chat scrolling responsive.
- [ ] Verify half-height and expanded chat sheets with the keyboard visible, both navigation modes, different screen sizes and accessibility font scaling.
- [ ] Rerun the previously deferred live Helix/provider integration checks when the required endpoint and credentials are available.

Recent targeted device checks passed for portrait-video controls/chat scrolling, model-picker top overscroll, the add sheet's half-height/expansion and Tools Manage ordering, Streaming/Passback Thinking, and provider initials/model badges. These checks do not replace the broader regression pass.

## UI Consistency and Localization

- [ ] Move remaining hardcoded visible/accessibility labels in chat action sheets, Tools Manage and model capability badges into English/Vietnamese resources.
- [ ] Continue shortening configuration labels while preserving clear accessibility descriptions and consistent Material typography, spacing and icon sizes.
- [ ] Verify Tools Manage with many MCP servers, empty configuration and large font sizes; ensure all controls remain reachable in the half-height and expanded states.

## Release and Update Delivery

- [ ] Verify signed Codemagic release output and GitHub assets for version `1.1.0`: universal `multifgateway.apk` plus ABI-specific `multifgateway-<abi>.apk` files and matching checksums.
- [ ] Ensure the in-app updater selects a compatible ABI-specific APK, with the universal APK as a fallback, when a release contains multiple APK assets.
- [ ] Confirm release metadata, version checks and checksum links remain correct after artifact renaming.
- [ ] Review and publish the accumulated changes when requested; previous trackers' historical push/install blockers should not be treated as current environment status.

## Context Accounting

- [ ] Evaluate provider-aware token counting if more precise context limits become necessary. Current character-based estimates should continue to be clearly identified as estimates until an accurate implementation exists.
