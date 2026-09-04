package bonjour

import "strings"

import "testing"

// 主机名里只要还留着点，zeroconf 就会在后面再拼一次 domain，
// 通告出去的 SRV 会指向 "xxx.local.local." 这种不存在的名字。
// 客户端于是解析不出 IP，自动发现在所有平台上静默失效 ——
// 服务浏览得到，连接却永远卡在 preparing，日志里一个错都没有。
func TestHostLabelHasNoDots(t *testing.T) {
	got := hostLabel()
	if got == "" {
		t.Fatal("主机名不能为空")
	}
	if strings.Contains(got, ".") {
		t.Errorf("主机名不能带点，否则会拼成 xxx.local.local.：%q", got)
	}
	for _, r := range got {
		ok := (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') ||
			(r >= '0' && r <= '9') || r == '-'
		if !ok {
			t.Errorf("主机名里有 DNS 标签不允许的字符 %q：%q", r, got)
		}
	}
	if strings.HasPrefix(got, "-") || strings.HasSuffix(got, "-") {
		t.Errorf("主机名不能以连字符开头或结尾：%q", got)
	}
}
