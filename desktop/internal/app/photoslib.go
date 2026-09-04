package app

// 「从照片图库救数据」的接口层。
//
// 分成扫描和导出两步，而且**扫描是免费的**：用户得先看见「你这个图库里有
// 3421 张原片在本机，另外 1892 张只在 iCloud」，才知道这功能对他有没有用。
// 上来就挡在付费墙后面，等于让人盲买。

import (
	"context"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"sync"

	"droidtrans/internal/license"
	"droidtrans/internal/photoslib"
)

type photosState struct {
	mu       sync.Mutex
	scan     *photoslib.Scan
	exporter *photoslib.Exporter
	outDir   string
}

// photosLibraries 这台机器上有哪些图库可以挑。
//
// 只认默认位置是不够的：照片一多，图库就会被搬到外置硬盘上，
// 而照片多的人恰恰最可能为这个功能付钱。
func (a *App) photosLibraries(w http.ResponseWriter, r *http.Request) {
	libs := photoslib.Discover()
	saved := a.readSettings()
	writeJSON(w, 200, map[string]any{
		"success": true, "libraries": libs, "current": saved.PhotosLibrary,
	})
}

// photosReveal 在访达里打开导出的位置。
//
// 导完只给一行路径，用户还得自己去找 —— 差的就是这一下。
// 这里只开我们自己刚写过的那个目录，不接受任意路径。
func (a *App) photosReveal(w http.ResponseWriter, r *http.Request) {
	a.photos.mu.Lock()
	out := a.photos.outDir
	a.photos.mu.Unlock()
	if out == "" {
		writeJSON(w, 400, map[string]any{"success": false, "error": "还没有导出过"})
		return
	}
	openInFileManager(out)
	writeJSON(w, 200, map[string]any{"success": true})
}

// photosOpenPrivacySettings 直接跳到「完全磁盘访问权限」那一页。
//
// 没权限时光说「去系统设置里打开」是把用户扔在半路上 ——
// 那个开关埋在五层菜单下面，很多人翻不到就放弃了。
func (a *App) photosOpenPrivacySettings(w http.ResponseWriter, r *http.Request) {
	if runtime.GOOS == "darwin" {
		_ = exec.Command("open",
			"x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles").Start()
	}
	writeJSON(w, 200, map[string]any{"success": true})
}

func openInFileManager(dir string) {
	switch runtime.GOOS {
	case "darwin":
		_ = exec.Command("open", dir).Start()
	case "windows":
		_ = exec.Command("explorer", dir).Start()
	default:
		_ = exec.Command("xdg-open", dir).Start()
	}
}

// photosScan 扫一遍图库，报告有多少能导、多少只在 iCloud。
func (a *App) photosScan(w http.ResponseWriter, r *http.Request) {
	saved := a.readSettings()
	lib := r.URL.Query().Get("library")
	if lib == "" {
		lib = saved.PhotosLibrary // 上次用的那个
	}
	if lib == "" {
		var err error
		lib, err = photoslib.DefaultLibrary()
		if err != nil {
			writeJSON(w, 404, map[string]any{"success": false, "error": err.Error()})
			return
		}
	}

	scan, err := photoslib.ScanLibrary(lib)
	if err != nil {
		writeJSON(w, 500, map[string]any{"success": false, "error": err.Error()})
		return
	}

	a.photos.mu.Lock()
	a.photos.scan = scan
	a.photos.mu.Unlock()

	// 记住这次用的图库，下次进来不用再指一遍
	a.updateSettings(func(s *settings) { s.PhotosLibrary = lib })

	var live int
	for _, x := range scan.Assets {
		if x.IsLive {
			live++
		}
	}

	writeJSON(w, 200, map[string]any{
		"success":       true,
		"library":       scan.Library,
		"exportable":    len(scan.Assets),
		"in_cloud_only": scan.InCloudOnly,
		"trashed":       scan.Trashed,
		"live_photos":   live,
		"total_bytes":   scan.TotalBytes,
		"can_export":    true,
		"free_limit":    freeLimitFor(a),
		"output":        a.photosOutDir(saved),
		"lower_ext":     saved.PhotosLowerExt,
	})
}

// photosOutDir 上次用的目标目录，没有就落在传输输出目录下面。
func (a *App) photosOutDir(saved settings) string {
	if saved.PhotosOut != "" {
		return saved.PhotosOut
	}
	a.mu.Lock()
	base := a.wifiOut
	a.mu.Unlock()
	return filepath.Join(base, "照片图库")
}

// photosExport 开始往外导。
// FreePhotosExport 免费版单趟能导出的项目数。
//
// 从「一张都不给导」改成给额度：原来的设计用户完全没体验过就要掏钱，
// 转化率不会好。竞品都是先让你用上 —— PowerPhotos 免费能导，
// 只是有量的上限，付费解锁「无限量导出」。
//
// 1000 而不是 200：200 张只是一次旅行的量，普通用户一趟就用完了，
// 那不叫「体验过」，叫「刚上手就被拦住」。1000 张够一个人把最近
// 一两年的照片理出来，真正碰到上限的是整库几千上万张的人。
const FreePhotosExport = 1000

func (a *App) photosExport(w http.ResponseWriter, r *http.Request) {
	a.photos.mu.Lock()
	scan := a.photos.scan
	running := a.photos.exporter != nil && !a.photos.exporter.Progress().Finished
	a.photos.mu.Unlock()

	if scan == nil {
		writeJSON(w, 400, map[string]any{"success": false, "error": "请先扫描图库"})
		return
	}
	if running {
		writeJSON(w, 400, map[string]any{"success": false, "error": "导出正在进行中"})
		return
	}
	if len(scan.Assets) == 0 {
		writeJSON(w, 400, map[string]any{
			"success": false,
			"error":   "本机上没有可导出的原片。这些照片都只在 iCloud 上，先在「照片」里下载原片再来。",
		})
		return
	}

	body := readJSON(r)
	saved := a.readSettings()

	out, _ := body["output_dir"].(string)
	if out == "" {
		out = a.photosOutDir(saved)
	}
	if err := os.MkdirAll(out, 0o755); err != nil {
		writeJSON(w, 400, map[string]any{"success": false, "error": "这个目录建不出来：" + err.Error()})
		return
	}

	lowerExt := saved.PhotosLowerExt
	if v, ok := body["lower_ext"].(bool); ok {
		lowerExt = v
	}

	// 用户在网格里挑了哪些。「只能整组导、不能挑单张」是同类工具
	// 最集中的一条差评，所以两种表达都收：
	//
	//   excluded —— 默认全选、只排除少数几张（最常见）
	//   selected —— 只导这几张
	//
	// 收 excluded 是为了让前端不必背着几万个 uuid：一个五万张的库，
	// 光 uuid 就有近 2MB，为了取消两张而把它们全塞进请求体没道理。
	picked := scan.Assets
	if raw, ok := body["excluded"].([]any); ok && len(raw) > 0 {
		skip := uuidSet(raw)
		picked = picked[:0:0]
		for _, x := range scan.Assets {
			if !skip[x.UUID] {
				picked = append(picked, x)
			}
		}
	} else if raw, ok := body["selected"].([]any); ok {
		want := uuidSet(raw)
		picked = picked[:0:0]
		for _, x := range scan.Assets {
			if want[x.UUID] {
				picked = append(picked, x)
			}
		}
	}
	if len(picked) == 0 {
		writeJSON(w, 400, map[string]any{"success": false, "error": "一张都没选中"})
		return
	}

	a.updateSettings(func(s *settings) {
		s.PhotosOut = out
		s.PhotosLowerExt = lowerExt
	})

	// 免费版一趟导 200 项，数的是真正抄过去的个数，
	// 所以再点一次会接着往下导，不会卡在同一批上。
	maxItems := 0
	if !a.Can(license.FeaturePhotosRescue) {
		maxItems = FreePhotosExport
	}

	e := &photoslib.Exporter{}
	a.photos.mu.Lock()
	a.photos.exporter = e
	a.photos.outDir = out
	a.photos.mu.Unlock()

	go e.Run(context.Background(), picked, out, photoslib.Options{
		LowercaseExt: lowerExt,
		MaxItems:     maxItems,
	})

	writeJSON(w, 200, map[string]any{
		"success": true, "output": out, "total": len(picked), "limit": maxItems,
	})
}

// photosItems 网格要显示的条目。分页给 —— 几万张一次性塞过去，
// 前端渲染会卡死，用户以为软件挂了。
func (a *App) photosItems(w http.ResponseWriter, r *http.Request) {
	a.photos.mu.Lock()
	scan := a.photos.scan
	a.photos.mu.Unlock()
	if scan == nil {
		writeJSON(w, 400, map[string]any{"success": false, "error": "请先扫描图库"})
		return
	}

	offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if limit <= 0 || limit > 500 {
		limit = 200
	}
	if offset < 0 || offset > len(scan.Assets) {
		offset = 0
	}
	end := offset + limit
	if end > len(scan.Assets) {
		end = len(scan.Assets)
	}

	items := make([]map[string]any, 0, end-offset)
	for _, x := range scan.Assets[offset:end] {
		items = append(items, map[string]any{
			"uuid": x.UUID, "name": x.OriginalName, "size": x.Size,
			"video": x.IsVideo, "live": x.IsLive,
			"date": x.Created.Format("2006-01-02"),
		})
	}
	writeJSON(w, 200, map[string]any{
		"success": true, "items": items, "offset": offset, "total": len(scan.Assets),
	})
}

// photosThumb 网格里的缩略图。
//
// 只认扫描结果里的 UUID，不接受任意路径 —— 否则这就成了一个
// 「把本机任意文件读出来」的接口。
//
// HEIC 直接丢给浏览器在别的引擎上是打不开的，而且几万张原图当缩略图用
// 也太浪费，所以统一过一遍 QuickLook 出 512px 的 PNG，带缓存。
func (a *App) photosThumb(w http.ResponseWriter, r *http.Request) {
	uuid := r.URL.Query().Get("uuid")
	a.photos.mu.Lock()
	scan := a.photos.scan
	a.photos.mu.Unlock()
	if scan == nil || uuid == "" {
		http.Error(w, "not found", 404)
		return
	}
	var path string
	for _, x := range scan.Assets {
		if x.UUID == uuid {
			path = x.Path
			break
		}
	}
	if path == "" {
		http.Error(w, "not found", 404)
		return
	}
	// videoPoster 名字里带 video，其实是通用的 QuickLook 缩略图，图片一样吃
	poster, err := a.videoPoster(path)
	if err != nil {
		http.Error(w, "no thumb", 404)
		return
	}
	w.Header().Set("Cache-Control", "private, max-age=86400")
	http.ServeFile(w, r, poster)
}

func (a *App) photosStatus(w http.ResponseWriter, r *http.Request) {
	a.photos.mu.Lock()
	e := a.photos.exporter
	out := a.photos.outDir
	a.photos.mu.Unlock()

	if e == nil {
		writeJSON(w, 200, map[string]any{"success": true, "running": false})
		return
	}
	p := e.Progress()
	writeJSON(w, 200, map[string]any{
		"success":     true,
		"running":     !p.Finished,
		"done":        p.Done,
		"total":       p.Total,
		"bytes":       p.Bytes,
		"total_bytes": p.TotalBytes,
		"current":     p.Current,
		"failed":      p.Failed,
		"finished":    p.Finished,
		"error":       p.Error,
		"output":      out,
		"copied":      p.Copied,
		"limit":       p.Limit,
		"limit_hit":   p.LimitHit,
	})
}

func (a *App) photosCancel(w http.ResponseWriter, r *http.Request) {
	a.photos.mu.Lock()
	e := a.photos.exporter
	a.photos.mu.Unlock()
	if e != nil {
		e.Cancel()
	}
	writeJSON(w, 200, map[string]any{"success": true})
}

func uuidSet(raw []any) map[string]bool {
	out := make(map[string]bool, len(raw))
	for _, v := range raw {
		if s, ok := v.(string); ok && s != "" {
			out[s] = true
		}
	}
	return out
}

// freeLimitFor 这台机器现在的免费导出额度，0 表示不限。
func freeLimitFor(a *App) int {
	if a.Can(license.FeaturePhotosRescue) {
		return 0
	}
	return FreePhotosExport
}
