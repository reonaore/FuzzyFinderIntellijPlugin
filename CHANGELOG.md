<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# FuzzyFinderIntellijPlugin Changelog

## [Unreleased]
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
