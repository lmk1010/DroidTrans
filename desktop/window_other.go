//go:build !darwin

package main

func runNativeWindow(url string) {
	openUI(url)
	ch := make(chan struct{})
	<-ch
}

func requestAttention() {}
