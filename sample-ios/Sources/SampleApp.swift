import SwiftUI

@main
struct SampleApp: App {
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

struct ContentView: View {
    @State private var name = ""
    @State private var greeting = ""
    @State private var time = Date()
    @State private var gesture = "none"
    @State private var network = "none"
    @State private var startup = "none"

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            TextField("Name", text: $name)
                .textFieldStyle(.roundedBorder)
                .accessibilityIdentifier("sample.name")
            Button("Greet") { greeting = "Hello \(name)" }
                .accessibilityIdentifier("sample.greet")
            Text(greeting)
                .accessibilityIdentifier("sample.greeting")
            Text("Gesture target")
                .padding(8)
                .contentShape(Rectangle())
                .onTapGesture(count: 2) { gesture = "double tap" }
                .onTapGesture { gesture = "tap" }
                .onLongPressGesture { gesture = "long press" }
                .accessibilityIdentifier("sample.gesture")
            Text("Gesture: \(gesture)")
                .accessibilityIdentifier("sample.gesture.result")
            // A host that does not exist: the text comes from a mock of the end-to-end tests, or it is an error
            HStack {
                Button("Fetch") { Task { network = await load("greeting") } }
                    .accessibilityIdentifier("sample.net.fetch")
                Text("Network: \(network)")
                    .accessibilityIdentifier("sample.net.result")
                Text("Startup: \(startup)")
                    .accessibilityIdentifier("sample.net.startup")
            }
            DatePicker("", selection: $time, displayedComponents: [.hourAndMinute])
                .datePickerStyle(.wheel)
                .labelsHidden()
                .frame(height: 100)
                .accessibilityIdentifier("sample.time")
            Text(time.formatted(date: .omitted, time: .shortened))
                .accessibilityIdentifier("sample.timeLabel")
            ScrollView {
                LazyVStack(alignment: .leading) {
                    ForEach(1...60, id: \.self) { index in
                        Text("Row \(index)").accessibilityIdentifier("sample.row.\(index)")
                    }
                }
            }
            .accessibilityIdentifier("sample.list")
        }
        .padding()
        // The first request is made while the app starts, so the tests can mock what an app asks for at startup
        .task { startup = await load("startup") }
    }

    private func load(_ path: String) async -> String {
        guard let url = URL(string: "https://api.gekko.invalid/v1/\(path)") else { return "error" }
        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            return "\(status) \(String(decoding: data, as: UTF8.self))"
        } catch {
            return "error"
        }
    }
}
