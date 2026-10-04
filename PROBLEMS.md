# Code Problems

## God file / God class survey

**Status:** Partially addressed; refactoring has resumed at the user's request. Completed boundaries are checked off below after verification. This is a maintainability finding, not necessarily an immediate runtime bug. The survey counts below describe the original assessment, not the current files.

### Refactoring progress

Implemented responsibility boundaries:

- [x] ~~Provider connection/authentication fields and labels~~: `ui/providers/ProviderConnectionFields.kt`.
- [x] ~~Provider model discovery, filtering, metadata and bulk selection~~: `ui/providers/ProviderModelCatalogSheet.kt`.
- [x] ~~Provider model connection-test orchestration and dialog~~: `ui/providers/ModelConnectionTests.kt` and `ModelConnectionDialog.kt`; verified duplicate suppression, four-request concurrency limit and cancellation handling.
- [x] ~~MCP tool list, enablement controls and parameter presentation~~: `ui/mcp/McpToolsTab.kt`.
- [x] ~~MCP list/editor/connection/OAuth/server-card separation~~: `ui/mcp/McpScreen.kt`, `McpServerEditor.kt`, `McpConnectionSettings.kt`, `McpOAuthFields.kt` and `McpServerCard.kt`.
- [x] ~~Markdown block model and pure parser; code and LaTeX presentation~~: `ui/chat/MarkdownParser.kt`, `MarkdownCodeBlock.kt` and `MarkdownLatexBlock.kt`.
- [x] ~~Inline Markdown parsing and link presentation~~: `ui/chat/MarkdownInlineText.kt`; verified link annotation offsets, formatting and unfinished streaming tokens.
- [x] ~~Speech playback/cancellation and conversation summarization/chunk merging/persistence~~: `ui/chat/ChatSpeechCoordinator.kt` and `ConversationSummaryCoordinator.kt`. Message generation/queue handling already has a `ChatGeneration.kt` coordinator.
- [x] ~~GitHub release fetching and release metadata~~: `data/service/GitHubReleaseService.kt`.
- [x] ~~SDK attachment conversion and Google media upload/polling~~: `data/service/SdkAttachmentContent.kt`.
- [x] ~~Encrypted MCP OAuth token storage and token state~~: `data/service/McpOAuthTokenStore.kt`.
- [x] ~~Codex token response/JWT claim parsing~~: `data/adapter/codex/CodexTokenParser.kt`.

Remaining work before closing this finding:

- [ ] Separate provider list/group presentation from the provider editor.
- [x] ~~Separate MCP server list, editor/basic settings and authentication/transport presentation~~.
- [x] ~~Separate inline Markdown parsing/link presentation from Compose block rendering~~.
- [ ] Further narrow `ChatViewModel` dependencies and conversation mutation responsibilities.
- [ ] Split settings category/update/download UI and the speech-service editor from their list/navigation screens.
- [ ] Separate SDK provider stream handlers, MCP OAuth discovery/token exchange, and Codex authorization/request/streaming boundaries.

The finding remains open: the extractions reduce coupling, but do not yet complete all of the recommended splits.

### High priority

- `app/src/main/java/org/starfall/multigateway/ui/providers/ProviderScreen.kt` — **1,865 lines**. It combines provider/group list state, drag-and-drop ordering, group cards, menus and dialogs, provider editing, authentication UI, model configuration, and model catalog loading. This is the clearest God file in the project.
- [x] ~~`app/src/main/java/org/starfall/multigateway/ui/mcp/McpScreen.kt` — originally **1,181 lines**, combining server lists, editing, connection/authentication/transport settings, tool discovery and tool rows~~. These responsibilities now have separate UI modules; the main screen only coordinates the server list and navigation.
- [x] ~~`app/src/main/java/org/starfall/multigateway/ui/chat/MarkdownRenderer.kt` — originally **1,075 lines**, combining parsing, block models, inline links, code, LaTeX and Compose rendering~~. Parsing/model, inline text, code and LaTeX boundaries are now separated; the main file handles Compose block composition and streaming presentation.
- `app/src/main/java/org/starfall/multigateway/ui/chat/ChatViewModel.kt` — **815 lines**. It coordinates conversations, providers, MCP, preferences, tool settings, message generation, queueing, summaries, context-window checks, speech/TTS, file attachments, and persistence. The class also has a very large dependency list.

### Medium priority

- `app/src/main/java/org/starfall/multigateway/ui/settings/SettingsScreen.kt` — **705 lines**. It contains navigation/category rendering, appearance settings, preferences, user-data management, update checking/download handling, and about UI.
- `app/src/main/java/org/starfall/multigateway/data/service/OfficialLlmSdk.kt` — **636 lines**. It centralizes SDK integration and request/response handling for multiple providers, including streaming, attachments, tool calls, and error handling.
- `app/src/main/java/org/starfall/multigateway/ui/speech/SpeechScreen.kt` — **619 lines**. It combines speech-service list management, editor state, provider/model selection, validation, and screen UI.
- `app/src/main/java/org/starfall/multigateway/data/service/McpOAuthService.kt` — **573 lines**. It combines OAuth token persistence, discovery, authorization flow, callback handling, and MCP HTTP/session concerns.
- `app/src/main/java/org/starfall/multigateway/data/adapter/codex/OpenAICodexAdapter.kt` — **538 lines**. It combines OAuth authorization, token handling, model discovery, request preparation, streaming, and provider-specific response normalization.

### Recommended refactoring order

1. Split `ProviderScreen.kt` into provider list/group UI, provider editor, model editor/catalog, and provider authentication components.
2. Split `McpScreen.kt` into server list, server editor/basic settings, transport/auth settings, and tool management components.
3. Separate `MarkdownRenderer.kt` into parser/model, Compose block renderers, and LaTeX/code rendering.
4. Reduce `ChatViewModel.kt` responsibilities by extracting message queue/generation coordination, conversation summary logic, and speech actions into dedicated coordinators or use cases.
5. Split `OfficialLlmSdk.kt` and `McpOAuthService.kt` by provider/protocol boundary after the UI files are addressed.

### Survey method

The assessment used source-file size, line count, number of top-level/private functions, dependency breadth, and the number of distinct responsibilities. There is no universal line-count threshold for a God file; the architectural responsibility split is the deciding factor.

### Verification checkpoint

- Debug APK and Android-test APK build successfully.
- The complete debug unit-test suite passes, including tool/file delivery, content-api, Markdown and model-connection tests.
- `git diff --check` passes.
- Recent device reruns are not confirmed: ADB test-APK installation/instrumentation commands timed out. Earlier provider Base URL/auth controls passed their device test before the later media changes.
