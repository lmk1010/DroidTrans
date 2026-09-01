package store

import (
	"os"
	"path/filepath"
	"testing"
)

func TestBatchAndPhotoRoundTrip(t *testing.T) {
	s, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	s.UpsertDevice("dev1", "小米 14")
	if err := s.SaveBatch(Batch{DeviceID: "dev1", BatchID: "b1", PhotoCount: 2, TotalSize: 100, Status: "uploading"}); err != nil {
		t.Fatal(err)
	}
	// 同一批次再存一次应当是更新，不是插入第二条
	if err := s.SaveBatch(Batch{DeviceID: "dev1", BatchID: "b1", PhotoCount: 5, TotalSize: 500, Status: "completed"}); err != nil {
		t.Fatal(err)
	}
	batches, err := s.Batches("dev1")
	if err != nil {
		t.Fatal(err)
	}
	if len(batches) != 1 {
		t.Fatalf("批次数 = %d, 想要 1", len(batches))
	}
	if batches[0].Status != "completed" || batches[0].PhotoCount != 5 {
		t.Errorf("批次没被更新: %+v", batches[0])
	}

	s.AddPhoto("dev1", "b1", "a.jpg", "/tmp/a.jpg", 10)
	s.AddPhoto("dev1", "b1", "a.jpg", "/tmp/a.jpg", 10) // 重复不应产生第二条
	photos, err := s.Photos("dev1", "b1")
	if err != nil {
		t.Fatal(err)
	}
	if len(photos) != 1 {
		t.Fatalf("照片数 = %d, 想要 1", len(photos))
	}

	devs, err := s.Devices()
	if err != nil {
		t.Fatal(err)
	}
	if len(devs) != 1 || devs[0]["device_id"] != "dev1" {
		t.Errorf("设备列表异常: %+v", devs)
	}
}

func TestExistingPathOnlyMatchesRealFile(t *testing.T) {
	dir := t.TempDir()
	s, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	real := filepath.Join(dir, "real.jpg")
	if err := os.WriteFile(real, []byte("0123456789"), 0o644); err != nil {
		t.Fatal(err)
	}
	s.AddPhoto("dev1", "b1", "real.jpg", real, 10)
	s.AddPhoto("dev1", "b1", "gone.jpg", filepath.Join(dir, "gone.jpg"), 10)

	if got := s.ExistingPath("real.jpg", 10); got != real {
		t.Errorf("秒传没命中已存在文件: %q", got)
	}
	if got := s.ExistingPath("gone.jpg", 10); got != "" {
		t.Errorf("文件已不在磁盘上却被当成可秒传: %q", got)
	}
	if got := s.ExistingPath("real.jpg", 999); got != "" {
		t.Errorf("大小不一致却命中: %q", got)
	}
}

func TestExistingPathForDeviceDoesNotCrossDevices(t *testing.T) {
	dir := t.TempDir()
	s, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	real := filepath.Join(dir, "real.jpg")
	if err := os.WriteFile(real, []byte("0123456789"), 0o644); err != nil {
		t.Fatal(err)
	}
	s.AddPhoto("dev-a", "b1", "same.jpg", real, 10)

	if got := s.ExistingPathForDevice("dev-a", "same.jpg", 10); got != real {
		t.Errorf("同一设备没有命中: %q", got)
	}
	if got := s.ExistingPathForDevice("dev-b", "same.jpg", 10); got != "" {
		t.Errorf("不同设备不应命中: %q", got)
	}
}

func TestClear(t *testing.T) {
	s, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	s.UpsertDevice("d", "n")
	_ = s.SaveBatch(Batch{DeviceID: "d", BatchID: "b"})
	if err := s.Clear(); err != nil {
		t.Fatal(err)
	}
	devs, _ := s.Devices()
	if len(devs) != 0 {
		t.Errorf("清空后仍有设备: %+v", devs)
	}
}

func TestPruneEmptyBatches(t *testing.T) {
	s, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	_ = s.SaveBatch(Batch{DeviceID: "d", BatchID: "empty", PhotoCount: 0})
	_ = s.SaveBatch(Batch{DeviceID: "d", BatchID: "real", PhotoCount: 2})
	s.AddPhoto("d", "real", "a.jpg", "/tmp/a.jpg", 10)

	if n := s.PruneEmptyBatches(); n != 1 {
		t.Fatalf("清掉了 %d 个空批次，想要 1", n)
	}
	rows, _ := s.Batches("d")
	if len(rows) != 1 || rows[0].BatchID != "real" {
		t.Errorf("剩下的批次不对: %+v", rows)
	}
}

func TestDeleteBatch(t *testing.T) {
	s, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	_ = s.SaveBatch(Batch{DeviceID: "d", BatchID: "b", PhotoCount: 1})
	s.AddPhoto("d", "b", "a.jpg", "/tmp/a.jpg", 1)
	s.DeleteBatch("d", "b")
	if rows, _ := s.Batches("d"); len(rows) != 0 {
		t.Errorf("批次没删掉: %+v", rows)
	}
	if photos, _ := s.Photos("d", "b"); len(photos) != 0 {
		t.Errorf("照片记录没删掉: %+v", photos)
	}
}
