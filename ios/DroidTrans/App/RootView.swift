import SwiftUI

/// 按连接状态决定给用户看什么。
struct RootView: View {
    @EnvironmentObject private var app: AppState

    var body: some View {
        content
            .task { await app.restoreLastSession() }
    }

    @ViewBuilder
    private var content: some View {
        switch app.phase {
        case .disconnected:
            switch app.route {
            case .start:       StartView()
            case .findDesktop: ConnectView()
            case .peer(let k): PeerView(kind: k)
            }
        case .needsPairing(let d):
            PairView(desktop: d)
        case .awaitingApproval(let d):
            ApprovalView(desktop: d)
        case .connected(let d):
            HomeScreen(desktop: d)
        }
    }
}
