# TODO

A checklist for the first JetBrains Marketplace release and the follow-up release workflow.

## 1. Pre-release Content Review

- [x] Set the plugin name, plugin group, and plugin id to project-specific values
- [x] Add a plugin description section to `README.md`
- [x] Align the requirements section in `README.md` with the actual supported versions
- [x] Review the installation instructions and decide whether screenshots are needed in `README.md`
- [x] Finalize the public description, feature list, and usage expectations for the Marketplace page
- [x] Review the vendor, action description, and settings display name in `plugin.xml` for publication

## 2. Release Scope

- [x] Update `pluginVersion` in `gradle.properties` to the first public release version
- [x] Refine the `Unreleased` section in `CHANGELOG.md` to match the release contents
- [x] Decide whether the first release should be a pre-release or a stable release
- [ ] Prepare Marketplace icons or promotional images if needed

## 3. Quality Checks

- [x] Run `./gradlew check`
- [x] Run `./gradlew verifyPlugin`
- [x] Confirm that `./gradlew buildPlugin` generates the ZIP artifact successfully
- [x] Perform manual verification with `./gradlew runIde`
- [ ] Verify the behavior and settings UI when `fd` or `fzf` is not installed
- [ ] Run basic smoke tests on the IDEs you intend to support

## 4. Marketplace Preparation

- [ ] Review the JetBrains Marketplace legal agreements
- [ ] Create the plugin entry in JetBrains Marketplace
- [ ] Generate a deployment token
- [ ] Prepare the certificate, private key, and password for plugin signing
- [ ] Configure the following secrets locally or in CI
- [ ] `PUBLISH_TOKEN`
- [ ] `CERTIFICATE_CHAIN`
- [ ] `PRIVATE_KEY`
- [ ] `PRIVATE_KEY_PASSWORD`

## 5. First Publication

- [ ] Run `./gradlew publishPlugin` manually for the first publication
- [ ] Confirm the Marketplace page, description, compatibility range, and release channel after upload
- [ ] Address any review feedback or warnings and republish if necessary

## 6. Post-publication Updates

- [ ] Record the assigned Marketplace plugin id
- [ ] Replace the `MARKETPLACE_ID` placeholders in `README.md`
- [ ] Add the Marketplace URL to the README and repository description
- [ ] Update the GitHub Releases text if it should mirror the published release details

## 7. Ongoing Release Automation

- [ ] Configure the publish and signing secrets in GitHub Secrets
- [ ] Confirm that the release workflow can run `publishPlugin`
- [ ] Document the release process from tagging to Marketplace publication in the README or a dedicated document
- [ ] Configure `CODECOV_TOKEN` if coverage reporting should be enabled

## 8. Follow-up Improvements

- [ ] Improve Marketplace screenshots and promotional copy
- [ ] Expand the UI test automation coverage
- [ ] Define the supported IDE version policy
- [ ] Align release note handling between `CHANGELOG.md` and GitHub Releases
