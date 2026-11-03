import Foundation
import GekkoAgentBootstrap

private var started = false

/// Called by the C constructor when the dylib loads; no app code is needed. The agent is dormant unless gekko started
/// the app with GEKKO_PORT (loopback TCP port) and GEKKO_TOKEN (per-run token): without both it does nothing and
/// nothing listens.
@_cdecl("gekko_agent_start")
public func gekkoAgentStart() {
    guard !started else { return }
    started = true
    gekko_agent_bootstrap_anchor()
    let environment = ProcessInfo.processInfo.environment
    guard let port = environment["GEKKO_PORT"].flatMap({ UInt16($0) }), port > 0,
          let token = environment["GEKKO_TOKEN"], !token.isEmpty else { return }
    // Nothing the app starts later should inherit the token
    unsetenv("GEKKO_TOKEN")
    startMocks(file: environment["GEKKO_MOCKS"])
    unsetenv("GEKKO_MOCKS")
    enableAutomationAccessibility()
    let executor = CommandExecutor()
    let server = HttpCommandServer(port: port, token: token) { command in executor.execute(command) }
    server.start()
    GekkoAgentRetainer.server = server
}

enum GekkoAgentRetainer {
    static var server: HttpCommandServer?
}

/// Hooks into URLSession before the app makes its first request, and applies the mocks the CLI wrote to `file` (the same
/// JSON as the setMocks command) so the requests of the app's own start are mocked too. The file belongs to this launch:
/// it is deleted, and a missing or broken one is ignored.
private func startMocks(file: String?) {
    MockURLProtocol.install()
    guard let file, !file.isEmpty else { return }
    defer { try? FileManager.default.removeItem(atPath: file) }
    guard let data = FileManager.default.contents(atPath: file) else { return }
    guard let command = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
        NSLog("Gekko could not read the network mocks of this start: the file is not a JSON object")
        return
    }
    do {
        try MockStore.shared.setMocks(command)
    } catch {
        // The app starts without mocks; the CLI believes it has delivered them, so this is the one place to say so
        NSLog("Gekko could not read the network mocks of this start: %@", (error as? MockError)?.message ?? "\(error)")
    }
}

/// SwiftUI only exposes accessibility identifiers once an accessibility client is attached; automation mode
/// (what XCUITest and KIF switch on) makes it build the tree without VoiceOver.
private func enableAutomationAccessibility() {
    guard let handle = dlopen("/usr/lib/libAccessibility.dylib", RTLD_LOCAL),
          let symbol = dlsym(handle, "_AXSSetAutomationEnabled") else { return }
    typealias SetAutomationEnabled = @convention(c) (Int32) -> Void
    unsafeBitCast(symbol, to: SetAutomationEnabled.self)(1)
}
