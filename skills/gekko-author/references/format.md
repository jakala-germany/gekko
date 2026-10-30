# Test and flow format

Tests live in `tests/` (any depth), flows in `flows/`; both folders can be renamed with `testsDir` and `flowsDir` in `gekko.yaml`. gekko is strict: an unknown key, a misspelled action, a step with two actions or none, or an id that exists for one platform only is an error when the file is loaded, with a hint (`tapp` gives "did you mean 'tap'?"). `gekko check` reads every test and flow without a device and reports these errors; `gekko run` does the same before it starts anything.

The format is part of the 1.0 compatibility promise: keys and actions listed here are not removed or renamed in a 1.x release.

## Test file (`tests/*.yaml`)

```yaml
name: Search opens the detail page    # shown in reports; also the name of the result folder
tags: [smoke, search]                 # gekko run --tags smoke, --exclude-tags slow
uses: [onboarding-complete]           # optional: flows that run before the steps (flows without parameters)
timeout: 8000                         # optional: default step timeout in ms for this test
device: { language: en-US, timeFormat: 24h }   # optional, see below
steps:
  - say: Open the search               # intent in the QA's words; keep it stable
    do: { tap: { id: Home.Tab.Search } }
  - say: Search for "tent"
    do: { type: { id: Search.Field, value: "tent" } }
  - say: Open the first result
    do: { tap: { id: Search.Result.0 } }
    until: { assertVisible: { id: Detail.Title } }   # the state the step must reach
    attempts: 3                                      # tries of do + until
  - say: The detail page is shown
    do: { assertVisible: { id: Detail.Title } }
    timeout: 15000                                   # per step override
```

| Key | Meaning |
|---|---|
| `name` | Required. |
| `tags` | List of tags. `gekko run --tags a,b` runs tests that have any of them; `--exclude-tags` removes tests that have any of them (applied after `--tags`). |
| `uses` | Flows that run first, in order. A flow with parameters cannot be used here (it would miss them); use a `use` step instead. |
| `timeout` | Default step timeout in ms; wins over `defaultTimeoutMs` of `gekko.yaml`. |
| `device` | `language` and `timeFormat`, see "Device state". |
| `steps` | Required. |

## Flow file (`flows/*.yaml`)

A flow is a list of steps with optional parameters. `use` finds it by **file name** (`flows/account-login-sign-in.yaml`, `.yml` works too), so keep `name:` equal to the file name. Name flows `feature-screen-functionality`: `onboarding-complete`, `search-field-enter-term`.

```yaml
name: account-login-sign-in
params: [user, password]
steps:
  - say: Enter the user name
    do: { type: { id: Login.User, value: "${user}" } }
  - say: Enter the password
    do: { type: { id: Login.Password, value: "${password}" } }
  - say: Submit
    do: { tap: { id: Login.Submit } }
```

Call it from a test or another flow:

```yaml
  - say: Sign in with the test account
    do: { use: account-login-sign-in, with: { user: "${env.TEST_USER}", password: "${env.TEST_PASSWORD}" } }
```

- `${param}` is replaced by the `with:` value in the text of the flow file (comment lines are left alone) before the YAML is read. Write it inside double quotes (`"${user}"`): the value is escaped for a double-quoted string. A `${...}` that is neither a parameter nor one of the runtime variables below is an error ("Missing parameter").
- Flows may call other flows; a flow that calls itself is reported by `gekko check`.
- `with:` only goes with `use:`.

## Steps

| Key | Meaning |
|---|---|
| `say` | The intent, in plain words. Optional for gekko, but always write it: it is the documentation, the log line and what a heal model reads. |
| `do` | Required. **Exactly one action**: two actions or none is an error when the file is loaded. Write one step per action. |
| `until` + `attempts` | After `do`, the state in `until` (an `assertVisible` or `assertNotVisible`) must be reached; if not, `do` is repeated, up to `attempts` tries in total (default 1). `attempts` needs an `until`. Before each retry gekko checks whether the state was reached after all, and on iOS a retried `tap` is sent as a real touch (on Android the agent falls back to one by itself). For taps that a reloading screen swallows. Not for `use` and `repeat`. |
| `timeout` | Timeout in ms for this step (`optional` and `if` use their own short look, see below). Not for `use` and `repeat`. |
| `platform: android\|ios` | Run the step only on that platform. Also valid inside `do` and `until`: a `do` for another platform skips the step, an `until` for another platform is dropped, so one step can differ per platform: `until: { platform: ios, assertNotVisible: { id: Search.Spinner } }`. Skipped steps are not logged (`GEKKO_DEBUG=1` lists them). Any other value is an error. |
| `optional: true` | Skip the step when the element it needs is not there (a banner, a tooltip). gekko looks for `optionalTimeoutMs` (default 500 ms) or the step's own `timeout`, not for the full default timeout. Once the element is there the step is a normal step: a failed `until` or a rejected action still fails. Not for `use` and `repeat`; put those behind an `if`. |
| `if: { visible: <selector> }` or `if: { notVisible: <selector> }` | The step (also `use` and `repeat`) runs only when the screen shows, or does not show, that element. The look is as short as for `optional`. Exactly one of the two. |

**Waiting.** There are no sleeps. The timeout (`defaultTimeoutMs`, 10 s by default) only covers a screen that is still loading. Once the screen is done (nothing changed for `settleMs`, 2.5 s, and no progress indicator is shown) a missing element fails at once. Raise `timeout:` on a step that waits for a slow network call, or `settleMs` for slow backends; do not add `wait`.

## Actions

`do` takes exactly one of these.

| Action | Form | Notes |
|---|---|---|
| `tap` | `{ tap: { id: Search.Field } }`<br>`{ tap: { text: "Buy", index: 1 } }`<br>`{ tap: { id: Alert.Allow, system: true } }`<br>`{ tap: { point: "50%,95%" } }` | Taps the element. If the agent's accessibility tap does nothing, gekko repeats it as a real touch through the system runner. `system: true` always uses the system runner: permission dialogs, the browser, anything outside the app. `point` taps a spot of the screen as `x%,y%`; Android only, last resort for an element without id and text. |
| `doubleTap` | `{ doubleTap: { id: Photo } }` | Two quick touches (a real touch on both platforms). Takes `system: true` like `tap`. |
| `longPress` | `{ longPress: { id: Row, holdMs: 1000 } }` | Holds the element pressed (default 700 ms), e.g. for a context menu. Takes `system: true`. |
| `type` | `{ type: { id: Search.Field, value: "tent" } }` | Sets the text of a field (replaces what is there). Any selector key (not `system` or `point`) plus `value`. The text of a password field can be typed but never read back. |
| `eraseText` | `{ eraseText: { id: Search.Field } }` | Clears a text field. |
| `scroll` | `{ scroll: { id: Search.List, direction: down, distance: 600 } }` | Scrolls a container by a distance, see "Scrolling". |
| `scrollUntilVisible` | `{ scrollUntilVisible: { id: Search.Result.40, direction: down, maxScrolls: 10, in: { id: Search.List } } }` | Scrolls until the element is completely visible, see "Scrolling". |
| `swipe` | `{ swipe: { direction: up } }`<br>`{ swipe: { from: { id: Slider.Handle }, direction: right, durationMs: 400 } }`<br>`{ swipe: { start: "50%,80%", end: "50%,20%" } }` | A physical finger gesture, see "Scrolling". |
| `assertVisible`<br>`assertNotVisible` | `{ assertVisible: { id: Detail.Title, text: "Tent" } }` | Passes when an element that matches is on screen (its center lies inside the window), or when none is. `gekko tree` also lists off-screen rows, so an id in the tree is not proof it is visible. |
| `waitForAnimationToEnd` | `{ waitForAnimationToEnd: true }` | Waits until the screen stopped changing (same notion of "done" as for missing elements). |
| `wait` | `{ wait: 500 }` | Fixed pause in ms. Avoid; assert the end state instead. |
| `back` | `{ back: true }` | System back button. Android only. |
| `pressKey` | `{ pressKey: enter }` | `back`, `home`, `enter`, `tab` or an Android key code number. Android only. |
| `hideKeyboard` | `{ hideKeyboard: true }` | Closes the keyboard when it is open; do it before a scroll or swipe over the lower part of the screen. |
| `openLink` | `{ openLink: "example://item/42" }` | Opens a URL or deep link (Android intent, `simctl openurl` on a simulator). |
| `launchApp` | `{ launchApp: {} }`<br>`{ launchApp: { clearState: true } }`<br>`{ launchApp: { arguments: { feature: "on" } } }` | Starts the app (again). `clearState: true` wipes its data first. `arguments` become Android intent extras (strings) and iOS launch arguments (`-feature on`); keys are letters, digits, `_` or `.`, starting with a letter. |
| `stopApp` | `{ stopApp: true }` | Quits the app, keeping its data. |
| `clearState` | `{ clearState: true }` | Wipes the app data and quits the app; follow it with `launchApp`. |
| `launchPackage`<br>`stopPackage` | `{ launchPackage: com.example.other }`<br>`{ stopPackage: com.android.chrome }` | Start or force-stop another app by package name. Android only; while the other app is in front, later steps go through the system runner. |
| `takeScreenshot` | `{ takeScreenshot: search-empty }` | Saves `search-empty.png` into the result folder of the test (characters outside `A-Za-z0-9_.-` become `_`). |
| `copyText` | `{ copyText: { from: { id: Cart.Total }, match: "(\\d+)", as: total } }` | Keeps the text of an element in `${var.total}` for the rest of the test, flows included. With `match` the variable holds the first group of the regular expression, or the whole match when it has no group. `as` is letters, digits and `_`. |
| `setDarkMode` | `{ setDarkMode: true }` | Dark or light mode; restored when the test ends. Android needs API 29 or newer. |
| `setOrientation` | `{ setOrientation: landscape }` | `portrait` or `landscape`; goes through the system runner and is put back to portrait when the test ends. |
| `setAirplaneMode` | `{ setAirplaneMode: true }` | Android only, API 30 or newer; restored when the test ends. |
| `setLocation` | `{ setLocation: { latitude: 52.52, longitude: 13.40 } }` | Android emulators, physical Android devices and iOS simulators. A physical Android device (or an emulator with `GEKKO_MOCK_LOCATION=1`) gets the location from the system runner through Android's mock location providers; gekko switches the runner on as the mock location app (`appops`), so nothing has to be set by hand, and ends the mock location when the test ends. |
| `setPermissions` | `{ setPermissions: { location: allow, notifications: deny } }` | Values are `allow` or `deny`. Names: `location`, `camera`, `microphone`, `notifications`, `contacts`, `calendar`, `photos`; on iOS simulators only `location`, `microphone`, `contacts`, `calendar`, `photos`. On Android a full permission name (`android.permission.CAMERA`) also works. Revoking a permission can restart the app process. |
| `repeat` | `{ repeat: { times: 3, steps: [ { say: Next page, do: { swipe: { direction: left } } } ] } }`<br>`{ repeat: { while: { visible: { id: Banner.Close } }, max: 5, steps: [...] } }` | Runs the nested steps in a row. Exactly one of `times` and `while`; `while` repeats as long as the condition holds and fails when it still holds after `max` rounds (default 10). |
| `use` | `{ use: account-login-sign-in, with: { user: qa1 } }` | Runs `flows/<name>.yaml`; `with:` values are strings. |

`false` is not an action: `{ back: false }` (and the same for `stopApp`, `clearState`, `waitForAnimationToEnd`, `hideKeyboard`) is rejected, remove the step instead.

## Selectors

| Key | Meaning |
|---|---|
| `id` | Test tag / resource id (Android), accessibility identifier (iOS). A shared string (`Search.Field`) or per platform: `{ android: search_field, ios: Search.Field }`. An id that exists for one platform only needs `platform:` on the step (or inside `do`), otherwise loading the file fails. |
| `text` | The whole visible text, exact. It also matches the accessibility label (content description). Whitespace is normalized (non-breaking spaces, surrounding blanks). |
| `textContains` | A part of the text, for texts with changing parts. |
| `textMatches` | A regular expression the whole text must match: case-insensitive, `.` also matches line breaks. A text that equals the pattern literally matches too, so `What are you looking for?` finds exactly that text. |
| `action` | The element must offer this action: `click`, `setText` or `scroll`. |
| `within` | Only elements below the element with this id (shared or per platform): `{ within: Filter.Dialog, action: setText, index: 1 }` is "the second input in the dialog". |
| `index` | Zero-based position among the matches, in document order. For genuinely repeated items such as list rows. |
| `enabled` | `true` or `false`: only enabled or only disabled elements. |
| `checked` | `true` or `false`: only checked or unchecked switches, checkboxes and radio buttons (iOS: switches and toggle buttons). |
| `selected` | `true` or `false`: only selected or unselected tabs, chips and segments. |
| `system` | `true`: use the system runner instead of the in-app agent (permission dialogs, the browser) and wait for the element there. Only `tap`, `doubleTap` and `longPress` take it; `type`, `scroll` and `scrollUntilVisible` reject it when the file is loaded, and the other actions ignore it. |
| `point` | `"x%,y%"` of the screen, e.g. `"50%,95%"`: taps that spot instead of an element. Only `tap` knows it, on Android only; a last resort for an element without id and text, and no other key is needed. |

A selector needs at least one of `id`, `text`, `textContains`, `textMatches` or `action` (or, for `tap`, a `point`); `index`, `within`, `enabled`, `checked`, `selected` and `system` only narrow it down or route it. All keys given must match at once.

Prefer ids: they survive copy changes and other device languages. Text selectors are for system UI and third-party screens you cannot tag.

## Scrolling and swiping

Three actions, three jobs:

- **`scroll`** moves a list by a distance, then you assert. `{ scroll: { id: Search.List, direction: down, distance: 600 } }`. The selector names an element in the list (or the list); the nearest container that scrolls along the wanted axis does it.
- **`scrollUntilVisible`** looks for one element: `{ scrollUntilVisible: { id: Search.Result.40, direction: down, maxScrolls: 10, in: { id: Search.List } } }`. It swipes, checks after every swipe and stops as soon as the element is **completely inside its scroll container**, a few short extra swipes included when a sticky bar hides part of it. It gives up after `maxScrolls` (default 10) or earlier when a swipe changes nothing (end of the list). Without `in:` the whole screen is swiped; name the container (`in:`, any selector) for a carousel or a nested list.
- **`swipe`** is a physical finger gesture for everything that is not a plain scroll: pagers, swipe-to-confirm sliders, pull to refresh.

`direction` of `scroll` and `scrollUntilVisible` says **where the wanted content is**, as the user reads the screen:

| `direction` | Reveals |
|---|---|
| `down` (default) | Content further down. |
| `up` | Content further up. |
| `start` / `end` | Content at the start / end of the reading direction: left / right in a left-to-right layout, the other way round in a right-to-left one. gekko reads the layout direction from the device. |
| `left` / `right` | Content to the physical left / right. |

`distance` is in dp (points on iOS), default 400; use 1000 to 2000 for long lists, and check with `gekko tree` what is on screen instead of tuning blindly.

`swipe` is the other way round: the **finger** moves in the given direction (`up`, `down`, `left`, `right`), so `swipe: up` shows content below. It comes in three forms: only `direction` (swipes the screen), `from: <selector>` plus `direction` (starts in the center of the element and goes on to the screen edge), or `start` and `end` as `x%,y%` points. `durationMs` (default 200) makes it slower; a slider that confirms a purchase needs about 400.

To prove a scroll worked, assert the target is visible and that something from the start of the list is `assertNotVisible`.

## Variables

| Placeholder | Value |
|---|---|
| `${param}` | A flow parameter (`params:` in the flow, `with:` at the call). |
| `${env.NAME}` | An environment variable. Only names listed in `env:` of `gekko.yaml` work, see below. |
| `${var.name}` | A text kept by `copyText`. Using it before `copyText` ran fails the step. |
| `${date(-1, 'EEEE, d. MMMM yyyy')}` | The device's today plus or minus days. Letters: `EEEE`/`EEE` weekday, `d`/`dd` day, `MMMM`/`MMM`/`MM`/`M` month, `yyyy`/`yy` year; everything else is kept. Written in German when the test language is `de`, English otherwise. Android asks the device for its date, an iOS simulator uses the UTC date of the Mac. |
| `${random.email}` | `gekko-<8 hex>@example.com`. |
| `${random.number(6)}` | A number of 1 to 18 digits, never starting with 0. |
| `${random.uuid}` | A version 4 UUID. |

- `${var.x}`, `${date(...)}` and `${random.x}` can be used in the texts of selectors (`text`, `textContains`, `textMatches`), in `type` values, `openLink`, `with:` values and `say`. They are filled in when the step runs. Ids, `within` and `takeScreenshot` names do not take them.
- A `${random...}` placeholder gives one value per test: the same placeholder written twice is the same value, and the value is printed in the run log (`${random.email} = "gekko-1a2b3c4d@example.com"`) so a failure can be reproduced. A different digit count (`${random.number(4)}`) is a different value.
- `${env.NAME}` and `${param}` are filled in when the file is loaded, which means `gekko check` also needs the values (process environment or `.env`). A name that is not in `env:`, or a variable that is not set, is an error (exit code 2).

### Environment variables for credentials

Credentials never go in YAML. Declare the names in `gekko.yaml` and set the values outside the repo:

```yaml
# gekko.yaml
env: [TEST_USER, TEST_PASSWORD]
```

```
# .env next to gekko.yaml (git-ignored), or the process environment / CI secrets
TEST_USER=qa-user
TEST_PASSWORD=...
```

```yaml
do: { type: { id: Login.Password, value: "${env.TEST_PASSWORD}" } }
```

- Values come from the process environment first, then from `.env` next to `gekko.yaml`.
- Names that start with `GEKKO_`, `GITHUB_`, `ACTIONS_`, `RUNNER_`, `OP_`, `ANTHROPIC_`, `OPENAI_`, `AWS_`, `SONATYPE_`, `GPG_`, `NPM_` or `SSH_` are refused even when declared: a test cannot read the secrets of gekko or of CI.
- Every value that is used is masked as `***` in the console, JUnit files, `failure.json`, `tree.txt`, heal prompts and heal reports (values shorter than 3 characters are not masked). Screenshots and videos are pictures and are not masked: use throwaway test accounts.

## Device state (`device:`)

```yaml
device:
  language: en-US      # app language (BCP 47)
  timeFormat: 24h      # 12h or 24h
```

Put it in `gekko.yaml` for all tests or at the top of a test to override it. It is applied after the app data was cleared and before the app starts, and restored when the test is done. Android uses the per-app locale (`language` needs API 33 or newer) and the system time format, an iOS simulator gets launch arguments (`-AppleLanguages`, `-AppleLocale`, `-AppleICUForce24HourTime`). Tests that find elements by id do not depend on the language; text assertions do.

## What works where

| | Android | iOS simulator |
|---|---|---|
| All selector keys, `tap`, `doubleTap`, `longPress`, `type`, `eraseText`, `scroll`, `scrollUntilVisible`, `swipe`, assertions, `waitForAnimationToEnd`, `wait`, `takeScreenshot`, `copyText`, `hideKeyboard`, `openLink`, `launchApp`, `stopApp`, `clearState`, `setDarkMode`, `setOrientation`, `repeat`, `use`, `system: true` on `tap`, `doubleTap` and `longPress` | yes | yes |
| `tap` with `point` | yes | no |
| `back`, `pressKey`, `launchPackage`, `stopPackage`, `setAirplaneMode` | yes | no: the step fails ("is not supported") |
| `setLocation` | yes (emulators through the console, physical devices through the system runner) | simulators only |
| `setPermissions` | all names, or a full permission name | `location`, `microphone`, `contacts`, `calendar`, `photos` |
| `checked` | switches, checkboxes, radio buttons | switches and toggle buttons (iOS 17 and later for toggle buttons) |

iOS runs on simulators only in 1.0. Steps that only one platform can do go behind `platform: android` or `platform: ios` so the same file runs on both. Platform-only values for an id belong to the same rule: `id: { android: a }` alone needs `platform: android`.

## Healing and the format

With `--heal`, a step whose element is missing can pass with a warning. Only these steps are healed: `tap`, `type` and `scroll` (the model picks an existing element; a healed `scroll` scrolls down by the default distance), `assertVisible` (only when its id is nowhere in the tree and its texts and states hold on the screen), and the `until` of a step, checked like an assertion. `doubleTap`, `longPress`, `eraseText`, `scrollUntilVisible`, `swipe` and `assertNotVisible` are never healed. At most 15 heals per test. A `tap` with `system: true` that finds no element is skipped with a warning and an entry in the heal report (no model call): a permission dialog that did not appear is not an error under `--heal`, and the entry also shows up in `--junit-healed`. See `../../gekko-setup/references/cli.md`.
