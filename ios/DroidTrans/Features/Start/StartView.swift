/// 主界面：先选跟谁传，再进对应的连接流程。
///
/// 原来 App 一打开就是雷达页，直接开扫 —— 用户第一眼看到的是一个正在转的圈，
/// 而不是「我能干什么」。而且雷达页只找电脑，可这个 App 要做的是三件事：
/// 和电脑传、和 iPhone 传、和安卓机传。把三件事摆在一屏上，选哪件是用户的事。
///
/// 这一屏还是整个 App 的「家」。有了它，各处的 home 键才有地方可回 ——
/// 在这之前主界面必须先连上电脑才存在，返回键无处可去。

import SwiftUI

struct StartView: View {
    @EnvironmentObject private var app: AppState
    @State private var showMe = false
    /// 入场动画只跑一次。做成 @State 而不是每次 body 求值都重来，
    /// 是因为 sheet 关掉时 body 会重算，卡片不该跟着再飞一遍。
    @State private var entered = false

    private var cards: [(art: Art, title: String, sub: String, id: String, go: () -> Void)] {
        [
            (.laptop,  L("start.desktop"), L("start.desktop.sub"), "start-desktop",
             { app.route = .findDesktop() }),
            (.phone,   L("start.iphone"),  L("start.iphone.sub"),  "start-iphone",
             { app.route = .peer(.iphone) }),
            (.android, L("start.android"), L("start.android.sub"), "start-android",
             { app.route = .peer(.android) }),
        ]
    }

    var body: some View {
        ZStack {
            AppBackground()

            VStack(spacing: 0) {
                settingsRow

                // 上下各留一段可伸缩的空白，把「标志 + 标题 + 三张卡」这一整块
                // 顶到视觉中心。全靠 padding.top 顶着的话，屏幕一高，
                // 内容就全挤在上半屏，下面空一大片 —— 之前就是这样。
                Spacer(minLength: Space.l)

                brand

                Spacer(minLength: Space.xl)

                VStack(spacing: Space.m) {
                    ForEach(Array(cards.enumerated()), id: \.offset) { i, c in
                        card(c)
                            // 依次浮上来，不是三张一起弹 —— 错开一点点，
                            // 眼睛才会顺着从上往下读
                            .opacity(entered ? 1 : 0)
                            .offset(y: entered ? 0 : 18)
                            .animation(.spring(response: 0.5, dampingFraction: 0.82)
                                        .delay(0.08 * Double(i) + 0.12),
                                       value: entered)
                    }
                }
                .padding(.horizontal, Space.gutter)

                Spacer(minLength: Space.xl)
                Spacer(minLength: 0)   // 下面比上面多留一点，重心略微上移，看着才稳
            }
        }
        .preferredColorScheme(.dark)
        .onAppear { entered = true }
        .sheet(isPresented: $showMe) {
            MeView(desktop: nil).environmentObject(app)
        }
    }

    // MARK: - 设置

    /// 设置放右上角。它必须在这一屏上 —— 之前设置只挂在「连上电脑之后」的
    /// 主界面里，没连上电脑的人根本改不了设备名、看不到自己的授权状态。
    private var settingsRow: some View {
        HStack {
            Spacer()
            Button { showMe = true } label: {
                Image(systemName: "gearshape")
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(Color.ink2)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityIdentifier("open-me")
            .accessibilityLabel(L("start.settings"))
        }
        .padding(.horizontal, Space.gutter - 10)
        .padding(.top, Space.s)
    }

    // MARK: - 标志与标题

    private var brand: some View {
        VStack(spacing: Space.m) {
            // 用的是 app_logo.svg 本人 —— 和 App 图标、Mac 端侧栏是同一枚。
            // 别在这儿放一个「看着差不多」的图：一个产品只能有一个标志。
            Image("logo")
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(width: 84, height: 84)
                .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                .shadow(color: Color.brand.opacity(0.45), radius: 22, y: 8)
                .scaleEffect(entered ? 1 : 0.82)
                .opacity(entered ? 1 : 0)
                .animation(.spring(response: 0.6, dampingFraction: 0.72), value: entered)

            VStack(spacing: Space.xs) {
                Text(L("connect.title"))
                    .font(.system(size: 30, weight: .bold))
                    .foregroundStyle(Color.ink)

                Text(L("start.subtitle"))
                    .font(.system(size: 15))
                    .foregroundStyle(Color.ink2)
            }
            .opacity(entered ? 1 : 0)
            .offset(y: entered ? 0 : 10)
            .animation(.spring(response: 0.55, dampingFraction: 0.85).delay(0.06),
                       value: entered)
        }
    }

    // MARK: - 卡片

    private func card(_ c: (art: Art, title: String, sub: String,
                            id: String, go: () -> Void)) -> some View {
        Button(action: c.go) {
            HStack(spacing: Space.l) {
                ArtIcon(art: c.art, size: 50)

                VStack(alignment: .leading, spacing: 3) {
                    Text(c.title)
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(Color.ink)
                    Text(c.sub)
                        .font(.system(size: 13))
                        .foregroundStyle(Color.ink2)
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Spacer(minLength: Space.s)

                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Color.ink3)
            }
            .padding(.vertical, Space.l)
            .padding(.horizontal, Space.l)
            .frame(maxWidth: .infinity, alignment: .leading)
            .glass(radius: Radius.card)
        }
        .buttonStyle(PressableCardStyle())
        .accessibilityIdentifier(c.id)
    }
}

/// 按下去要有回应。系统默认的 .plain 一点反馈都没有，
/// 大卡片尤其明显 —— 手指按上去像按在一张贴纸上。
private struct PressableCardStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.975 : 1)
            .opacity(configuration.isPressed ? 0.88 : 1)
            .animation(.easeOut(duration: 0.14), value: configuration.isPressed)
    }
}
