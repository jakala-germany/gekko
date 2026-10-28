import UIKit

final class Element {
    let object: NSObject
    let id: String?
    let text: String?
    let role: String
    let frame: CGRect
    let actions: [String]
    let enabled: Bool
    let checked: Bool?
    let selected: Bool?
    let secure: Bool
    let busy: Bool
    var children: [Element] = []
    weak var parent: Element?

    init(object: NSObject, id: String?, text: String?, role: String, frame: CGRect, actions: [String], enabled: Bool, checked: Bool? = nil, selected: Bool? = nil, secure: Bool = false, busy: Bool = false) {
        self.object = object
        self.id = id
        self.text = text
        self.role = role
        self.frame = frame
        self.actions = actions
        self.enabled = enabled
        self.checked = checked
        self.selected = selected
        self.secure = secure
        self.busy = busy
    }

    func toJson() -> [String: Any] {
        var json: [String: Any] = [
            "role": role,
            "bounds": [
                "left": Element.integer(frame.minX.rounded()),
                "top": Element.integer(frame.minY.rounded()),
                "right": Element.integer(frame.maxX.rounded()),
                "bottom": Element.integer(frame.maxY.rounded()),
            ],
            "actions": actions,
            "enabled": enabled,
            "secure": secure,
            "busy": busy,
            "children": children.map { $0.toJson() },
        ]
        if let id { json["id"] = id }
        if let text { json["text"] = text }
        if let checked { json["checked"] = checked }
        if let selected { json["selected"] = selected }
        return json
    }

    func flatten() -> [Element] {
        return [self] + children.flatMap { $0.flatten() }
    }

    /// Int(_:) traps on NaN and infinity, and an accessibility frame can be CGRect.null.
    static func integer(_ value: CGFloat) -> Int {
        guard value.isFinite else { return 0 }
        return Int(max(-1e9, min(1e9, value)))
    }
}

extension Element: SelectorNode {
    var selectorId: String? { return id }
    var selectorTexts: [String] { return text.map { [$0] } ?? [] }
    var selectorActions: [String] { return actions }
    var selectorEnabled: Bool { return enabled }
    var selectorChecked: Bool? { return checked }
    var selectorSelected: Bool? { return selected }

    func hasAncestor(withId id: String) -> Bool {
        var current = parent
        while let candidate = current {
            if candidate.id == id { return true }
            current = candidate.parent
        }
        return false
    }
}
