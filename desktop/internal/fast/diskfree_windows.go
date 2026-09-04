//go:build windows

package fast

import "golang.org/x/sys/windows"

// freeSpace 目标目录所在卷的可用字节数。
//
// GetDiskFreeSpaceEx 的第一个出参才是「当前用户可用」，
// 和整卷剩余不是一回事（配额、卷影副本都会让两者不同）。
func freeSpace(dir string) (int64, error) {
	p, err := windows.UTF16PtrFromString(dir)
	if err != nil {
		return 0, err
	}
	var availToCaller, total, totalFree uint64
	if err := windows.GetDiskFreeSpaceEx(p, &availToCaller, &total, &totalFree); err != nil {
		return 0, err
	}
	return int64(availToCaller), nil
}
