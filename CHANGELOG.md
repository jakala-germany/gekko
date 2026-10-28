# Changelog

## [Snapshot]

[Snapshot]: https://github.com/jakala-germany/gekko/compare/1.0.0...HEAD

**New**

- Upcoming additions go here

**Changed**

- Upcoming changes go here

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
