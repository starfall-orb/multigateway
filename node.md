# Model discovery notes

Mục tiêu của phần model discovery là **không hardcode danh sách model nếu upstream có API để trả về model mà chính tài khoản/credential hiện tại được phép dùng**.

Các request discovery phải luôn dùng đúng credential/account đang được chọn trong provider. Nếu provider hỗ trợ nhiều OAuth account thì đổi account phải đổi luôn kết quả model discovery/cache tương ứng. Cache model phải được key theo ít nhất `providerId + oauthCredentialId/accountId`.

## Quy tắc chung

- Ưu tiên API/model catalog chính upstream đang dùng thay vì danh sách tĩnh trong app.
- Chỉ fallback sang danh sách bundled/hardcode khi upstream thật sự không có cách discovery động hoặc request discovery thất bại và cần giữ UX usable.
- Không trộn model của account A sang account B.
- Sau OAuth/re-OAuth/đổi account/xóa credential phải invalidate model catalog cache của account đó.
- Nếu endpoint có pagination thì phải tải hết trang trước khi hiển thị.
- Giữ metadata model khi upstream trả về được: display name, supported endpoints/protocol, reasoning efforts, visibility, quota, input modalities, v.v. Không chỉ giữ mỗi string id nếu metadata đó giúp route request đúng hơn.

## Provider types

### Chat Completions / OpenAI-compatible (`ProviderType.OPENAI`)

Model discovery chuẩn:

```http
GET {normalizedBaseUrl}/models
Authorization: Bearer <current provider credential>
```

- Dùng auth/header/query-param đúng theo provider config hiện tại.
- Parse OpenAI-compatible `data[].id`.
- Đây là discovery theo credential hiện tại; server tương thích OpenAI có thể lọc model theo API key/account.
- Nếu người dùng đặt `customListModelsUrl` thì URL custom có ưu tiên cao hơn URL suy ra từ base URL.

### OpenAI Responses (`ProviderType.OPENAI_RESPONSES`)

Dùng cùng model catalog chuẩn OpenAI:

```http
GET {normalizedBaseUrl}/models
Authorization: Bearer <current provider credential>
```

- Parse `data[].id`.
- Không hardcode model theo Responses API; model availability phải lấy từ account/API key hiện tại.

### Anthropic Messages (`ProviderType.ANTHROPIC`)

```http
GET https://api.anthropic.com/v1/models
x-api-key: <current credential>
anthropic-version: 2023-06-01
```

Với custom Anthropic-compatible base URL thì dùng `{base}/v1/models` sau khi normalize.

- Parse `data[].id`.
- Pagination: khi `has_more=true`, dùng `last_id` làm `after_id` cho trang sau.
- Kết quả phải theo credential hiện tại.

### Google Gemini (`ProviderType.GOOGLE`)

```http
GET https://generativelanguage.googleapis.com/v1beta/models
x-goog-api-key: <current API key>
```

Hoặc auth/query param theo cấu hình provider.

- Parse `models[].name`.
- Bỏ prefix `models/` trước khi lưu model id.
- Pagination bằng `pageToken` / `nextPageToken`.
- Không dùng một danh sách Gemini tĩnh cho mọi account/key.

### Ollama Cloud / Ollama-compatible (`ProviderType.OLLAMA`)

Ưu tiên endpoint OpenAI-compatible khi base hỗ trợ:

```http
GET {host}/v1/models
Authorization: Bearer <current credential, nếu cần>
```

Parse `data[].id`.

Với Ollama local/native có thể fallback:

```http
GET {host}/api/tags
```

Parse `models[].name`.

### GitHub Copilot (`ProviderType.GITHUB_COPILOT`)

Không hardcode. Dùng OAuth token của **Copilot account hiện tại**:

```http
GET https://api.githubcopilot.com/models
Authorization: Bearer <current GitHub/Copilot OAuth token>
```

- Parse `data[].id`.
- Đồng thời đọc `supported_endpoints` để quyết định protocol cho từng model:
  - `/v1/messages` -> Anthropic Messages.
  - `/responses` (không có `/chat/completions`) -> OpenAI Responses.
  - còn lại -> OpenAI Chat Completions.
- Model catalog có thể khác theo plan, org policy, feature flag và account. Vì vậy cache bắt buộc scope theo account.
- Khi đổi Copilot account phải refetch và xóa protocol metadata cache của account cũ.

### Claude Code (`ProviderType.CLAUDE_CODE`)

Dùng OAuth access token của **Claude account hiện tại** và Anthropic model catalog:

```http
GET https://api.anthropic.com/v1/models
Authorization: Bearer <current Claude Code OAuth access token>
anthropic-version: 2023-06-01
<same Claude Code OAuth/beta headers used by the adapter when required>
```

- Parse `data[].id`.
- Không dùng model list tĩnh.
- Kết quả phải scope theo OAuth account hiện tại.

### OpenAI Codex / ChatGPT account (`ProviderType.OPENAI_CODEX`)

**Không dùng `SUPPORTED_MODELS` làm nguồn chính.** Codex client có remote model catalog theo account:

```http
GET https://chatgpt.com/backend-api/codex/models?client_version=<compatible-client-version>
Authorization: Bearer <current ChatGPT OAuth access token>
Chatgpt-Account-Id: <current account id, nếu token có>
Originator: <same Codex originator used by generation>
```

- Response là Codex catalog với `models[]`; id chuẩn là `models[].slug`.
- Có thể lấy thêm `display_name`, `supported_reasoning_levels`, `default_reasoning_level`, `visibility`, `supported_in_api`, context window, service tiers, input modalities, v.v.
- Chỉ hiển thị model user-facing/selectable theo catalog (ví dụ `visibility == list`) và model hợp lệ cho account/client hiện tại.
- `Originator` và `client_version` có thể làm catalog/metadata khác nhau; discovery phải dùng cùng identity với request generation của Multigateway để tránh picker hiển thị catalog khác với runtime.
- Chỉ fallback về bundled `SUPPORTED_MODELS` khi remote catalog không đọc được. Fallback này không được ghi đè cache account thành nguồn "thật".

Tham khảo upstream Codex:
- https://github.com/openai/codex
- Remote catalog: `https://chatgpt.com/backend-api/codex/models`
- Codex protocol/model structures dùng `models[].slug`.

### Antigravity (`ProviderType.ANTIGRAVITY`)

Hiện tại không nên dùng `AntigravityModels.available` làm nguồn model chính.

Flow discovery theo **Google account hiện tại**:

1. Lấy/refresh OAuth token hiện tại.
2. Resolve project bằng `loadCodeAssist`.
3. Dùng project đã chọn/resolved để gọi `fetchAvailableModels`.
4. Hiển thị đúng model được provision cho account/project đó.

Resolve project:

```http
POST https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist
Authorization: Bearer <current Google OAuth token>
Content-Type: application/json
User-Agent: antigravity
```

Body tối thiểu:

```json
{
  "metadata": {
    "ideType": "ANTIGRAVITY",
    "platform": "PLATFORM_UNSPECIFIED",
    "pluginType": "GEMINI"
  }
}
```

Response có thể trả `cloudaicompanionProject`. Nếu account chưa được provision thì giữ flow `allowedTiers -> onboardUser -> poll -> cloudaicompanionProject` như adapter hiện tại.

Sau khi có project:

```http
POST https://cloudcode-pa.googleapis.com/v1internal:fetchAvailableModels
Authorization: Bearer <current Google OAuth token>
Content-Type: application/json
User-Agent: antigravity

{
  "project": "<selected project id>"
}
```

- Response hiện quan sát được trả `models` dạng object/map keyed theo model id, không phải OpenAI `data[]`.
- Mỗi item có thể chứa `displayName`, `model`, `quotaInfo.remainingFraction`, `quotaInfo.resetTime`, provider metadata, internal flags, v.v.
- Dùng catalog này làm nguồn chính vì nó phản ánh entitlement/quota/model provision của account hiện tại.
- Không loại model chỉ vì id trông như placeholder nếu upstream vẫn đánh dấu nó là user-facing. Chỉ lọc internal/non-user-facing dựa trên metadata đáng tin cậy (ví dụ `isInternal`, thiếu display name, hoặc rule hẹp đã xác minh).
- Base URL có thể fallback theo thứ tự hiện có của adapter (daily/sandbox/autopush/prod), nhưng kết quả phải luôn gắn với đúng account + project.

#### Antigravity project dropdown

OAuth scope hiện tại có `https://www.googleapis.com/auth/cloud-platform`, vì vậy **có thể thử lấy danh sách Google Cloud project mà account hiện tại nhìn thấy** bằng Cloud Resource Manager:

```http
GET https://cloudresourcemanager.googleapis.com/v1/projects?filter=lifecycleState:ACTIVE
Authorization: Bearer <current Google OAuth token>
```

API v1 trả các project mà caller có `resourcemanager.projects.get`.

Yêu cầu UI/behavior:

- Nếu project list request thành công và có >= 1 project phù hợp:
  - hiển thị dropdown Project trong Antigravity provider/account settings;
  - label nên dùng project display name + project id;
  - **mặc định chọn project đầu tiên** nếu account chưa từng lưu lựa chọn;
  - nếu đã lưu project cho account thì giữ project đã chọn, miễn project đó vẫn còn hợp lệ;
  - đổi project phải invalidate/refetch Antigravity model catalog và quota cho project mới.
- Project selection phải được lưu **theo OAuth account**, không global theo provider.
- Không được giả định mọi Google Cloud project list ra đều dùng được với Antigravity/Code Assist. Trước khi chấp nhận project làm selected project, validate bằng `loadCodeAssist` với project candidate (gửi `cloudaicompanionProject` và/hoặc `metadata.duetProject` theo wire format đang được Antigravity client dùng) và bỏ các project trả `projectValidationError`.
- Nếu Cloud Resource Manager trả 403/không khả dụng/không có project hợp lệ:
  - fallback về project tự resolve từ `loadCodeAssist.cloudaicompanionProject`;
  - nếu chỉ có một project auto-resolved thì có thể ẩn dropdown hoặc hiển thị dropdown disabled với một option.
- Nếu `loadCodeAssist` phải tạo project bằng `onboardUser`, project được provision đó vẫn phải được thêm vào lựa chọn hiện tại ngay cả khi Cloud Resource Manager chưa list ra ngay (project listing có thể eventually consistent).

Cloud Resource Manager reference:
- https://cloud.google.com/resource-manager/reference/rest/v1/projects/list
- `GET https://cloudresourcemanager.googleapis.com/v1/projects`

Antigravity discovery references observed in compatible clients:
- `POST /v1internal:loadCodeAssist`
- `POST /v1internal:onboardUser`
- `POST /v1internal:fetchAvailableModels`

## Current Multigateway gaps to fix when implementing this note

- `GitHubCopilotAdapter.fetchModels()`: already dynamic/account-scoped; keep this behavior and retain `supported_endpoints` metadata.
- `ClaudeCodeAdapter.fetchModels()`: already dynamic/account-scoped; keep this behavior.
- Generic OpenAI/Responses/Anthropic/Gemini/Ollama discovery in `LlmService.fetchProviderModelCatalog()`: mostly already dynamic; preserve pagination and custom list URL behavior.
- `AntigravityAdapter.fetchModels()`: currently calls `ensureToken()` then returns `AntigravityModels.available`. Replace with `loadCodeAssist + selected project + fetchAvailableModels`.
- `OpenAICodexAdapter.fetchModels()`: currently returns `SUPPORTED_MODELS`. Replace primary path with `/backend-api/codex/models?client_version=...`; keep bundled list only as fallback.
- Add account-scoped Antigravity project state and project dropdown as described above.
- A model refresh action should always refetch from upstream for the current account/project rather than merely reread a bundled list.
