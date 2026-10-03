# Contributing to MultiGateway

MultiGateway is a native Android application written in Kotlin with Jetpack Compose.

## Development

Use JDK 17 and Android SDK 36. Open the repository root in Android Studio and sync
Gradle, or use another Android development environment with API 36 and build-tools
36.0.0 installed. The Gradle wrapper is the entry point for all builds; no separate
language SDK or package manager is required. Configure `sdk.dir` in your untracked
`local.properties` or set `ANDROID_HOME`.

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest  # requires a device/emulator
```

## Architecture

See [docs/architecture.md](docs/architecture.md) for dependency ownership and state
lifetimes. Keep rendering in Compose, screen actions in ViewModels, and persistent
or remote data operations in the data layer. Pass state and callbacks to reusable
composables. Collect UI flows with `collectAsStateWithLifecycle()`.

- Add feature UI and its state holder under `ui/<feature>/`.
- Inject dependencies through constructors and wire them in `di/AppContainer.kt`.
- Add destinations to `ui/navigation/AppDestination.kt` and the `MainScreen` NavHost.
- Use `viewModelScope` for writes triggered by user actions. Composition scopes are
  for UI work such as opening a drawer, not persistence.
- Keep Room entities, DAOs and migrations under `data/local/db/`. Export schemas
  and supply migrations for schema changes; preserve existing users' data.
- Put Android resources in `app/src/main/res/` and raw asset files in
  `app/src/main/assets/`.
- Use Kotlin coroutines and Flow, and propagate cancellation when handling errors.

## Validation and pull requests

Run unit tests, lint, and a debug build before submitting. For navigation or state
changes, also check system Back, rotation, process recreation, and navigation while
chat generation is active on an emulator/device. Add behavior tests when changing
business logic. Summarize the behavior change and the checks you ran in the PR.

The Codemagic debug workflow runs unit tests, lint, and builds APKs. Release builds
use the existing signing environment variables and `scripts/publish-github-release.sh`.
Version name/code live in `app/build.gradle.kts`.

Do not commit API keys, local SDK paths, build outputs, or release signing secrets.
Contributions are covered by the repository [LICENSE](LICENSE). By submitting code for inclusion, you represent that you have the right to submit it and agree that an accepted, merged code contribution is licensed under the repository license. A qualifying code contribution also gives the contributing person or organization the contributor-only commercial-use permission defined in the license; documentation-only, issue, review, sponsorship, and other non-code contributions do not qualify.
