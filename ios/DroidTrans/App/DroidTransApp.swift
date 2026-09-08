import SwiftUI

@main
struct DroidTransApp: App {
    @StateObject private var store = Store.shared
    @StateObject private var app = AppState()
    @StateObject private var peer = PeerServer.shared
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .environmentObject(app)
                .tint(.brand)
                // App 一打开就能被别人找到。
                //
                // 原来必须先点进「我要收」，对面才发现得了这台 —— 于是
                // 「我要发」那一屏经常什么都扫不到，而用户根本不知道
                // 还得让对面先做个动作，只觉得这东西连不上。
                //
                // 退到后台就停：手机不是常驻服务器，在后台一直挂着监听
                // 既费电，也不是用户预期里会发生的事。
                .onChange(of: phase) { newPhase in
                    switch newPhase {
                    case .active: peer.startIfIdle()
                    case .background: peer.stop()
                    default: break
                    }
                }
                .task { peer.startIfIdle() }
                // 敲门的弹框放在最外层：不管用户当时在哪一屏，
                // 对面点你一下，这儿都能问一句「同意吗」。
                .alert(knockTitle, isPresented: knocking) {
                    Button(L("peer.knock.deny"), role: .cancel) { peer.deny() }
                    Button(L("peer.knock.allow")) {
                        peer.approve()
                        app.route = .peer(.android)
                    }
                }
        }
    }

    private var knocking: Binding<Bool> {
        Binding(get: { peer.knock != nil },
                set: { if !$0 { peer.deny() } })
    }

    private var knockTitle: String {
        "\(peer.knock?.name ?? L("peer.someone")) \(L("peer.knock.title"))"
    }
}
