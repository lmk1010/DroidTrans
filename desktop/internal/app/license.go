package app

// 授权相关的接口。
//
// 客户端拿激活码找授权服务换一份签名许可证，之后**完全离线**校验：
// 用户插着数据线往电脑倒照片时那台机器很可能没外网，
// 做成每次联网核对等于把付过钱的人关在门外。

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"droidtrans/internal/license"
	"droidtrans/internal/update"
)

// 授权服务地址。走官网同一个域名，由 nginx 反代到本机的授权服务。
// 用环境变量覆盖，便于本地对着测试服务调试。
func licenseAPI() string {
	if v := strings.TrimSpace(os.Getenv("DROIDTRANS_LICENSE_API")); v != "" {
		return strings.TrimSuffix(v, "/")
	}
	return "https://droidtrans.mkstore.life"
}

type licenseState struct {
	mu  sync.RWMutex
	lic *license.License
}

var licState licenseState

// LoadLicense 在启动时读一次本机的许可证。
// 读不到或已损坏都按未激活处理，不影响任何基础功能。
func (a *App) LoadLicense() {
	// 固定的配置目录，不跟着用户可改的输出目录走
	lic, _ := license.Load("")
	licState.mu.Lock()
	licState.lic = lic
	licState.mu.Unlock()

	// 该回连就在后台悄悄换一份新的。
	// 一定要异步：启动流程不能被网络拖住，
	// 何况绝大多数时候这一步的结果用户根本不需要知道。
	if lic != nil && lic.NeedsRefresh() {
		go a.refreshLicense()
	}
}

// refreshLicense 拿现有许可证换一份签发时间更新的。
//
// 失败一律静默：没网是常态，不该弹任何东西。真正连不上超过宽限期时，
// 界面会自己显示「需要联网校验一次」，那时用户才需要知道。
func (a *App) refreshLicense() {
	cur := a.License()
	if cur == nil {
		return
	}
	raw, err := os.ReadFile(license.Path(""))
	if err != nil {
		return
	}
	deviceID, err := license.DeviceID("")
	if err != nil {
		return
	}

	payload, _ := json.Marshal(map[string]string{
		"license":   strings.TrimSpace(string(raw)),
		"device_id": deviceID,
	})
	client := &http.Client{Timeout: 12 * time.Second}
	resp, err := client.Post(licenseAPI()+"/api/refresh", "application/json", bytes.NewReader(payload))
	if err != nil {
		return
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	var out struct {
		License string `json:"license"`
		Error   string `json:"error"`
	}
	_ = json.Unmarshal(body, &out)

	// 码被吊销了（退款、滥用）：服务端明确说了，就地清掉本机授权。
	// 这是唯一一处会主动撤销的地方，必须以服务端的明确答复为准 ——
	// 网络错误绝不能导致降级。
	if resp.StatusCode == 403 &&
		(out.Error == "revoked" || out.Error == "device_deactivated") {
		_ = license.Remove("")
		licState.mu.Lock()
		licState.lic = nil
		licState.mu.Unlock()
		return
	}
	if resp.StatusCode != 200 || out.License == "" {
		return
	}

	lic, err := license.Save("", out.License)
	if err != nil && !errors.Is(err, license.ErrExpired) && !errors.Is(err, license.ErrStale) {
		return
	}
	licState.mu.Lock()
	licState.lic = lic
	licState.mu.Unlock()
}

// License 返回当前授权，可能为 nil。
func (a *App) License() *license.License {
	licState.mu.RLock()
	defer licState.mu.RUnlock()
	return licState.lic
}

// Can 判断某个功能现在能不能用。基础功能永远返回 true。
func (a *App) Can(f license.Feature) bool {
	return license.Allowed(a.License(), f)
}

// licenseBuy 直接把用户送进结账页。
//
// 界面是内嵌 WebView，<a target="_blank"> 在里面根本不会调起系统浏览器 ——
// 点了毫无反应。外链必须由 Go 这边用系统命令打开。
//
// 顺带把「先跳官网定价页、再点一次购买」这一步也省了：
// 结账地址由这边去要（Go 发请求没有跨域问题），拿到就直接开浏览器。
func (a *App) licenseBuy(w http.ResponseWriter, r *http.Request) {
	body := readJSON(r)
	plan, _ := body["plan"].(string)
	switch plan {
	case "year", "years3", "lifetime":
	default:
		writeJSON(w, 400, map[string]any{"success": false, "error": "unknown plan"})
		return
	}

	lang := "zh"
	if l, ok := body["lang"].(string); ok && l == "en" {
		lang = "en"
	}

	url, err := checkoutURL(plan, lang)
	if err != nil {
		// 拿不到结账地址就退而求其次，把定价页打开，
		// 总比让用户对着一个没反应的按钮强。
		url = licenseAPI() + "/pricing.html"
		if lang == "en" {
			url = licenseAPI() + "/en/pricing.html"
		}
	}
	if err := update.OpenURL(url); err != nil {
		writeJSON(w, 500, map[string]any{"success": false, "error": "打不开浏览器"})
		return
	}
	writeJSON(w, 200, map[string]any{"success": true})
}

// checkoutURL 向授权服务要一个结账地址。
func checkoutURL(plan, lang string) (string, error) {
	payload, _ := json.Marshal(map[string]string{"plan": plan, "lang": lang})
	client := &http.Client{Timeout: 12 * time.Second}
	resp, err := client.Post(licenseAPI()+"/api/checkout", "application/json", bytes.NewReader(payload))
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 32<<10))
	var out struct {
		URL string `json:"url"`
	}
	if err := json.Unmarshal(raw, &out); err != nil || out.URL == "" {
		return "", errors.New("no checkout url")
	}
	return out.URL, nil
}

func (a *App) licenseStatus(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, license.Describe(a.License()))
}

// licenseActivate 拿激活码换许可证。这是全流程里唯一需要联网的一步。
func (a *App) licenseActivate(w http.ResponseWriter, r *http.Request) {
	body := readJSON(r)
	code, _ := body["code"].(string)
	if strings.TrimSpace(code) == "" {
		writeJSON(w, 400, map[string]any{"success": false, "error": "请输入激活码"})
		return
	}

	deviceID, err := license.DeviceID("")
	if err != nil {
		writeJSON(w, 500, map[string]any{"success": false, "error": "无法创建设备标识"})
		return
	}
	token, err := fetchLicense(code, deviceID)
	if err != nil {
		writeJSON(w, 400, map[string]any{"success": false, "error": err.Error()})
		return
	}

	lic, err := license.Save("", token)
	// 过期的也存下来，界面才能提示续期而不是一句「无效」
	if err != nil && !errors.Is(err, license.ErrExpired) {
		writeJSON(w, 400, map[string]any{"success": false, "error": "许可证校验失败"})
		return
	}
	licState.mu.Lock()
	licState.lic = lic
	licState.mu.Unlock()

	writeJSON(w, 200, map[string]any{"success": true, "status": license.Describe(lic)})
}

// licenseDeactivate 注销本机授权。换电脑前用得上。
func (a *App) licenseDeactivate(w http.ResponseWriter, r *http.Request) {
	raw, err := os.ReadFile(license.Path(""))
	if err == nil {
		deviceID, idErr := license.DeviceID("")
		if idErr != nil {
			writeJSON(w, 500, map[string]any{"success": false, "error": "无法读取设备标识"})
			return
		}
		if err := releaseLicense(strings.TrimSpace(string(raw)), deviceID); err != nil {
			writeJSON(w, 400, map[string]any{"success": false, "error": err.Error()})
			return
		}
	}
	if err := license.Remove(""); err != nil {
		writeJSON(w, 500, map[string]any{"success": false, "error": err.Error()})
		return
	}
	licState.mu.Lock()
	licState.lic = nil
	licState.mu.Unlock()
	writeJSON(w, 200, map[string]any{"success": true, "status": license.Describe(nil)})
}

// fetchLicense 向授权服务换许可证，并把服务端的错误码翻译成人话。
func fetchLicense(code, deviceID string) (string, error) {
	payload, _ := json.Marshal(map[string]string{"code": code, "device_id": deviceID})
	client := &http.Client{Timeout: 15 * time.Second}
	resp, err := client.Post(licenseAPI()+"/api/activate", "application/json", bytes.NewReader(payload))
	if err != nil {
		return "", errors.New("连不上激活服务器，检查一下网络")
	}
	defer resp.Body.Close()

	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	var out struct {
		License string `json:"license"`
		Error   string `json:"error"`
		Max     int    `json:"max"`
	}
	_ = json.Unmarshal(raw, &out)

	if resp.StatusCode == 200 && out.License != "" {
		return out.License, nil
	}
	// 服务端返回的是机器码，这里换成用户看得懂的话
	switch out.Error {
	case "invalid_code":
		return "", errors.New("激活码格式不对，请核对一下")
	case "not_found":
		return "", errors.New("没有这个激活码")
	case "revoked":
		return "", errors.New("这个激活码已失效（订单已退款）")
	case "too_many_activations":
		return "", errors.New("这个激活码已在太多设备上激活过，请先在旧设备上注销")
	default:
		return "", errors.New("激活失败，请稍后再试")
	}
}

func releaseLicense(token, deviceID string) error {
	payload, _ := json.Marshal(map[string]string{
		"license":   token,
		"device_id": deviceID,
	})
	client := &http.Client{Timeout: 15 * time.Second}
	resp, err := client.Post(licenseAPI()+"/api/deactivate", "application/json", bytes.NewReader(payload))
	if err != nil {
		return errors.New("连不上激活服务器，暂时不能释放设备名额")
	}
	defer resp.Body.Close()

	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	if resp.StatusCode == 200 {
		return nil
	}
	var out struct {
		Error string `json:"error"`
	}
	_ = json.Unmarshal(raw, &out)
	switch out.Error {
	case "revoked":
		return errors.New("这个激活码已失效（订单已退款）")
	case "not_found":
		return errors.New("没有找到这份授权")
	case "device_mismatch", "device_deactivated", "invalid_license":
		return errors.New("本机授权状态不一致，请重新激活")
	default:
		return errors.New("释放设备名额失败，请稍后再试")
	}
}
