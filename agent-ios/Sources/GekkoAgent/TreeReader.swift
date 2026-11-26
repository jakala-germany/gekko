import UIKit

/// Reads the app's UI through the accessibility tree plus the view hierarchy (UIKit and SwiftUI).
enum TreeReader {
    static func windows() -> [UIWindow] {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return scenes.flatMap { $0.windows }
            .filter { !$0.isHidden }
            .sorted { $0.windowLevel.rawValue < $1.windowLevel.rawValue }
    }

    static func read() -> [Element] {
        let secureFrames = secureInputFrames()
        let focus = treeFocus()
        let focusedFrame = treeFocusFrame(of: focus)
        return windows().compactMap { build($0, parent: nil, visited: [], secureFrames: secureFrames, focus: focus, focusedFrame: focusedFrame) }
    }

    /// The view the tree marks as focused. On a TV it is the one the remote moved the focus to; elsewhere the one a
    /// keyboard types into.
    private static func treeFocus() -> UIView? {
        #if os(tvOS)
        if let view = remoteFocusedView() { return view }
        #endif
        return focusedView()
    }

    /// Where the focus is on the screen: the frame of what [treeFocus] found; on a TV the frame of the focus item, which
    /// SwiftUI makes of its own (a responder that is no view) and so [treeFocus] cannot name.
    private static func treeFocusFrame(of focus: UIView?) -> CGRect? {
        #if os(tvOS)
        if let frame = remoteFocusedFrame() { return frame }
        #endif
        return focus.flatMap { screenFrame(of: $0) }
    }

    #if os(tvOS)
    /// The view the focus engine has focused, from the topmost window down: what the remote moves and select presses.
    static func remoteFocusedView() -> UIView? {
        for window in windows().reversed() {
            if let view = window.windowScene?.focusSystem?.focusedItem as? UIView { return view }
        }
        return nil
    }

    /// The screen frame of the focus item the focus engine has focused, whatever kind of item it is.
    private static func remoteFocusedFrame() -> CGRect? {
        for window in windows().reversed() {
            guard let item = window.windowScene?.focusSystem?.focusedItem else { continue }
            if let view = item as? UIView { return screenFrame(of: view) }
            // SwiftUI's items have no container: their frame is in the window
            let space = item.focusItemContainer?.coordinateSpace ?? window.coordinateSpace
            return space.convert(item.frame, to: window.screen.coordinateSpace)
        }
        return nil
    }
    #endif

    /// The view that has the input focus, from the topmost window down: what a keyboard types into. A text field is
    /// edited through a field editor (a scroll view) inside of it; the field is what a person calls focused.
    static func focusedView() -> UIView? {
        func find(_ view: UIView) -> UIView? {
            if view.isFirstResponder { return view }
            for subview in view.subviews {
                if let found = find(subview) { return found }
            }
            return nil
        }
        for window in windows().reversed() {
            if let found = find(window) { return found.superview as? UITextField ?? found }
        }
        return nil
    }

    private static func screenFrame(of view: UIView) -> CGRect? {
        guard let screen = view.window?.screen else { return nil }
        return view.convert(view.bounds, to: screen.coordinateSpace)
    }

    /// Changes whenever something a person could notice changes (ids, texts, positions, state).
    static func signature(_ elements: [Element]) -> Int {
        var hasher = Hasher()
        for element in elements {
            hasher.combine(element.id)
            hasher.combine(element.text)
            hasher.combine(Element.integer(element.frame.minX))
            hasher.combine(Element.integer(element.frame.minY))
            hasher.combine(Element.integer(element.frame.width))
            hasher.combine(Element.integer(element.frame.height))
            hasher.combine(element.enabled)
            hasher.combine(element.checked)
            hasher.combine(element.selected)
            hasher.combine(element.busy)
            hasher.combine(element.focused)
        }
        return hasher.finalize()
    }

    private static func build(_ object: NSObject, parent: Element?, visited: Set<ObjectIdentifier>, secureFrames: [CGRect], focus: UIView?, focusedFrame: CGRect?) -> Element? {
        let identity = ObjectIdentifier(object)
        if visited.contains(identity) { return nil }
        if let view = object as? UIView, view.isHidden || view.alpha < 0.01 || view.accessibilityElementsHidden {
            return nil
        }
        let area = frame(of: object)
        // A SwiftUI field is an accessibility node on top of the UITextField: it is secure when it covers a secure one
        let secure = isSecureInput(object) || secureFrames.contains { Self.covers($0, area) }
        // Same for the focus: the node on top of the focused UITextField has it as well
        let focused = (focus != nil && object === focus) || focusedFrame.map { Self.coversFocus($0, area) } == true
        let element = Element(
            object: object,
            id: identifier(of: object),
            text: secure ? nil : text(of: object),
            role: role(of: object, parent: parent),
            frame: area,
            actions: actions(of: object),
            enabled: !object.accessibilityTraits.contains(.notEnabled) && ((object as? UIControl)?.isEnabled ?? true),
            checked: checked(of: object),
            selected: selected(of: object, parent: parent),
            secure: secure,
            busy: isBusy(object),
            focused: focused
        )
        element.parent = parent
        let nextVisited = visited.union([identity])
        element.children = accessibleChildren(of: object)
            .compactMap { build($0, parent: element, visited: nextVisited, secureFrames: secureFrames, focus: focus, focusedFrame: focusedFrame) }
            .flatMap { child -> [Element] in
                guard repeats(child, parent: element) else { return [child] }
                child.children.forEach { $0.parent = element }
                return child.children
            }
        let isBoring = element.id == nil && element.text == nil && element.actions.isEmpty && element.children.isEmpty
        return isBoring ? nil : element
    }

    /// A child that only repeats its parent, and is one element with it for accessibility and for XCUITest: a row of a
    /// table is an element of UIKit's own with one more below it that speaks the same text (iOS 18 and later), a bar
    /// button a control around a button with the same label. The parent stays, as it is what VoiceOver activates; the
    /// child goes when it tells nothing the parent does not (the same id or none on both, no other state, inside its
    /// frame), and what is below it moves up. A parent with an id keeps a child without one: a selector can name the
    /// child `within` the parent, which never matches the parent itself.
    private static func repeats(_ child: Element, parent: Element) -> Bool {
        guard child.text != nil, child.text == parent.text, child.role == parent.role else { return false }
        guard child.id == parent.id else { return false }
        guard child.checked == nil || child.checked == parent.checked else { return false }
        guard child.selected == nil || child.selected == parent.selected else { return false }
        guard child.enabled == parent.enabled, child.secure == parent.secure else { return false }
        guard !child.focused || parent.focused, !child.busy || parent.busy else { return false }
        guard Set(child.actions).isSubset(of: parent.actions) else { return false }
        return parent.frame.insetBy(dx: -2, dy: -2).contains(child.frame)
    }

    private static func isSecureInput(_ object: NSObject) -> Bool {
        if let field = object as? UITextField { return field.isSecureTextEntry }
        if let view = object as? UITextView { return view.isSecureTextEntry }
        return false
    }

    private static func secureInputFrames() -> [CGRect] {
        var frames: [CGRect] = []
        func visit(_ view: UIView) {
            if isSecureInput(view), let screen = view.window?.screen {
                frames.append(view.convert(view.bounds, to: screen.coordinateSpace))
            }
            view.subviews.forEach(visit)
        }
        windows().forEach(visit)
        return frames
    }

    /// Whether [frame] is the node of the focused item. On a TV the focus effect scales the focused item about its
    /// center, so its frame differs in size from the one the accessibility node reports, and not in its center.
    private static func coversFocus(_ focus: CGRect, _ frame: CGRect) -> Bool {
        #if os(tvOS)
        guard focus.width > 0, focus.height > 0, frame.width > 0, frame.height > 0 else { return false }
        let ratioX = frame.width / focus.width
        let ratioY = frame.height / focus.height
        return abs(focus.midX - frame.midX) < 3 && abs(focus.midY - frame.midY) < 3
            && (0.7 ... 1.4).contains(ratioX) && (0.7 ... 1.4).contains(ratioY)
        #else
        return covers(focus, frame)
        #endif
    }

    private static func covers(_ secure: CGRect, _ frame: CGRect) -> Bool {
        return abs(secure.minX - frame.minX) < 2 && abs(secure.minY - frame.minY) < 2
            && abs(secure.maxX - frame.maxX) < 2 && abs(secure.maxY - frame.maxY) < 2
    }

    /// State of a switch or toggle; nil for everything that cannot be checked.
    private static func checked(of object: NSObject) -> Bool? {
        #if os(iOS)
        if let toggle = object as? UISwitch { return toggle.isOn }
        #endif
        if #available(iOS 17.0, tvOS 17.0, *), object.accessibilityTraits.contains(.toggleButton) {
            let value = object.accessibilityValue?.lowercased()
            return value == "1" || value == "on" || value == "true"
        }
        return nil
    }

    /// State of a tab, segment or cell; nil for everything that cannot be selected (an unselected tab has no trait).
    private static func selected(of object: NSObject, parent: Element?) -> Bool? {
        if object.accessibilityTraits.contains(.selected) { return true }
        if let cell = object as? UITableViewCell { return cell.isSelected }
        if let cell = object as? UICollectionViewCell { return cell.isSelected }
        guard object.accessibilityTraits.contains(.button) else { return nil }
        var container = parent
        for _ in 0 ..< 4 {
            if container?.object is UITabBar || container?.object is UISegmentedControl { return false }
            container = container?.parent
        }
        return nil
    }

    /// Progress indicators and anything that announces frequent updates: the screen is still working.
    private static func isBusy(_ object: NSObject) -> Bool {
        if let indicator = object as? UIActivityIndicatorView { return indicator.isAnimating }
        if let progress = object as? UIProgressView { return progress.progress < 1 }
        return object.accessibilityTraits.contains(.updatesFrequently)
    }

    private static func accessibleChildren(of object: NSObject) -> [NSObject] {
        if let elements = object.accessibilityElements as? [NSObject], !elements.isEmpty {
            return elements
        }
        let count = object.accessibilityElementCount()
        if count != NSNotFound && count > 0 {
            return (0..<count).compactMap { object.accessibilityElement(at: $0) as? NSObject }
        }
        return (object as? UIView)?.subviews ?? []
    }

    private static func identifier(of object: NSObject) -> String? {
        let selector = NSSelectorFromString("accessibilityIdentifier")
        guard object.responds(to: selector) else { return nil }
        let value = object.value(forKey: "accessibilityIdentifier") as? String
        return (value?.isEmpty ?? true) ? nil : value
    }

    private static func text(of object: NSObject) -> String? {
        let text: String?
        switch object {
        case let field as UITextField: text = field.text?.isEmpty == false ? field.text : field.placeholder
        case let view as UITextView: text = view.text
        case let label as UILabel: text = label.text
        case let button as UIButton: text = button.title(for: .normal) ?? object.accessibilityLabel
        default: text = object.accessibilityLabel ?? (object.accessibilityValue)
        }
        return (text?.isEmpty ?? true) ? nil : text
    }

    private static func frame(of object: NSObject) -> CGRect {
        if let view = object as? UIView, let screen = view.window?.screen {
            return view.convert(view.bounds, to: screen.coordinateSpace)
        }
        return object.accessibilityFrame
    }

    private static func actions(of object: NSObject) -> [String] {
        var actions: [String] = []
        let traits = object.accessibilityTraits
        if object is UIControl || traits.contains(.button) || traits.contains(.link) || traits.contains(.adjustable) {
            actions.append("click")
        }
        if object is UITextField || object is UITextView || traits.contains(.searchField) {
            actions.append("setText")
        }
        if object is UIScrollView {
            actions.append("scroll")
        }
        return actions
    }

    /// The name of what the element is: the name of its XCUITest type, so that the iOS system runner (see `roleName` in
    /// its tests) calls the same element by the same name. A text is `Text` (a heading as well: XCUITest has no type
    /// for it) and what is nothing more specific is `View`, never the name of a (private) class.
    private static func role(of object: NSObject, parent: Element?) -> String {
        let role = classRole(of: object) ?? traitRole(of: object.accessibilityTraits, parent: parent)
        // What a table or a collection holds is a cell, also where UIKit hands out a (private) accessibility element
        // instead of the cell itself, as a table of iOS 18 does
        if role == "View", parent?.role == "Table" || parent?.role == "CollectionView" { return "Cell" }
        return role
    }

    /// The role of a UIKit class; the most specific one first (a table view is a scroll view, a search field a text field).
    private static func classRole(of object: NSObject) -> String? {
        switch object {
        case is UIWindow: return "Window"
        case let field as UITextField: return fieldRole(of: field)
        case is UITextView: return "TextView"
        case is UINavigationBar: return "NavigationBar"
        case is UITabBar: return "TabBar"
        case is UITableViewCell, is UICollectionViewCell: return "Cell"
        case is UITableView: return "Table"
        case is UICollectionView: return "CollectionView"
        case is UIScrollView: return "ScrollView"
        case is UISegmentedControl: return "SegmentedControl"
        case is UIPageControl: return "PageIndicator"
        case is UIActivityIndicatorView: return "ActivityIndicator"
        case is UIProgressView: return "ProgressIndicator"
        case is UIButton: return "Button"
        case is UILabel: return "Text"
        case is UIImageView: return "Image"
        default: break
        }
        #if os(iOS)
        switch object {
        case is UISwitch: return "Switch"
        case is UISlider: return "Slider"
        case is UIStepper: return "Stepper"
        case is UIDatePicker: return "DatePicker"
        case is UIPickerView: return "Picker"
        case is UIToolbar: return "Toolbar"
        default: break
        }
        #endif
        // WebKit is not linked: the class is looked up by its name
        if let webView = NSClassFromString("WKWebView"), object.isKind(of: webView) { return "WebView" }
        // The bar at the edge of the screen on iOS 26 and later is a container of UIKit's own, not a UIToolbar, that
        // names itself `Toolbar`. There is no public class to ask; when its name changes, it is a `View` again.
        if NSStringFromClass(type(of: object)).hasSuffix("FloatingBarContainerView"), identifier(of: object) == "Toolbar" {
            return "Toolbar"
        }
        return nil
    }

    private static func fieldRole(of field: UITextField) -> String {
        if field.isSecureTextEntry { return "SecureTextField" }
        #if os(iOS)
        if field is UISearchTextField { return "SearchField" }
        #endif
        return field.accessibilityTraits.contains(.searchField) ? "SearchField" : "TextField"
    }

    /// The role of what the accessibility traits tell: a SwiftUI element or a custom view has no class of its own to name.
    private static func traitRole(of traits: UIAccessibilityTraits, parent: Element?) -> String {
        // A wheel of a picker is an element that can be adjusted, as a slider is
        if traits.contains(.adjustable), parent?.role == "Picker" { return "PickerWheel" }
        if traits.contains(.searchField) { return "SearchField" }
        // A toggle of SwiftUI is a button that can be on; XCUITest calls it a switch
        if #available(iOS 17.0, tvOS 17.0, *), traits.contains(.toggleButton) { return "Switch" }
        if traits.contains(.button) { return "Button" }
        if traits.contains(.link) { return "Link" }
        if traits.contains(.image) { return "Image" }
        if traits.contains(.tabBar) { return "TabBar" }
        if traits.contains(.staticText) || traits.contains(.header) { return "Text" }
        return "View"
    }
}
