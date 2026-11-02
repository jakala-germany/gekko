# How gekko works

The background to the [README](../README.md): what the name stands for, how the CLI talks to the app, how gekko decides that a screen is done, how it runs on several devices and in groups and where its limits are. Commands, flags and keys are in the [CLI reference](../skills/gekko-setup/references/cli.md), the YAML format in the [format reference](../skills/gekko-author/references/format.md).

## The name

**G**enerate and **E**xecute with **K**otlin to **K**eep-green through **O**bservation

| Letter | Meaning |
|---|---|
| **G**enerate | Tests start as plain-language intent (`say:`). An LLM (Claude, on the tester's own subscription) explores the real app, picks ids and generates the compiled steps. |
| **E**xecute | The CLI executes the compiled steps deterministically on Android, iOS and in the browser. CI needs no model to run them. |
| **K**otlin | A native Kotlin CLI, a Kotlin agent for Android, a shared Kotlin protocol and, for the web, a small Node bridge around Playwright. |
| **K**eep-green | Suites stay passing: `--heal` fills in for ids that are missing during a run and reports exactly what it used, and the `gekko-author` skill repairs tests from the failure artifacts and the heal report. |
| **O**bservation | On Android and iOS the agent lives inside the app, on the web a script injected into the page does the same: it observes the UI tree directly (ids, texts, actions). gekko knows when the screen is done loading, so there are no fixed sleeps and no screenshot scraping. |

A gecko is also fast, light and sticks to every surface it climbs on.

## Design constraints

gekko is a small native binary that executes a YAML sheet against the device or the browser. The model is an optional extra for healing, never part of the run.

![gekko hands a YAML sheet to a small native box with a stopwatch, while the LLM stays an optional extra for healing: the design constraints.](img/design-constraints.png)

## Architecture

The CLI posts JSON commands to two small servers on the device: the in-app agent and the system runner. On the web it talks to a bridge that drives the browser.

![gekko, as the CLI, posts a JSON envelope to two mailboxes on the device, the in-app agent and the system runner, and to a web bridge that drives the browser.](img/architecture.png)

```text
 gekko CLI (Kotlin/Native) ──adb forward / localhost──▶ in-app agent      (Android: androidx.startup · iOS: injected dylib)
        │                                          └─▶ system runner     (Android: UiAutomator · iOS: XCUITest)
        ├─ adb / xcrun simctl: launch, fresh state, screenshots            for permission prompts, browser login
        └─ localhost ──▶ web bridge (Node, Playwright) ──▶ browser         (page script injected into every page)
```

## Choosing the driver

The CLI chooses the driver per command: the in-app agent while the app has the focus, the system runner for everything outside the app.

- On Android the agent tells the CLI by itself when the app is not in front, so a command costs one request.
- Waiting for an element (`assertVisible`) is one request too: the agent polls next to the app instead of sending the tree over the wire.

![A railway switch: while the app is in front the command takes the in-app agent track, otherwise the system runner track.](img/driver-choice.png)

### On the web

Nothing goes into the app under test: gekko starts the browser through Playwright (a small Node bridge it ships and starts itself) and injects a page script that only reads the page. Which element a selector means, what is on screen and when the screen is done changing is decided in the CLI with the code the Android agent uses, so a test fails and heals the same way everywhere.

A web "device" is a named browser session (`chromium`, `firefox`, `webkit`; `chromium-2` for more of one) that outlives a CLI call, so `gekko launch`, `gekko tree` and `gekko tap` work step by step like on a device.

## Waiting for a settled screen

The default timeout (`defaultTimeoutMs`) only covers a screen that is still loading. Once the screen is *done* (nothing changed for `settleMs` and no progress indicator is shown) a missing element fails, or heals, immediately.

![gekko restarts a stopwatch every time the screen changes; after 2.5 seconds of quiet the screen counts as settled.](img/settle-detection.png)

Pages that never settle:

- A page with an endless animation (Lottie, spinner) never gets idle. The Android agent waits at most 500 ms for idle before it looks for the element, so such pages cost no more than that per step.
- A loading skeleton that is not a progress indicator counts as a settled screen after `settleMs`. Raise `settleMs` for slow networks.

## Running on several devices

`gekko run --devices` gives every device its own connection, so Android, iOS and the web, or several devices, run at the same time.

- **Connections.** Android: adb forwards the private socket to a free host port. iOS simulators: a stable port per UDID. Web: one bridge per browser session.
- **Which devices.** `--devices` takes serials, UDIDs and browsers (`chromium,firefox,webkit`), or `all` for every attached device, booted simulator and running web session, and hands the tests to the first free device of each platform.
- **Default.** The devices of one platform share the tests, so each test runs once per platform. With Android, iOS and web devices together every test runs on every platform.
- **`--every-device`.** Every device runs every test, and the run passes only when every test passes on every device.
- **Artifacts.** They go to `<output>/<device id>/<test>/`.

![gekko deals test files from a work queue to a Pixel phone, an Android emulator, an iOS simulator and a browser at the same time.](img/parallel-runs.png)

### Shards

`--shard i/n` runs shard i of n: test number i, i+n, i+2n, ... of the selected tests (sorted by path), after `--tags` and `--exclude-tags`. A pull request in a test repository runs the whole suite, split over shards.

### Groups

A folder with a `group.yaml` is a group: its `setup:` flows (a login) run once in a fresh start, then its tests run one after the other without clearing the app data (a browser keeps its context), so the login is still there in every test. If the setup fails, the tests of the group are reported as `SKIP` instead of failing one by one. A group stays on one device, in one shard and in order, and `--record` does not record it. The rules of the `group.yaml`: [format reference](../skills/gekko-author/references/format.md).

## Limits

- iOS works on **simulators only**; real devices are not supported yet.
- The agent needs ids. Missing ids can be worked around with `--heal`, but the durable fix is a tag or identifier in the app (a `data-testid` on the web).
- On iOS a tap through accessibility can be a no-op for SwiftUI tap gestures. Retries (`attempts` with `until`) and `--heal` use a real touch through the system runner.
- `launchPackage` and `stopPackage` are Android only; `back`, `pressKey` and `setAirplaneMode` work on Android and the web, not on iOS simulators. Limit such steps with `platform: android` or a list such as `platform: [android, web]`.
- Some Android settings need a newer API level: `setDarkMode` needs API 29, `setAirplaneMode` API 30 and the `device.language` setting API 33. An older device fails the step with a message.
- `setLocation` works on emulators (console), physical devices (the system runner feeds Android's mock location providers; no setting needed) and iOS simulators.
- Web: Chromium, Firefox and WebKit through Playwright only, desktop browsers (a `web.device` emulates a phone's viewport and user agent, not its touch). The page script reads open shadow roots and iframes; a closed shadow root is invisible to it. A browser caps one mouse-wheel turn (Firefox: about a page), so long scrolls are sent in steps. Native pickers (the dropdown of a `<select>`, the date picker) are not part of the page: set a `<select>` or a date field with `type`.
- A group shares its start state between its tests, which therefore depend on each other's order (see [Groups](#groups)).
