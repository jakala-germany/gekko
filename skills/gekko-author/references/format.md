# Test and flow format

Tests live in `tests/` (any depth), flows in `flows/`, the screens of the app (panes) in `panes/`; the folders can be renamed with `testsDir`, `flowsDir` and `panesDir` in `gekko.yaml`. gekko is strict: an unknown key, a misspelled action, a step with two actions or none, or an id that exists for one platform only is an error when the file is loaded, with a hint (`tapp` gives "did you mean 'tap'?"). `gekko check` reads every test and flow without a device and reports these errors; `gekko run` does the same before it starts anything.

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

## Group file (`group.yaml`)

A folder of tests with a `group.yaml` is a **group**: its tests share a start state, typically a login.

```yaml
# tests/logged-in/group.yaml
name: Account (logged in)        # optional; shown in the log, default is the folder name
platform: web                    # optional: android, ios, web or a list; left out, the group runs everywhere
setup: [account-login-sign-in]   # flows, run once before the first test, in a fresh start
teardown: [account-cleanup]      # optional flows, run once after the last test
```

- The tests below the folder (the nearest `group.yaml` counts) run one after the other, sorted by path, **without clearing the app data**: a login of the setup is still there, a browser keeps its context. A test does not need `uses:` for the login.
- The setup and the teardown are reported as tests of their own (`<name> (setup)`, `<name> (teardown)`). If the setup fails, the tests of the group are reported as `SKIP` with the reason (JUnit `<skipped>`), nothing is retried, and the run still fails because of the setup. The teardown runs whenever the setup passed.
- A group is one unit: it stays on one device, in one shard, in the order of its tests. `--tags` and `--exclude-tags` filter the tests first; a group with no selected test does not run its setup. `gekko run <one test of the group>` runs the setup first.
- Tests depend on each other's order and on what the one before left behind; keep them tolerant (a test that changes state undoes it) and put the logout test last (`zz-` folder). `--record` does not record group tests.
- `platform:` in the `group.yaml` keeps the group off the other platforms entirely (no setup, no tests, not even a skip line), so its tests, and the flows only they use, do not repeat `platform: web` on every step. It does not change how an id map is checked: `id: { web: x }` alone in a group test still needs `platform:` on the step.
- Flows named in `setup` and `teardown` take no parameters (like `uses:`); `gekko check` reports a missing or broken one.

## Steps

| Key | Meaning |
|---|---|
| `say` | The intent, in plain words. Optional for gekko, but always write it: it is the documentation, the log line and what a heal model reads. |
| `do` | Required. **Exactly one action**: two actions or none is an error when the file is loaded. Write one step per action. |
| `until` + `attempts` | After `do`, the state in `until` (an `assertVisible` or `assertNotVisible`) must be reached; if not, `do` is repeated, up to `attempts` tries in total (default 1). `attempts` needs an `until`. Before each retry gekko checks whether the state was reached after all, and on iOS a retried `tap` is sent as a real touch (on Android the agent falls back to one by itself). For taps that a reloading screen swallows. Not for `use` and `repeat`. |
| `timeout` | Timeout in ms for this step (`optional` and `if` use their own short look, see below). Not for `use` and `repeat`. |
| `platform: android\|ios\|web` or a list (`platform: [android, web]`) | Run the step only on that platform or those. Also valid inside `do` and `until`: a `do` for another platform skips the step, an `until` for another platform is dropped, so one step can differ per platform: `until: { platform: ios, assertNotVisible: { id: Search.Spinner } }`. Skipped steps are not logged (`GEKKO_DEBUG=1` lists them). Any other value is an error. |
| `optional: true` | Skip the step when the element it needs is not there (a banner, a tooltip). gekko looks for `optionalTimeoutMs` (default 500 ms) or the step's own `timeout`, not for the full default timeout. Once the element is there the step is a normal step: a failed `until` or a rejected action still fails. Not for `use` and `repeat`; put those behind an `if`. |
| `if: { visible: <selector> }`, `if: { notVisible: <selector> }` or `if: { pane: <pane> }` | The step (also `use` and `repeat`) runs only when the screen shows, or does not show, that element, or when the identity of that pane is on screen (see "Panes"). The look is as short as for `optional`. Exactly one of the three. |

**Waiting.** There are no sleeps. The timeout (`defaultTimeoutMs`, 10 s by default) only covers a screen that is still loading. Once the screen is done (nothing changed for `settleMs`, 2.5 s, and no progress indicator is shown) a missing element fails at once. Raise `timeout:` on a step that waits for a slow network call, or `settleMs` for slow backends; do not add `wait`.

## Actions

`do` takes exactly one of these.

| Action | Form | Notes |
|---|---|---|
| `tap` | `{ tap: { id: Search.Field } }`<br>`{ tap: { text: "Buy", index: 1 } }`<br>`{ tap: { id: Alert.Allow, system: true } }`<br>`{ tap: { point: "50%,95%" } }` | Taps the element. If the agent's accessibility tap does nothing, gekko repeats it as a real touch through the system runner. `system: true` always uses the system runner: permission dialogs, the browser, anything outside the app. `point` taps a spot of the screen as `x%,y%`; Android only, last resort for an element without id and text. |
| `doubleTap` | `{ doubleTap: { id: Photo } }` | Two quick touches (a real touch on Android and iOS, a double click on the web). Takes `system: true` like `tap`. |
| `longPress` | `{ longPress: { id: Row, holdMs: 1000 } }`<br>`{ longPress: { id: Map, point: "25%,75%", holdMs: 900 } }`<br>`{ longPress: { point: "50%,40%", holdMs: 900 } }` | Holds the element pressed (default 700 ms), e.g. for a context menu. `point` holds a spot as `x%,y%` instead: of the element when one is named (`50%,50%` is its center, `25%,75%` a spot in its lower left), of the screen when none is: for a map, a chart or a canvas that has nothing to select inside. Takes `system: true`. On iOS, SwiftUI may read a hold of 700 ms as a tap: hold 900 ms. |
| `type` | `{ type: { id: Search.Field, value: "tent" } }` | Sets the text of a field (replaces what is there). Any selector key (not `system` or `point`) plus `value`. The text of a password field can be typed but never read back. |
| `typeFocused` | `{ typeFocused: { value: "Ada" } }`<br>`{ typeFocused: { value: " Lovelace", system: true } }` | Types at the cursor of the field that has the input focus, the way a keyboard does: it adds to the text (a selection is replaced). Put the focus there first, e.g. with a `tap` on the field. Waits for a field that takes text to get the focus, and fails when none does or when the field does not change. Takes `system: true`. Use `type` to replace a text or to name the field. |
| `eraseText` | `{ eraseText: { id: Search.Field } }` | Clears a text field. |
| `scroll` | `{ scroll: { id: Search.List, direction: down, distance: 600 } }` | Scrolls a container by a distance, see "Scrolling". |
| `scrollUntilVisible` | `{ scrollUntilVisible: { id: Search.Result.40, direction: down, maxScrolls: 10, in: { id: Search.List } } }` | Scrolls until the element is completely visible, see "Scrolling". |
| `swipe` | `{ swipe: { direction: up } }`<br>`{ swipe: { from: { id: Slider.Handle }, direction: right, durationMs: 400 } }`<br>`{ swipe: { start: "50%,80%", end: "50%,20%" } }` | A physical finger gesture that flings, see "Scrolling". Takes `system: true`. |
| `drag` | `{ drag: { start: "50%,80%", end: "50%,30%" } }`<br>`{ drag: { from: { id: Slider.Handle }, end: "90%,50%" } }` | Moves the content under the start to the end and lets go without momentum, see "Scrolling". Takes `system: true`. |
| `assertVisible`<br>`assertNotVisible` | `{ assertVisible: { id: Detail.Title, text: "Tent" } }` | Passes when an element that matches is on screen (its center lies inside the window), or when none is. `gekko tree` also lists off-screen rows, so an id in the tree is not proof it is visible. |
| `assertFocused` | `{ assertFocused: { id: home.teaser, index: 1 } }` | Passes once the element has the focus (the element itself, or something inside of it): on a TV the one the remote moved the focus to, elsewhere the field a tap put the focus into. The `index` is counted first, over the elements that match, so `index: 1` is the second teaser whether it has the focus or not (it is not "the second focused one"). It waits like `assertVisible` and fails with where the focus is: `Expected focused: Selector(id=home.teaser, index=1) (focus is on home.teaser "Top Gun")`. |
| `assertPane` | `{ assertPane: auth/login }` | Passes when every element of the identity of the pane is visible, see "Panes". Also valid as the `until` of a step. |
| `waitForAnimationToEnd` | `{ waitForAnimationToEnd: true }` | Waits until the screen stopped changing (same notion of "done" as for missing elements). |
| `wait` | `{ wait: 500 }` | Fixed pause in ms. Avoid; assert the end state instead. |
| `back` | `{ back: true }` | System back button (Android) or history back (web). With the keyboard up on Android, the first back closes it, as on a phone. On Android 13 and newer the system runner presses it (an app takes back through callbacks there), so the first back of a run starts the runner. iOS has no back key: the step fails. |
| `pressKey` | `{ pressKey: enter }`<br>`{ pressKey: { key: backspace, times: 2 } }`<br>`{ pressKey: { key: home, system: true } }` | Presses a key `times` times (default 1) in one request. `enter`, `backspace` (erases to the left) and `tab` go to the field that has the input focus: the step waits for a field that takes text to get it (put it there first, e.g. with a `tap`) and fails when none does; on iOS a `tab` types a tab character. `back` and `home` (a real button, so it leaves the app) work on Android; on iOS `home` works and `back` does not. An Android key code number (`"66"`) works on Android. The in-app agent presses what it can inside the app; `home`, a key code and a key of an app that is not in front go to the system runner, which presses any key, and `system: true` sends it there at once. On the web `enter`, `tab`, `escape`, `space`, `backspace`, `delete` (the forward delete), `home`, `end`, `pageup`, `pagedown`, `up`, `down`, `left`, `right` or any Playwright key name (`a`, `Shift+A`) goes to the focused element. Erase with `backspace`: `delete` is the web's forward delete, and no key on Android and iOS. The buttons of a TV remote (`up`, `down`, `left`, `right`, `select`, `playPause`, `menu`) go to the system runner (Android key codes, `XCUIRemote` on an Apple TV, where `back` is the menu button); on the web they are the arrow keys, `select` is `Enter`. With `until: <selector>` (`{ pressKey: { key: down, until: { id: row.sports }, max: 15 } }`) the key is pressed until that element has the focus, at most `max` times (default 20), and the step fails as soon as a press did not move the focus: `focus stuck on row.news after 4 presses`. `until` is not combined with `times`. See "TV". |
| `hideKeyboard` | `{ hideKeyboard: true }` | Closes the keyboard when it is open; do it before a scroll or swipe over the lower part of the screen. |
| `openLink` | `{ openLink: "example://item/42" }` | Opens a URL or deep link (Android intent, `simctl openurl` on a simulator, a navigation of the page on the web: an address starting with `/`, `.`, `?` or `#` is relative to the current page). |
| `launchApp` | `{ launchApp: {} }`<br>`{ launchApp: { clearState: true } }`<br>`{ launchApp: { arguments: { feature: "on" } } }` | Starts the app (again). `clearState: true` wipes its data first. `arguments` become Android intent extras (strings), iOS launch arguments (`-feature on`) and query parameters on the web; keys are letters, digits, `_` or `.`, starting with a letter. |
| `stopApp` | `{ stopApp: true }` | Quits the app, keeping its data. |
| `clearState` | `{ clearState: true }` | Wipes the app data and quits the app; follow it with `launchApp`. |
| `launchPackage`<br>`stopPackage` | `{ launchPackage: com.example.other }`<br>`{ stopPackage: com.android.chrome }` | Start or force-stop another app by package name. Android only; while the other app is in front, later steps go through the system runner. |
| `takeScreenshot` | `{ takeScreenshot: search-empty }` | Saves `search-empty.png` into the result folder of the test (characters outside `A-Za-z0-9_.-` become `_`). |
| `copyText` | `{ copyText: { from: { id: Cart.Total }, match: "(\\d+)", as: total } }` | Keeps the text of an element in `${var.total}` for the rest of the test, flows included. With `match` the variable holds the first group of the regular expression, or the whole match when it has no group. `as` is letters, digits and `_`. |
| `setDarkMode` | `{ setDarkMode: true }` | Dark or light mode; restored when the test ends. Android needs API 29 or newer. |
| `setOrientation` | `{ setOrientation: landscape }` | `portrait` or `landscape`; goes through the system runner and is put back to portrait when the test ends. |
| `setAirplaneMode` | `{ setAirplaneMode: true }` | Android (API 30 or newer) and the web (the page goes offline); not on iOS simulators. Restored when the test ends. |
| `setLocation` | `{ setLocation: { latitude: 52.52, longitude: 13.40 } }` | Android emulators, physical Android devices and iOS simulators. A physical Android device (or an emulator with `GEKKO_MOCK_LOCATION=1`) gets the location from the system runner through Android's mock location providers; gekko switches the runner on as the mock location app (`appops`), so nothing has to be set by hand, and ends the mock location when the test ends. |
| `setPermissions` | `{ setPermissions: { location: allow, notifications: deny } }` | Values are `allow` or `deny`. Names: `location`, `camera`, `microphone`, `notifications`, `contacts`, `calendar`, `photos`; on iOS simulators only `location`, `microphone`, `contacts`, `calendar`, `photos`. On Android a full permission name (`android.permission.CAMERA`) also works. Revoking a permission can restart the app process. |
| `mock` | `{ mock: { url: "https://api.example.com/v1/profile", file: profile.json } }`<br>`{ mock: { url: "https://api.example.com/v1/profile", status: 503, delayMs: 2000 } }` | Answers the requests of the app that match with a canned response instead of the real backend; the test undoes every mock when it ends. See "Network mocks". |
| `clearMocks` | `{ clearMocks: true }` | Removes every mock again, so the following requests go to the real backend. |
| `repeat` | `{ repeat: { times: 3, steps: [ { say: Next page, do: { swipe: { direction: left } } } ] } }`<br>`{ repeat: { while: { visible: { id: Banner.Close } }, max: 5, steps: [...] } }` | Runs the nested steps in a row. Exactly one of `times` and `while`; `while` repeats as long as the condition holds and fails when it still holds after `max` rounds (default 10). |
| `use` | `{ use: account-login-sign-in, with: { user: qa1 } }` | Runs `flows/<name>.yaml`; `with:` values are strings. |

`false` is not an action: `{ back: false }` (and the same for `stopApp`, `clearState`, `waitForAnimationToEnd`, `hideKeyboard`) is rejected, remove the step instead.

## Selectors

| Key | Meaning |
|---|---|
| `id` | Test tag / resource id (Android), accessibility identifier (iOS), `data-testid` (web, see `web.idAttributes`). A shared string (`Search.Field`) or per platform: `{ android: search_field, ios: Search.Field, web: search-field }`. A per-platform id has to name android and ios, and every other platform that has an `app:` entry in `gekko.yaml` (web: `app.web`); otherwise the step needs `platform:` (or inside `do`), or loading the file fails. |
| `text` | The whole visible text, exact. It also matches the accessibility label (content description). Whitespace is normalized (non-breaking spaces, surrounding blanks). |
| `textContains` | A part of the text, for texts with changing parts. |
| `textMatches` | A regular expression the whole text must match: case-insensitive, `.` also matches line breaks. A text that equals the pattern literally matches too, so `What are you looking for?` finds exactly that text. |
| `action` | The element must offer this action: `click`, `setText` or `scroll`. |
| `within` | Only elements below the element with this id (shared or per platform): `{ within: Filter.Dialog, action: setText, index: 1 }` is "the second input in the dialog". |
| `index` | Zero-based position among the matches, in document order. For genuinely repeated items such as list rows. |
| `enabled` | `true` or `false`: only enabled or only disabled elements. |
| `checked` | `true` or `false`: only checked or unchecked switches, checkboxes and radio buttons (iOS: switches and toggle buttons). |
| `selected` | `true` or `false`: only selected or unselected tabs, chips and segments. |
| `system` | `true`: use the system runner instead of the in-app agent (permission dialogs, the browser) and wait for the element there. Only `tap`, `doubleTap`, `longPress`, `swipe` and `drag` take it (on `swipe` and `drag` it makes a real touch of the runner instead of the agent's); `type`, `scroll` and `scrollUntilVisible` reject it when the file is loaded, and the other actions ignore it. |
| `point` | `"x%,y%"` of the screen, e.g. `"50%,95%"`: taps that spot instead of an element. `tap` knows it on Android only; a last resort for an element without id and text, and no other key is needed. `longPress` takes it on every platform, relative to its element when it names one. |
| `element` | An element of a pane, `<pane>.<element>` (`auth/login.submit`): its selector, with the other keys of the step added. A plain string stands for it where a selector is a map (`tap: auth/login.submit`). See "Panes". |

A selector needs at least one of `id`, `text`, `textContains`, `textMatches`, `action` or `element` (or, for `tap`, a `point`); `index`, `within`, `enabled`, `checked`, `selected` and `system` only narrow it down or route it. All keys given must match at once.

Prefer ids: they survive copy changes and other device languages. Text selectors are for system UI and third-party screens you cannot tag.

## Panes

A pane is one screen of the app, written once in `panes/<name>.yaml` and used by every test and flow, like the page objects of other test tools. It holds the **elements** of the screen (named selectors), an **identity** (what proves the screen is shown) and **actions** (named steps of that screen). When the app changes you fix the pane, not every test. `panesDir` in `gekko.yaml` renames the folder.

```yaml
# panes/auth/login.yaml
identity: [title, email]                     # all of them visible: the login is on screen
elements:
  title: { id: Login.Title }
  email: { id: Login.Email }
  password: { id: Login.Password }
  submit:                                    # a whole selector per platform, where they differ
    android: { id: login_submit }
    ios:     { text: "Sign in" }
    web:     { id: login-submit }
  dialog:    { id: Login.Dialog }
  dialog.ok: { text: OK, within: dialog }    # dots are only for reading; within names an element of this pane
actions:
  signIn:
    params: [user, password]
    steps:
      - say: Enter the user
        do: { type: { element: email, value: "${user}" } }
      - say: Enter the password
        do: { type: { element: password, value: "${password}" } }
      - say: Submit
        do: { tap: submit }
```

| Key | Meaning |
|---|---|
| `identity` | Optional list of element names of this pane. The pane is on screen when all of them are visible. |
| `elements` | Name → selector. A name is letters, digits, `_`, `-` and `.`. A selector has the keys of "Selectors" (not `element` and `point`; `system: true` is fine), or it is one selector per platform: `{ android: {...}, ios: {...}, web: {...} }`. |
| `actions` | Name → `{ params: [...], steps: [...] }`. The steps are normal steps; a name is letters, digits and `_`. |

The name of a pane is its path below `panes/` without the extension: `panes/checkout/payment.yaml` is `checkout/payment` (letters, digits, `_`, `-`, and `/` for folders). There is no `name:` key, so the name cannot drift from the file.

**Using elements.**

```yaml
- do: { tap: auth/login.submit }                                       # <pane>.<element>
- do: { type: { element: auth/login.email, value: "${env.TEST_USER}" } }
- do: { assertVisible: { element: cart.row, index: 2 } }              # the step adds to the element
- do: { assertVisible: { element: home.greeting, text: "Hi Ada" } }
```

- A reference is `<pane>.<element>`: the pane is everything up to the first `.` after the last `/`, the rest is the element, so `checkout/payment.card.number` is the element `card.number` of the pane `checkout/payment`.
- A plain string is a reference wherever a selector is a map: `tap`, `doubleTap`, `longPress`, `eraseText`, `scroll`, `scrollUntilVisible` (and its `in:`), `assertVisible`, `assertNotVisible`, `swipe.from`, `copyText.from` and the `if` conditions. `type` needs its `value`, so it takes `element:`.
- `element:` plus other selector keys: the keys of the step are added to the element's (all must match), and where both set the same key the step wins. An `index` of 0 is the default, so it does not replace the `index` of an element. `element:` together with `id:` or `point:` is an error.
- The element is read for the platform the step runs on. An element that has no selector for a platform where the step runs is an error when the file is loaded, unless `platform:` limits the step (the same rule as an id for one platform only; web counts when `app.web` is set).
- `within` of an element: a name of another element of the same pane means that element, which must have an id and nothing else (the agents look for an id); any other value is an id, as in a plain selector. `within` in a step stays an id.
- Inside a pane (its identity, its actions) an element is written without the pane. When a name is an element of the pane and also `<pane>.<element>` of another pane, it is an error; write the one you mean with the pane name or rename one.
- Elements take `${var.x}`, `${date(...)}` and `${now(...)}` in their texts like any selector, and `${env.NAME}` is filled in when the file is loaded. `${param}` is only for actions.

**Identity.**

```yaml
- do: { assertPane: auth/login }                 # every element of the identity is visible
- do: { tap: home.profile }
  until: { assertPane: profile }                 # the state the step must reach
- if: { pane: onboarding }                       # a short look, like optional
  do: { use: onboarding.skip }
```

A pane without an `identity` cannot be used with `assertPane` or `if: { pane }`.

**Actions.**

```yaml
- say: Sign in with the test account
  do: { use: auth/login.signIn, with: { user: "${env.TEST_USER}", password: "${env.TEST_PASSWORD}" } }
```

- `use: <pane>.<action>` runs it; inside an action of the same pane `use: signIn` is enough. It works in steps, in `uses:` and in the `setup` and `teardown` of a group (an action without params there, like a flow).
- `with:` gives exactly the params of the action: a missing one and one the action does not declare are errors. `${param}` in the steps is filled in like in a flow; a `${name}` that is not a param of its action breaks the pane when it is read.
- Before its first step an action **waits for the identity of its pane** (the step timeout): `not on pane 'auth/login': Expected visible: Selector(id=Login.Title)`. A pane without an identity has no wait.
- A name that is both a flow and an action of a pane is ambiguous and an error. Flows stay for journeys across screens; an action belongs to one screen.

**What gekko tells you.**

- `gekko check` reports an unknown pane, element or action with a hint (`Did you mean 'auth/login.submit'?`), an element without a selector for a platform, a pane file that cannot be read and wrong params. When it checks the whole project it also warns (the exit code stays 0) about elements, actions and panes that nothing uses; an element in an `identity` or the `within` of another element counts as used.
- A failed step names the element and what it stands for (`element auth/login.submit = Selector(id=login_submit)`) and, when it can tell from the screen, `current pane: home`. The log shows `tap auth/login.submit` for a step without `say`. `gekko tree` marks the lines that are elements of a pane (`<- auth/login.email`) and prints the current pane. The heal report says which element of which pane file to fix.

## Scrolling and swiping

Three actions, three jobs:

- **`scroll`** moves a list by a distance, then you assert. `{ scroll: { id: Search.List, direction: down, distance: 600 } }`. The selector names an element in the list (or the list); the nearest container that scrolls along the wanted axis does it.
- **`scrollUntilVisible`** looks for one element: `{ scrollUntilVisible: { id: Search.Result.40, direction: down, maxScrolls: 10, in: { id: Search.List } } }`. It drags (half a viewport at a time, without a fling that would carry the list past the element), checks after every drag and stops as soon as the element is **completely inside its scroll container**, a few short extra swipes included when a sticky bar hides part of it. It gives up after `maxScrolls` (default 10) or earlier when a swipe changes nothing (end of the list). Without `in:` the whole screen is swiped; name the container (`in:`, any selector) for a carousel or a nested list.
- **`swipe`** is a physical finger gesture for everything that is not a plain scroll: pagers, swipe-to-confirm sliders, pull to refresh.
- **`drag`** moves the content under a spot to another spot and lets go **without momentum**, so nothing keeps scrolling: scroll by an exact distance before a screenshot, move a slider thumb, reorder a list. `{ drag: { start: "50%,80%", end: "50%,30%" } }` or `{ drag: { from: { id: Slider.Handle }, end: "90%,50%" } }` (the finger starts in the center of the element). The finger goes a little beyond the end and rests there, so the content ends exactly where the finger does, also past the touch slop that a scroll container swallows first.

`direction` of `scroll` and `scrollUntilVisible` says **where the wanted content is**, as the user reads the screen:

| `direction` | Reveals |
|---|---|
| `down` (default) | Content further down. |
| `up` | Content further up. |
| `start` / `end` | Content at the start / end of the reading direction: left / right in a left-to-right layout, the other way round in a right-to-left one. gekko reads the layout direction from the device. |
| `left` / `right` | Content to the physical left / right. |

`distance` is in dp (points on iOS, CSS pixels on the web), default 400; use 1000 to 2000 for long lists, and check with `gekko tree` what is on screen instead of tuning blindly.

`swipe` is the other way round: the **finger** moves in the given direction (`up`, `down`, `left`, `right`), so `swipe: up` shows content below. It comes in three forms: only `direction` (swipes the screen), `from: <selector>` plus `direction` (starts in the center of the element and goes on to the screen edge), or `start` and `end` as `x%,y%` points. `durationMs` (default 200) is how long the finger takes, on every platform: a short swipe flings the content on, a long one barely moves it past the finger, and a slider that confirms a purchase needs about 400. On iOS a swipe is a real touch of the system runner (the agent cannot fling), so it takes about a second more than on Android.

`drag` takes `start` (or `from`) and `end` the same way, and always ends at rest. On iOS the agent moves the scroll view under the start by exactly the distance, and the system runner makes a real touch where there is none (a slider, a reorder handle); `system: true` asks for the real touch. The system runner moves its finger at XCUITest's slow speed, so a long drag takes a while (about 3 seconds for 500 points) and the agent (the default) is the quicker way to scroll a list. On the web a drag is the mouse: it moves sliders and reorders lists but does not scroll a page (use `scroll`).

To prove a scroll worked, assert the target is visible and that something from the start of the list is `assertNotVisible`.

## Variables

| Placeholder | Value |
|---|---|
| `${param}` | A flow parameter (`params:` in the flow, `with:` at the call). |
| `${env.NAME}` | An environment variable. Only names listed in `env:` of `gekko.yaml` work, see below. |
| `${var.name}` | A text kept by `copyText`. Using it before `copyText` ran fails the step. |
| `${date(-1, 'EEEE, d. MMMM yyyy')}` | The device's today plus or minus days. Letters: `EEEE`/`EEE` weekday, `d`/`dd` day, `MMMM`/`MMM`/`MM`/`M` month, `yyyy`/`yy` year; everything else is kept. Written in German when the test language is `de`, English otherwise. Android asks the device for its date, an iOS simulator uses the UTC date of the Mac. |
| `${now(+10m)}` | The device's clock plus or minus a time, as ISO 8601 in the time zone of the device (`2026-11-02T23:40:00+01:00`). The offset is a number and a unit, `s`, `m`, `h` or `d`: `+90s`, `+10m`, `-2h`, `-1d`; `${now()}` is now. A second argument sets the form: a quoted pattern, `${now(+10m, 'HH:mm')}`, or `epochMillis` / `epochSeconds` for a number, `${now(+10m, epochMillis)}`. Pattern letters: the ones of `date()` plus `HH` / `H` (0-23), `hh` / `h` (1-12), `mm`, `ss` and `a` (AM / PM). Android asks the device for its clock (an emulator's clock is often off), an iOS simulator and a browser use the clock and the time zone of the Mac. Made for mock files, see "Network mocks". |
| `${random.email}` | `gekko-<8 hex>@example.com`. |
| `${random.number(6)}` | A number of 1 to 18 digits, never starting with 0. |
| `${random.uuid}` | A version 4 UUID. |

- `${var.x}`, `${date(...)}`, `${now(...)}` and `${random.x}` can be used in the texts of selectors (`text`, `textContains`, `textMatches`), in `type` values, `openLink`, `with:` values, `say`, and in the `url`, `bodyContains` and `headers` of a `mock` and in the mock files. They are filled in when the step runs. Ids, `within` and `takeScreenshot` names do not take them.
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

## TV (Android TV, Apple TV)

A TV is recognised by itself (`gekko devices` marks it `tv`): an Android device with the leanback or television feature, a simulator that runs a tvOS runtime. Tests use the same ids and `app.*` settings as on a phone; only the way the app is driven changes, because a TV is driven with a remote that moves the focus.

```yaml
name: Open the second teaser of the top row with the remote
tags: [tv]
steps:
  - say: The first teaser has the focus after launch
    do: { assertFocused: { id: home.teaser } }
  - say: Move one teaser to the right
    do: { pressKey: right }
  - say: The second teaser has the focus now
    do: { assertFocused: { id: home.teaser, index: 1 } }
  - say: Go down to the sports row, at most 15 presses
    do: { pressKey: { key: down, until: { id: row.sports }, max: 15 } }
  - say: Open it with the select button
    do: { pressKey: select }
```

- **No touch.** `tap`, `doubleTap`, `longPress`, `scroll`, `scrollUntilVisible`, `swipe` and `drag` fail on a TV with a message that points to `pressKey`: a tap would pass where a person with the remote gets stuck (a focus trap, a row the focus cannot reach). Reach what is off screen with `pressKey` and `until`.
- **Focus.** `assertFocused` checks where the focus is, `pressKey` with `until` moves it and stops with `focus stuck on <element> after N presses` when a press did not move it (the end of a row, a trap); the focus is the `focused` marker of `gekko tree`.
- **Typing.** `type` only types into a field that already has the focus (move it there with `pressKey` first), because that is how a person reaches the field; the failure says where the focus is. `typeFocused` works as before.
- **Healing.** With `--heal` the model proposes a `focus` (a move to another element), never a tap or a scroll.
- Put the tests in a tag such as `tv` and run the phone and the TV tests on their devices (`--tags tv`, `--exclude-tags tv`).

## What works where

| | Android | iOS simulator | Web (Chromium, Firefox, WebKit) |
|---|---|---|---|
| All selector keys, `tap`, `doubleTap`, `longPress` (also with `point`), `type`, `typeFocused`, `pressKey` (`enter`, `backspace`, `tab`), `eraseText`, `scroll`, `scrollUntilVisible`, `swipe`, `drag`, assertions, `waitForAnimationToEnd`, `wait`, `takeScreenshot`, `copyText`, `hideKeyboard`, `openLink`, `launchApp`, `stopApp`, `clearState`, `setDarkMode`, `setOrientation`, `repeat`, `use`, `system: true` on `tap`, `doubleTap`, `longPress`, `swipe`, `drag`, `typeFocused` and `pressKey` | yes | yes | yes |
| `tap` with `point` | yes | no | no |
| `pressKey` with `enter`, `backspace`, `tab` | yes | yes | yes |
| `assertFocused`, `pressKey` with `until` | yes | yes (on an iPhone the focus is the field of the keyboard, on an Apple TV the element the remote focused) | yes (the focused element) |
| `pressKey` with `up`, `down`, `left`, `right`, `select`, `playPause`, `menu` | yes, through the system runner | Apple TV simulators only | the arrow keys, `select` is Enter, `playPause` is MediaPlayPause; `menu` is not a key |
| `pressKey` with `home` or a key code, `back` | yes | `home` only: `back` and a key code fail with the server's message | history back, any Playwright key name |
| `setAirplaneMode` | yes | no: the step fails ("is not supported") | yes (offline) |
| `launchPackage`, `stopPackage` | yes | no | no |
| `setLocation` | yes (emulators through the console, physical devices through the system runner) | simulators only | yes |
| `setPermissions` | all names, or a full permission name | `location`, `microphone`, `contacts`, `calendar`, `photos` | `location`, `camera`, `microphone`, `notifications`, `clipboard` or a Playwright permission name |
| `checked` | switches, checkboxes, radio buttons | switches and toggle buttons (iOS 17 and later for toggle buttons) | checkboxes, radio buttons, `aria-checked` and `aria-pressed` |
| `mock`, `clearMocks` | OkHttp and `HttpURLConnection` of a debuggable build with the gekko Gradle plugin, on emulators and devices | everything on `URLSession`, simulators only | every request of the page (not the ones a service worker answers) |

iOS runs on simulators only. Steps that only one platform can do go behind `platform: android`, `platform: ios` or `platform: web` (or a list such as `platform: [android, web]`) so the same file runs everywhere. Platform-only values for an id belong to the same rule: `id: { android: a }` alone needs `platform: android`.

### On the web

- **What an element is.** `id` is `data-testid` (or `flt-semantics-identifier`; `web.idAttributes` changes the list; the HTML `id` is not used). `text` is the text of the element (the value of a field, the label of a button) and the accessible label (`aria-label`, an associated `<label>`, `title`, `alt`) counts as the text too, so `{ textContains: "E-Mail", action: setText }` is "the e-mail field". `gekko tree` shows what gekko sees.
- **On screen means in the window.** Content below the fold is not visible; the window size is `web.viewport` (make it as big as the page needs). `tap`, `doubleTap`, `longPress` and `type` scroll the page to an element that is outside the window, `assertVisible` does not, and an element that a container hides (a row far down a list) needs `scroll` or `scrollUntilVisible`. Scrolling turns the mouse wheel, `swipe` and `drag` move the mouse with the button down.
- **Every test is a new browser context:** no cookies, no storage, no granted permissions, so a consent banner is back in every test (an `optional: true` step in a flow dismisses it; raise `optionalTimeoutMs` if it comes late) and a login has to be done in each test (a flow with `${env.NAME}` values). Many logins in a short time can trip a rate limit (a CloudFront 403 page): then use `web.session: shared` (one context for the whole run; the login flow guards its steps with `if: { visible: <login heading> }` so only the first test logs in; tests depend on their order, so the logout test goes last, e.g. in a `zz-` folder) or a pause. `app.web` can name a variable (`${env.STAGE}`) to switch environment.
- **Launch and state.** `launchApp` loads `app.web` again (`arguments` become query parameters), `clearState` starts a new browser context and `stopApp` closes it.
- **Shared session** (`web.session: shared`, or `GEKKO_WEB_SESSION=shared`). The `device:` language and time format apply to the first test only, and `--record` records no video. A session that `gekko launch` started and a person logged in to by hand (`GEKKO_WEB_HEADED=1`) is used as it is by the following `gekko run`.
- **Device actions.** `setDarkMode` sets the page's `prefers-color-scheme`, `setOrientation` swaps the sides of the window, and a `<select>` is set with `type` (the label or the value of the option).
- **Dialogs.** A native `alert`, `confirm`, `prompt` or `beforeunload` is the topmost window with the buttons `OK` and `Cancel` (`Leave` and `Stay`): `tap: { text: OK, system: true }`; a prompt takes its text with `type: { id: gekko.dialog.field, value: ... }` first. A step that cannot find its element while a dialog is open says so.
- **Slow pages.** A page that shows nothing new while a request runs counts as settled after `settleMs`; raise `settleMs` (and `timeout` on the step) for slow backends instead of adding `wait`.
- **Not available:** URL assertions (assert a text of the new page), native pickers (a `<select>` or a date input is set with `type`), `launchPackage`, `stopPackage`, `tap` with `point`.

## Network mocks

Some backend answers are not easy to provoke: an error, a slow answer, an empty list, data that is only there at some times. A `mock` step makes the app get the answer the test wants, from a file, while everything else still goes to the real backend. The app needs no code for it and no update.

```yaml
# tests/profile-shows-name.yaml
name: The profile shows the name
steps:
  - say: The backend answers with a known profile          # leading mock: set before the app starts
    do: { mock: { url: "https://api.example.com/v1/profile", file: profile.json } }
  - say: Open the profile
    do: { tap: { id: Home.Tab.Profile } }
  - say: The name from the file is shown
    do: { assertVisible: { id: Profile.Name, text: Ada } }
  - say: Now the backend is down
    do: { mock: { url: "https://api.example.com/v1/profile", status: 503, delayMs: 2000 } }
  - say: Reload the profile
    do: { tap: { id: Profile.Reload } }
  - say: The error is shown
    do: { assertVisible: { id: Profile.Error } }
  - say: Back to the real backend
    do: { clearMocks: true }
```

**A test always undoes every mock when it ends**, whether it passed, failed or the app crashed. The next test starts with the real backend again, and `clearMocks` is only needed to go back to it in the middle of a test. The mock is held by the app (and, on the web, by the bridge), never changed on the backend or the device settings.

| Key | Meaning |
|---|---|
| `url` | Required. A pattern for the whole URL, query string included: `*` stands for any text (also `/`, `?` and `&`), everything else is literal. `https://api.example.com/v1/items*` matches `/v1/items`, `/v1/items?page=2&sort=name` and `/v1/items/42`. To tell two calls apart by a parameter write it into the pattern (`https://api.example.com/v1/items?*page=2*`); the parameters have to be in that order. |
| `method` | `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD` or `OPTIONS`. Without it every method matches. |
| `bodyContains` | Text the body of the request has to contain, for APIs that send every call to one URL: `'"operationName":"Profile"'` for a GraphQL operation. Only the first 1 MB of a body is looked at: a larger one (an upload) never matches and goes on to the next mock or the real backend. |
| `file` | The answer, a file below `mocks/` (`mocksDir` in `gekko.yaml` renames the folder): the body exactly as the backend sends it, so a real response can be pasted in. The extension decides the `Content-Type` (`.json`, `.xml`, `.html`, `.csv`, `.txt`, otherwise `application/octet-stream`). Without `file` the answer has no body, e.g. a bare 503. Text files only, all mocks together up to about 2 MB. |
| `status` | The HTTP status, `200` by default. |
| `headers` | More headers of the answer, e.g. `{ Retry-After: "30" }`; a `Content-Type` here wins over the one of the extension. A name has letters, digits and ``!#$%&'*+-.^_`|~`` only, a value printable ASCII only (no line breaks, no umlauts): HTTP clients refuse the rest, so `gekko` refuses it when it reads the test. |
| `delayMs` | Waits this long before the answer, up to 120000, to see a spinner or a timeout (default 0). |

- **Which mock answers.** Of all mocks that match a request, the one that was set **last** answers, so a later step can override an earlier one. Setting a mock with the same `url`, `method` and `bodyContains` replaces the earlier one. A request that matches no mock goes to the real backend untouched.
- **Mocks at the start of a test.** Many apps fetch data while they start. Consecutive `mock` steps at the very start of a test (also in the flows of `uses:` or `use` steps that begin the test; steps for other platforms do not break the run) are set **before** gekko starts the app, so even its first request is answered. A `mock` step later takes effect at once in the running app. A step with an `if` or any other action ends the leading run.
- **Texts in mock files.** The files take the placeholders of tests: `${now(+10m)}` and `${now(+10m, 'HH:mm')}` (a departure in ten minutes, however late the test runs), `${var.name}` (a text kept by `copyText`), `${date(...)}`, `${random.uuid}` and `${env.NAME}` (declared in `env:`). They are filled in when the step runs, and in a `.json` file the values are escaped so that they cannot break it. A placeholder written wrong (`${now(+10x)}`) is reported by `gekko check`, which also reports a missing file.

  ```json
  { "name": "Ada", "lastSeen": "${now(-5m)}", "lastSeenAt": ${now(-5m, epochMillis)} }
  ```
- **What you see.** The log prints every answered request, `mocked GET https://api.example.com/v1/profile → profile.json`, and a mock that no request used is a warning when the test ends (also in the JUnit file as the output of the test): usually a typo in the URL, or an HTTP client gekko cannot reach. The result of the test does not change. `mock` takes no `timeout`, `optional`, `until` or `attempts`; it is limited to a platform with `platform:` like any step.
- **Launching again.** A new app process knows no mocks, so `launchApp` hands them over again before the app starts. A mock does not survive the app restarting by itself (the system killing it).

### Where mocks work

| Platform | Devices | What it needs |
|---|---|---|
| Android | emulators and physical devices | a debuggable build with the gekko Gradle plugin (`networkMocks` is on by default, unless the plugin is told to use an agent of another release) |
| iOS | simulators only (there is no real-device support at all) | the gekko agent injected into the app (`ios.agentFramework`) |
| Web | Chromium, Firefox and WebKit | nothing in the page |

A library counts as covered when it makes its requests through one of the covered clients. gekko's own end-to-end tests run OkHttp and `HttpURLConnection` (Android), `URLSession` (iOS) and `fetch` (web); the other names follow from how those libraries send their requests. When a client of yours is not answered, the warning "network mock was never used" at the end of the test tells you.

**Android**

- Works:
  - **OkHttp** (3.12 up to 5.x) and everything built on it: Retrofit, Apollo Kotlin, Coil, Picasso, Glide with its OkHttp integration, Ktor with the OkHttp engine, React Native.
  - **`HttpURLConnection`** (`URL.openConnection()` and `URL.openStream()`) in the app and in its libraries: Volley, Glide's default loader, Ktor's Android engine.
  - HTTP and HTTPS alike: the mock answers before anything goes to the network, so no certificate, proxy or device setting is involved. A mocked OkHttp answer passes the app's own interceptors, so its error handling and logging see it like a real one.
- Does not work:
  - Flutter, Cronet, gRPC, Ktor with the CIO engine, WebSocket, networking in native (NDK) code, and any client that opens sockets by itself.
  - Content inside a `WebView`.
  - An OkHttp that an app or library repackaged under another package name (not `okhttp3`).
  - A connection that the app opened before its mock was set.
- Good to know: the mocks at the start of a test reach the app through its private directory (`run-as`), which needs a debuggable build, the build that has the agent anyway. `gekko { networkMocks.set(false) }` leaves the bytecode of the app alone, and mocks do nothing then.

**iOS simulators**

- Works: everything on **`URLSession`**: `URLSession.shared`, and the default and ephemeral configurations of any session the app creates (also `async`/`await`), so Alamofire, Moya, Apollo iOS, Kingfisher, SDWebImage and React Native work too.
- Does not work:
  - `WKWebView` content, Flutter, Cronet, and clients that use sockets or `Network.framework` directly.
  - Background sessions (`URLSessionConfiguration.background`).
  - A session whose `protocolClasses` the app replaces itself.
  - `bodyContains` on a request whose body the app gave as its own `InputStream` (a body set with `httpBody`, the usual JSON or GraphQL case, works).
- Real iPhones are not supported by gekko.

**Web (Chromium, Firefox, WebKit)**

- Works: every request of the page and its frames, whatever sends it: `fetch`, `XMLHttpRequest`, images, scripts, stylesheets, form posts and navigations. The bridge answers cross-origin requests with the CORS headers the page needs.
- Does not work:
  - Requests that a service worker answers or sends.
  - WebSocket and other streaming connections.
  - Requests of browser extensions and the browser's own.

## Healing and the format

With `--heal`, a step whose element is missing can pass with a warning. Only these steps are healed: `tap`, `type` and `scroll` (the model picks an existing element; a healed `scroll` scrolls down by the default distance), `assertVisible` (only when its id is nowhere in the tree and its texts and states hold on the screen), and the `until` of a step, checked like an assertion. `doubleTap`, `longPress`, `eraseText`, `scrollUntilVisible`, `swipe` and `assertNotVisible` are never healed. At most 15 heals per test. A `tap` with `system: true` that finds no element is skipped with a warning and an entry in the heal report (no model call): a permission dialog that did not appear is not an error under `--heal`, and the entry also shows up in `--junit-healed`. When the step used an element of a pane, the report names it and its file (`fix: login.submit (panes/login.yaml)`): fix the element there and every test is repaired. See `../../gekko-setup/references/cli.md`.
