package app

import (
	"context"
	"crypto/sha1"
	"encoding/hex"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"

	"droidtrans/internal/adb"
	"droidtrans/internal/license"
	"droidtrans/internal/store"
)

var mediaExt = map[string]bool{
	".jpg": true, ".jpeg": true, ".png": true, ".gif": true, ".webp": true, ".bmp": true,
	".heic": true, ".heif": true, ".mp4": true, ".mov": true, ".m4v": true, ".mkv": true,
	".webm": true, ".avi": true, ".3gp": true,
}
var imageExt = map[string]bool{
	".jpg": true, ".jpeg": true, ".png": true, ".gif": true, ".webp": true, ".bmp": true,
	".heic": true, ".heif": true,
}

var albumNames = map[string]string{
	"camera": "相机", "100media": "相机", "100andro": "相机",
	"screenshots": "截图", "screenshot": "截图",
	"weixin": "微信", "wechat": "微信", "micromsg": "微信", "微信": "微信",
	"qq": "QQ", "tencent": "QQ", "download": "下载", "downloads": "下载",
	"pictures": "图片", "movies": "视频",
}

var nestedAlbums = []string{
	"DCIM/Camera", "DCIM/Screenshots", "DCIM/Screen recordings", "DCIM/ScreenRecorder",
	"DCIM/100MEDIA", "DCIM/100ANDRO", "Pictures/WeiXin", "Pictures/WeChat", "Pictures/微信",
	"Pictures/Screenshots", "Pictures/QQ", "tencent/MicroMsg/WeiXin", "Screenshots",
}

func albumDisplayName(dir string) string {
	base := path.Base(strings.TrimRight(dir, "/"))
	if n, ok := albumNames[strings.ToLower(base)]; ok {
		return n
	}
	low := strings.ToLower(dir)
	if strings.Contains(low, "weixin") || strings.Contains(low, "wechat") || strings.Contains(low, "micromsg") {
		return "微信"
	}
	if strings.Contains(low, "/dcim/camera") {
		return "相机"
	}
	if strings.Contains(low, "screenshot") {
		return "截图"
	}
	return base
}

func isMedia(p string) bool {
	return mediaExt[strings.ToLower(path.Ext(p))]
}

func (a *App) deviceStatus(w http.ResponseWriter, r *http.Request) {
	a.refreshDevices()
	a.devMu.Lock()
	defer a.devMu.Unlock()
	writeJSON(w, 200, map[string]any{
		"success": true, "connected": a.connected, "devices": a.serials,
		"unauthorized_devices": a.unauth, "offline_devices": a.offline,
		"selected": a.ADB.Serial(), "model": a.model, "brand": a.brand,
		"usb_code": a.usbCode, "usb_error": a.usbErr, "storage": a.hasStor,
		"adb": a.ADB.Bin(),
	})
}

func (a *App) listDevices(w http.ResponseWriter, r *http.Request) {
	ready, unauth, offline, err := a.ADB.Devices()
	if err != nil {
		writeJSON(w, 200, map[string]any{"success": false, "error": err.Error(), "devices": []any{}})
		return
	}
	// 序列号对用户没意义 —— 插着两台机器时，一串 10AECF1LTK0028N
	// 和一串 emulator-5554 分不出哪台是自己手里那部。带上型号再给前端。
	list := make([]map[string]any, 0, len(ready))
	for _, d := range ready {
		brand := a.ADB.PropOf(d.Serial, "ro.product.brand")
		model := a.ADB.PropOf(d.Serial, "ro.product.model")
		name := strings.TrimSpace(brand + " " + model)
		if name == "" {
			name = d.Serial
		}
		list = append(list, map[string]any{
			"serial": d.Serial,
			"name":   name,
			"state":  d.State,
		})
	}
	writeJSON(w, 200, map[string]any{
		"success": true, "devices": list, "selected": a.ADB.Serial(),
		"unauthorized": unauth, "offline": offline,
	})
}

func (a *App) selectDevice(w http.ResponseWriter, r *http.Request) {
	body := readJSON(r)
	serial, _ := body["serial"].(string)
	if serial == "" {
		serial, _ = body["device"].(string)
	}
	// 别的地方（比如 startTransfer）也是这么判的，保持一致；
	// 顺带让这条路在没有 ADB 的环境里可测。
	if a.ADB != nil {
		a.ADB.SetSerial(serial)
	}

	// 换了设备就把上一台的扫描结果扔掉。
	//
	// 不清的话，界面上还挂着上一台手机的相册；用户直接点「开始传输」，
	// 拉的是旧设备上的路径 —— 轻则一片失败，重则从新设备上取到同名的
	// 别的文件。切设备本来就意味着「重新看一遍」。
	a.scanMu.Lock()
	a.albums = map[string]Album{}
	a.scanning = false
	a.scanStage = ""
	a.scanErr = ""
	a.scanID++
	a.scanMu.Unlock()

	writeJSON(w, 200, map[string]any{"success": true, "serial": serial})
}

func (a *App) adbRestart(w http.ResponseWriter, r *http.Request) {
	_, _, _ = a.ADB.RunRaw(8*time.Second, "kill-server")
	_, _, err := a.ADB.RunRaw(12*time.Second, "start-server")
	if err != nil {
		writeJSON(w, 500, map[string]any{"success": false, "error": err.Error()})
		return
	}
	a.refreshDevices()
	writeJSON(w, 200, map[string]any{"success": true})
}

func (a *App) startScan(w http.ResponseWriter, r *http.Request) {
	a.scanMu.Lock()
	if a.scanning {
		a.scanMu.Unlock()
		writeJSON(w, 200, map[string]any{"success": true, "message": "扫描进行中"})
		return
	}
	a.scanning = true
	a.scanStage = "finding"
	a.scanErr = ""
	a.scanID++
	id := a.scanID
	a.scanMu.Unlock()
	go a.scanAlbums(id)
	writeJSON(w, 200, map[string]any{"success": true, "scan_id": id})
}

func (a *App) scanAlbums(id int) {
	defer func() {
		a.scanMu.Lock()
		a.scanning = false
		if a.scanStage != "error" {
			a.scanStage = "done"
		}
		a.scanMu.Unlock()
	}()
	ready, _, _, err := a.ADB.Devices()
	if err != nil || len(ready) == 0 {
		a.scanMu.Lock()
		a.scanErr = "设备未连接，请检查 USB 调试"
		a.scanStage = "error"
		a.scanMu.Unlock()
		return
	}
	if a.ADB.Serial() == "" {
		a.ADB.SetSerial(ready[0].Serial)
	}
	storage := a.storageRoot()
	if storage == "" {
		a.scanMu.Lock()
		a.scanErr = "无法访问手机存储"
		a.scanStage = "error"
		a.scanMu.Unlock()
		return
	}
	dirs := a.discoverAlbums(storage)
	found := map[string]Album{}
	for _, dir := range dirs {
		cover, count := a.albumPreview(dir)
		if count <= 0 {
			continue
		}
		found[dir] = Album{Name: albumDisplayName(dir), Cover: cover, TotalCount: count}
		a.scanMu.Lock()
		a.albums = found
		a.scanMu.Unlock()
	}
	a.scanMu.Lock()
	a.albums = found
	a.scanMu.Unlock()
	_ = id
}

func (a *App) discoverAlbums(storage string) []string {
	seen := map[string]bool{}
	var out []string
	add := func(p string) {
		p = strings.TrimRight(p, "/")
		if p == "" || seen[p] {
			return
		}
		seen[p] = true
		out = append(out, p)
	}
	for _, root := range []string{"DCIM", "Pictures"} {
		base := storage + "/" + root
		if !a.ADB.DirExists(base) {
			continue
		}
		script := "find " + adb.ShellQuote(base) + " -mindepth 1 -maxdepth 1 -type d 2>/dev/null"
		txt, err := a.ADB.Shell(10*time.Second, script)
		if err == nil {
			for _, line := range strings.Split(txt, "\n") {
				line = strings.TrimSpace(line)
				if line == "" || strings.HasPrefix(path.Base(line), ".") {
					continue
				}
				add(line)
			}
		}
	}
	for _, leaf := range []string{"Download", "Downloads", "Screenshots", "Movies"} {
		p := storage + "/" + leaf
		if a.ADB.DirExists(p) {
			add(p)
		}
	}
	for _, rel := range nestedAlbums {
		p := storage + "/" + rel
		if a.ADB.DirExists(p) {
			add(p)
		}
	}
	return out
}

func isVideo(p string) bool {
	ext := strings.ToLower(path.Ext(p))
	switch ext {
	case ".mp4", ".mov", ".m4v", ".mkv", ".webm", ".avi", ".3gp":
		return true
	default:
		return false
	}
}

func (a *App) albumPreview(dir string) (cover string, count int) {
	q := adb.ShellQuote(dir)
	txt, err := a.ADB.Shell(14*time.Second, "echo COUNT:$(find "+q+" -maxdepth 1 -type f \\( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' -o -iname '*.webp' -o -iname '*.heic' -o -iname '*.heif' -o -iname '*.gif' -o -iname '*.mp4' -o -iname '*.mov' -o -iname '*.m4v' -o -iname '*.mkv' -o -iname '*.webm' -o -iname '*.3gp' \\) 2>/dev/null | wc -l); ls -1pt "+q+" 2>/dev/null | head -40")
	if err != nil {
		return "", 0
	}
	for _, line := range strings.Split(txt, "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "COUNT:") {
			n, _ := strconv.Atoi(strings.TrimSpace(strings.TrimPrefix(line, "COUNT:")))
			count = n
			continue
		}
		if line == "" || strings.HasSuffix(line, "/") || cover != "" {
			continue
		}
		p := strings.TrimRight(dir, "/") + "/" + line
		if imageExt[strings.ToLower(path.Ext(p))] {
			cover = p
		}
	}
	if cover == "" {
		for _, line := range strings.Split(txt, "\n") {
			line = strings.TrimSpace(line)
			if line == "" || strings.HasPrefix(line, "COUNT:") || strings.HasSuffix(line, "/") {
				continue
			}
			p := strings.TrimRight(dir, "/") + "/" + line
			if isMedia(p) {
				cover = p
				break
			}
		}
	}
	return cover, count
}

func (a *App) scanStatus(w http.ResponseWriter, r *http.Request) {
	a.scanMu.Lock()
	preview := map[string]any{}
	for p, al := range a.albums {
		preview[p] = al
	}
	st := map[string]any{
		"is_running": a.scanning, "stage": a.scanStage, "error": a.scanErr,
		"scan_id": a.scanID, "albums_preview": preview, "photo_count": 0,
	}
	a.scanMu.Unlock()
	writeJSON(w, 200, st)
}

func (a *App) scanResult(w http.ResponseWriter, r *http.Request) {
	a.scanMu.Lock()
	albums := a.albums
	a.scanMu.Unlock()
	writeJSON(w, 200, map[string]any{"success": true, "albums": albums})
}

func (a *App) albumPhotos(w http.ResponseWriter, r *http.Request) {
	album := strings.TrimSpace(r.URL.Query().Get("album"))
	offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if limit <= 0 {
		limit = 120
	}
	if album == "" {
		writeJSON(w, 400, map[string]any{"success": false, "error": "缺少相册路径"})
		return
	}
	txt, err := a.ADB.Shell(10*time.Second, "ls -1t "+adb.ShellQuote(album)+" 2>/dev/null")
	if err != nil {
		writeJSON(w, 200, map[string]any{"success": true, "photos": []any{}, "total": 0, "has_more": false})
		return
	}
	var media []map[string]any
	for _, name := range strings.Split(txt, "\n") {
		name = strings.TrimSpace(name)
		if name == "" || strings.HasSuffix(name, "/") {
			continue
		}
		p := strings.TrimRight(album, "/") + "/" + name
		if !isMedia(p) {
			continue
		}
		media = append(media, map[string]any{"path": p, "name": name, "size": 0, "size_mb": 0, "video": isVideo(p)})
	}
	end := offset + limit
	if end > len(media) {
		end = len(media)
	}
	page := []map[string]any{}
	if offset < len(media) {
		page = media[offset:end]
	}
	writeJSON(w, 200, map[string]any{
		"success": true, "photos": page, "total": len(media), "has_more": end < len(media),
	})
}

func isQuickAlbum(dir, name string) bool {
	n := strings.ToLower(name + " " + dir)
	return strings.Contains(n, "相机") || strings.Contains(n, "/camera") ||
		strings.Contains(n, "100media") || strings.Contains(n, "100andro") ||
		strings.Contains(n, "截图") || strings.Contains(n, "screenshot") ||
		strings.Contains(n, "screen record")
}

func isCameraAlbum(dir, name string) bool {
	n := strings.ToLower(name + " " + dir)
	return strings.Contains(n, "相机") || strings.Contains(n, "/dcim/camera") ||
		strings.Contains(n, "100media") || strings.Contains(n, "100andro")
}

func (a *App) recentMedia(w http.ResponseWriter, r *http.Request) {
	a.scanMu.Lock()
	albums := a.albums
	a.scanMu.Unlock()
	var dirs []string
	for dir, al := range albums {
		if isQuickAlbum(dir, al.Name) {
			dirs = append(dirs, dir)
		}
	}
	if len(dirs) == 0 {
		writeJSON(w, 200, map[string]any{"success": true, "photos": []any{}, "total": 0, "camera": []string{}})
		return
	}
	quoted := make([]string, len(dirs))
	for i, d := range dirs {
		quoted[i] = adb.ShellQuote(d)
	}
	joined := strings.Join(quoted, " ")
	kind := strings.TrimSpace(r.URL.Query().Get("range"))
	script := "H=$(date +%H); M=$(date +%M); H=$((1$H-100)); M=$((1$M-100)); find " + joined + " -type f -mmin -$((H*60+M+1)) 2>/dev/null"
	if kind == "week" {
		script = "find " + joined + " -type f -mtime -7 2>/dev/null"
	}
	txt, err := a.ADB.Shell(25*time.Second, script)
	if err != nil {
		writeJSON(w, 200, map[string]any{"success": true, "photos": []any{}, "total": 0, "camera": cameraAlbumPaths(albums)})
		return
	}
	var photos []map[string]any
	seen := map[string]bool{}
	for _, line := range strings.Split(txt, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || seen[line] || !isMedia(line) {
			continue
		}
		seen[line] = true
		photos = append(photos, map[string]any{"path": line, "name": path.Base(line)})
		if len(photos) >= 1500 {
			break
		}
	}
	writeJSON(w, 200, map[string]any{
		"success": true, "photos": photos, "total": len(photos), "camera": cameraAlbumPaths(albums),
	})
}

func cameraAlbumPaths(albums map[string]Album) []string {
	var out []string
	for dir, al := range albums {
		if isCameraAlbum(dir, al.Name) {
			out = append(out, dir)
		}
	}
	return out
}

func (a *App) thumb(w http.ResponseWriter, r *http.Request) {
	remote := r.URL.Query().Get("path")
	if remote == "" {
		http.Error(w, "missing path", 400)
		return
	}
	if abs, ok := a.safeLocal(remote); ok {
		if st, err := os.Stat(abs); err == nil && !st.IsDir() {
			if isVideo(abs) {
				// 本地视频用系统的 QuickLook 出一帧封面，不额外依赖 ffmpeg。
				// 之前视频一律 404，图库里就是一排空白格。
				if poster, err := a.videoPoster(abs); err == nil {
					http.ServeFile(w, r, poster)
					return
				}
				http.Error(w, "no poster", 404)
				return
			}
			http.ServeFile(w, r, abs)
			return
		}
	}
	if isVideo(remote) {
		// 手机上的视频要抽帧得先整份拉下来，代价太大，前端会显示胶片占位
		http.Error(w, "video", 404)
		return
	}
	// 缓存键带上文件大小：手机上同名文件换了内容时，不能再给旧缩略图
	size := a.ADB.FileSize(remote)
	key := strings.ReplaceAll(remote, "/", "_")
	if len(key) > 160 {
		key = key[len(key)-160:]
	}
	key = fmt.Sprintf("%s.%d", key, size)
	local := filepath.Join(a.ThumbDir, key)
	if st, err := os.Stat(local); err == nil && st.Size() > 0 {
		http.ServeFile(w, r, local)
		return
	}
	if size > 2*1024*1024 {
		http.Error(w, "too large", 404)
		return
	}
	data, err := a.ADB.ExecOut(12*time.Second, "cat", remote)
	if err != nil || len(data) == 0 {
		http.Error(w, "thumb failed", 404)
		return
	}
	_ = os.WriteFile(local, data, 0o644)
	w.Header().Set("Content-Type", "image/jpeg")
	_, _ = w.Write(data)
}

// videoPoster 用 qlmanage 给本地视频生成一张封面并缓存。
func (a *App) videoPoster(path string) (string, error) {
	if runtime.GOOS != "darwin" {
		return "", fmt.Errorf("unsupported")
	}
	st, err := os.Stat(path)
	if err != nil {
		return "", err
	}
	sum := sha1.Sum([]byte(fmt.Sprintf("%s|%d|%d", path, st.Size(), st.ModTime().UnixNano())))
	dir := filepath.Join(a.ThumbDir, "video")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	out := filepath.Join(dir, hex.EncodeToString(sum[:])+".png")
	if fi, err := os.Stat(out); err == nil && fi.Size() > 0 {
		return out, nil
	}

	a.posterMu.Lock()
	defer a.posterMu.Unlock()
	if fi, err := os.Stat(out); err == nil && fi.Size() > 0 {
		return out, nil
	}
	tmp, err := os.MkdirTemp("", "dtposter")
	if err != nil {
		return "", err
	}
	defer os.RemoveAll(tmp)

	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()
	if err := exec.CommandContext(ctx, "qlmanage", "-t", "-s", "512", "-o", tmp, path).Run(); err != nil {
		return "", err
	}
	entries, err := os.ReadDir(tmp)
	if err != nil {
		return "", err
	}
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(strings.ToLower(e.Name()), ".png") {
			continue
		}
		if _, err := linkOrCopy(filepath.Join(tmp, e.Name()), out); err != nil {
			return "", err
		}
		return out, nil
	}
	return "", fmt.Errorf("no poster generated")
}

func remoteToLocal(remote, output string) string {
	rel := remote
	for _, p := range []string{"/sdcard/", "/storage/emulated/0/", "/storage/self/primary/"} {
		rel = strings.TrimPrefix(rel, p)
	}
	return filepath.Join(output, filepath.FromSlash(rel))
}

func (a *App) listAlbumMedia(album string) []string {
	txt, err := a.ADB.Shell(12*time.Second, "ls -1 "+adb.ShellQuote(album)+" 2>/dev/null")
	if err != nil {
		return nil
	}
	var out []string
	for _, name := range strings.Split(txt, "\n") {
		name = strings.TrimSpace(name)
		if name == "" || strings.HasSuffix(name, "/") {
			continue
		}
		p := strings.TrimRight(album, "/") + "/" + name
		if isMedia(p) {
			out = append(out, p)
		}
	}
	return out
}

func (a *App) startTransfer(w http.ResponseWriter, r *http.Request) {
	body := readJSON(r)
	if sel, ok := body["selection"].(map[string]any); ok {
		if albums, ok := sel["albums"].([]any); ok && len(albums) > 0 && !a.Can(license.FeatureUSBBulk) {
			writeJSON(w, http.StatusPaymentRequired, map[string]any{
				"success": false,
				"error":   "请先激活 Pro",
				"feature": string(license.FeatureUSBBulk),
			})
			return
		}
	}

	a.xferMu.Lock()
	if a.xferActive {
		a.xferMu.Unlock()
		writeJSON(w, 400, map[string]any{"success": false, "error": "传输正在进行中"})
		return
	}
	a.xferActive = true
	a.xferRunning = true
	a.xferPaused = false
	a.xferStop = false
	a.xferDone = 0
	a.xferOK = 0
	a.xferStopped = false
	a.xferLost = false
	a.xferLastAt = time.Time{}
	a.xferFailed = nil
	a.xferBytes = 0
	a.xferTotalB = 0
	a.xferStart = time.Now()
	a.xferWin = rateWin{}
	a.xferLive = map[string]int64{}
	a.xferMu.Unlock()

	output, _ := body["output_dir"].(string)
	if output == "" {
		output = a.OutputDir
	}
	var photos []string
	if sel, ok := body["selection"].(map[string]any); ok {
		if albums, ok := sel["albums"].([]any); ok {
			ex := map[string]map[string]bool{}
			if raw, ok := sel["exclude"].(map[string]any); ok {
				for k, v := range raw {
					ex[k] = map[string]bool{}
					if arr, ok := v.([]any); ok {
						for _, p := range arr {
							if s, ok := p.(string); ok {
								ex[k][s] = true
							}
						}
					}
				}
			}
			for _, al := range albums {
				dir, _ := al.(string)
				skip := ex[dir]
				for _, p := range a.listAlbumMedia(dir) {
					if skip[p] {
						continue
					}
					photos = append(photos, p)
				}
			}
		}
		if singles, ok := sel["singles"].([]any); ok {
			for _, p := range singles {
				if s, ok := p.(string); ok {
					photos = append(photos, s)
				}
			}
		}
	}
	if raw, ok := body["photos"].([]any); ok {
		for _, p := range raw {
			switch t := p.(type) {
			case string:
				photos = append(photos, t)
			case map[string]any:
				if s, ok := t["path"].(string); ok {
					photos = append(photos, s)
				}
			}
		}
	}
	if len(photos) == 0 {
		a.xferMu.Lock()
		a.xferActive = false
		a.xferRunning = false
		a.xferMu.Unlock()
		writeJSON(w, 400, map[string]any{"success": false, "error": "没有要传输的文件"})
		return
	}
	deviceID := a.ADB.Serial()
	if deviceID == "" {
		deviceID = "usb"
	}
	if a.Can(license.FeatureIncrementalSync) && a.Store != nil && a.ADB != nil {
		var skipped int
		photos, skipped = filterNewPhotos(
			photos,
			a.ADB.FileSize,
			func(name string, size int64) string {
				return a.Store.ExistingPathForDevice(deviceID, name, size)
			},
		)
		if len(photos) == 0 {
			a.xferMu.Lock()
			a.xferActive = false
			a.xferRunning = false
			a.xferMu.Unlock()
			writeJSON(w, 400, map[string]any{
				"success": false,
				"skipped": skipped,
				"error":   "没有新增照片",
			})
			return
		}
	}
	a.devMu.Lock()
	deviceName := strings.TrimSpace(a.model)
	a.devMu.Unlock()
	if deviceName == "" {
		deviceName = strings.TrimSpace(a.ADB.Prop("ro.product.model"))
	}
	if deviceName == "" {
		deviceName = deviceID
	}
	batchID := time.Now().Format("20060102_150405")
	dest := filepath.Join(output, deviceID, batchID)
	_ = os.MkdirAll(dest, 0o755)
	a.mu.Lock()
	a.wifiOut = output
	a.OutputDir = output
	a.mu.Unlock()
	a.Fast.SetOutputDir(output)
	a.saveSettings(output)
	a.Store.UpsertDevice(deviceID, deviceName)
	ctx, cancel := context.WithCancel(context.Background())
	a.xferMu.Lock()
	a.xferTotal = len(photos)
	a.xferOut = dest
	a.xferDevice = deviceID
	a.xferBatch = batchID
	a.xferCancel = cancel
	a.xferMu.Unlock()
	_ = a.Store.SaveBatch(store.Batch{
		DeviceID: deviceID, BatchID: batchID, Timestamp: time.Now().Format("2006-01-02 15:04:05"),
		PhotoCount: len(photos), Status: "uploading",
	})
	go a.runTransfer(ctx, photos, dest, deviceID, batchID)
	writeJSON(w, 200, map[string]any{"success": true, "total": len(photos), "device_id": deviceID, "batch_id": batchID})
}

func filterNewPhotos(
	photos []string,
	sizeOf func(string) int64,
	existingPath func(string, int64) string,
) ([]string, int) {
	keep := make([]bool, len(photos))
	for i := range keep {
		keep[i] = true
	}

	workers := 8
	if len(photos) < workers {
		workers = len(photos)
	}
	jobs := make(chan int)
	var wg sync.WaitGroup
	wg.Add(workers)
	for i := 0; i < workers; i++ {
		go func() {
			defer wg.Done()
			for index := range jobs {
				remote := photos[index]
				size := sizeOf(remote)
				if size > 0 && existingPath(path.Base(remote), size) != "" {
					keep[index] = false
				}
			}
		}()
	}
	for i := range photos {
		jobs <- i
	}
	close(jobs)
	wg.Wait()

	remaining := make([]string, 0, len(photos))
	skipped := 0
	for i, remote := range photos {
		if !keep[i] {
			skipped++
			continue
		}
		remaining = append(remaining, remote)
	}
	return remaining, skipped
}

func (a *App) runTransfer(ctx context.Context, photos []string, output, deviceID, batchID string) {
	defer func() {
		a.xferMu.Lock()
		a.xferActive = false
		a.xferRunning = false
		if a.xferCancel != nil {
			a.xferCancel()
			a.xferCancel = nil
		}
		a.xferFile = "完成"
		elapsed := int(time.Since(a.xferStart).Seconds())
		if a.xferLastAt.After(a.xferStart) {
			elapsed = int(a.xferLastAt.Sub(a.xferStart).Seconds())
		}
		done := a.xferOK
		bytes := a.xferBytes
		failedN := len(a.xferFailed)
		stopped := a.xferStop
		a.xferLive = map[string]int64{}
		a.xferMu.Unlock()
		if done == 0 {
			// 一张都没落盘（多半是刚开始就被停掉）：别在图库里留一张「0 张」的空卡
			a.Store.DeleteBatch(deviceID, batchID)
			_ = os.Remove(filepath.Join(a.OutputDir, deviceID, batchID))
		} else {
			status := "completed"
			if stopped {
				status = "stopped"
			}
			_ = a.Store.SaveBatch(store.Batch{
				DeviceID: deviceID, BatchID: batchID, Timestamp: time.Now().Format("2006-01-02 15:04:05"),
				PhotoCount: done, TotalSize: bytes, TotalSizeMB: float64(bytes) / 1024 / 1024,
				Status: status, DurationSec: elapsed,
			})
		}
		if a.OnNotify != nil && done+failedN > 0 {
			title := "USB 传输完成"
			if stopped {
				title = "USB 传输已停止"
			}
			body := fmt.Sprintf("已保存 %d 张，共 %s", done, humanBytes(bytes))
			if failedN > 0 {
				body += fmt.Sprintf("，失败 %d", failedN)
			}
			a.OnNotify(title, body)
		}
	}()
	workers := 6
	jobs := make(chan string)
	largeCh := make(chan struct{}, 1)
	var wg sync.WaitGroup
	wg.Add(workers)
	for i := 0; i < workers; i++ {
		go func() {
			defer wg.Done()
			for remote := range jobs {
				for {
					a.xferMu.Lock()
					stop, paused := a.xferStop, a.xferPaused
					a.xferMu.Unlock()
					if stop {
						return
					}
					if paused {
						time.Sleep(200 * time.Millisecond)
						continue
					}
					break
				}
				local := remoteToLocal(remote, output)
				_ = os.MkdirAll(filepath.Dir(local), 0o755)
				name := path.Base(remote)
				size := a.ADB.FileSize(remote)
				if size > 0 {
					a.xferMu.Lock()
					a.xferTotalB += size
					a.xferMu.Unlock()
				}

				finish := func(sz int64, err error) {
					a.xferMu.Lock()
					delete(a.xferLive, remote)
					a.xferDone++
					a.xferLastAt = time.Now()
					a.xferFile = name
					if err != nil {
						a.xferFailed = append(a.xferFailed, map[string]any{"path": remote, "error": err.Error()})
					} else {
						a.xferOK++ // 「完成」只算真正落盘的；中断和失败不能混进来
						a.xferBytes += sz
					}
					a.xferMu.Unlock()
					// 落库放在锁外，别让 SQLite 写盘卡住其他 worker 的进度上报
					if err == nil && sz > 0 {
						a.Store.AddPhoto(deviceID, batchID, path.Base(local), local, sz)
					}
				}

				if st, err := os.Stat(local); err == nil {
					if size > 0 && st.Size() == size {
						finish(st.Size(), nil)
						continue
					}
				}
				if size > 0 {
					if src := a.Store.ExistingPath(name, size); src != "" && src != local {
						if _, err := linkOrCopy(src, local); err == nil {
							finish(size, nil)
							continue
						}
					}
				}

				report := func(n int64) {
					a.xferMu.Lock()
					if a.xferLive == nil {
						a.xferLive = map[string]int64{}
					}
					a.xferLive[remote] = n
					a.xferFile = name
					a.xferMu.Unlock()
				}
				if size >= 32<<20 {
					select {
					case largeCh <- struct{}{}:
					case <-ctx.Done():
						return
					}
				}
				err := a.ADB.PullProgressCtx(ctx, remote, local, adb.PullTimeout(size), report)
				if size >= 32<<20 {
					<-largeCh
				}
				if err != nil && deviceGone(err) {
					// 线掉了/手机关机：剩下的文件再试也是一样的错，
					// 一个个撞过去只是把失败清单刷满、白等一堆超时。
					a.xferMu.Lock()
					a.xferLost = true
					a.xferMu.Unlock()
					finish(0, err)
					a.abortTransfer()
					return
				}
				if err != nil {
					// 用户点了停止导致的中断不是「失败」，不进失败清单也不计数
					if ctx.Err() != nil {
						a.xferMu.Lock()
						delete(a.xferLive, remote)
						a.xferMu.Unlock()
						return
					}
					finish(0, err)
					continue
				}
				got := int64(0)
				if st, e := os.Stat(local); e == nil {
					got = st.Size()
				}
				if size > 0 && got != size {
					finish(0, fmt.Errorf("size mismatch: got %d want %d", got, size))
					continue
				}
				finish(got, nil)
			}
		}()
	}
	workersDone := make(chan struct{})
	go func() {
		wg.Wait()
		close(workersDone)
	}()
	for _, p := range photos {
		a.xferMu.Lock()
		stop := a.xferStop
		a.xferMu.Unlock()
		if stop {
			break
		}
		select {
		case jobs <- p:
		case <-ctx.Done():
			// 用户点了停止，worker 可能已经全退了，别在这里卡住
		case <-workersDone:
		}
		if ctx.Err() != nil {
			break
		}
	}
	close(jobs)
	<-workersDone
}

// deviceGone 判断错误是不是「手机不在了」。
func deviceGone(err error) bool {
	if err == nil {
		return false
	}
	msg := strings.ToLower(err.Error())
	for _, s := range []string{"device offline", "device not found", "no devices", "device unauthorized", "closed"} {
		if strings.Contains(msg, s) {
			return true
		}
	}
	return false
}

// abortTransfer 立刻中止整轮传输（设备掉线时用），不改 stopped 标记。
func (a *App) abortTransfer() {
	a.xferMu.Lock()
	a.xferStop = true
	cancel := a.xferCancel
	a.xferMu.Unlock()
	if cancel != nil {
		cancel()
	}
}

func (a *App) transferStatus(w http.ResponseWriter, r *http.Request) {
	a.xferMu.Lock()
	defer a.xferMu.Unlock()
	total := a.xferTotal
	done := a.xferDone
	ok := a.xferOK
	elapsed := 0.0
	speed := 0.0
	eta := 0
	bytesDone := a.xferBytes + liveSum(a.xferLive)
	if !a.xferStart.IsZero() {
		inst := a.xferWin.sample(bytesDone)
		speed, eta, elapsed = transferPace(bytesDone, a.xferTotalB, done, total, a.xferStart, inst)
	}
	if !a.xferRunning && !a.xferStart.IsZero() && a.xferLastAt.After(a.xferStart) {
		// 结束之后别再走秒表：暂停、掉线之后干等的时间不算传输耗时
		eta = 0
		elapsed = a.xferLastAt.Sub(a.xferStart).Seconds()
		if elapsed > 0.05 {
			speed = float64(bytesDone) / 1024 / 1024 / elapsed
		}
	}
	pct := 0
	if a.xferTotalB > 0 {
		pct = int(bytesDone * 100 / a.xferTotalB)
	} else if total > 0 {
		pct = done * 100 / total
	}
	if pct > 100 {
		pct = 100
	}
	writeJSON(w, 200, map[string]any{
		"is_running": a.xferRunning, "paused": a.xferPaused, "total": total, "current": done,
		"completed_count": ok, "failed": a.xferFailed, "current_file": a.xferFile,
		"stopped":     a.xferStopped,
		"device_lost": a.xferLost,
		"bytes_done":  bytesDone, "bytes_total": a.xferTotalB, "speed_mbps": speed,
		"eta_sec": eta, "elapsed_sec": elapsed, "percent_completed": pct, "output_dir": a.xferOut,
		"device_id": a.xferDevice, "batch_id": a.xferBatch,
	})
}

func (a *App) pauseTransfer(w http.ResponseWriter, r *http.Request) {
	a.xferMu.Lock()
	a.xferPaused = true
	a.xferMu.Unlock()
	writeJSON(w, 200, map[string]any{"success": true, "paused": true})
}

func (a *App) resumeTransfer(w http.ResponseWriter, r *http.Request) {
	a.xferMu.Lock()
	a.xferPaused = false
	a.xferMu.Unlock()
	writeJSON(w, 200, map[string]any{"success": true, "paused": false})
}

func (a *App) stopTransfer(w http.ResponseWriter, r *http.Request) {
	a.xferMu.Lock()
	a.xferStop = true
	a.xferStopped = true
	a.xferPaused = false
	a.xferRunning = false
	cancel := a.xferCancel
	a.xferMu.Unlock()
	if cancel != nil {
		cancel()
	}
	writeJSON(w, 200, map[string]any{"success": true})
}

func (a *App) burstGet(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]any{"success": true, "enabled": a.ADB.Burst()})
}

func (a *App) burstSet(w http.ResponseWriter, r *http.Request) {
	body := readJSON(r)
	on, _ := body["enabled"].(bool)
	a.ADB.SetBurst(on)
	writeJSON(w, 200, map[string]any{"success": true, "enabled": on})
}

func (a *App) usbSpeed(w http.ResponseWriter, r *http.Request) {
	out, _ := a.ADB.Shell(4*time.Second, "dumpsys usb")
	speed := "unknown"
	low := strings.ToLower(out)
	switch {
	case strings.Contains(low, "superspeedplus"), strings.Contains(low, "usb 3.2"), strings.Contains(low, "usb 3.1"):
		speed = "super+"
	case strings.Contains(low, "superspeed"), strings.Contains(low, "usb 3"):
		speed = "super"
	case strings.Contains(low, "highspeed"), strings.Contains(low, "usb 2"):
		speed = "high"
	}
	writeJSON(w, 200, map[string]any{"success": true, "speed": speed, "raw": "", "connected": a.connectedFlag()})
}

func (a *App) skipSpeed(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]any{"success": true})
}

func (a *App) connectedFlag() bool {
	a.devMu.Lock()
	defer a.devMu.Unlock()
	return a.connected
}
