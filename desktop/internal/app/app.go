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
	"strconv"
	"strings"
	"sync"
	"time"

	"droidtrans/internal/adb"
	"droidtrans/internal/fast"
	"droidtrans/internal/store"
)

const HTTPPort = 9500

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

	devMu     sync.Mutex
	connected bool
	serials   []string
	unauth    []string
	model     string

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
	xferOut     string
}

type inboxFile struct {
	Name string `json:"name"`
	Size int64  `json:"size"`
	At   string `json:"at"`
}

type inboxState struct {
	Receiving  bool
	DeviceID   string
	DeviceName string
	BatchID    string
	Completed  int
	Total      int
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
		Fast:      fast.New(out, LanIP()),
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
	if runtime.GOOS == "darwin" {
		out, err := exec.Command("ipconfig", "getifaddr", "en0").Output()
		if err == nil {
			if ip := strings.TrimSpace(string(out)); ip != "" {
				return ip
			}
		}
	}
	conn, err := net.Dial("udp", "8.8.8.8:80")
	if err != nil {
		return "127.0.0.1"
	}
	defer conn.Close()
	return conn.LocalAddr().(*net.UDPAddr).IP.String()
}

func (a *App) StartBackground() {
	a.Fast.SetLANIP(LanIP())
	a.Fast.SetOutputDir(a.OutputDir)
	a.Fast.SetOnReceived(func(name string, size int64, dest string) {
		a.recordFile("", "", name, size, dest)
	})
	a.Fast.Start()
	go a.monitorADB()
}

func (a *App) monitorADB() {
	time.Sleep(2 * time.Second)
	for {
		a.refreshDevices()
		time.Sleep(2 * time.Second)
	}
}

func (a *App) refreshDevices() {
	ready, unauth, _ := a.ADB.Devices()
	a.devMu.Lock()
	defer a.devMu.Unlock()
	a.serials = nil
	for _, d := range ready {
		a.serials = append(a.serials, d.Serial)
	}
	a.unauth = nil
	for _, d := range unauth {
		a.unauth = append(a.unauth, d.Serial)
	}
	a.connected = len(a.serials) > 0
	if a.connected {
		cur := a.ADB.Serial()
		ok := false
		for _, s := range a.serials {
			if s == cur {
				ok = true
				break
			}
		}
		if !ok {
			a.ADB.SetSerial(a.serials[0])
		}
		a.model = strings.TrimSpace(a.ADB.Prop("ro.product.brand") + " " + a.ADB.Prop("ro.product.model"))
	} else {
		a.ADB.SetSerial("")
		a.model = ""
	}
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
	writeJSON(w, 200, map[string]any{
		"success": true, "ip": ip, "port": HTTPPort,
		"url":      fmt.Sprintf("http://%s:%d", ip, HTTPPort),
		"tcp_port": fast.TCPPort, "ftp_port": fast.FTPPort,
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
	if st, err := os.Stat(dest); err == nil {
		if expected > 0 && st.Size() == expected || expected == 0 && st.Size() > 0 {
			a.recordFile(deviceID, batchID, filepath.Base(dest), st.Size(), dest)
			writeJSON(w, 200, map[string]any{"success": true, "skipped": true, "path": dest})
			return
		}
		_ = os.Remove(dest)
	}
	out, err := os.Create(dest)
	if err != nil {
		writeJSON(w, 500, map[string]any{"success": false, "error": err.Error()})
		return
	}
	_, copyErr := io.Copy(out, file)
	_ = out.Close()
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
		p := filepath.Join(root, filepath.FromSlash(rel))
		if _, err := os.Stat(p); err != nil {
			missing = append(missing, f)
		}
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
	f, err := os.Create(dest)
	if err != nil {
		writeJSON(w, 500, map[string]any{"success": false, "error": err.Error()})
		return
	}
	n, copyErr := io.Copy(f, r.Body)
	_ = f.Close()
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
			if a.inbox.DeviceID != "" && a.inbox.BatchID != "" {
				_ = a.Store.SaveBatch(store.Batch{
					DeviceID: a.inbox.DeviceID, BatchID: a.inbox.BatchID,
					Timestamp:  time.Now().Format("2006-01-02 15:04:05"),
					PhotoCount: a.inbox.Completed, Status: "completed",
				})
			}
		}
	}
	out := map[string]any{
		"success":    true,
		"receiving":  receiving,
		"device_id":  a.inbox.DeviceID,
		"device":     a.inbox.DeviceName,
		"batch_id":   a.inbox.BatchID,
		"completed":  a.inbox.Completed,
		"total":      a.inbox.Total,
		"last_file":  a.inbox.LastFile,
		"seq":        a.inbox.Seq,
		"recent":     a.inbox.Recent,
		"output_dir": a.wifiOut,
	}
	if !a.inbox.LastAt.IsZero() {
		out["last_at"] = a.inbox.LastAt.Format(time.RFC3339)
	}
	a.mu.Unlock()
	writeJSON(w, 200, out)
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
