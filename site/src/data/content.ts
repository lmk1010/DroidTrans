/// 官网文案与结构数据。
///
/// 改文案、加功能、调平台差异，都只动这个文件，不用碰组件。

import type { ComponentType } from 'react'
import {
  IconUpload, IconDownload, IconFiles, IconLink, IconResume,
  IconShield, IconHotspot, IconShare,
} from '../components/Icons'

export type Feature = {
  icon: ComponentType<{ className?: string }>
  title: string
  body: string
}

export const features: Feature[] = [
  { icon: IconUpload, title: '手机 → 电脑', body: 'Wi-Fi 直传；也可以插 USB 线，由电脑直接把整个相册拉过去。' },
  { icon: IconDownload, title: '电脑 → 手机', body: '文件或整个文件夹拖进桌面窗口，手机上一键收走，目录结构照旧。' },
  { icon: IconFiles, title: '任意文件', body: '不只是照片视频。文档、压缩包、安装包，多大都能传。' },
  { icon: IconLink, title: '文字和链接', body: '两个方向都能发。手机发过来的自动进电脑剪贴板，直接就能粘。' },
  { icon: IconResume, title: '断点续传', body: '两个方向都支持。中断之后接着传，已经过去的部分不重复搬。' },
  { icon: IconShield, title: '配对才能连', body: '扫一次二维码换一个长期令牌。同一个局域网里，别人连不进来。' },
  { icon: IconHotspot, title: '没有路由器也行', body: '手机开热点，电脑连上去，照传不误。出差在酒店也不耽误事。' },
  { icon: IconShare, title: '系统分享菜单', body: '任何 App 里「分享 → 卓传」，直接送到电脑，不用先存一份。' },
]

export type Step = { title: string; body: string }

export const steps: Step[] = [
  { title: '电脑上打开卓传', body: '桌面端会显示一个二维码和局域网地址。两台设备连同一个 Wi-Fi。' },
  { title: '手机扫码配对', body: '扫那个二维码就完成了，不用手输。一台电脑只需要配一次。' },
  { title: '开始传', body: '选文件发过去，或者把电脑那边准备好的东西一键收走。' },
]

/// 平台能力差异。iOS 拿不到的能力必须在官网写清楚，
/// 让人装完才发现，比事先讲明白伤得多。
export type CompatRow = { name: string; android: boolean | string; ios: boolean | string }

export const compat: CompatRow[] = [
  { name: 'Wi-Fi 双向互传', android: true, ios: true },
  { name: '任意文件收发', android: true, ios: '经「文件」App' },
  { name: '文字 / 链接互传', android: true, ios: true },
  { name: '断点续传', android: true, ios: true },
  { name: '系统分享菜单', android: true, ios: true },
  { name: '扫码配对', android: true, ios: true },
  { name: 'USB 线直接拉相册', android: true, ios: '系统不开放' },
  { name: '手机开热点直连', android: true, ios: '需手动开热点' },
]

export type Platform = {
  id: 'macos' | 'android' | 'ios'
  name: string
  tagline: string
  requirement: string
  points: { text: string; off?: boolean }[]
  /// 还没上架的平台按钮置灰
  pending?: string
}

export const platforms: Platform[] = [
  {
    id: 'macos',
    name: 'macOS',
    tagline: '桌面端，传输的另一头',
    requirement: 'macOS 12 及以上　·　Apple Silicon',
    points: [
      { text: '拖进窗口即可发给手机' },
      { text: 'USB 直接拉取手机相册' },
      { text: '手机发来的文字自动进剪贴板' },
      { text: '一个独立程序，不装后台服务' },
    ],
  },
  {
    id: 'android',
    name: 'Android',
    tagline: '手机端',
    requirement: 'Android 7.0 及以上',
    points: [
      { text: '相册、任意文件、文字链接' },
      { text: 'USB 与 Wi-Fi 双通道' },
      { text: '系统分享菜单直达' },
      { text: '断点续传' },
    ],
  },
  {
    id: 'ios',
    name: 'iPhone / iPad',
    tagline: '手机端',
    requirement: 'iOS 15.5 及以上',
    points: [
      { text: '相册、文件、文字链接' },
      { text: '系统分享菜单直达' },
      { text: '断点续传' },
      { text: 'USB 直连（iOS 系统不开放）', off: true },
    ],
    pending: '即将上架 App Store',
  },
]

export const SUPPORT_EMAIL = 'support@mkstore.life'