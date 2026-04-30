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
Follow Kotlin conventions already used in `src/main/kotlin`: 4-space indentation, trailing commas where Kotlin supports them, and one top-level class or closely related set of declarations per file. Use `PascalCase` for classes, `camelCase` for functions and properties, and keep package names lowercase. Prefer descriptive names tied to IntelliJ concepts, for example `FuzzyFinderService` or `OpenFuzzyFinderAction`. Keep UI strings in `MyBundle.properties` instead of hardcoding them. All repository-facing artifacts must be written in English, including source code, code comments, identifiers, commit messages, pull request titles and descriptions, issue comments, and documentation added to the repository. This also applies to checklists and planning documents committed to the repository, such as `TODO.md`, release checklists, and similar notes.

## Swing UI Architecture
Implement Swing UI code with an MVVM-style structure. Keep Swing components, layout, listeners, and rendering in the View. Keep UI state and user-intent handlers in the ViewModel. Represent UI state as an immutable Kotlin `data class`, expose it as `StateFlow<UiState>`, and keep the backing `MutableStateFlow` private.

ViewModels must not depend on Swing components or events such as `JFrame`, `JPanel`, `JTextField`, `JLabel`, or `DocumentEvent`. Name ViewModel methods after user intent, such as `onUpdateInput`, `onClickSearch`, `onSelectItem`, or `onClose`. Swing listeners should be thin adapters that pass plain values to these methods; do not put business logic, persistence, search logic, or complex state changes directly in listeners.

Structure Swing Views so they receive their ViewModel through the constructor, configure components and layout, bind listeners, observe state, and render from the latest state. Prefer method names such as `layoutComponents()`, `bindEvents()`, `observeState()`, and `render(state)`. Use a single `render(state)` method per View when practical, and make rendering deterministic: the same state should produce the same visible UI. Guard component updates that can trigger listeners again, for example `if (textField.text != state.input) textField.text = state.input`.

Collect ViewModel state on `Dispatchers.Swing` when updating Swing components. Views that collect state should own a `CoroutineScope(Dispatchers.Swing + SupervisorJob())` and cancel it in `dispose()`. Do not use global coroutine scopes for UI windows, and do not update Swing components from non-Swing dispatchers. Long-running work should live in the ViewModel or service layer, with dispatchers or services injected where practical.

Use this minimal shape for new Swing screens:

```kotlin
data class ExampleState(
    val input: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

class ExampleViewModel {
    private val _state = MutableStateFlow(ExampleState())
    val state: StateFlow<ExampleState> = _state

    fun onUpdateInput(input: String) {
        _state.value = _state.value.copy(input = input)
    }
}

class ExampleView(
    private val viewModel: ExampleViewModel,
) : JFrame() {
    private val scope = CoroutineScope(Dispatchers.Swing + SupervisorJob())

    init {
        layoutComponents()
        bindEvents()
        observeState()
    }

    private fun layoutComponents() {
        // Create and arrange Swing components here.
    }

    private fun bindEvents() {
        // Convert Swing events into ViewModel method calls.
    }

    private fun observeState() {
        scope.launch {
            viewModel.state.collect { state ->
                render(state)
            }
        }
    }

    private fun render(state: ExampleState) {
        // Update Swing components from state.
    }

    override fun dispose() {
        scope.cancel()
        super.dispose()
    }
}
```

Prefer testing ViewModel state transitions without Swing. Keep Swing View tests minimal unless the UI behavior specifically requires integration or UI automation coverage.

## Testing Guidelines
Tests use JUnit 4 and should live beside the matching package path under `src/test/kotlin`. Name test files `*Test.kt` and use focused method names such as `parsesNulSeparatedPaths`. Add or update fixtures in `src/test/testData` when parser or file-handling behavior changes. Run `./gradlew check` before opening a PR.

## Commit & Pull Request Guidelines
Recent history favors short, imperative commit subjects such as `Update target version`, `remove plan.md`, or `refactor by using coroutine`. Keep commits narrowly scoped and avoid mixing refactors with behavior changes. Pull requests should describe the user-visible change, mention any IntelliJ or external-tool assumptions (`fd`, `fzf`), and link related issues. Include screenshots or short recordings for dialog, preview, or settings UI changes.

## Configuration Notes
Publishing and signing use environment variables such as `PUBLISH_TOKEN`, `PRIVATE_KEY`, and `CERTIFICATE_CHAIN`. Do not commit local secrets, Marketplace credentials, or machine-specific executable paths.
