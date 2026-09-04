// Package photoslib 把 macOS「照片」图库里的原片救出来。
//
// 为什么要做这个：照片 App 的图库是个黑盒。Catalina 之后用户在 Finder 里
// 按原始文件名根本找不到自己的照片；而官方的导出器是出了名的不靠谱 ——
// 导几千张会崩，每两千张原图里大约有一百张导不出来，报的还是看不懂的错误，
// 拖到桌面不工作、「在访达中显示」点了没反应。
//
// 我们绕开那个导出器：直接读图库自己的 Photos.sqlite，按记录去 originals/
// 目录里取原始文件，用用户认识的文件名（IMG_1234.HEIC 而不是一串 UUID）
// 按日期摊到普通文件夹里。全程只读，一个字节都不往图库里写。
//
// ⚠️ 最要紧的一件事：开了 iCloud「优化 iPhone 储存空间」之后，
// 本机上很可能**根本没有原片**，只有缩略图。实测一个 70 张的图库里
// 69 张在本地都没有原文件。这种情况必须明明白白报给用户
// （「这 69 张要先在照片 App 里下载原片」），绝不能静默导出 1 张就说完事了 ——
// 那正是我们要取代的那种烂体验。
package photoslib

import (
	"database/sql"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"

	_ "modernc.org/sqlite"
)

// coreDataEpoch Core Data 的时间原点是 2001-01-01，不是 Unix 的 1970。
// 差的这 978307200 秒忘了加，导出的照片会全部落到 1970 年代的文件夹里。
const coreDataEpoch = 978307200

// Asset 图库里的一条记录。
type Asset struct {
	UUID string
	// OriginalName 用户认识的名字，比如 IMG_1234.HEIC。
	// 图库内部存的是一串 UUID 文件名，直接导出来用户完全对不上号。
	OriginalName string
	// Path 原始文件在图库里的绝对路径。空表示本机没有这个原片。
	Path    string
	Size    int64
	Created time.Time
	IsVideo bool
	IsLive  bool
	// LivePath 实况照片配对的那段视频，可能为空。
	LivePath string
}

// Scan 扫描的结果。
type Scan struct {
	// Library 图库路径
	Library string
	// Assets 本机有原片、可以导出的
	Assets []Asset
	// InCloudOnly 只在 iCloud、本机没有原片的张数
	InCloudOnly int
	// Trashed 在「最近删除」里的，不导
	Trashed int
	// TotalBytes 可导出的总字节数
	TotalBytes int64
}

// DefaultLibrary 系统默认图库的位置。
func DefaultLibrary() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	p := filepath.Join(home, "Pictures", "Photos Library.photoslibrary")
	if _, err := os.Stat(p); err != nil {
		return "", fmt.Errorf("没找到照片图库：%s", p)
	}
	return p, nil
}

// openDB 把图库数据库复制一份再打开。
//
// 不直接开原库有两个原因：照片 App 开着的时候库是锁的；而且它用 WAL 模式，
// 只复制主库文件会读到几小时前的旧数据 —— 用户刚导入的照片会凭空消失。
// 所以 -wal 和 -shm 必须一起复制过来，让 SQLite 自己把 WAL 回放上去。
//
// 复制出来的副本以读写方式打开（回放 WAL 需要写权限），但那是副本，
// 原图库全程只读。
func openDB(lib string) (*sql.DB, func(), error) {
	tmp, err := os.MkdirTemp("", "droidtrans-photoslib-")
	if err != nil {
		return nil, nil, err
	}
	cleanup := func() { _ = os.RemoveAll(tmp) }

	base := filepath.Join(lib, "database", "Photos.sqlite")
	for _, suffix := range []string{"", "-wal", "-shm"} {
		src := base + suffix
		if _, err := os.Stat(src); err != nil {
			if suffix == "" {
				cleanup()
				return nil, nil, fmt.Errorf("图库里没有 Photos.sqlite：%w", err)
			}
			continue // wal/shm 不一定存在
		}
		if err := copyFile(src, filepath.Join(tmp, "Photos.sqlite"+suffix)); err != nil {
			cleanup()
			// 读不到基本就是没有权限。macOS 会把照片图库挡在 TCC 后面，
			// 说清楚怎么解决，比抛一句 operation not permitted 有用得多。
			if errors.Is(err, os.ErrPermission) {
				return nil, nil, fmt.Errorf(
					"没有权限读取照片图库。到「系统设置 → 隐私与安全性 → 完全磁盘访问权限」里把卓传打开，然后重启卓传")
			}
			return nil, nil, err
		}
	}

	db, err := sql.Open("sqlite", filepath.Join(tmp, "Photos.sqlite"))
	if err != nil {
		cleanup()
		return nil, nil, err
	}
	return db, func() { _ = db.Close(); cleanup() }, nil
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.Create(dst)
	if err != nil {
		return err
	}
	defer out.Close()
	_, err = io.Copy(out, in)
	return err
}

// ScanLibrary 读出图库里所有还在的照片和视频。
func ScanLibrary(lib string) (*Scan, error) {
	db, closeDB, err := openDB(lib)
	if err != nil {
		return nil, err
	}
	defer closeDB()

	// ZKIND: 0=照片 1=视频；ZKINDSUBTYPE=2 是实况照片。
	// ZTRASHEDSTATE 非 0 的在「最近删除」里，用户已经表示不要了，不导。
	rows, err := db.Query(`
		SELECT a.ZUUID, a.ZDIRECTORY, a.ZFILENAME,
		       COALESCE(aa.ZORIGINALFILENAME, ''), COALESCE(aa.ZORIGINALFILESIZE, 0),
		       COALESCE(a.ZDATECREATED, 0), COALESCE(a.ZKIND, 0), COALESCE(a.ZKINDSUBTYPE, 0),
		       COALESCE(a.ZTRASHEDSTATE, 0)
		FROM ZASSET a
		LEFT JOIN ZADDITIONALASSETATTRIBUTES aa ON aa.ZASSET = a.Z_PK
	`)
	if err != nil {
		return nil, fmt.Errorf("图库结构和预期不符（macOS 版本可能太新或太旧）：%w", err)
	}
	defer rows.Close()

	out := &Scan{Library: lib}
	for rows.Next() {
		var uuid, dir, fname, origName string
		var origSize, kind, subtype, trashed int64
		var created float64
		if err := rows.Scan(&uuid, &dir, &fname, &origName, &origSize,
			&created, &kind, &subtype, &trashed); err != nil {
			return nil, err
		}
		if trashed != 0 {
			out.Trashed++
			continue
		}
		if dir == "" || fname == "" {
			out.InCloudOnly++
			continue
		}
		p := filepath.Join(lib, "originals", dir, fname)
		st, err := os.Stat(p)
		if err != nil {
			// 记录在，文件不在 —— 开了 iCloud「优化储存空间」就是这样
			out.InCloudOnly++
			continue
		}

		a := Asset{
			UUID:         uuid,
			OriginalName: pickName(origName, fname),
			Path:         p,
			Size:         st.Size(),
			Created:      time.Unix(int64(created)+coreDataEpoch, 0),
			IsVideo:      kind == 1,
			IsLive:       subtype == 2,
		}
		if a.IsLive {
			a.LivePath = findPairedVideo(p)
		}
		out.Assets = append(out.Assets, a)
		out.TotalBytes += a.Size
		if a.LivePath != "" {
			if st, err := os.Stat(a.LivePath); err == nil {
				out.TotalBytes += st.Size()
			}
		}
	}
	return out, rows.Err()
}

// pickName 优先用用户认识的原始文件名。
//
// 图库内部的文件名是一串 UUID，导出来用户完全对不上号 ——
// 「按原始文件名找不到自己的照片」正是大家骂这个图库的头一条。
func pickName(original, internal string) string {
	if original != "" {
		return original
	}
	return internal
}

var videoExts = []string{".mov", ".MOV", ".mp4", ".MP4"}

// findPairedVideo 实况照片的那段动态。
//
// 它和静态图同名、只换扩展名，就放在 originals 的同一个目录里。
// 找不到不算错 —— 有些实况照片的动态部分只在 iCloud 上。
func findPairedVideo(stillPath string) string {
	stem := strings.TrimSuffix(stillPath, filepath.Ext(stillPath))
	for _, ext := range videoExts {
		if _, err := os.Stat(stem + ext); err == nil {
			return stem + ext
		}
	}
	return ""
}
