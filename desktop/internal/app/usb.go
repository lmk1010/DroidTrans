package app

import (
	"fmt"
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
			http.ServeFile(w, r, abs)
			return
		}
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
	a.xferTotalB = 0
	a.xferStart = time.Now()
	a.xferWin = rateWin{}
	a.xferLive = map[string]int64{}
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
	if len(photos) == 0 {
		a.xferMu.Lock()
		a.xferRunning = false
		a.xferMu.Unlock()
		writeJSON(w, 400, map[string]any{"success": false, "error": "没有要传输的文件"})
		return
	}
	deviceID := a.ADB.Serial()
	if deviceID == "" {
		deviceID = "usb"
	}
	deviceName := strings.TrimSpace(a.model)
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
	a.Store.UpsertDevice(deviceID, deviceName)
	a.xferMu.Lock()
	a.xferTotal = len(photos)
	a.xferOut = dest
	a.xferDevice = deviceID
	a.xferBatch = batchID
	a.xferMu.Unlock()
	_ = a.Store.SaveBatch(store.Batch{
		DeviceID: deviceID, BatchID: batchID, Timestamp: time.Now().Format("2006-01-02 15:04:05"),
		PhotoCount: len(photos), Status: "uploading",
	})
	go a.runTransfer(photos, dest, deviceID, batchID)
	writeJSON(w, 200, map[string]any{"success": true, "total": len(photos), "device_id": deviceID, "batch_id": batchID})
}

func (a *App) runTransfer(photos []string, output, deviceID, batchID string) {
	defer func() {
		a.xferMu.Lock()
		a.xferRunning = false
		a.xferFile = "完成"
		elapsed := int(time.Since(a.xferStart).Seconds())
		done := a.xferDone
		bytes := a.xferBytes
		failedN := len(a.xferFailed)
		stopped := a.xferStop
		a.xferLive = map[string]int64{}
		a.xferMu.Unlock()
		_ = a.Store.SaveBatch(store.Batch{
			DeviceID: deviceID, BatchID: batchID, Timestamp: time.Now().Format("2006-01-02 15:04:05"),
			PhotoCount: done, TotalSize: bytes, TotalSizeMB: float64(bytes) / 1024 / 1024,
			Status: "completed", DurationSec: elapsed,
		})
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
					a.xferFile = name
					if err != nil {
						a.xferFailed = append(a.xferFailed, map[string]any{"path": remote, "error": err.Error()})
					} else if sz > 0 {
						a.xferBytes += sz
						a.Store.AddPhoto(deviceID, batchID, path.Base(local), local, sz)
					}
					a.xferMu.Unlock()
				}

				if st, err := os.Stat(local); err == nil {
					if size > 0 && st.Size() == size {
						finish(st.Size(), nil)
						continue
					}
					_ = os.Remove(local)
				}
				if size > 0 {
					if src := a.Store.ExistingPath(name, size); src != "" && src != local {
						if err := linkOrCopy(src, local); err == nil {
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
				err := a.ADB.PullProgress(remote, local, adb.PullTimeout(size), report)
				if err != nil {
					_ = os.Remove(local)
					finish(0, err)
					continue
				}
				got := int64(0)
				if st, e := os.Stat(local); e == nil {
					got = st.Size()
				}
				if size > 0 && got != size {
					_ = os.Remove(local)
					finish(0, fmt.Errorf("size mismatch: got %d want %d", got, size))
					continue
				}
				finish(got, nil)
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
	total := a.xferTotal
	done := a.xferDone
	elapsed := 0.0
	speed := 0.0
	eta := 0
	bytesDone := a.xferBytes + liveSum(a.xferLive)
	if !a.xferStart.IsZero() {
		inst := a.xferWin.sample(bytesDone)
		speed, eta, elapsed = transferPace(bytesDone, a.xferTotalB, done, total, a.xferStart, inst)
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
		"completed_count": done, "failed": a.xferFailed, "current_file": a.xferFile,
		"bytes_done": bytesDone, "bytes_total": a.xferTotalB, "speed_mbps": speed,
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
