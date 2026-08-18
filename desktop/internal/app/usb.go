package app

import (
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"droidtrans/internal/adb"
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
		"unauthorized_devices": a.unauth, "selected": a.ADB.Serial(), "model": a.model,
		"adb": a.ADB.Bin(),
	})
}

func (a *App) listDevices(w http.ResponseWriter, r *http.Request) {
	ready, unauth, err := a.ADB.Devices()
	if err != nil {
		writeJSON(w, 200, map[string]any{"success": false, "error": err.Error(), "devices": []any{}})
		return
	}
	writeJSON(w, 200, map[string]any{"success": true, "devices": ready, "unauthorized": unauth})
}

func (a *App) selectDevice(w http.ResponseWriter, r *http.Request) {
	body := readJSON(r)
	serial, _ := body["serial"].(string)
	if serial == "" {
		serial, _ = body["device"].(string)
	}
	a.ADB.SetSerial(serial)
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
	ready, _, err := a.ADB.Devices()
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
	storage := ""
	for _, p := range []string{"/sdcard", "/storage/emulated/0", "/storage/self/primary"} {
		if a.ADB.DirExists(p) {
			storage = p
			break
		}
	}
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

func (a *App) albumPreview(dir string) (cover string, count int) {
	txt, err := a.ADB.Shell(8*time.Second, "ls -1pt "+adb.ShellQuote(dir)+" 2>/dev/null")
	if err != nil {
		return "", 0
	}
	for _, name := range strings.Split(txt, "\n") {
		name = strings.TrimSpace(name)
		if name == "" || strings.HasSuffix(name, "/") {
			continue
		}
		p := strings.TrimRight(dir, "/") + "/" + name
		if !isMedia(p) {
			continue
		}
		count++
		if cover == "" && imageExt[strings.ToLower(path.Ext(p))] {
			cover = p
		}
	}
	if cover == "" {
		for _, name := range strings.Split(txt, "\n") {
			name = strings.TrimSpace(name)
			if name == "" || strings.HasSuffix(name, "/") {
				continue
			}
			p := strings.TrimRight(dir, "/") + "/" + name
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
		media = append(media, map[string]any{"path": p, "name": name, "size": 0, "size_mb": 0})
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

func (a *App) thumb(w http.ResponseWriter, r *http.Request) {
	remote := r.URL.Query().Get("path")
	if remote == "" {
		http.Error(w, "missing path", 400)
		return
	}
	key := strings.ReplaceAll(remote, "/", "_")
	if len(key) > 180 {
		key = key[len(key)-180:]
	}
	local := filepath.Join(a.ThumbDir, key)
	if st, err := os.Stat(local); err == nil && st.Size() > 0 {
		http.ServeFile(w, r, local)
		return
	}
	data, err := a.ADB.ExecOut(20*time.Second, "cat", remote)
	if err != nil || len(data) == 0 {
		http.Error(w, "thumb failed", 404)
		return
	}
	_ = os.WriteFile(local, data, 0o644)
	w.Header().Set("Content-Type", "image/jpeg")
	_, _ = w.Write(data)
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
	a.xferMu.Lock()
	if a.xferRunning {
		a.xferMu.Unlock()
		writeJSON(w, 400, map[string]any{"success": false, "error": "传输正在进行中"})
		return
	}
	a.xferRunning = true
	a.xferPaused = false
	a.xferStop = false
	a.xferDone = 0
	a.xferFailed = nil
	a.xferBytes = 0
	a.xferStart = time.Now()
	a.xferMu.Unlock()

	body := readJSON(r)
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
	a.xferMu.Lock()
	a.xferTotal = len(photos)
	a.xferOut = output
	a.xferMu.Unlock()
	if len(photos) == 0 {
		a.xferMu.Lock()
		a.xferRunning = false
		a.xferMu.Unlock()
		writeJSON(w, 400, map[string]any{"success": false, "error": "没有要传输的文件"})
		return
	}
	go a.runTransfer(photos, output)
	writeJSON(w, 200, map[string]any{"success": true, "total": len(photos)})
}

func (a *App) runTransfer(photos []string, output string) {
	defer func() {
		a.xferMu.Lock()
		a.xferRunning = false
		a.xferFile = "完成"
		elapsed := int(time.Since(a.xferStart).Seconds())
		out := a.xferOut
		done := a.xferDone
		fail := len(a.xferFailed)
		a.xferMu.Unlock()
		batchID := time.Now().Format("20060102_150405")
		device := a.ADB.Serial()
		if device == "" {
			device = "usb"
		}
		label := strings.TrimSpace(a.ADB.Prop("ro.product.model"))
		if label == "" {
			label = device
		}
		_ = a.Store.SaveBatch(store.Batch{
			DeviceID: label, BatchID: batchID, Timestamp: time.Now().Format("2006-01-02 15:04:05"),
			PhotoCount: done, Status: "completed", DurationSec: elapsed,
		})
		_ = out
		_ = fail
	}()
	workers := 8
	jobs := make(chan string)
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
				if st, err := os.Stat(local); err == nil && st.Size() > 0 {
					a.xferMu.Lock()
					a.xferDone++
					a.xferFile = path.Base(remote)
					a.xferBytes += st.Size()
					a.xferMu.Unlock()
					continue
				}
				err := a.ADB.Pull(remote, local, 45*time.Second)
				a.xferMu.Lock()
				a.xferDone++
				a.xferFile = path.Base(remote)
				if err != nil {
					a.xferFailed = append(a.xferFailed, map[string]any{"path": remote, "error": err.Error()})
				} else if st, e := os.Stat(local); e == nil {
					a.xferBytes += st.Size()
				}
				a.xferMu.Unlock()
			}
		}()
	}
	for _, p := range photos {
		a.xferMu.Lock()
		stop := a.xferStop
		a.xferMu.Unlock()
		if stop {
			break
		}
		jobs <- p
	}
	close(jobs)
	wg.Wait()
}

func (a *App) transferStatus(w http.ResponseWriter, r *http.Request) {
	a.xferMu.Lock()
	defer a.xferMu.Unlock()
	elapsed := 0.0
	speed := 0.0
	if !a.xferStart.IsZero() {
		elapsed = time.Since(a.xferStart).Seconds()
		if elapsed > 0 {
			speed = float64(a.xferBytes) / 1024 / 1024 / elapsed
		}
	}
	total := a.xferTotal
	done := a.xferDone
	pct := 0
	if total > 0 {
		pct = done * 100 / total
	}
	writeJSON(w, 200, map[string]any{
		"is_running": a.xferRunning, "paused": a.xferPaused, "total": total, "current": done,
		"completed_count": done, "failed": a.xferFailed, "current_file": a.xferFile,
		"bytes_done": a.xferBytes, "bytes_total": a.xferTotalB, "speed_mbps": speed,
		"elapsed_sec": elapsed, "percent_completed": pct, "output_dir": a.xferOut,
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
	a.xferRunning = false
	a.xferMu.Unlock()
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
