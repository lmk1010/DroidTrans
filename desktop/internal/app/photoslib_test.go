package app

// 接口层的测试。
//
// 导出引擎本身在 internal/photoslib 里测过了，这里盯的是接口层新增的两处风险：
// 付费门控有没有真的挡住，以及「用户在网格里挑了哪些」有没有被正确翻译成
// 要导的那批文件 —— 挑错了就是少导或多导用户的照片。

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"droidtrans/internal/license"
	"droidtrans/internal/photoslib"
)

// grantPro 给当前进程装一份终生授权。
func grantPro(t *testing.T) {
	t.Helper()
	licState.mu.Lock()
	prev := licState.lic
	licState.lic = &license.License{
		V: 2, Product: "droidtrans", Plan: "lifetime", Code: "DT-TEST",
		Issued: time.Now().UTC().Format(time.RFC3339),
	}
	licState.mu.Unlock()
	t.Cleanup(func() {
		licState.mu.Lock()
		licState.lic = prev
		licState.mu.Unlock()
	})
}

func clearPro(t *testing.T) {
	t.Helper()
	licState.mu.Lock()
	prev := licState.lic
	licState.lic = nil
	licState.mu.Unlock()
	t.Cleanup(func() {
		licState.mu.Lock()
		licState.lic = prev
		licState.mu.Unlock()
	})
}

// seedScan 直接把扫描结果塞进去，不依赖本机有没有照片图库。
func seedScan(t *testing.T, a *App, n int) []photoslib.Asset {
	t.Helper()
	src := t.TempDir()
	assets := make([]photoslib.Asset, 0, n)
	for i := 0; i < n; i++ {
		name := string(rune('a'+i)) + ".jpg"
		p := filepath.Join(src, name)
		if err := os.WriteFile(p, []byte("photo-"+name), 0o644); err != nil {
			t.Fatal(err)
		}
		assets = append(assets, photoslib.Asset{
			UUID: "uuid-" + name, OriginalName: "IMG_000" + string(rune('1'+i)) + ".JPG",
			Path: p, Size: int64(len("photo-" + name)),
			Created: time.Date(2026, 9, 2, 0, 0, 0, 0, time.UTC),
		})
	}
	a.photos.mu.Lock()
	a.photos.scan = &photoslib.Scan{Library: src, Assets: assets}
	a.photos.mu.Unlock()
	return assets
}

func postExport(t *testing.T, a *App, body string) (int, map[string]any) {
	t.Helper()
	req := httptest.NewRequest("POST", "/api/photoslib/export", strings.NewReader(body))
	w := httptest.NewRecorder()
	a.photosExport(w, req)
	var out map[string]any
	_ = json.Unmarshal(w.Body.Bytes(), &out)
	return w.Code, out
}

// 没激活也能导，但有额度 —— 从「一张都不给」改成「给 200 张」是有意的：
// 用户完全没体验过就要掏钱，转化率不会好。竞品都是先让你用上。
func TestFreeTierGetsAQuotaNotAWall(t *testing.T) {
	clearPro(t)
	a := newTestApp(t)
	a.settingsPath = filepath.Join(t.TempDir(), "settings.json")
	seedScan(t, a, 2)

	code, body := postExport(t, a, `{"output_dir":`+jsonStr(t.TempDir())+`}`)
	if code != 200 {
		t.Fatalf("免费版应该能导，却被拒了：%d %v", code, body)
	}
	if int(body["limit"].(float64)) != FreePhotosExport {
		t.Errorf("应该带上免费额度 %d，实际 %v", FreePhotosExport, body["limit"])
	}
	waitExport(t, a)
}

// 激活之后不设额度。
func TestProHasNoQuota(t *testing.T) {
	grantPro(t)
	a := newTestApp(t)
	a.settingsPath = filepath.Join(t.TempDir(), "settings.json")
	seedScan(t, a, 2)

	_, body := postExport(t, a, `{"output_dir":`+jsonStr(t.TempDir())+`}`)
	if int(body["limit"].(float64)) != 0 {
		t.Errorf("Pro 不该有额度，实际 %v", body["limit"])
	}
	waitExport(t, a)
}

// 扫描必须免费 —— 用户得先看见有多少能导，才知道值不值得买。
func TestScanIsFreeWithoutPro(t *testing.T) {
	clearPro(t)
	a := newTestApp(t)
	a.settingsPath = filepath.Join(t.TempDir(), "settings.json")

	lib := t.TempDir() // 不是真图库，扫描会失败，但绝不该是 402
	req := httptest.NewRequest("GET", "/api/photoslib/scan?library="+lib, nil)
	w := httptest.NewRecorder()
	a.photosScan(w, req)
	if w.Code == http.StatusPaymentRequired {
		t.Fatal("扫描被付费墙挡住了 —— 那用户只能盲买")
	}
}

// 「排除这几张」要正确翻译成「导剩下的」。
func TestExcludedSkipsOnlyThose(t *testing.T) {
	grantPro(t)
	a := newTestApp(t)
	a.settingsPath = filepath.Join(t.TempDir(), "settings.json")
	assets := seedScan(t, a, 3)
	out := t.TempDir()

	code, body := postExport(t, a,
		`{"output_dir":`+jsonStr(out)+`,"excluded":["`+assets[1].UUID+`"]}`)
	if code != 200 {
		t.Fatalf("导出被拒：%v", body)
	}
	if got := int(body["total"].(float64)); got != 2 {
		t.Fatalf("排除 1 张之后应该导 2 张，实际 %d", got)
	}
	waitExport(t, a)

	dir := filepath.Join(out, "2026", "2026-09")
	mustExist(t, filepath.Join(dir, assets[0].OriginalName))
	mustNotExist(t, filepath.Join(dir, assets[1].OriginalName))
	mustExist(t, filepath.Join(dir, assets[2].OriginalName))
}

// 「只导这几张」同理，方向反过来。
func TestSelectedExportsOnlyThose(t *testing.T) {
	grantPro(t)
	a := newTestApp(t)
	a.settingsPath = filepath.Join(t.TempDir(), "settings.json")
	assets := seedScan(t, a, 3)
	out := t.TempDir()

	code, _ := postExport(t, a,
		`{"output_dir":`+jsonStr(out)+`,"selected":["`+assets[2].UUID+`"]}`)
	if code != 200 {
		t.Fatal("导出被拒")
	}
	waitExport(t, a)

	dir := filepath.Join(out, "2026", "2026-09")
	mustNotExist(t, filepath.Join(dir, assets[0].OriginalName))
	mustExist(t, filepath.Join(dir, assets[2].OriginalName))
}

// 一张都没选中要报错，而不是默默把全部导出去 ——
// 用户点了「全不选」却导出几万张，是真会吓到人的。
func TestEmptySelectionIsRejected(t *testing.T) {
	grantPro(t)
	a := newTestApp(t)
	a.settingsPath = filepath.Join(t.TempDir(), "settings.json")
	seedScan(t, a, 2)

	code, _ := postExport(t, a, `{"output_dir":`+jsonStr(t.TempDir())+`,"selected":[]}`)
	if code == 200 {
		t.Fatal("一张没选却开始导出了")
	}
}

// 目标目录和小写选项要记住，下次进来不用重指。
func TestExportRemembersSettings(t *testing.T) {
	grantPro(t)
	a := newTestApp(t)
	a.settingsPath = filepath.Join(t.TempDir(), "settings.json")
	seedScan(t, a, 1)
	out := t.TempDir()

	if code, _ := postExport(t, a,
		`{"output_dir":`+jsonStr(out)+`,"lower_ext":true}`); code != 200 {
		t.Fatal("导出被拒")
	}
	waitExport(t, a)

	saved := a.readSettings()
	if saved.PhotosOut != out {
		t.Errorf("目标目录没记住：%q", saved.PhotosOut)
	}
	if !saved.PhotosLowerExt {
		t.Error("小写选项没记住")
	}
	// 记新字段不能把已有的输出目录抹掉
	a.saveSettings("/tmp/somewhere")
	if again := a.readSettings(); again.PhotosOut != out {
		t.Error("写别的设置时把照片导出目录冲掉了")
	}
}

func waitExport(t *testing.T, a *App) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		a.photos.mu.Lock()
		e := a.photos.exporter
		a.photos.mu.Unlock()
		if e != nil && e.Progress().Finished {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("导出没有结束")
}

func mustExist(t *testing.T, p string) {
	t.Helper()
	if _, err := os.Stat(p); err != nil {
		t.Errorf("应该存在却没有：%s", filepath.Base(p))
	}
}

func mustNotExist(t *testing.T, p string) {
	t.Helper()
	if _, err := os.Stat(p); err == nil {
		t.Errorf("不该导出却导了：%s", filepath.Base(p))
	}
}

func jsonStr(s string) string {
	b, _ := json.Marshal(s)
	return string(b)
}
