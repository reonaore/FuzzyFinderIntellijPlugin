# Repository Guidelines

## Project Structure & Module Organization
This repository is a Gradle-based IntelliJ Platform plugin written in Kotlin. Production code lives under `src/main/kotlin/com/github/reonaore/fuzzyfinderintellijplugin`, split by responsibility: `actions/`, `services/`, `settings/`, `ui/`, and `util/`. Plugin metadata and localized messages live in `src/main/resources`, especially `META-INF/plugin.xml` and `messages/MyBundle.properties`. Tests live under `src/test/kotlin`, with fixture data in `src/test/testData`. Build and dependency configuration is in `build.gradle.kts`, `gradle.properties`, and `gradle/libs.versions.toml`.

## Build, Test, and Development Commands
Use the Gradle wrapper so contributors stay on the pinned toolchain.

- `./gradlew buildPlugin` builds the plugin ZIP in `build/distributions/`.
- `./gradlew check` runs tests and generates the Kover XML coverage report used by CI.
- `./gradlew verifyPlugin` runs IntelliJ plugin verification tasks.
- `./gradlew runIde` launches a local IDE with the plugin loaded for manual testing.
- `./gradlew runIdeForUiTests` starts the IDE with the Robot Server enabled for UI automation.

The project targets Java/Kotlin toolchain 21 and IntelliJ Platform `2026.1`.

## Coding Style & Naming Conventions
Follow Kotlin conventions already used in `src/main/kotlin`: 4-space indentation, trailing commas where Kotlin supports them, and one top-level class or closely related set of declarations per file. Use `PascalCase` for classes, `camelCase` for functions and properties, and keep package names lowercase. Prefer descriptive names tied to IntelliJ concepts, for example `FuzzyFinderService` or `OpenFuzzyFinderAction`. Keep UI strings in `MyBundle.properties` instead of hardcoding them. All repository-facing artifacts must be written in English, including source code, code comments, identifiers, commit messages, pull request titles and descriptions, issue comments, and documentation added to the repository.

## Testing Guidelines
Tests use JUnit 4 and should live beside the matching package path under `src/test/kotlin`. Name test files `*Test.kt` and use focused method names such as `parsesNulSeparatedPaths`. Add or update fixtures in `src/test/testData` when parser or file-handling behavior changes. Run `./gradlew check` before opening a PR.

## Commit & Pull Request Guidelines
Recent history favors short, imperative commit subjects such as `Update target version`, `remove plan.md`, or `refactor by using coroutine`. Keep commits narrowly scoped and avoid mixing refactors with behavior changes. Pull requests should describe the user-visible change, mention any IntelliJ or external-tool assumptions (`fd`, `fzf`), and link related issues. Include screenshots or short recordings for dialog, preview, or settings UI changes.

## Configuration Notes
Publishing and signing use environment variables such as `PUBLISH_TOKEN`, `PRIVATE_KEY`, and `CERTIFICATE_CHAIN`. Do not commit local secrets, Marketplace credentials, or machine-specific executable paths.
