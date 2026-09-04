package bonjour

import (
	"fmt"
	"os"
	"strings"

	"github.com/grandcat/zeroconf"
)

const ServiceType = "_droidtrans._tcp"

// Advertise 在局域网里通告桌面端，手机据此自动发现电脑。
//
// ips 是本机的局域网地址，用 app.LanIPs() 取。给空的话会退回让库自己查，
// 那条路在 macOS 的图形进程里是坏的 —— 见 hostLabel 的注释。
func Advertise(instance string, port int, ips []string) (func(), error) {
	instance = strings.TrimSpace(instance)
	if instance == "" {
		instance, _ = os.Hostname()
	}
	if instance == "" {
		instance = "DroidTrans"
	}

	txt := []string{"txtvers=1", "app=droidtrans"}

	var (
		server *zeroconf.Server
		err    error
	)
	if len(ips) > 0 {
		// 用 Proxy 版：主机名和 IP 都由我们自己给，不让库去猜。
		// 这样 A 记录一定指向真正能连上的那个地址。
		server, err = zeroconf.RegisterProxy(
			instance, ServiceType, "local.", port, hostLabel(), ips, txt, nil)
	} else {
		server, err = zeroconf.Register(instance, ServiceType, "local.", port, txt, nil)
	}
	if err != nil {
		return func() {}, err
	}
	fmt.Println("bonjour ", ServiceType, instance, ips)
	return server.Shutdown, nil
}

// hostLabel 返回一个能安全放进 SRV 记录的主机名，不带域名后缀。
//
// 不能把 os.Hostname() 的结果直接交给 zeroconf：在 macOS 的图形进程里
// 它返回的常常是 DHCP 给的 "xxx.local"（终端里却是干净的短名，所以这个问题
// 在开发时不容易撞见），而库会再拼一次 domain，结果是 "xxx.local.local."。
//
// 那是个不存在的主机名，SRV 指向它之后客户端解析不出 IP —— 自动发现在
// iOS、Android 上都会静默失效：服务能被浏览到，连接却永远停在 preparing。
// 用户看到的现象是「手机就是搜不到电脑」，而日志里什么错都没有。
func hostLabel() string {
	h, _ := os.Hostname()
	h = strings.TrimSuffix(h, ".")
	h = strings.TrimSuffix(h, ".local")

	// DNS 标签只允许字母、数字和连字符。中文机器名（"我的电脑"）
	// 或者带空格的名字直接放进 SRV 会让整条记录不可解析。
	var b strings.Builder
	for _, r := range h {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9', r == '-':
			b.WriteRune(r)
		default:
			b.WriteByte('-')
		}
	}
	s := strings.Trim(b.String(), "-")
	if s == "" {
		return "droidtrans"
	}
	return s
}
