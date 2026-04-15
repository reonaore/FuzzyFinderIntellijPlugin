# FuzzyFinderIntellijPlugin

![Build](https://github.com/reonaore/FuzzyFinderIntellijPlugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

<!-- Plugin description -->
Fuzzy Finder adds a lightweight file search dialog to IntelliJ-based IDEs by combining
[`fd`](https://github.com/sharkdp/fd) for fast candidate discovery and
[`fzf`](https://github.com/junegunn/fzf) for ranked filtering.

The plugin opens a modeless dialog with:

- incremental file search backed by `fzf --filter`
- a live file preview pane with syntax highlighting
- filters for file type, hidden files, symlink handling, and ignore rules
- configurable executable paths for `fd` and `fzf`
<!-- Plugin description end -->

## Requirements

- IntelliJ Platform 2025.2+
- `fd` available on `PATH`, or configured in Settings
- `fzf` available on `PATH`, or configured in Settings

## Usage

Open `Tools | Open Fuzzy Finder`, type a query, then press `Enter` to open the selected file.

The dialog supports:

- `Ctrl+N` to move to the next result
- `Ctrl+P` to move to the previous result
- double-click or `Enter` to open the selected file

Executable paths can be configured in `Settings | Tools | Fuzzy Finder`.

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "Fuzzy Finder"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/reonaore/FuzzyFinderIntellijPlugin/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>
