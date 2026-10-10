# Android architecture

The app uses a single Android Gradle module with feature-oriented UI packages and
a separate data layer. This follows the [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations).
Additional Gradle modules or a domain layer should be introduced when there is a
concrete isolation or reusable business-logic requirement.

## Ownership and dependencies

```text
MultiGatewayApplication
  -> AppContainer (application-scoped database, repositories, network clients)
  -> DefaultDataInitializer (application scope)
MainActivity
  -> injected ViewModel factory
  -> SettingsViewModel (theme and settings)
  -> MainScreen (navigation and route wiring)
       -> ChatViewModel (conversation selection, edits, generation and tool controls)
       -> ConfigurationViewModel (profiles, providers, MCP and speech configuration)
       -> feature composables (state and callbacks)
ViewModels -> repositories / tool runtime -> Room, DataStore, HTTP and system TTS
```

`AppContainer` is the manual dependency-injection composition root. It retains only
an application Context. ViewModels use constructor injection and do not instantiate
databases or network clients. TTS is created for the chat ViewModel and shut down
when that ViewModel is cleared. Configuration calls access LLM/MCP through repositories.

`DefaultDataInitializer` owns the existing default-record seeding and legacy endpoint
normalization. It runs once per application process, independently of Activity
recreation. It preserves the previous seeding behavior and stored serialization
formats; this refactor does not change the Room schema or DataStore keys.

## State and navigation

`MainScreen` is a route-level composition root. Feature composables receive state
and callbacks, while writes run in ViewModel scopes so leaving a composition does
not cancel a settings update. UI observers use lifecycle-aware Flow collection.
Settings and configuration display flows use `WhileSubscribed(5_000)`; chat inputs
remain observed after their first subscriber so generation can continue with current
provider/profile/tool access while the UI is backgrounded. Chat preferences are
observed eagerly because send actions read them without a UI subscription.

`NavHost` owns the back stack, system Back handling, and destination restoration.
Stable route names live in `ui/navigation/AppDestination.kt`. Top-level navigation
returns to the chat root and avoids duplicate destinations. The open conversation
drawer consumes Back before the destination stack.

ViewModels are Activity-scoped deliberately: switching screens or rotating must not
stop an active chat stream. `ChatGeneration` owns each generation's snapshot and saves
partial output on cancellation. After process death, stored conversations are available
from Room, but an in-flight request is not restarted. When the Continue Last
Conversation preference is enabled, the most recently updated stored conversation is
reopened after the initial Room/DataStore snapshots are ready. Editors still use local Compose state; unsaved editor
forms are not guaranteed to survive process recreation.

## Local mutations and responsiveness

Repositories share an application-lifetime `ImmediateState` for Room lists, app
preferences and tool settings. It publishes in-memory changes before awaiting disk,
serializes accepted writes on IO, and retains pending transforms until read-back.
A Room/DataStore invalidation is re-read under the writer lock, so an older emission
cannot undo a newer optimistic edit. All JSON decoding, credential encryption and
persistence run off the UI thread. Read/modify/write actions consult the current
in-memory snapshot, including changes whose writes are still pending.

Transforms must be pure and inexpensive: they may be reapplied to a newer committed
snapshot. Never put IO or side effects in a transform. Persistence failures remove
only the failed transform, retain newer pending changes, and are reported to the
user through `LocalWriteErrors`. Accepted writes survive navigation/ViewModel
cancellation, but are not durable until persistence finishes; process termination
can still lose an uncommitted change.

Chat actions update the visible selection immediately. Their persistence is ordered
separately from rendering; active generation is stopped and its final snapshot saved
before deleting/renaming its conversation. Message editing no longer rejects input
merely because a previous local write is pending.

## Source layout

- `di/`: construction and ViewModel factories.
- `data/local/`: Room, DataStore and credential encryption.
- `data/repository/`: one file per repository, shared serialization and default seeding.
- `data/service/`: protocol/SDK and Android speech adapters.
- `data/tools/`: tool execution, generated media and local file handling.
- `ui/chat/`: chat rendering, ChatViewModel and generation state holder.
- `ui/configuration/`: configuration mutations shared by management screens.
- `ui/settings/`: settings rendering and its ViewModel.
- `ui/navigation/`: stable app destinations.
- Other `ui/<feature>/` packages: feature screens and reusable components.
- `app/src/main/assets/`: raw brand artwork and its manifest.
- `app/src/main/res/`: Android resources, launcher icons and platform configuration.

Media previews and Android document/share launchers remain Compose platform adapters
in `ui/tools`. They use IO dispatchers for file reads/writes; a dedicated storage
state holder is a possible follow-up if this feature grows.
