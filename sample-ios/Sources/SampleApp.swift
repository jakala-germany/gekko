import SwiftUI

@main
struct SampleApp: App {
    var body: some Scene {
        WindowGroup {
            #if os(tvOS)
            TvContentView()
            #else
            ContentView()
            #endif
        }
    }
}

#if os(tvOS)
/// The screen of a TV app: a search field above a grid of teasers that the remote moves the focus through (up, down,
/// left, right) and opens (select). The ids are the ones of the Android sample, so the same tests run on both.
struct TvContentView: View {
    private enum Spot: Hashable {
        case search
        case teaser(Int)
    }

    @FocusState private var focus: Spot?
    @State private var query = ""
    @State private var opened = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            TextField("Search", text: $query)
                .focused($focus, equals: .search)
                .accessibilityIdentifier("sample.tv.search")
            Text("Opened: \(opened)")
                .accessibilityIdentifier("sample.teaser.opened")
            ForEach(0 ..< 3, id: \.self) { row in
                HStack(spacing: 24) {
                    ForEach(1 ... 3, id: \.self) { column in
                        let number = row * 3 + column
                        Button("Teaser \(number)") { opened = number }
                            .focused($focus, equals: .teaser(number))
                            .accessibilityIdentifier("sample.teaser.\(number)")
                    }
                }
            }
        }
        .padding(60)
        // A TV app starts with the focus on its content
        .onAppear { focus = .teaser(1) }
    }
}
#else
struct ScrollOffsetKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}

extension View {
    /// The scroll offset of a scroll view as a number. The preference above stays at 0 while a scroll view moves on
    /// iOS 18 and later, where the scroll geometry reports it.
    @ViewBuilder
    func readScrollOffset(into offset: Binding<Int>) -> some View {
        #if compiler(>=6.0)
        if #available(iOS 18.0, *) {
            onScrollGeometryChange(for: CGFloat.self) { geometry in
                geometry.contentOffset.y + geometry.contentInsets.top
            } action: { _, new in
                offset.wrappedValue = Int(new.rounded())
            }
        } else {
            self
        }
        #else
        self
        #endif
    }
}

/// One of each kind of control, for `scripts/check-roles.py`: the in-app agent and the system runner must call every
/// one of them by the same role.
struct ControlsView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var search = ""
    @State private var notes = ""
    @State private var enabled = false
    @State private var level = 0.5
    @State private var count = 0

    var body: some View {
        // NavigationView: the sample is built for iOS 15, which has no NavigationStack
        NavigationView {
            Form {
                Section("Inputs") {
                    TextEditor(text: $notes)
                        .frame(height: 60)
                        .accessibilityIdentifier("sample.controls.notes")
                    Toggle("Enabled", isOn: $enabled)
                        .accessibilityIdentifier("sample.controls.switch")
                    Slider(value: $level)
                        .accessibilityIdentifier("sample.controls.slider")
                    Stepper("Count: \(count)", value: $count)
                        .accessibilityIdentifier("sample.controls.stepper")
                }
            }
            .navigationTitle("Controls")
            .searchable(text: $search)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                        .accessibilityIdentifier("sample.controls.done")
                }
            }
        }
        .navigationViewStyle(.stack)
    }
}

struct ContentView: View {
    @State private var name = ""
    @State private var greeting = ""
    @State private var password = ""
    @State private var time = Date()
    @State private var gesture = "none"
    @State private var network = "none"
    @State private var startup = "none"
    /// How far the list is scrolled, in points: the end-to-end tests compare what a drag and a swipe moved
    @State private var scrolled = 0
    @State private var controlsShown = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // Both fields share a row, so the rows below keep their place
            HStack {
                // Return on the keyboard greets, as the button does
                TextField("Name", text: $name)
                    .textFieldStyle(.roundedBorder)
                    .accessibilityIdentifier("sample.name")
                    .onSubmit { greeting = "Hello \(name)" }
                SecureField("Password", text: $password)
                    .textFieldStyle(.roundedBorder)
                    .accessibilityIdentifier("sample.password")
            }
            HStack(spacing: 16) {
                Button("Greet") { greeting = "Hello \(name)" }
                    .accessibilityIdentifier("sample.greet")
                // The controls of the role check: it compares what the agent and the system runner call them
                Button("Controls") { controlsShown = true }
                    .accessibilityIdentifier("sample.controls")
                // A password is never readable in the tree: the app tells how long it is, which a test can check
                Text("Password: \(password.count)")
                    .accessibilityIdentifier("sample.password.length")
            }
            Text(greeting)
                .accessibilityIdentifier("sample.greeting")
            Text("Gesture target")
                .padding(8)
                .contentShape(Rectangle())
                .onTapGesture(count: 2) { gesture = "double tap" }
                .onTapGesture { gesture = "tap" }
                .onLongPressGesture { gesture = "long press" }
                .accessibilityIdentifier("sample.gesture")
            // The scroll offset next to the gesture result, so the list keeps its place
            HStack(spacing: 16) {
                Text("Gesture: \(gesture)")
                    .accessibilityIdentifier("sample.gesture.result")
                Text("Scroll: \(scrolled)")
                    .accessibilityIdentifier("sample.list.offset")
            }
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
                .background(
                    GeometryReader { proxy in
                        Color.clear.preference(key: ScrollOffsetKey.self, value: -proxy.frame(in: .named("sample.list")).minY)
                    }
                )
            }
            .coordinateSpace(name: "sample.list")
            .onPreferenceChange(ScrollOffsetKey.self) { scrolled = Int($0.rounded()) }
            .readScrollOffset(into: $scrolled)
            .accessibilityIdentifier("sample.list")
        }
        .padding()
        .sheet(isPresented: $controlsShown) { ControlsView() }
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
#endif
