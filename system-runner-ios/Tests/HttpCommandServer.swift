import CryptoKit
import Foundation
import Network

/// Minimal HTTP/1.1 keep-alive server on 127.0.0.1: POST body = JSON command, response body = JSON response.
///
/// Both directions are authenticated with HMAC-SHA256 over the per-user token (see Protocol in the shared protocol):
/// a request carries a nonce and the MAC of its endpoint (the port), the session of this server, the nonce and the
/// body; an answer carries the MAC of the same nonce, the status and its body. The token itself never goes over the
/// connection, so a program that listens on the port first learns nothing, and a request that is not signed with the
/// token is answered and closed without anything being executed. The session is picked at start and told in the answer
/// to `hello`; any other command has to name it, so a recorded request is worthless once the server restarted.
/// The agent and the runner each carry a copy (separate modules): keep both files identical.
final class HttpCommandServer {
    private static let nonceHeader = "x-gekko-nonce"
    private static let sessionHeader = "x-gekko-session"
    private static let authHeader = "x-gekko-auth"
    private static let maxHeaderBytes = 16 * 1024
    private static let maxBodyBytes = 4 * 1024 * 1024
    private static let maxNonceCharacters = 128

    private let port: UInt16
    private let key: SymmetricKey
    private let session = HttpCommandServer.randomHex(16)
    private let handler: ([String: Any]) -> [String: Any]
    private let queue = DispatchQueue(label: "gekko.server", attributes: .concurrent)
    private var listener: NWListener?

    private var endpoint: String { String(port) }

    init(port: UInt16, token: String, handler: @escaping ([String: Any]) -> [String: Any]) {
        self.port = port
        self.key = SymmetricKey(data: Data(token.utf8))
        self.handler = handler
    }

    /// A bind failure is written to stderr, the listener is closed (so no Hello is ever answered) and reported to
    /// `onFailure`.
    func start(onFailure: @escaping (String) -> Void = { _ in }) {
        guard let endpointPort = NWEndpoint.Port(rawValue: port) else {
            report("invalid port \(port)", onFailure)
            return
        }
        let parameters = NWParameters.tcp
        parameters.requiredInterfaceType = .loopback
        parameters.requiredLocalEndpoint = NWEndpoint.hostPort(host: .ipv4(.loopback), port: endpointPort)
        let listener: NWListener
        do {
            listener = try NWListener(using: parameters)
        } catch {
            report("cannot listen on 127.0.0.1:\(port): \(error)", onFailure)
            return
        }
        listener.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed(let error), .waiting(let error):
                listener.cancel()
                self?.listener = nil
                self?.report("cannot listen on 127.0.0.1:\(self?.port ?? 0): \(error)", onFailure)
            default:
                break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            self?.accept(connection)
        }
        listener.start(queue: queue)
        self.listener = listener
    }

    private func report(_ message: String, _ onFailure: (String) -> Void) {
        let text = "gekko: \(message)"
        FileHandle.standardError.write(Data((text + "\n").utf8))
        onFailure(text)
    }

    private func accept(_ connection: NWConnection) {
        connection.start(queue: queue)
        receive(connection, buffer: Data())
    }

    private func receive(_ connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 1 << 16) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            var buffer = buffer
            if let data { buffer.append(data) }
            var keepOpen = true
            // The peer closed its side: answer what it sent, then close ours
            let closing = isComplete || error != nil
            parsing: while true {
                switch self.parse(&buffer) {
                case .incomplete:
                    break parsing
                case .request(let body, let keepAlive, let nonce, let requestSession):
                    let (status, json) = self.respond(to: body, in: requestSession)
                    if keepAlive && !closing {
                        connection.send(content: self.http(status, json, keepAlive: true, signedFor: nonce), completion: .contentProcessed { _ in })
                    } else {
                        self.sendFinal(connection, self.http(status, json, keepAlive: false, signedFor: nonce))
                        keepOpen = false
                        break parsing
                    }
                case .reject(let status, let message):
                    self.sendFinal(connection, self.http(status, Self.errorJson(message), keepAlive: false, signedFor: nil))
                    keepOpen = false
                    break parsing
                }
            }
            if !keepOpen { return }
            if closing {
                connection.cancel()
            } else {
                self.receive(connection, buffer: buffer)
            }
        }
    }

    private func sendFinal(_ connection: NWConnection, _ response: Data) {
        connection.send(content: response, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { _ in
            connection.cancel()
        })
    }

    private enum Parsed {
        case incomplete
        case request(body: Data, keepAlive: Bool, nonce: String, session: String)
        case reject(status: Int, message: String)
    }

    /// Takes one complete request off the buffer. Host and Origin are checked before anything else is looked at, the
    /// signature as soon as the body is there: nothing is decoded, let alone executed, before it is right.
    private func parse(_ buffer: inout Data) -> Parsed {
        let separator = Data("\r\n\r\n".utf8)
        guard let range = buffer.range(of: separator) else {
            return buffer.count > Self.maxHeaderBytes ? .reject(status: 431, message: "Header section too large") : .incomplete
        }
        let headerLength = buffer.distance(from: buffer.startIndex, to: range.lowerBound)
        if headerLength > Self.maxHeaderBytes { return .reject(status: 431, message: "Header section too large") }
        let text = String(decoding: buffer[buffer.startIndex..<range.lowerBound], as: UTF8.self)
        guard let head = RequestHead(text) else { return .reject(status: 400, message: "Malformed request") }

        guard let hosts = head.headers["host"], hosts.count == 1 else { return .reject(status: 400, message: "Exactly one Host header is required") }
        guard Self.isLoopbackHost(hosts[0]) else { return .reject(status: 403, message: "Host not allowed") }
        if head.headers["origin"] != nil { return .reject(status: 403, message: "Origin not allowed") }

        guard head.method == "POST" else { return .reject(status: 405, message: "Only POST is served") }
        if head.headers["transfer-encoding"] != nil { return .reject(status: 400, message: "Transfer-Encoding is not supported") }
        guard let lengths = head.headers["content-length"], lengths.count == 1, let length = Self.contentLength(lengths[0]) else {
            return .reject(status: 400, message: "A numeric Content-Length is required")
        }
        if length > Self.maxBodyBytes { return .reject(status: 413, message: "Body too large") }

        let bodyStart = range.upperBound
        guard buffer.distance(from: bodyStart, to: buffer.endIndex) >= length else { return .incomplete }
        let bodyEnd = buffer.index(bodyStart, offsetBy: length)
        let body = Data(buffer[bodyStart..<bodyEnd])
        buffer.removeSubrange(buffer.startIndex..<bodyEnd)

        guard let nonces = head.headers[Self.nonceHeader], nonces.count == 1, !nonces[0].isEmpty, nonces[0].count <= Self.maxNonceCharacters,
              let macs = head.headers[Self.authHeader], macs.count == 1 else {
            return .reject(status: 401, message: "The request is not signed")
        }
        let requestSession = head.headers[Self.sessionHeader].flatMap { $0.count == 1 ? $0[0] : nil } ?? ""
        let message = Self.requestMessage(endpoint: endpoint, session: requestSession, nonce: nonces[0], body: body)
        guard Self.constantTimeEquals(Array(Self.hmacHex(key, message).utf8), Array(macs[0].utf8)) else {
            return .reject(status: 401, message: "The request is not signed with the key of this server")
        }
        let keepAlive = !(head.headers["connection"]?.contains { $0.lowercased() == "close" } ?? false)
        return .request(body: body, keepAlive: keepAlive, nonce: nonces[0], session: requestSession)
    }

    /// "gekko-req\n" endpoint "\n" session "\n" nonce "\n" body; keep in sync with Protocol.requestMessage.
    static func requestMessage(endpoint: String, session: String, nonce: String, body: Data) -> Data {
        var message = Data("gekko-req\n\(endpoint)\n\(session)\n\(nonce)\n".utf8)
        message.append(body)
        return message
    }

    /// "gekko-res\n" endpoint "\n" session "\n" nonce "\n" status "\n" body; keep in sync with Protocol.responseMessage.
    static func responseMessage(endpoint: String, session: String, nonce: String, status: Int, body: Data) -> Data {
        var message = Data("gekko-res\n\(endpoint)\n\(session)\n\(nonce)\n\(status)\n".utf8)
        message.append(body)
        return message
    }

    /// HMAC-SHA256 as lowercase hex. Known answer: key "0123456789abcdef" x 4 and the request message for endpoint
    /// "20412", no session, nonce "00112233445566778899aabbccddeeff" and body {"type":"hello"} give
    /// 4ee5a8b1b169ea84d0a350b84270a987f8ff285a9087ce3bf17d75a0943631a0.
    static func hmacHex(_ key: SymmetricKey, _ message: Data) -> String {
        return HMAC<SHA256>.authenticationCode(for: message, using: key).map { String(format: "%02x", $0) }.joined()
    }

    private static func randomHex(_ byteCount: Int) -> String {
        var generator = SystemRandomNumberGenerator()
        return (0..<byteCount).map { _ in String(format: "%02x", UInt8.random(in: 0...255, using: &generator)) }.joined()
    }

    private struct RequestHead {
        let method: String
        var headers: [String: [String]] = [:]

        init?(_ text: String) {
            let lines = text.components(separatedBy: "\r\n")
            guard let requestLine = lines.first else { return nil }
            let parts = requestLine.split(separator: " ", omittingEmptySubsequences: false)
            guard parts.count == 3, parts[2].hasPrefix("HTTP/1.") else { return nil }
            method = String(parts[0])
            for line in lines.dropFirst() {
                guard let colon = line.firstIndex(of: ":"), colon != line.startIndex else { return nil }
                let name = line[line.startIndex..<colon]
                if name.contains(where: { $0 == " " || $0 == "\t" }) { return nil }
                let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
                headers[name.lowercased(), default: []].append(value)
            }
        }
    }

    /// Digits only: no sign, no spaces, nothing that Int() would accept by accident.
    private static func contentLength(_ value: String) -> Int? {
        guard !value.isEmpty, value.utf8.allSatisfy({ $0 >= 0x30 && $0 <= 0x39 }) else { return nil }
        return Int(value)
    }

    /// 127.0.0.1, localhost or [::1] with an optional port; anything else is a request meant for another site.
    static func isLoopbackHost(_ value: String) -> Bool {
        let host = value.lowercased()
        let name: Substring
        let rest: Substring
        if host.hasPrefix("[") {
            guard let close = host.firstIndex(of: "]") else { return false }
            name = host[host.startIndex...close]
            rest = host[host.index(after: close)...]
        } else if let colon = host.firstIndex(of: ":") {
            name = host[host.startIndex..<colon]
            rest = host[colon...]
        } else {
            name = host[host.startIndex...]
            rest = ""
        }
        guard name == "127.0.0.1" || name == "localhost" || name == "[::1]" else { return false }
        if rest.isEmpty { return true }
        guard rest.hasPrefix(":") else { return false }
        let digits = rest.dropFirst()
        return !digits.isEmpty && digits.count <= 5 && digits.utf8.allSatisfy { $0 >= 0x30 && $0 <= 0x39 }
    }

    /// Looks at every byte of the longer input, so the time does not tell how much of the token was right.
    static func constantTimeEquals(_ lhs: [UInt8], _ rhs: [UInt8]) -> Bool {
        var difference = lhs.count ^ rhs.count
        for index in 0..<max(lhs.count, rhs.count) {
            let left = index < lhs.count ? lhs[index] : 0
            let right = index < rhs.count ? rhs[index] : 0
            difference |= Int(left ^ right)
        }
        return difference == 0
    }

    /// Only `hello` may come without a session: it is how the client learns the one of this server.
    private func respond(to body: Data, in requestSession: String) -> (Int, [String: Any]) {
        guard let command = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] else {
            return (200, Self.errorJson("Invalid JSON"))
        }
        if (command["type"] as? String) != "hello" && requestSession != session {
            return (401, ["type": "error", "message": "The server was restarted since the last hello", "kind": "session"])
        }
        return (200, handler(command))
    }

    private static func errorJson(_ message: String) -> [String: Any] {
        return ["type": "error", "message": message, "kind": "unsupported"]
    }

    /// [signedFor] is the nonce of the request that is answered; without one the answer is not signed (a rejection).
    private func http(_ status: Int, _ result: [String: Any], keepAlive: Bool, signedFor nonce: String?) -> Data {
        let reasons = [200: "OK", 400: "Bad Request", 401: "Unauthorized", 403: "Forbidden", 405: "Method Not Allowed", 413: "Payload Too Large", 431: "Request Header Fields Too Large"]
        // JSONSerialization raises an Objective-C exception (not a Swift error) for values it cannot write
        let json = JSONSerialization.isValidJSONObject(result)
            ? ((try? JSONSerialization.data(withJSONObject: result)) ?? Data())
            : Data("{\"type\":\"error\",\"message\":\"Response could not be encoded\",\"kind\":\"unsupported\"}".utf8)
        var signature = ""
        if let nonce {
            let message = Self.responseMessage(endpoint: endpoint, session: session, nonce: nonce, status: status, body: json)
            signature = "X-Gekko-Session: \(session)\r\nX-Gekko-Auth: \(Self.hmacHex(key, message))\r\n"
        }
        let head = "HTTP/1.1 \(status) \(reasons[status] ?? "Error")\r\nContent-Type: application/json\r\nContent-Length: \(json.count)\r\nConnection: \(keepAlive ? "keep-alive" : "close")\r\n\(signature)\r\n"
        var response = Data(head.utf8)
        response.append(json)
        return response
    }
}
