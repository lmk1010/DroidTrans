//go:build darwin

package main

import (
	"runtime"
	"strings"
	"unsafe"
)

/*
#cgo CFLAGS: -fobjc-arc
#cgo LDFLAGS: -framework Cocoa -framework WebKit -framework UserNotifications
#include <stdlib.h>
#include "window_darwin.h"
*/
import "C"

// onFilesPicked 由原生层在拖拽/选择文件后调用
var onFilesPicked func([]string)

func setFilesPickedHandler(fn func([]string)) { onFilesPicked = fn }

// 菜单栏每次弹出都会问一遍这两行字。
// 状态是会变的（手机连上/断开、待取件数增减），
// 建一次菜单就不管的话，用户看到的是打开 App 那一刻的快照。
var (
	onStatusLine  func() string
	onAddressLine func() string
	onOpenOutput  func()
)

func setMenuHandlers(status, address func() string, openOutput func()) {
	onStatusLine, onAddressLine, onOpenOutput = status, address, openOutput
}

//export dtStatusLine
func dtStatusLine() *C.char {
	if onStatusLine == nil {
		return C.CString("")
	}
	return C.CString(onStatusLine())
}

//export dtAddressLine
func dtAddressLine() *C.char {
	if onAddressLine == nil {
		return C.CString("")
	}
	return C.CString(onAddressLine())
}

//export dtPickFilesFromMenu
func dtPickFilesFromMenu() { C.DTPickFiles() }

//export dtOpenOutputFolder
func dtOpenOutputFolder() {
	if onOpenOutput != nil {
		onOpenOutput()
	}
}

//export dtFilesPicked
func dtFilesPicked(paths *C.char) {
	if onFilesPicked == nil || paths == nil {
		return
	}
	raw := C.GoString(paths)
	var out []string
	for _, p := range strings.Split(raw, "\n") {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	if len(out) > 0 {
		onFilesPicked(out)
	}
}

func pickFiles() {
	C.DTPickFiles()
}

func runNativeWindow(url string) {
	runtime.LockOSThread()
	cs := C.CString(url)
	defer C.free(unsafe.Pointer(cs))
	C.DTRunWindow(cs)
}

func requestAttention() {
	C.DTRequestAttention()
}

func notifyUser(title, body string) {
	ct := C.CString(title)
	cb := C.CString(body)
	defer C.free(unsafe.Pointer(ct))
	defer C.free(unsafe.Pointer(cb))
	C.DTNotify(ct, cb)
}
