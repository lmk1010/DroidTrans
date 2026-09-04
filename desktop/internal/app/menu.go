package app

import (
	"fmt"
	"os/exec"
	"runtime"
)

// MenuStatusLine 是菜单栏顶上那行只读状态。
//
// 目的是让人瞟一眼就知道现在通不通 —— 不用为了确认「手机连上没有」
// 特意把窗口翻出来。所以这句话要能独立看懂，别只写个数字。
func (a *App) MenuStatusLine() string {
	// devices 是被多个 HTTP 请求同时写的，读它必须拿锁
	a.mu.Lock()
	devices := len(a.devices)
	a.mu.Unlock()
	pending, _ := 0, int64(0)
	if a.Out != nil {
		pending, _ = a.Out.Stats()
	}

	switch {
	case devices > 0 && pending > 0:
		return fmt.Sprintf("%d 台手机在线 · %d 件待取", devices, pending)
	case devices > 0:
		return fmt.Sprintf("%d 台手机在线", devices)
	case pending > 0:
		return fmt.Sprintf("%d 件等手机来取", pending)
	default:
		return "等待手机连接"
	}
}

// MenuAddressLine 是手机端要输的那个地址。点一下能复制。
func (a *App) MenuAddressLine() string {
	ips := LanIPs()
	if len(ips) == 0 {
		return ""
	}
	return fmt.Sprintf("%s:%d", ips[0], HTTPPort)
}

// OpenOutputFolder 在访达里打开收到的文件所在目录。
func (a *App) OpenOutputFolder() {
	if a.OutputDir == "" {
		return
	}
	if runtime.GOOS == "darwin" {
		_ = exec.Command("open", a.OutputDir).Start()
	}
}
