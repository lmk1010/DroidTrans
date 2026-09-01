import SwiftUI

@main
struct DroidTransApp: App {
    @StateObject private var store = Store.shared
    @StateObject private var app = AppState()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .environmentObject(app)
                .tint(.brand)
        }
    }
}
