/// 左上角的 home 键。
///
/// 只做一件事：回主界面。纯跳转，不发网络请求、不可能失败、不会变灰、
/// 也不会消失 —— 主界面（StartView）任何时候都在，所以它任何时候都有地方可回。

import SwiftUI

struct HomeButton: View {
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "house.fill")
                .font(.system(size: 19, weight: .medium))
                .foregroundStyle(Color.brand)
                .frame(width: 44, height: 44)   // 图标只有 19，触控区补到 44
                .contentShape(Rectangle())
        }
        .accessibilityIdentifier("back-to-last")
        .accessibilityLabel(L("peer.back"))
    }
}
