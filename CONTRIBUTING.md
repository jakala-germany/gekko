# Contributing to gekko

Thank you for your interest in gekko. Issues, pull requests and ideas are welcome.

- Search the existing issues before you file a bug.
- For a large change, open an issue first and describe what you plan.
- Be kind: see the [Code of Conduct](CODE_OF_CONDUCT.md).
- Report security problems privately, see [SECURITY.md](SECURITY.md).

## Development setup

| For | You need |
|---|---|
| CLI, protocol, transport, Gradle plugin | JDK 17 or newer (the Gradle wrapper is included) |
| Android agent, system runner, sample app | Android SDK (`ANDROID_HOME`), an emulator for the end-to-end run |
| iOS agent, system runner, sample app | macOS, Xcode, `brew install xcodegen` |

```
./gradlew ktfmtCheck :protocol:jvmTest :cli:jvmTest :transport-jvm:test
(cd gradle-plugin && ../gradlew test)
./gradlew :cli:linuxX64Test            # Kotlin/Native tests of the shipped binary
./gradlew :sample-android:assembleDebug :system-runner-android:assembleDebug
./scripts/build-ios-runner.sh          # iOS runner and agent (macOS)
./scripts/check-hygiene.sh             # no secrets or internal names in tracked files
actionlint -config-file .github/actionlint.yml                                        # the workflows
actionlint -config-file .github/actionlint.yml skills/gekko-setup/assets/workflows/*.yml   # and the templates
```

Format Kotlin with `./gradlew ktfmtFormat && ./gradlew -p gradle-plugin ktfmtFormat`; CI fails on unformatted files.

## Pull requests

1. Fork the repository and create a topic branch.
2. Add or update tests for your change.
3. Make sure the commands above pass.
4. Update `README.md`, the skills under `skills/` and the `Snapshot` section of `CHANGELOG.md` when users can notice
   the change. A change to the YAML format, the CLI flags, the exit codes or the Gradle plugin DSL is a breaking change
   after 1.0 and needs a major version.
5. Describe what changed and how you tested it.

## Commit messages

To keep the history readable at a glance, a commit message is one line that says precisely what changed, 100 characters at most.

## Examples and tests

Use neutral ids and texts (`Search.Field`, `Search.Result.0`). Do not commit real customer names, account data, API keys
or screenshots of real apps.
