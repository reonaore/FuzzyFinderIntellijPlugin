# Fuzzy Finder Plugin Plan

## Goal

Implement file search for an IntelliJ Platform plugin by invoking `fd` and `fzf` from Kotlin code in the background, then showing:

- a filtered list of matching files
- a text preview for the currently selected file
- opening the selected file in the editor

The plugin owns the UI. `fd` and `fzf` are only used as external search/filter engines.

## Constraints

- Do not depend on terminal embedding.
- Keep long-running work off the EDT.
- Treat `fd` as candidate discovery and `fzf` as non-interactive ranking/filtering via `--filter`.
- Start with `project.basePath` as the search root for the MVP.
- Handle missing executables and command failures with user-visible notifications.

## Implementation Plan

1. Replace template sample components
   - Remove the sample tool window, startup activity, and placeholder project service usage.
   - Register an `AnAction` entry point in `plugin.xml`.

2. Add a background command service
   - Create a project service responsible for:
     - discovering files with `fd`
     - caching the discovered file list
     - filtering cached candidates with `fzf --filter`
   - Use `GeneralCommandLine` and plain process I/O from background threads.

3. Build the search dialog
   - Create a modeless `DialogWrapper`.
   - Add:
     - search input
     - results list
     - preview pane
     - lightweight status text
   - Debounce query changes before launching filtering work.

4. File preview and open behavior
   - Render a text preview for the selected file.
   - Guard against directories, binary files, and oversized previews.
   - Open the selected file with `FileEditorManager`.

5. Validation
   - Add focused tests for pure parsing/formatting helpers.
   - Run the test suite.

## MVP Scope

- One action to open the search dialog
- Search root is `project.basePath`
- `fd` options tuned for source repositories
- `fzf` used in non-interactive mode
- Read-only text preview

## Deferred Work

- Configurable `fd` and `fzf` executable paths
- Search across multiple content roots
- Preview syntax highlighting
- File icons and richer result rendering
- Cancellation that actively terminates stale child processes
- Settings UI and recent-query persistence
