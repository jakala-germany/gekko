import UIKit

final class CommandExecutor {
    private static let pollSeconds = 0.016
    private static let idleWaitSeconds = 0.5
    private static let maxTimeoutSeconds = 600.0
    private static let verifySeconds = 0.3
    private static let verifyPollSeconds = 0.05
    /// Keep in sync with Protocol.VERSION of the shared protocol.
    private static let protocolVersion = 1
    /// Set to 0 to skip checking that a tap changed anything.
    private static let verifyTaps = ProcessInfo.processInfo.environment["GEKKO_TAP_VERIFY"] != "0"

    /// What an action did inside the main-thread block of withElement.
    private enum ActionResult {
        case done([String: Any])
        /// Sent something to the element; the screen has to change for the tap to count (compared with `before`).
        case verify(before: Int)
    }

    func execute(_ command: [String: Any]) -> [String: Any] {
        guard let type = command["type"] as? String else { return failure("Command has no type", ErrorKind.unsupported) }
        switch type {
        case "hello": return ["type": "hello", "protocol": Self.protocolVersion, "version": GekkoBuild.version, "role": "agent"]
        case "metrics": return metrics()
        case "tree": return ["type": "tree", "windows": onMain { TreeReader.read().map { $0.toJson() } }]
        case "foreground": return ["type": "foreground", "appInForeground": onMain { UIApplication.shared.applicationState == .active }]
        case "screenshot": return failure("Screenshot is handled by the host CLI", ErrorKind.unsupported)
        case "waitIdle": return waitIdle(timeout: seconds(command["timeoutMs"]))
        case "tap": return tap(command)
        case "tapAt": return tapAt(command)
        case "type": return typeText(command)
        case "scroll": return scroll(command)
        case "swipe": return swipe(command)
        case "setOrientation": return failure("setOrientation is served by the system runner", ErrorKind.unsupported)
        case "doubleTap", "longPress": return failure("\(type) needs real touches: the CLI sends it to the system runner", ErrorKind.unsupported)
        default: return failure("Unknown command \(type)", ErrorKind.unsupported)
        }
    }

    private func metrics() -> [String: Any] {
        let rtl = onMain { () -> Bool in
            let window = TreeReader.windows().last(where: { $0.isKeyWindow }) ?? TreeReader.windows().last
            let direction = window?.effectiveUserInterfaceLayoutDirection ?? UIApplication.shared.userInterfaceLayoutDirection
            return direction == .rightToLeft
        }
        return ["type": "metrics", "density": 1.0, "rtl": rtl]
    }

    private func tap(_ command: [String: Any]) -> [String: Any] {
        return withElement(command) { element, before in
            switch ElementActions.tap(element) {
            case .handled: return .done(self.ok())
            case .sent: return Self.verifyTaps ? .verify(before: before) : .done(self.ok())
            case .rejected: return .done(self.notHandled("Tap"))
            }
        }
    }

    private func tapAt(_ command: [String: Any]) -> [String: Any] {
        guard let x = finite(command["x"]), let y = finite(command["y"]) else { return failure("tapAt needs numeric x and y", ErrorKind.unsupported) }
        let outcome: ActionResult = onMain {
            let all = TreeReader.read().flatMap { $0.flatten() }
            let before = TreeReader.signature(all)
            switch ElementActions.tap(at: CGPoint(x: x, y: y), among: all) {
            case .handled: return .done(self.ok())
            case .sent: return Self.verifyTaps ? .verify(before: before) : .done(self.ok())
            case .rejected: return .done(self.failure("Nothing at (\(Element.integer(CGFloat(x))), \(Element.integer(CGFloat(y)))) can be tapped without a real touch: the CLI sends it to the system runner", ErrorKind.unsupported))
            }
        }
        return finish(outcome)
    }

    private func typeText(_ command: [String: Any]) -> [String: Any] {
        let text = command["text"] as? String ?? ""
        return withElement(command) { element, _ in
            return .done(ElementActions.type(element, text: text) ? self.ok() : self.failure("Element is not editable", ErrorKind.rejected))
        }
    }

    private func scroll(_ command: [String: Any]) -> [String: Any] {
        var direction = "down"
        if let raw = command["direction"], !(raw is NSNull) {
            guard let value = raw as? String else { return failure("Scroll direction must be a string", ErrorKind.unsupported) }
            direction = value
        }
        guard ElementActions.isScrollDirection(direction) else { return failure("Unknown scroll direction '\(direction)'", ErrorKind.unsupported) }
        var distance = 400.0
        if let raw = command["distanceDp"], !(raw is NSNull) {
            guard let value = finite(raw), value >= 0 else { return failure("distanceDp must be a number of points", ErrorKind.unsupported) }
            distance = value
        }
        return withElement(command) { element, _ in
            return .done(ElementActions.scroll(element, direction: direction, distance: CGFloat(distance)) ? self.ok() : self.failure("Nothing scrollable \(direction)", ErrorKind.rejected))
        }
    }

    private func swipe(_ command: [String: Any]) -> [String: Any] {
        guard let fromX = finite(command["fromX"]), let fromY = finite(command["fromY"]), let toX = finite(command["toX"]), let toY = finite(command["toY"]) else {
            return failure("swipe needs numeric fromX, fromY, toX and toY", ErrorKind.unsupported)
        }
        let moved = onMain { ElementActions.swipe(from: CGPoint(x: fromX, y: fromY), to: CGPoint(x: toX, y: toY)) }
        return moved ? ok() : notHandled("Swipe")
    }

    /// Finds the element (matching, enabled, on screen, in document order) and runs the action on the main thread.
    private func withElement(_ command: [String: Any], _ action: @escaping (Element, Int) -> ActionResult) -> [String: Any] {
        let timeout = seconds(command["timeoutMs"])
        let settle = min(seconds(command["settleMs"], fallback: 0), Self.maxTimeoutSeconds)
        let selector: ElementSelector
        do {
            selector = try ElementSelector.parse(command["selector"])
        } catch let problem as SelectorError {
            return failure(problem.message, ErrorKind.unsupported)
        } catch {
            return failure("Selector could not be read", ErrorKind.unsupported)
        }
        // A page with an endless animation never gets idle; the lookup below polls anyway, so the wait must not eat the step's timeout
        _ = waitIdle(timeout: min(timeout, Self.idleWaitSeconds))
        let deadline = Date().addingTimeInterval(timeout)
        // Done = nothing changed for `settle` and no progress indicator; only a loading screen deserves the full timeout
        var lastSignature: Int?
        var lastActivity = Date()
        while true {
            var found: ActionResult?
            var miss = ErrorKind.notFound
            var detail = ""
            onMain {
                let all = TreeReader.read().flatMap { $0.flatten() }
                let signature = TreeReader.signature(all)
                if all.contains(where: { $0.busy }) || signature != lastSignature {
                    lastSignature = signature
                    lastActivity = Date()
                }
                let matching = all.filter { selector.matches($0) }
                // Disabled nodes only count when the selector asks for them
                let enabled = selector.enabled == nil ? matching.filter { $0.enabled } : matching
                // Pages and lists keep off-screen duplicates in the tree; a person can only tap what is visible
                let screen = UIScreen.main.bounds
                let visible = enabled.filter { screen.contains(CGPoint(x: $0.frame.midX, y: $0.frame.midY)) }
                if selector.index < visible.count {
                    found = action(visible[selector.index], signature)
                } else if !visible.isEmpty {
                    detail = " (index \(selector.index), \(visible.count) on screen)"
                } else if !enabled.isEmpty {
                    miss = ErrorKind.notOnScreen
                } else if !matching.isEmpty {
                    miss = ErrorKind.disabled
                }
            }
            if let found { return finish(found) }
            let settled = settle > 0 && Date().timeIntervalSince(lastActivity) >= settle
            if settled || Date() > deadline {
                return missing(miss, selector, detail, settled: settle > 0 ? settled : nil)
            }
            Thread.sleep(forTimeInterval: Self.pollSeconds)
        }
    }

    private func missing(_ kind: String, _ selector: ElementSelector, _ detail: String, settled: Bool?) -> [String: Any] {
        let what: String
        switch kind {
        case ErrorKind.notOnScreen: what = "Element is not on screen"
        case ErrorKind.disabled: what = "Element is disabled"
        default: what = "Element not found"
        }
        return failure("\(what): \(selector)\(detail)\(settled == true ? " (screen settled)" : "")", kind, settled: settled)
    }

    private func finish(_ result: ActionResult) -> [String: Any] {
        switch result {
        case .done(let response): return response
        case .verify(let before): return awaitChange(since: before) ? ok() : notHandled("Tap")
        }
    }

    /// Accessibility activation and control actions report success even when the app ignores them (a SwiftUI tap
    /// gesture, a menu button); a tap counts when something a person could see changed within a moment.
    private func awaitChange(since before: Int) -> Bool {
        let deadline = Date().addingTimeInterval(Self.verifySeconds)
        repeat {
            Thread.sleep(forTimeInterval: Self.verifyPollSeconds)
            if onMain({ TreeReader.signature(TreeReader.read().flatMap { $0.flatten() }) }) != before { return true }
        } while Date() < deadline
        return false
    }

    /// Idle = the UI tree is identical across two consecutive frames after the main queue drained.
    private func waitIdle(timeout: TimeInterval) -> [String: Any] {
        let deadline = Date().addingTimeInterval(timeout)
        var previous = snapshot()
        while Date() < deadline {
            frame()
            let current = snapshot()
            if current == previous { return ok() }
            previous = current
        }
        return failure("App did not become idle in \(Int(timeout * 1000))ms", ErrorKind.timeout)
    }

    private func frame() {
        let semaphore = DispatchSemaphore(value: 0)
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.pollSeconds) { semaphore.signal() }
        semaphore.wait()
    }

    private func snapshot() -> Data {
        let json = onMain { TreeReader.read().map { $0.toJson() } }
        guard JSONSerialization.isValidJSONObject(json) else { return Data() }
        return (try? JSONSerialization.data(withJSONObject: json, options: [.sortedKeys])) ?? Data()
    }

    @discardableResult
    private func onMain<T>(_ block: () -> T) -> T {
        return Thread.isMainThread ? block() : DispatchQueue.main.sync(execute: block)
    }

    /// Timeouts come from the wire: negative, huge or missing values must not break the loops.
    private func seconds(_ value: Any?, fallback: Double = 5000) -> TimeInterval {
        let milliseconds = finite(value) ?? fallback
        return min(max(milliseconds, 0), Self.maxTimeoutSeconds * 1000) / 1000
    }

    private func finite(_ value: Any?) -> Double? {
        guard let number = value as? NSNumber, number.doubleValue.isFinite else { return nil }
        return number.doubleValue
    }

    private func notHandled(_ what: String) -> [String: Any] {
        return failure("\(what) was not handled", ErrorKind.rejected)
    }

    private func ok() -> [String: Any] {
        return ["type": "ok"]
    }

    private func failure(_ message: String, _ kind: String, settled: Bool? = nil) -> [String: Any] {
        var json: [String: Any] = ["type": "error", "message": message, "kind": kind]
        if let settled { json["settled"] = settled }
        return json
    }
}

/// Facts about the build of this framework.
enum GekkoBuild {
    /// The release version; the build scripts write it into the framework's Info.plist (GekkoVersion).
    static let version: String = {
        let value = Bundle(for: CommandExecutor.self).object(forInfoDictionaryKey: "GekkoVersion") as? String
        return value?.isEmpty == false ? value ?? "unknown" : "unknown"
    }()
}
