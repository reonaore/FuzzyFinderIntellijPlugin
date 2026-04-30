<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# FuzzyFinderIntellijPlugin Changelog

## [Unreleased]

### Added

- Live Grep option controls now expose keyboard shortcuts for faster filtering.

### Changed

- Finder and Live Grep dialogs now use Flow-based ViewModels for more predictable UI state handling.
- Finder and Live Grep result panes now show clearer loading and empty candidate states.
- Gradle wrapper and internal package structure have been updated.

### Fixed

- Qodana warnings have been resolved without changing the plugin behavior.

## [0.3.0] - 2026-04-28

### Added

- File search can now filter results by file extension.
- File search and Live Grep dialogs now focus the search field when opened from the menu shortcut.

### Fixed

- File search option filters now apply consistently when toggled.

### Changed

- Marketplace preview images in the README have been updated.

## [0.2.2] - 2026-04-27

### Added

- Live Grep results can now be narrowed with an `fzf` fuzzy filter after running the `rg` regex search.

### Fixed

- Live Grep preview scrolling now works correctly when the dialog opens with the current editor selection as the initial query.

## [0.2.1] - 2026-04-27

### Added

- Live Grep now uses the current editor selection as the initial query when opened.

## [0.2.0] - 2026-04-27

### Added

- Live Grep dialog powered by `rg` with smart-case regex matching
- Configurable executable path for `rg`
- Match highlighting in Live Grep result rows and previews

## [0.1.0] - 2026-04-23

### Added

- File search dialog powered by `fd` candidate discovery and `fzf --filter`
- Live preview pane with syntax highlighting for text files
- Search options for entry type, hidden files, symlink handling, and ignore rules
- Configurable executable paths for `fd` and `fzf`

### Changed

- Search roots now follow IntelliJ content roots instead of only `project.basePath`
- Candidate caches are invalidated on VFS changes
- Preview loading now runs off the EDT and ignores stale selection updates
- User-visible strings are centralized in the resource bundle

[Unreleased]: https://github.com/reonaore/FuzzyFinderIntellijPlugin/compare/0.3.0...HEAD
[0.3.0]: https://github.com/reonaore/FuzzyFinderIntellijPlugin/compare/0.2.2...0.3.0
[0.2.2]: https://github.com/reonaore/FuzzyFinderIntellijPlugin/compare/0.2.1...0.2.2
[0.2.1]: https://github.com/reonaore/FuzzyFinderIntellijPlugin/compare/0.2.0...0.2.1
[0.2.0]: https://github.com/reonaore/FuzzyFinderIntellijPlugin/compare/0.1.0...0.2.0
[0.1.0]: https://github.com/reonaore/FuzzyFinderIntellijPlugin/commits/0.1.0
