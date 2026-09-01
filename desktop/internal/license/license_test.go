package license

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// 跨语言对拍：testdata 里的许可证是 Node 那边用真私钥签出来的。
// 这个测试保证两端对「签什么、怎么编码」的理解一致 ——
// 签名覆盖的是 base64 那串字符本身，不是解码后的 JSON。
// 任一端理解错了，客户端就永远验不过线上签发的许可证，
// 而这种错只有等到第一个用户投诉才会暴露。
func readToken(t *testing.T, name string) string {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", name))
	if err != nil {
		t.Fatalf("读不到 %s：%v（先在 licensing 里跑 mktoken 生成）", name, err)
	}
	return strings.TrimSpace(string(b))
}

func TestVerifyNodeIssued(t *testing.T) {
	lic, err := Verify(readToken(t, "lifetime.token"))
	if err != nil {
		t.Fatalf("Node 签发的许可证验不过：%v", err)
	}
	if lic.Product != "droidtrans-pro" || lic.Plan != "lifetime" {
		t.Fatalf("解出来的内容不对：%+v", lic)
	}
	if lic.Email != "crosscheck@example.com" {
		t.Fatalf("邮箱对不上：%q", lic.Email)
	}
	if lic.Expires != "" {
		t.Errorf("终生买断不该有到期时间，实际 %q", lic.Expires)
	}
	if lic.Expired() {
		t.Error("终生买断被判成过期了")
	}
}

func TestYearPlanHasExpiry(t *testing.T) {
	lic, err := Verify(readToken(t, "year.token"))
	if err != nil {
		t.Fatalf("年付许可证验不过：%v", err)
	}
	if lic.Expires == "" {
		t.Fatal("年付应当带到期时间")
	}
	if lic.Expired() {
		t.Error("刚签发的年付不该已过期")
	}
}

func TestExpiredIsReported(t *testing.T) {
	lic, err := Verify(readToken(t, "expired.token"))
	if err != ErrExpired {
		t.Fatalf("过期的许可证应当报 ErrExpired，得到 %v", err)
	}
	// 过期仍然要把内容带回来，界面才能提示「续期」而不是「无效」
	if lic == nil || lic.Email != "crosscheck@example.com" {
		t.Fatal("过期时也应当返回许可证内容，供界面提示续期")
	}
	if !lic.Expired() {
		t.Error("Expired() 判断不一致")
	}
}

func TestTamperedIsRejected(t *testing.T) {
	tok := readToken(t, "lifetime.token")
	dot := strings.IndexByte(tok, '.')

	cases := map[string]string{
		"改内容":         "eyJ2IjoyLCJwcm9kdWN0IjoiZHJvaWR0cmFucy1wcm8ifQ" + tok[dot:],
		"改签名":         tok[:dot+1] + strings.Repeat("A", len(tok)-dot-1),
		"少一段":         tok[:dot],
		"空串":          "",
		"只有点":         ".",
		"签名不是 base64": tok[:dot+1] + "!!!!",
	}
	for name, bad := range cases {
		if _, err := Verify(bad); err == nil {
			t.Errorf("%s：应当被拒，却通过了", name)
		}
	}
}

func TestSaveLoadRoundTrip(t *testing.T) {
	dir := t.TempDir()
	tok := readToken(t, "lifetime.token")

	if got, _ := Load(dir); got != nil {
		t.Fatal("空目录不该读出许可证")
	}
	if _, err := Save(dir, tok); err != nil {
		t.Fatalf("保存失败：%v", err)
	}
	lic, err := Load(dir)
	if err != nil || lic == nil || lic.Email != "crosscheck@example.com" {
		t.Fatalf("读回来的许可证不对：%+v (%v)", lic, err)
	}

	// 文件权限：里面有购买者邮箱，同机其他账户不该读到
	info, err := os.Stat(Path(dir))
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Errorf("权限应为 0600，实际 %o", info.Mode().Perm())
	}

	if err := Remove(dir); err != nil {
		t.Fatalf("注销失败：%v", err)
	}
	if got, _ := Load(dir); got != nil {
		t.Fatal("注销后不该还能读出来")
	}
	if err := Remove(dir); err != nil {
		t.Fatalf("重复注销应当无害：%v", err)
	}
}

func TestExpiredStillLoadsForRenewalPrompt(t *testing.T) {
	dir := t.TempDir()
	// 过期的许可证也要能落盘，否则用户续期前连「过期了」都看不到
	if _, err := Save(dir, readToken(t, "expired.token")); err != ErrExpired {
		t.Fatalf("保存过期许可证应当返回 ErrExpired，得到 %v", err)
	}
}

func TestGarbageOnDiskIsIgnored(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(Path(dir), []byte("这不是许可证"), 0o600); err != nil {
		t.Fatal(err)
	}
	if got, _ := Load(dir); got != nil {
		t.Fatal("坏文件应当当作未激活，而不是崩掉")
	}
}

// 授权必须存在固定的配置目录，不能跟着用户可改的输出目录走。
// 这条如果挂了，说明有人又把它挪回了输出目录 ——
// 那意味着用户改一次「保存到」，买的东西就没了。
func TestDefaultDirIsStableConfigLocation(t *testing.T) {
	d := DefaultDir()
	if d == "" {
		t.Fatal("默认目录不能为空")
	}
	if !strings.Contains(d, "DroidTrans") && !strings.Contains(d, ".droidtrans") {
		t.Errorf("默认目录看起来不对：%s", d)
	}
	// 不传目录时，读写必须都落在这个固定位置
	if Path("") != filepath.Join(d, "license.txt") {
		t.Errorf("空目录应当回落到默认位置，得到 %s", Path(""))
	}
}

func TestDeviceIDIsStableAndSeparateFromLicense(t *testing.T) {
	dir := t.TempDir()
	first, err := DeviceID(dir)
	if err != nil {
		t.Fatal(err)
	}
	second, err := DeviceID(dir)
	if err != nil {
		t.Fatal(err)
	}
	if first != second || len(first) != 32 {
		t.Fatalf("设备 ID 应稳定且为 32 位十六进制：%q / %q", first, second)
	}
	if err := Remove(dir); err != nil {
		t.Fatal(err)
	}
	afterRemove, err := DeviceID(dir)
	if err != nil {
		t.Fatal(err)
	}
	if afterRemove != first {
		t.Fatal("注销许可证不应改变设备 ID，否则重新激活会重复占名额")
	}
}

func TestDeviceBinding(t *testing.T) {
	if !deviceMatches(&License{}, "device-a") {
		t.Fatal("旧许可证没有设备绑定时必须保持兼容")
	}
	bound := &License{DeviceID: "device-a"}
	if !deviceMatches(bound, "device-a") {
		t.Fatal("绑定到本机的许可证应当通过")
	}
	if deviceMatches(bound, "device-b") {
		t.Fatal("复制到其他设备的许可证不应通过")
	}
}
