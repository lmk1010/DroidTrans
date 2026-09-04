/// 正在等对面点头。
///
/// 对面是一台手机时走这一屏，取代输六位码那一屏 ——
/// 两个人面对面传东西，让一个人念码给另一个人抄，纯属自找麻烦。
/// 真正的把关在对面那台：它会弹一句「XXX 想连过来」，同意才发令牌。

import SwiftUI

struct ApprovalView: View {
    let desktop: Desktop

    @EnvironmentObject private var app: AppState
    @State private var asked = false

    var body: some View {
        ZStack {
            AppBackground()

            VStack(spacing: Space.xl) {
                Spacer()

                ArtIcon(art: .link, size: 76)

                VStack(spacing: Space.m) {
                    Text(L("approval.waiting"))
                        .accessibilityIdentifier("approval-waiting")
                        .font(.system(size: 22, weight: .bold))
                        .foregroundStyle(Color.ink)
                    Text(desktop.name)
                        .font(.system(size: 15))
                        .foregroundStyle(Color.ink2)
                    Text(L("approval.hint"))
                        .font(.system(size: 14))
                        .foregroundStyle(Color.ink2)
                        .multilineTextAlignment(.center)
                        .lineSpacing(3)
                        .padding(.horizontal, Space.xl)
                }

                ProgressView()
                    .controlSize(.large)
                    .tint(Color.brand)

                Spacer()

                Button(L("pair.cancel")) { app.switchDevice() }
                    .font(.system(size: 15))
                    .foregroundStyle(Color.ink2)
                    .padding(.bottom, Space.l)
            }
            .padding(.horizontal, Space.gutter)
        }
        .preferredColorScheme(.dark)
        .task {
            // 进来就敲门。放在 .task 而不是按钮上：用户已经在雷达上点过那台了，
            // 再让他点一次「请求连接」是多余的一步。
            guard !asked else { return }
            asked = true
            await app.knock()
        }
    }
}
