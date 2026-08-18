package main

import (
	"embed"
	"flag"
	"fmt"
	"io/fs"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"runtime"
	"syscall"
	"time"

	"droidtrans/internal/app"
)

//go:embed all:frontend
var frontendFS embed.FS

func init() {
	runtime.LockOSThread()
}

func main() {
	headless := flag.Bool("headless", false, "只启动服务，不打开窗口")
	addr := flag.String("addr", fmt.Sprintf("0.0.0.0:%d", app.HTTPPort), "listen address")
	flag.Parse()

	application, err := app.New()
	if err != nil {
		fmt.Fprintln(os.Stderr, "init:", err)
		os.Exit(1)
	}
	sub, err := fs.Sub(frontendFS, "frontend")
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	application.Frontend = sub
	application.OnAttention = requestAttention
	application.StartBackground()

	srv := &http.Server{Addr: *addr, Handler: application.Handler()}
	go func() {
		fmt.Printf("DroidTrans  http://127.0.0.1:%d\n", app.HTTPPort)
		fmt.Printf("输出目录      %s\n", application.OutputDir)
		fmt.Printf("ADB          %s\n", application.ADB.Bin())
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
	}()

	deadline := time.Now().Add(8 * time.Second)
	for time.Now().Before(deadline) {
		resp, err := http.Get(fmt.Sprintf("http://127.0.0.1:%d/api/health", app.HTTPPort))
		if err == nil {
			_ = resp.Body.Close()
			if resp.StatusCode == 200 {
				break
			}
		}
		time.Sleep(150 * time.Millisecond)
	}

	if !*headless {
		runNativeWindow(fmt.Sprintf("http://127.0.0.1:%d", app.HTTPPort))
		_ = srv.Close()
		return
	}

	ch := make(chan os.Signal, 1)
	signal.Notify(ch, syscall.SIGINT, syscall.SIGTERM)
	<-ch
	_ = srv.Close()
}

func openUI(url string) {
	candidates := []struct {
		bin  string
		args []string
	}{
		{"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", []string{"--app=" + url, "--new-window", "--force-dark-mode"}},
		{"/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge", []string{"--app=" + url, "--new-window", "--force-dark-mode"}},
		{"/Applications/Brave Browser.app/Contents/MacOS/Brave Browser", []string{"--app=" + url, "--new-window", "--force-dark-mode"}},
	}
	if runtime.GOOS == "darwin" {
		for _, c := range candidates {
			if _, err := os.Stat(c.bin); err == nil {
				_ = exec.Command(c.bin, c.args...).Start()
				return
			}
		}
		_ = exec.Command("open", url).Start()
		return
	}
	if runtime.GOOS == "windows" {
		_ = exec.Command("cmd", "/c", "start", url).Start()
		return
	}
	_ = exec.Command("xdg-open", url).Start()
}
