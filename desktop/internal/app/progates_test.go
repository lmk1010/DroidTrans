package app

// 付费权益的门控测试。
//
// 对比表上写出去的每一条都必须在这里有一条对应的验证：写了却没门控，
// 等于卖一个免费就有的东西（`auto_archive` 就是这么混进付费清单的）；
// 门控了却没测，等于没人知道它哪天会失效。
//
// 每条都验两个方向 —— 免费版拦住、Pro 放行。只验一边的话，
// 一个「永远返回 false」的 bug 能让两种用户都用不了，测试照样绿。

import (
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"droidtrans/internal/license"
	"droidtrans/internal/store"
)

// 用一个别名，免得测试文件里到处写全名
const licenseIncrementalSync = license.FeatureIncrementalSync

// 跨批次去重：Pro 才复用别处已有的同一个文件。
//
// 这条是对比表上的付费权益，之前一行测试都没有。
func TestDedupeReusesExistingFileOnlyForPro(t *testing.T) {
	dir := t.TempDir()
	st, err := store.Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()

	a := newTestApp(t)
	a.Store = st

	// 上一批里已经收过同名同大小的文件
	old := filepath.Join(dir, "old-batch", "IMG_1.JPG")
	if err := os.MkdirAll(filepath.Dir(old), 0o755); err != nil {
		t.Fatal(err)
	}
	body := []byte("same-bytes")
	if err := os.WriteFile(old, body, 0o644); err != nil {
		t.Fatal(err)
	}
	st.AddPhoto("dev", "old-batch", "IMG_1.JPG", old, int64(len(body)))

	dest := filepath.Join(dir, "new-batch", "IMG_1.JPG")
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		t.Fatal(err)
	}

	clearPro(t)
	if _, ok := a.tryReuse(dest, int64(len(body))); ok {
		t.Error("免费版不该跨批次复用 —— 那是 Pro 的去重能力")
	}

	grantPro(t)
	n, ok := a.tryReuse(dest, int64(len(body)))
	if !ok || n != int64(len(body)) {
		t.Errorf("Pro 应该能复用已有文件，实际 ok=%v n=%d", ok, n)
	}
	got, err := os.ReadFile(dest)
	if err != nil || string(got) != string(body) {
		t.Errorf("复用出来的内容不对：%v %q", err, got)
	}
}

// 已经完整落在目标路径上的文件，免费版也该跳过。
//
// 这是幂等，不是付费能力 —— 拿它收费会让「重传一次」变成付费功能，
// 那是砍基础体验。
func TestIdempotentSkipIsFreeForEveryone(t *testing.T) {
	clearPro(t)
	a := newTestApp(t)
	dir := t.TempDir()
	dest := filepath.Join(dir, "a.bin")
	body := []byte("hello")
	if err := os.WriteFile(dest, body, 0o644); err != nil {
		t.Fatal(err)
	}
	n, ok := a.tryReuse(dest, int64(len(body)))
	if !ok || n != int64(len(body)) {
		t.Errorf("同一个文件已经在目标位置了，免费版也该跳过：ok=%v n=%d", ok, n)
	}
}

// 单文件额度必须跟着授权状态走，而且是回调而不是快照 ——
// 用户当场激活就该立刻生效，不该等重启。
func TestFileSizeQuotaFollowsLicense(t *testing.T) {
	a := newTestApp(t)

	clearPro(t)
	if got := a.freeMaxFileSize(); got != FreeMaxFileSize {
		t.Errorf("免费版单文件上限应是 %d，实际 %d", FreeMaxFileSize, got)
	}

	grantPro(t)
	if got := a.freeMaxFileSize(); got != 0 {
		t.Errorf("Pro 不该有单文件上限，实际 %d", got)
	}
}

// 4 GB 这个数字不能被人随手改小。
//
// iPhone 4K60 约 400 MB/分钟，4 GB 正好十分钟。改成 2 GB 就只有五分钟，
// 随手拍段孩子的演出就超，会砸在普通用户身上。
func TestFreeFileQuotaIsFourGigabytes(t *testing.T) {
	if FreeMaxFileSize != 4<<30 {
		t.Errorf("免费单文件额度被改成了 %d —— 4 GB 是按 iPhone 4K60 的码率定的，改之前先算一遍",
			FreeMaxFileSize)
	}
}

// 导出额度同理：1000 而不是 200。
// 200 只是一次旅行的量，普通用户一趟就用完，那叫刚上手就被拦住。
func TestFreeExportQuotaIsOneThousand(t *testing.T) {
	if FreePhotosExport != 1000 {
		t.Errorf("免费导出额度被改成了 %d，对比表和官网写的都是 1000", FreePhotosExport)
	}
}

// 相册增量同步：Pro 才跳过已经传过的照片。
//
// 门控在 usb.go 的 startTransfer 里，直接测那条路要拉起 ADB，
// 所以这里测它依赖的那个判定 —— 免费版不该拿到「已存在」的路径，
// 于是 filterNewPhotos 根本不会被调用，整册重传。
func TestIncrementalSkipIsProOnly(t *testing.T) {
	dir := t.TempDir()
	st, err := store.Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()

	a := newTestApp(t)
	a.Store = st

	prev := filepath.Join(dir, "b1", "IMG_9.JPG")
	if err := os.MkdirAll(filepath.Dir(prev), 0o755); err != nil {
		t.Fatal(err)
	}
	body := []byte("nine")
	if err := os.WriteFile(prev, body, 0o644); err != nil {
		t.Fatal(err)
	}
	st.AddPhoto("dev-1", "b1", "IMG_9.JPG", prev, int64(len(body)))

	// 增量同步真正做的事：认出这张已经传过了
	if got := st.ExistingPathForDevice("dev-1", "IMG_9.JPG", int64(len(body))); got != prev {
		t.Fatalf("同一台设备上传过的照片没被认出来：%q", got)
	}

	clearPro(t)
	if a.Can(licenseIncrementalSync) {
		t.Error("免费版不该有增量同步")
	}
	grantPro(t)
	if !a.Can(licenseIncrementalSync) {
		t.Error("Pro 应该有增量同步")
	}
}

// 换设备必须把上一台的扫描结果扔掉。
//
// 不清的话，界面上还挂着上一台手机的相册；用户直接点「开始传输」，
// 拉的是旧设备上的路径 —— 轻则一片失败，重则从新设备上取到同名的别的文件。
func TestSelectDeviceClearsPreviousScan(t *testing.T) {
	a := newTestApp(t)
	a.albums = map[string]Album{
		"/sdcard/DCIM/Camera": {Name: "相机", TotalCount: 30},
	}
	a.scanStage = "扫描中"
	a.scanErr = "上一台的报错"
	before := a.scanID

	req := httptest.NewRequest("POST", "/api/select_device",
		strings.NewReader(`{"serial":"other-device"}`))
	w := httptest.NewRecorder()
	a.selectDevice(w, req)

	if w.Code != 200 {
		t.Fatalf("切换设备失败：%d", w.Code)
	}
	if len(a.albums) != 0 {
		t.Errorf("上一台的相册没清掉，还剩 %d 个 —— 用户会拿旧路径去传", len(a.albums))
	}
	if a.scanStage != "" || a.scanErr != "" {
		t.Errorf("上一台的扫描状态没清：stage=%q err=%q", a.scanStage, a.scanErr)
	}
	if a.scanID == before {
		t.Error("scanID 没往前走，还在跑的旧扫描回来时会覆盖新设备的结果")
	}
}
