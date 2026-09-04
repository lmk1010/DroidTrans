//go:build !windows

package fast

import "syscall"

// freeSpace 目标目录所在卷的可用字节数。
//
// 用 Bavail 而不是 Bfree：后者包含只有 root 能用的保留块，
// 普通进程写到那里照样 ENOSPC。
func freeSpace(dir string) (int64, error) {
	var st syscall.Statfs_t
	if err := syscall.Statfs(dir, &st); err != nil {
		return 0, err
	}
	return int64(st.Bavail) * int64(st.Bsize), nil
}
