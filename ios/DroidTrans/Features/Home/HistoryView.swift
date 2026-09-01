import SwiftUI

/// 传过什么、收过什么，按天倒着排。
struct HistoryView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var history = History.shared
    @State private var confirmClear = false
    @State private var share: URL?

    var body: some View {
        ZStack {
            AppBackground()

            VStack(spacing: 0) {
                bar

                if history.entries.isEmpty {
                    empty
                } else {
                    list
                }
            }
        }
        .preferredColorScheme(.dark)
        .confirmationDialog(L("history.clear.confirm"), isPresented: $confirmClear, titleVisibility: .visible) {
            Button(L("common.clear"), role: .destructive) { history.clear() }
            Button(L("history.clear.keep"), role: .cancel) {}
        } message: {
            Text(L("history.clear.hint"))
        }
        .sheet(item: $share) { url in
            ShareSheet(items: [url])
        }
    }

    private var bar: some View {
        HStack {
            Button(L("common.done")) { dismiss() }
                .foregroundStyle(Color.brand)
            Spacer()
            Text(L("history.title"))
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Color.ink)
            Spacer()
            Button(L("common.clear")) { confirmClear = true }
                .foregroundStyle(history.entries.isEmpty ? Color.ink3 : Color.ink2)
                .disabled(history.entries.isEmpty)
        }
        .padding(.horizontal, Space.gutter)
        .padding(.vertical, Space.m)
    }

    private var empty: some View {
        VStack(spacing: Space.m) {
            Spacer()
            ArtIcon(art: .inbox, size: 84)
            Text(L("history.empty"))
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(Color.ink)
            Text(L("history.empty.hint"))
                .font(.system(size: 13.5))
                .foregroundStyle(Color.ink3)
            Spacer()
            Spacer()
        }
    }

    private var list: some View {
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: Space.l) {
                ForEach(history.grouped, id: \.day) { group in
                    VStack(alignment: .leading, spacing: Space.s) {
                        SectionHeader(dayLabel(group.day))
                        VStack(spacing: Space.s) {
                            ForEach(group.items) { e in
                                Row(entry: e) { url in share = url }
                            }
                        }
                    }
                }
                Color.clear.frame(height: Space.xxl)
            }
            .padding(.horizontal, Space.gutter)
        }
    }

    private func dayLabel(_ day: Date) -> String {
        let cal = Calendar.current
        if cal.isDateInToday(day) { return L("history.today") }
        if cal.isDateInYesterday(day) { return L("history.yesterday") }
        let f = DateFormatter()
        f.setLocalizedDateFormatFromTemplate("MMMd")
        return f.string(from: day)
    }
}

private struct Row: View {
    let entry: HistoryEntry
    var onShare: (URL) -> Void

    var body: some View {
        HStack(spacing: Space.m) {
            ZStack {
                ArtIcon(art: art, size: 32)
                // 方向角标：一眼看出这条是发出去的还是拿回来的
                Image(systemName: entry.direction == .sent ? "arrow.up" : "arrow.down")
                    .font(.system(size: 8, weight: .black))
                    .foregroundStyle(.white)
                    .frame(width: 15, height: 15)
                    .background(Circle().fill(entry.direction == .sent ? Color.brand : .green))
                    .offset(x: 14, y: 12)
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(entry.name)
                    .font(.system(size: 14.5, weight: .medium))
                    .foregroundStyle(Color.ink)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text(subtitle)
                    .font(.system(size: 12))
                    .foregroundStyle(Color.ink3)
            }

            Spacer()

            if let url = fileURL {
                Button {
                    onShare(url)
                } label: {
                    Image(systemName: "square.and.arrow.up")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(Color.brand)
                        .frame(width: 34, height: 34)
                }
            }
        }
        .padding(Space.m)
        .glass(radius: Radius.tile)
    }

    /// 收到的文件还在手机上才给分享按钮。用户可能已经在「文件」里删了它。
    private var fileURL: URL? {
        guard entry.direction == .received, let rel = entry.localPath else { return nil }
        let url = FileManager.default
            .urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(rel)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    private var subtitle: String {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        let dir = entry.direction == .sent ? L("history.to") : L("history.from")
        return "\(f.string(from: entry.at)) · \(humanSize(entry.size)) · \(dir) \(entry.peer)"
    }

    private var art: Art {
        let ext = (entry.name as NSString).pathExtension.lowercased()
        switch ext {
        case "jpg", "jpeg", "png", "heic", "gif", "webp", "mp4", "mov": return .photos
        case "txt", "md": return .text
        default: return .files
        }
    }
}

/// 系统分享面板。让用户把取回来的文件转手发到别处。
private struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}

/// sheet(item:) 要求 Identifiable，URL 默认没有
extension URL: Identifiable {
    public var id: String { absoluteString }
}
