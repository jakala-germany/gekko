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
    enableAutomationAccessibility()
    let executor = CommandExecutor()
    let server = HttpCommandServer(port: port, token: token) { command in executor.execute(command) }
    server.start()
    GekkoAgentRetainer.server = server
}

enum GekkoAgentRetainer {
    static var server: HttpCommandServer?
}

/// SwiftUI only exposes accessibility identifiers once an accessibility client is attached; automation mode
/// (what XCUITest and KIF switch on) makes it build the tree without VoiceOver.
private func enableAutomationAccessibility() {
    guard let handle = dlopen("/usr/lib/libAccessibility.dylib", RTLD_LOCAL),
          let symbol = dlsym(handle, "_AXSSetAutomationEnabled") else { return }
    typealias SetAutomationEnabled = @convention(c) (Int32) -> Void
    unsafeBitCast(symbol, to: SetAutomationEnabled.self)(1)
}
