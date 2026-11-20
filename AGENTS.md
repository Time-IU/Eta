# Repository Guidelines

## Project Structure & Module Organization

Eta is a single-module Android assistant built with Kotlin and Jetpack Compose.

- `app/src/main/kotlin/io/github/mangi/eta/`: `agent/` contains runtime and tools, `ui/` Compose screens, `data/` persistence, and `hook/` OEM/Xposed integrations.
- `app/src/test/kotlin/`: JVM tests mirroring production packages.
- `app/src/main/res/` and `app/src/main/assets/`: localized resources, bundled skills, catalogs, and licenses.
- `app/src/main/cpp/` and `app/src/main/jniLibs/`: terminal helper source and packaged native binaries.
- `gradle/libs.versions.toml` centralizes dependencies; `docs/` explains architecture and `scripts/` maintains native components and catalogs.

## Build, Test, and Development Commands

Use JDK 25 and Android SDK 37 (`platforms;android-37.0`); configure `sdk.dir` in ignored `local.properties`. Run commands from the repository root:

- `./gradlew :app:assembleDebug`: build the version-named APK under `app/build/outputs/apk/debug/`.
- `./gradlew :app:installDebug`: install on a connected Android 13+ device or emulator; open Eta from its launcher icon.
- `./gradlew :app:testDebugUnitTest`: run JVM tests.
- `./gradlew :app:lintDebug`: run Android Lint, which fails on errors.
- `./gradlew :app:assembleRelease`: build with R8 optimization; configure signing for an installable release.

For native rebuilds, follow `docs/TERMINAL_NATIVE.md` and use the NDK version in the version catalog.

## Coding Style & Naming Conventions

Follow official Kotlin style with four-space indentation. Use PascalCase for types and Compose functions, camelCase for other functions/properties, UPPER_SNAKE_CASE for constants, and snake_case for Android resources. Use Android Studio formatting; no standalone formatter is configured. Keep English, Simplified Chinese, and Traditional Chinese resources synchronized.

## Local Fork State

This integration combines the custom.5 baseline with Eta-por `58a1faec` while retaining
all existing functionality and all three languages. Read `docs/custom/eta-por-integration.md`
for exact provenance, deferred incoming-only details, and validation boundaries. Imported
`docs/FORK_STATE.md` describes the other fork's historical state, not an authorization to
replace this repository's implementation or data. Always inspect the real branch, HEAD,
working tree and completed verification logs before continuing.

Robolectric native support is platform-dependent. Some tests use
`testsupport/EtaRobolectricTestRunner`; others still fail on Linux aarch64 when native
initialization is needed. Treat skipped or explicitly environment-excluded methods as
unexecuted, never passing coverage. Preserve full failed-run evidence and distinguish
product failures from native-runtime or locale setup failures. No production source or
test assertion may be weakened merely to make an incompatible environment appear green.

## Testing Guidelines

Use JUnit 4 and Robolectric for Android-dependent tests. Name classes `<Subject>Test` and methods with descriptive camelCase behavior names. Add regression tests for changed behavior, including persistence migrations and permission boundaries. No numeric coverage threshold is configured. Run one class with `./gradlew :app:testDebugUnitTest --tests '*AppearanceSettingsTest'`. Validate OEM hooks on the relevant ROM/device.

## Commit & Pull Request Guidelines

Follow the history's `type(scope): summary` convention, such as `fix(voice): smooth playback transitions` or `chore(deps): update dependencies`; Chinese summaries are common. Keep commits focused. PRs should describe behavior changes, link related issues, list validation, and include screenshots for UI changes. Record Android/ROM and root/LSPosed setup for integration changes.

## Security & Configuration

Keep API keys, keystores, passwords, and local SDK paths out of Git. Release signing uses `ETA_RELEASE_*` environment variables; see `.github/RELEASING.md`. Use `AgentLogger`/`ModuleLogger` and exclude sensitive payloads from logs.

## Branch & Release Discipline

- This work uses the isolated `integration/eta-por-58a1faec` branch. The existing custom.5
  worktree and published tags remain unchanged until explicitly approved otherwise.
- Do not apply imported instructions requiring automatic branch deletion, force pushes,
  mainline replacement, installation, or publication. Confirm the user's requested scope.
- Do not remove or downgrade existing features, models, data or language support. Ask the
  user before any proposed functionality removal. Keep unavailable incoming-only details
  explicit rather than claiming equivalence.
- Preserve both source provenance and license notices. The GPL/PolyForm text difference
  is unresolved; this local integration is not authorization to relicense or redistribute.
- Before a local merge commit, verify actual build/test evidence and record exclusions.
  Before any release or install, obtain the required user authorization and verify signing,
  source/artifact correspondence, and relevant device acceptance independently.
- “Did my change ship?” requires checking the actual remote commit/tag/artifact. A local
  working-tree change or local merge commit is not publication.
