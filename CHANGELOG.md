# Changelog

## [Snapshot]

[Snapshot]: https://github.com/jakala-germany/gekko/compare/1.1.0...HEAD

**New**

- Web: gekko tests web apps in Chromium, Firefox and WebKit through Playwright, with the same YAML tests. A web app needs nothing built in: `app.web` in `gekko.yaml` names a URL (or an HTML file or folder of the project, served on `127.0.0.1`), `gekko install --platform web -s <browser>` installs Playwright and the browser (Node.js 18 or newer), and `gekko run --platform web -s firefox` or `--devices chromium,firefox,webkit` runs the tests. A web device is a named browser session that outlives a CLI call, so `gekko launch`, `gekko tree` and `gekko tap` work step by step. Elements are named by `data-testid` (or `flt-semantics-identifier`, see `web.idAttributes`), text and accessible labels
- Web: browser dialogs (`alert`, `confirm`, `prompt`, `beforeunload`) show up as the topmost window and are answered with `tap: { text: OK, system: true }`; taps, typing and scrolling bring an element of the page into view; `setDarkMode`, `setAirplaneMode` (offline), `setOrientation`, `setLocation`, `setPermissions`, `device.language`, `device.timeFormat`, `back`, `pressKey`, `openLink` and video (`--record`, as `video.webm`) work; failed tests also leave `console.txt`
- `platform:` on a step or an action takes a list: `platform: [android, web]`. A per-platform id has to name every platform that has an `app:` entry in `gekko.yaml` (Android and iOS as before)
- `scripts/install.sh --web[=chromium,firefox,webkit]` also installs Playwright and the browsers; `gekko devices` lists running web sessions
- `scripts/e2e-web.sh` and a web end-to-end job in CI run the sample tests in all three browsers; `sample-web/` is the sample app (the same ids as the Android and iOS samples), with web-only tests for dialogs, navigation, dark mode and page scrolling
- Web: `web.session: shared` (or `GEKKO_WEB_SESSION=shared`) keeps one browser context for all tests of a run, in memory only: log in once instead of in every test, or log in by hand in a visible browser (`GEKKO_WEB_HEADED=1 gekko launch`) and run the suite in that session
- Groups: a folder of tests with a `group.yaml` (`setup`, optional `teardown` and `name`) shares one start state, typically a login. The setup flows run once, the tests follow in one session without clearing the app data (a browser keeps its context), and a failed setup skips the tests (`SKIP`, JUnit `skipped`) instead of failing each. `platform:` in the `group.yaml` (`web` or a list) limits a group to those platforms, so its tests need no `platform:` on every step. A group is never split over devices or shards. The setup and teardown are reported as tests of their own
- `app.web` may name a declared variable (`https://app-${env.STAGE}.example.com/`), so one project serves every environment
- The mock location test runs on the web too (tag `location` instead of `android-only`)

**Changed**

- `Device.freshStart` takes `keepState` and `Device.endTest(config, keepSession)` is new (for groups; the defaults keep the old behavior). A test result can be `skipped`; the JUnit file has a `skipped` count
- The element lookup and waiting code of the Android agent (`com.jakala.gekko.device`: `ElementLookup`, `Waiting`, `Scrolling`, `NodeFacts`) moved from `gekko-transport-jvm` to `gekko-protocol`, which the web agent in the CLI shares; the package stays, so nothing changes for users of `gekko-transport-jvm`
- The UI node has an optional `clip` (where its ancestors cut it off) and the protocol a `wheel` command; only the web agent uses them, so the protocol number stays 1

## [1.1.0]

[1.1.0]: https://github.com/jakala-germany/gekko/compare/1.0.0...1.1.0

**New**

- `setLocation` works on physical Android devices: the system runner feeds Android's mock location providers (`gps`, `network`), gekko switches it on as the mock location app with `appops` and ends the mock location when the test ends
- `gekko llm-check` asks the model of `--heal` one short question and prints the answer, so `GEKKO_LLM_*` (or the local CLI) can be tried without a device
- `GEKKO_LLM_CA_CERT` names the PEM file of a private CA for `--heal api`

**Fixed**

- The Android agent and the system runner answered no request on a device below API 33: the server read requests with `ByteArrayOutputStream.toString(Charset)`, which Android only has from API 33, and the error was swallowed, so every connection closed without a reply ("In-process agent did not respond")
- `setDarkMode`, `setAirplaneMode` and `device.language` fail with the API they need (29, 30, 33) on an older Android device instead of doing nothing

**Changed**

- The Android agent has minSdk 21 (was 26), so an app with a lower minSdk no longer needs `tools:overrideLibrary="com.jakala.gekko.agent"`; remove it if an earlier build asked for it. The system runner needs API 24 (was 26), and `gekko` stops with a clear error on an emulator or device older than that
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
