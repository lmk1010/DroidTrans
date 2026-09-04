/// 设计基元。
///
/// 跟 macOS 客户端同一套调性：深色底、玻璃质感的卡片、一个蓝色作强调。
/// 三端看起来得像一个产品，而不是三个各自为政的 App。
///
/// 这里只固定颜色、间距、圆角这些数值 —— 每个页面各写各的 padding，
/// 界面就会开始显得松散，那是「功能都对但看着不专业」最常见的来源。

import SwiftUI

extension Color {
    /// 品牌蓝。与官网、桌面端同一个值。
    static let brand = Color(red: 0x49 / 255, green: 0x92 / 255, blue: 0xff / 255)
    static let brandDeep = Color(red: 0x2f / 255, green: 0x6f / 255, blue: 0xd0 / 255)

    /// 底色。不是纯黑 —— 纯黑上面放玻璃卡片会脏，也压不出层次
    static let bg0 = Color(red: 0x0a / 255, green: 0x0d / 255, blue: 0x14 / 255)
    static let bg1 = Color(red: 0x11 / 255, green: 0x16 / 255, blue: 0x22 / 255)

    static let ink = Color.white.opacity(0.94)
    static let ink2 = Color.white.opacity(0.58)
    static let ink3 = Color.white.opacity(0.36)

    /// 成功与出错。取值和桌面端 styles.css 里的 --ok / --danger 一致，
    /// 两端的「绿」「红」才是同一个绿和红。
    static let ok = Color(red: 0x8e / 255, green: 0xf0 / 255, blue: 0xc0 / 255)
    static let danger = Color(red: 0xff / 255, green: 0x7a / 255, blue: 0x7a / 255)

    static let stroke = Color.white.opacity(0.10)
    static let strokeSoft = Color.white.opacity(0.06)
    static let fill = Color.white.opacity(0.05)
}

enum Space {
    static let xs: CGFloat = 4
    static let s: CGFloat = 8
    static let m: CGFloat = 12
    static let l: CGFloat = 16
    static let xl: CGFloat = 22
    static let xxl: CGFloat = 32
    /// 屏幕左右留白。整个 App 只用这一个值，边缘才对得齐。
    static let gutter: CGFloat = 18
}

enum Radius {
    static let card: CGFloat = 18
    static let tile: CGFloat = 14
    static let pill: CGFloat = 999
}

// MARK: - 背景

/// 整个 App 的底。
///
/// 一块死板的深灰会显得很廉价，所以压两团很淡的光晕进去 ——
/// 静态、不动、几乎看不见，只是让大片深色不至于是一潭死水。
struct AppBackground: View {
    var body: some View {
        ZStack {
            LinearGradient(colors: [.bg1, .bg0],
                           startPoint: .top, endPoint: .bottom)

            Circle()
                .fill(Color.brand.opacity(0.16))
                .frame(width: 380, height: 380)
                .blur(radius: 130)
                .offset(x: -120, y: -260)

            Circle()
                .fill(Color.brandDeep.opacity(0.12))
                .frame(width: 320, height: 320)
                .blur(radius: 140)
                .offset(x: 150, y: 300)
        }
        .ignoresSafeArea()
    }
}

// MARK: - 玻璃卡片

struct GlassCard: ViewModifier {
    var radius: CGFloat = Radius.card

    func body(content: Content) -> some View {
        content
            .background(
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .fill(.ultraThinMaterial)
                    .environment(\.colorScheme, .dark)
            )
            .overlay(
                // 一条细边。玻璃没有边的话会糊在背景里，看不出是一块面
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .strokeBorder(Color.stroke, lineWidth: 0.7)
            )
            .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

extension View {
    func glass(radius: CGFloat = Radius.card) -> some View {
        modifier(GlassCard(radius: radius))
    }
}

// MARK: - 3D 素材

/// 出图生成的 3D 图标。名字对应 Assets.xcassets 里的 imageset。
enum Art: String {
    case photos, files, text, inbox, link, shield, laptop, phone
    /// 安卓机：手机屏上一个小机器人
    case android
    /// App 自己的标志，主界面顶上那枚
    case logo
    /// 手机往电脑传东西的场景，用在主界面空着的时候
    case sync
    /// 空箱子。「电脑那边还没放东西」
    case dropbox
    /// 完成。和桌面端用同一张，两端的「done」得是同一个东西
    case done
}

struct ArtIcon: View {
    let art: Art
    var size: CGFloat = 56

    var body: some View {
        Image(art.rawValue)
            .resizable()
            .aspectRatio(contentMode: .fit)
            .frame(width: size, height: size)
            // 让它像是浮在卡片上方，而不是贴在上面
            .shadow(color: Color.brand.opacity(0.35), radius: 12, y: 6)
    }
}

// MARK: - 按钮

struct PrimaryButtonStyle: ButtonStyle {
    var enabled = true

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 17, weight: .semibold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .frame(height: 52)
            .background(
                RoundedRectangle(cornerRadius: Radius.tile, style: .continuous)
                    .fill(enabled
                          ? LinearGradient(colors: [.brand, .brandDeep],
                                           startPoint: .top, endPoint: .bottom)
                          : LinearGradient(colors: [.fill, .fill],
                                           startPoint: .top, endPoint: .bottom))
            )
            .opacity(enabled ? 1 : 0.5)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.spring(response: 0.3, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

struct GhostButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 15, weight: .medium))
            .foregroundStyle(Color.ink)
            .frame(maxWidth: .infinity)
            .frame(height: 46)
            .background(
                RoundedRectangle(cornerRadius: Radius.tile, style: .continuous)
                    .fill(Color.white.opacity(configuration.isPressed ? 0.10 : 0.06))
            )
            .overlay(
                RoundedRectangle(cornerRadius: Radius.tile, style: .continuous)
                    .strokeBorder(Color.stroke, lineWidth: 0.7)
            )
    }
}

// MARK: - 小工具

/// 分区标题。左边一行字，右边可以挂个动作。
struct SectionHeader<Trailing: View>: View {
    let title: String
    var count: Int?
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: Space.s) {
            Text(title)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Color.ink2)
                .textCase(.uppercase)
            if let count, count > 0 {
                Text("\(count)")
                    .font(.system(size: 12, weight: .semibold, design: .rounded))
                    .foregroundStyle(Color.brand)
            }
            Spacer()
            trailing
        }
    }
}

extension SectionHeader where Trailing == EmptyView {
    init(_ title: String, count: Int? = nil) {
        self.init(title: title, count: count) { EmptyView() }
    }
}

/// 把字节数说成人话。
func humanSize(_ bytes: Int64) -> String {
    if bytes < 0 { return "—" }
    let units = ["B", "KB", "MB", "GB", "TB"]
    var v = Double(bytes)
    var i = 0
    while v >= 1024 && i < units.count - 1 {
        v /= 1024
        i += 1
    }
    // 到 MB 以上给一位小数，再多就是噪音了
    return i <= 1 ? "\(Int(v.rounded())) \(units[i])" : String(format: "%.1f %@", v, units[i])
}
