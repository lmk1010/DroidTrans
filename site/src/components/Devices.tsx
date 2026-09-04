/// 设备外壳。
///
/// 用 devices.css（MIT，github.com/picturepan2/devices.css）里的 iPhone 14 Pro
/// 和 MacBook Pro 框架。自己用 CSS 画了几版都不像真机 —— 圆角比例、边框宽度、
/// 刘海和转轴的位置都得照着实物量，与其反复试错不如用已经量准的现成实现。
///
/// 框架本身是固定像素尺寸（iPhone 428×868、MacBook 740×434），
/// 这里用 transform 缩放，外层给一个按同样比例算出来的盒子占位 ——
/// transform 不影响布局，不给盒子的话周围元素会按原始尺寸排。

type Props = {
  src: string
  alt: string
  /// 缩放比例，1 是框架原始尺寸
  scale: number
  className?: string
}

function Stage({
  w,
  h,
  scale,
  className,
  children,
}: {
  w: number
  h: number
  scale: number
  className?: string
  children: React.ReactNode
}) {
  return (
    <div
      className={`dev-stage${className ? ` ${className}` : ''}`}
      style={{
        width: `${w * scale}px`,
        height: `${h * scale}px`,
        // @ts-expect-error 自定义属性
        '--s': scale,
      }}
    >
      {children}
    </div>
  )
}

export function MacBook({ src, alt, scale, className }: Props) {
  return (
    <Stage w={740} h={434} scale={scale} className={className}>
      <div className="device device-macbook-pro">
        <div className="device-frame">
          <img className="device-screen" src={src} alt={alt} loading="eager" fetchPriority="high" decoding="async" />
        </div>
        <div className="device-header" />
        <div className="device-power" />
      </div>
    </Stage>
  )
}

export function IPhone({ src, alt, scale, className }: Props) {
  return (
    <Stage w={428} h={868} scale={scale} className={className}>
      <div className="device device-iphone-14-pro device-silver">
        <div className="device-frame">
          <img className="device-screen" src={src} alt={alt} loading="eager" fetchPriority="high" decoding="async" />
        </div>
        <div className="device-stripe" />
        <div className="device-header" />
        <div className="device-sensors" />
        <div className="device-btns" />
        <div className="device-power" />
      </div>
    </Stage>
  )
}
