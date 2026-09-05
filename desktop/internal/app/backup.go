package app

// 备份。
//
// 和「USB 导入」「已接收」是两回事：那两个回答「这一次搬了什么」，
// 备份回答「这台设备的照片在这台电脑上留了几份、每份差在哪、丢了能不能找回来」。
//
// 做法照搬 Time Machine：每次备份生成一个快照目录，里面**看起来是完整的一份**，
// 但只有这次新增的文件是真的写了一遍，其余全是指向上一份的硬链接。
// 于是既能「随便挑一个时间点，那天的照片全在」，又不会备十次就占十倍空间。
//
// 硬链接还顺带解决了删除：删掉某个快照目录，其他快照里的照片一张都不会少——
// 删的只是一个链接，文件本身要等最后一个链接消失才真的释放。
// 这是「每份都完整」和「只占增量」能同时成立的唯一原因，别改成软链接或拷贝。

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"droidtrans/internal/store"
)

type backupState struct {
	Running    bool      `json:"running"`
	PlanID     int64     `json:"plan_id"`
	RunID      int64     `json:"run_id"`
	Snapshot   string    `json:"snapshot"`
	Total      int       `json:"total"`
	Done       int       `json:"done"`
	Added      int       `json:"added"`
	Reused     int       `json:"reused"`
	Failed     int       `json:"failed"`
	BytesAdded int64     `json:"bytes_added"`
	BytesTotal int64     `json:"bytes_total"`
	Current    string    `json:"current"`
	Stage      string    `json:"stage"`
	Error      string    `json:"error"`
	StartedAt  time.Time `json:"-"`
	ElapsedSec int       `json:"elapsed_sec"`
}

// backupItem 一个待备份的文件。源是手机还是本机目录，到这一层就没区别了。
type backupItem struct {
	// rel 是它在快照里的相对路径。带上目录，不然两个相册里的 IMG_0001.JPG 会互相覆盖。
	rel string
	// remote 手机源在设备上的原始路径。量大小要用它——用 rel 反拼前缀会拼错，
	// 而存储根在不同机型上是 /sdcard、/storage/emulated/0、/storage/self/primary 三选一。
	remote string
	size   int64
	// fetch 把它取到 dest。手机源是 adb pull，本机源是硬链接。
	fetch func(dest string) error
}

// relOnDevice 把设备上的绝对路径变成快照里的相对路径。
// 剥的前缀要和 remoteToLocal 那份保持一致，少一个就会在
// /storage/self/primary 的机型上留下一层莫名其妙的目录。
func relOnDevice(remote string) string {
	rel := remote
	for _, p := range []string{"/sdcard/", "/storage/emulated/0/", "/storage/self/primary/"} {
		rel = strings.TrimPrefix(rel, p)
	}
	return strings.TrimPrefix(rel, "/")
}

func (a *App) backupSnapshotDir(p store.BackupPlan, snapshot string) string {
	return filepath.Join(p.Dest, snapshot)
}

// backupSource 把一个计划展开成待备份的文件清单。
func (a *App) backupSource(p store.BackupPlan) ([]backupItem, error) {
	switch p.SourceKind {
	case "usb":
		if !a.connectedFlag() {
			return nil, fmt.Errorf("手机没连上")
		}
		// 存储根必须先探出来。写死 "" 的话 discoverAlbums 会去找 /DCIM、/Pictures
		// 这些根目录，安卓上一个都不存在，扫出来永远是空的——备份看着「成功」，
		// 实际一个文件都没备。
		storage := a.storageRoot()
		if storage == "" {
			return nil, fmt.Errorf("读不到手机存储")
		}
		var items []backupItem
		for _, album := range a.discoverAlbums(storage) {
			for _, remote := range a.listAlbumMedia(album) {
				remote := remote
				items = append(items, backupItem{
					rel:    relOnDevice(remote),
					remote: remote,
					fetch: func(dest string) error {
						return a.ADB.Pull(remote, dest, 10*time.Minute)
					},
				})
			}
		}
		return items, nil

	case "folder":
		root := p.SourceID
		if st, err := os.Stat(root); err != nil || !st.IsDir() {
			return nil, fmt.Errorf("源目录不在了：%s", root)
		}
		var items []backupItem
		err := filepath.Walk(root, func(p2 string, info os.FileInfo, err error) error {
			if err != nil || info == nil || info.IsDir() {
				return nil
			}
			if strings.HasPrefix(info.Name(), ".") {
				return nil
			}
			rel, err := filepath.Rel(root, p2)
			if err != nil {
				return nil
			}
			src := p2
			items = append(items, backupItem{
				rel:  rel,
				size: info.Size(),
				// 老老实实拷。硬链接到源文件会和它共用 inode，源那边被就地改写时
				// 快照里的内容跟着一起变——那不叫备份。快照之间才可以硬链接。
				fetch: func(dest string) error { return copyFile(src, dest) },
			})
			return nil
		})
		return items, err
	}
	return nil, fmt.Errorf("不认识的备份源：%s", p.SourceKind)
}

// backupSizes 手机源的大小要一个个问，串行问几千次能问上好几分钟。
func (a *App) backupSizes(items []backupItem) {
	need := []int{}
	for i := range items {
		if items[i].size <= 0 {
			need = append(need, i)
		}
	}
	if len(need) == 0 {
		return
	}
	workers := 8
	if len(need) < workers {
		workers = len(need)
	}
	jobs := make(chan int)
	var wg sync.WaitGroup
	wg.Add(workers)
	for i := 0; i < workers; i++ {
		go func() {
			defer wg.Done()
			for idx := range jobs {
				items[idx].size = a.ADB.FileSize(items[idx].remote)
			}
		}()
	}
	for _, i := range need {
		jobs <- i
	}
	close(jobs)
	wg.Wait()
}

func (a *App) setBackup(f func(*backupState)) {
	a.backupMu.Lock()
	f(&a.backup)
	a.backupMu.Unlock()
}

func (a *App) runBackup(ctx context.Context, p store.BackupPlan, runID int64, snapshot string, items []backupItem) {
	dir := a.backupSnapshotDir(p, snapshot)
	total := int64(0)
	for _, it := range items {
		total += it.size
	}
	a.setBackup(func(s *backupState) {
		s.Stage = "copy"
		s.Total = len(items)
		s.BytesTotal = total
	})

	added, reused, failed := 0, 0, 0
	var bytesAdded int64
	for _, it := range items {
		select {
		case <-ctx.Done():
			a.finishBackup(runID, p, "stopped", added, reused, failed, bytesAdded, total, "")
			return
		default:
		}
		dest := filepath.Join(dir, filepath.FromSlash(it.rel))
		// rel 是从设备上的路径切出来的，不是我们自己拼的。带 .. 的一条就能把文件
		// 写到快照目录外面去——同一个道理，删除类接口也只认输出目录内的路径。
		if !withinDir(dir, dest) {
			failed++
			a.setBackup(func(s *backupState) { s.Failed = failed; s.Done = added + reused + failed })
			continue
		}
		a.setBackup(func(s *backupState) { s.Current = path.Base(it.rel) })

		// 差异备份就在这一句：这个计划以前备过同名同大小的文件，就只挂一个硬链接。
		if old := a.Store.BackedUpPath(p.ID, path.Base(it.rel), it.size); old != "" {
			if err := linkOrCopy(old, dest); err == nil {
				reused++
				a.Store.AddBackupFile(p.ID, runID, path.Base(it.rel), it.rel, dest, it.size, true)
				a.setBackup(func(s *backupState) { s.Reused = reused; s.Done = added + reused + failed })
				continue
			}
		}

		if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
			failed++
			a.setBackup(func(s *backupState) { s.Failed = failed; s.Done = added + reused + failed })
			continue
		}
		if err := it.fetch(dest); err != nil {
			failed++
			_ = os.Remove(dest)
			a.setBackup(func(s *backupState) { s.Failed = failed; s.Done = added + reused + failed })
			continue
		}
		size := it.size
		if st, err := os.Stat(dest); err == nil {
			size = st.Size()
		}
		added++
		bytesAdded += size
		a.Store.AddBackupFile(p.ID, runID, path.Base(it.rel), it.rel, dest, size, false)
		a.setBackup(func(s *backupState) {
			s.Added = added
			s.BytesAdded = bytesAdded
			s.Done = added + reused + failed
		})
	}

	// 一个指向最新快照的入口，省得用户每次去一堆时间戳目录里找最新的那个
	link := filepath.Join(p.Dest, "latest")
	_ = os.Remove(link)
	_ = os.Symlink(dir, link)

	a.finishBackup(runID, p, "done", added, reused, failed, bytesAdded, total, "")
}

func (a *App) finishBackup(runID int64, p store.BackupPlan, status string,
	added, reused, failed int, bytesAdded, bytesTotal int64, note string) {
	a.Store.FinishBackupRun(runID, store.BackupRun{
		Status: status, Added: added, Reused: reused, Failed: failed,
		BytesAdded: bytesAdded, BytesTotal: bytesTotal, Note: note,
	})
	// 刚点开始就被停掉，一张都没落盘：别在列表里留一个空快照。
	//
	// 只对「取消」这么干。正常跑完但一项都没有（源里的照片被清空了），那条记录
	// 反而必须留着——用户翻备份列表时要看得出「这一次跑过，那天源里已经空了」，
	// 而不是以为这天压根没备份。
	if status == "stopped" && added+reused == 0 {
		a.Store.DeleteBackupRun(runID)
		_ = os.Remove(a.backupSnapshotDir(p, a.backupSnapshot()))
	}
	a.setBackup(func(s *backupState) {
		s.Running = false
		s.Stage = status
		s.Current = ""
		s.ElapsedSec = int(time.Since(s.StartedAt).Seconds())
	})
	if a.OnNotify != nil && added+reused > 0 {
		if a.lang() == "en" {
			a.OnNotify(p.Name+" backed up",
				fmt.Sprintf("%d new, %d unchanged", added, reused))
		} else {
			a.OnNotify(p.Name+" 备份完成",
				fmt.Sprintf("新增 %d 项，%d 项没变", added, reused))
		}
	}
}

func (a *App) backupSnapshot() string {
	a.backupMu.Lock()
	defer a.backupMu.Unlock()
	return a.backup.Snapshot
}

// ---- HTTP ----

func (a *App) backupPlans(w http.ResponseWriter, r *http.Request) {
	plans, err := a.Store.BackupPlans()
	if err != nil {
		writeJSON(w, 500, map[string]any{"success": false, "error": err.Error()})
		return
	}
	out := make([]map[string]any, 0, len(plans))
	for _, p := range plans {
		runs, _ := a.Store.BackupRuns(p.ID)
		var last *store.BackupRun
		if len(runs) > 0 {
			last = &runs[0]
		}
		var bytes int64
		for _, r := range runs {
			bytes += r.BytesAdded
		}
		out = append(out, map[string]any{
			"id": p.ID, "name": p.Name, "source_kind": p.SourceKind,
			"source_id": p.SourceID, "dest": p.Dest,
			"runs": len(runs), "last": last, "bytes_on_disk": bytes, "auto": p.Auto,
		})
	}
	writeJSON(w, 200, map[string]any{"success": true, "plans": out})
}

func (a *App) backupSavePlan(w http.ResponseWriter, r *http.Request) {
	body := readJSON(r)
	name, _ := body["name"].(string)
	kind, _ := body["source_kind"].(string)
	src, _ := body["source_id"].(string)
	dest, _ := body["dest"].(string)
	if kind != "usb" && kind != "folder" {
		writeJSON(w, 400, map[string]any{"success": false, "error": "source_kind 只能是 usb 或 folder"})
		return
	}
	if dest == "" {
		writeJSON(w, 400, map[string]any{"success": false, "error": "没有选备份到哪儿"})
		return
	}
	if kind == "folder" && src == "" {
		writeJSON(w, 400, map[string]any{"success": false, "error": "没有选要备份的文件夹"})
		return
	}
	// 备份到源自己里面会无限套娃：备份产生的文件下一轮又成了要备份的源。
	if kind == "folder" && withinDir(src, dest) {
		writeJSON(w, 400, map[string]any{"success": false, "error": "备份目标不能放在源文件夹里面"})
		return
	}
	if name == "" {
		name = filepath.Base(src)
		if kind == "usb" {
			name = a.deviceModel()
		}
	}
	if err := os.MkdirAll(dest, 0o755); err != nil {
		writeJSON(w, 400, map[string]any{"success": false, "error": err.Error()})
		return
	}
	id, err := a.Store.SaveBackupPlan(store.BackupPlan{
		Name: name, SourceKind: kind, SourceID: src, Dest: dest,
	})
	if err != nil {
		writeJSON(w, 500, map[string]any{"success": false, "error": err.Error()})
		return
	}
	writeJSON(w, 200, map[string]any{"success": true, "id": id})
}

func (a *App) backupDeletePlan(w http.ResponseWriter, r *http.Request) {
	id := int64(readJSONNum(r, "id"))
	if id <= 0 {
		writeJSON(w, 400, map[string]any{"success": false, "error": "缺少 id"})
		return
	}
	// 正在备份的就是它：先停。不停的话它会一路跑完，把一堆 backup_files 写回
	// 一个已经不存在的计划名下——那些记录再也没人查得到，也再也没人删得掉。
	a.backupMu.Lock()
	if a.backup.Running && a.backup.PlanID == id && a.backupCancel != nil {
		a.backupCancel()
	}
	a.backupMu.Unlock()

	// 只删记录，不动磁盘上的备份——用户删的是「这个计划」，不是「我的照片」
	a.Store.DeleteBackupPlan(id)
	writeJSON(w, 200, map[string]any{"success": true})
}

func (a *App) backupRuns(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(r.URL.Query().Get("plan"), 10, 64)
	runs, err := a.Store.BackupRuns(id)
	if err != nil {
		writeJSON(w, 500, map[string]any{"success": false, "error": err.Error()})
		return
	}
	p, _ := a.Store.BackupPlan(id)
	out := make([]map[string]any, 0, len(runs))
	for _, run := range runs {
		dir := a.backupSnapshotDir(p, run.Snapshot)
		_, err := os.Stat(dir)
		out = append(out, map[string]any{
			"id": run.ID, "snapshot": run.Snapshot, "started_at": run.StartedAt,
			"finished_at": run.FinishedAt, "status": run.Status,
			"added": run.Added, "reused": run.Reused, "failed": run.Failed,
			"bytes_added": run.BytesAdded, "bytes_total": run.BytesTotal,
			"path": dir, "missing": err != nil,
		})
	}
	writeJSON(w, 200, map[string]any{"success": true, "plan": p, "runs": out})
}

func (a *App) backupStart(w http.ResponseWriter, r *http.Request) {
	id := int64(readJSONNum(r, "plan_id"))
	p, err := a.Store.BackupPlan(id)
	if err != nil {
		writeJSON(w, 404, map[string]any{"success": false, "error": "没有这个备份计划"})
		return
	}
	if err := a.startBackupPlan(p); err != nil {
		writeJSON(w, 409, map[string]any{"success": false, "error": err.Error()})
		return
	}
	// 清点还没开始，这里给不出总数。界面靠 /api/backup/status 轮询拿。
	writeJSON(w, 200, map[string]any{"success": true})
}

func (a *App) backupStop(w http.ResponseWriter, r *http.Request) {
	a.backupMu.Lock()
	if a.backupCancel != nil {
		a.backupCancel()
	}
	a.backupMu.Unlock()
	writeJSON(w, 200, map[string]any{"success": true})
}

func (a *App) backupStatus(w http.ResponseWriter, r *http.Request) {
	a.backupMu.Lock()
	st := a.backup
	a.backupMu.Unlock()
	if st.Running {
		st.ElapsedSec = int(time.Since(st.StartedAt).Seconds())
	}
	writeJSON(w, 200, map[string]any{"success": true, "backup": st})
}

func (a *App) backupDeleteRun(w http.ResponseWriter, r *http.Request) {
	body := readJSON(r)
	runID := int64(toNum(body["run_id"]))
	planID := int64(toNum(body["plan_id"]))
	if runID <= 0 {
		writeJSON(w, 400, map[string]any{"success": false, "error": "缺少 run_id"})
		return
	}
	p, err := a.Store.BackupPlan(planID)
	if err != nil {
		writeJSON(w, 404, map[string]any{"success": false, "error": "没有这个备份计划"})
		return
	}
	runs, _ := a.Store.BackupRuns(planID)
	for _, run := range runs {
		if run.ID != runID {
			continue
		}
		dir := a.backupSnapshotDir(p, run.Snapshot)
		// 只删这个快照目录。同一份内容在别的快照里是另一个硬链接，一张都不会少。
		if withinDir(p.Dest, dir) {
			_ = os.RemoveAll(dir)
		}
		a.Store.DeleteBackupRun(runID)
		writeJSON(w, 200, map[string]any{"success": true})
		return
	}
	writeJSON(w, 404, map[string]any{"success": false, "error": "没有这个快照"})
}

// toNum JSON 里的数字过来都是 float64，取 id 时得先落地。
func toNum(v any) float64 {
	if f, ok := v.(float64); ok {
		return f
	}
	return 0
}

func readJSONNum(r *http.Request, key string) float64 {
	return toNum(readJSON(r)[key])
}

// deviceModel 当前这台 USB 手机的型号，用来给备份计划起个默认名字。
func (a *App) deviceModel() string {
	a.devMu.Lock()
	defer a.devMu.Unlock()
	if m := strings.TrimSpace(a.model); m != "" {
		return m
	}
	return "手机"
}

// withinDir 判断 child 是不是在 parent 里面（含自身）。
func withinDir(parent, child string) bool {
	p, err1 := filepath.Abs(parent)
	c, err2 := filepath.Abs(child)
	if err1 != nil || err2 != nil {
		return false
	}
	rel, err := filepath.Rel(p, c)
	if err != nil {
		return false
	}
	return rel == "." || (!strings.HasPrefix(rel, "..") && !filepath.IsAbs(rel))
}

// autoBackupCooldown 同一个计划两次自动备份之间至少隔这么久。
//
// 没有它，拔一下线再插回去就会再备一遍；有些数据线接触不良，一分钟能反复
// 断连好几次。手动点「立即备份」不受这个限制——那是用户明确要的。
const autoBackupCooldown = 30 * time.Minute

// autoBackupOnConnect 手机插上来时，把开了自动备份的计划跑一遍。
func (a *App) autoBackupOnConnect() {
	a.backupMu.Lock()
	busy := a.backup.Running
	a.backupMu.Unlock()
	if busy {
		return
	}
	plans, err := a.Store.BackupPlans()
	if err != nil {
		return
	}
	for _, p := range plans {
		if !p.Auto || p.SourceKind != "usb" {
			continue
		}
		if last, err := time.Parse(time.RFC3339, p.LastAuto); err == nil &&
			time.Since(last) < autoBackupCooldown {
			continue
		}
		// 启动成功了才记冷却。反过来的话，手机在这一瞬掉线（或另一个备份正好在跑）
		// 导致启动失败，冷却却已经记上，接下来半小时都不会再试。
		if err := a.startBackupPlan(p); err != nil {
			continue
		}
		a.Store.MarkBackupAuto(p.ID)
		return // 一次只跑一个，两个计划抢同一根线只会互相拖慢
	}
}

// startBackupPlan 真正把一个计划跑起来。手动和自动共用这一条路。
//
// 只负责占住位子然后立刻返回，剩下的全在后台做。清点相册和逐个问文件大小
// 都要走 adb，几千张照片能花上好几分钟——放在 HTTP 处理函数里做的话，
// 「立即备份」这个请求就挂在那儿，界面上按钮卡住、进度条不出现，
// 而「正在清点」「正在核对大小」这两个状态永远没机会显示出来。
func (a *App) startBackupPlan(p store.BackupPlan) error {
	// 检查和置位必须在同一个锁里。先 Unlock 再置位的话，
	// 两个几乎同时进来的请求会双双通过检查，然后一起开跑。
	snapshot := time.Now().Format("20060102_150405")
	a.backupMu.Lock()
	if a.backup.Running {
		a.backupMu.Unlock()
		return fmt.Errorf("已经有一个备份在跑了")
	}
	a.backup = backupState{Running: true, PlanID: p.ID, Snapshot: snapshot,
		Stage: "scan", StartedAt: time.Now()}
	a.backupMu.Unlock()

	go func() {
		items, err := a.backupSource(p)
		if err != nil {
			a.setBackup(func(s *backupState) { s.Running = false; s.Stage = "failed"; s.Error = err.Error() })
			return
		}
		if len(items) == 0 {
			// 和「备完了」区分开：界面要能说出「源里没有可备份的文件」，
			// 否则用户点完按钮看到的是一片安静，分不清是没反应还是已经好了。
			a.setBackup(func(s *backupState) { s.Running = false; s.Stage = "empty" })
			return
		}
		if p.SourceKind == "usb" {
			a.setBackup(func(s *backupState) { s.Stage = "size"; s.Total = len(items) })
			a.backupSizes(items)
		}
		sort.Slice(items, func(i, j int) bool { return items[i].rel < items[j].rel })

		runID, err := a.Store.StartBackupRun(p.ID, snapshot)
		if err != nil {
			a.setBackup(func(s *backupState) { s.Running = false; s.Stage = "failed"; s.Error = err.Error() })
			return
		}
		ctx, cancel := context.WithCancel(context.Background())
		a.backupMu.Lock()
		a.backup.RunID = runID
		a.backupCancel = cancel
		a.backupMu.Unlock()
		a.runBackup(ctx, p, runID, snapshot, items)
	}()
	return nil
}

func (a *App) backupSetAuto(w http.ResponseWriter, r *http.Request) {
	body := readJSON(r)
	id := int64(toNum(body["id"]))
	on, _ := body["auto"].(bool)
	if id <= 0 {
		writeJSON(w, 400, map[string]any{"success": false, "error": "缺少 id"})
		return
	}
	p, err := a.Store.BackupPlan(id)
	if err != nil {
		writeJSON(w, 404, map[string]any{"success": false, "error": "没有这个备份计划"})
		return
	}
	if on && p.SourceKind != "usb" {
		writeJSON(w, 400, map[string]any{"success": false,
			"error": "只有手机源能「连上就备份」——文件夹一直都在，没有「连上」这回事"})
		return
	}
	a.Store.SetBackupAuto(id, on)
	writeJSON(w, 200, map[string]any{"success": true})
}
