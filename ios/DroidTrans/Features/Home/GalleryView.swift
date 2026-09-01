/// 图库：从电脑取回来的东西都在这儿。
///
/// 历史记录是一份清单，回答「传过什么」；图库是内容本身，回答「那张图长什么样」。
/// 用户找一张照片时不会记得文件名，只认得出画面 —— 所以缩略图不能省。

import QuickLook
import SwiftUI

struct GalleryView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var files: [LocalFile] = []
    @State private var preview: LocalFile?
    @State private var confirmDelete: LocalFile?

    private let columns = [GridItem(.adaptive(minimum: 104), spacing: Space.s)]

    var body: some View {
        ZStack {
            AppBackground()

            VStack(spacing: 0) {
                bar
                if files.isEmpty { empty } else { grid }
            }
        }
        .preferredColorScheme(.dark)
        .task { reload() }
        .sheet(item: $preview) { f in
            QuickLookView(url: f.url)
                .ignoresSafeArea()
        }
        .confirmationDialog(
            L("gallery.delete.confirm"),
            isPresented: .constant(confirmDelete != nil),
            titleVisibility: .visible
        ) {
            Button(L("gallery.delete"), role: .destructive) {
                if let f = confirmDelete { delete(f) }
                confirmDelete = nil
            }
            Button(L("common.cancel"), role: .cancel) { confirmDelete = nil }
        }
    }

    private var bar: some View {
        HStack {
            Button(L("common.done")) { dismiss() }
                .foregroundStyle(Color.brand)
            Spacer()
            Text(L("gallery.title"))
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Color.ink)
            Spacer()
            Text(files.isEmpty ? "" : "\(files.count)")
                .font(.system(size: 14))
                .foregroundStyle(Color.ink3)
                .frame(minWidth: 36, alignment: .trailing)
        }
        .padding(.horizontal, Space.gutter)
        .padding(.vertical, Space.m)
    }

    private var empty: some View {
        VStack(spacing: Space.m) {
            Spacer()
            ArtIcon(art: .inbox, size: 84)
            Text(L("gallery.empty"))
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(Color.ink)
            Text(L("gallery.empty.hint"))
                .font(.system(size: 13.5))
                .foregroundStyle(Color.ink3)
                .multilineTextAlignment(.center)
                .padding(.horizontal, Space.xl)
            Spacer()
            Spacer()
        }
    }

    private var grid: some View {
        ScrollView(showsIndicators: false) {
            LazyVGrid(columns: columns, spacing: Space.s) {
                ForEach(files) { f in
                    Tile(file: f)
                        .onTapGesture { preview = f }
                        .onLongPressGesture {
                            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                            confirmDelete = f
                        }
                }
            }
            .padding(.horizontal, Space.gutter)
            .padding(.bottom, Space.xxl)
        }
    }

    // MARK: - 数据

    /// 直接列 Documents 目录，不另外维护一份索引。
    ///
    /// 用户可能在「文件」App 里把东西删了或挪走了 ——
    /// 以目录为准，索引只会和现实分叉，然后点开是一片空白。
    private func reload() {
        let root = FileManager.default
            .urls(for: .documentDirectory, in: .userDomainMask)[0]
        let keys: [URLResourceKey] = [.contentModificationDateKey, .fileSizeKey, .isDirectoryKey]
        guard let e = FileManager.default.enumerator(
            at: root, includingPropertiesForKeys: keys,
            options: [.skipsHiddenFiles]
        ) else { return }

        var out: [LocalFile] = []
        for case let url as URL in e {
            let v = try? url.resourceValues(forKeys: Set(keys))
            if v?.isDirectory == true { continue }
            out.append(LocalFile(
                url: url,
                size: Int64(v?.fileSize ?? 0),
                at: v?.contentModificationDate ?? .distantPast
            ))
        }
        files = out.sorted { $0.at > $1.at }
    }

    private func delete(_ f: LocalFile) {
        try? FileManager.default.removeItem(at: f.url)
        reload()
    }
}

struct LocalFile: Identifiable, Equatable {
    let url: URL
    let size: Int64
    let at: Date

    var id: String { url.path }
    var name: String { url.lastPathComponent }

    var isImage: Bool {
        ["jpg", "jpeg", "png", "heic", "heif", "gif", "webp"]
            .contains(url.pathExtension.lowercased())
    }
    var isVideo: Bool {
        ["mp4", "mov", "m4v", "avi", "mkv"].contains(url.pathExtension.lowercased())
    }
}

// MARK: - 格子

private struct Tile: View {
    let file: LocalFile
    @State private var thumb: UIImage?

    var body: some View {
        ZStack {
            if let thumb {
                Image(uiImage: thumb)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
            } else {
                VStack(spacing: 6) {
                    ArtIcon(art: file.isVideo ? .photos : .files, size: 34)
                    Text(file.name)
                        .font(.system(size: 10))
                        .foregroundStyle(Color.ink3)
                        .lineLimit(2)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 6)
                }
            }

            if file.isVideo {
                // 视频缩略图和照片长得一样，得有个标记区分
                VStack {
                    Spacer()
                    HStack {
                        Image(systemName: "play.fill")
                            .font(.system(size: 9, weight: .bold))
                            .foregroundStyle(.white)
                            .padding(5)
                            .background(Circle().fill(.black.opacity(0.45)))
                        Spacer()
                    }
                }
                .padding(6)
            }
        }
        .frame(height: 104)
        .frame(maxWidth: .infinity)
        .clipped()
        .glass(radius: Radius.tile)
        .task { await makeThumb() }
    }

    /// 缩略图走 QuickLook，图片和视频都能出图。
    /// 不自己解码原图 —— 一屏几十张 4K 照片会直接把内存吃爆。
    private func makeThumb() async {
        guard file.isImage || file.isVideo, thumb == nil else { return }
        let size = CGSize(width: 220, height: 220)
        let req = QLThumbnailGenerator.Request(
            fileAt: file.url, size: size,
            scale: await UIScreen.main.scale,
            representationTypes: .thumbnail
        )
        let image = try? await QLThumbnailGenerator.shared.generateBestRepresentation(for: req)
        thumb = image?.uiImage
    }
}

// MARK: - 预览

private struct QuickLookView: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> QLPreviewController {
        let vc = QLPreviewController()
        vc.dataSource = context.coordinator
        return vc
    }

    func updateUIViewController(_ vc: QLPreviewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(url: url) }

    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        let url: URL
        init(url: URL) { self.url = url }

        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController,
                               previewItemAt index: Int) -> QLPreviewItem {
            url as NSURL
        }
    }
}
