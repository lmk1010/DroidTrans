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
`)
	return err
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

func (s *Store) Clear() error {
	_, err := s.db.Exec(`DELETE FROM photos; DELETE FROM batches; DELETE FROM devices;`)
	return err
}
