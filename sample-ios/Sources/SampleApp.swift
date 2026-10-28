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
    }
}
