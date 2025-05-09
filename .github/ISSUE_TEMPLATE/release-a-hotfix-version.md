---
name: Release a New Version
about: Publishing a new release
title: 'Release a New Version'
labels: ''
assignees: ''

---
<!-- Please Follow this checklist before making your release. Thanks! -->

## Publish a HOTFIX Release
### Release Preparation
- [ ] Cut a branch for the new release out of the `main` branch, following the Gitflow naming pattern `hotfix/3.x.x`.
- [ ] Make your changes in the `hotfix/3.x.x` branch.
- [ ] Decide on a version number based on the current version number and the common rules defined in [Semantic Versioning](https://semver.org). E.g. `3.x.x`.
- [ ] Update this ticket name to reflect the new version number, following the pattern "Release `3.x.x`".
- [ ] Update `version` string (Eg: `3.x.x`) attribute in the `build.gradle.kts`
- [ ] Update the badges versions in [docs/README.md].
### Release Publication
- [ ] Create a new release draft on GitHub with the name `3.x.x` and tag: `3.x.x` (without the `release-` prefix).
- [ ] Write the proper release notes.
  - [ ] Use `Generate release notes` in the GitHub UI to generate the changes. Target branch should be `main`.
  - [ ] Remove chore and refactor commits from the release notes. (eg. merge to `main`, version bump, etc.)
  - [ ] Add `What's New` section if applicable.
  - [ ] Add `What's Changed` section if applicable.
  - [ ] Add `Bug Fixes` section if applicable.
- [ ] After reviewing the release draft, publish!!!
### Post Release Publication
- [ ] Check the docker image of the release automatically published to [Docker Hub](https://hub.docker.com/r/stellar/anchor-platform).
- [ ] If necessary, update the docker image version in [docs/README.md].
- [ ] If necessary, update the badges versions in [docs/00 - Stellar Anchor Platform.md].
- [ ] Checkout the `develop` branch, create a merge commit from `main` to `develop` and checkout to a new `chore/3.x.x` branch.
- [ ] Create the pull request `chore/3.x.x -> main`. DO NOT squash merge.
- [ ] Publish the new version in the [#release](https://stellarfoundation.slack.com/archives/C04ECVCV162) Slack channel.
