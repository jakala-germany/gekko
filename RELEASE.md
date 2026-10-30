# Releasing

A release is a tag `X.Y.Z` on `main`. The version comes from the tag: no file has to be edited to change it, and the
`VERSION_NAME` in `gradle.properties` and `gradle-plugin/gradle.properties` stays `-SNAPSHOT`.

## One-time setup

The `release` environment of the repository (Settings → Environments) holds everything a release needs:

| Secret | What |
|---|---|
| `SONATYPE_CENTRAL_USERNAME`, `SONATYPE_CENTRAL_PASSWORD` | User token of the Central Portal account that owns the namespace `io.github.jakala-germany` |
| `GPG_SECRET_KEY`, `GPG_SECRET_PASSPHRASE` | Armored private signing key (`gpg --armor --export-secret-keys <KEYID>`) and its passphrase; the public key must be on a keyserver |

The environment requires reviewers and only accepts tags that look like `X.Y.Z`. Tags matching `*.*.*` can only be
created by release managers (repository ruleset), and releases are immutable (Settings → General).

## Release

1. Make sure `main` is green (CI: unit tests, Apple build, Android end to end, iOS end to end).

2. Update `CHANGELOG.md`:
    1. Change `Snapshot` to the version that is released, e.g. `[1.2.0]`.
    2. Point its link at the release: `[1.2.0]: https://github.com/jakala-germany/gekko/compare/1.1.0...1.2.0`.
    3. Add a new `Snapshot` section on the top, with the link `[Snapshot]: https://github.com/jakala-germany/gekko/compare/1.2.0...HEAD`
       and the placeholders `**New**` / `- Upcoming additions go here` and `**Changed**` / `- Upcoming changes go here`.
       The workflow takes the release notes from the section below `Snapshot`.

3. Commit and push to `main`: `git commit -am "Release X.Y.Z" && git push`.

4. Wait for CI on that commit, then tag exactly that commit and push only the tag:
   `git tag -am "Release X.Y.Z" X.Y.Z && git push origin X.Y.Z`

5. The workflow `Release` runs the checks again, builds the binaries and the runners on GitHub-hosted runners and then
   waits for the approval of the `release` environment. After the approval it:
    1. attests the build provenance and creates the GitHub release as a draft with the binaries, the runners and
       `SHA256SUMS`;
    2. publishes `gekko-protocol`, `gekko-transport-jvm`, `gekko-agent-android` and `gekko-gradle-plugin` to Maven
       Central (a published version cannot be changed or removed);
    3. publishes the GitHub release.

6. Check the result:
    - the GitHub release has all assets, `gh attestation verify gekko-macos-arm64 --repo jakala-germany/gekko` passes;
    - central.sonatype.com → Deployments shows the deployment as published (the artifacts appear on
      search.maven.org after about half an hour);
    - the install script works with the new version: `curl -fsSL https://raw.githubusercontent.com/jakala-germany/gekko/X.Y.Z/scripts/install.sh | bash -s -- X.Y.Z && gekko --version`.

7. Release the setup action with the same version, after the GitHub release is published: follow the `RELEASE.md` of
   `jakala-germany/setup-gekko-cli-action`. Then replace the pinned commit and the version in `README.md`,
   `skills/gekko-setup/` and its workflow templates (`gh api repos/jakala-germany/setup-gekko-cli-action/commits/X.Y.Z --jq .sha`).

## When something fails

- Before the Maven Central step: fix the problem and re-run the failed jobs; the draft release is reused.
- After the Maven Central step: that version exists for good. Fix the problem and release the next patch version.
- A dry run builds and tests everything without publishing: run the `Release` workflow manually (Actions → Release →
  Run workflow) with a version like `0.0.0-dryrun`.
