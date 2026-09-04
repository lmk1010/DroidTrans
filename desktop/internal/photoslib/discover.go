package photoslib

// 找出这台机器上所有的照片图库。
//
// 为什么不能只认默认位置（~/Pictures/Photos Library.photoslibrary）：
// **照片一多，图库就会被搬到外置硬盘上。** 而照片多的人恰恰是最可能
// 为这个功能付钱的人 —— 只认默认路径的话，功能对他们直接是不可用的，
// 界面还会理直气壮地说「没找到照片图库」。
//
// 所以主动去几个地方翻：家目录的图片文件夹、家目录本身、以及所有挂载的卷。
// 让用户从列表里挑，而不是让他去粘一个路径 —— 大多数人不知道
// .photoslibrary 是个「文件夹伪装成的文件」，在访达里根本点不进去。

import (
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// Library 一个被找到的图库。
type Library struct {
	Path string `json:"path"`
	Name string `json:"name"`
	// Default 是不是系统默认的那个
	Default bool `json:"default"`
	// Bytes 图库占多大。用户有好几个库时，这个数字比名字更能帮他认出
	// 哪个是「装着十年照片的那个」。
	Bytes int64 `json:"bytes"`
	// External 在外置卷上
	External bool `json:"external"`
}

const libSuffix = ".photoslibrary"

// Discover 找出能看到的所有图库。
//
// 只翻固定的几层，不做全盘遍历 —— 全盘扫一遍要几分钟，
// 而且会把外置硬盘吵醒，代价远大于收益。
func Discover() []Library {
	home, _ := os.UserHomeDir()
	def, _ := DefaultLibrary()

	seen := map[string]bool{}
	var out []Library

	add := func(path string, external bool) {
		abs, err := filepath.Abs(path)
		if err != nil || seen[abs] {
			return
		}
		if !isLibrary(abs) {
			return
		}
		seen[abs] = true
		out = append(out, Library{
			Path:     abs,
			Name:     strings.TrimSuffix(filepath.Base(abs), libSuffix),
			Default:  def != "" && abs == def,
			Bytes:    originalsSize(abs),
			External: external,
		})
	}

	// 家目录下的常见位置
	if home != "" {
		for _, dir := range []string{
			filepath.Join(home, "Pictures"),
			home,
			filepath.Join(home, "Documents"),
			filepath.Join(home, "Desktop"),
		} {
			scanDir(dir, func(p string) { add(p, false) })
		}
	}

	// 挂载的卷。外置硬盘上的图库是常态而不是例外 ——
	// 几百 GB 的照片本来就放不进笔记本。
	scanDir("/Volumes", func(vol string) {
		// /Volumes 下面是卷，不是图库；图库在卷里面再翻一层
		if isLibrary(vol) {
			add(vol, true)
			return
		}
		scanDir(vol, func(p string) { add(p, true) })
		scanDir(filepath.Join(vol, "Pictures"), func(p string) { add(p, true) })
	})

	// 默认库排最前，其余按体积从大到小 —— 大的通常就是用户要找的那个
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].Default != out[j].Default {
			return out[i].Default
		}
		return out[i].Bytes > out[j].Bytes
	})
	return out
}

// scanDir 把 dir 下面第一层的条目喂给 fn。目录不存在或读不了就当没有。
func scanDir(dir string, fn func(string)) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	for _, e := range entries {
		fn(filepath.Join(dir, e.Name()))
	}
}

// isLibrary 看着像不像一个照片图库。
//
// 光看后缀不够：用户可能有个同名的空壳，或者是从别的机器拷来的半个目录。
// 里面必须真的有 Photos.sqlite，否则扫描时才失败，用户会以为是我们的 bug。
func isLibrary(p string) bool {
	if !strings.HasSuffix(p, libSuffix) {
		return false
	}
	st, err := os.Stat(p)
	if err != nil || !st.IsDir() {
		return false
	}
	db, err := os.Stat(filepath.Join(p, "database", "Photos.sqlite"))
	return err == nil && !db.IsDir()
}

// originalsSize originals 目录占多大。
//
// 只统计 originals：图库里还有一大堆缩略图和渲染缓存，把它们算进去
// 会让用户对「能导出多少」产生错误预期。
func originalsSize(lib string) int64 {
	var total int64
	root := filepath.Join(lib, "originals")
	_ = filepath.WalkDir(root, func(p string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		if info, err := d.Info(); err == nil {
			total += info.Size()
		}
		return nil
	})
	return total
}
