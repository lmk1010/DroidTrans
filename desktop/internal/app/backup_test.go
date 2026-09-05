package app

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

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
