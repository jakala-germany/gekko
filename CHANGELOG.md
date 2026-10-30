# Changelog

## [Snapshot]

[Snapshot]: https://github.com/jakala-germany/gekko/compare/1.0.0...HEAD

**New**

- iPhones on a USB cable are supported (`-s <UDID>`, `--devices`, `gekko devices` lists them as `ios <UDID> (physical)`): gekko installs, launches, screenshots and sets dark mode and location through `xcrun devicectl` (Xcode 27, iOS 17 or later) and reaches the agent and the XCUITest runner through usbmuxd, so nothing listens on the network. The app links the `GekkoAgent` package in its debug build (a signed build cannot get the agent injected); a fresh start reinstalls `ios.deviceApp` (or `--app`, which also takes an `.ipa`); the runner is built for the team with `scripts/build-ios-runner.sh --device --team TEAMID --install`. `--record` and `setPermissions` are not available on an iPhone. `scripts/e2e-ios-device.sh` tries the sample tests on a phone
- New settings `ios.deviceApp` and `ios.deviceRunnerDir` (`GEKKO_IOS_DEVICE_RUNNER_DIR`)
- `setLocation` works on physical Android devices: the system runner feeds Android's mock location providers (`gps`, `network`), gekko switches it on as the mock location app with `appops` and ends the mock location when the test ends
- `gekko llm-check` asks the model of `--heal` one short question and prints the answer, so `GEKKO_LLM_*` (or the local CLI) can be tried without a device
- `GEKKO_LLM_CA_CERT` names the PEM file of a private CA for `--heal api`

**Changed**

- `setLocation` ends the simulated position when the test ends on iOS simulators too (it used to stay set)
- A system runner that does not start now shows the tail of its log in the error
- `--heal api` works over `https://` in the native binary: the request goes through `curl`, because Ktor CIO has no TLS on Kotlin/Native (the heal failed with "TLS sessions are not supported on Native platform"). `curl` must be on the `PATH`

## [1.0.0]

[1.0.0]: https://github.com/jakala-germany/gekko/releases/tag/1.0.0

First release. UI tests written once in YAML run on Android and on iOS simulators.

**Tests and flows**

- Steps with `say` (intent), `do` (exactly one action), `until` and `attempts`; reusable flows with parameters; test-level
  `uses:`; `platform:` per step; `if`, `optional`, `repeat`.
- Actions: `tap`, `doubleTap`, `longPress`, `type`, `eraseText`, `scroll`, `scrollUntilVisible`, `swipe`,
  `assertVisible`, `assertNotVisible`, `waitForAnimationToEnd`, `wait`, `back`, `pressKey`, `openLink`, `launchApp`,
  `stopApp`, `clearState`, `takeScreenshot`, `setDarkMode`, `setAirplaneMode`, `setOrientation`, `setLocation`,
  `setPermissions`, `copyText`, `hideKeyboard`, `launchPackage`, `stopPackage`.
- Selectors by `id`, `text`, `textContains`, `textMatches`, `action`, `within`, `index`, `enabled`, `checked`,
  `selected`. Scrolling uses reading directions (`down`, `up`, `start`, `end`, `left`, `right`).
- Variables: `${var.x}` from `copyText`, `${date(...)}`, `${random.email}`, `${random.number(n)}`, `${random.uuid}` and
  `${env.NAME}` for the variables `gekko.yaml` declares in `env:` (values are masked in all output).

**CLI**

- `gekko run` (tags, `--exclude-tags`, shards, several devices, `--every-device`, `--junit`, `--record`), `gekko check`,
  `gekko devices`, `install`, `uninstall`, `launch`, `tree`, `tap`, `type`, `scroll`, `assert`, `screenshot`,
  `gekko --version`.
- Exit codes: 0 passed, 1 a test failed, 2 usage, configuration or version problem, 3 device, agent or runner problem.
- `--heal claude|opencode|api` fills in for missing ids and reports what it used (`gekko-heal-report.md`,
  `--junit-healed`); assertions are only healed when the id is nowhere on the screen.

**Platforms**

- Android: in-app agent through the Gradle plugin `io.github.jakala-germany.gekko`, system runner (UiAutomator).
- iOS simulators: agent injected at launch, system runner (XCUITest).
- CLI for macOS arm64 and Linux x64; install with `scripts/install.sh` or, in CI, the setup action
  `jakala-germany/setup-gekko-cli-action`.
