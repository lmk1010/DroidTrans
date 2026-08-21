package app

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math/big"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"
)

// Pairing 管住「谁能往这台电脑上塞东西、谁能取走电脑上的文件」。
//
// 局域网里默认谁都能连，这在公共 Wi-Fi 下不成立。这里给一个六位配对码：
// 手机扫桌面端的二维码（码就在二维码里）或手输一次，换到一个长期 token，
// 之后每个请求带着走。发现类接口（health / wifi info / caps）保持开放，
// 否则手机连「这台电脑在不在」都问不出来。
type Pairing struct {
	mu       sync.RWMutex
	path     string
	Required bool             `json:"required"`
	Code     string           `json:"code"`
	Tokens   map[string]*Peer `json:"tokens"`
}

type Peer struct {
	Name     string `json:"name"`
	DeviceID string `json:"device_id"`
	PairedAt string `json:"paired_at"`
	LastSeen string `json:"last_seen"`
}

func newCode() string {
	n, err := rand.Int(rand.Reader, big.NewInt(1000000))
	if err != nil {
		return "042042"
	}
	return fmt.Sprintf("%06d", n.Int64())
}

func newToken() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return hex.EncodeToString([]byte(time.Now().String()))
	}
	return hex.EncodeToString(b)
}

func LoadPairing(path string) *Pairing {
	p := &Pairing{path: path, Required: true, Tokens: map[string]*Peer{}}
	if b, err := os.ReadFile(path); err == nil {
		_ = json.Unmarshal(b, p)
	}
	if p.Tokens == nil {
		p.Tokens = map[string]*Peer{}
	}
	if p.Code == "" {
		p.Code = newCode()
	}
	p.save()
	return p
}

func (p *Pairing) save() {
	p.path = strings.TrimSpace(p.path)
	if p.path == "" {
		return
	}
	b, err := json.MarshalIndent(p, "", "  ")
	if err != nil {
		return
	}
	_ = os.WriteFile(p.path, b, 0o600)
}

func (p *Pairing) Snapshot() (required bool, code string, peers []map[string]any) {
	p.mu.RLock()
	defer p.mu.RUnlock()
	required = p.Required
	code = p.Code
	for tok, peer := range p.Tokens {
		peers = append(peers, map[string]any{
			"token_hint": tok[:6],
			"name":       peer.Name,
			"device_id":  peer.DeviceID,
			"paired_at":  peer.PairedAt,
			"last_seen":  peer.LastSeen,
		})
	}
	return
}

func (p *Pairing) SetRequired(on bool) {
	p.mu.Lock()
	p.Required = on
	p.save()
	p.mu.Unlock()
}

func (p *Pairing) NewCode() string {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.Code = newCode()
	p.save()
	return p.Code
}

// Pair 用配对码换 token。
func (p *Pairing) Pair(code, deviceID, name string) (string, bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if strings.TrimSpace(code) != p.Code {
		return "", false
	}
	tok := newToken()
	now := time.Now().Format(time.RFC3339)
	p.Tokens[tok] = &Peer{Name: name, DeviceID: deviceID, PairedAt: now, LastSeen: now}
	p.save()
	return tok, true
}

func (p *Pairing) Valid(token string) bool {
	if token == "" {
		return false
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	peer, ok := p.Tokens[token]
	if !ok {
		return false
	}
	peer.LastSeen = time.Now().Format(time.RFC3339)
	return true
}

func (p *Pairing) Revoke(hint string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	for tok := range p.Tokens {
		if strings.HasPrefix(tok, hint) {
			delete(p.Tokens, tok)
		}
	}
	p.save()
}

// ---- 请求准入 ----

// openPath 判断是不是「不需要配对也能问」的路径。
//
// 页面本身（界面外壳、手机落地页、图标）不含任何数据，必须放行：
// 手机用相机扫码进来时还没配对，落地页正是给它看「怎么装 App、配对码是多少」的，
// 把它挡掉就成了死循环。真正的数据接口照挡不误。
func openPath(path string) bool {
	if !strings.HasPrefix(path, "/api/") {
		return true
	}
	switch path {
	case "/api/health", "/api/wifi/info", "/api/fast/caps", "/api/pair":
		return true
	}
	return false
}

func isLoopback(r *http.Request) bool {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	ip := net.ParseIP(strings.Trim(host, "[]"))
	return ip != nil && ip.IsLoopback()
}

// allowRequest 决定这个请求能不能进来。
func (a *App) allowRequest(r *http.Request) bool {
	if a.Pair == nil {
		return true
	}
	required, _, _ := a.Pair.Snapshot()
	if !required {
		return true
	}
	if openPath(r.URL.Path) {
		return true
	}
	// 桌面端界面自己就跑在本机，不用配对
	if isLoopback(r) {
		return true
	}
	return a.Pair.Valid(r.Header.Get("X-DT-Token"))
}

func (a *App) pairHandler(w http.ResponseWriter, r *http.Request) {
	body := readJSON(r)
	code, _ := body["code"].(string)
	deviceID, _ := body["device_id"].(string)
	name, _ := body["device_name"].(string)
	if name == "" {
		name = "手机"
	}
	tok, ok := a.Pair.Pair(code, deviceID, name)
	if !ok {
		writeJSON(w, 403, map[string]any{"success": false, "error": "配对码不对"})
		return
	}
	writeJSON(w, 200, map[string]any{"success": true, "token": tok})
}

func (a *App) pairInfo(w http.ResponseWriter, r *http.Request) {
	required, code, peers := a.Pair.Snapshot()
	out := map[string]any{"success": true, "required": required, "peers": peers}
	if isLoopback(r) {
		out["code"] = code // 配对码只给本机界面看
	}
	writeJSON(w, 200, out)
}

func (a *App) pairSet(w http.ResponseWriter, r *http.Request) {
	body := readJSON(r)
	if v, ok := body["required"].(bool); ok {
		a.Pair.SetRequired(v)
	}
	if v, ok := body["new_code"].(bool); ok && v {
		a.Pair.NewCode()
	}
	if hint, ok := body["revoke"].(string); ok && hint != "" {
		a.Pair.Revoke(hint)
	}
	a.pairInfo(w, r)
}
