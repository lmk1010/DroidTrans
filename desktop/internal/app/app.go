package app

import (
	"encoding/json"
	"fmt"
	"io"
	"io/fs"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"droidtrans/internal/adb"
	"droidtrans/internal/bonjour"
	"droidtrans/internal/fast"
	"droidtrans/internal/store"
)

const (
	HTTPPort      = 9500
	APKDownloadURL = "https://dl.neox-dev.com/droidtrans/latest.apk"
)

type deviceInfo struct {
	Name          string `json:"name"`
	LastHeartbeat string `json:"last_heartbeat"`
	ConnectedAt   string `json:"connected_at"`
	PhotoCount    int    `json:"photo_count"`
}

type uploadSession struct {
	Files        []map[string]any `json:"files"`
	CurrentIndex int              `json:"current_index"`
	Completed    int              `json:"completed"`
	Failed       int              `json:"failed"`
	IsUploading  bool             `json:"is_uploading"`
	StartTime    string           `json:"start_time"`
	FileStatus   map[int]string   `json:"file_status"`
	BatchID      string           `json:"batch_id"`
}

type Album struct {
	Name       string `json:"name"`
	Cover      string `json:"cover"`
	TotalCount int    `json:"total_count"`
	TotalSize  int64  `json:"total_size"`
}

type App struct {
	ADB       *adb.Client
	Fast      *fast.Server
	Store     *store.Store
	OutputDir string
	ThumbDir  string
	Frontend  fs.FS

	mu          sync.Mutex
	devices     map[string]*deviceInfo
	sessions    map[string]*uploadSession
	wifiOut     string
	inbox       inboxState
	OnAttention func()
	OnNotify    func(title, body string)
	mdnsStop    func()

	devMu     sync.Mutex
	connected bool
	serials   []string
	unauth    []string
	model     string
	devAt     time.Time

	scanMu    sync.Mutex
	scanning  bool
	scanStage string
	scanErr   string
	scanID    int
	albums    map[string]Album

	xferMu      sync.Mutex
	xferRunning bool
	xferPaused  bool
	xferStop    bool
	xferTotal   int
	xferDone    int
	xferFailed  []map[string]any
	xferFile    string
	xferBytes   int64
	xferTotalB  int64
	xferStart   time.Time
	xferWin     rateWin
	xferOut     string
	xferDevice  string
	xferBatch   string
	xferLive    map[string]int64
}

type inboxFile struct {
	Name string `json:"name"`
	Size int64  `json:"size"`
	At   string `json:"at"`
}

type rateWin struct {
	t    time.Time
	n    int64
	mbps float64
}

func (w *rateWin) sample(bytes int64) float64 {
	now := time.Now()
	if w.t.IsZero() {
		w.t, w.n = now, bytes
		return w.mbps
	}
	dt := now.Sub(w.t).Seconds()
	if dt < 0.7 {
		return w.mbps
	}
	db := bytes - w.n
	if db < 0 {
		db = 0
	}
	w.mbps = float64(db) / 1024 / 1024 / dt
	w.t, w.n = now, bytes
	return w.mbps
}

func transferPace(bytes, totalBytes int64, filesDone, filesTotal int, started time.Time, inst float64) (speed float64, eta int, elapsed float64) {
	if started.IsZero() {
		return 0, 0, 0
	}
	elapsed = time.Since(started).Seconds()
	avg := 0.0
	if elapsed > 0.2 {
		avg = float64(bytes) / 1024 / 1024 / elapsed
	}
	speed = inst
	if speed < 0.04 {
		speed = avg
	}
	switch {
	case totalBytes > bytes && speed > 0.02:
		eta = int(float64(totalBytes-bytes)/1024/1024/speed + 0.5)
	case filesDone > 0 && filesTotal > filesDone && elapsed > 0.8:
		eta = int((elapsed/float64(filesDone))*float64(filesTotal-filesDone) + 0.5)
	}
	if eta > 24*3600 {
		eta = 0
	}
	return
}

func liveSum(m map[string]int64) int64 {
	var n int64
	for _, v := range m {
		n += v
	}
	return n
}

func humanBytes(n int64) string {
	switch {
	case n < 1024:
		return fmt.Sprintf("%d B", n)
	case n < 1024*1024:
		return fmt.Sprintf("%.1f KB", float64(n)/1024)
	case n < 1024*1024*1024:
		return fmt.Sprintf("%.1f MB", float64(n)/1024/1024)
	default:
		return fmt.Sprintf("%.2f GB", float64(n)/1024/1024/1024)
	}
}

func linkOrCopy(src, dest string) error {
	if src == "" || dest == "" || src == dest {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return err
	}
	_ = os.Remove(dest)
	if err := os.Link(src, dest); err == nil {
		return nil
	}
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.Create(dest)
	if err != nil {
		return err
	}
	_, err = io.Copy(out, in)
	cerr := out.Close()
	if err != nil {
		return err
	}
	return cerr
}

type progressWriter struct {
	w      io.Writer
	app    *App
	name   string
	total  int64
	n      int64
	last   time.Time
}

func (p *progressWriter) Write(b []byte) (int, error) {
	n, err := p.w.Write(b)
	p.n += int64(n)
	now := time.Now()
	if p.last.IsZero() || now.Sub(p.last) >= 200*time.Millisecond || err != nil {
		p.last = now
		p.app.noteLive(p.name, p.n)
	}
	return n, err
}

func (a *App) noteLive(name string, written int64) {
	if name == "" {
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.inbox.Live == nil {
		a.inbox.Live = map[string]int64{}
	}
	a.inbox.Live[name] = written
	a.inbox.LastFile = name
	a.inbox.LastAt = time.Now()
}

func (a *App) tryReuse(dest string, expected int64) (int64, bool) {
	if expected <= 0 {
		return 0, false
	}
	if st, err := os.Stat(dest); err == nil {
		if st.Size() == expected {
			return st.Size(), true
		}
		_ = os.Remove(dest)
	}
	src := a.Store.ExistingPath(filepath.Base(dest), expected)
	if src == "" || src == dest {
		return 0, false
	}
	if err := linkOrCopy(src, dest); err != nil {
		return 0, false
	}
	return expected, true
}

type inboxState struct {
	Receiving  bool
	DeviceID   string
	DeviceName string
	BatchID    string
	Completed  int
	Total      int
	Bytes      int64
	TotalBytes int64
	Started    time.Time
	Win        rateWin
	Live       map[string]int64
	Notified   int
	LastFile   string
	LastAt     time.Time
	Seq        int
	Recent     []inboxFile
}

func New() (*App, error) {
	out := defaultOutputDir()
	_ = os.MkdirAll(out, 0o755)
	thumbs := filepath.Join(out, "previews", "thumbs")
	_ = os.MkdirAll(thumbs, 0o755)
	st, err := store.Open(out)
	if err != nil {
		return nil, err
	}
	a := &App{
		ADB:       adb.New(),
		Fast:      fast.New(out, ""),
		Store:     st,
		OutputDir: out,
		ThumbDir:  thumbs,
		devices:   map[string]*deviceInfo{},
		sessions:  map[string]*uploadSession{},
		wifiOut:   out,
		albums:    map[string]Album{},
		scanStage: "idle",
	}
	return a, nil
}

func defaultOutputDir() string {
	home, _ := os.UserHomeDir()
	base := filepath.Join(home, "Videos")
	if runtime.GOOS == "darwin" {
		base = filepath.Join(home, "Movies")
	}
	return filepath.Join(base, "AndroidTransfer")
}

func LanIP() string {
	if ips := LanIPs(); len(ips) > 0 {
		return ips[0]
	}
	conn, err := net.Dial("udp", "8.8.8.8:80")
	if err != nil {
		return "127.0.0.1"
	}
	defer conn.Close()
	return conn.LocalAddr().(*net.UDPAddr).IP.String()
}

func ifaceRank(name string) int {
	n := strings.ToLower(name)
	switch {
	case n == "en0":
		return 0
	case strings.HasPrefix(n, "en"):
		return 1
	case strings.HasPrefix(n, "eth"), strings.HasPrefix(n, "wlan"):
		return 2
	case strings.HasPrefix(n, "bridge"):
		return 3
	default:
		return 5
	}
}

func skipIface(name string) bool {
	n := strings.ToLower(name)
	for _, p := range []string{"lo", "awdl", "llw", "utun", "ipsec", "ppp", "appletalk", "dummy", "vmnet", "vnic"} {
		if n == p || strings.HasPrefix(n, p) {
			return true
		}
	}
	return false
}

func LanIPs() []string {
	ifs, err := net.Interfaces()
	if err != nil {
		return nil
	}
	type item struct {
		rank int
		ip   string
	}
	var items []item
	for _, iface := range ifs {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		if skipIface(iface.Name) {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			ipn, ok := addr.(*net.IPNet)
			if !ok || ipn.IP == nil {
				continue
			}
			ip4 := ipn.IP.To4()
			if ip4 == nil || ip4.IsLoopback() || ip4.IsLinkLocalUnicast() {
				continue
			}
			items = append(items, item{rank: ifaceRank(iface.Name), ip: ip4.String()})
		}
	}
	sort.SliceStable(items, func(i, j int) bool { return items[i].rank < items[j].rank })
	seen := map[string]bool{}
	var out []string
	for _, it := range items {
		if seen[it.ip] {
			continue
		}
		seen[it.ip] = true
		out = append(out, it.ip)
	}
	return out
}

func (a *App) StartBackground() {
	a.Fast.SetLANIP(LanIP())
	a.Fast.SetOutputDir(a.OutputDir)
	a.Fast.SetOnReceived(func(name string, size int64, dest string) {
		a.recordFile("", "", name, size, dest)
	})
	a.Fast.SetOnProgress(func(name string, written, total int64) {
		a.noteLive(name, written)
	})
	a.Fast.Start()
	go a.monitorADB()
	go a.maintainLoop()
	go a.advertiseBonjour()
}

func computerName() string {
	if runtime.GOOS == "darwin" {
		out, err := exec.Command("scutil", "--get", "ComputerName").Output()
		if err == nil {
			n := strings.TrimSpace(string(out))
			if n != "" {
				return n
			}
		}
	}
	h, _ := os.Hostname()
	return strings.TrimSuffix(h, ".local")
}

func (a *App) advertiseBonjour() {
	stop, err := bonjour.Advertise(computerName(), HTTPPort)
	if err != nil {
		fmt.Println("bonjour:", err)
		return
	}
	a.mu.Lock()
	a.mdnsStop = stop
	a.mu.Unlock()
}

func (a *App) maintainLoop() {
	time.Sleep(8 * time.Second)
	a.maintain()
	tick := time.NewTicker(10 * time.Minute)
	defer tick.Stop()
	for range tick.C {
		a.maintain()
	}
}

func (a *App) maintain() {
	ip := LanIP()
	a.Fast.SetLANIP(ip)
	a.refreshDevices()
	a.pruneStaleDevices(10 * time.Minute)
	a.pruneIdleSessions(10 * time.Minute)
	a.pruneOldThumbs(7 * 24 * time.Hour)
	a.Store.Checkpoint()
	fmt.Println("maintain  lan=", ip)
}

func (a *App) pruneStaleDevices(maxAge time.Duration) {
	now := time.Now()
	a.mu.Lock()
	defer a.mu.Unlock()
	for id, d := range a.devices {
		if d == nil || d.LastHeartbeat == "" {
			continue
		}
		t, err := time.Parse(time.RFC3339, d.LastHeartbeat)
		if err != nil || now.Sub(t) > maxAge {
			delete(a.devices, id)
		}
	}
}

func (a *App) pruneIdleSessions(maxAge time.Duration) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.inbox.Receiving && !a.inbox.LastAt.IsZero() && time.Since(a.inbox.LastAt) > maxAge {
		a.inbox.Receiving = false
		if s := a.sessions[a.inbox.DeviceID]; s != nil {
			s.IsUploading = false
		}
	}
}

func (a *App) pruneOldThumbs(maxAge time.Duration) {
	_ = filepath.Walk(a.ThumbDir, func(p string, info os.FileInfo, err error) error {
		if err != nil || info == nil || info.IsDir() {
			return nil
		}
		if time.Since(info.ModTime()) > maxAge {
			_ = os.Remove(p)
		}
		return nil
	})
}

func (a *App) monitorADB() {
	for {
		a.refreshDevices()
		time.Sleep(2 * time.Second)
	}
}

func (a *App) refreshDevices() {
	a.devMu.Lock()
	if !a.devAt.IsZero() && time.Since(a.devAt) < 800*time.Millisecond {
		a.devMu.Unlock()
		return
	}
	a.devAt = time.Now()
	a.devMu.Unlock()

	ready, unauth, _ := a.ADB.Devices()
	serials := make([]string, 0, len(ready))
	for _, d := range ready {
		serials = append(serials, d.Serial)
	}
	un := make([]string, 0, len(unauth))
	for _, d := range unauth {
		un = append(un, d.Serial)
	}

	model := ""
	if len(serials) > 0 {
		cur := a.ADB.Serial()
		ok := false
		for _, s := range serials {
			if s == cur {
				ok = true
				break
			}
		}
		if !ok {
			a.ADB.SetSerial(serials[0])
		}
		model = strings.TrimSpace(a.ADB.Prop("ro.product.brand") + " " + a.ADB.Prop("ro.product.model"))
	} else {
		a.ADB.SetSerial("")
	}

	a.devMu.Lock()
	a.serials = serials
	a.unauth = un
	a.connected = len(serials) > 0
	a.model = model
	a.devAt = time.Now()
	a.devMu.Unlock()
}

func (a *App) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/health", a.health)
	mux.HandleFunc("GET /api/wifi/info", a.wifiInfo)
	mux.HandleFunc("POST /api/wifi/connect", a.wifiConnect)
	mux.HandleFunc("GET /api/wifi/status", a.wifiStatus)
	mux.HandleFunc("POST /api/wifi/heartbeat", a.wifiHeartbeat)
	mux.HandleFunc("POST /api/wifi/set_output_dir", a.wifiSetOut)
	mux.HandleFunc("POST /api/wifi/open_folder", a.openFolder)
	mux.HandleFunc("POST /api/wifi/open_photo_folder", a.openFolder)
	mux.HandleFunc("GET /api/wifi/devices", a.wifiDevices)
	mux.HandleFunc("GET /api/wifi/device_batches/{id}", a.wifiBatches)
	mux.HandleFunc("GET /api/wifi/batch_photos/{id}/{batch}", a.wifiBatchPhotos)
	mux.HandleFunc("GET /api/wifi/photo/{id}/{path...}", a.wifiPhoto)
	mux.HandleFunc("POST /api/wifi/upload_photo_list", a.wifiPhotoList)
	mux.HandleFunc("POST /api/wifi/upload_photo", a.wifiUpload)
	mux.HandleFunc("POST /api/wifi/batch_upload", a.wifiUpload)
	mux.HandleFunc("POST /api/wifi/delete_photo", a.wifiDeletePhoto)
	mux.HandleFunc("POST /api/wifi/delete_batch", a.wifiDeleteBatch)
	mux.HandleFunc("POST /api/wifi/check_files", a.wifiCheckFiles)
	mux.HandleFunc("GET /api/fast/caps", a.fastCaps)
	mux.HandleFunc("PUT /api/fast/put", a.fastPut)
	mux.HandleFunc("POST /api/fast/put", a.fastPut)
	mux.HandleFunc("POST /api/upload/init", a.uploadInit)
	mux.HandleFunc("GET /api/upload/progress/{id}", a.uploadProgress)
	mux.HandleFunc("POST /api/upload/update", a.uploadUpdate)
	mux.HandleFunc("POST /api/upload/cancel/{id}", a.uploadCancel)
	mux.HandleFunc("GET /api/inbox", a.inboxStatus)

	mux.HandleFunc("GET /api/device_status", a.deviceStatus)
	mux.HandleFunc("GET /api/check_device", a.deviceStatus)
	mux.HandleFunc("GET /api/devices", a.listDevices)
	mux.HandleFunc("POST /api/select_device", a.selectDevice)
	mux.HandleFunc("POST /api/adb_restart", a.adbRestart)
	mux.HandleFunc("POST /api/scan", a.startScan)
	mux.HandleFunc("GET /api/scan_status", a.scanStatus)
	mux.HandleFunc("GET /api/scan_result", a.scanResult)
	mux.HandleFunc("GET /api/album_photos", a.albumPhotos)
	mux.HandleFunc("GET /api/recent_media", a.recentMedia)
	mux.HandleFunc("GET /api/thumb", a.thumb)
	mux.HandleFunc("POST /api/transfer", a.startTransfer)
	mux.HandleFunc("GET /api/transfer_status", a.transferStatus)
	mux.HandleFunc("POST /api/pause_transfer", a.pauseTransfer)
	mux.HandleFunc("POST /api/resume_live", a.resumeTransfer)
	mux.HandleFunc("POST /api/stop_transfer", a.stopTransfer)
	mux.HandleFunc("GET /api/usb/burst_mode", a.burstGet)
	mux.HandleFunc("POST /api/usb/burst_mode", a.burstSet)
	mux.HandleFunc("GET /api/usb/speed", a.usbSpeed)
	mux.HandleFunc("POST /api/usb/retry_speed_test", a.usbSpeed)
	mux.HandleFunc("POST /api/usb/skip_speed_test", a.skipSpeed)
	mux.HandleFunc("POST /api/directories/list", a.listDirs)
	mux.HandleFunc("GET /api/history/batches", a.histBatches)
	mux.HandleFunc("GET /api/history/devices", a.histDevices)
	mux.HandleFunc("POST /api/history/clear", a.histClear)
	mux.HandleFunc("GET /api/gallery", a.gallery)
	mux.HandleFunc("GET /api/gallery/batch", a.galleryBatch)
	mux.HandleFunc("POST /api/reveal", a.revealPath)

	fileServer := http.FileServer(http.FS(a.Frontend))
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/") {
			http.NotFound(w, r)
			return
		}
		if r.URL.Path == "/" || r.URL.Path == "/usb" || r.URL.Path == "/wifi" || r.URL.Path == "/history" {
			http.ServeFileFS(w, r, a.Frontend, "index.html")
			return
		}
		fileServer.ServeHTTP(w, r)
	})
	return withCORS(mux)
}

func withCORS(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Headers", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS")
		if r.Method == http.MethodOptions {
			w.WriteHeader(204)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func readJSON(r *http.Request) map[string]any {
	var m map[string]any
	_ = json.NewDecoder(r.Body).Decode(&m)
	if m == nil {
		m = map[string]any{}
	}
	return m
}

func (a *App) health(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]any{"ok": true, "app": "droidtrans", "engine": "go", "root": a.OutputDir})
}

func (a *App) wifiInfo(w http.ResponseWriter, r *http.Request) {
	ip := LanIP()
	a.Fast.SetLANIP(ip)
	deviceID := r.URL.Query().Get("device_id")
	deviceName := r.URL.Query().Get("device_name")
	if deviceID != "" {
		a.touchDevice(deviceID, deviceName)
	}
	a.mu.Lock()
	list := make([]map[string]any, 0, len(a.devices))
	for id, d := range a.devices {
		list = append(list, map[string]any{"id": id, "name": d.Name})
	}
	a.mu.Unlock()
	ips := LanIPs()
	if len(ips) == 0 && ip != "" {
		ips = []string{ip}
	}
	urls := make([]string, 0, len(ips))
	for _, x := range ips {
		urls = append(urls, fmt.Sprintf("http://%s:%d", x, HTTPPort))
	}
	writeJSON(w, 200, map[string]any{
		"success": true, "ip": ip, "ips": ips, "port": HTTPPort,
		"url":      fmt.Sprintf("http://%s:%d", ip, HTTPPort),
		"urls":     urls,
		"tcp_port": fast.TCPPort, "ftp_port": fast.FTPPort,
		"apk_url":           APKDownloadURL,
		"protocols":         []string{"tcp", "ftp", "http_put", "http_multipart"},
		"connected_devices": list, "device_count": len(list),
	})
}

func (a *App) touchDevice(id, name string) {
	now := time.Now().Format(time.RFC3339)
	a.mu.Lock()
	d, ok := a.devices[id]
	if !ok {
		if name == "" {
			name = "设备 " + shortID(id)
		}
		d = &deviceInfo{Name: name, ConnectedAt: now}
		a.devices[id] = d
	}
	if name != "" {
		d.Name = name
	}
	d.LastHeartbeat = now
	a.mu.Unlock()
	a.Store.UpsertDevice(id, d.Name)
}

func shortID(id string) string {
	if len(id) > 8 {
		return id[:8]
	}
	return id
}

func (a *App) wifiConnect(w http.ResponseWriter, r *http.Request) {
	body := readJSON(r)
	id, _ := body["device_id"].(string)
	name, _ := body["device_name"].(string)
	if id == "" {
		id = "unknown"
	}
	a.touchDevice(id, name)
	a.mu.Lock()
	n := len(a.devices)
	a.mu.Unlock()
	writeJSON(w, 200, map[string]any{"success": true, "message": "设备连接成功", "device_id": id, "connected_devices": n})
}

func (a *App) wifiHeartbeat(w http.ResponseWriter, r *http.Request) {
	body := readJSON(r)
	id, _ := body["device_id"].(string)
	name, _ := body["device_name"].(string)
	if id != "" {
		a.touchDevice(id, name)
	}
	writeJSON(w, 200, map[string]any{"success": true})
}

func (a *App) wifiStatus(w http.ResponseWriter, r *http.Request) {
	a.mu.Lock()
	list := make([]map[string]any, 0, len(a.devices))
	for id, d := range a.devices {
		list = append(list, map[string]any{"id": id, "name": d.Name, "last_heartbeat": d.LastHeartbeat})
	}
	out := a.wifiOut
	a.mu.Unlock()
	writeJSON(w, 200, map[string]any{"success": true, "enabled": true, "connected_devices": list, "output_dir": out})
}

func (a *App) wifiSetOut(w http.ResponseWriter, r *http.Request) {
	body := readJSON(r)
	dir, _ := body["output_dir"].(string)
	if dir == "" {
		writeJSON(w, 400, map[string]any{"success": false, "error": "缺少路径"})
		return
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		writeJSON(w, 400, map[string]any{"success": false, "error": err.Error()})
		return
	}
	a.mu.Lock()
	a.wifiOut = dir
	a.OutputDir = dir
	a.mu.Unlock()
	a.Fast.SetOutputDir(dir)
	writeJSON(w, 200, map[string]any{"success": true, "output_dir": dir})
}

func (a *App) openFolder(w http.ResponseWriter, r *http.Request) {
	body := readJSON(r)
	path, _ := body["folder_path"].(string)
	if path == "" {
		path, _ = body["path"].(string)
	}
	if path == "" {
		a.mu.Lock()
		path = a.wifiOut
		a.mu.Unlock()
	}
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "darwin":
		cmd = exec.Command("open", path)
	case "windows":
		cmd = exec.Command("explorer", path)
	default:
		cmd = exec.Command("xdg-open", path)
	}
	_ = cmd.Start()
	writeJSON(w, 200, map[string]any{"success": true})
}

func (a *App) revealPath(w http.ResponseWriter, r *http.Request) {
	body := readJSON(r)
	p, _ := body["path"].(string)
	if p == "" {
		writeJSON(w, 400, map[string]any{"success": false, "error": "缺少路径"})
		return
	}
	if _, ok := a.safeLocal(p); !ok {
		writeJSON(w, 400, map[string]any{"success": false, "error": "路径不在输出目录内"})
		return
	}
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "darwin":
		cmd = exec.Command("open", "-R", p)
	case "windows":
		cmd = exec.Command("explorer", "/select,", p)
	default:
		cmd = exec.Command("xdg-open", filepath.Dir(p))
	}
	_ = cmd.Start()
	writeJSON(w, 200, map[string]any{"success": true})
}

func (a *App) safeLocal(p string) (string, bool) {
	if p == "" {
		return "", false
	}
	abs, err := filepath.Abs(p)
	if err != nil {
		return "", false
	}
	a.mu.Lock()
	roots := []string{a.wifiOut, a.OutputDir}
	a.mu.Unlock()
	sep := string(os.PathSeparator)
	for _, root := range roots {
		if root == "" {
			continue
		}
		r, err := filepath.Abs(root)
		if err != nil {
			continue
		}
		if abs == r || strings.HasPrefix(abs, r+sep) {
			return abs, true
		}
	}
	return "", false
}

func (a *App) wifiDevices(w http.ResponseWriter, r *http.Request) {
	devs, err := a.Store.Devices()
	if err != nil {
		writeJSON(w, 500, map[string]any{"success": false, "error": err.Error()})
		return
	}
	writeJSON(w, 200, map[string]any{"success": true, "devices": devs, "count": len(devs)})
}

func (a *App) wifiBatches(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	rows, err := a.Store.Batches(id)
	if err != nil {
		writeJSON(w, 500, map[string]any{"success": false, "error": err.Error()})
		return
	}
	writeJSON(w, 200, map[string]any{"success": true, "batches": rows})
}

func (a *App) wifiBatchPhotos(w http.ResponseWriter, r *http.Request) {
	photos, err := a.Store.Photos(r.PathValue("id"), r.PathValue("batch"))
	if err != nil {
		writeJSON(w, 500, map[string]any{"success": false, "error": err.Error()})
		return
	}
	writeJSON(w, 200, map[string]any{"success": true, "photos": photos})
}

func (a *App) wifiPhoto(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	rest := r.PathValue("path")
	a.mu.Lock()
	base := a.wifiOut
	a.mu.Unlock()
	p := filepath.Join(base, id, filepath.FromSlash(rest))
	http.ServeFile(w, r, p)
}

func (a *App) wifiPhotoList(w http.ResponseWriter, r *http.Request) {
	body := readJSON(r)
	id, _ := body["device_id"].(string)
	if id == "" {
		id = "unknown"
	}
	a.touchDevice(id, "")
	writeJSON(w, 200, map[string]any{"success": true, "message": "ok"})
}

func (a *App) wifiUpload(w http.ResponseWriter, r *http.Request) {
	if err := r.ParseMultipartForm(32 << 20); err != nil && err != http.ErrNotMultipart {
		writeJSON(w, 400, map[string]any{"success": false, "error": err.Error()})
		return
	}
	file, hdr, err := r.FormFile("file")
	if err != nil {
		writeJSON(w, 400, map[string]any{"success": false, "error": "没有文件"})
		return
	}
	defer file.Close()
	deviceID := r.FormValue("device_id")
	if deviceID == "" {
		deviceID = "unknown"
	}
	a.touchDevice(deviceID, "")
	rel := r.FormValue("relative_path")
	if rel == "" && hdr != nil {
		rel = hdr.Filename
	}
	a.mu.Lock()
	sess := a.sessions[deviceID]
	base := a.wifiOut
	a.mu.Unlock()
	batchID := r.FormValue("batch_id")
	if batchID == "" && sess != nil {
		batchID = sess.BatchID
	}
	destDir := filepath.Join(base, deviceID)
	if batchID != "" {
		destDir = filepath.Join(base, deviceID, batchID)
	}
	dest, err := fast.SafeJoin(destDir, rel)
	if err != nil {
		writeJSON(w, 500, map[string]any{"success": false, "error": err.Error()})
		return
	}
	expected, _ := strconv.ParseInt(r.FormValue("file_size"), 10, 64)
	if sz, ok := a.tryReuse(dest, expected); ok {
		a.recordFile(deviceID, batchID, filepath.Base(dest), sz, dest)
		writeJSON(w, 200, map[string]any{"success": true, "skipped": true, "path": dest})
		return
	}
	out, err := os.Create(dest)
	if err != nil {
		writeJSON(w, 500, map[string]any{"success": false, "error": err.Error()})
		return
	}
	pw := &progressWriter{w: out, app: a, name: filepath.Base(dest), total: expected}
	_, copyErr := io.Copy(pw, file)
	_ = out.Close()
	a.noteLive(filepath.Base(dest), pw.n)
	if copyErr != nil {
		writeJSON(w, 500, map[string]any{"success": false, "error": copyErr.Error()})
		return
	}
	if st, err := os.Stat(dest); err == nil && batchID != "" {
		a.recordFile(deviceID, batchID, filepath.Base(dest), st.Size(), dest)
	}
	writeJSON(w, 200, map[string]any{"success": true, "skipped": false, "path": dest})
}

func (a *App) wifiDeletePhoto(w http.ResponseWriter, r *http.Request) {
	body := readJSON(r)
	p, _ := body["path"].(string)
	if p != "" {
		_ = os.Remove(p)
	}
	writeJSON(w, 200, map[string]any{"success": true})
}

func (a *App) wifiDeleteBatch(w http.ResponseWriter, r *http.Request) {
	body := readJSON(r)
	deviceID, _ := body["device_id"].(string)
	batchID, _ := body["batch_id"].(string)
	a.mu.Lock()
	base := a.wifiOut
	a.mu.Unlock()
	if deviceID != "" && batchID != "" {
		_ = os.RemoveAll(filepath.Join(base, deviceID, batchID))
	}
	writeJSON(w, 200, map[string]any{"success": true})
}

func (a *App) wifiCheckFiles(w http.ResponseWriter, r *http.Request) {
	body := readJSON(r)
	deviceID, _ := body["device_id"].(string)
	files, _ := body["files"].([]any)
	a.mu.Lock()
	base := a.wifiOut
	sess := a.sessions[deviceID]
	a.mu.Unlock()
	batchID := ""
	if sess != nil {
		batchID = sess.BatchID
	}
	root := filepath.Join(base, deviceID, batchID)
	var missing []any
	for _, f := range files {
		m, _ := f.(map[string]any)
		name, _ := m["name"].(string)
		rel, _ := m["relative_path"].(string)
		if rel == "" {
			rel = name
		}
		expected := asInt64(m["size"])
		dest, err := fast.SafeJoin(root, rel)
		if err != nil {
			missing = append(missing, f)
			continue
		}
		if expected > 0 {
			if sz, ok := a.tryReuse(dest, expected); ok {
				a.recordFile(deviceID, batchID, filepath.Base(dest), sz, dest)
				continue
			}
		} else if st, err := os.Stat(dest); err == nil && st.Size() > 0 {
			continue
		}
		missing = append(missing, f)
	}
	writeJSON(w, 200, map[string]any{"success": true, "missing": missing})
}

func (a *App) fastCaps(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, fast.Caps(LanIP(), HTTPPort))
}

func (a *App) fastPut(w http.ResponseWriter, r *http.Request) {
	filename := r.Header.Get("X-Filename")
	if filename == "" {
		filename = r.URL.Query().Get("name")
	}
	if filename == "" {
		filename = "unnamed.bin"
	}
	rel := r.Header.Get("X-Relative-Path")
	if rel == "" {
		rel = filename
	}
	deviceID := r.Header.Get("X-Device-Id")
	if deviceID == "" {
		deviceID = "bench"
	}
	a.mu.Lock()
	base := a.wifiOut
	sess := a.sessions[deviceID]
	a.mu.Unlock()
	destDir := filepath.Join(base, deviceID)
	if sess != nil && sess.BatchID != "" {
		destDir = filepath.Join(base, deviceID, sess.BatchID)
	}
	dest, err := fast.SafeJoin(destDir, rel)
	if err != nil {
		writeJSON(w, 400, map[string]any{"success": false, "error": err.Error()})
		return
	}
	expected, _ := strconv.ParseInt(r.Header.Get("X-File-Size"), 10, 64)
	if expected <= 0 {
		expected = r.ContentLength
	}
	if sz, ok := a.tryReuse(dest, expected); ok {
		batchID := ""
		if sess != nil {
			batchID = sess.BatchID
		}
		a.recordFile(deviceID, batchID, filepath.Base(dest), sz, dest)
		writeJSON(w, 200, map[string]any{"success": true, "skipped": true, "bytes": sz, "path": dest, "protocol": "http_put"})
		return
	}
	f, err := os.Create(dest)
	if err != nil {
		writeJSON(w, 500, map[string]any{"success": false, "error": err.Error()})
		return
	}
	pw := &progressWriter{w: f, app: a, name: filepath.Base(dest), total: expected}
	n, copyErr := io.Copy(pw, r.Body)
	_ = f.Close()
	a.noteLive(filepath.Base(dest), n)
	if copyErr != nil {
		writeJSON(w, 500, map[string]any{"success": false, "error": copyErr.Error()})
		return
	}
	if sess != nil && sess.BatchID != "" {
		a.recordFile(deviceID, sess.BatchID, filepath.Base(dest), n, dest)
	} else {
		a.recordFile(deviceID, "", filepath.Base(dest), n, dest)
	}
	writeJSON(w, 200, map[string]any{"success": true, "bytes": n, "path": dest, "protocol": "http_put"})
}

func (a *App) uploadInit(w http.ResponseWriter, r *http.Request) {
	body := readJSON(r)
	id, _ := body["device_id"].(string)
	if id == "" {
		id = "unknown"
	}
	raw, _ := body["files"].([]any)
	if len(raw) == 0 {
		writeJSON(w, 400, map[string]any{"success": false, "error": "文件列表为空"})
		return
	}
	files := make([]map[string]any, 0, len(raw))
	var total int64
	for _, item := range raw {
		m, _ := item.(map[string]any)
		files = append(files, m)
		if sz, ok := m["size"].(float64); ok {
			total += int64(sz)
		}
	}
	batchID := time.Now().Format("20060102_150405")
	status := map[int]string{}
	for i := range files {
		status[i] = "pending"
	}
	a.touchDevice(id, "")
	a.mu.Lock()
	a.sessions[id] = &uploadSession{
		Files: files, IsUploading: true, StartTime: time.Now().Format(time.RFC3339),
		FileStatus: status, BatchID: batchID,
	}
	outDir := a.wifiOut
	name := ""
	if d := a.devices[id]; d != nil {
		name = d.Name
	}
	a.inbox.Receiving = true
	a.inbox.DeviceID = id
	a.inbox.DeviceName = name
	a.inbox.BatchID = batchID
	a.inbox.Completed = 0
	a.inbox.Total = len(files)
	a.inbox.Bytes = 0
	a.inbox.TotalBytes = total
	a.inbox.Started = time.Now()
	a.inbox.Win = rateWin{}
	a.inbox.Live = map[string]int64{}
	a.inbox.LastFile = ""
	a.inbox.LastAt = time.Now()
	a.inbox.Seq++
	a.mu.Unlock()
	_ = a.Store.SaveBatch(store.Batch{
		DeviceID: id, BatchID: batchID, Timestamp: time.Now().Format("2006-01-02 15:04:05"),
		PhotoCount: len(files), TotalSize: total, TotalSizeMB: float64(total) / 1024 / 1024, Status: "uploading",
	})
	a.Fast.SetOutputDir(filepath.Join(outDir, id, batchID))
	writeJSON(w, 200, map[string]any{
		"success": true, "message": "上传会话已初始化", "session_id": id, "batch_id": batchID, "total_files": len(files),
	})
}

func (a *App) uploadProgress(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	a.mu.Lock()
	s := a.sessions[id]
	a.mu.Unlock()
	if s == nil {
		writeJSON(w, 404, map[string]any{"success": false, "error": "未找到上传会话"})
		return
	}
	writeJSON(w, 200, map[string]any{
		"success": true, "device_id": id, "total": len(s.Files), "completed": s.Completed,
		"failed": s.Failed, "current_index": s.CurrentIndex, "is_uploading": s.IsUploading,
		"file_status": s.FileStatus, "files": s.Files, "batch_id": s.BatchID,
	})
}

func (a *App) uploadUpdate(w http.ResponseWriter, r *http.Request) {
	body := readJSON(r)
	id, _ := body["device_id"].(string)
	a.mu.Lock()
	s := a.sessions[id]
	if s != nil {
		if v, ok := asInt(body["completed"]); ok {
			s.Completed = v
		}
		if v, ok := asInt(body["failed"]); ok {
			s.Failed = v
		}
		if v, ok := asInt(body["current_index"]); ok {
			s.CurrentIndex = v
		}
		if v, ok := body["is_uploading"].(bool); ok {
			s.IsUploading = v
			if !v {
				a.inbox.Receiving = false
				a.inbox.LastAt = time.Now()
				a.inbox.Seq++
			}
		}
		a.inbox.Completed = s.Completed
		a.inbox.Total = len(s.Files)
		a.inbox.DeviceID = id
		a.inbox.BatchID = s.BatchID
		a.inbox.LastAt = time.Now()
		a.inbox.Seq++
	}
	a.mu.Unlock()
	writeJSON(w, 200, map[string]any{"success": true})
}

func (a *App) uploadCancel(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	a.mu.Lock()
	if s := a.sessions[id]; s != nil {
		s.IsUploading = false
	}
	a.mu.Unlock()
	writeJSON(w, 200, map[string]any{"success": true})
}

func (a *App) recordFile(deviceID, batchID, name string, size int64, dest string) {
	a.mu.Lock()
	if deviceID == "" || batchID == "" {
		d, b := a.parseDestLocked(dest)
		if deviceID == "" {
			deviceID = d
		}
		if batchID == "" {
			batchID = b
		}
	}
	if deviceID == "" {
		for id, s := range a.sessions {
			if s != nil && s.IsUploading {
				deviceID = id
				if batchID == "" {
					batchID = s.BatchID
				}
				break
			}
		}
	}
	if s := a.sessions[deviceID]; s != nil {
		s.Completed++
		if len(s.Files) > 0 && s.Completed >= len(s.Files) {
			s.IsUploading = false
		}
		if batchID == "" {
			batchID = s.BatchID
		}
		a.inbox.Total = len(s.Files)
		a.inbox.Completed = s.Completed
		a.inbox.BatchID = s.BatchID
	} else {
		a.inbox.Completed++
	}
	first := !a.inbox.Receiving
	a.inbox.Receiving = true
	if deviceID != "" {
		a.inbox.DeviceID = deviceID
		if d := a.devices[deviceID]; d != nil {
			a.inbox.DeviceName = d.Name
		}
	}
	if name == "" {
		name = filepath.Base(dest)
	}
	if a.inbox.Live != nil {
		delete(a.inbox.Live, name)
	}
	a.inbox.Bytes += size
	if a.inbox.Started.IsZero() {
		a.inbox.Started = time.Now()
	}
	a.inbox.LastFile = name
	a.inbox.LastAt = time.Now()
	a.inbox.Seq++
	a.inbox.Recent = append([]inboxFile{{Name: name, Size: size, At: time.Now().Format("15:04:05")}}, a.inbox.Recent...)
	if len(a.inbox.Recent) > 16 {
		a.inbox.Recent = a.inbox.Recent[:16]
	}
	notify := first && a.OnAttention != nil
	onAtt := a.OnAttention
	a.mu.Unlock()

	if deviceID != "" && batchID != "" {
		a.Store.AddPhoto(deviceID, batchID, name, dest, size)
	}
	if notify {
		onAtt()
	}
}

func (a *App) parseDestLocked(dest string) (deviceID, batchID string) {
	rel, err := filepath.Rel(a.wifiOut, dest)
	if err != nil {
		return "", ""
	}
	parts := strings.Split(filepath.ToSlash(rel), "/")
	if len(parts) == 0 || parts[0] == ".." {
		return "", ""
	}
	deviceID = parts[0]
	if len(parts) >= 3 {
		batchID = parts[1]
	} else if s := a.sessions[deviceID]; s != nil {
		batchID = s.BatchID
	}
	return deviceID, batchID
}

func (a *App) inboxStatus(w http.ResponseWriter, r *http.Request) {
	a.mu.Lock()
	justDone := false
	nTitle, nBody := "", ""
	receiving := a.inbox.Receiving
	if s := a.sessions[a.inbox.DeviceID]; s != nil && s.IsUploading {
		receiving = true
		a.inbox.Completed = s.Completed
		a.inbox.Total = len(s.Files)
	}
	if receiving && !a.inbox.LastAt.IsZero() && time.Since(a.inbox.LastAt) > 4*time.Second {
		if s := a.sessions[a.inbox.DeviceID]; s == nil || !s.IsUploading {
			receiving = false
			a.inbox.Receiving = false
			a.inbox.Live = map[string]int64{}
			if a.inbox.DeviceID != "" && a.inbox.BatchID != "" {
				elapsed := 0
				if !a.inbox.Started.IsZero() {
					elapsed = int(time.Since(a.inbox.Started).Seconds())
				}
				_ = a.Store.SaveBatch(store.Batch{
					DeviceID: a.inbox.DeviceID, BatchID: a.inbox.BatchID,
					Timestamp:   time.Now().Format("2006-01-02 15:04:05"),
					PhotoCount:  a.inbox.Completed,
					TotalSize:   a.inbox.Bytes,
					TotalSizeMB: float64(a.inbox.Bytes) / 1024 / 1024,
					Status:      "completed",
					DurationSec: elapsed,
				})
			}
			if a.inbox.Completed > 0 && a.inbox.Notified != a.inbox.Seq {
				a.inbox.Notified = a.inbox.Seq
				justDone = true
				nTitle = "Wi-Fi 传输完成"
				nBody = fmt.Sprintf("已收到 %d 张，共 %s", a.inbox.Completed, humanBytes(a.inbox.Bytes))
			}
		}
	}
	bytesDone := a.inbox.Bytes + liveSum(a.inbox.Live)
	inst := a.inbox.Win.sample(bytesDone)
	speed, eta, elapsed := transferPace(bytesDone, a.inbox.TotalBytes, a.inbox.Completed, a.inbox.Total, a.inbox.Started, inst)
	out := map[string]any{
		"success":     true,
		"receiving":   receiving,
		"device_id":   a.inbox.DeviceID,
		"device":      a.inbox.DeviceName,
		"batch_id":    a.inbox.BatchID,
		"completed":   a.inbox.Completed,
		"total":       a.inbox.Total,
		"bytes_done":  bytesDone,
		"bytes_total": a.inbox.TotalBytes,
		"speed_mbps":  speed,
		"eta_sec":     eta,
		"elapsed_sec": elapsed,
		"last_file":   a.inbox.LastFile,
		"seq":         a.inbox.Seq,
		"recent":      a.inbox.Recent,
		"output_dir":  a.wifiOut,
	}
	if !a.inbox.LastAt.IsZero() {
		out["last_at"] = a.inbox.LastAt.Format(time.RFC3339)
	}
	onNotify := a.OnNotify
	a.mu.Unlock()
	if justDone && onNotify != nil {
		onNotify(nTitle, nBody)
	}
	writeJSON(w, 200, out)
}

func asInt64(v any) int64 {
	switch t := v.(type) {
	case float64:
		return int64(t)
	case int64:
		return t
	case int:
		return int64(t)
	case json.Number:
		n, err := t.Int64()
		if err == nil {
			return n
		}
	}
	return 0
}

func asInt(v any) (int, bool) {
	switch t := v.(type) {
	case float64:
		return int(t), true
	case int:
		return t, true
	case json.Number:
		n, err := t.Int64()
		return int(n), err == nil
	default:
		return 0, false
	}
}

func (a *App) histBatches(w http.ResponseWriter, r *http.Request) {
	rows, err := a.Store.Batches(r.URL.Query().Get("device_id"))
	if err != nil {
		writeJSON(w, 500, map[string]any{"success": false, "error": err.Error()})
		return
	}
	writeJSON(w, 200, map[string]any{"success": true, "batches": rows})
}

func (a *App) histDevices(w http.ResponseWriter, r *http.Request) {
	devs, err := a.Store.Devices()
	if err != nil {
		writeJSON(w, 500, map[string]any{"success": false, "error": err.Error()})
		return
	}
	writeJSON(w, 200, map[string]any{"success": true, "devices": devs})
}

func (a *App) histClear(w http.ResponseWriter, r *http.Request) {
	if err := a.Store.Clear(); err != nil {
		writeJSON(w, 500, map[string]any{"success": false, "error": err.Error()})
		return
	}
	writeJSON(w, 200, map[string]any{"success": true})
}

func (a *App) gallery(w http.ResponseWriter, r *http.Request) {
	rows, err := a.Store.Batches("")
	if err != nil {
		writeJSON(w, 500, map[string]any{"success": false, "error": err.Error()})
		return
	}
	devs, _ := a.Store.Devices()
	names := map[string]string{}
	for _, d := range devs {
		id, _ := d["device_id"].(string)
		name, _ := d["device_name"].(string)
		if id != "" {
			names[id] = name
		}
	}
	a.mu.Lock()
	base := a.wifiOut
	a.mu.Unlock()
	limit := 48
	if len(rows) > limit {
		rows = rows[:limit]
	}
	out := make([]map[string]any, 0, len(rows))
	for _, b := range rows {
		folder := filepath.Join(base, b.DeviceID, b.BatchID)
		files := a.batchFiles(b.DeviceID, b.BatchID, folder)
		cover := ""
		previews := []map[string]any{}
		for _, f := range files {
			p, _ := f["path"].(string)
			if cover == "" && isImage(p) {
				cover = p
			}
			if len(previews) < 4 {
				previews = append(previews, f)
			}
		}
		name := names[b.DeviceID]
		if name == "" {
			name = b.DeviceID
		}
		if len(files) > 0 {
			if p, _ := files[0]["path"].(string); p != "" {
				dir := filepath.Dir(p)
				for dir != "." && dir != string(filepath.Separator) {
					if filepath.Base(dir) == b.BatchID {
						folder = dir
						break
					}
					dir = filepath.Dir(dir)
				}
			}
		}
		count := b.PhotoCount
		if len(files) > count {
			count = len(files)
		}
		size := b.TotalSize
		if size == 0 {
			for _, f := range files {
				switch n := f["size"].(type) {
				case int64:
					size += n
				case float64:
					size += int64(n)
				}
			}
		}
		out = append(out, map[string]any{
			"device_id": b.DeviceID, "device_name": name, "batch_id": b.BatchID,
			"photo_count": count, "folder": folder, "cover": cover,
			"previews": previews, "timestamp": b.Timestamp, "status": b.Status,
			"total_size": size, "total_size_mb": float64(size) / 1024 / 1024,
			"duration_sec": b.DurationSec,
		})
	}
	writeJSON(w, 200, map[string]any{"success": true, "batches": out})
}

func (a *App) galleryBatch(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("device")
	batch := r.URL.Query().Get("batch")
	if id == "" || batch == "" {
		writeJSON(w, 400, map[string]any{"success": false, "error": "缺少参数"})
		return
	}
	a.mu.Lock()
	folder := filepath.Join(a.wifiOut, id, batch)
	a.mu.Unlock()
	files := a.batchFiles(id, batch, folder)
	if len(files) > 0 {
		if p, _ := files[0]["path"].(string); p != "" {
			dir := filepath.Dir(p)
			for dir != "." && dir != string(filepath.Separator) {
				if filepath.Base(dir) == batch {
					folder = dir
					break
				}
				dir = filepath.Dir(dir)
			}
		}
	}
	writeJSON(w, 200, map[string]any{
		"success": true, "device_id": id, "batch_id": batch, "folder": folder, "photos": files,
	})
}

func (a *App) batchFiles(deviceID, batchID, folder string) []map[string]any {
	seen := map[string]bool{}
	var out []map[string]any
	photos, _ := a.Store.Photos(deviceID, batchID)
	for _, p := range photos {
		path, _ := p["path"].(string)
		name, _ := p["name"].(string)
		if path == "" {
			path = filepath.Join(folder, name)
		}
		if !isMedia(path) {
			continue
		}
		seen[path] = true
		out = append(out, map[string]any{"name": name, "path": path, "size": p["size"]})
	}
	_ = filepath.Walk(folder, func(p string, info os.FileInfo, err error) error {
		if err != nil || info == nil || info.IsDir() {
			return nil
		}
		if !isMedia(p) || seen[p] {
			return nil
		}
		seen[p] = true
		out = append(out, map[string]any{"name": info.Name(), "path": p, "size": info.Size()})
		return nil
	})
	return out
}

func isImage(p string) bool {
	return imageExt[strings.ToLower(filepath.Ext(p))]
}

func (a *App) listDirs(w http.ResponseWriter, r *http.Request) {
	body := readJSON(r)
	path, _ := body["path"].(string)
	if path == "" {
		home, _ := os.UserHomeDir()
		path = home
	}
	entries, err := os.ReadDir(path)
	if err != nil {
		writeJSON(w, 400, map[string]any{"success": false, "error": err.Error()})
		return
	}
	var dirs []map[string]any
	for _, e := range entries {
		if !e.IsDir() || strings.HasPrefix(e.Name(), ".") {
			continue
		}
		dirs = append(dirs, map[string]any{"name": e.Name(), "path": filepath.Join(path, e.Name())})
	}
	parent := filepath.Dir(path)
	writeJSON(w, 200, map[string]any{"success": true, "current": path, "parent": parent, "directories": dirs})
}
