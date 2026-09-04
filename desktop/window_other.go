//go:build !darwin

package main

func runNativeWindow(url string) {
	openUI(url)
	ch := make(chan struct{})
	<-ch
}

func requestAttention() {}

func notifyUser(title, body string) {}

// 非 macOS 暂无原生拖拽/选择面板，界面上用不到就不显示入口
func pickFiles() {}

func setFilesPickedHandler(fn func([]string)) {}

// 只有 macOS 有菜单栏，其他平台什么都不用做
func setMenuHandlers(status, address func() string, openOutput func()) {}
