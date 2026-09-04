package update

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

const ManifestURL = "https://droid.mkstore.life/latest.json"

// Version 由 -ldflags 注入；开发态默认 "dev"。
var Version = "dev"

type Asset struct {
	URL    string `json:"url"`
	SHA256 string `json:"sha256,omitempty"`
}

type Manifest struct {
	Version     string `json:"version"`
	Notes       string `json:"notes"`
	PublishedAt string `json:"published_at"`
	MacOSArm64  *Asset `json:"macos_arm64"`
	Android     *Asset `json:"android"`
}

type Status struct {
	Current   string `json:"current"`
	Latest    string `json:"latest,omitempty"`
	Notes     string `json:"notes,omitempty"`
	UpdateURL string `json:"update_url,omitempty"`
	Available bool   `json:"available"`
	CheckedAt string `json:"checked_at,omitempty"`
	Error     string `json:"error,omitempty"`
}

var (
	mu     sync.RWMutex
	cached Status
)

func Current() string {
	if Version == "" {
		return "dev"
	}
	return Version
}

func Snapshot() Status {
	mu.RLock()
	defer mu.RUnlock()
	s := cached
	if s.Current == "" {
		s.Current = Current()
	}
	return s
}

// Check 拉远端清单；失败只记错误，不打扰用户。
//
// DROIDTRANS_NO_UPDATE=1 时整个跳过。离线开发、以及录演示视频时用得上 ——
// 顶上挂一条「有新版本」的横幅会盖住页面标题，而那是要给别人看的画面。
func Check() Status {
	st := Status{Current: Current(), CheckedAt: time.Now().UTC().Format(time.RFC3339)}
	if os.Getenv("DROIDTRANS_NO_UPDATE") == "1" {
		store(st)
		return st
	}
	client := &http.Client{Timeout: 8 * time.Second}
	req, err := http.NewRequest(http.MethodGet, ManifestURL, nil)
	if err != nil {
		st.Error = err.Error()
		store(st)
		return st
	}
	req.Header.Set("User-Agent", "DroidTrans/"+st.Current)
	res, err := client.Do(req)
	if err != nil {
		st.Error = err.Error()
		store(st)
		return st
	}
	defer res.Body.Close()
	body, err := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	if err != nil {
		st.Error = err.Error()
		store(st)
		return st
	}
	if res.StatusCode != 200 {
		st.Error = fmt.Sprintf("HTTP %d", res.StatusCode)
		store(st)
		return st
	}
	var m Manifest
	if err := json.Unmarshal(body, &m); err != nil {
		st.Error = err.Error()
		store(st)
		return st
	}
	st.Latest = strings.TrimSpace(m.Version)
	st.Notes = strings.TrimSpace(m.Notes)
	if runtime.GOOS == "darwin" && m.MacOSArm64 != nil {
		st.UpdateURL = m.MacOSArm64.URL
	}
	if st.UpdateURL == "" && m.MacOSArm64 != nil {
		st.UpdateURL = m.MacOSArm64.URL
	}
	st.Available = Compare(st.Latest, st.Current) > 0 && st.UpdateURL != ""
	store(st)
	return st
}

func store(st Status) {
	mu.Lock()
	cached = st
	mu.Unlock()
}

// Compare 返回 1 若 a>b，-1 若 a<b，0 相等。非数字段按字符串比。
func Compare(a, b string) int {
	a = strings.TrimPrefix(strings.TrimSpace(a), "v")
	b = strings.TrimPrefix(strings.TrimSpace(b), "v")
	if a == "" || a == "dev" {
		if b == "" || b == "dev" {
			return 0
		}
		return -1
	}
	if b == "" || b == "dev" {
		return 1
	}
	as := strings.Split(a, ".")
	bs := strings.Split(b, ".")
	n := len(as)
	if len(bs) > n {
		n = len(bs)
	}
	for i := 0; i < n; i++ {
		avar, bvar := 0, 0
		if i < len(as) {
			avar, _ = strconv.Atoi(as[i])
		}
		if i < len(bs) {
			bvar, _ = strconv.Atoi(bs[i])
		}
		if avar > bvar {
			return 1
		}
		if avar < bvar {
			return -1
		}
	}
	return 0
}

// OpenURL 用系统默认方式打开下载页/文件。
func OpenURL(u string) error {
	if u == "" {
		return fmt.Errorf("empty url")
	}
	switch runtime.GOOS {
	case "darwin":
		return exec.Command("open", u).Start()
	case "windows":
		return exec.Command("rundll32", "url.dll,FileProtocolHandler", u).Start()
	default:
		return exec.Command("xdg-open", u).Start()
	}
}
