package store

import (
	"database/sql"
	"os"
	"path/filepath"
	"time"

	_ "modernc.org/sqlite"
)

type Store struct {
	db *sql.DB
}

func Open(dir string) (*Store, error) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	db, err := sql.Open("sqlite", filepath.Join(dir, "android_transfer.db"))
	if err != nil {
		return nil, err
	}
	if _, err := db.Exec(`PRAGMA journal_mode=WAL`); err != nil {
		_ = db.Close()
		return nil, err
	}
	s := &Store{db: db}
	if err := s.migrate(); err != nil {
		_ = db.Close()
		return nil, err
	}
	return s, nil
}

func (s *Store) Close() error { return s.db.Close() }

func (s *Store) migrate() error {
	_, err := s.db.Exec(`
CREATE TABLE IF NOT EXISTS devices (
  device_id TEXT PRIMARY KEY,
  device_name TEXT,
  last_heartbeat TEXT,
  connected_at TEXT,
  photo_count INTEGER DEFAULT 0
);
CREATE TABLE IF NOT EXISTS batches (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id TEXT NOT NULL,
  batch_id TEXT NOT NULL,
  timestamp TEXT,
  photo_count INTEGER DEFAULT 0,
  total_size INTEGER DEFAULT 0,
  total_size_mb REAL DEFAULT 0,
  status TEXT DEFAULT 'completed',
  is_legacy INTEGER DEFAULT 0,
  duration_sec INTEGER DEFAULT 0,
  created_at TEXT DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(device_id, batch_id)
);
CREATE TABLE IF NOT EXISTS photos (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id TEXT NOT NULL,
  batch_id TEXT NOT NULL,
  name TEXT NOT NULL,
  path TEXT NOT NULL,
  size INTEGER DEFAULT 0,
  size_mb REAL DEFAULT 0,
  date TEXT,
  created_at TEXT DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(device_id, batch_id, name)
);
CREATE INDEX IF NOT EXISTS idx_batches_device ON batches(device_id);
CREATE INDEX IF NOT EXISTS idx_photos_batch ON photos(device_id, batch_id);
CREATE INDEX IF NOT EXISTS idx_photos_name_size ON photos(name, size);

-- 备份。和上面那套「批次」是两回事：批次记的是「某一次传输搬了什么」，
-- 备份记的是「这台设备的照片，在这台电脑上留了几份、每份差在哪」。
CREATE TABLE IF NOT EXISTS backup_plans (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  source_kind TEXT NOT NULL,
  source_id TEXT NOT NULL,
  dest TEXT NOT NULL,
  created_at TEXT DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(source_kind, source_id, dest)
);
CREATE TABLE IF NOT EXISTS backup_runs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  plan_id INTEGER NOT NULL,
  snapshot TEXT NOT NULL,
  started_at TEXT,
  finished_at TEXT,
  status TEXT DEFAULT 'running',
  added INTEGER DEFAULT 0,
  reused INTEGER DEFAULT 0,
  failed INTEGER DEFAULT 0,
  bytes_added INTEGER DEFAULT 0,
  bytes_total INTEGER DEFAULT 0,
  note TEXT,
  UNIQUE(plan_id, snapshot)
);
CREATE TABLE IF NOT EXISTS backup_files (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  plan_id INTEGER NOT NULL,
  run_id INTEGER NOT NULL,
  name TEXT NOT NULL,
  rel TEXT NOT NULL,
  path TEXT NOT NULL,
  size INTEGER DEFAULT 0,
  linked INTEGER DEFAULT 0
);
-- 差异备份每个文件都要查一次「以前备过吗」，这条索引是它唯一的依据
CREATE INDEX IF NOT EXISTS idx_backup_files_seen ON backup_files(plan_id, name, size);
CREATE INDEX IF NOT EXISTS idx_backup_files_run ON backup_files(run_id);
`)
	if err != nil {
		return err
	}
	// 后加的列。CREATE TABLE IF NOT EXISTS 对已经建好的表不会补列，
	// 老用户的库升上来会缺这一列，所以只能 ALTER——重复执行报的错咽掉就行。
	s.addColumn("backup_plans", "auto", "INTEGER DEFAULT 0")
	s.addColumn("backup_plans", "last_auto", "TEXT")
	return nil
}

func (s *Store) addColumn(table, col, decl string) {
	_, _ = s.db.Exec("ALTER TABLE " + table + " ADD COLUMN " + col + " " + decl)
}

type Batch struct {
	DeviceID    string  `json:"device_id"`
	BatchID     string  `json:"batch_id"`
	Timestamp   string  `json:"timestamp"`
	PhotoCount  int     `json:"photo_count"`
	TotalSize   int64   `json:"total_size"`
	TotalSizeMB float64 `json:"total_size_mb"`
	Status      string  `json:"status"`
	DurationSec int     `json:"duration_sec"`
}

func (s *Store) UpsertDevice(id, name string) {
	now := time.Now().Format(time.RFC3339)
	_, _ = s.db.Exec(`
INSERT INTO devices(device_id, device_name, last_heartbeat, connected_at)
VALUES(?,?,?,?)
ON CONFLICT(device_id) DO UPDATE SET device_name=excluded.device_name, last_heartbeat=excluded.last_heartbeat
`, id, name, now, now)
}

func (s *Store) SaveBatch(b Batch) error {
	_, err := s.db.Exec(`
INSERT INTO batches(device_id, batch_id, timestamp, photo_count, total_size, total_size_mb, status, duration_sec)
VALUES(?,?,?,?,?,?,?,?)
ON CONFLICT(device_id, batch_id) DO UPDATE SET
  photo_count=excluded.photo_count,
  total_size=excluded.total_size,
  total_size_mb=excluded.total_size_mb,
  status=excluded.status,
  duration_sec=excluded.duration_sec
`, b.DeviceID, b.BatchID, b.Timestamp, b.PhotoCount, b.TotalSize, b.TotalSizeMB, b.Status, b.DurationSec)
	return err
}

func (s *Store) ExistingPath(name string, size int64) string {
	return s.existingPath("", name, size)
}

func (s *Store) ExistingPathForDevice(deviceID, name string, size int64) string {
	return s.existingPath(deviceID, name, size)
}

func (s *Store) existingPath(deviceID, name string, size int64) string {
	if name == "" || size <= 0 {
		return ""
	}
	query := `SELECT path FROM photos WHERE name=? AND size=?`
	args := []any{name, size}
	if deviceID != "" {
		query += ` AND device_id=?`
		args = append(args, deviceID)
	}
	query += ` ORDER BY id DESC LIMIT 1`
	row := s.db.QueryRow(query, args...)
	var p string
	if err := row.Scan(&p); err != nil {
		return ""
	}
	st, err := os.Stat(p)
	if err != nil || st.Size() != size {
		return ""
	}
	return p
}

func (s *Store) AddPhoto(deviceID, batchID, name, path string, size int64) {
	_, _ = s.db.Exec(`
INSERT OR IGNORE INTO photos(device_id, batch_id, name, path, size, size_mb, date)
VALUES(?,?,?,?,?,?,?)
`, deviceID, batchID, name, path, size, float64(size)/1024/1024, time.Now().Format("2006-01-02 15:04:05"))
}

func (s *Store) Devices() ([]map[string]any, error) {
	rows, err := s.db.Query(`
SELECT d.device_id, COALESCE(d.device_name,''),
       COUNT(DISTINCT b.batch_id), COALESCE(SUM(b.photo_count),0)
FROM devices d
LEFT JOIN batches b ON b.device_id = d.device_id
GROUP BY d.device_id
ORDER BY d.last_heartbeat DESC
`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []map[string]any
	for rows.Next() {
		var id, name string
		var batches, photos int
		if err := rows.Scan(&id, &name, &batches, &photos); err != nil {
			continue
		}
		out = append(out, map[string]any{
			"device_id": id, "device_name": name, "batches": batches, "photos": photos,
		})
	}
	if out == nil {
		out = []map[string]any{}
	}
	return out, nil
}

func (s *Store) Batches(deviceID string) ([]Batch, error) {
	q := `SELECT device_id, batch_id, COALESCE(timestamp,''), photo_count, total_size, total_size_mb, COALESCE(status,''), duration_sec FROM batches`
	args := []any{}
	if deviceID != "" {
		q += ` WHERE device_id = ?`
		args = append(args, deviceID)
	}
	q += ` ORDER BY timestamp DESC, id DESC`
	rows, err := s.db.Query(q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Batch
	for rows.Next() {
		var b Batch
		if err := rows.Scan(&b.DeviceID, &b.BatchID, &b.Timestamp, &b.PhotoCount, &b.TotalSize, &b.TotalSizeMB, &b.Status, &b.DurationSec); err != nil {
			continue
		}
		out = append(out, b)
	}
	if out == nil {
		out = []Batch{}
	}
	return out, nil
}

func (s *Store) Photos(deviceID, batchID string) ([]map[string]any, error) {
	rows, err := s.db.Query(`SELECT name, path, size, size_mb, COALESCE(date,'') FROM photos WHERE device_id=? AND batch_id=? ORDER BY id`, deviceID, batchID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []map[string]any
	for rows.Next() {
		var name, path, date string
		var size int64
		var sizeMB float64
		if err := rows.Scan(&name, &path, &size, &sizeMB, &date); err != nil {
			continue
		}
		out = append(out, map[string]any{"name": name, "path": path, "size": size, "size_mb": sizeMB, "date": date})
	}
	if out == nil {
		out = []map[string]any{}
	}
	return out, nil
}

// DeleteBatch 删掉一个批次及其照片记录。传输被停掉、一张都没落盘时用，
// 免得图库里堆一排「0 张」的空卡片。
func (s *Store) DeleteBatch(deviceID, batchID string) {
	_, _ = s.db.Exec(`DELETE FROM photos WHERE device_id=? AND batch_id=?`, deviceID, batchID)
	_, _ = s.db.Exec(`DELETE FROM batches WHERE device_id=? AND batch_id=?`, deviceID, batchID)
}

// PruneEmptyBatches 清掉历史遗留的空批次。
func (s *Store) PruneEmptyBatches() int64 {
	res, err := s.db.Exec(`
DELETE FROM batches
WHERE photo_count <= 0
  AND NOT EXISTS (SELECT 1 FROM photos p WHERE p.device_id = batches.device_id AND p.batch_id = batches.batch_id)`)
	if err != nil {
		return 0
	}
	n, _ := res.RowsAffected()
	return n
}

func (s *Store) Clear() error {
	_, err := s.db.Exec(`DELETE FROM photos; DELETE FROM batches; DELETE FROM devices;`)
	return err
}

func (s *Store) Checkpoint() {
	_, _ = s.db.Exec(`PRAGMA wal_checkpoint(TRUNCATE)`)
}

// ---- 备份 ----
//
// 差异备份的全部依据就是「这个计划以前备过这个文件吗」。
// 判断用「文件名 + 大小」，和跨批次去重同一套标准：照片一旦拍下来内容就不再变，
// 改名和改大小都算另一个文件。算内容哈希更准，但要把每张照片整个读一遍，
// 一次几千张的备份会慢到没法用，而它拦下的那点误差在照片上几乎不存在。

type BackupPlan struct {
	ID         int64  `json:"id"`
	Name       string `json:"name"`
	SourceKind string `json:"source_kind"`
	SourceID   string `json:"source_id"`
	Dest       string `json:"dest"`
	CreatedAt  string `json:"created_at"`
	// Auto 手机一连上就自动备一次
	Auto     bool   `json:"auto"`
	LastAuto string `json:"last_auto"`
}

type BackupRun struct {
	ID         int64  `json:"id"`
	PlanID     int64  `json:"plan_id"`
	Snapshot   string `json:"snapshot"`
	StartedAt  string `json:"started_at"`
	FinishedAt string `json:"finished_at"`
	Status     string `json:"status"`
	Added      int    `json:"added"`
	Reused     int    `json:"reused"`
	Failed     int    `json:"failed"`
	BytesAdded int64  `json:"bytes_added"`
	BytesTotal int64  `json:"bytes_total"`
	Note       string `json:"note"`
}

func (s *Store) SaveBackupPlan(p BackupPlan) (int64, error) {
	res, err := s.db.Exec(`
INSERT INTO backup_plans(name, source_kind, source_id, dest) VALUES(?,?,?,?)
ON CONFLICT(source_kind, source_id, dest) DO UPDATE SET name=excluded.name
`, p.Name, p.SourceKind, p.SourceID, p.Dest)
	if err != nil {
		return 0, err
	}
	if id, err := res.LastInsertId(); err == nil && id > 0 {
		return id, nil
	}
	// ON CONFLICT 走了更新分支时 LastInsertId 不可靠，回头查一次
	var id int64
	err = s.db.QueryRow(`SELECT id FROM backup_plans WHERE source_kind=? AND source_id=? AND dest=?`,
		p.SourceKind, p.SourceID, p.Dest).Scan(&id)
	return id, err
}

func (s *Store) BackupPlans() ([]BackupPlan, error) {
	rows, err := s.db.Query(`SELECT id, name, source_kind, source_id, dest, COALESCE(created_at,''),
       COALESCE(auto,0), COALESCE(last_auto,'') FROM backup_plans ORDER BY id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []BackupPlan{}
	for rows.Next() {
		var p BackupPlan
		var auto int
		if err := rows.Scan(&p.ID, &p.Name, &p.SourceKind, &p.SourceID, &p.Dest, &p.CreatedAt,
			&auto, &p.LastAuto); err != nil {
			return nil, err
		}
		p.Auto = auto == 1
		out = append(out, p)
	}
	return out, rows.Err()
}

func (s *Store) BackupPlan(id int64) (BackupPlan, error) {
	var p BackupPlan
	var auto int
	err := s.db.QueryRow(`SELECT id, name, source_kind, source_id, dest, COALESCE(created_at,''),
       COALESCE(auto,0), COALESCE(last_auto,'') FROM backup_plans WHERE id=?`, id).
		Scan(&p.ID, &p.Name, &p.SourceKind, &p.SourceID, &p.Dest, &p.CreatedAt, &auto, &p.LastAuto)
	p.Auto = auto == 1
	return p, err
}

func (s *Store) DeleteBackupPlan(id int64) {
	_, _ = s.db.Exec(`DELETE FROM backup_files WHERE plan_id=?`, id)
	_, _ = s.db.Exec(`DELETE FROM backup_runs WHERE plan_id=?`, id)
	_, _ = s.db.Exec(`DELETE FROM backup_plans WHERE id=?`, id)
}

func (s *Store) StartBackupRun(planID int64, snapshot string) (int64, error) {
	res, err := s.db.Exec(`
INSERT INTO backup_runs(plan_id, snapshot, started_at, status) VALUES(?,?,?, 'running')
`, planID, snapshot, time.Now().Format(time.RFC3339))
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

func (s *Store) FinishBackupRun(runID int64, r BackupRun) {
	_, _ = s.db.Exec(`
UPDATE backup_runs SET finished_at=?, status=?, added=?, reused=?, failed=?,
  bytes_added=?, bytes_total=?, note=? WHERE id=?
`, time.Now().Format(time.RFC3339), r.Status, r.Added, r.Reused, r.Failed,
		r.BytesAdded, r.BytesTotal, r.Note, runID)
}

func (s *Store) BackupRuns(planID int64) ([]BackupRun, error) {
	rows, err := s.db.Query(`
SELECT id, plan_id, snapshot, COALESCE(started_at,''), COALESCE(finished_at,''),
       COALESCE(status,''), added, reused, failed, bytes_added, bytes_total, COALESCE(note,'')
FROM backup_runs WHERE plan_id=? ORDER BY id DESC
`, planID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []BackupRun{}
	for rows.Next() {
		var r BackupRun
		if err := rows.Scan(&r.ID, &r.PlanID, &r.Snapshot, &r.StartedAt, &r.FinishedAt,
			&r.Status, &r.Added, &r.Reused, &r.Failed, &r.BytesAdded, &r.BytesTotal, &r.Note); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

// BackedUpPath 这个计划以前备过这个文件吗？备过就返回一份还在磁盘上的旧副本，
// 新快照直接硬链接过去——这是「差异备份」省下空间的地方。
//
// 必须 os.Stat 确认文件还在：用户在访达里删掉某个快照之后，库里的记录还在，
// 照着一个不存在的路径去 link 会失败，然后整张照片被当成失败项。
func (s *Store) BackedUpPath(planID int64, name string, size int64) string {
	if name == "" || size <= 0 {
		return ""
	}
	rows, err := s.db.Query(`
SELECT path FROM backup_files WHERE plan_id=? AND name=? AND size=? ORDER BY id DESC LIMIT 8
`, planID, name, size)
	if err != nil {
		return ""
	}
	defer rows.Close()
	for rows.Next() {
		var p string
		if rows.Scan(&p) != nil {
			continue
		}
		if st, err := os.Stat(p); err == nil && st.Size() == size {
			return p
		}
	}
	return ""
}

func (s *Store) AddBackupFile(planID, runID int64, name, rel, path string, size int64, linked bool) {
	n := 0
	if linked {
		n = 1
	}
	_, _ = s.db.Exec(`
INSERT INTO backup_files(plan_id, run_id, name, rel, path, size, linked) VALUES(?,?,?,?,?,?,?)
`, planID, runID, name, rel, path, size, n)
}

func (s *Store) BackupRunFiles(runID int64) ([]map[string]any, error) {
	rows, err := s.db.Query(`SELECT name, rel, path, size, linked FROM backup_files WHERE run_id=? ORDER BY id`, runID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var name, rel, path string
		var size int64
		var linked int
		if err := rows.Scan(&name, &rel, &path, &size, &linked); err != nil {
			return nil, err
		}
		out = append(out, map[string]any{
			"name": name, "rel": rel, "path": path, "size": size, "linked": linked == 1,
		})
	}
	return out, rows.Err()
}

// DeleteBackupRun 只删这一个快照的记录。
//
// 磁盘上的文件交给调用方删，而且删掉也不会伤到别的快照：同一份内容在多个快照里
// 是硬链接，删掉一个链接，其他快照里的还在——这正是 Time Machine 的做法，
// 也是「每个快照看起来都是完整一份」和「只占增量空间」能同时成立的原因。
func (s *Store) DeleteBackupRun(runID int64) {
	_, _ = s.db.Exec(`DELETE FROM backup_files WHERE run_id=?`, runID)
	_, _ = s.db.Exec(`DELETE FROM backup_runs WHERE id=?`, runID)
}

// SetBackupAuto 打开/关掉「连上就自动备份」。
func (s *Store) SetBackupAuto(id int64, on bool) {
	n := 0
	if on {
		n = 1
	}
	_, _ = s.db.Exec(`UPDATE backup_plans SET auto=? WHERE id=?`, n, id)
}

// MarkBackupAuto 记下这次自动备份的时间，用来做冷却。
func (s *Store) MarkBackupAuto(id int64) {
	_, _ = s.db.Exec(`UPDATE backup_plans SET last_auto=? WHERE id=?`, time.Now().Format(time.RFC3339), id)
}
