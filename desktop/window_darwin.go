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
