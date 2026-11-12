# gekko CLI

The YAML format, `gekko.yaml`, the commands, flags and exit codes on this page, the documented environment variables, the Gradle plugin DSL and the Maven coordinates are part of the 1.0 compatibility promise (semver). Not covered are the diagnostic and internal environment variables (marked in "Environment variables") and the wire protocol between the CLI and the agents: it is internal and versioned by a protocol number (see "Versions").

## Install and requirements

- Testers: `curl -fsSL https://raw.githubusercontent.com/jakala-germany/gekko/1.3.0/scripts/install.sh | bash -s -- 1.3.0` (macOS arm64, Linux x64 or Windows x64; or run `scripts/install.sh [VERSION]` from a checkout; the default version is `latest`). On Windows, `install.sh` in Git Bash hands over to `scripts/install.ps1` (PowerShell: `& ([scriptblock]::Create((irm <raw URL of install.ps1>))) -Version X.Y.Z`; `-Uninstall`, `-Web chromium,firefox`), which installs `gekko.exe` to `%USERPROFILE%\.gekko\bin` (or `$GEKKO_BIN_DIR`), adds that folder to the user `PATH` and puts the Android runner to `%USERPROFILE%\.gekko\runners`; `~/.gekko` below is `%USERPROFILE%\.gekko` there. Elsewhere it installs the CLI to `$GEKKO_BIN_DIR` (default `~/.local/bin`) and the Android system runner and, on macOS, the iOS runner and the iOS agent to `$GEKKO_HOME/runners` (default `~/.gekko/runners`): `gekko-system-runner.apk`, `gekko-ios-runner/`, `gekko-ios-agent/GekkoAgent.framework`. Every download is checked against the `SHA256SUMS` of the release; `GEKKO_VERIFY_ATTESTATION=1` (needs `gh`) also verifies the build provenance; `install.sh --uninstall` removes the CLI, the runners and the web files (`~/.gekko/token` stays); `install.sh --web[=chromium,firefox,webkit]` also installs Playwright and the browsers for web tests (needs Node.js 18 or newer).
- The CLI finds the installed Android system runner and iOS runner **without any setting**. The iOS agent is injected into the simulator app only when asked for: `ios.agentFramework: installed` (or `GEKKO_IOS_AGENT`). `android.runnerApk` and `ios.runnerDir` accept `installed` as well (the same as leaving them out).
- Release assets on [GitHub](https://github.com/jakala-germany/gekko/releases): `gekko-macos-arm64`, `gekko-linux-x64`, `gekko-windows-x64.exe`, `gekko-system-runner.apk`, `gekko-ios-runner.zip`, `gekko-ios-agent.zip` and `SHA256SUMS`. The web bridge and the page script are part of the CLI binary, so there is no separate web asset (Playwright and the browsers are downloaded by `gekko install --platform web`). Verify a download with `gh attestation verify <file> --repo jakala-germany/gekko`.
- CI: the action `jakala-germany/setup-gekko-cli-action` (tag = gekko version, e.g. `@1.3.0`). Every download is verified against the sha256 digest GitHub reports for the release asset; the step fails when it differs or when the release reports none. The inputs `cli-sha256`, `system-runner-sha256`, `ios-runner-sha256` and `ios-agent-sha256` hard-pin a file (the hashes are in `SHA256SUMS`); `token` and `repository` are further inputs (see the action's README). Pin the action to the commit of its tag.
- Host: macOS arm64, Linux x64 or Windows x64 (Windows 10 or newer, no ARM64 yet; iOS needs macOS). The CLI is a native binary and needs no JVM. Android needs `adb`. iOS needs macOS with Xcode and simulators only (iOS 15 and later). The web needs Node.js 18 or newer; `gekko install --platform web` downloads Playwright and the browsers. The CLI starts `adb`, `xcrun simctl`, `xcodebuild`, `unzip`, `curl` (`--heal api`), `node` and `npm` and the `claude` or `opencode` CLI directly, never through a shell (every process gets a time limit of 300 seconds). On Windows it finds them through `PATH` and `PATHEXT`, and runs `.cmd` files (what npm installs) through `cmd.exe`; an argument with a line break cannot be passed to those. Xcode and `xcodegen` are only needed to *build* the runners from source; users get them from the release.
- App side: Android agent `minSdk` 21; the emulator or device that runs the tests needs API 24 or newer; gekko is tested with AGP 9.x, Gradle 9.x and Kotlin 2.4.

## Global options

On every command that talks to a device (`tree`, `tap`, `type`, `scroll`, `assert`, `screenshot`, `install`, `uninstall`, `launch`, `run`):

| Option | Meaning |
|---|---|
| `-s`, `--serial` | adb serial, simulator UDID or browser. A booted simulator's UDID selects iOS by itself, a browser name selects the web. |
| `--platform android\|ios\|web` | Default android; ios when `-s` is a booted simulator, web when `-s` is a browser (`chromium`, `firefox`, `webkit`, with `-2`, `-3` for more of one; without `-s` the browser is `web.browser`). |
| `--project DIR` | Directory with `gekko.yaml` (default `.`). Every command except `devices`, `screenshot` and `--version` needs `gekko.yaml` and stops with exit code 2 without it. |

Without `-s`, Android uses the one attached device, iOS the one booted simulator and the web the browser of `web.browser`; with several devices, the CLI stops and asks for `-s` (it does not guess).

## Commands

| Command | Purpose |
|---|---|
| `gekko devices` | Attached adb devices, booted simulators and running web sessions, one per line: `android <serial>`, `ios <UDID>`, `web <browser>`; a TV (an Android TV, an Apple TV simulator) ends with ` tv`. On a TV `tap` and `scroll` fail (use `press-key`), and `type` needs the focus on the field |
| `gekko tree [--json]` | The current UI as the active driver sees it: role, `id=`, `text=`, `desc=`, `actions=`, `disabled`, `focused` (the element that has the input focus; not on iOS system-runner trees). Lines that are an element of a pane (`panes/`) end with `<- pane.element`, and a last line `current pane: ...` names the panes whose identity is on screen (not with `--json`) |
| `gekko tap SELECTOR` | Tap an element; when the in-app agent's accessibility tap is not handled, the tap is repeated as a real touch through the system runner |
| `gekko type SELECTOR "text"` | Type into an element (replaces its text) |
| `gekko type-focused "text"` | Type at the cursor of the field that has the focus (adds to its text); waits up to `--timeout` for a field to get the focus, `--system` uses the system runner |
| `gekko press-key KEY [--times N]` | Press a key: `enter`, `backspace` (erases to the left), `tab`, `back`, `home`, a button of a TV remote (`up`, `down`, `left`, `right`, `select`, `playPause`, `menu`) or an Android key code number. `--until-id ID` / `--until-text TEXT` press until that element has the focus (at most `--max N` times, default 20; fails as soon as a press did not move the focus; not with `--times`). `enter`, `backspace` and `tab` go to the field that has the focus and wait up to `--timeout` for one; a key the in-app agent cannot press goes to the system runner, `--system` uses it at once |
| `gekko scroll SELECTOR [--direction D] [--distance DP]` | Scroll the container of an element; `--direction` is where the wanted content is: `down` (default), `up`, `start`, `end`, `left`, `right`; `--distance` in dp, points on iOS, CSS pixels on the web (default 400) |
| `gekko assert SELECTOR [--gone] [--focused]` | Wait until the element is visible (or, with `--gone`, not visible; with `--focused`, has the focus: the failure says where the focus is); exit 1 when it is not within `--timeout` |
| `gekko screenshot FILE` | Save a screenshot as PNG |
| `gekko install [APP]` | Install the app under test and keep its data: an `.apk`, an `.app`, or a `.zip` that contains an `.app` (CI build products); without `APP` the `android.apk` / `ios.app` of `gekko.yaml`. `--platform web -s <browser>`: installs Playwright (into `~/.gekko/web`) and that browser, needs Node.js 18+, works without `gekko.yaml`; `--with-deps` adds the system libraries (Linux, uses sudo) |
| `gekko uninstall` | Remove the app, the system runner and the port forwards from the device; fails when something is left. Web: stops the browser session |
| `gekko launch [--fresh]` | Start the app from `gekko.yaml`; `--fresh` clears its data first and grants the configured permissions. Prints `ok` once the agent answers |
| `gekko run [PATH...] [flags]` | Run YAML tests, see below |
| `gekko check [FILE...] [--project DIR]` | Read all tests and the flows they use, no device needed: broken YAML, unknown keys, steps with two actions or none, missing flows or parameters, flows that call themselves, and the panes: unknown panes, elements and actions with a hint, an element without a selector for a platform, a pane file that cannot be read, wrong params of an action. Prints `N tests ok` or the problems and exits 2. For the whole project it also prints `warning:` lines for elements, actions and panes that nothing uses (they do not change the exit code). It reads `${env.NAME}` values, so they must be set |
| `gekko llm-check [--heal claude\|opencode\|api]` | Ask the model of `--heal` (default `api`) one short question and print the answer, no device needed: tries `GEKKO_LLM_*` or the local CLI. Exit 3 when the model does not answer, 2 when it is not configured |
| `gekko --version` | The version of the CLI |

`SELECTOR` for `tap`, `type`, `scroll` and `assert` is one or more of `--id`, `--text`, `--text-contains`, `--text-matches` (regular expression for the whole text), `--action` (`click`, `setText`, `scroll`), `--within ID`, plus `--index N`, `--timeout MS` (default 5000) and `--system` (use the system runner: permission dialogs, the browser). At least one of `--id`, `--text`, `--text-contains`, `--text-matches`, `--action` is required.

The driver is chosen per call: the in-app agent while the app has the focus, the system runner (UiAutomator / XCUITest) for everything else. On Android the agent and the runner listen on private sockets (`gekko-<appId>`, `gekko-system-runner`) that only adb can reach. Every request and answer is signed with a key that only the CLI of the user and the servers it started hold, so a program that listens on a port first is refused ("did not prove to be the gekko agent": update the agent and the Gradle plugin, or stop the program that holds the port). On the web there is one driver: the web bridge (Node and Playwright) that the CLI starts on `127.0.0.1` with the per-user token; native browser dialogs are answered with `system: true` through the same bridge.

### `gekko run`

```
gekko run [PATH...] [--tags a,b] [--exclude-tags a,b] [--shard i/n]
          [--junit FILE] [--junit-healed FILE] [--output DIR]
          [--heal claude|opencode|api]
          [--devices ids|all] [--every-device]
          [--install] [--app FILE] [--uninstall]
          [--record none|failures|all]
          [-s SERIAL|UDID] [--platform android|ios|web] [--project DIR]
```

| Flag | Meaning |
|---|---|
| `PATH...` | Test files or folders (default: the `testsDir` of `gekko.yaml`, found recursively). |
| `--tags a,b` | Run tests that have any of these tags. |
| `--exclude-tags a,b` | Skip tests that have any of these tags; applied after `--tags`. |
| `--shard i/n` | Run the i-th of n parts, for example `1/3`. The tests (sorted by path, after the tag filters) are dealt out round-robin. A shard without tests (more shards than tests) exits 0 with a note and writes empty JUnit files. |
| `--junit FILE` | JUnit XML. A failed test is a `failure`, an infrastructure problem an `error`; with several devices `classname` is the device. On a single device the file is rewritten after every test, so a run that is killed still leaves one. |
| `--junit-healed FILE` | A second JUnit file with one *failed* testcase per healed step (empty, `tests="0"`, without `--heal` or when nothing was healed). |
| `--output DIR` | Where failure artifacts go (default `gekko-results`; with `--devices`: one subfolder per device). |
| `--heal claude\|opencode\|api` | Fill in for missing ids with a model, see "Heal". |
| `--devices a,b,c` or `all` | Spread the tests over adb serials, simulator UDIDs and browsers (`chromium,firefox,webkit`), or every attached device, booted simulator and running web session. Every platform, and every browser engine, has its own queue (every engine runs the whole selection) and the first free device takes the next test, so Android, iOS and the web run at the same time and the order is not predictable. Not combinable with `-s`. |
| `--every-device` | With `--devices`: every device runs every test; the run passes only when every test passes on every device. Prints `[device] x/y passed` per device. |
| `--install` / `--app FILE` | Install the app before the tests: `android.apk` / `ios.app` of `gekko.yaml`, or `FILE` (implies `--install`). Repeat `--app` for several platforms: an `.apk` goes to Android devices, an `.app` or `.zip` to iOS simulators; with `--devices` a platform without a build keeps what is installed. A web app is not installed from a build: it is opened from `app.web`. |
| `--uninstall` | Remove the app, the runner and the port forwards when the run is done. |
| `--record none\|failures\|all` | Keep a video of the screen: of failed tests only, or of every test (default none). Saved as `video.mp4` (`video.webm` in a browser) next to `screenshot.png` and `tree.txt`. Android records at most 180 seconds per test. |
| `-s`, `--platform`, `--project` | As above. |

- Every test starts from cleared app data (on the web: a new browser context, unless `web.session: shared` or a group says otherwise), applies `device:` settings, launches the app, and quits it when done.
- `gekko run` validates all tests and flows like `gekko check` before it starts anything; a problem is exit code 2.
- With no matching test the run stops with "No tests found" (exit 2).

## Output of a run

Passing tests print `PASS name (ms)`; a failed one prints `FAIL name: reason`, and the last line is `N/M passed`. A failed test leaves, in `<output>/<test>/` (the test name with characters outside `A-Za-z0-9_.-` replaced by `_`):

- `failure.json`: `test`, `path`, `step`, `error`, `kind` (`test` when a step failed, `infrastructure` when the device, the agent or the system runner failed)
- `screenshot.png` and `tree.txt` (what the agent saw)
- `video.mp4` with `--record` (`video.webm` in a browser)
- `console.txt` on the web: the console output and page errors of the failed test (secrets masked)
- the files of `takeScreenshot` steps (also for passing tests)

With `--heal` and at least one healed step, `<output>/gekko-heal-report.md` lists them (Markdown-escaped: model output and app texts cannot become links, mentions or HTML) and the console shows a warning block. In CI upload `gekko-results` as an artifact when the job fails.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | All tests passed (also an empty shard, and healed steps, which pass with a warning). |
| 1 | A test failed (a step failed, an assertion did not hold); also a failed `gekko assert` or a rejected `tap`/`type`/`scroll`. |
| 2 | Usage, YAML or configuration problem: a wrong flag, a broken test or flow (found when it loads), a missing `gekko.yaml`, an undeclared or unset `env:` variable, no tests found, a CLI/agent version mismatch found by a single-device command. |
| 3 | Device, agent or runner problem, or the app crashed: no device, `adb` or `simctl` failing, "Agent did not respond", a runner that does not start, the web bridge or Playwright failing to start. `gekko run` ends with 3 when any test ended this way, even if another failed normally. |

## Versions

The CLI says `hello` to the agent in the app and to the system runner before it uses them. The answer carries a protocol number; the CLI refuses a peer with another number and says what to update:

- agent older than the CLI: "Update the gekko Gradle plugin of the app to <CLI version> and rebuild the app."
- runner older than the CLI: "Remove the runner (`gekko uninstall`); the CLI installs the matching one on the next run."
- agent or runner newer than the CLI: "Update the gekko CLI to <their version> or newer."

The protocol number is not the release number; most releases keep it. Keep the CLI (the version you gave `install.sh`, or `version:` of the setup action), the plugin version of the app and the runners from the same release anyway. A mismatch found by `tree`, `tap`, `launch` and the other single-device commands is exit code 2; a `gekko run` that meets it while starting a test reports that test as an infrastructure failure.

## `gekko.yaml`

Required, in the directory the CLI runs in (or `--project`). Unknown keys are an error. Relative paths are relative to the file.

```yaml
app:
  android: com.example.app            # applicationId of the debug build
  androidActivity: com.example.app.MainActivity   # launcher activity, when several exist
  ios: com.example.app                # bundle id
  web: https://staging.example.com/   # URL, or an HTML file or folder of the project
android:
  permissions: []                     # full names, granted after every data clear
  runnerApk: installed                # or a path; wins: GEKKO_RUNNER_APK. Left out: the installed runner
  apk: builds/app-debug.apk           # for gekko install / run --install
  stopApps: [com.android.chrome]      # force-stopped before and after every test
ios:
  permissions: [location]             # simctl privacy services granted after every data clear
  agentFramework: installed           # inject the installed agent; or a path; wins: GEKKO_IOS_AGENT
  app: builds/App.zip                 # an .app or a zip with one; for install / run --install
  runnerDir: installed                # or a path; wins: GEKKO_IOS_RUNNER_DIR. Left out: the installed runner
  tvosAgentFramework: installed       # Apple TV simulators: the tvOS agent, or a path; wins: GEKKO_TVOS_AGENT
  tvosRunnerDir: installed            # Apple TV simulators: the tvOS runner, or a path; wins: GEKKO_TVOS_RUNNER_DIR
web:                                  # all keys optional, see the table
  browser: chromium                   # chromium, firefox or webkit
  viewport: 1280x800
device:                               # for all tests; a test's own device: overrides it
  language: en-US                     # BCP 47 app language
  timeFormat: 24h                     # 12h or 24h; a time picker takes 20 only in 24h mode
defaultTimeoutMs: 10000               # waiting for a screen that is still loading
settleMs: 2500                        # quiet time after which a screen counts as done (0 = always wait the full timeout)
optionalTimeoutMs: 500                # how long optional steps, `if` and `repeat while` look for an element
tapIntervalMs: 0                      # minimum pause between taps; only for apps that throttle clicks, e.g. 650
testsDir: tests
flowsDir: flows
panesDir: panes                       # the screens of the app: elements, identity, actions
mocksDir: mocks                       # the files that `mock` steps answer with
env: [TEST_USER, TEST_PASSWORD]       # variables tests may read as ${env.NAME}
```

| Key | Default | Notes |
|---|---|---|
| `app.android`, `app.ios`, `app.web` | none | Needed for the platform you use. `app.web` is a URL (it may name a declared variable: `https://app-${env.STAGE}.example.com/`), or an HTML file or folder of the project that gekko serves on 127.0.0.1. |
| `web.browser` | `chromium` | `chromium`, `firefox` or `webkit`, for `--platform web` without `-s`. |
| `web.session` | `fresh` | `shared` keeps one browser context for all tests of a run (log in once; tests depend on their order; `GEKKO_WEB_SESSION` wins). |
| `web.idAttributes` | `[data-testid, flt-semantics-identifier]` | The attributes that name an element (the first present wins); the HTML `id` is not used. |
| `web.viewport` | `1280x800` | Window size in CSS pixels; only what is inside it counts as visible. |
| `web.device` | none | A Playwright device name to emulate (`iPhone 15`). |
| `web.headless` | `true` | `false` shows the browser window (or `GEKKO_WEB_HEADED=1`). |
| `web.permissions` | none | Permissions granted to every fresh browser context (`location`, `camera`, `microphone`, `notifications`, `clipboard`, or a Playwright name). |
| `web.playwright` | `installed` | A folder with Playwright in `node_modules`, instead of the one `gekko install --platform web` puts into `~/.gekko/web`. `GEKKO_PLAYWRIGHT_DIR` wins. |
| `app.androidActivity` | none | Without it the app is started through its launcher entry; set it when several launcher activities exist (for example one added by a debug library). |
| `android.runnerApk`, `ios.runnerDir` | the installed runner | A path, or `installed` for the runner that `scripts/install.sh` put into `$GEKKO_HOME/runners`. Without the key the installed runner is used. The environment variables below win. |
| `ios.tvosAgentFramework`, `ios.tvosRunnerDir` | the installed tvOS agent and runner | The same as `ios.agentFramework` and `ios.runnerDir` for an Apple TV simulator, which needs the builds for the tvOS simulator (dyld refuses an iOS library there). `ios.agentFramework: installed` serves both; an explicit iOS path is never used on an Apple TV (the run fails and names `ios.tvosAgentFramework`). `ios.app` stays the one setting for the app to install: pass the tvOS build as `gekko install <app>` or `--app`. |
| `ios.agentFramework` | none (no agent is injected) | A path to `GekkoAgent.framework`, or `installed` for the installed agent. The iOS agent is only injected when this key or `GEKKO_IOS_AGENT` is set. |
| `android.stopApps` | none | Other apps (a browser that showed a login page) that a failed test must not leave in front. |
| `defaultTimeoutMs` | 10000 | |
| `settleMs` | 2500 | |
| `optionalTimeoutMs` | 500 | |
| `tapIntervalMs` | 0 | |
| `testsDir`, `flowsDir` | `tests`, `flows` | |
| `panesDir` | `panes` | The folder of the pane files: the screens of the app with their elements, identity and actions, see [panes](../../gekko-author/references/format.md#panes). |
| `mocksDir` | `mocks` | The folder of the files that `mock` steps answer with, see [network mocks](../../gekko-author/references/format.md#network-mocks). |
| `env` | none | Names only, never values. Names starting with `GEKKO_`, `GITHUB_`, `ACTIONS_`, `RUNNER_`, `OP_`, `ANTHROPIC_`, `OPENAI_`, `AWS_`, `SONATYPE_`, `GPG_`, `NPM_` or `SSH_` are refused. |

Values of `env:` names come from the process environment first, then from a `.env` file next to `gekko.yaml` (`KEY=value` lines, `#` comments, optional quotes). Every used value is masked as `***` in console output, JUnit files, `failure.json`, `tree.txt`, heal prompts and reports; screenshots and videos are not masked.

## Environment variables

| Variable | Effect |
|---|---|
| `GEKKO_RUNNER_APK` | Android system runner APK; wins over `android.runnerApk` |
| `GEKKO_IOS_AGENT` | `GekkoAgent.framework` for simulators; wins over `ios.agentFramework` and switches the injection on |
| `GEKKO_IOS_RUNNER_DIR` | iOS system runner directory; wins over `ios.runnerDir` |
| `GEKKO_TVOS_AGENT`, `GEKKO_TVOS_RUNNER_DIR` | The same for Apple TV simulators; win over `ios.tvosAgentFramework` and `ios.tvosRunnerDir` |
| `GEKKO_HOME` | Where `scripts/install.sh` puts the runners and where the CLI looks for them: `$GEKKO_HOME/runners` (default `~/.gekko`) |
| `GEKKO_LLM_BASE_URL`, `GEKKO_LLM_MODEL`, `GEKKO_LLM_API_KEY`, `GEKKO_LLM_CA_CERT` | OpenAI-compatible endpoint for `--heal api` (the API key is optional for endpoints that need none; `GEKKO_LLM_CA_CERT` is the PEM file of a private CA) |
| `GEKKO_HEAL_MODEL` | Model for `--heal claude` (default `haiku`) and `--heal opencode` (`provider/model`, else the model configured in opencode) |
| `GEKKO_HEAL_AGENT` | opencode agent to use instead of gekko's own deny-all agent `gekko-heal` (the explicit opt-out) |
| `GEKKO_OPENCODE_COMMAND` | Launcher used instead of `opencode`, e.g. a script that sets an isolated config in CI |
| `GEKKO_WEB_SESSION` | Web: `fresh` or `shared`, wins over `web.session` |
| `GEKKO_PLAYWRIGHT_DIR`, `GEKKO_NODE`, `GEKKO_WEB_HEADED` | Web: the folder with Playwright (wins over `web.playwright`), the `node` binary, and `1` to show the browser window when a session starts |

Diagnostic and internal variables are not covered by the compatibility promise:

| Variable | Kind | Effect |
|---|---|---|
| `GEKKO_DEBUG` | diagnostic | Log which driver answers each command and which steps are skipped on this platform |
| `GEKKO_HEAL_DEBUG` | diagnostic | Directory where heal prompts are written (values masked) |
| `GEKKO_MOCK_LOCATION=1` | diagnostic | `setLocation` uses the system runner on an emulator too |
| `GEKKO_PORT`, `GEKKO_TOKEN`, `GEKKO_TARGET_BUNDLE_ID` | internal | Set by the CLI when it starts the iOS app and runner (a loopback port per simulator, the per-user token and the bundle id of the app under test); the iOS agent only starts when `GEKKO_PORT` and `GEKKO_TOKEN` are set. Do not set them by hand |
| `GEKKO_BRIDGE_TOKEN`, `GEKKO_BRIDGE_LOG` | internal | Set by the CLI when it starts the web bridge. Do not set them by hand |
| `GEKKO_TAP_VERIFY` | internal | A switch of the iOS agent. Do not set it by hand |

The setup action sets the runner variables for the job, so a test repository needs no copy of the runners in CI. With the install script nothing has to be set for the runners; only the iOS agent has to be asked for, with `ios.agentFramework: installed` in `gekko.yaml` or `export GEKKO_IOS_AGENT="$HOME/.gekko/runners/gekko-ios-agent/GekkoAgent.framework"`. The web needs no runner at all, only Node.js and Playwright (`gekko install --platform web`).

The variables of the install script itself (not read by the CLI): `GEKKO_BIN_DIR` (default `~/.local/bin`), `GEKKO_HOME`, `GEKKO_VERIFY_ATTESTATION=1`, `GEKKO_RELEASE_BASE_URL` (replaces the release URL, for tests).

## Heal

`--heal` always names where the model comes from; nothing is detected:

| Source | What runs |
|---|---|
| `--heal claude` | The local `claude` CLI (the tester's own subscription), small model by default (`GEKKO_HEAL_MODEL` to change it). Started with no tools, no MCP servers, no project settings and no session files, in a private temporary directory. |
| `--heal opencode` | The local `opencode` CLI with the model configured there, run with an agent that gekko defines for the call (`gekko-heal`): every permission is denied, so it can answer but cannot read files, run commands or fetch pages; project configuration is switched off. `GEKKO_HEAL_AGENT` names another agent instead. |
| `--heal api` | An OpenAI-compatible endpoint (`GEKKO_LLM_BASE_URL`, `GEKKO_LLM_MODEL`, `GEKKO_LLM_API_KEY`); gekko posts to `<base>/chat/completions`. This is the source for CI. The request goes through `curl` (the native binary has no TLS of its own), so the system's certificates apply; a private CA is named in `GEKKO_LLM_CA_CERT`. It is tested against a fake HTTPS endpoint, not against specific providers: try yours with `gekko llm-check` before you rely on it. |

If the named source is not usable (no such CLI on `PATH`, `GEKKO_LLM_*` not set) the run stops with a message (exit 2) before any test. When the model is unreachable the step fails as it would without `--heal`.

- Only a **missing element** is healed, never a wrong state or a timeout. A healed step passes with a warning, an entry in `gekko-heal-report.md`, and, with `--junit-healed`, a failed testcase in that second file. At most 15 heals per test.
- The model only chooses an existing element for `tap`, `type` and `scroll`: the kind of the step never changes and typed text always comes from the test. A `tap` with `system: true` that finds no element is skipped with a warning and an entry in the heal report (no model call): a permission dialog that did not appear is not an error under `--heal`. An `assertVisible` is healed only when its id is nowhere in the tree and its texts and states hold on the screen (checked in code), and the model confirms from the screen that the state is shown; otherwise it fails. Both `do` and `until` are healed: a healed action is followed by its `until` check, and a missing id there is checked like an assertion and reported as its own entry. `assertNotVisible`, `doubleTap`, `longPress`, `eraseText`, `scrollUntilVisible` and `swipe` are never healed.
- The prompt holds the step's `say`, the failed action and the screen listing (at most 12,000 characters, secrets masked). Model output and app texts are treated as untrusted data: the prompts tell the model never to follow instructions found on screen, and reports escape what they quote. Remember that this text leaves the machine for whatever model you named.
- Without `--heal`, a missing element fails and the message lists the closest ids and texts on screen (`closest on screen: id=Search.Field ...`), so nothing is ever guessed.

### Healed steps in CI

A healed step passes, so `--junit` stays all green and has no trace of the heal. `--junit-healed FILE` writes a second JUnit file with one *failed* testcase per healed step:

| Field | Value |
|---|---|
| Testcase name | `<test> › <say>` |
| Failure message | `<missing id> missing; used <what was used>` |
| Failure text | The model's reason |
| `classname` | The device, when several devices are used |

The file is empty (`tests="0"`) when nothing was healed or `--heal` is not used. Publish both with two JUnit report steps: `junit.xml` as the result check and `junit-healed.xml` as a separate check that only annotates and never fails the job, so every healed id shows up in the pull request. The same data is in `gekko-heal-report.md` for the job summary.

## In CI

The workflow templates are in `assets/workflows/` of the `gekko-setup` skill (see "Workflows" in its `SKILL.md`). They run on GitHub-hosted `ubuntu-latest` runners with an Android emulator (API 34, KVM switched on by a udev rule), and are Android-only; a web job needs no emulator (see below). The comment at the top of `run-tests.yml` and `app-ui-tests-smoke.yml` says where to change the runner. Never run pull requests from forks on a self-hosted runner that holds secrets.

- Pull requests of an app repository run the `smoke`-tagged tests (`app-ui-tests-smoke.yml`); pull requests of a test repository run the whole suite, sharded with `--shard i/n` (`pr.yml` and `nightly.yml` call the reusable `run-tests.yml`, two shards by default).
- `run-tests.yml` and `app-ui-tests-smoke.yml` run `gekko check` before they start the emulator, so a broken test or a missing `env:` value fails in seconds.
- Web tests need no emulator: a job on any runner with Node.js installs the browsers (`--with-deps` adds the system libraries on Linux) and runs the same suite. The browsers cost a download per job (cache `~/.cache/ms-playwright` and `~/.gekko/web` to save it). The web end-to-end job of [gekko's own `ci.yml`](https://github.com/jakala-germany/gekko/blob/main/.github/workflows/ci.yml) is a working example.

  ```yaml
  - uses: actions/setup-node@<commit> # v7.1.0
    with: { node-version: '22' }
  - uses: jakala-germany/setup-gekko-cli-action@<commit>
    with: { version: <version> }
  - run: |
      gekko install --platform web -s firefox --with-deps
      gekko run --platform web -s firefox --junit junit.xml     # or --devices chromium,firefox,webkit
  ```
- CI runs with `--heal api` when the endpoint is configured (`GEKKO_LLM_BASE_URL` and `GEKKO_LLM_MODEL` as repository variables and, if it needs one, `GEKKO_LLM_API_KEY` as a secret, given to the gekko step only); without it the tests run without healing. The main JUnit file stays green for healed steps, `--junit-healed` is published as a second check that annotates and never fails the job. No model decides pass or fail; the model only chooses an element.
- Other secrets: the test accounts that `env:` in `gekko.yaml` names, and optionally `APP_REPOSITORY_TOKEN` / `TESTS_REPOSITORY_TOKEN`, a read-only token to check out a private repository.
- A failed run uploads `gekko-results` (`screenshot.png`, `tree.txt`, `failure.json`, optional `video.mp4` or `video.webm`, `console.txt` on the web) as an artifact for 14 days (they can show the UI of the app); `gekko-heal-report.md` goes to the job summary.
- Workflows need `contents: read`, plus `checks: write` to publish the JUnit report (the token of a pull request from a fork is read-only: there the result check cannot be written, the job result still follows the tests). There are no automatic repair pull requests and no personal access token.
