package fast

// 断点续传的落盘纪律。
//
// 以前接收端是 os.Create(dest) —— 每次从 0 截断重写。传一个 4GB 的视频到
// 3.9GB 断网，下次从头再来；100GB 的任务传到 99GB 断了，全废。
// 而「导入几万张照片导到一半卡住」恰恰是用户抛弃系统自带工具的头号原因，
// 我们要是也这样，就没资格说比它强。
//
// 现在的纪律有三条：
//
//  1. 没传完的字节永远写在分片文件里，**绝不出现在目标路径上**。
//     半个文件躺在输出目录里比传输失败更糟 —— 用户不知道它是坏的。
//  2. 分片文件名里带上总大小。同名但不同大小的文件不会互相续错，
//     这比「按文件名续传」安全得多，相册里同名文件遍地都是。
//  3. 只有写满 size 字节才 rename 转正。rename 在同一分区上是原子的，
//     所以目标路径上要么没有，要么是完整的。

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// partSuffix 分片文件的后缀。前面还会加一个点变成隐藏文件 ——
// 用户在访达/资源管理器里不该看见传输中间态。
const partSuffix = ".dtpart"

// PartPath 目标文件对应的分片路径。
//
// 大小写进文件名，是为了让「同名但不是同一个文件」自然地拿到不同的分片，
// 而不用额外存一份元数据。
func PartPath(dest string, size int64) string {
	dir := filepath.Dir(dest)
	base := filepath.Base(dest)
	return filepath.Join(dir, "."+base+"."+strconv.FormatInt(size, 10)+partSuffix)
}

// ResumeOffset 目标位置已经有多少字节可以直接用。
//
// done 为 true 表示目标文件已经完整存在，一个字节都不用再传。
// 否则 offset 是应该从第几个字节接着传（0 就是从头传）。
func ResumeOffset(dest string, size int64) (offset int64, done bool) {
	if size <= 0 {
		// 大小未知就没法安全续传：分不清「传完了」和「传了一半」
		return 0, false
	}
	if st, err := os.Stat(dest); err == nil && !st.IsDir() {
		if st.Size() == size {
			return size, true
		}
		// 目标路径上有个大小不对的东西。它不是我们写的（我们只在写满后
		// 才 rename），可能是同名的别的文件 —— 不动它，重新传一份，
		// 让后面的落盘逻辑去决定是覆盖还是改名。
		return 0, false
	}
	if st, err := os.Stat(PartPath(dest, size)); err == nil && !st.IsDir() {
		if st.Size() > size {
			// 分片比目标还大，只可能是坏的，丢掉重来
			_ = os.Remove(PartPath(dest, size))
			return 0, false
		}
		return st.Size(), false
	}
	return 0, false
}

// OpenPart 打开分片文件，定位到 offset 准备接着写。
func OpenPart(dest string, size, offset int64) (*os.File, error) {
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return nil, err
	}
	f, err := os.OpenFile(PartPath(dest, size), os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return nil, err
	}
	// 截到 offset 再写：offset 是我们回给客户端的数字，
	// 客户端就是从那里开始发的。分片文件比 offset 长的话
	// （比如上次写了一半没来得及 flush 统计），多出来的必须丢掉，
	// 否则会在文件中间留下一段重复的字节，内容静默损坏。
	if err := f.Truncate(offset); err != nil {
		_ = f.Close()
		return nil, err
	}
	if _, err := f.Seek(offset, 0); err != nil {
		_ = f.Close()
		return nil, err
	}
	return f, nil
}

// CommitPart 分片写满之后转正，并把同名的其他分片清掉。
func CommitPart(dest string, size int64) error {
	part := PartPath(dest, size)
	st, err := os.Stat(part)
	if err != nil {
		return err
	}
	if st.Size() != size {
		return fmt.Errorf("分片只有 %d 字节，还差 %d", st.Size(), size-st.Size())
	}
	if err := os.Rename(part, dest); err != nil {
		return err
	}
	cleanStaleParts(dest, size)
	return nil
}

// cleanStaleParts 同一个目标名下、别的大小留下的分片。
//
// 典型来源：用户换了一张同名的照片重传。旧分片再也不会被用到，
// 留着只会长期占盘，而且它是隐藏文件，用户自己发现不了。
func cleanStaleParts(dest string, keep int64) {
	dir := filepath.Dir(dest)
	prefix := "." + filepath.Base(dest) + "."
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	keepName := filepath.Base(PartPath(dest, keep))
	for _, e := range entries {
		n := e.Name()
		if n == keepName || !strings.HasPrefix(n, prefix) || !strings.HasSuffix(n, partSuffix) {
			continue
		}
		_ = os.Remove(filepath.Join(dir, n))
	}
}

// EnsureSpace 目标盘还能不能再放下 need 字节。
//
// 传到一半盘满，报出来的是 write: no space left on device —— 用户看不懂，
// 而且此时已经白传了几十 GB。宁可一开始就拒绝。
//
// 留 64MB 余量：盘塞到一个字节不剩的时候，系统本身也会出问题。
func EnsureSpace(dir string, need int64) error {
	if need <= 0 {
		return nil
	}
	free, err := freeSpace(dir)
	if err != nil {
		// 拿不到就别拦。查询失败不该变成传不了文件。
		return nil
	}
	const margin = 64 << 20
	if free < need+margin {
		return fmt.Errorf("磁盘空间不够：还需要 %s，可用 %s",
			humanBytes(need+margin), humanBytes(free))
	}
	return nil
}

func humanBytes(n int64) string {
	const unit = 1024
	if n < unit {
		return fmt.Sprintf("%d B", n)
	}
	div, exp := int64(unit), 0
	for v := n / unit; v >= unit && exp < 3; v /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %cB", float64(n)/float64(div), "KMGT"[exp])
}
