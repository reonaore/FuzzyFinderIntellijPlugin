# FuzzyFinderIntellijPlugin

![Build](https://github.com/reonaore/FuzzyFinderIntellijPlugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/31449-fuzzy-finder.svg)](https://plugins.jetbrains.com/plugin/31449-fuzzy-finder)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/31449-fuzzy-finder.svg)](https://plugins.jetbrains.com/plugin/31449-fuzzy-finder)

<!-- Plugin description -->
Fuzzy Finder adds lightweight search dialogs to IntelliJ-based IDEs by combining
[`fd`](https://github.com/sharkdp/fd) for fast candidate discovery and
[`fzf`](https://github.com/junegunn/fzf) for ranked filtering, plus
[`ripgrep`](https://github.com/BurntSushi/ripgrep) for live text search.

The plugin opens modeless dialogs with:

- incremental file search backed by streamed `fd` candidates and `fzf --filter`
- live grep backed by `rg` with word search by default and optional regex mode
- fuzzy switching across currently open tabs
- a live file preview pane with syntax highlighting
- filters for file type, hidden files, symlink handling, and ignore rules
- `Cmd+F` on macOS or `Ctrl+F` on other platforms to refocus the search field
- configurable executable paths for `fd`, `fzf`, and `rg`
- project-root aware search scoped to IntelliJ content roots

![Fuzzy Finder search dialog](https://raw.githubusercontent.com/reonaore/FuzzyFinderIntellijPlugin/main/assets/fuzzy-file-finder-preview.png)

![Live Grep search dialog](https://raw.githubusercontent.com/reonaore/FuzzyFinderIntellijPlugin/main/assets/live-grep-preview.png)
<!-- Plugin description end -->

## Requirements

- IntelliJ IDEA 2026.1 or newer
- `fd` available on `PATH`, or configured in Settings
- `fzf` available on `PATH`, or configured in Settings
- `rg` available on `PATH`, or configured in Settings

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "Fuzzy Finder"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/31449-fuzzy-finder) and install it by clicking
  the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest Marketplace version](https://plugins.jetbrains.com/plugin/31449-fuzzy-finder/versions)
  and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually from GitHub Releases:

  Download the [latest release](https://github.com/reonaore/FuzzyFinderIntellijPlugin/releases/latest) and install it
  manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

## Quick Start

Fuzzy Finder depends on external `fd`, `fzf`, and `rg` commands. The plugin does not bundle
these executables, so install them before using the plugin.

### 1. Install `fd`, `fzf`, and `rg`

On macOS with Homebrew:

```shell
brew install fd fzf ripgrep
```

On other platforms, follow the official installation guides:

- [`fd` installation](https://github.com/sharkdp/fd#installation)
- [`fzf` installation](https://github.com/junegunn/fzf#installation)
- [`ripgrep` installation](https://github.com/BurntSushi/ripgrep#installation)

### 2. Verify executable paths

```shell
which fd
which fzf
which rg
```

If these commands return executable paths, the plugin can usually use them without additional configuration.

### 3. Configure executable paths in the IDE (if needed)

1. Open `Settings/Preferences | Tools | Fuzzy Finder`.
2. Set `fd executable path`, `fzf executable path`, and `rg executable path`.
3. Apply the changes.

For example, Homebrew installations may use paths such as:

```text
/opt/homebrew/bin/fd
/opt/homebrew/bin/fzf
/opt/homebrew/bin/rg
```

Leave fields blank to use `fd`, `fzf`, and `rg` from `PATH`.

### 4. Open dialogs

- Open `Tools | Open Fuzzy Finder`, type a query, and press `Enter` to open the selected file.
- Open `Tools | Open Live Grep`, type words to search in order, and press `Enter` to open the selected match. Enable Regex mode when you want to enter a raw `rg` pattern.
- Open `Tools | Open Tab Finder`, type a query, and press `Enter` to activate the selected open tab.

## Shortcuts

### Common shortcuts (Fuzzy Finder, Live Grep, and Open Tab Finder)

| Shortcut | Action |
| --- | --- |
| `Ctrl+N` | Move to the next result |
| `Ctrl+P` | Move to the previous result |
| `Enter` / double-click | Open the selected item |
| `Cmd+F` (macOS) / `Ctrl+F` (others) | Refocus the search field |

### Option shortcuts

| Shortcut | Scope | Action |
| --- | --- | --- |
| `Alt+H` | Fuzzy Finder | Toggle hidden files |
| `Alt+S` | Fuzzy Finder | Toggle symlink following |
| `Alt+G` | Fuzzy Finder | Toggle ignore rules |
| Search field buttons | Live Grep | Toggle Regex and smart-case modes |
| `Alt+E` | Finder / Live Grep | Focus extensions field |
| `Alt+X` | Finder / Live Grep | Focus exclude field |

## Feature Overview

### Search

- Find project files quickly as `fd` streams candidates and rank matches with `fzf --filter`
- Search project text with word search by default, switch to Regex mode for raw `rg` patterns, and jump directly to matching lines
- Switch between currently open tabs with fuzzy matching over file names and paths
- Scope searches to IntelliJ content roots in the current project

### Preview

- Preview the current selection without leaving the dialog
- Show syntax-highlighted previews for text files

### Filters

- Filter file results by file extension
- Hidden files are included by default; toggle hidden-file, symlink-following, and ignore-rule options per search
- The `.gitignore` option is enabled by default, so Finder and Live Grep respect `.gitignore` and other ignore files; turn it off to include ignored files
- Apply fuzzy filtering to Live Grep results after `rg` regex search

### Configuration

- Configure custom `fd`, `fzf`, and `rg` executable paths when tools are not on `PATH`

## Behavior and UX Notes

- Finder and Live Grep dialogs use Flow-based ViewModels to keep UI state updates predictable.
- Result panes include clearer loading and empty-result states.
- Dialogs can refocus the search field quickly via keyboard (`Cmd+F` on macOS, `Ctrl+F` on other platforms).
- File search supports file-extension filtering, streams candidates as they are discovered, and includes hidden files by default.
- Live Grep supports word search by default, search-field buttons for Regex and smart-case modes, and post-search fuzzy filtering for fast narrowing.

## Troubleshooting

> Note: Option shortcuts (Alt+...) are available while the dialog is focused.


### Commands are not found

- Run:

  ```shell
  which fd
  which fzf
  which rg
  ```

- If any command is missing, install it and restart the IDE.
- If commands exist but are still not detected, set explicit paths in `Settings/Preferences | Tools | Fuzzy Finder`.

### No results appear in Fuzzy Finder

- Check whether hidden/ignore/symlink toggles are narrowing results unexpectedly.
- If the target file is ignored by `.gitignore`, turn off the `.gitignore` option in the dialog to include ignored files.
- Confirm you are searching under project content roots (the plugin is scoped to IntelliJ content roots).

### Live Grep results are unexpected

- Live Grep uses word search by default. Enable Regex mode in the search field when you want raw `rg` syntax.
- Toggle smart-case in the search field and compare results when matches differ from expectations.

## Notes

- The plugin does not bundle `fd`, `fzf`, or `rg`; these must be installed separately.
- For full release history, see [CHANGELOG.md](./CHANGELOG.md).
