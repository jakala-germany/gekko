# Changelog

## [Snapshot]

[Snapshot]: https://github.com/jakala-germany/gekko/compare/1.2.0...HEAD

**New**

- Panes: the screens of the app as page objects. A pane is a YAML file in `panes/` (`panesDir` renames the folder; its path is its name, `panes/checkout/payment.yaml` is `checkout/payment`) with named `elements` (one selector, or one per platform with `{ android, ios, web }`), an `identity` (the elements that prove the screen is shown) and `actions` (named steps with `params`). Steps refer to elements with `tap: auth/login.submit` or `{ element: auth/login.submit, index: 2 }` (the keys of the step are added to the element's, the step wins on a clash), check a screen with `assertPane: auth/login`, `until: { assertPane: ... }` and `if: { pane: ... }`, and call actions with `use: auth/login.signIn` and `with:` (also in `uses:` and in the `setup` and `teardown` of a group). An action waits for the identity of its pane before its first step. See "Panes" in the format reference
- Panes in the diagnostics: `gekko check` names unknown panes, elements and actions with a hint and warns about elements, actions and panes that nothing uses; a failed step names the element and what it stands for and the `current pane`; `gekko tree` marks the elements of the panes (`<- auth/login.email`) and prints the current pane; the heal report names the element and the pane file to fix
- The `gekko-author` skill reads `panes/`, writes steps with elements and pane actions and puts new elements into the pane instead of the test; the test-repo template's `AGENTS.md` describes the folder. `docs/how-it-works.md` has a section and an illustration for panes
- Network mocks: a `mock` step answers the requests of the app that match a URL (a glob with `*`, optionally a `method` and a `bodyContains` for GraphQL) with the content of a file below `mocks/` (`mocksDir` renames the folder), with an optional `status`, `headers` and `delayMs`; `clearMocks` takes every mock away. A test always undoes every mock when it ends, whether it passed or failed. Mock steps at the very start of a test (also in flows) are set before the app starts, so the requests the app makes while it starts are answered too. Android (OkHttp and `HttpURLConnection` of a debuggable build with the Gradle plugin, emulators and devices), iOS simulators (`URLSession`) and the web (every request of the page) are covered; a request that matches no mock goes to the real backend. The log shows every answered request and a mock that no request used is a warning (also in the JUnit file). See "Network mocks" in the format reference
- `${now(+10m)}`, `${now(-1d, 'HH:mm')}` and `${now(+10m, epochMillis)}` give a point in time relative to the clock of the device, for mock files whose times have to stay in the future
- `gekko { networkMocks.set(false) }` in the Gradle plugin switches off the OkHttp and `HttpURLConnection` hooks that the mocks need (on by default in the variants that get the agent of the same release; with `version` or `agent` set it is off unless you switch it on, because the hooks call classes that only the agent of the same release has)
- `gekko check` reports a mock file that is missing, a placeholder in it that is written wrong and a file that is no UTF-8 text (mock files are text only)
- The sample apps make a request to a host that does not exist, and `network-mock` tests show the three platforms answering it from files

**Changed**

- Android: the agent no longer reports rows of a lazy list that scrolled out of view as on screen. Compose keeps them in its tree without placing them, so their old positions made `assertNotVisible` on a scrolled-away row fail (seen on Android 15 and 16 emulators)
- Upcoming changes go here

## [1.2.0]

[1.2.0]: https://github.com/jakala-germany/gekko/compare/1.1.0...1.2.0

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
- The CLI reference marks the diagnostic and internal environment variables as not covered by the compatibility promise and lists `GEKKO_MOCK_LOCATION`, `GEKKO_TARGET_BUNDLE_ID` and `GEKKO_TAP_VERIFY`

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
