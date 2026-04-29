---
name: intellij-plugin-release
description: Prepare and publish Gradle-based IntelliJ Platform plugin releases. Use when Codex needs to bump pluginVersion, update CHANGELOG.md, run Gradle release checks, create/merge GitHub release PRs, publish GitHub draft releases, monitor GitHub Actions release workflows, verify uploaded release assets, or confirm JetBrains Marketplace plugin versions.
---

# IntelliJ Plugin Release

## Overview

Use this skill to release a Gradle-based IntelliJ Platform plugin with GitHub Actions and JetBrains Marketplace publishing. Keep the work conservative: release metadata first, CI gates second, publishing last, and always verify the remote state after each irreversible step.

## Release Workflow

1. Inspect the repository release shape before editing:
   - Read `gradle.properties`, `CHANGELOG.md`, `build.gradle.kts`, and `.github/workflows/*.yml`.
   - Check `git status --short`, current branch, recent tags, recent commits since the latest release tag, open PRs, and current GitHub release drafts.
   - Identify the Marketplace plugin ID from `README.md`, `plugin.xml`, or existing Marketplace links when later verification is needed.

2. Choose the next version from the actual changes:
   - Prefer SemVer-compatible bumps from `pluginVersion`.
   - For `0.x` plugins, use a minor bump for user-visible feature additions and a patch bump for small fixes only.
   - Do not reuse a version that already has a published tag or Marketplace update.

3. Prepare the release on a branch:
   - Create a branch such as `codex/prepare-<version>-release`.
   - Update only release metadata unless a blocking issue is discovered.
   - Set `pluginVersion=<version>` in `gradle.properties`.
   - Normalize `CHANGELOG.md`: keep `Unreleased` for the new release notes that the build will use, move stale notes into their historical version section, and preserve existing English repository style.
   - Commit with `Prepare <version> release`.

4. Verify before opening or merging:
   - Run `./gradlew check`.
   - Run `./gradlew verifyPlugin` when feasible. If it fails because another IDE instance is running locally, use CI verification instead. If it fails because of an external service error, rerun or use CI after confirming it is not a code issue.
   - Create a PR to `main`, monitor all checks, and do not merge until required Build/Test/Verify/Qodana gates pass.

5. Merge and confirm draft creation:
   - Merge the release PR using the repository's established merge style.
   - Monitor the `main` Build workflow until `releaseDraft` succeeds.
   - Inspect the generated draft release notes and tag name before publishing.

6. Publish:
   - Publish the GitHub draft release only after the draft notes, version, and CI status are correct.
   - Monitor the Release workflow. Treat `publishPlugin` and release asset upload as the critical publishing steps.
   - If a later changelog PR creation step fails due to GitHub Actions permissions, create the intended changelog PR manually from the pushed branch instead of rerunning publish.

7. Post-publish verification:
   - Confirm the GitHub release is public and has expected assets, including signed and unsigned plugin ZIPs when the workflow uploads both.
   - Confirm the Marketplace version through the JetBrains Marketplace API when possible:

```bash
curl -fsSL https://plugins.jetbrains.com/api/plugins/<plugin-id>/updates
```

   - Verify the new version is listed, approved/listed as expected, and has matching notes.
   - Report any Release workflow failure precisely: distinguish publish failure from post-publish bookkeeping failure.

## GitHub Actions Patterns

- `Build` workflow on PRs usually validates `buildPlugin`, `check`, Qodana, and `verifyPlugin`.
- `Build` workflow on `main` usually creates a draft release after all gates pass.
- `Release` workflow usually starts when a GitHub release is published, runs `patchChangelog`, `publishPlugin`, uploads release assets, and may create a follow-up changelog PR.
- If release workflow fails after `publishPlugin` and `Upload Release Asset` both succeeded, do not assume the release failed. Verify Marketplace and assets directly.

## Safety Rules

- Never publish a draft release before checking its notes and version.
- Never ignore a failed `publishPlugin` step; inspect logs and stop.
- Do not rerun publishing blindly if Marketplace already accepted the version, because duplicate version uploads can fail or create confusing state.
- Do not delete release drafts or tags unless the user explicitly asks or the repository workflow clearly removes old drafts as part of draft creation.
- Keep repository-facing text in English.
