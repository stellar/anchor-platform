---
name: Release a New Version
about: Publishing a new release
title: 'Release a New Version'
labels: ''
assignees: ''

---
<!-- Please Follow this checklist before making your release. Thanks! -->

## Publish a New Release
### Release Preparation
- [ ] Decide on a version number based on the current version number and the common rules defined in [Semantic Versioning](https://semver.org). E.g. `2.2.x`.
- [ ] Update this ticket name to reflect the new version number, following the pattern "Release `2.2.x`".
- [ ] Update `version` string (Eg: `2.2.x`) attribute in the `build.gradle.kts`
- [ ] Code freeze and cut a branch for the new release out of the `develop` branch, following the Gitflow naming pattern `release/2.2.x`.
- [ ] Update the badges versions in [docs/README.md].
- [ ] In general, only bug fixes and security patches will be applied to the `release/2.2.x` branch.
### Release Publication
- [ ] DO NOT RELEASE before holidays or weekends! Mondays and Tuesdays are preferred.
- [ ] Create a new release draft on GitHub with the name `2.2.x` and tag: `2.2.x` (without the `release-` prefix).
- [ ] Write the proper release notes.
- [ ] After reviewing the release draft, publish!!!
### Post Release Publication
- [ ] Check the docker image of the release automatically published to [Docker Hub](https://hub.docker.com/r/stellar/anchor-platform).
- [ ] If necessary, update the badges versions in [docs/00 - Stellar Anchor Platform.md].
- [ ] Create the pull request `release/2.2.x -> main`: this should require two approvals. DO NOT squash merge.
- [ ] Create another pull request `release/2.2.x -> develop`: AFTER the release branch is merged with the `main` branch. DO NOT squash merge.
- [ ] (Optional) Generate the new Anchor Platform documentation version in the stellar-docs repository if this is a new major or minor version.
- [ ] (Optional) You'll need to manually publish a new version of the SDK to [Maven Central](https://search.maven.org/search?q=g:org.stellar.anchor-sdk) in the `legacy/release-1.0`.
