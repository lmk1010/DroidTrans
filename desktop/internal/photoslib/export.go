package photoslib

// 把扫出来的原片摊到普通文件夹里。
//
// 目标结构刻意做得无聊：
//
//	输出目录/2026/2026-09/IMG_1234.HEIC
//	                     IMG_1234.MOV      ← 实况照片的动态部分，和静态图同名
//
// 无聊是重点。用户要的就是「能在访达里直接看见、能拖走、能备份到移动硬盘」，
// 而不是又一个只有某个 App 打得开的库。

import (
	"context"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
)

// Options 导出选项。
type Options struct {
	// LowercaseExt 把扩展名统一成小写。
	//
	// 相册里 .JPG 和 .jpg 是混着的（不同机型、不同年代的相机写法不一样），
	// 拿去跨平台同步或者喂给查重工具，同一张照片会被当成两个文件。
	// 统一一下几乎不要钱，但同类工具的 issue 里一直有人在提。
	LowercaseExt bool

	// MaxItems 这一趟最多真正拷贝多少个，0 表示不限。
	//
	// 数的是「真的抄过去了」的个数，已经存在被跳过的不算 ——
	// 否则免费用户第二次点导出，前 200 个全被跳过、一个新的都不出来，
	// 看起来就像坏了。这样才能一批一批往下推。
	MaxItems int
}

// Progress 导出进度。
type Progress struct {
	Done       int    `json:"done"`
	Total      int    `json:"total"`
	Bytes      int64  `json:"bytes"`
	TotalBytes int64  `json:"total_bytes"`
	Current    string `json:"current"`
	Failed     int    `json:"failed"`
	Skipped    int    `json:"skipped"`
	Finished   bool   `json:"finished"`
	Error      string `json:"error,omitempty"`
	// Copied 真正抄过去的个数（不含已存在被跳过的）
	Copied int `json:"copied"`
	// Limit 这一趟的免费额度，0 表示不限
	Limit int `json:"limit"`
	// LimitHit 因为额度用完而提前停下
	LimitHit bool `json:"limit_hit"`
}

// Exporter 一次导出任务。
type Exporter struct {
	mu       sync.Mutex
	progress Progress
	cancel   context.CancelFunc
}

func (e *Exporter) Progress() Progress {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.progress
}

func (e *Exporter) Cancel() {
	e.mu.Lock()
	c := e.cancel
	e.mu.Unlock()
	if c != nil {
		c()
	}
}

func (e *Exporter) set(fn func(*Progress)) {
	e.mu.Lock()
	fn(&e.progress)
	e.mu.Unlock()
}

// Run 把 assets 导到 outDir。会阻塞到导完或被取消。
func (e *Exporter) Run(ctx context.Context, assets []Asset, outDir string, opt Options) {
	ctx, cancel := context.WithCancel(ctx)
	e.mu.Lock()
	e.cancel = cancel
	e.progress = Progress{Total: len(assets)}
	e.progress.Limit = opt.MaxItems
	for _, a := range assets {
		e.progress.TotalBytes += a.Size
	}
	e.mu.Unlock()
	defer cancel()

	// 同一批里重名的要区分开。相册里 IMG_0001.JPG 这种名字能有好几张，
	// 直接覆盖会静默吃掉照片 —— 这是最不可接受的一类 bug。
	used := map[string]bool{}
	copied := 0

	for _, a := range assets {
		select {
		case <-ctx.Done():
			e.set(func(p *Progress) { p.Finished = true; p.Error = "已取消" })
			return
		default:
		}

		e.set(func(p *Progress) { p.Current = a.OriginalName })

		dir := filepath.Join(outDir,
			strconv.Itoa(a.Created.Year()),
			a.Created.Format("2006-01"))
		if err := os.MkdirAll(dir, 0o755); err != nil {
			e.set(func(p *Progress) { p.Failed++; p.Done++ })
			continue
		}

		dest := uniquePath(dir, normalizeExt(a.OriginalName, opt.LowercaseExt), used)
		n, err := copyIfNeeded(a.Path, dest)
		if err != nil {
			e.set(func(p *Progress) { p.Failed++; p.Done++ })
			continue
		}

		// 实况照片的动态部分跟着静态图走，同名不同扩展名 ——
		// 名字对不上的话，用户在电脑上根本看不出这两个文件是一对。
		if a.LivePath != "" {
			stem := strings.TrimSuffix(dest, filepath.Ext(dest))
			liveExt := filepath.Ext(a.LivePath)
			if opt.LowercaseExt {
				liveExt = strings.ToLower(liveExt)
			} else {
				liveExt = strings.ToUpper(liveExt)
			}
			liveDest := stem + liveExt
			if m, err := copyIfNeeded(a.LivePath, liveDest); err == nil {
				n += m
			}
		}

		if n > 0 {
			copied++
		}
		e.set(func(p *Progress) {
			p.Done++
			p.Bytes += n
			p.Copied = copied
		})

		// 到了免费额度就停，但不算失败 —— 这一批是真的导出去了。
		if opt.MaxItems > 0 && copied >= opt.MaxItems {
			e.set(func(p *Progress) { p.LimitHit = true })
			break
		}
	}

	e.set(func(p *Progress) { p.Finished = true; p.Current = "" })
}

// uniquePath 在目录里取一个不冲突的文件名。
func uniquePath(dir, name string, used map[string]bool) string {
	ext := filepath.Ext(name)
	stem := strings.TrimSuffix(name, ext)
	for i := 0; ; i++ {
		candidate := name
		if i > 0 {
			candidate = fmt.Sprintf("%s (%d)%s", stem, i, ext)
		}
		full := filepath.Join(dir, candidate)
		if used[full] {
			continue
		}
		if _, err := os.Stat(full); err == nil {
			// 已经存在。可能是上一次导出留下的同一张 —— 交给 copyIfNeeded
			// 按大小判断，是同一张就跳过，不是就换个名字。
			used[full] = true
			return full
		}
		used[full] = true
		return full
	}
}

// copyIfNeeded 目标已经有同样大小的文件就跳过。
//
// 导出是个会被反复执行的动作（又拍了几百张，再导一次）。
// 每次都把几十 GB 重抄一遍，用户第二次就不会再用了。
func copyIfNeeded(src, dst string) (int64, error) {
	si, err := os.Stat(src)
	if err != nil {
		return 0, err
	}
	if di, err := os.Stat(dst); err == nil && di.Size() == si.Size() {
		return 0, nil
	}

	in, err := os.Open(src)
	if err != nil {
		return 0, err
	}
	defer in.Close()

	// 先写临时文件再改名：中途失败或断电时，目标位置上不会留下半张照片。
	// 半张照片比没有更糟 —— 用户会以为它是好的，然后把手机上的原件删了。
	tmp := dst + ".dtpart"
	out, err := os.Create(tmp)
	if err != nil {
		return 0, err
	}
	n, err := io.Copy(out, in)
	closeErr := out.Close()
	if err == nil {
		err = closeErr
	}
	if err != nil {
		_ = os.Remove(tmp)
		return 0, err
	}
	if err := os.Rename(tmp, dst); err != nil {
		_ = os.Remove(tmp)
		return 0, err
	}
	// 把拍摄时间写回文件，访达里按日期排序才对得上
	_ = os.Chtimes(dst, si.ModTime(), si.ModTime())
	return n, nil
}

// normalizeExt 按需要把扩展名统一成小写，主名不动。
//
// 只动扩展名：主名是用户自己起的（或相机给的），改了就对不上号了。
func normalizeExt(name string, lower bool) string {
	if !lower {
		return name
	}
	ext := filepath.Ext(name)
	if ext == "" {
		return name
	}
	return strings.TrimSuffix(name, ext) + strings.ToLower(ext)
}
