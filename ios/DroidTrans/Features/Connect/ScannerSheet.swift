/// 扫码。桌面端二维码里是 http://ip:9500/?c=<配对码>，扫一下就连上并配好。

import AVFoundation
import SwiftUI

struct ScannerSheet: View {
    var onFound: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var denied = false

    var body: some View {
        NavigationView {
            ZStack {
                if denied {
                    permissionHint
                } else {
                    CameraView(onFound: onFound, onDenied: { denied = true })
                        .ignoresSafeArea()
                    scanFrame
                }
            }
            .navigationTitle(L("scan.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("common.cancel")) { dismiss() }
                }
            }
        }
    }

    /// 取景框。只画四个角，比一个完整的方框干净，也不挡二维码。
    private var scanFrame: some View {
        VStack(spacing: Space.l) {
            Spacer()
            ZStack {
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .stroke(Color.white.opacity(0.9), lineWidth: 3)
                    .frame(width: 230, height: 230)
                    .mask(CornerMask())
            }
            Text(L("scan.aim"))
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(.white)
                .shadow(radius: 3)
            Spacer()
        }
    }

    private var permissionHint: some View {
        VStack(spacing: Space.l) {
            Image(systemName: "camera.fill")
                .font(.system(size: 34, weight: .medium))
                .foregroundStyle(Color.brand)
            Text(L("scan.noPermission"))
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(Color.ink)
            Text(L("scan.noPermission.hint"))
                .font(.system(size: 14))
                .foregroundStyle(Color.ink2)
                .multilineTextAlignment(.center)
                .lineSpacing(3)
            Button(L("scan.openSettings")) {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
            .buttonStyle(GhostButtonStyle())
            .frame(maxWidth: 200)
        }
        .padding(Space.xl)
    }
}

/// 只留四个角的遮罩
private struct CornerMask: View {
    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height
            let arm: CGFloat = 34
            Path { p in
                p.addRect(CGRect(x: 0, y: 0, width: arm, height: arm))
                p.addRect(CGRect(x: w - arm, y: 0, width: arm, height: arm))
                p.addRect(CGRect(x: 0, y: h - arm, width: arm, height: arm))
                p.addRect(CGRect(x: w - arm, y: h - arm, width: arm, height: arm))
            }
            .fill(Color.white)
        }
    }
}

// MARK: - 相机

private struct CameraView: UIViewControllerRepresentable {
    var onFound: (String) -> Void
    var onDenied: () -> Void

    func makeUIViewController(context: Context) -> ScannerController {
        let vc = ScannerController()
        vc.onFound = onFound
        vc.onDenied = onDenied
        return vc
    }

    func updateUIViewController(_ vc: ScannerController, context: Context) {}
}

final class ScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onFound: ((String) -> Void)?
    var onDenied: (() -> Void)?

    private let session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer?
    /// 扫到之后会连着回调好几次，只认第一次
    private var handled = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configure()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] ok in
                DispatchQueue.main.async {
                    ok ? self?.configure() : self?.onDenied?()
                }
            }
        default:
            onDenied?()
        }
    }

    private func configure() {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            onDenied?()
            return
        }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { return }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        // 必须在 addOutput 之后设置，否则这一行会因为「不支持的类型」直接崩
        output.metadataObjectTypes = [.qr]

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.addSublayer(layer)
        preview = layer

        // startRunning 是阻塞的，放主线程会卡住转场动画
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            self?.session.startRunning()
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if session.isRunning {
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                self?.session.stopRunning()
            }
        }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput,
                        didOutput objects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard !handled,
              let obj = objects.first as? AVMetadataMachineReadableCodeObject,
              let value = obj.stringValue else { return }
        handled = true
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        onFound?(value)
    }
}
