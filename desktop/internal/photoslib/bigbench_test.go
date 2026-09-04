package photoslib

import (
	"database/sql"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// 造一个五万张的库，量扫描要多久。
// 扫描是同步的 HTTP 请求，慢到几十秒的话前端看起来就是卡死了。
func TestScanBigLibrary(t *testing.T) {
	if testing.Short() {
		t.Skip("大库基准，-short 时跳过")
	}
	const n = 50000
	lib := filepath.Join(t.TempDir(), "Big.photoslibrary")
	os.MkdirAll(filepath.Join(lib, "database"), 0o755)
	db, _ := sql.Open("sqlite", filepath.Join(lib, "database", "Photos.sqlite"))
	db.Exec(`CREATE TABLE ZASSET (Z_PK INTEGER PRIMARY KEY, ZUUID TEXT, ZDIRECTORY TEXT,
		ZFILENAME TEXT, ZDATECREATED REAL, ZKIND INTEGER, ZKINDSUBTYPE INTEGER, ZTRASHEDSTATE INTEGER)`)
	db.Exec(`CREATE TABLE ZADDITIONALASSETATTRIBUTES (Z_PK INTEGER PRIMARY KEY, ZASSET INTEGER,
		ZORIGINALFILENAME TEXT, ZORIGINALFILESIZE INTEGER)`)
	when := float64(time.Now().Unix() - coreDataEpoch)

	tx, _ := db.Begin()
	sa, _ := tx.Prepare(`INSERT INTO ZASSET VALUES (?,?,?,?,?,?,?,?)`)
	sb, _ := tx.Prepare(`INSERT INTO ZADDITIONALASSETATTRIBUTES VALUES (?,?,?,?)`)
	for i := 0; i < n; i++ {
		dir := fmt.Sprintf("%X", i%16)
		fn := fmt.Sprintf("%08d.HEIC", i)
		sa.Exec(i+1, "u"+fn, dir, fn, when, 0, 0, 0)
		sb.Exec(i+1, i+1, fmt.Sprintf("IMG_%05d.HEIC", i), 100)
		// 一半在本地，一半只在 iCloud —— 贴近真实
		if i%2 == 0 {
			d := filepath.Join(lib, "originals", dir)
			os.MkdirAll(d, 0o755)
			os.WriteFile(filepath.Join(d, fn), []byte("x"), 0o644)
		}
	}
	tx.Commit()
	db.Close()

	start := time.Now()
	scan, err := ScanLibrary(lib)
	if err != nil {
		t.Fatal(err)
	}
	elapsed := time.Since(start)
	t.Logf("五万条记录（本地 %d / 云端 %d）扫描耗时 %v",
		len(scan.Assets), scan.InCloudOnly, elapsed)
	if elapsed > 20*time.Second {
		t.Errorf("扫描 %v 太慢了，界面会像卡死", elapsed)
	}
}
