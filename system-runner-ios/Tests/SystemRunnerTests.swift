import UIKit
import XCTest

/// Serves the gekko protocol on 127.0.0.1:GEKKO_PORT (token GEKKO_TOKEN) for everything outside the app process:
/// permission alerts, Safari / SFSafariViewController / ASWebAuthenticationSession and SpringBoard. On an Apple TV
/// (tvOS) there is no touch: it presses the buttons of the remote and reads which element has the focus. Blocks until
/// killed. xcodebuild passes the TEST_RUNNER_GEKKO_PORT and TEST_RUNNER_GEKKO_TOKEN of the CLI on as GEKKO_PORT and
/// GEKKO_TOKEN.
final class SystemRunnerTests: XCTestCase {
    /// Keep in sync with Protocol.VERSION of the shared protocol.
    private static let protocolVersion = 2
    private static let maxTimeoutSeconds = 600.0
    private static let pollSeconds = 0.1
    private static let maxResolved = 8
    private static let changeSeconds = 1.0
    /// The commands this runner serves, as `hello` lists them. Keep in sync with the cases of `execute`.
    #if os(tvOS)
    private static let servedCommands = ["hello", "metrics", "tree", "foreground", "waitIdle", "type", "typeFocused", "pressKey"]
    #else
    private static let servedCommands = [
        "hello", "metrics", "tree", "foreground", "waitIdle", "tap", "doubleTap", "longPress", "type", "typeFocused",
        "pressKey", "tapAt", "longPressAt", "scroll", "swipe", "drag", "setOrientation",
    ]
    #endif

    /// The system UI that shows alerts and the home screen: SpringBoard on iOS, PineBoard on tvOS.
    #if os(tvOS)
    private let springboard = XCUIApplication(bundleIdentifier: "com.apple.PineBoard")
    #else
    private let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    #endif
    private lazy var target: XCUIApplication? = {
        guard let id = ProcessInfo.processInfo.environment["GEKKO_TARGET_BUNDLE_ID"] else { return nil }
        return XCUIApplication(bundleIdentifier: id)
    }()

    func testServe() {
        continueAfterFailure = true
        let environment = ProcessInfo.processInfo.environment
        guard let port = environment["GEKKO_PORT"].flatMap({ UInt16($0) }), port > 0,
              let token = environment["GEKKO_TOKEN"], !token.isEmpty else {
            let message = "GEKKO_PORT and GEKKO_TOKEN are required"
            FileHandle.standardError.write(Data("gekko: \(message)\n".utf8))
            XCTFail(message)
            return
        }
        #if os(tvOS)
        // Starting this runner took the screen from the app, and a remote press goes to whatever has it: the app comes
        // back before the server answers. An app that is gone is not launched again, its agent would be lost.
        if environment["GEKKO_ACTIVATE_TARGET"] == "1", let target, target.state != .notRunning, target.state != .unknown {
            target.activate()
        }
        #endif
        let serving = expectation(description: "serve forever")
        let server = HttpCommandServer(port: port, token: token) { [self] command in
            var result: [String: Any] = [:]
            // XCUITest APIs must run on the test's main thread.
            DispatchQueue.main.sync { result = execute(command) }
            return result
        }
        server.start { message in
            DispatchQueue.main.async {
                XCTFail(message)
                serving.fulfill()
            }
        }
        wait(for: [serving], timeout: 60 * 60 * 6)
    }

    private func execute(_ command: [String: Any]) -> [String: Any] {
        guard let type = command["type"] as? String else { return error("Command has no type", ErrorKind.unsupported) }
        switch type {
        case "hello": return ["type": "hello", "protocol": Self.protocolVersion, "version": Self.version, "role": "runner", "commands": Self.servedCommands]
        case "metrics": return ["type": "metrics", "density": 1.0, "rtl": UIView.userInterfaceLayoutDirection(for: .unspecified) == .rightToLeft]
        case "tree": return ["type": "tree", "windows": [node(for: foremost().root)]]
        case "foreground": return ["type": "foreground", "appInForeground": false]
        case "waitIdle": return ok()
        case "screenshot": return error("Screenshot is handled by the host CLI", ErrorKind.unsupported)
        #if os(tvOS)
        case "tap", "doubleTap", "longPress", "tapAt", "longPressAt", "scroll", "swipe", "drag", "setOrientation":
            return error("An Apple TV has no touch and no rotation: move the focus with pressKey (up, down, left, right, select)", ErrorKind.unsupported)
        case "type":
            let text = command["text"] as? String ?? ""
            return withElement(command, alertOnlyWithoutText: false) { match in
                // Nothing is tapped: the remote already moved the focus to the field, which takes the keyboard input
                guard match.candidate.snapshot.hasFocus else {
                    return self.error("The element does not have the focus: move it there with the remote first", ErrorKind.rejected)
                }
                match.scope.app.typeText(text)
                return self.ok()
            }
        #else
        case "tap": return withElement(command, alertOnlyWithoutText: true) { match in
            self.touch(match, { $0.tap() }, { $0.tap() })
            return self.ok()
        }
        case "doubleTap": return withElement(command, alertOnlyWithoutText: true) { match in
            self.touch(match, { $0.doubleTap() }, { $0.doubleTap() })
            return self.ok()
        }
        case "longPress":
            let hold = seconds(command["holdMs"], fallback: 700)
            return withElement(command, alertOnlyWithoutText: true) { match in
                self.touch(match, { $0.press(forDuration: hold) }, { $0.press(forDuration: hold) })
                return self.ok()
            }
        case "type":
            let text = command["text"] as? String ?? ""
            return withElement(command, alertOnlyWithoutText: false) { match in
                self.touch(match, { $0.tap() }, { $0.tap() })
                match.scope.app.typeText(text)
                return self.ok()
            }
        case "tapAt": return tapAt(command)
        case "longPressAt": return longPressAt(command)
        case "scroll": return scroll(command)
        case "swipe": return swipe(command)
        case "drag": return drag(command)
        case "setOrientation":
            XCUIDevice.shared.orientation = (command["landscape"] as? Bool ?? false) ? .landscapeLeft : .portrait
            return ok()
        #endif
        case "typeFocused": return typeFocused(command)
        case "pressKey": return pressKey(command)
        default: return error("Unknown command \(type)", ErrorKind.unsupported)
        }
    }

    /// The release version; the build script writes it into the Info.plist of the test bundle (GekkoVersion).
    private static let version: String = {
        let value = Bundle(for: SystemRunnerTests.self).object(forInfoDictionaryKey: "GekkoVersion") as? String
        return value?.isEmpty == false ? value ?? "unknown" : "unknown"
    }()

    /// What owns the screen: an alert on SpringBoard, Safari's view service, Safari, then the target app. `root` is the
    /// element to read, `app` the application that receives keystrokes and the origin of screen coordinates.
    private struct Scope {
        let root: XCUIElement
        let app: XCUIApplication
    }

    private func foremost() -> Scope {
        let alert = springboard.alerts.firstMatch
        if alert.exists { return Scope(root: alert, app: springboard) }
        #if !os(tvOS)
        for id in ["com.apple.SafariViewService", "com.apple.mobilesafari"] {
            let app = XCUIApplication(bundleIdentifier: id)
            if app.state == .runningForeground { return Scope(root: app, app: app) }
        }
        #endif
        // An app that is not in front (the home button was pressed) is not what is on the screen: the home screen is
        if let target, target.state != .runningForeground { return Scope(root: springboard, app: springboard) }
        let app = target ?? springboard
        return Scope(root: app, app: app)
    }

    /// A node of the tree with what a selector needs to know about its surroundings.
    private struct Candidate: SelectorNode {
        let snapshot: XCUIElementSnapshot
        let ancestors: [String]
        /// Below a tab bar or a segmented control, where every button can be selected.
        let inSelectionGroup: Bool
        /// The nearest scrollable area around the node (or the node itself).
        let scrollFrame: CGRect?

        var selectorId: String? { return snapshot.identifier.isEmpty ? nil : snapshot.identifier }
        var selectorTexts: [String] { return SystemRunnerTests.texts(of: snapshot) }
        var selectorActions: [String] { return SystemRunnerTests.actions(for: snapshot) }
        var selectorEnabled: Bool { return snapshot.isEnabled }
        var selectorChecked: Bool? { return SystemRunnerTests.checked(snapshot) }
        var selectorSelected: Bool? { return SystemRunnerTests.selected(snapshot, inSelectionGroup: inSelectionGroup) }
        func hasAncestor(withId id: String) -> Bool { return ancestors.contains(id) }
    }

    /// A match, ready to be touched at its center.
    private struct Match {
        let candidate: Candidate
        let scope: Scope
        let rootFrame: CGRect

        #if !os(tvOS)
        func coordinate() -> XCUICoordinate {
            return point(CGPoint(x: candidate.snapshot.frame.midX, y: candidate.snapshot.frame.midY))
        }

        /// Screen position as an offset from the origin of the root element the snapshot was taken from.
        func point(_ screenPoint: CGPoint) -> XCUICoordinate {
            let origin = scope.root.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
            return origin.withOffset(CGVector(dx: screenPoint.x - rootFrame.minX, dy: screenPoint.y - rootFrame.minY))
        }
        #endif
    }

    #if !os(tvOS)
    /// Touches the element the way XCUIElement does, at its activation point (the switch of a SwiftUI toggle row, not the
    /// middle of the row). An element that cannot be told apart from others, or is covered, gets the touch at the center
    /// of its frame instead.
    private func touch(_ match: Match, _ onElement: (XCUIElement) -> Void, _ onPoint: (XCUICoordinate) -> Void) {
        if let element = resolve(match), element.isHittable {
            onElement(element)
        } else {
            onPoint(match.coordinate())
        }
    }

    /// The live element behind a snapshot node: same type, identifier and label, and the same frame.
    private func resolve(_ match: Match) -> XCUIElement? {
        let snapshot = match.candidate.snapshot
        var predicates: [NSPredicate] = []
        if !snapshot.identifier.isEmpty { predicates.append(NSPredicate(format: "identifier == %@", snapshot.identifier)) }
        if !snapshot.label.isEmpty { predicates.append(NSPredicate(format: "label == %@", snapshot.label)) }
        guard !predicates.isEmpty else { return nil }
        let query = match.scope.root.descendants(matching: snapshot.elementType).matching(NSCompoundPredicate(andPredicateWithSubpredicates: predicates))
        guard query.count > 0, query.count <= Self.maxResolved else { return nil }
        return query.allElementsBoundByIndex.first { abs($0.frame.midX - snapshot.frame.midX) < 1 && abs($0.frame.midY - snapshot.frame.midY) < 1 }
    }
    #endif

    private func candidates(_ snapshot: XCUIElementSnapshot, ancestors: [String] = [], inSelectionGroup: Bool = false, scrollFrame: CGRect? = nil) -> [Candidate] {
        let scrolls = [.scrollView, .table, .collectionView, .webView].contains(snapshot.elementType)
        let area = scrolls ? snapshot.frame : scrollFrame
        let node = Candidate(snapshot: snapshot, ancestors: ancestors, inSelectionGroup: inSelectionGroup, scrollFrame: area)
        let below = snapshot.identifier.isEmpty ? ancestors : ancestors + [snapshot.identifier]
        let group = inSelectionGroup || [.tabBar, .segmentedControl].contains(snapshot.elementType)
        return [node] + snapshot.children.flatMap { candidates($0, ancestors: below, inSelectionGroup: group, scrollFrame: area) }
    }

    /// Looks for the element (matching, enabled, on screen, in document order) until it shows up or the time is over.
    /// A permission alert shows up a moment after the app asked for it, so the foremost app is looked at again while
    /// waiting. A selector that only names an action ("the Nth button") taps a button of a SpringBoard alert, never of
    /// the app under test.
    private func withElement(_ command: [String: Any], alertOnlyWithoutText: Bool, _ action: (Match) -> [String: Any]) -> [String: Any] {
        let selector: ElementSelector
        do {
            selector = try ElementSelector.parse(command["selector"])
        } catch let problem as SelectorError {
            return self.error(problem.message, ErrorKind.unsupported)
        } catch {
            return self.error("Selector could not be read", ErrorKind.unsupported)
        }
        let alertOnly = alertOnlyWithoutText && selector.id == nil && selector.text == nil && selector.textContains == nil && selector.textMatches == nil
        let deadline = Date().addingTimeInterval(seconds(command["timeoutMs"]))
        var miss = ErrorKind.notFound
        var detail = ""
        var sawAlert = false
        repeat {
            let scope: Scope
            if alertOnly {
                let alert = springboard.alerts.firstMatch
                guard alert.exists else {
                    Thread.sleep(forTimeInterval: Self.pollSeconds)
                    continue
                }
                sawAlert = true
                scope = Scope(root: alert, app: springboard)
            } else {
                scope = foremost()
            }
            // One snapshot is matched in memory, so every field of the selector means what it means in the tree
            guard let snapshot = try? scope.root.snapshot() else {
                Thread.sleep(forTimeInterval: Self.pollSeconds)
                continue
            }
            let rootFrame = snapshot.frame
            let matching = candidates(snapshot).filter { selector.matches($0) }
            // Disabled nodes only count when the selector asks for them
            let enabled = selector.enabled == nil ? matching.filter { $0.snapshot.isEnabled } : matching
            let visible = enabled.filter { rootFrame.contains(CGPoint(x: $0.snapshot.frame.midX, y: $0.snapshot.frame.midY)) }
            if selector.index < visible.count {
                return action(Match(candidate: visible[selector.index], scope: scope, rootFrame: rootFrame))
            }
            if !visible.isEmpty {
                detail = " (index \(selector.index), \(visible.count) on screen)"
            } else if !enabled.isEmpty {
                miss = ErrorKind.notOnScreen
            } else if !matching.isEmpty {
                miss = ErrorKind.disabled
            }
            Thread.sleep(forTimeInterval: Self.pollSeconds)
        } while Date() < deadline
        if alertOnly && !sawAlert { return error("No system alert", ErrorKind.notFound) }
        let what = miss == ErrorKind.notOnScreen ? "Element is not on screen" : (miss == ErrorKind.disabled ? "Element is disabled" : "Element not found")
        return error("\(what): \(selector)\(detail)", miss)
    }

    #if !os(tvOS)
    /// A touch at screen points, counted from the origin of the foremost application.
    private func tapAt(_ command: [String: Any]) -> [String: Any] {
        guard let x = finite(command["x"]), let y = finite(command["y"]), x >= 0, y >= 0 else {
            return error("tapAt needs non-negative numeric x and y", ErrorKind.unsupported)
        }
        let origin = foremost().app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
        origin.withOffset(CGVector(dx: x, dy: y)).tap()
        return ok()
    }
    #endif

    /// While a typeFocused runs, what XCTest would record as a failure is kept here instead; nil otherwise.
    private var typingIssues: [String]?

    /// XCTest records a failure when typeText finds nothing with the keyboard focus, and a failure ends this one
    /// long-running test: the focus is checked first, and one lost on the way is answered, not recorded.
    override func record(_ issue: XCTIssue) {
        if typingIssues != nil {
            typingIssues?.append(issue.compactDescription)
            return
        }
        super.record(issue)
    }

    /// Types at the cursor of the element that has the keyboard focus and sees whether its value changed.
    private func typeFocused(_ command: [String: Any]) -> [String: Any] {
        let text = command["text"] as? String ?? ""
        let scope = foremost()
        typingIssues = []
        defer { typingIssues = nil }
        guard let field = awaitFocusedElement(in: scope, timeout: seconds(command["timeoutMs"])) else {
            return error("Nothing has the input focus", ErrorKind.rejected)
        }
        if text.isEmpty { return ok() }
        let before = field.value as? String
        scope.app.typeText(text)
        if let lost = typingIssues?.first {
            return error("The input focus was lost before the text was typed: \(lost)", ErrorKind.rejected)
        }
        // A value that cannot be read cannot be checked: it got the text
        guard let before else { return ok() }
        let changeDeadline = Date().addingTimeInterval(Self.changeSeconds)
        repeat {
            if field.exists, field.value as? String != before { return ok() }
            Thread.sleep(forTimeInterval: 0.1)
        } while Date() < changeDeadline
        return error("The focused field did not take the text", ErrorKind.rejected)
    }

    #if os(tvOS)
    /// The buttons of the Siri Remote by the names of the protocol (lower case); back is the menu button of the remote.
    private static let remoteButtons: [String: XCUIRemote.Button] = [
        "up": .up, "down": .down, "left": .left, "right": .right, "select": .select, "playpause": .playPause,
        "menu": .menu, "back": .menu, "home": .home,
    ]
    /// The focus moves with an animation: the next press waits for it.
    private static let remoteSettleSeconds = 0.2
    #endif

    /// Presses a key as a keyboard does. The keys of a field wait for the keyboard focus as typeFocused does; home is the
    /// hardware button, and there is no back key on iOS. On an Apple TV the keys are the buttons of the remote.
    private func pressKey(_ command: [String: Any]) -> [String: Any] {
        guard let name = (command["key"] as? String)?.lowercased() else { return error("pressKey needs a key", ErrorKind.unsupported) }
        let times = max((command["times"] as? NSNumber)?.intValue ?? 1, 1)
        #if os(tvOS)
        if let button = Self.remoteButtons[name] {
            for index in 0 ..< times {
                if index > 0 { Thread.sleep(forTimeInterval: Self.remoteSettleSeconds) }
                XCUIRemote.shared.press(button)
            }
            return ok()
        }
        #endif
        let keys: [String: XCUIKeyboardKey] = ["enter": .return, "backspace": .delete, "tab": .tab]
        #if !os(tvOS)
        if name == "home" {
            for _ in 0 ..< times { XCUIDevice.shared.press(.home) }
            return ok()
        }
        #endif
        if name == "back" { return error("iOS has no back key", ErrorKind.unsupported) }
        guard let key = keys[name] else { return error("The system runner cannot press '\(name)'", ErrorKind.unsupported) }
        let scope = foremost()
        typingIssues = []
        defer { typingIssues = nil }
        guard awaitFocusedElement(in: scope, timeout: seconds(command["timeoutMs"])) != nil else {
            return error("Nothing has the input focus", ErrorKind.rejected)
        }
        scope.app.typeText(String(repeating: key.rawValue, count: times))
        if let lost = typingIssues?.first {
            return error("The input focus was lost before the key was pressed: \(lost)", ErrorKind.rejected)
        }
        return ok()
    }

    /// The element with the keyboard focus, once it has it: right after a tap the focus can still be on its way.
    private func awaitFocusedElement(in scope: Scope, timeout: TimeInterval) -> XCUIElement? {
        let deadline = Date().addingTimeInterval(timeout)
        var focused = focusedElement(in: scope)
        while focused == nil {
            if Date() > deadline { return nil }
            Thread.sleep(forTimeInterval: Self.pollSeconds)
            focused = focusedElement(in: scope)
        }
        return focused
    }

    /// The element with the keyboard focus; no snapshot field has it, so it takes a query of its own.
    private func focusedElement(in scope: Scope) -> XCUIElement? {
        let query = scope.app.descendants(matching: .any).matching(NSPredicate(format: "hasKeyboardFocus == true"))
        let element = query.firstMatch
        return element.exists ? element : nil
    }

    #if !os(tvOS)
    /// A touch at screen points, counted from the origin of the foremost application, held for holdMs.
    private func longPressAt(_ command: [String: Any]) -> [String: Any] {
        guard let x = finite(command["x"]), let y = finite(command["y"]), x >= 0, y >= 0 else {
            return error("longPressAt needs non-negative numeric x and y", ErrorKind.unsupported)
        }
        let origin = foremost().app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
        origin.withOffset(CGVector(dx: x, dy: y)).press(forDuration: seconds(command["holdMs"], fallback: 700))
        return ok()
    }

    /// Drags inside the scrollable area around the element, against the direction the content moves.
    private func scroll(_ command: [String: Any]) -> [String: Any] {
        var direction = "down"
        if let raw = command["direction"], !(raw is NSNull) {
            guard let value = raw as? String else { return error("Scroll direction must be a string", ErrorKind.unsupported) }
            direction = value
        }
        guard ["down", "up", "start", "end", "left", "right"].contains(direction) else {
            return error("Unknown scroll direction '\(direction)'", ErrorKind.unsupported)
        }
        var distance = 400.0
        if let raw = command["distanceDp"], !(raw is NSNull) {
            guard let value = finite(raw), value >= 0 else { return error("distanceDp must be a number of points", ErrorKind.unsupported) }
            distance = value
        }
        // The runner only serves system UI: its layout direction is the device's
        let rtl = UIView.userInterfaceLayoutDirection(for: .unspecified) == .rightToLeft
        return withElement(command, alertOnlyWithoutText: false) { match in
            let area = (match.candidate.scrollFrame ?? match.rootFrame).intersection(match.rootFrame)
            guard !area.isNull, area.width > 0, area.height > 0 else { return self.error("Nothing scrollable \(direction)", ErrorKind.rejected) }
            // Content direction as a step of the finger: it moves the opposite way
            let (dx, dy): (CGFloat, CGFloat)
            switch direction {
            case "down": (dx, dy) = (0, -1)
            case "up": (dx, dy) = (0, 1)
            case "right": (dx, dy) = (-1, 0)
            case "left": (dx, dy) = (1, 0)
            case "end": (dx, dy) = (rtl ? 1 : -1, 0)
            default: (dx, dy) = (rtl ? -1 : 1, 0)
            }
            let reach = dx == 0 ? area.height * 0.8 : area.width * 0.8
            let length = min(CGFloat(distance), reach)
            let center = CGPoint(x: area.midX, y: area.midY)
            let from = CGPoint(x: center.x - dx * length / 2, y: center.y - dy * length / 2)
            let to = CGPoint(x: center.x + dx * length / 2, y: center.y + dy * length / 2)
            self.drag(from: from, to: to) { match.point($0) }
            return self.ok()
        }
    }

    private func swipe(_ command: [String: Any]) -> [String: Any] {
        guard let from = points(command, "fromX", "fromY"), let to = points(command, "toX", "toY") else {
            return error("swipe needs numeric fromX, fromY, toX and toY", ErrorKind.unsupported)
        }
        let root = foremost().root
        guard root.frame.width > 0, root.frame.height > 0 else { return error("No window", ErrorKind.rejected) }
        // The finger takes durationMs from start to end: a short swipe flings the content on, a long one barely does
        let duration = max(seconds(command["durationMs"], fallback: 200), Self.minSwipeSeconds)
        let velocity = XCUIGestureVelocity(rawValue: hypot(to.x - from.x, to.y - from.y) / CGFloat(duration))
        screenPoint(root, from).press(forDuration: 0.05, thenDragTo: screenPoint(root, to), withVelocity: velocity, thenHoldForDuration: 0)
        return ok()
    }

    /// Moves the content under the start to the end and lets it rest there, so nothing flings on past it.
    private func drag(_ command: [String: Any]) -> [String: Any] {
        guard let from = points(command, "fromX", "fromY"), let to = points(command, "toX", "toY") else {
            return error("drag needs numeric fromX, fromY, toX and toY", ErrorKind.unsupported)
        }
        let root = foremost().root
        guard root.frame.width > 0, root.frame.height > 0 else { return error("No window", ErrorKind.rejected) }
        drag(from: from, to: to) { self.screenPoint(root, $0) }
        return ok()
    }

    /// The content only follows the finger after it moved the touch slop, so the finger goes that far beyond the end and
    /// rests there before it lifts. It moves at the slow speed of XCUITest: measured on the simulator (iOS 27), the
    /// content ends at the end to the point for 175, 350 and 525 points, while a drag at any other speed that was tried
    /// (a fixed time of 0.3 s, 100, 300 and 623 points per second) flung it on by up to 490 points for a distance over
    /// 100 points, whatever the rest before it lifted.
    private func drag(from: CGPoint, to: CGPoint, coordinate: (CGPoint) -> XCUICoordinate) {
        let dx = to.x - from.x
        let dy = to.y - from.y
        let length = hypot(dx, dy)
        let beyond = length == 0 ? CGPoint.zero : CGPoint(x: dx / length * Self.dragSlop, y: dy / length * Self.dragSlop)
        let end = CGPoint(x: to.x + beyond.x, y: to.y + beyond.y)
        coordinate(from).press(forDuration: 0.05, thenDragTo: coordinate(end), withVelocity: .slow, thenHoldForDuration: Self.dragRestSeconds)
    }

    /// Screen points, counted from the origin of the element that owns the screen.
    private func screenPoint(_ root: XCUIElement, _ point: CGPoint) -> XCUICoordinate {
        let origin = root.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
        return origin.withOffset(CGVector(dx: point.x - root.frame.minX, dy: point.y - root.frame.minY))
    }

    private func points(_ command: [String: Any], _ x: String, _ y: String) -> CGPoint? {
        guard let px = finite(command[x]), let py = finite(command[y]) else { return nil }
        return CGPoint(x: px, y: py)
    }

    /// How far a finger moves before a scroll view follows it. Measured on the simulator (iOS 27): the content of a
    /// scroll view trails the finger of a press-and-drag by 12 points.
    private static let dragSlop: CGFloat = 12
    private static let dragRestSeconds = 0.3
    private static let minSwipeSeconds = 0.05
    #endif

    private func node(for element: XCUIElement) -> [String: Any] {
        guard let snapshot = try? element.snapshot() else {
            return ["role": "Window", "enabled": true, "secure": false, "actions": [], "children": [], "bounds": bounds(.zero)]
        }
        return node(from: snapshot, inSelectionGroup: false)
    }

    private func node(from snapshot: XCUIElementSnapshot, inSelectionGroup: Bool) -> [String: Any] {
        let secure = snapshot.elementType == .secureTextField
        var json: [String: Any] = [
            "role": roleName(snapshot.elementType),
            "enabled": snapshot.isEnabled,
            "secure": secure,
            "actions": Self.actions(for: snapshot),
            "bounds": bounds(snapshot.frame),
            "children": snapshot.children.map { node(from: $0, inSelectionGroup: inSelectionGroup || [.tabBar, .segmentedControl].contains(snapshot.elementType)) },
        ]
        if !snapshot.identifier.isEmpty { json["id"] = snapshot.identifier }
        // A password field's value is never sent
        if !secure {
            let text = !snapshot.label.isEmpty ? snapshot.label : (snapshot.value as? String ?? "")
            if !text.isEmpty { json["text"] = text }
        }
        if let checked = Self.checked(snapshot) { json["checked"] = checked }
        if let selected = Self.selected(snapshot, inSelectionGroup: inSelectionGroup) { json["selected"] = selected }
        // The element the remote moved the focus to; on iOS a query for the focus is slow, so the tree leaves it out
        #if os(tvOS)
        if snapshot.hasFocus { json["focused"] = true }
        #endif
        return json
    }

    private func roleName(_ type: XCUIElement.ElementType) -> String {
        switch type {
        case .application: return "Application"
        case .window: return "Window"
        case .alert: return "Alert"
        case .button: return "Button"
        case .staticText: return "Text"
        case .textField: return "TextField"
        case .secureTextField: return "SecureTextField"
        case .searchField: return "SearchField"
        case .textView: return "TextView"
        case .image: return "Image"
        case .link: return "Link"
        case .cell: return "Cell"
        case .switch: return "Switch"
        case .scrollView: return "ScrollView"
        case .table: return "Table"
        case .collectionView: return "CollectionView"
        case .webView: return "WebView"
        case .navigationBar: return "NavigationBar"
        case .tabBar: return "TabBar"
        case .other: return "View"
        default: return "Element\(type.rawValue)"
        }
    }

    /// Label, title and value: what the old text selectors looked at. A password field has none.
    fileprivate static func texts(of snapshot: XCUIElementSnapshot) -> [String] {
        if snapshot.elementType == .secureTextField { return [] }
        return [snapshot.label, snapshot.title, snapshot.value as? String ?? ""].filter { !$0.isEmpty }
    }

    fileprivate static func actions(for snapshot: XCUIElementSnapshot) -> [String] {
        switch snapshot.elementType {
        case .button, .link, .cell, .switch, .menuItem, .tab: return ["click"]
        case .textField, .secureTextField, .searchField, .textView: return ["click", "setText"]
        case .scrollView, .table, .collectionView: return ["scroll"]
        default: return []
        }
    }

    /// State of a switch, checkbox or radio button; nil for everything that cannot be checked.
    fileprivate static func checked(_ snapshot: XCUIElementSnapshot) -> Bool? {
        guard [.switch, .toggle, .checkBox, .radioButton].contains(snapshot.elementType) else { return nil }
        if let number = snapshot.value as? NSNumber { return number.boolValue }
        let value = (snapshot.value as? String)?.lowercased()
        return value == "1" || value == "on" || value == "true"
    }

    /// State of a tab, segment or cell; nil for everything that cannot be selected.
    fileprivate static func selected(_ snapshot: XCUIElementSnapshot, inSelectionGroup: Bool) -> Bool? {
        if snapshot.isSelected { return true }
        let selectable = [.tab, .cell, .radioButton].contains(snapshot.elementType) || (snapshot.elementType == .button && inSelectionGroup)
        return selectable ? false : nil
    }

    private func bounds(_ frame: CGRect) -> [String: Int] {
        return ["left": integer(frame.minX), "top": integer(frame.minY), "right": integer(frame.maxX), "bottom": integer(frame.maxY)]
    }

    /// Int(_:) traps on NaN and infinity.
    private func integer(_ value: CGFloat) -> Int {
        guard value.isFinite else { return 0 }
        return Int(max(-1e9, min(1e9, value)))
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

    private func ok() -> [String: Any] {
        return ["type": "ok"]
    }

    private func error(_ message: String, _ kind: String) -> [String: Any] {
        return ["type": "error", "message": message, "kind": kind]
    }
}
