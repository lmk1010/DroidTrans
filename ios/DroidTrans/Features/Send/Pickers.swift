/// 选照片、选文件。
///
/// 两个都是 UIKit 的控制器，SwiftUI 里得包一层。

import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// 选出来的一项。url 指向 App 自己临时目录里的副本。
struct PickedFile {
    let url: URL
    let name: String
    /// 从相册导出的副本，传完要删；用户自己的文件不能删
    let temporary: Bool
}

// MARK: - 相册

struct PhotoPicker: UIViewControllerRepresentable {
    var onPicked: ([PickedFile]) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var cfg = PHPickerConfiguration(photoLibrary: .shared())
        cfg.selectionLimit = 0            // 0 = 不限张数
        cfg.preferredAssetRepresentationMode = .current  // 别为了兼容把 HEIC 转成 JPEG
        let vc = PHPickerViewController(configuration: cfg)
        vc.delegate = context.coordinator
        return vc
    }

    func updateUIViewController(_ vc: PHPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onPicked: onPicked, dismiss: { dismiss() })
    }

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        private let onPicked: ([PickedFile]) -> Void
        private let dismiss: () -> Void

        init(onPicked: @escaping ([PickedFile]) -> Void, dismiss: @escaping () -> Void) {
            self.onPicked = onPicked
            self.dismiss = dismiss
        }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            dismiss()
            guard !results.isEmpty else { return }

            Task {
                var files: [PickedFile] = []
                for r in results {
                    if let f = await Self.export(r.itemProvider) {
                        files.append(f)
                    }
                }
                await MainActor.run { self.onPicked(files) }
            }
        }

        /// 把相册里的资源导出成临时文件。
        ///
        /// loadFileRepresentation 给的 URL 在回调返回后就失效了，
        /// 必须在闭包里立刻拷走 —— 不然后面读到的是一个不存在的路径。
        private static func export(_ provider: NSItemProvider) async -> PickedFile? {
            let type: UTType
            if provider.hasItemConformingToTypeIdentifier(UTType.movie.identifier) {
                type = .movie
            } else if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
                type = .image
            } else {
                return nil
            }

            return await withCheckedContinuation { k in
                provider.loadFileRepresentation(forTypeIdentifier: type.identifier) { url, _ in
                    guard let url else { return k.resume(returning: nil) }
                    let name = url.lastPathComponent
                    let dest = FileManager.default.temporaryDirectory
                        .appendingPathComponent(UUID().uuidString, isDirectory: true)
                        .appendingPathComponent(name)
                    do {
                        try FileManager.default.createDirectory(
                            at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
                        try FileManager.default.copyItem(at: url, to: dest)
                        k.resume(returning: PickedFile(url: dest, name: name, temporary: true))
                    } catch {
                        k.resume(returning: nil)
                    }
                }
            }
        }
    }
}

// MARK: - 文件

struct DocumentPicker: UIViewControllerRepresentable {
    var onPicked: ([PickedFile]) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let vc = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)
        vc.allowsMultipleSelection = true
        vc.delegate = context.coordinator
        return vc
    }

    func updateUIViewController(_ vc: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onPicked: onPicked) }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        private let onPicked: ([PickedFile]) -> Void
        init(onPicked: @escaping ([PickedFile]) -> Void) { self.onPicked = onPicked }

        func documentPicker(_ controller: UIDocumentPickerViewController,
                            didPickDocumentsAt urls: [URL]) {
            // asCopy: true，系统已经把副本放进我们的临时目录了，传完可以删
            onPicked(urls.map {
                PickedFile(url: $0, name: $0.lastPathComponent, temporary: true)
            })
        }
    }
}
