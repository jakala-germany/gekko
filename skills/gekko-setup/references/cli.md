# gekko CLI

The commands, flags, exit codes, environment variables and `gekko.yaml` keys on this page are part of the 1.0 compatibility promise. The wire protocol between the CLI and the agents is not: it is internal and versioned by a protocol number (see "Versions").

## Install and requirements

- Testers: `curl -fsSL https://raw.githubusercontent.com/jakala-germany/gekko/1.0.0/scripts/install.sh | bash -s -- 1.0.0` (macOS arm64 or Linux x64; or run `scripts/install.sh [VERSION]` from a checkout; the default version is `latest`). It installs the CLI to `$GEKKO_BIN_DIR` (default `~/.local/bin`) and the Android system runner and, on macOS, the iOS runner and the iOS agent to `$GEKKO_HOME/runners` (default `~/.gekko/runners`): `gekko-system-runner.apk`, `gekko-ios-runner/`, `gekko-ios-agent/GekkoAgent.framework`. Every download is checked against the `SHA256SUMS` of the release; `GEKKO_VERIFY_ATTESTATION=1` (needs `gh`) also verifies the build provenance; `install.sh --uninstall` removes the CLI and the runners (`~/.gekko/token` stays).
- The CLI finds the installed Android system runner and iOS runner **without any setting**. The iOS agent is injected into the simulator app only when asked for: `ios.agentFramework: installed` (or `GEKKO_IOS_AGENT`). `android.runnerApk` and `ios.runnerDir` accept `installed` as well (the same as leaving them out).
- CI: the action `jakala-germany/setup-gekko-cli-action` (tag = gekko version, e.g. `@1.0.0`).
- Host: macOS arm64 or Linux x64. Android needs `adb`. iOS needs macOS with Xcode and simulators (iOS 15 and later) or iPhones on a USB cable (Xcode 27 with `xcrun devicectl`, iOS 17 and later). The CLI starts `adb`, `xcrun simctl`, `xcodebuild`, `unzip` and `perl` (every process gets a time limit of 300 seconds). Xcode and `xcodegen` are only needed to *build* the runners from source; users get them from the release.
- App side: Android agent `minSdk` 26 (may be lowered later); gekko is tested with AGP 9.x, Gradle 9.x and Kotlin 2.4.

## Global options

On every command that talks to a device (`tree`, `tap`, `type`, `scroll`, `assert`, `screenshot`, `install`, `uninstall`, `launch`, `run`):

| Option | Meaning |
|---|---|
| `-s`, `--serial` | adb serial, or the UDID of a simulator or an iPhone on a USB cable. The UDID of a booted simulator or a connected iPhone selects iOS by itself. |
| `--platform android\|ios` | Default android, or ios when `-s` is a booted simulator or a connected iPhone. |
| `--project DIR` | Directory with `gekko.yaml` (default `.`). Every command except `devices`, `screenshot` and `--version` needs `gekko.yaml` and stops with exit code 2 without it. |

Without `-s`, Android uses the one attached device and iOS the one booted simulator (or, with none booted, the one connected iPhone); with several, the CLI stops and asks for `-s` (it does not guess).

## Commands

| Command | Purpose |
|---|---|
| `gekko devices` | Attached adb devices, booted simulators and iPhones on a USB cable, one per line: `android <serial>`, `ios <UDID>`, `ios <UDID> (physical)` |
| `gekko tree [--json]` | The current UI as the active driver sees it: role, `id=`, `text=`, `desc=`, `actions=`, `disabled` |
| `gekko tap SELECTOR` | Tap an element; when the in-app agent's accessibility tap is not handled, the tap is repeated as a real touch through the system runner |
| `gekko type SELECTOR "text"` | Type into an element (replaces its text) |
| `gekko scroll SELECTOR [--direction D] [--distance DP]` | Scroll the container of an element; `--direction` is where the wanted content is: `down` (default), `up`, `start`, `end`, `left`, `right`; `--distance` in dp, points on iOS (default 400) |
| `gekko assert SELECTOR [--gone]` | Wait until the element is visible (or, with `--gone`, not visible); exit 1 when it is not within `--timeout` |
| `gekko screenshot FILE` | Save a screenshot as PNG |
| `gekko install [APP]` | Install the app under test and keep its data: an `.apk`, an `.app`, or a `.zip` that contains an `.app` (CI build products); without `APP` the `android.apk` / `ios.app` of `gekko.yaml` |
| `gekko uninstall` | Remove the app, the system runner and the port forwards from the device; fails when something is left |
| `gekko launch [--fresh]` | Start the app from `gekko.yaml`; `--fresh` clears its data first and grants the configured permissions. Prints `ok` once the agent answers |
| `gekko run [PATH...] [flags]` | Run YAML tests, see below |
| `gekko check [FILE...] [--project DIR]` | Read all tests and the flows they use, no device needed: broken YAML, unknown keys, steps with two actions or none, missing flows or parameters, flows that call themselves. Prints `N tests ok` or the problems and exits 2. It reads `${env.NAME}` values, so they must be set |
| `gekko llm-check [--heal claude\|opencode\|api]` | Ask the model of `--heal` (default `api`) one short question and print the answer, no device needed: tries `GEKKO_LLM_*` or the local CLI. Exit 3 when the model does not answer, 2 when it is not configured |
| `gekko --version` | The version of the CLI |

`SELECTOR` for `tap`, `type`, `scroll` and `assert` is one or more of `--id`, `--text`, `--text-contains`, `--text-matches` (regular expression for the whole text), `--action` (`click`, `setText`, `scroll`), `--within ID`, plus `--index N`, `--timeout MS` (default 5000) and `--system` (use the system runner: permission dialogs, the browser). At least one of `--id`, `--text`, `--text-contains`, `--text-matches`, `--action` is required.

The driver is chosen per call: the in-app agent while the app has the focus, the system runner (UiAutomator / XCUITest) for everything else. On Android the agent and the runner listen on private sockets (`gekko-<appId>`, `gekko-system-runner`) that only adb can reach.

### `gekko run`

```
gekko run [PATH...] [--tags a,b] [--exclude-tags a,b] [--shard i/n]
          [--junit FILE] [--junit-healed FILE] [--output DIR]
          [--heal claude|opencode|api]
          [--devices ids|all] [--every-device]
          [--install] [--app FILE] [--uninstall]
          [--record none|failures|all]
          [-s SERIAL|UDID] [--platform android|ios] [--project DIR]
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
| `--devices a,b,c` or `all` | Spread the tests over adb serials and simulator or iPhone UDIDs, or every attached device, booted simulator and iPhone. Every platform has its own queue and the first free device takes the next test, so Android and iOS run at the same time and the order is not predictable. Not combinable with `-s`. |
| `--every-device` | With `--devices`: every device runs every test; the run passes only when every test passes on every device. Prints `[device] x/y passed` per device. |
| `--install` / `--app FILE` | Install the app before the tests: `android.apk` / `ios.app` of `gekko.yaml`, or `FILE` (implies `--install`). Repeat `--app` for several platforms: an `.apk` goes to Android devices, an `.app` or `.zip` to iOS simulators, an `.ipa` (or `.app`, `.zip`) to iPhones; with `--devices` a platform without a build keeps what is installed. |
| `--uninstall` | Remove the app, the runner and the port forwards when the run is done. |
| `--record none\|failures\|all` | Keep a video of the screen: of failed tests only, or of every test (default none). Saved as `video.mp4` next to `screenshot.png` and `tree.txt`. Android records at most 180 seconds per test. |
| `-s`, `--platform`, `--project` | As above. |

- Every test starts from cleared app data, applies `device:` settings, launches the app, and quits it when done.
- `gekko run` validates all tests and flows like `gekko check` before it starts anything; a problem is exit code 2.
- With no matching test the run stops with "No tests found" (exit 2).

## Output of a run

Passing tests print `PASS name (ms)`; a failed one prints `FAIL name: reason`, and the last line is `N/M passed`. A failed test leaves, in `<output>/<test>/` (the test name with characters outside `A-Za-z0-9_.-` replaced by `_`):

- `failure.json`: `test`, `path`, `step`, `error`, `kind` (`test` when a step failed, `infrastructure` when the device, the agent or the system runner failed)
- `screenshot.png` and `tree.txt` (what the agent saw)
- `video.mp4` with `--record`
- the files of `takeScreenshot` steps (also for passing tests)

With `--heal` and at least one healed step, `<output>/gekko-heal-report.md` lists them (Markdown-escaped: model output and app texts cannot become links, mentions or HTML) and the console shows a warning block. In CI upload `gekko-results` as an artifact when the job fails.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | All tests passed (also an empty shard, and healed steps, which pass with a warning). |
| 1 | A test failed (a step failed, an assertion did not hold); also a failed `gekko assert` or a rejected `tap`/`type`/`scroll`. |
| 2 | Usage, YAML or configuration problem: a wrong flag, a broken test or flow (found when it loads), a missing `gekko.yaml`, an undeclared or unset `env:` variable, no tests found, a CLI/agent version mismatch found by a single-device command. |
| 3 | Device, agent or runner problem, or the app crashed: no device, `adb` or `simctl` failing, "Agent did not respond", a runner that does not start. `gekko run` ends with 3 when any test ended this way, even if another failed normally. |

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
device:                               # for all tests; a test's own device: overrides it
  language: en-US                     # BCP 47 app language
  timeFormat: 24h                     # 12h or 24h
defaultTimeoutMs: 10000               # waiting for a screen that is still loading
settleMs: 2500                        # quiet time after which a screen counts as done (0 = always wait the full timeout)
optionalTimeoutMs: 500                # how long optional steps, `if` and `repeat while` look for an element
tapIntervalMs: 0                      # minimum pause between taps; only for apps that throttle clicks, e.g. 650
testsDir: tests
flowsDir: flows
env: [TEST_USER, TEST_PASSWORD]       # variables tests may read as ${env.NAME}
```

| Key | Default | Notes |
|---|---|---|
| `app.android`, `app.ios` | none | Needed for the platform you use. |
| `app.androidActivity` | none | Without it the app is started through its launcher entry; set it when several launcher activities exist (for example one added by a debug library). |
| `android.runnerApk`, `ios.runnerDir` | the installed runner | A path, or `installed` for the runner that `scripts/install.sh` put into `$GEKKO_HOME/runners`. Without the key the installed runner is used. The environment variables below win. |
| `ios.agentFramework` | none (no agent is injected) | A path to `GekkoAgent.framework`, or `installed` for the installed agent. The iOS agent is only injected when this key or `GEKKO_IOS_AGENT` is set. |
| `android.stopApps` | none | Other apps (a browser that showed a login page) that a failed test must not leave in front. |
| `defaultTimeoutMs` | 10000 | |
| `settleMs` | 2500 | |
| `optionalTimeoutMs` | 500 | |
| `tapIntervalMs` | 0 | |
| `testsDir`, `flowsDir` | `tests`, `flows` | |
| `env` | none | Names only, never values. Names starting with `GEKKO_`, `GITHUB_`, `ACTIONS_`, `RUNNER_`, `OP_`, `ANTHROPIC_`, `OPENAI_`, `AWS_`, `SONATYPE_`, `GPG_`, `NPM_` or `SSH_` are refused. |

Values of `env:` names come from the process environment first, then from a `.env` file next to `gekko.yaml` (`KEY=value` lines, `#` comments, optional quotes). Every used value is masked as `***` in console output, JUnit files, `failure.json`, `tree.txt`, heal prompts and reports; screenshots and videos are not masked.

## Environment variables

| Variable | Effect |
|---|---|
| `GEKKO_RUNNER_APK` | Android system runner APK; wins over `android.runnerApk` |
| `GEKKO_IOS_AGENT` | `GekkoAgent.framework` for simulators; wins over `ios.agentFramework` and switches the injection on |
| `GEKKO_IOS_DEVICE_RUNNER_DIR` | The system runner built for iPhones (`scripts/build-ios-runner.sh --device`); wins over `ios.deviceRunnerDir`. Left out, `$GEKKO_HOME/runners/gekko-ios-device-runner` is used |
| `GEKKO_IOS_RUNNER_DIR` | iOS system runner directory; wins over `ios.runnerDir` |
| `GEKKO_HOME` | Where `scripts/install.sh` puts the runners and where the CLI looks for them: `$GEKKO_HOME/runners` (default `~/.gekko`) |
| `GEKKO_LLM_BASE_URL`, `GEKKO_LLM_MODEL`, `GEKKO_LLM_API_KEY`, `GEKKO_LLM_CA_CERT` | OpenAI-compatible endpoint for `--heal api` (the API key is optional for endpoints that need none; `GEKKO_LLM_CA_CERT` is the PEM file of a private CA) |
| `GEKKO_HEAL_MODEL` | Model for `--heal claude` (default `haiku`) and `--heal opencode` (`provider/model`, else the model configured in opencode) |
| `GEKKO_HEAL_AGENT` | opencode agent to use instead of gekko's own deny-all agent `gekko-heal` (the explicit opt-out) |
| `GEKKO_OPENCODE_COMMAND` | Launcher used instead of `opencode`, e.g. a script that sets an isolated config in CI |
| `GEKKO_HEAL_DEBUG` | Directory where heal prompts are written (values masked) |
| `GEKKO_DEBUG` | Log which driver answers each command and which steps are skipped on this platform |
| `GEKKO_PORT`, `GEKKO_TOKEN` | Set by the CLI when it starts the iOS app and runner (a loopback port per simulator or iPhone and the per-user token); the iOS agent only starts when both are set. Do not set them by hand |

The setup action sets the runner variables for the job, so a test repository needs no copy of the runners in CI. With the install script nothing has to be set for the runners; only the iOS agent has to be asked for, with `ios.agentFramework: installed` in `gekko.yaml` or `export GEKKO_IOS_AGENT="$HOME/.gekko/runners/gekko-ios-agent/GekkoAgent.framework"`.

The variables of the install script itself (not read by the CLI): `GEKKO_BIN_DIR` (default `~/.local/bin`), `GEKKO_HOME`, `GEKKO_VERIFY_ATTESTATION=1`, `GEKKO_RELEASE_BASE_URL` (replaces the release URL, for tests).

## Heal

`--heal` always names where the model comes from; nothing is detected:

| Source | What runs |
|---|---|
| `--heal claude` | The local `claude` CLI (the tester's own subscription), small model by default (`GEKKO_HEAL_MODEL` to change it). Started with no tools, no MCP servers, no project settings and no session files, in a private temporary directory. |
| `--heal opencode` | The local `opencode` CLI with the model configured there, run with an agent that gekko defines for the call (`gekko-heal`): every permission is denied, so it can answer but cannot read files, run commands or fetch pages; project configuration is switched off. `GEKKO_HEAL_AGENT` names another agent instead. |
| `--heal api` | An OpenAI-compatible `/chat/completions` endpoint (`GEKKO_LLM_BASE_URL`, `GEKKO_LLM_MODEL`, `GEKKO_LLM_API_KEY`). This is the source for CI. The request goes through `curl` (the native binary has no TLS of its own); a private CA is named in `GEKKO_LLM_CA_CERT`. Try the settings first with `gekko llm-check`. |

If the named source is not usable (no such CLI on `PATH`, `GEKKO_LLM_*` not set) the run stops with a message (exit 2) before any test.

- Only a **missing element** is healed, never a wrong state or a timeout. A healed step passes with a warning, an entry in `gekko-heal-report.md`, and, with `--junit-healed`, a failed testcase in that second file. At most 15 heals per test.
- The model only chooses an existing element for `tap`, `type` and `scroll`: the kind of the step never changes and typed text always comes from the test. A `tap` with `system: true` that finds no element is skipped with a warning and an entry in the heal report (no model call): a permission dialog that did not appear is not an error under `--heal`. An `assertVisible` is healed only when its id is nowhere in the tree and its texts and states hold on the screen (checked in code), and the model confirms from the screen that the state is shown; otherwise it fails. The `until` of a step is checked the same way. `assertNotVisible`, `doubleTap`, `longPress`, `eraseText`, `scrollUntilVisible` and `swipe` are never healed.
- The prompt holds the step's `say`, the failed action and the screen listing (at most 12,000 characters, secrets masked). Model output and app texts are treated as untrusted data: the prompts tell the model never to follow instructions found on screen, and reports escape what they quote. Remember that this text leaves the machine for whatever model you named.
- Without `--heal`, a missing element fails and the message lists the closest ids and texts on screen.

## In CI

- Pull requests of an app repository run the `smoke`-tagged tests; pull requests of a test repository run the whole suite, sharded with `--shard i/n`.
- CI runs with `--heal api` when the endpoint is configured (`GEKKO_LLM_BASE_URL`, `GEKKO_LLM_MODEL` and, if it needs one, `GEKKO_LLM_API_KEY`). The main JUnit file stays green for healed steps, `--junit-healed` is published as a second check that annotates and never fails the job. No model decides pass or fail; the model only chooses an element.
- A failed run uploads `gekko-results` (`screenshot.png`, `tree.txt`, `failure.json`, optional `video.mp4`) as an artifact.
- Workflows need `contents: read`, plus `checks: write` to publish the JUnit report. There are no automatic repair pull requests and no personal access token.
