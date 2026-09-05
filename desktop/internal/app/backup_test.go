package app

import (
	"context"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"droidtrans/internal/store"
)

func backupApp(t *testing.T) (*App, string) {
	t.Helper()
	dir := t.TempDir()
	st, err := store.Open(filepath.Join(dir, "db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	a := newTestApp(t)
	a.Store = st
	return a, dir
}

func writeFile(t *testing.T, p, body string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p, []byte(body), 0o644); err != nil {
		t.Fatal(err)
	}
}

// 备份一遍，返回这一次新增了多少、复用了多少。
func runOnce(t *testing.T, a *App, p store.BackupPlan, snapshot string) (int, int) {
	t.Helper()
	items, err := a.backupSource(p)
	if err != nil {
		t.Fatal(err)
	}
	runID, err := a.Store.StartBackupRun(p.ID, snapshot)
	if err != nil {
		t.Fatal(err)
	}
	a.setBackup(func(s *backupState) { *s = backupState{Running: true, PlanID: p.ID, Snapshot: snapshot} })
	a.runBackup(context.Background(), p, runID, snapshot, items)
	runs, _ := a.Store.BackupRuns(p.ID)
	for _, r := range runs {
		if r.Snapshot == snapshot {
			return r.Added, r.Reused
		}
	}
	t.Fatalf("快照 %s 没留下记录", snapshot)
	return 0, 0
}

// 差异备份的全部意义：第二次只搬新增的，没变的挂硬链接。
func TestBackupSecondRunOnlyCopiesNewFiles(t *testing.T) {
	a, root := backupApp(t)
	src := filepath.Join(root, "src")
	dest := filepath.Join(root, "backup")
	writeFile(t, filepath.Join(src, "a.jpg"), "aaa")
	writeFile(t, filepath.Join(src, "sub", "b.jpg"), "bbb")

	id, err := a.Store.SaveBackupPlan(store.BackupPlan{
		Name: "测试", SourceKind: "folder", SourceID: src, Dest: dest,
	})
	if err != nil {
		t.Fatal(err)
	}
	p, _ := a.Store.BackupPlan(id)

	if added, reused := runOnce(t, a, p, "20260101_000000"); added != 2 || reused != 0 {
		t.Fatalf("第一次应该全是新增，得到 added=%d reused=%d", added, reused)
	}

	// 一个都没改，再备一次：应该一个都不用搬
	if added, reused := runOnce(t, a, p, "20260101_000001"); added != 0 || reused != 2 {
		t.Fatalf("没改动的第二次应该全部复用，得到 added=%d reused=%d", added, reused)
	}

	// 加一张新的，只有它算新增
	writeFile(t, filepath.Join(src, "c.jpg"), "ccc")
	if added, reused := runOnce(t, a, p, "20260101_000002"); added != 1 || reused != 2 {
		t.Fatalf("只加了一张，得到 added=%d reused=%d", added, reused)
	}

	// 每个快照都得是完整的一份，而不是「只有那次新增的几张」
	for _, snap := range []string{"20260101_000001", "20260101_000002"} {
		for _, rel := range []string{"a.jpg", "sub/b.jpg"} {
			p2 := filepath.Join(dest, snap, filepath.FromSlash(rel))
			if _, err := os.Stat(p2); err != nil {
				t.Errorf("快照 %s 里少了 %s：%v", snap, rel, err)
			}
		}
	}
}

// 删掉一个快照，其他快照里的照片一张都不能少——硬链接的意义就在这儿。
func TestDeletingOneSnapshotKeepsTheOthers(t *testing.T) {
	a, root := backupApp(t)
	src := filepath.Join(root, "src")
	dest := filepath.Join(root, "backup")
	writeFile(t, filepath.Join(src, "keep.jpg"), "hello")

	id, _ := a.Store.SaveBackupPlan(store.BackupPlan{
		Name: "测试", SourceKind: "folder", SourceID: src, Dest: dest,
	})
	p, _ := a.Store.BackupPlan(id)
	runOnce(t, a, p, "20260101_000000")
	runOnce(t, a, p, "20260101_000001")

	if err := os.RemoveAll(filepath.Join(dest, "20260101_000000")); err != nil {
		t.Fatal(err)
	}
	body, err := os.ReadFile(filepath.Join(dest, "20260101_000001", "keep.jpg"))
	if err != nil {
		t.Fatalf("删掉旧快照之后新快照也没了：%v", err)
	}
	if string(body) != "hello" {
		t.Errorf("内容变了：%q", body)
	}
}

// 源文件被删了之后，旧快照里那份还得在——备份的意义就是「删了还能找回来」。
func TestDeletedSourceFileStaysInOldSnapshot(t *testing.T) {
	a, root := backupApp(t)
	src := filepath.Join(root, "src")
	dest := filepath.Join(root, "backup")
	writeFile(t, filepath.Join(src, "gone.jpg"), "data")

	id, _ := a.Store.SaveBackupPlan(store.BackupPlan{
		Name: "测试", SourceKind: "folder", SourceID: src, Dest: dest,
	})
	p, _ := a.Store.BackupPlan(id)
	runOnce(t, a, p, "20260101_000000")

	if err := os.Remove(filepath.Join(src, "gone.jpg")); err != nil {
		t.Fatal(err)
	}
	runOnce(t, a, p, "20260101_000001")

	if _, err := os.Stat(filepath.Join(dest, "20260101_000000", "gone.jpg")); err != nil {
		t.Errorf("源文件删了之后，旧快照里那份也没了：%v", err)
	}
	if _, err := os.Stat(filepath.Join(dest, "20260101_000001", "gone.jpg")); err == nil {
		t.Error("源里已经没有的文件，不该出现在新快照里")
	}
}

// 备份到源自己里面会无限套娃：这一轮备出来的文件，下一轮又成了要备份的源。
func TestBackupIntoItsOwnSourceIsRejected(t *testing.T) {
	a, root := backupApp(t)
	src := filepath.Join(root, "photos")
	writeFile(t, filepath.Join(src, "a.jpg"), "a")

	if !withinDir(src, filepath.Join(src, "backup")) {
		t.Fatal("withinDir 没认出子目录")
	}
	if withinDir(src, filepath.Join(root, "elsewhere")) {
		t.Fatal("withinDir 把不相干的目录也算进去了")
	}
	_ = a
}

// 同一个「源 + 目标」不该攒出好几个计划：用户点两次「新建」，得到的应该还是那一个。
func TestSavePlanIsIdempotent(t *testing.T) {
	a, root := backupApp(t)
	src := filepath.Join(root, "src")
	dest := filepath.Join(root, "dest")
	first, err := a.Store.SaveBackupPlan(store.BackupPlan{Name: "旧名字", SourceKind: "folder", SourceID: src, Dest: dest})
	if err != nil {
		t.Fatal(err)
	}
	again, err := a.Store.SaveBackupPlan(store.BackupPlan{Name: "新名字", SourceKind: "folder", SourceID: src, Dest: dest})
	if err != nil {
		t.Fatal(err)
	}
	if first != again {
		t.Fatalf("同一个源+目标存出了两个计划：%d 和 %d", first, again)
	}
	p, _ := a.Store.BackupPlan(first)
	if !strings.Contains(p.Name, "新名字") {
		t.Errorf("重复保存时名字没更新：%q", p.Name)
	}
}

// 自动备份得有冷却：数据线接触不良的话，一分钟能反复断连好几次，
// 每次都重备一遍等于把用户的磁盘和时间当免费的。
func TestAutoBackupRespectsCooldown(t *testing.T) {
	a, root := backupApp(t)
	id, err := a.Store.SaveBackupPlan(store.BackupPlan{
		Name: "手机", SourceKind: "usb", SourceID: "", Dest: filepath.Join(root, "dest"),
	})
	if err != nil {
		t.Fatal(err)
	}
	a.Store.SetBackupAuto(id, true)

	p, _ := a.Store.BackupPlan(id)
	if !p.Auto {
		t.Fatal("自动备份没开起来")
	}
	if p.LastAuto != "" {
		t.Fatalf("还没跑过就有时间戳：%q", p.LastAuto)
	}

	a.Store.MarkBackupAuto(id)
	p, _ = a.Store.BackupPlan(id)
	last, err := time.Parse(time.RFC3339, p.LastAuto)
	if err != nil {
		t.Fatalf("时间戳存坏了 %q: %v", p.LastAuto, err)
	}
	if time.Since(last) > autoBackupCooldown {
		t.Error("刚记下的时间就已经过了冷却期")
	}

	// 冷却期内再插一次线，不该再跑一遍：没有手机连着时 backupSource 会报错，
	// 真跑起来这里就会留下一条 failed 记录。
	a.autoBackupOnConnect()
	runs, _ := a.Store.BackupRuns(id)
	if len(runs) != 0 {
		t.Errorf("冷却期内又备了一次：%d 条记录", len(runs))
	}
}

// 文件夹源没有「连上」这回事，不该能打开自动备份。
func TestAutoBackupOnlyForPhoneSource(t *testing.T) {
	a, root := backupApp(t)
	src := filepath.Join(root, "src")
	writeFile(t, filepath.Join(src, "a.jpg"), "a")
	id, _ := a.Store.SaveBackupPlan(store.BackupPlan{
		Name: "文件夹", SourceKind: "folder", SourceID: src, Dest: filepath.Join(root, "dest"),
	})
	a.Store.SetBackupAuto(id, true)

	// 就算库里被写成了开，触发时也只认手机源
	a.autoBackupOnConnect()
	runs, _ := a.Store.BackupRuns(id)
	if len(runs) != 0 {
		t.Errorf("文件夹源被自动备份触发了：%d 条记录", len(runs))
	}
}

// 备份跑到一半把计划删了，不能留下一堆没有主人的记录：
// 它们再也没人查得到、也没人删得掉，只会让库一直长大。
func TestDeletingPlanMidRunLeavesNoOrphans(t *testing.T) {
	a, root := backupApp(t)
	src := filepath.Join(root, "src")
	for i := 0; i < 40; i++ {
		writeFile(t, filepath.Join(src, "f"+strconv.Itoa(i)+".bin"), strings.Repeat("x", 512))
	}
	id, _ := a.Store.SaveBackupPlan(store.BackupPlan{
		Name: "半路删掉", SourceKind: "folder", SourceID: src, Dest: filepath.Join(root, "dest"),
	})
	p, _ := a.Store.BackupPlan(id)

	items, err := a.backupSource(p)
	if err != nil {
		t.Fatal(err)
	}
	runID, _ := a.Store.StartBackupRun(p.ID, "20260101_000000")
	ctx, cancel := context.WithCancel(context.Background())
	a.setBackup(func(s *backupState) {
		*s = backupState{Running: true, PlanID: p.ID, Snapshot: "20260101_000000"}
	})
	a.backupCancel = cancel

	done := make(chan struct{})
	go func() { a.runBackup(ctx, p, runID, "20260101_000000", items); close(done) }()
	// 让它先备几个，再从「删计划」那条路把它掐掉
	time.Sleep(20 * time.Millisecond)
	w := httptest.NewRecorder()
	a.backupDeletePlan(w, httptest.NewRequest("POST", "/api/backup/plans/delete",
		strings.NewReader(`{"id":`+strconv.FormatInt(id, 10)+`}`)))
	<-done

	a.Store.PruneOrphanBackupFiles()
	files, _ := a.Store.BackupRunFiles(runID)
	if len(files) != 0 {
		t.Errorf("计划删了之后还剩 %d 条无主的文件记录", len(files))
	}
	if runs, _ := a.Store.BackupRuns(id); len(runs) != 0 {
		t.Errorf("计划删了之后还剩 %d 条无主的快照记录", len(runs))
	}
}

// rel 是从设备上的路径切出来的，不是自己拼的。带 .. 的一条就能把文件写到
// 快照目录外面去。
func TestBackupRefusesToWriteOutsideTheSnapshot(t *testing.T) {
	a, root := backupApp(t)
	dest := filepath.Join(root, "dest")
	id, _ := a.Store.SaveBackupPlan(store.BackupPlan{
		Name: "越界", SourceKind: "folder", SourceID: filepath.Join(root, "src"), Dest: dest,
	})
	p, _ := a.Store.BackupPlan(id)

	escaped := filepath.Join(root, "escaped.txt")
	items := []backupItem{{
		rel:   "../../escaped.txt",
		size:  4,
		fetch: func(d string) error { return os.WriteFile(d, []byte("boom"), 0o644) },
	}}
	runID, _ := a.Store.StartBackupRun(p.ID, "20260101_000000")
	a.setBackup(func(s *backupState) { *s = backupState{Running: true, PlanID: p.ID} })
	a.runBackup(context.Background(), p, runID, "20260101_000000", items)

	if _, err := os.Stat(escaped); err == nil {
		t.Error("带 .. 的路径把文件写到快照目录外面去了")
	}
	runs, _ := a.Store.BackupRuns(p.ID)
	if len(runs) == 0 || runs[0].Failed != 1 {
		t.Errorf("越界的那一项应该记成失败，实际：%+v", runs)
	}
}

// 源文件被就地改写，已经备好的快照不能跟着变。
//
// 一开始本机源是硬链接过去的——「一秒钟备完，不多占一个字节」，看着很聪明，
// 实际上快照和源文件共用同一个 inode：源那边被追加、被 dd、被 sqlite 写一下，
// 所有历史快照的内容同时被改掉。一份会随原件变化的「备份」不是备份。
func TestBackupSurvivesInPlaceEditOfTheSource(t *testing.T) {
	a, root := backupApp(t)
	src := filepath.Join(root, "src")
	dest := filepath.Join(root, "dest")
	note := filepath.Join(src, "notes.txt")
	writeFile(t, note, "ORIGINAL")

	id, _ := a.Store.SaveBackupPlan(store.BackupPlan{
		Name: "就地改写", SourceKind: "folder", SourceID: src, Dest: dest,
	})
	p, _ := a.Store.BackupPlan(id)
	runOnce(t, a, p, "20260101_000000")

	// 就地写，不删不换名——inode 不变，硬链接就会跟着变
	f, err := os.OpenFile(note, os.O_WRONLY, 0o644)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.WriteAt([]byte("MUTATED!"), 0); err != nil {
		t.Fatal(err)
	}
	f.Close()

	got, err := os.ReadFile(filepath.Join(dest, "20260101_000000", "notes.txt"))
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != "ORIGINAL" {
		t.Fatalf("源文件被就地改写之后，快照里的内容也变了：%q", got)
	}
}
