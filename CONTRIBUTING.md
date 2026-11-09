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
| Web agent (`agent-web/`), web end-to-end run | Node.js 18 or newer; `./scripts/e2e-web.sh` installs Playwright and the browsers |
| Windows binary (`mingwX64`) | Any host links it (Kotlin/Native cross-compiles); its tests run on Windows only (`./gradlew.bat :cli:mingwX64Test`, or `./gradlew` in Git Bash) |

```bash
./gradlew ktfmtCheck :protocol:jvmTest :cli:jvmTest :transport-jvm:test
(cd gradle-plugin && ../gradlew test)
./gradlew :cli:linuxX64Test                    # Kotlin/Native tests of the shipped binary
./gradlew :cli:mingwX64Test                    # on Windows: the tests of the Windows binary
./gradlew :cli:linkReleaseExecutableMacosArm64 :cli:linkReleaseExecutableLinuxX64 :cli:linkReleaseExecutableMingwX64   # the CLI binaries
./gradlew :sample-android:assembleDebug :system-runner-android:assembleDebug
./scripts/build-sample-ios.sh                  # sample-ios/build/Sample.app (macOS)
./scripts/build-ios-runner.sh                  # dist/gekko-ios-runner and dist/gekko-ios-agent (macOS)
./scripts/e2e-android.sh                       # the sample tests on a running emulator; e2e-ios.sh: a booted simulator
./scripts/e2e-web.sh                           # the sample tests in Chromium, Firefox and WebKit (after the CLI link)
./scripts/check-hygiene.sh                     # no secrets or internal names in tracked files
actionlint -config-file .github/actionlint.yml                                             # the workflows
actionlint -config-file .github/actionlint.yml skills/gekko-setup/assets/workflows/*.yml   # and the templates
```

Running programs and the operating system calls of the CLI are C code in `cli/src/interop/` (`host-posix.def` and `host-windows.def`, which declare the same functions); `cli/src/nativeHost/` is the Kotlin side that all native targets share. Git keeps text files with LF on every host (`.gitattributes`); the scripts run in Git Bash on Windows.

`e2e-web.sh` installs Playwright and the browsers; on Linux `GEKKO_WEB_WITH_DEPS=1` adds the browsers' system libraries.

Format Kotlin with `./gradlew ktfmtFormat && ./gradlew -p gradle-plugin ktfmtFormat`; CI fails on unformatted files. The
style is [ktfmt](https://github.com/facebook/ktfmt) (kotlinlang style, 120 columns), set up by the convention plugin
`build-logic/src/main/kotlin/gekko-format.gradle.kts`.

## Repository layout

| Path | What |
|---|---|
| `protocol/` | Shared command/response/UI-node types, `SettleTracker`, `VisibilityWaiter`, element lookup and waiting (`com.jakala.gekko.device`) |
| `transport-jvm/` | Minimal HTTP command server of the Android agent and runner (over a private socket) |
| `agent-android/` | Android agent (Compose semantics and View hierarchy) |
| `gradle-plugin/` | `io.github.jakala-germany.gekko` plugin |
| `system-runner-android/` | UiAutomator runner |
| `Package.swift`, `agent-ios/` | iOS agent (Swift package, dynamic library) |
| `system-runner-ios/` | XCUITest runner (XcodeGen project) |
| `agent-web/` | The web agent: `page.js` (read in every page) and `bridge.mjs` (the Playwright bridge); both are built into the CLI |
| `cli/` | The `gekko` CLI |
| `sample-android/`, `sample-ios/`, `sample-web/`, `examples/` | Sample apps and tests; CI runs them end to end |
| `skills/` | Claude skills `gekko-setup` and `gekko-author`, with the test-repo and workflow templates |
| `scripts/` | `install.sh` (the installer), `build-ios-runner.sh`, `build-sample-ios.sh`, `e2e-android.sh`, `e2e-ios.sh`, `e2e-web.sh`, `check-hygiene.sh` |

The Maven artifacts are `gekko-agent-android`, `gekko-protocol`, `gekko-transport-jvm` and `gekko-gradle-plugin` (group
`io.github.jakala-germany`); Kotlin and Swift sources keep the package `com.jakala.gekko`.

## Continuous integration

The CI of this repository (`.github/workflows/ci.yml`, GitHub-hosted runners) runs on every pull request: hygiene
(secrets scan, actionlint), unit tests, the Apple build and tests, the Android end-to-end run and the web end-to-end run
(three browsers). The iOS end-to-end run runs on `main` and before a release. Releases are described in [RELEASE.md](RELEASE.md), changes in
[CHANGELOG.md](CHANGELOG.md).

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
