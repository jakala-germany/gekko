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
  ports, the web bridge), and anything that lets one of them pass for such a server to the CLI or put text into the
  CLI's output or artifacts that a terminal acts on;
- secrets of the user or of CI (API keys, tokens, test credentials) reaching logs, reports, prompts, artifacts or the
  network;
- code execution through test files, flows, `gekko.yaml`, model output or the files that the install script and the
  setup action download;
- weaknesses in the release pipeline and the install path: artifacts, checksums, provenance, `scripts/install.sh`, the
  setup action.

## Good to know

- The agent belongs in the variants you list in `gekko { variants.set(...) }` (default `debug`), never in a build for
  users. It can tap anything and read the whole UI; a store build that contains it is a remote control for the app. On
  Android the agent only answers a CLI that holds its key, and the CLI hands the key over with `run-as`, which only a
  debuggable build allows: in any other build the agent refuses every command, and the Gradle plugin warns about the
  variant. Do not list a non-debuggable variant.
- The CLI and the servers it talks to (Android agent and system runner, iOS agent and system runner, web bridge)
  authenticate each other. Whoever listens on a port or a socket first can answer the CLI, so a program that does so
  (another app on the device, another process or user of the machine) can neither give orders nor pass for the server:
    - Every request carries a fresh nonce and an HMAC-SHA256 over its endpoint (the port, on Android the socket name), the
      session of the server, the nonce and the body. Every answer carries an HMAC over the same nonce, its status and its
      body. The key never goes over the connection. The CLI believes no answer that is not signed for its request: a fake
      server cannot fail tests, return a fake tree or hand out a fake result, the run stops with an error that names the
      port. A server that does not sign (one from before protocol 2) is refused the same way, so update the agent, the
      Gradle plugin and the CLI together.
    - The session is a random id that a server picks when it starts and names in its signed answer to `hello`. Every other
      command has to carry it, so a request that was recorded against a fake server is worthless against the real one,
      and one that was recorded earlier is worthless once the app has restarted. A program that relays every request and
      answer unchanged to the real server can still delay or repeat them within one session.
    - Android: the agent and the runner listen on private abstract Unix sockets (`gekko-<appId>`, `gekko-system-runner`)
      reached through `adb forward`. Only `adb` (shell), root and the app itself can connect, and the agent adds no
      INTERNET permission. On top of that every socket has its own key, derived from the per-user token (the token itself
      never goes onto a device): the agent reads its key from a file in the private directory of the app that the CLI
      writes with `run-as`, the runner gets its key as an argument of `am instrument`. No other app can read either, so no
      other app can pass for the agent or the runner.
    - iOS simulators: loopback with a port per simulator. The key is the per-user token in `~/.gekko/token` (readable by
      that user only; on Windows a protected ACL that names only that user, so not even administrators or SYSTEM are
      listed, and nothing is inherited from the folder above), handed to the agent and the runner through the launch
      environment, and a loopback `Host` header without an `Origin` is required, so other users and web pages are turned
      away.
    - Web bridge (a Node process with Playwright that the CLI starts for a browser session): it listens on `127.0.0.1`
      only, signs and checks like the iOS servers, needs a loopback `Host` and no `Origin` header, so a web page open in
      any browser on the machine cannot reach it, and it offers a fixed list of operations: it never runs JavaScript, a
      command or a file path that arrives in a request, except the folder of the app under test that it serves and the
      video file the CLI names. It ends by itself after 30 minutes without a signed request. The page script it injects
      into the tested pages only reads the page and sends nothing anywhere; a page under test can see that the script is
      there. A bridge of an older gekko that cannot sign is ended through the process named in its own session file, never
      by a request.
- Text that comes from the app under test (ids, texts, error messages, the console of a page) can hold anything, among
  it escape sequences that a terminal executes. The CLI turns every control character in it into a visible escape
  (`\u001b`) before it prints it or writes it to an artifact (`tree.txt`, `console.txt`, `failure.json`, the heal report,
  the JUnit files), so reading a failed run in a terminal, with `cat` or in an editor is safe. Take care with anything
  else that displays the raw text of an app.
- Test credentials belong in the environment (`${env.NAME}` with `env:` in `gekko.yaml`), never in test files. Add `.env`
  and `gekko-results*/` to `.gitignore` (the test-repo template of `gekko-setup` does). Values are masked in text output;
  screenshots and videos are not masked.
