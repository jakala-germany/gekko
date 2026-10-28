import UIKit

/// Semantics-first actions: controls and accessibility activation, scroll views set directly.
enum ElementActions {
    /// What a tap did: `handled` has its effect for sure (a toggled switch, a focused field), `sent` only dispatched
    /// something the app may ignore (the caller checks that the screen changed), `rejected` found nothing to operate.
    enum TapResult {
        case handled
        case sent
        case rejected
    }

    static func tap(_ element: Element) -> TapResult {
        let object = element.object
        if let toggle = object as? UISwitch, toggle.isEnabled {
            toggle.setOn(!toggle.isOn, animated: false)
            toggle.sendActions(for: .valueChanged)
            return .handled
        }
        if let (control, index) = segment(of: element) {
            control.selectedSegmentIndex = index
            control.sendActions(for: .valueChanged)
            return .handled
        }
        if let input = textInput(for: element), input.isFirstResponder || input.becomeFirstResponder() {
            return .handled
        }
        if let control = object as? UIControl, control.isEnabled {
            // A real touch ends with both; a button made from a UIAction only listens to the second one
            control.sendActions(for: .touchUpInside)
            control.sendActions(for: .primaryActionTriggered)
            return .sent
        }
        if object.accessibilityActivate() {
            return .sent
        }
        return selectCell(containing: element) ? .sent : .rejected
    }

    /// A touch at a point: the innermost enabled element there that can be operated.
    static func tap(at point: CGPoint, among elements: [Element]) -> TapResult {
        let candidates = elements.filter {
            $0.enabled && $0.frame.contains(point)
                && ($0.actions.contains("click") || $0.actions.contains("setText") || $0.object is UITableViewCell || $0.object is UICollectionViewCell)
        }
        guard let target = candidates.min(by: { $0.frame.width * $0.frame.height < $1.frame.width * $1.frame.height }) else { return .rejected }
        return tap(target)
    }

    private static func textInput(for element: Element) -> UIView? {
        if element.object is UITextField || element.object is UITextView { return element.object as? UIView }
        return element.actions.contains("setText") ? inputView(for: element) : nil
    }

    /// A segment is its own accessibility element inside the UISegmentedControl; a tap on it selects its index.
    private static func segment(of element: Element) -> (UISegmentedControl, Int)? {
        var control: UISegmentedControl?
        var node = element.parent
        while let candidate = node, control == nil {
            control = candidate.object as? UISegmentedControl
            node = candidate.parent
        }
        var view = (element.object as? UIView)?.superview
        while let candidate = view, control == nil {
            control = candidate as? UISegmentedControl
            view = candidate.superview
        }
        guard let control, control.numberOfSegments > 0, control.isEnabled else { return nil }
        if let title = element.text, let index = (0 ..< control.numberOfSegments).first(where: { control.titleForSegment(at: $0) == title }) {
            return (control, index)
        }
        guard let frame = screenFrame(control), frame.width > 0 else { return nil }
        let count = control.numberOfSegments
        let position = Int(max(0, min(CGFloat(count - 1), ((element.frame.midX - frame.minX) / frame.width * CGFloat(count)).rounded(.down))))
        return (control, control.effectiveUserInterfaceLayoutDirection == .rightToLeft ? count - 1 - position : position)
    }

    static func type(_ element: Element, text: String) -> Bool {
        if let picker = datePicker(for: element) {
            return setTime(of: picker, to: text)
        }
        guard let input = inputView(for: element) else { return false }
        _ = input.becomeFirstResponder()
        // Apps react to the focus change a moment later (select all, reset the search); type only after that settled
        spin(focusSettleSeconds)
        for _ in 0 ..< typeAttempts {
            if enter(text, into: input) { return true }
            spin(retrySeconds)
        }
        return false
    }

    /// Select-all + insertText goes through the same path as typing (delegate, editing events, bindings); verified by reading back.
    private static func enter(_ text: String, into input: UIView) -> Bool {
        if let field = input as? UITextField {
            field.selectAll(nil)
            field.insertText(text)
            spin(settleSeconds)
            if field.text != text {
                field.text = text
                field.sendActions(for: .editingChanged)
                NotificationCenter.default.post(name: UITextField.textDidChangeNotification, object: field)
                spin(settleSeconds)
            }
            return field.text == text
        }
        if let view = input as? UITextView {
            view.selectAll(nil)
            view.insertText(text)
            spin(settleSeconds)
            if view.text != text {
                view.text = text
                view.delegate?.textViewDidChange?(view)
                NotificationCenter.default.post(name: UITextView.textDidChangeNotification, object: view)
                spin(settleSeconds)
            }
            return view.text == text
        }
        return true
    }

    private static let focusSettleSeconds = 1.0
    private static let settleSeconds = 0.9
    private static let retrySeconds = 0.2
    private static let typeAttempts = 3

    private static func spin(_ seconds: TimeInterval) {
        RunLoop.current.run(until: Date(timeIntervalSinceNow: seconds))
    }

    static func isScrollDirection(_ value: String) -> Bool {
        return ["down", "up", "start", "end", "left", "right"].contains(value)
    }

    /// Scrolls the container of the element so that content in [direction] comes into view; false when nothing moved.
    static func scroll(_ element: Element, direction: String, distance: CGFloat) -> Bool {
        for scrollView in scrollViews(for: element) {
            let rtl = scrollView.effectiveUserInterfaceLayoutDirection == .rightToLeft
            // Content offset grows towards the content that is revealed; start and end follow the layout direction
            let (dx, dy): (CGFloat, CGFloat)
            switch direction {
            case "down": (dx, dy) = (0, distance)
            case "up": (dx, dy) = (0, -distance)
            case "right": (dx, dy) = (distance, 0)
            case "left": (dx, dy) = (-distance, 0)
            case "end": (dx, dy) = (rtl ? -distance : distance, 0)
            default: (dx, dy) = (rtl ? distance : -distance, 0)
            }
            if scrollBy(scrollView, dx: dx, dy: dy) { return true }
        }
        return false
    }

    /// The content follows the finger: swiping up (or left) reveals what is below (or to the right). Both axes count,
    /// the longer finger movement decides; false when no scroll view under the start point could move.
    static func swipe(from: CGPoint, to: CGPoint) -> Bool {
        let dx = from.x - to.x
        let dy = from.y - to.y
        let horizontal = abs(dx) > abs(dy)
        for scrollView in scrollViews(containing: from) {
            if scrollBy(scrollView, dx: horizontal ? dx : 0, dy: horizontal ? 0 : dy) { return true }
        }
        return false
    }

    /// Moves the offset along the axes with a non-zero delta (clamped to the content, whole pages for a pager);
    /// false when that changes nothing.
    private static func scrollBy(_ scrollView: UIScrollView, dx: CGFloat, dy: CGFloat) -> Bool {
        guard scrollView.isScrollEnabled else { return false }
        let inset = scrollView.adjustedContentInset
        let bounds = scrollView.bounds
        let size = scrollView.contentSize
        let current = scrollView.contentOffset
        func target(_ offset: CGFloat, _ delta: CGFloat, _ low: CGFloat, _ high: CGFloat, page: CGFloat) -> CGFloat {
            guard delta != 0 else { return offset }
            let step = scrollView.isPagingEnabled && page > 0 ? (delta < 0 ? -1 : 1) * page * max(1, (abs(delta) / page).rounded()) : delta
            return min(max(offset + step, low), max(low, high))
        }
        let next = CGPoint(
            x: target(current.x, dx, -inset.left, size.width - bounds.width + inset.right, page: bounds.width),
            y: target(current.y, dy, -inset.top, size.height - bounds.height + inset.bottom, page: bounds.height)
        )
        guard abs(next.x - current.x) >= 0.5 || abs(next.y - current.y) >= 0.5 else { return false }
        scrollView.setContentOffset(next, animated: false)
        return true
    }

    /// The scroll view that is the element, one around it in the tree, then every one under its center, innermost first.
    private static func scrollViews(for element: Element) -> [UIScrollView] {
        var result: [UIScrollView] = []
        var node: Element? = element
        while let candidate = node {
            if let scrollView = candidate.object as? UIScrollView { result.append(scrollView) }
            node = candidate.parent
        }
        for scrollView in scrollViews(containing: CGPoint(x: element.frame.midX, y: element.frame.midY)) where !result.contains(where: { $0 === scrollView }) {
            result.append(scrollView)
        }
        return result
    }

    private static func scrollViews(containing point: CGPoint) -> [UIScrollView] {
        return TreeReader.windows().reversed().flatMap { all(UIScrollView.self, containing: point, in: $0) }
    }

    private static func views(_ root: UIView) -> [UIView] {
        return [root] + root.subviews.flatMap { views($0) }
    }

    private static func screenFrame(_ view: UIView) -> CGRect? {
        guard let screen = view.window?.screen else { return nil }
        return view.convert(view.bounds, to: screen.coordinateSpace)
    }

    /// Views of a kind in a window whose screen frame contains the point, innermost first (SwiftUI wraps UIKit views we can't reach via the element).
    private static func all<T: UIView>(_ type: T.Type, containing point: CGPoint, in window: UIWindow) -> [T] {
        let candidates = views(window).compactMap { $0 as? T }.filter {
            guard let frame = screenFrame($0) else { return false }
            return frame.contains(point) && !$0.isHidden && $0.alpha >= 0.01
        }
        return candidates.sorted { (screenFrame($0)?.size.width ?? 0) * (screenFrame($0)?.size.height ?? 0) < (screenFrame($1)?.size.width ?? 0) * (screenFrame($1)?.size.height ?? 0) }
    }

    private static func smallest<T: UIView>(_ type: T.Type, containing point: CGPoint) -> T? {
        return TreeReader.windows().reversed().lazy.compactMap { all(type, containing: point, in: $0).first }.first
    }

    private static func datePicker(for element: Element) -> UIDatePicker? {
        if let picker = element.object as? UIDatePicker { return picker }
        return smallest(UIDatePicker.self, containing: CGPoint(x: element.frame.midX, y: element.frame.midY))
    }

    /// Sets hour and minute ("20:15") of a date picker and keeps its date; wheels cannot be typed into.
    private static func setTime(of picker: UIDatePicker, to value: String) -> Bool {
        let parts = value.split(separator: ":").compactMap { Int($0.trimmingCharacters(in: .whitespaces)) }
        guard parts.count == 2, (0..<24).contains(parts[0]), (0..<60).contains(parts[1]) else { return false }
        var calendar = picker.calendar ?? Calendar.current
        calendar.timeZone = picker.timeZone ?? calendar.timeZone
        guard let date = calendar.date(bySettingHour: parts[0], minute: parts[1], second: 0, of: picker.date) else { return false }
        picker.setDate(date, animated: false)
        picker.sendActions(for: .valueChanged)
        return true
    }

    private static func inputView(for element: Element) -> UIView? {
        if element.object is UITextField || element.object is UITextView { return element.object as? UIView }
        let point = CGPoint(x: element.frame.midX, y: element.frame.midY)
        return smallest(UITextField.self, containing: point) ?? smallest(UITextView.self, containing: point)
    }

    private static func selectCell(containing element: Element) -> Bool {
        guard let view = element.object as? UIView else { return false }
        var current: UIView? = view
        while let candidate = current {
            if let cell = candidate as? UITableViewCell, let table = cell.superview as? UITableView, let path = table.indexPath(for: cell) {
                table.selectRow(at: path, animated: false, scrollPosition: .none)
                table.delegate?.tableView?(table, didSelectRowAt: path)
                return true
            }
            if let cell = candidate as? UICollectionViewCell, let collection = cell.superview as? UICollectionView, let path = collection.indexPath(for: cell) {
                collection.delegate?.collectionView?(collection, didSelectItemAt: path)
                return true
            }
            current = candidate.superview
        }
        return false
    }
}
