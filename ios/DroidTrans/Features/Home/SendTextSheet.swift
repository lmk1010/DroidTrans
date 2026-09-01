import SwiftUI

/// 发一段文字或链接，直接落到电脑剪贴板。
struct SendTextSheet: View {
    let desktop: Desktop
    var onSent: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var sending = false
    @State private var error: String?
    @FocusState private var focused: Bool

    var body: some View {
        ZStack {
            AppBackground()

            VStack(alignment: .leading, spacing: Space.l) {
                HStack {
                    Button(L("common.cancel")) { dismiss() }
                        .foregroundStyle(Color.ink2)
                    Spacer()
                    Text(L("text.title"))
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Color.ink)
                    Spacer()
                    Button(L("common.cancel")) { }.opacity(0).disabled(true)   // 占位，让标题居中
                }

                ZStack(alignment: .topLeading) {
                    if text.isEmpty {
                        Text(L("text.placeholder"))
                            .font(.system(size: 16))
                            .foregroundStyle(Color.ink3)
                            .padding(.horizontal, Space.m + 5)
                            .padding(.vertical, Space.m + 8)
                    }
                    TextEditor(text: $text)
                        .font(.system(size: 16))
                        .foregroundStyle(Color.ink)
                        .accessibilityIdentifier("text-editor")
                        .focused($focused)
                        .padding(Space.m)
                        // scrollContentBackground 是 iOS 16 才有的，
                        // 部署目标是 15.5，只能从 UIKit 那层把底色抹掉
                        .onAppear { UITextView.appearance().backgroundColor = .clear }
                        .onDisappear { UITextView.appearance().backgroundColor = nil }
                }
                .frame(minHeight: 170)
                .glass()

                Text(L("text.hint"))
                    .font(.system(size: 13))
                    .foregroundStyle(Color.ink3)

                Button(sending ? L("common.sending") : L("common.send")) {
                    Task { await send() }
                }
                .accessibilityIdentifier("send-text-button")
                .buttonStyle(PrimaryButtonStyle(enabled: !trimmed.isEmpty && !sending))
                .disabled(trimmed.isEmpty || sending)

                Spacer()
            }
            .padding(Space.gutter)
        }
        .preferredColorScheme(.dark)
        .onAppear { focused = true }
        .alert(L("text.failed"), isPresented: .constant(error != nil)) {
            Button(L("common.ok")) { error = nil }
        } message: {
            Text(error ?? "")
        }
    }

    private var trimmed: String { text.trimmingCharacters(in: .whitespacesAndNewlines) }

    private func send() async {
        sending = true
        defer { sending = false }
        let api = ApiClient(baseURL: desktop.baseURL, token: Store.shared.token(for: desktop))
        do {
            try await api.sendText(trimmed)
            onSent()
            dismiss()
        } catch {
            self.error = (error as? ApiError)?.message ?? error.localizedDescription
        }
    }
}
