import Foundation

/// Error kinds of the protocol (ErrorKind); the CLI reads them, the strings must match.
enum ErrorKind {
    static let notFound = "notFound"
    static let notOnScreen = "notOnScreen"
    static let disabled = "disabled"
    static let rejected = "rejected"
    static let unsupported = "unsupported"
    static let timeout = "timeout"
}

struct SelectorError: Error {
    let message: String
}

/// What a selector looks at on a node of the tree; the agent (views and accessibility elements) and the runner
/// (XCUITest snapshots) each provide it.
protocol SelectorNode {
    var selectorId: String? { get }
    /// Label, value and the like; empty for a node without readable text (a password field).
    var selectorTexts: [String] { get }
    var selectorActions: [String] { get }
    var selectorEnabled: Bool { get }
    var selectorChecked: Bool? { get }
    var selectorSelected: Bool? { get }
    func hasAncestor(withId id: String) -> Bool
}

/// The protocol's Selector as the agents match it: same rules as Selector.matches in the shared protocol. The agent
/// and the runner each carry a copy (separate modules): keep both files identical.
struct ElementSelector: CustomStringConvertible {
    private static let maxIndex = 10_000

    let id: String?
    let text: String?
    let textContains: String?
    let textMatches: String?
    let action: String?
    let within: String?
    let index: Int
    let enabled: Bool?
    let checked: Bool?
    let selected: Bool?
    private let regex: NSRegularExpression?

    static func parse(_ json: Any?) throws -> ElementSelector {
        guard let fields = json as? [String: Any] else { throw SelectorError(message: "Selector is missing") }
        let pattern = try string(fields, "textMatches")
        let selector = ElementSelector(
            id: try string(fields, "id"),
            text: try string(fields, "text"),
            textContains: try string(fields, "textContains"),
            textMatches: pattern,
            action: try string(fields, "action"),
            within: try string(fields, "within"),
            index: try index(fields),
            enabled: try bool(fields, "enabled"),
            checked: try bool(fields, "checked"),
            selected: try bool(fields, "selected"),
            // The whole text matches (case-insensitive, `.` also matches line breaks); an invalid pattern only equals itself
            regex: pattern.flatMap { try? NSRegularExpression(pattern: "\\A(?:\($0))\\z", options: [.caseInsensitive, .dotMatchesLineSeparators]) }
        )
        if selector.id == nil && selector.text == nil && selector.textContains == nil && selector.textMatches == nil && selector.action == nil {
            throw SelectorError(message: "Selector needs id, text, textContains, textMatches or action")
        }
        return selector
    }

    private static func string(_ fields: [String: Any], _ key: String) throws -> String? {
        guard let value = fields[key], !(value is NSNull) else { return nil }
        guard let text = value as? String else { throw SelectorError(message: "Selector field \(key) must be a string") }
        return text
    }

    private static func bool(_ fields: [String: Any], _ key: String) throws -> Bool? {
        guard let value = fields[key], !(value is NSNull) else { return nil }
        guard let flag = value as? Bool else { throw SelectorError(message: "Selector field \(key) must be a boolean") }
        return flag
    }

    private static func index(_ fields: [String: Any]) throws -> Int {
        guard let value = fields["index"], !(value is NSNull) else { return 0 }
        guard let number = value as? NSNumber, number.doubleValue == number.doubleValue.rounded(),
              number.doubleValue >= 0, number.doubleValue <= Double(maxIndex) else {
            throw SelectorError(message: "Selector field index must be a whole number from 0 to \(maxIndex)")
        }
        return number.intValue
    }

    /// Every field; the enabled state only when the selector has one (callers default to enabled nodes) and the
    /// position on screen is left to the callers as well.
    func matches(_ node: some SelectorNode) -> Bool {
        if let id, node.selectorId != id { return false }
        if let action, !node.selectorActions.contains(action) { return false }
        if let enabled, node.selectorEnabled != enabled { return false }
        if let checked, node.selectorChecked != checked { return false }
        if let selected, node.selectorSelected != selected { return false }
        if let within, !node.hasAncestor(withId: within) { return false }
        let values = node.selectorTexts.map(Self.normalize)
        if let text, !values.contains(Self.normalize(text)) { return false }
        if let textContains, !values.contains(where: { $0.contains(Self.normalize(textContains)) }) { return false }
        if let textMatches, !values.contains(where: { matchesPattern($0, literal: Self.normalize(textMatches)) }) { return false }
        return true
    }

    private func matchesPattern(_ value: String, literal: String) -> Bool {
        if value == literal { return true }
        guard let regex else { return false }
        return regex.firstMatch(in: value, options: [], range: NSRange(value.startIndex..., in: value)) != nil
    }

    /// UI strings often contain non-breaking spaces ("20 Std."); tests are written with plain spaces.
    static func normalize(_ value: String) -> String {
        let mapped = value.unicodeScalars.map { CharacterSet.whitespacesAndNewlines.contains($0) || $0 == "\u{00A0}" || $0 == "\u{202F}" ? " " : String($0) }
        return mapped.joined().trimmingCharacters(in: .whitespaces)
    }

    var description: String {
        var parts: [String] = []
        if let id { parts.append("id=\(id)") }
        if let text { parts.append("text=\(text)") }
        if let textContains { parts.append("textContains=\(textContains)") }
        if let textMatches { parts.append("textMatches=\(textMatches)") }
        if let action { parts.append("action=\(action)") }
        if let within { parts.append("within=\(within)") }
        if index > 0 { parts.append("index=\(index)") }
        if let enabled { parts.append("enabled=\(enabled)") }
        if let checked { parts.append("checked=\(checked)") }
        if let selected { parts.append("selected=\(selected)") }
        return parts.joined(separator: " ")
    }
}
