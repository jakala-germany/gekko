# Security Policy

## Reporting a vulnerability

Please report vulnerabilities privately through GitHub: open the **Security** tab of this repository and choose
**Report a vulnerability**. Do not open public issues or pull requests for security reports.

Include what you found, how to reproduce it, which gekko version and platform (Android, iOS simulator, browser, host OS) are
affected, and what an attacker gains. We acknowledge reports within a few working days and keep you informed until a
fix is released.

## Supported versions

The latest released minor version of the current major version receives security fixes.

## What is in scope

gekko is a test tool. It runs on a developer machine or a CI runner, talks to emulators, simulators, phones and browsers, and
puts a small agent into debug builds of the app under test (on the web it injects a read-only script into the page instead). Reports about these are in scope:

- anything that lets another process, another app, a web page or a pull request read from or drive a device through
  gekko without the user's consent (the agent and system runner command servers, the `adb` forwards, the iOS loopback
  ports, the web bridge);
- secrets of the user or of CI (API keys, tokens, test credentials) reaching logs, reports, prompts, artifacts or the
  network;
- code execution through test files, flows, `gekko.yaml`, model output or the files that the install script and the
  setup action download;
- weaknesses in the release pipeline and the install path: artifacts, checksums, provenance, `scripts/install.sh`, the
  setup action.

## Good to know

- The agent belongs in the variants you list in `gekko { variants.set(...) }` (default `debug`), never in a build for
  users. It can tap anything and read the whole UI; a store build that contains it is a remote control for the app.
  Listing a non-debuggable variant ships the agent in that build, and that is the developer's decision.
- The agent and the system runner only accept commands from the machine that runs the CLI:
    - Android: the agent and the runner listen on private abstract Unix sockets (`gekko-<appId>`,
      `gekko-system-runner`) reached through `adb forward`. Only `adb` (shell), root and the app itself can connect, and
      the agent adds no INTERNET permission.
    - iOS simulators: loopback with a port per simulator. Every request needs the per-user token in `~/.gekko/token`
      (readable by that user only) and a loopback `Host` header without an `Origin`, so other users and web pages are
      turned away.
    - Web bridge (a Node process with Playwright that the CLI starts for a browser session): it listens on `127.0.0.1`
      only, needs the same per-user token, a loopback `Host` and no `Origin` header, so a web page open in any browser
      on the machine cannot reach it, and it offers a fixed list of operations: it never runs JavaScript, a command or a
      file path that arrives in a request, except the folder of the app under test that it serves and the video file the
      CLI names. It ends by itself after 30 minutes without a request. The page script it injects into the tested pages
      only reads the page and sends nothing anywhere; a page under test can see that the script is there.
- Test credentials belong in the environment (`${env.NAME}` with `env:` in `gekko.yaml`), never in test files. Add `.env`
  and `gekko-results*/` to `.gitignore` (the test-repo template of `gekko-setup` does). Values are masked in text output;
  screenshots and videos are not masked.
