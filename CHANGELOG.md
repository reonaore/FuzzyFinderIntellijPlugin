<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# FuzzyFinderIntellijPlugin Changelog

## [Unreleased]
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
