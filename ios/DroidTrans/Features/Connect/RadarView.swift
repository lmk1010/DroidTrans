/// 雷达。
///
/// 找电脑要花几秒，这几秒里屏幕上得有东西在动、而且要让人相信它真的在找。
///
/// 动画一律由 TimelineView 的时间戳驱动，不用 withAnimation(.repeatForever)：
/// 后者绑在视图的生命周期上，父视图一重建动画就没了，
/// 表现出来就是「雷达卡住不转了」，而且不报任何错。
///
/// 节点位置必须稳定：同一台电脑每次刷新都跳到别处，会让人以为是两台不同的机器。
/// 所以角度按「第几个被发现的」用黄金角分配，而不是随机。

import SwiftUI

struct RadarView: View {
    let devices: [Desktop]
    let scanning: Bool
    var onTap: (Desktop) -> Void

    var body: some View {
        GeometryReader { geo in
            let side = min(geo.size.width, geo.size.height)
            let mid = CGPoint(x: geo.size.width / 2, y: geo.size.height / 2)
            let radius = side / 2

            TimelineView(.animation) { timeline in
                let t = timeline.date.timeIntervalSinceReferenceDate

                ZStack {
                    Canvas { ctx, size in
                        drawBase(ctx: &ctx, size: size, t: t)
                    }

                    if scanning {
                        sweep(radius: radius, t: t)
                    }

                    hub(at: t)

                    ForEach(Array(devices.enumerated()), id: \.element.id) { index, device in
                        let p = spot(index: index, radius: radius)
                        DeviceNode(device: device, phase: t) { onTap(device) }
                            .position(x: mid.x + p.x, y: mid.y + p.y)
                    }
                }
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .animation(.spring(response: 0.55, dampingFraction: 0.72), value: devices.map(\.id))
    }

    // MARK: - 底

    /// 环和涟漪都画在一张 Canvas 上：几十条描边交给 SwiftUI 的视图树
    /// 每帧都要重新 diff，画进 Canvas 就只是一次绘制。
    private func drawBase(ctx: inout GraphicsContext, size: CGSize, t: Double) {
        let c = CGPoint(x: size.width / 2, y: size.height / 2)
        let R = min(size.width, size.height) / 2

        // 只留最外那一圈，而且淡到几乎看不见 —— 它的作用仅仅是给节点一个边界感。
        //
        // 之前画了四个静态环，加上三圈涟漪就是七条线同时在屏幕上，
        // 看着像地图上的距离刻度，把主角（设备）全压住了。
        let edge: CGFloat = 0.88
        let edgeRect = CGRect(x: c.x - R * edge, y: c.y - R * edge,
                              width: R * edge * 2, height: R * edge * 2)
        ctx.stroke(Path(ellipseIn: edgeRect),
                   with: .color(.white.opacity(0.045)), lineWidth: 0.8)

        guard scanning else { return }

        // 水波纹：从中心一圈圈荡开。线要粗一点、淡一点、慢一点，
        // 细而清晰的圆是「刻度」，粗而朦胧的圆才是「波」。
        let period = 4.6
        for k in 0..<3 {
            let phase = ((t / period) + Double(k) / 3).truncatingRemainder(dividingBy: 1)
            // 用 pow 让它出去得先快后慢，像真的水波往外摊
            let eased = pow(phase, 0.72)
            let r = R * CGFloat(0.10 + 0.82 * eased)
            // 出场先亮起来再散掉，不是一冒头就最亮然后线性变淡
            let fade = sin(phase * .pi)
            let rect = CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)
            ctx.stroke(Path(ellipseIn: rect),
                       with: .color(Color.brand.opacity(0.16 * fade)),
                       lineWidth: 1.6)
        }
    }

    // MARK: - 扫描光

    private func sweep(radius: CGFloat, t: Double) -> some View {
        // 每 4.2 秒一圈。再快显得急躁，再慢会像没在动
        let deg = (t / 4.2).truncatingRemainder(dividingBy: 1) * 360

        return Circle()
            .fill(
                AngularGradient(
                    // 前缘最亮，往后平滑散掉。一刀切的边缘看着像块饼图。
                    gradient: Gradient(stops: [
                        .init(color: Color.brand.opacity(0.16), location: 0.000),
                        .init(color: Color.brand.opacity(0.09), location: 0.030),
                        .init(color: Color.brand.opacity(0.035), location: 0.075),
                        .init(color: Color.brand.opacity(0.00), location: 0.150),
                        .init(color: Color.brand.opacity(0.00), location: 1.000),
                    ]),
                    center: .center
                )
            )
            .frame(width: radius * 1.72, height: radius * 1.72)
            .overlay(
                // 前缘那条线。有它才像「刚扫过这里」，而不是一片光在打转
                Rectangle()
                    .fill(
                        LinearGradient(
                            colors: [Color.brand.opacity(0.55), Color.brand.opacity(0)],
                            startPoint: .leading, endPoint: .trailing
                        )
                    )
                    .frame(width: radius * 0.86, height: 1)
                    .offset(x: radius * 0.43)
            )
            .rotationEffect(.degrees(deg))
            .blendMode(.plusLighter)
    }

    // MARK: - 中心

    private func hub(at t: Double) -> some View {
        // 呼吸用 sin 算，不用 repeatForever —— 视图重建也不会停
        let breath = (sin(t * 1.15) + 1) / 2

        return ZStack {
            Circle()
                .fill(Color.brand.opacity(0.10 + 0.10 * breath))
                .frame(width: 76 + 14 * breath, height: 76 + 14 * breath)
                .blur(radius: 10)

            Circle()
                .fill(LinearGradient(colors: [.brand, .brandDeep],
                                     startPoint: .topLeading, endPoint: .bottomTrailing))
                .frame(width: 56, height: 56)
                .overlay(
                    // 上缘一道高光，让它看着是个球而不是一块色卡
                    Circle()
                        .strokeBorder(
                            LinearGradient(colors: [.white.opacity(0.45), .clear],
                                           startPoint: .top, endPoint: .bottom),
                            lineWidth: 1
                        )
                )
                .shadow(color: Color.brand.opacity(0.5), radius: 16, y: 4)

            Image(systemName: "iphone")
                .font(.system(size: 23, weight: .medium))
                .foregroundStyle(.white)
        }
    }

    // MARK: - 布局

    /// 黄金角撒点：相邻的两个节点永远不会挨在一起，
    /// 而且第 n 个的位置只跟 n 有关，刷新后不会乱跳。
    private func spot(index: Int, radius: CGFloat) -> CGPoint {
        let angle = Double(index) * 137.507 - 90   // -90 让第一个落在正上方
        // 三档半径轮着来。最内一档也要离中心足够远 ——
        // 挨着中心那个「本机」图标的话，两个圆看起来像连在一起的一个控件
        let steps: [CGFloat] = [0.60, 0.80, 0.45]
        let r = radius * steps[index % steps.count]
        let rad = angle * Double.pi / 180
        return CGPoint(x: CGFloat(cos(rad)) * r, y: CGFloat(sin(rad)) * r)
    }
}

// MARK: - 节点

private struct DeviceNode: View {
    let device: Desktop
    /// 全局时间，用来驱动光圈的呼吸
    let phase: Double
    var onTap: () -> Void

    @State private var appeared = false
    @State private var pressed = false

    var body: some View {
        // 每台设备的呼吸错开一点，一排节点同时明暗会显得很机械
        let offset = Double(abs(device.id.hashValue % 100)) / 16
        let breath = (sin(phase * 1.4 + offset) + 1) / 2

        VStack(spacing: 7) {
            ZStack {
                // 一圈很淡的光晕，跟着呼吸。让节点看着是「活的信号」
                Circle()
                    .fill(Color.brand.opacity(0.10 + 0.08 * breath))
                    .frame(width: 74 + 8 * breath, height: 74 + 8 * breath)
                    .blur(radius: 9)

                Circle()
                    .fill(.ultraThinMaterial)
                    .environment(\.colorScheme, .dark)
                    .frame(width: 64, height: 64)
                    .overlay(Circle().strokeBorder(Color.white.opacity(0.14), lineWidth: 0.8))

                ArtIcon(art: art, size: 42)

                if device.outboxCount > 0 {
                    // 有东西等着取。这是用户最该先看到的信息，放角标上
                    Text("\(min(device.outboxCount, 99))")
                        .font(.system(size: 10.5, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 19, height: 19)
                        .background(Circle().fill(Color.brand))
                        .overlay(Circle().strokeBorder(Color.bg0.opacity(0.8), lineWidth: 1.5))
                        .offset(x: 25, y: -23)
                }
            }

            Text(device.name)
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(Color.ink2)
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .truncationMode(.tail)
                .frame(maxWidth: 118)
        }
        .scaleEffect(appeared ? (pressed ? 0.92 : 1) : 0.4)
        .opacity(appeared ? 1 : 0)
        .contentShape(Rectangle())
        .onTapGesture {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            onTap()
        }
        // 按下去要有反馈，不然从点击到界面切换那一下没有任何交代
        .onLongPressGesture(minimumDuration: 0, maximumDistance: 40, perform: {}) { p in
            withAnimation(.spring(response: 0.25, dampingFraction: 0.7)) { pressed = p }
        }
        .accessibilityIdentifier("radar-node")
        .accessibilityLabel(device.name)
        // 它是用 onTapGesture 做的点击，不是 Button。
        // 少了这个 trait，旁白会把它读成一张图，UI 测试也找不到它。
        .accessibilityAddTraits(.isButton)
        .onAppear {
            // 从小「弹」出来，像信号刚被捕捉到
            withAnimation(.spring(response: 0.5, dampingFraction: 0.62)) { appeared = true }
        }
    }

    /// 名字里通常带着机型，据此挑素材。认不出来就当电脑。
    private var art: Art {
        if device.isAndroidPhone {
            return .android
        }
        return device.isPhone ? .phone : .laptop
    }
}
