package main

import (
	"fmt"
	"os"
	"time"

	"droidtrans/internal/fast"
)

func main() {
	srv := fast.New(os.Args[1], "127.0.0.1")
	// 只认这个 token：能收到文件就说明 ATF3 的令牌字段位置和长度前缀都对
	srv.SetAuth(func(t string) bool { return t == "tok-abc" })
	srv.Start()
	fmt.Println("ready")
	time.Sleep(90 * time.Second)
}
