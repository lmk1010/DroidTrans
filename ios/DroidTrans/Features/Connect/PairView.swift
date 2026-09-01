/// 输配对码。
///
/// 扫码那条路会自动带上配对码，走到这一屏说明用户是手输地址进来的。
/// 六位数字，做成分格输入 —— 一个普通输入框在这里会让人不确定该填几位。

import SwiftUI

struct PairView: View {
    let desktop: Desktop

    @EnvironmentObject private var app: AppState
    @State private var code = ""
    @FocusState private var focused: Bool

    private let length = 6

    var body: some View {
        ZStack {
            AppBackground()

            VStack(spacing: Space.xl) {
                Spacer()

                VStack(spacing: Space.m) {
                    ArtIcon(art: .shield, size: 76)
                    Text(L("pair.title"))
                        .font(.system(size: 22, weight: .bold))
                        .foregroundStyle(Color.ink)
                        .accessibilityIdentifier("pair-title")
                    Text(desktop.name)
                        .font(.system(size: 15))
                        .foregroundStyle(Color.ink2)
                }

                Text(L("pair.hint"))
                    .font(.system(size: 14))
                    .foregroundStyle(Color.ink2)
                    .multilineTextAlignment(.center)

                codeBoxes

                Spacer()

                // 「取消，换一台」是换，不是断。disconnect() 会把 lastHost 一起清掉，
                // 用户点一下取消，之前配好的那台电脑就被忘干净了，
                // 回到雷达页连 home 键都是灰的。
                Button(L("pair.cancel")) { app.switchDevice() }
                    .font(.system(size: 15))
                    .foregroundStyle(Color.ink2)
                    .padding(.bottom, Space.l)
            }
            .padding(.horizontal, Space.gutter)

            // 真正接收输入的是这个看不见的输入框，格子只负责显示
            TextField("", text: $code)
                .keyboardType(.numberPad)
                .accessibilityIdentifier("pair-code-field")
                .focused($focused)
                .opacity(0.001)
                .frame(width: 1, height: 1)
                .onChange(of: code) { newValue in
                    let digits = newValue.filter(\.isNumber)
                    code = String(digits.prefix(length))
                    if code.count == length {
                        Task { await app.pair(code: code) }
                    }
                }
        }
        .overlay {
            if app.busy {
                ProgressView().controlSize(.large)
            }
        }
        .preferredColorScheme(.dark)
        .onAppear { focused = true }
        .onChange(of: app.error) { e in
            // 码不对就清空重来，让用户直接接着输，不用先手动删六下
            if e != nil { code = "" }
        }
    }

    private var codeBoxes: some View {
        HStack(spacing: Space.s) {
            ForEach(0..<length, id: \.self) { i in
                let ch = character(at: i)
                RoundedRectangle(cornerRadius: Radius.tile, style: .continuous)
                    .fill(.ultraThinMaterial)
                    .environment(\.colorScheme, .dark)
                    .frame(width: 46, height: 58)
                    .overlay(
                        RoundedRectangle(cornerRadius: Radius.tile, style: .continuous)
                            .strokeBorder(i == code.count ? Color.brand : Color.stroke,
                                          lineWidth: i == code.count ? 1.6 : 0.7)
                    )
                    .overlay(
                        Text(ch)
                            .font(.system(size: 24, weight: .semibold, design: .rounded))
                            .foregroundStyle(Color.ink)
                    )
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { focused = true }
        .animation(.easeOut(duration: 0.15), value: code)
    }

    private func character(at index: Int) -> String {
        guard index < code.count else { return "" }
        return String(Array(code)[index])
    }
}
