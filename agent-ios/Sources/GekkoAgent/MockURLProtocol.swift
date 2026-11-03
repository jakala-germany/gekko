import Foundation
import ObjectiveC

/// Answers URLSession requests that match a rule of MockStore with the canned response; every other request is not
/// touched (canInit says no) and goes to the network. Installed once: registered for URLSession.shared and put in front
/// of protocolClasses of every default and ephemeral configuration created afterwards (Alamofire, Apollo and the like).
/// Not covered: WKWebView, Flutter (own network stack), background sessions, a configuration whose protocolClasses the
/// app replaces after creating it.
final class MockURLProtocol: URLProtocol {
    private static let installed: Void = {
        URLProtocol.registerClass(MockURLProtocol.self)
        prependToConfigurations(createdBy: "defaultSessionConfiguration")
        prependToConfigurations(createdBy: "ephemeralSessionConfiguration")
    }()

    /// Marks a request that this protocol handed on to the network itself: canInit says no to it.
    private static let passThroughKey = "com.jakala.gekko.passThrough"

    /// Carries the requests that no rule answers any more (see startLoading); created after the hook is in place, the
    /// marker keeps this protocol out of its way.
    private static let passThroughSession = URLSession(configuration: .default)

    private var delivery: DispatchWorkItem?
    private var passThroughTask: URLSessionDataTask?

    /// Does nothing after the first call.
    static func install() {
        _ = installed
    }

    /// Swaps the class method behind URLSessionConfiguration.default / .ephemeral for one that also puts this protocol
    /// in front of the protocolClasses of the new configuration.
    private static func prependToConfigurations(createdBy name: String) {
        let selector = NSSelectorFromString(name)
        guard let method = class_getClassMethod(URLSessionConfiguration.self, selector) else { return }
        typealias Original = @convention(c) (AnyObject, Selector) -> AnyObject
        let original = unsafeBitCast(method_getImplementation(method), to: Original.self)
        let replacement: @convention(block) (AnyObject) -> AnyObject = { receiver in
            let created = original(receiver, selector)
            if let configuration = created as? URLSessionConfiguration {
                var classes = configuration.protocolClasses ?? []
                if !classes.contains(where: { $0 == MockURLProtocol.self }) {
                    classes.insert(MockURLProtocol.self, at: 0)
                    configuration.protocolClasses = classes
                }
            }
            return created
        }
        method_setImplementation(method, imp_implementationWithBlock(replacement))
    }

    override class func canInit(with request: URLRequest) -> Bool {
        guard let scheme = request.url?.scheme?.lowercased(), scheme == "http" || scheme == "https" else { return false }
        guard URLProtocol.property(forKey: passThroughKey, in: request) == nil else { return false }
        return MockStore.shared.match(request) != nil
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        return request
    }

    override func startLoading() {
        // The rules may have changed between canInit and now: the request goes to the network, as on Android and the web
        guard let rule = MockStore.shared.match(request), let url = request.url else {
            passThrough()
            return
        }
        MockStore.shared.record(rule, for: request)
        let item = DispatchWorkItem { [weak self] in
            self?.deliver(rule, url: url)
        }
        delivery = item
        DispatchQueue.global().asyncAfter(deadline: .now() + rule.delay, execute: item)
    }

    override func stopLoading() {
        delivery?.cancel()
        delivery = nil
        passThroughTask?.cancel()
        passThroughTask = nil
    }

    /// Asks the network what the app asked for and gives the answer to the app.
    private func passThrough() {
        guard let marked = (request as NSURLRequest).mutableCopy() as? NSMutableURLRequest else {
            client?.urlProtocol(self, didFailWithError: URLError(.unknown))
            return
        }
        URLProtocol.setProperty(true, forKey: Self.passThroughKey, in: marked)
        let task = Self.passThroughSession.dataTask(with: marked as URLRequest) { [weak self] data, response, error in
            guard let self else { return }
            if let error {
                self.client?.urlProtocol(self, didFailWithError: error)
                return
            }
            if let response { self.client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed) }
            if let data, !data.isEmpty { self.client?.urlProtocol(self, didLoad: data) }
            self.client?.urlProtocolDidFinishLoading(self)
        }
        passThroughTask = task
        task.resume()
    }

    private func deliver(_ rule: MockStore.Rule, url: URL) {
        guard let response = HTTPURLResponse(url: url, statusCode: rule.status, httpVersion: "HTTP/1.1", headerFields: rule.headers) else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        if !rule.body.isEmpty { client?.urlProtocol(self, didLoad: rule.body) }
        client?.urlProtocolDidFinishLoading(self)
    }
}
