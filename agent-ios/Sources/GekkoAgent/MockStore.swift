import Foundation

struct MockError: Error {
    let message: String
}

/// The network mocks of this app process: the rules set by setMocks (or the startup file), compiled once, and the log of
/// requests they answered. Same rules as MockRule and MockMatcher of the shared protocol: the last matching rule wins,
/// the URL regex has to match the whole URL, the body is only looked at for rules that ask for it.
final class MockStore {
    static let shared = MockStore()

    struct Rule {
        let id: String
        let regex: NSRegularExpression
        /// Upper case; nil matches every method.
        let method: String?
        let bodyContains: Data?
        let status: Int
        let headers: [String: String]
        let delay: TimeInterval
        let body: Data

        func matches(url: String) -> Bool {
            let range = NSRange(url.startIndex..., in: url)
            guard let match = regex.firstMatch(in: url, options: [.anchored], range: range) else { return false }
            // `$` would also accept a trailing line break
            return match.range == range
        }
    }

    private struct Hit {
        let id: String
        let method: String
        let url: String
    }

    private static let maxHits = 10_000
    private static let maxDelayMs = 600_000.0
    /// How much of a request body a rule looks at.
    private static let maxBodyBytes = 1024 * 1024

    private let lock = NSLock()
    private var rules: [Rule] = []
    private var hits: [Hit] = []

    /// Replaces all rules; an empty list switches mocking off. A rule that cannot be read rejects the whole list and
    /// leaves the current rules as they are.
    func setMocks(_ command: [String: Any]) throws {
        guard let entries = command["rules"] as? [Any] else { throw MockError(message: "setMocks needs a rules list") }
        var parsed: [Rule] = []
        for (index, entry) in entries.enumerated() {
            parsed.append(try Self.rule(entry, index: index))
        }
        MockURLProtocol.install()
        locked { rules = parsed }
    }

    /// Removes all rules and empties the hit log.
    func clear() {
        locked {
            rules = []
            hits = []
        }
    }

    /// The requests answered since the last call, oldest first; reading them empties the log.
    func takeHits() -> [[String: Any]] {
        let taken = locked { () -> [Hit] in
            let current = hits
            hits = []
            return current
        }
        return taken.map { ["id": $0.id, "method": $0.method, "url": $0.url] }
    }

    func record(_ rule: Rule, for request: URLRequest) {
        let hit = Hit(id: rule.id, method: (request.httpMethod ?? "GET").uppercased(), url: request.url?.absoluteString ?? "")
        locked {
            hits.append(hit)
            if hits.count > Self.maxHits { hits.removeFirst(hits.count - Self.maxHits) }
        }
    }

    /// The rule that answers the request: the last one that matches. The body is only read when a rule that matches
    /// method and URL needs it.
    func match(_ request: URLRequest) -> Rule? {
        let current = locked { rules }
        guard !current.isEmpty, let url = request.url?.absoluteString else { return nil }
        let method = request.httpMethod ?? "GET"
        var body: Data?
        var bodyRead = false
        for rule in current.reversed() {
            if let wanted = rule.method, wanted.caseInsensitiveCompare(method) != .orderedSame { continue }
            if !rule.matches(url: url) { continue }
            if let needle = rule.bodyContains {
                if !bodyRead {
                    body = Self.body(of: request)
                    bodyRead = true
                }
                guard let body, needle.isEmpty || body.range(of: needle) != nil else { continue }
            }
            return rule
        }
        return nil
    }

    /// The start of the request body; nil when there is none or when reading it would use it up for the real request.
    static func body(of request: URLRequest) -> Data? {
        if let body = request.httpBody { return Data(body.prefix(maxBodyBytes)) }
        // URLSession hands the body of an httpBody request over as a new stream on every access. A stream the app set
        // itself is the same object every time: reading it would leave the real request without body.
        let stream = request.httpBodyStream
        guard let stream, stream !== request.httpBodyStream else { return nil }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 16 * 1024)
        while data.count < maxBodyBytes {
            let count = stream.read(&buffer, maxLength: min(buffer.count, maxBodyBytes - data.count))
            if count <= 0 { break }
            data.append(buffer, count: count)
        }
        return data
    }

    @discardableResult
    private func locked<T>(_ block: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return block()
    }

    private static func rule(_ entry: Any, index: Int) throws -> Rule {
        guard let json = entry as? [String: Any] else { throw MockError(message: "Mock rule #\(index + 1) is not an object") }
        guard let id = json["id"] as? String, !id.isEmpty else { throw MockError(message: "Mock rule #\(index + 1) has no id") }
        let name = "Mock rule '\(id)'"
        guard let pattern = json["urlRegex"] as? String else { throw MockError(message: "\(name) has no urlRegex") }
        let regex: NSRegularExpression
        do {
            regex = try NSRegularExpression(pattern: pattern, options: [.dotMatchesLineSeparators])
        } catch {
            throw MockError(message: "\(name) has an invalid urlRegex: \(pattern)")
        }
        let method = try optionalString(json["method"], "method", name)?.uppercased()
        let bodyContains = try optionalString(json["bodyContains"], "bodyContains", name).map { Data($0.utf8) }
        let status = try integer(json["status"], "status", name, fallback: 200, in: 100...599)
        let delayMs = try integer(json["delayMs"], "delayMs", name, fallback: 0, in: 0...Int(maxDelayMs))
        var headers: [String: String] = [:]
        if let raw = json["headers"], !(raw is NSNull) {
            guard let object = raw as? [String: Any] else { throw MockError(message: "\(name): headers must be an object") }
            for (key, value) in object {
                guard let text = value as? String else { throw MockError(message: "\(name): header \(key) must be a string") }
                headers[key] = text
            }
        }
        var body = Data()
        if let encoded = try optionalString(json["bodyBase64"], "bodyBase64", name) {
            guard let decoded = Data(base64Encoded: encoded, options: [.ignoreUnknownCharacters]) else {
                throw MockError(message: "\(name): bodyBase64 is not base64")
            }
            body = decoded
        }
        return Rule(id: id, regex: regex, method: method, bodyContains: bodyContains, status: status, headers: headers, delay: TimeInterval(delayMs) / 1000, body: body)
    }

    private static func optionalString(_ value: Any?, _ field: String, _ name: String) throws -> String? {
        guard let value, !(value is NSNull) else { return nil }
        guard let text = value as? String else { throw MockError(message: "\(name): \(field) must be a string") }
        return text
    }

    private static func integer(_ value: Any?, _ field: String, _ name: String, fallback: Int, in range: ClosedRange<Int>) throws -> Int {
        guard let value, !(value is NSNull) else { return fallback }
        // Compared as Double first: Int(1e30) would trap
        guard let number = value as? NSNumber, number.doubleValue == number.doubleValue.rounded(),
              number.doubleValue >= Double(range.lowerBound), number.doubleValue <= Double(range.upperBound) else {
            throw MockError(message: "\(name): \(field) must be a whole number from \(range.lowerBound) to \(range.upperBound)")
        }
        return Int(number.doubleValue)
    }
}
