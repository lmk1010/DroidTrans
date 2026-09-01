/// 文案。
///
/// 主力市场在海外，所以英文是第一语言，中文跟着系统走。
///
/// 用 Localizable.strings 而不是自己写字典：这样 App Store 会显示支持的语言、
/// 系统语言一变文案就跟着变，而且以后加语种只要多一个 .lproj 目录。
///
/// key 一律用「界面.含义」的形式，别用中文原文当 key ——
/// 文案一改 key 就跟着变，翻译全得重做。

import Foundation

/// 取一条本地化文案。
func L(_ key: String) -> String {
    NSLocalizedString(key, comment: "")
}

/// 带参数的。用 %@ 占位，顺序在不同语言里可能不同，
/// 所以 .strings 里要写成 %1$@ 这种带序号的形式。
func L(_ key: String, _ args: CVarArg...) -> String {
    String(format: NSLocalizedString(key, comment: ""), arguments: args)
}
