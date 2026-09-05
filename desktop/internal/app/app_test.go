package app

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"droidtrans/internal/store"
)

func TestAllowedHost(t *testing.T) {
	ok := []string{"", "localhost", "localhost:9500", "127.0.0.1:9500", "192.168.1.7:9500", "[::1]:9500"}
	for _, h := range ok {
		if !allowedHost(h) {
			t.Errorf("allowedHost(%q) = false, 想要 true", h)
		}
	}
	bad := []string{"evil.com", "evil.com:9500", "rebind.attacker.net:9500", "droidtrans.local:9500"}
	for _, h := range bad {
		if allowedHost(h) {
			t.Errorf("allowedHost(%q) = true, 应当挡掉（DNS rebinding）", h)
		}
	}
}

func TestAllowedOrigin(t *testing.T) {
	if !allowedOrigin("http://127.0.0.1:9500") || !allowedOrigin("http://localhost:9500") {
		t.Error("本机来源应放行")
	}
	if allowedOrigin("https://evil.com") || allowedOrigin("http://evil.com:9500") {
		t.Error("外部网站来源应挡掉（CSRF）")
	}
}

func TestSafeSegment(t *testing.T) {
	for _, s := range []string{"..", ".", "", "a/b", "a\\b", "/abs"} {
		if _, ok := safeSegment(s); ok {
			t.Errorf("safeSegment(%q) 不该通过", s)
		}
	}
	if v, ok := safeSegment("20260819_101112"); !ok || v != "20260819_101112" {
		t.Errorf("正常批次号被拒: %q %v", v, ok)
	}
}

func TestSafeUnder(t *testing.T) {
	root := t.TempDir()
	if _, ok := safeUnder(root, "../../etc/passwd"); ok {
		t.Error("safeUnder 放过了越界路径")
	}
	got, ok := safeUnder(root, "dev/batch/a.jpg")
	if !ok || got != filepath.Join(root, "dev", "batch", "a.jpg") {
		t.Errorf("got %q ok=%v", got, ok)
	}
}

func newTestApp(t *testing.T) *App {
	t.Helper()
	dir := t.TempDir()
	return &App{
		OutputDir: dir,
		wifiOut:   dir,
		devices:   map[string]*deviceInfo{},
		sessions:  map[string]*uploadSession{},
		albums:    map[string]Album{},
	}
}

func TestSafeLocal(t *testing.T) {
	a := newTestApp(t)
	if _, ok := a.safeLocal(filepath.Join(a.OutputDir, "..", "secret.txt")); ok {
		t.Error("safeLocal 放过了输出目录之外的路径")
	}
	if _, ok := a.safeLocal("/etc/hosts"); ok {
		t.Error("safeLocal 放过了系统文件")
	}
	if _, ok := a.safeLocal(filepath.Join(a.OutputDir, "dev", "a.jpg")); !ok {
		t.Error("safeLocal 拒绝了输出目录内的路径")
	}
}

func TestDeletePhotoRefusesOutsidePaths(t *testing.T) {
	a := newTestApp(t)
	outside := filepath.Join(t.TempDir(), "keepme.txt")
	if err := os.WriteFile(outside, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	r := httptest.NewRequest("POST", "/api/wifi/delete_photo", strings.NewReader(`{"path":"`+outside+`"}`))
	w := httptest.NewRecorder()
	a.wifiDeletePhoto(w, r)
	if w.Code != 400 {
		t.Errorf("状态码 %d，想要 400", w.Code)
	}
	if _, err := os.Stat(outside); err != nil {
		t.Error("输出目录外的文件被删掉了")
	}
}

func TestDeleteBatchRefusesTraversal(t *testing.T) {
	a := newTestApp(t)
	victim := filepath.Join(a.OutputDir, "sibling")
	if err := os.MkdirAll(victim, 0o755); err != nil {
		t.Fatal(err)
	}
	r := httptest.NewRequest("POST", "/api/wifi/delete_batch",
		strings.NewReader(`{"device_id":"..","batch_id":"sibling"}`))
	w := httptest.NewRecorder()
	a.wifiDeleteBatch(w, r)
	if w.Code != 400 {
		t.Errorf("状态码 %d，想要 400", w.Code)
	}
	if _, err := os.Stat(victim); err != nil {
		t.Error("../ 批次删除把别的目录删了")
	}
}

func TestAlbumTransferRequiresPro(t *testing.T) {
	a := newTestApp(t)
	r := httptest.NewRequest("POST", "/api/transfer",
		strings.NewReader(`{"selection":{"albums":["/sdcard/DCIM/Camera"],"singles":[]}}`))
	w := httptest.NewRecorder()

	a.startTransfer(w, r)

	if w.Code != http.StatusPaymentRequired {
		t.Fatalf("状态码 %d，想要 %d", w.Code, http.StatusPaymentRequired)
	}
	var body map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body["error"] != "请先激活 Pro" {
		t.Fatalf("错误信息 %v，想要 Pro 提示", body["error"])
	}
}

func TestFilterNewPhotos(t *testing.T) {
	photos, skipped := filterNewPhotos(
		[]string{"/sdcard/DCIM/Camera/a.jpg", "/sdcard/DCIM/Camera/b.jpg"},
		func(remote string) int64 {
			if strings.HasSuffix(remote, "a.jpg") {
				return 10
			}
			return 20
		},
		func(name string, size int64) string {
			if name == "a.jpg" && size == 10 {
				return "/archive/a.jpg"
			}
			return ""
		},
	)
	if skipped != 1 {
		t.Fatalf("跳过数量 = %d，想要 1", skipped)
	}
	if len(photos) != 1 || photos[0] != "/sdcard/DCIM/Camera/b.jpg" {
		t.Fatalf("保留文件不对: %+v", photos)
	}
}

func TestGuardBlocksForeignOrigin(t *testing.T) {
	a := newTestApp(t)
	h := a.withGuard(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(200)
	}))
	r := httptest.NewRequest("POST", "http://127.0.0.1:9500/api/history/clear", nil)
	r.Header.Set("Origin", "https://evil.com")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != http.StatusForbidden {
		t.Errorf("外部网站请求返回 %d，应当 403", w.Code)
	}

	r2 := httptest.NewRequest("POST", "http://127.0.0.1:9500/api/history/clear", nil)
	w2 := httptest.NewRecorder()
	h.ServeHTTP(w2, r2)
	if w2.Code != 200 {
		t.Errorf("手机原生请求（无 Origin）返回 %d，应当放行", w2.Code)
	}
}

func TestGuardBlocksRebindHost(t *testing.T) {
	a := newTestApp(t)
	h := a.withGuard(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(200)
	}))
	r := httptest.NewRequest("GET", "/api/health", nil)
	r.Host = "rebind.attacker.net:9500"
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != http.StatusForbidden {
		t.Errorf("域名 Host 返回 %d，应当 403", w.Code)
	}
}

func TestRemoteToLocal(t *testing.T) {
	got := remoteToLocal("/sdcard/DCIM/Camera/IMG_1.jpg", "/out")
	if got != filepath.Join("/out", "DCIM", "Camera", "IMG_1.jpg") {
		t.Errorf("got %q", got)
	}
	got = remoteToLocal("/storage/emulated/0/Pictures/a.png", "/out")
	if got != filepath.Join("/out", "Pictures", "a.png") {
		t.Errorf("got %q", got)
	}
}

func TestHumanBytes(t *testing.T) {
	cases := map[int64]string{512: "512 B", 2048: "2.0 KB", 5 << 20: "5.0 MB", 3 << 30: "3.00 GB"}
	for in, want := range cases {
		if got := humanBytes(in); got != want {
			t.Errorf("humanBytes(%d) = %q want %q", in, got, want)
		}
	}
}

func TestTransferPaceETA(t *testing.T) {
	started := time.Now().Add(-10 * time.Second)
	speed, eta, elapsed := transferPace(10<<20, 20<<20, 1, 2, started, 1.0)
	if elapsed < 9 || elapsed > 11 {
		t.Errorf("elapsed = %v", elapsed)
	}
	if speed <= 0 || eta <= 0 {
		t.Errorf("speed=%v eta=%v", speed, eta)
	}
	if _, _, e := transferPace(0, 0, 0, 0, time.Time{}, 0); e != 0 {
		t.Error("未开始时应返回零值")
	}
}

func TestSettingsRoundTrip(t *testing.T) {
	dir := t.TempDir()
	a := &App{settingsPath: filepath.Join(dir, "settings.json")}
	a.saveSettings("/tmp/somewhere")
	if got := loadSettings(a.settingsPath); got.OutputDir != "/tmp/somewhere" {
		t.Errorf("重启后输出目录没留住: %q", got.OutputDir)
	}
}

func TestInboxElapsedStopsAtLastFile(t *testing.T) {
	a := newTestApp(t)
	a.Store = nil // 这条路径不写库
	start := time.Now().Add(-40 * time.Second)
	a.inbox = inboxState{
		Receiving: false,
		DeviceID:  "phone",
		BatchID:   "b1",
		Completed: 3,
		Total:     3,
		Bytes:     3 << 20,
		Started:   start,
		LastAt:    start.Add(3 * time.Second), // 最后一张是 3 秒时收到的
	}
	r := httptest.NewRequest("GET", "/api/inbox", nil)
	w := httptest.NewRecorder()
	a.inboxStatus(w, r)

	var out map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	elapsed, _ := out["elapsed_sec"].(float64)
	if elapsed < 2.5 || elapsed > 3.5 {
		t.Errorf("耗时 = %.1fs，应当是最后一张收到时的 3s，而不是干等到现在的 40s", elapsed)
	}
	speed, _ := out["speed_mbps"].(float64)
	if speed < 0.5 {
		t.Errorf("均速 = %.2f MB/s，耗时算错会把它压下去", speed)
	}
}

func TestOutboxAddFileAndFolder(t *testing.T) {
	dir := t.TempDir()
	if err := os.MkdirAll(filepath.Join(dir, "sub"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "a.txt"), []byte("hello"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "sub", "b.txt"), []byte("world!"), 0o644); err != nil {
		t.Fatal(err)
	}

	o := NewOutbox()
	n, err := o.Add(dir)
	if err != nil {
		t.Fatal(err)
	}
	if n != 2 {
		t.Fatalf("展开目录得到 %d 个文件，想要 2", n)
	}

	base := filepath.Base(dir)
	rels := map[string]bool{}
	for _, it := range o.List() {
		rels[it.Rel] = true
	}
	if !rels[base+"/a.txt"] || !rels[base+"/sub/b.txt"] {
		t.Errorf("相对路径没保住目录结构: %v", rels)
	}

	count, size := o.Stats()
	if count != 2 || size != 11 {
		t.Errorf("count=%d size=%d，想要 2 / 11", count, size)
	}
}

func TestOutboxDedupAndRemove(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "x.bin")
	if err := os.WriteFile(p, []byte("123"), 0o644); err != nil {
		t.Fatal(err)
	}
	o := NewOutbox()
	_, _ = o.Add(p)
	_, _ = o.Add(p) // 同一个文件加两次只算一条
	if c, _ := o.Stats(); c != 1 {
		t.Fatalf("重复添加变成了 %d 条", c)
	}
	id := o.List()[0].ID
	o.Remove(id)
	if c, _ := o.Stats(); c != 0 {
		t.Errorf("删除没生效")
	}
}

func TestOutboxFileServesAndMarksTaken(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "hello.txt")
	if err := os.WriteFile(p, []byte("payload"), 0o644); err != nil {
		t.Fatal(err)
	}
	a := newTestApp(t)
	a.Out = NewOutbox()
	if _, err := a.Out.Add(p); err != nil {
		t.Fatal(err)
	}
	id := a.Out.List()[0].ID

	r := httptest.NewRequest("GET", "/api/outbox/file/"+id, nil)
	r.SetPathValue("id", id)
	w := httptest.NewRecorder()
	a.outboxFile(w, r)

	if w.Code != 200 {
		t.Fatalf("状态码 %d", w.Code)
	}
	if w.Body.String() != "payload" {
		t.Errorf("内容不对: %q", w.Body.String())
	}
	if it, _ := a.Out.Get(id); it.Taken != 1 {
		t.Errorf("没标记成已取走")
	}
}

func TestOutboxListFlagsMissingFile(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "gone.txt")
	if err := os.WriteFile(p, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	a := newTestApp(t)
	a.Out = NewOutbox()
	_, _ = a.Out.Add(p)
	// 登记之后文件被删掉/移走：清单里要标出来，而不是等手机取的时候才失败
	if err := os.Remove(p); err != nil {
		t.Fatal(err)
	}

	w := httptest.NewRecorder()
	a.outboxList(w, httptest.NewRequest("GET", "/api/outbox", nil))
	var out map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	items, _ := out["items"].([]any)
	if len(items) != 1 {
		t.Fatalf("清单里有 %d 条", len(items))
	}
	first, _ := items[0].(map[string]any)
	if size, _ := first["size"].(float64); size >= 0 {
		t.Errorf("文件已不在，size 应当标成 -1，实际 %v", size)
	}
}

func TestPairingGate(t *testing.T) {
	dir := t.TempDir()
	p := LoadPairing(filepath.Join(dir, "pairing.json"))
	if !p.Required {
		t.Error("默认应当要求配对")
	}
	_, code, _ := p.Snapshot()
	if len(code) != 6 {
		t.Fatalf("配对码 %q 不是六位", code)
	}

	if _, ok := p.Pair("000000", "d1", "手机"); ok {
		t.Error("错误的配对码不该发令牌")
	}
	tok, ok := p.Pair(code, "d1", "手机")
	if !ok || tok == "" {
		t.Fatal("正确的配对码没换到令牌")
	}
	if !p.Valid(tok) {
		t.Error("刚发的令牌应当有效")
	}
	if p.Valid("whatever") {
		t.Error("随便一个字符串不该被当成令牌")
	}

	// 重新加载后仍然认这个令牌
	again := LoadPairing(filepath.Join(dir, "pairing.json"))
	if !again.Valid(tok) {
		t.Error("重启后配对关系丢了")
	}

	again.Revoke(tok[:6])
	if again.Valid(tok) {
		t.Error("撤销之后还认")
	}
}

func TestAllowRequestRules(t *testing.T) {
	a := newTestApp(t)
	a.Pair = LoadPairing(filepath.Join(t.TempDir(), "pairing.json"))
	_, code, _ := a.Pair.Snapshot()
	tok, _ := a.Pair.Pair(code, "d", "phone")

	lan := func(path, token string) *http.Request {
		r := httptest.NewRequest("POST", path, nil)
		r.RemoteAddr = "192.168.1.44:51234"
		if token != "" {
			r.Header.Set("X-DT-Token", token)
		}
		return r
	}

	if a.allowRequest(lan("/api/inbox", "")) {
		t.Error("局域网里没令牌的请求应当被挡")
	}
	if !a.allowRequest(lan("/api/inbox", tok)) {
		t.Error("带正确令牌的请求应当放行")
	}
	// 页面本身要放行：手机扫码进来时还没配对，落地页正是给它看怎么配对的
	for _, p := range []string{"/", "/wifi", "/app.js", "/icon.svg"} {
		r := httptest.NewRequest("GET", p, nil)
		r.RemoteAddr = "192.168.1.44:51234"
		if !a.allowRequest(r) {
			t.Errorf("%s 是页面资源，应当放行", p)
		}
	}
	// 发现类接口必须开着，否则手机连「这台电脑在不在」都问不出来
	for _, p := range []string{"/api/health", "/api/wifi/info", "/api/fast/caps", "/api/pair"} {
		if !a.allowRequest(lan(p, "")) {
			t.Errorf("%s 应当保持开放", p)
		}
	}
	// 桌面端界面跑在本机，不该被自己的配对挡住
	local := httptest.NewRequest("POST", "/api/history/clear", nil)
	local.RemoteAddr = "127.0.0.1:5555"
	if !a.allowRequest(local) {
		t.Error("本机界面被挡了")
	}

	a.Pair.SetRequired(false)
	if !a.allowRequest(lan("/api/inbox", "")) {
		t.Error("关掉配对后应当一律放行")
	}
}

func TestHotspotNetRecognised(t *testing.T) {
	yes := []string{"192.168.43.17", "192.168.49.2", "172.20.10.5"}
	for _, ip := range yes {
		if !hotspotNet(ip) {
			t.Errorf("%s 应当被认作手机热点网段", ip)
		}
	}
	no := []string{"192.168.1.7", "10.0.0.3", "172.21.10.5", "192.168.430.1"}
	for _, ip := range no {
		if hotspotNet(ip) {
			t.Errorf("%s 不该被认作热点网段", ip)
		}
	}
}

func TestPhoneBrowserDetection(t *testing.T) {
	phone := []string{
		"Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36",
		"Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Mobile/15E148",
	}
	for _, ua := range phone {
		if !isPhoneBrowser(ua) {
			t.Errorf("应当识别为手机浏览器: %s", ua)
		}
	}
	notPhone := []string{
		"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Safari/605.1.15",
		"okhttp/4.12.0", // App 自己的请求
		"Dalvik/2.1.0 (Linux; U; Android 14)",
		"",
	}
	for _, ua := range notPhone {
		if isPhoneBrowser(ua) {
			t.Errorf("不该识别为手机浏览器: %q", ua)
		}
	}
}

func TestBatchMissingMatchesPrune(t *testing.T) {
	a := newTestApp(t)
	st, err := store.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	a.Store = st

	// 一批文件还在
	liveDir := filepath.Join(a.OutputDir, "dev", "live")
	if err := os.MkdirAll(liveDir, 0o755); err != nil {
		t.Fatal(err)
	}
	livePath := filepath.Join(liveDir, "a.jpg")
	if err := os.WriteFile(livePath, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	_ = st.SaveBatch(store.Batch{DeviceID: "dev", BatchID: "live", PhotoCount: 1})
	st.AddPhoto("dev", "live", "a.jpg", livePath, 1)

	// 一批只剩数据库记录，文件早没了
	_ = st.SaveBatch(store.Batch{DeviceID: "dev", BatchID: "dead", PhotoCount: 1})
	st.AddPhoto("dev", "dead", "b.jpg", filepath.Join(a.OutputDir, "dev", "dead", "b.jpg"), 1)

	if a.batchMissing("dev", "live", liveDir) {
		t.Error("文件还在的批次被判成丢失")
	}
	if !a.batchMissing("dev", "dead", filepath.Join(a.OutputDir, "dev", "dead")) {
		t.Error("只剩数据库记录的批次应当算丢失")
	}

	w := httptest.NewRecorder()
	a.histPruneMissing(w, httptest.NewRequest("POST", "/api/history/prune_missing", nil))
	var out map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	if n, _ := out["removed"].(float64); n != 1 {
		t.Errorf("清理掉 %v 批，想要 1（界面标几批就该清几批）", out["removed"])
	}
	rows, _ := st.Batches("dev")
	if len(rows) != 1 || rows[0].BatchID != "live" {
		t.Errorf("剩下的批次不对: %+v", rows)
	}
}

func TestOutboxClearAndPruneTaken(t *testing.T) {
	dir := t.TempDir()
	pa := filepath.Join(dir, "a.txt")
	pb := filepath.Join(dir, "b.txt")
	for _, p := range []string{pa, pb} {
		if err := os.WriteFile(p, []byte("x"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	o := NewOutbox()
	_, _ = o.Add(pa)
	_, _ = o.Add(pb)
	idA := ""
	for _, it := range o.List() {
		if it.Name == "a.txt" {
			idA = it.ID
		}
	}
	o.MarkTaken(idA)

	// 刚取走的先留着：用户还要看到「已取走」这个反馈
	if n := o.PruneTaken(2 * time.Hour); n != 0 {
		t.Errorf("刚取走就被清掉了 %d 条", n)
	}
	// 放久了自动消失，没取走的必须留着
	if n := o.PruneTaken(0); n != 1 {
		t.Errorf("过期清理清掉 %d 条，想要 1", n)
	}
	if c, _ := o.Stats(); c != 1 {
		t.Errorf("清理后剩 %d 条，想要 1", c)
	}

	o2 := NewOutbox()
	_, _ = o2.Add(pa)
	_, _ = o2.Add(pb)
	o2.MarkTaken(o2.List()[0].ID)
	if n := o2.ClearTaken(); n != 1 {
		t.Errorf("一键清理清掉 %d 条，想要 1", n)
	}
}

// 手机连上来只该响一次。心跳每几秒来一趟，跟着响就成了骚扰。
func TestDeviceOnlineNotifiesOnce(t *testing.T) {
	a := newTestApp(t)
	var got []string
	a.OnNotify = func(title, body string) { got = append(got, title) }

	a.touchDevice("dev-1", "小米 14")
	a.touchDevice("dev-1", "小米 14") // 心跳
	a.touchDevice("dev-1", "小米 14")
	if len(got) != 1 {
		t.Fatalf("同一台设备提醒了 %d 次，应该只有 1 次：%v", len(got), got)
	}
	if !strings.Contains(got[0], "小米 14") {
		t.Errorf("通知里没有设备名：%q", got[0])
	}

	a.touchDevice("dev-2", "iPhone 15 Pro")
	if len(got) != 2 {
		t.Fatalf("第二台设备没触发提醒：%v", got)
	}

	// 掉线被清掉之后再连上，应该重新提醒 —— 那确实是一次新的连接
	a.pruneStaleDevices(0)
	a.touchDevice("dev-1", "小米 14")
	if len(got) != 3 {
		t.Fatalf("重连后没有再提醒：%v", got)
	}
}

// 英文界面不该收到中文通知。
func TestDeviceOnlineFollowsUILang(t *testing.T) {
	a := newTestApp(t)
	var title, body string
	a.OnNotify = func(ti, bo string) { title, body = ti, bo }

	a.uiLang = "en"
	a.touchDevice("dev-1", "Pixel 8")
	if strings.ContainsAny(title+body, "已现在可以") {
		t.Errorf("英文界面收到了中文通知：%q / %q", title, body)
	}

	a.uiLang = "zh"
	a.touchDevice("dev-2", "小米 14")
	if !strings.Contains(title, "已连接") {
		t.Errorf("中文界面没拿到中文通知：%q", title)
	}
}

// 「关于」里那几个链接由电脑端代为打开。这套接口在局域网上是开着的，
// 一个不加限制的「打开这个网址」等于把这台电脑的浏览器交给任何能连上来的人。
func TestOpenURLOnlyAllowsOwnSite(t *testing.T) {
	a := newTestApp(t)
	for _, bad := range []string{
		"https://evil.example/x",
		"file:///etc/passwd",
		"http://droidtrans.mkstore.life/",          // 明文
		"https://droidtrans.mkstore.life.evil.com", // 后缀冒充
		"https://evil.com/?x=droidtrans.mkstore.life",
		"",
	} {
		w := httptest.NewRecorder()
		r := httptest.NewRequest("POST", "/api/open_url",
			strings.NewReader(`{"url":`+strconv.Quote(bad)+`}`))
		a.openURL(w, r)
		if w.Code == 200 {
			t.Errorf("放行了不该开的网址：%q", bad)
		}
	}
}
