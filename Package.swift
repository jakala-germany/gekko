// swift-tools-version:5.9
import PackageDescription

// iOS in-process agent. Add as a package dependency and link the GekkoAgent product into debug builds only.
// The agent stays dormant (no listener) unless GEKKO_PORT and GEKKO_TOKEN are set; gekko injects the framework and sets both.
let package = Package(
    name: "GekkoAgent",
    platforms: [.iOS(.v15)],
    products: [
        // Dynamic so the C constructor in the bootstrap target runs at app launch without any app code.
        .library(name: "GekkoAgent", type: .dynamic, targets: ["GekkoAgent"]),
    ],
    targets: [
        .target(name: "GekkoAgentBootstrap", path: "agent-ios/Sources/GekkoAgentBootstrap"),
        .target(name: "GekkoAgent", dependencies: ["GekkoAgentBootstrap"], path: "agent-ios/Sources/GekkoAgent"),
    ]
)
