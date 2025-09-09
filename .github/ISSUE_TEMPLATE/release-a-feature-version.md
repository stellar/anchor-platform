---
name: Release a FEATURE Version
about: Publishing a feature release
title: 'Release a FEATURE Version'
labels: ''
assignees: ''

---
<!-- Please Follow this checklist before making your release. Thanks! -->

## Publish a FEATURE Release
### Release Preparation
- [ ] Decide on a version number based on the current version number and the common rules defined in [Semantic Versioning](https://semver.org). E.g. `4.x.x`.
- [ ] Update this ticket name to reflect the new version number, following the pattern "Release `4.x.x`".
- [ ] Update `version` string (Eg: `4.x.x`) attribute in the `build.gradle.kts`
- [ ] Code freeze and cut a branch for the new release out of the `develop` branch, following the Gitflow naming pattern `release/4.x.x`.
- [ ] Update the badges versions in [docs/README.md].
- [ ] In general, only bug fixes and security patches will be applied to the `release/4.x.x` branch.
### Release Publication
- [ ] DO NOT RELEASE before holidays or weekends! Mondays and Tuesdays are preferred.
- [ ] Create a new release draft on GitHub with the name `4.x.x` and tag: `4.x.x` (without the `release-` prefix).
- [ ] Write the proper release notes.
  - [ ] Use `Generate release notes` in the GitHub UI to generate the changes.
  - [ ] Remove chore and refactor commits from the release notes. (eg. merge to `develop`, version bump, etc.)
  - [ ] Add `What's New` section if applicable.
  - [ ] Add `What's Changed` section if applicable.
  - [ ] Add `Bug Fixes` section if applicable.
- [ ] After reviewing the release draft, publish!!!
### Post Release Publication
- [ ] Check the docker image of the release automatically published to [Docker Hub](https://hub.docker.com/r/stellar/anchor-platform).
- [ ] If necessary, update the badges versions in [docs/00 - Stellar Anchor Platform.md].
- [ ] Create the pull request `release/4.x.x -> main`: this should require two approvals. DO NOT squash merge.
- [ ] Create another pull request `release/4.x.x -> develop`: AFTER the release branch is merged with the `main` branch. DO NOT squash merge.
- [ ] Publish the new version in the [#release](https://stellarfoundation.slack.com/archives/C04ECVCV162) Slack channel.
- [ ] (Optional) Generate the new Anchor Platform documentation version in the stellar-docs repository if this is a new major or minor version.
- [ ] (Optional) You'll need to manually publish a new version of the SDK to [Maven Central](https://search.maven.org/search?q=g:org.stellar.anchor-sdk) in the `legacy/release-1.0`.
