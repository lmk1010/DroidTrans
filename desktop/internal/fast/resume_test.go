package fast

// 断点续传的行为测试。
//
// 这里不测「函数返回值对不对」，测的是用户真正在乎的三件事：
//   · 断了再连，已经传过的字节不会再传一遍
//   · 不管断在哪，输出目录里都不会出现半个文件
//   · 续出来的文件内容和原文件逐字节相同
//
// 所以全部走真实的 TCP 连接和真实的落盘，不 mock。

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"io"
	"math/rand"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// dial 连上服务端，发完头，读回偏移量应答。
func dial(t *testing.T, addr, name string, size int64) (net.Conn, int64) {
	t.Helper()
	conn, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatalf("连不上: %v", err)
	}
	var h bytes.Buffer
	h.WriteString("ATF3")
	_ = binary.Write(&h, binary.BigEndian, uint32(0)) // 无令牌
	_ = binary.Write(&h, binary.BigEndian, uint32(len(name)))
	h.WriteString(name)
	_ = binary.Write(&h, binary.BigEndian, uint64(size))
	if _, err := conn.Write(h.Bytes()); err != nil {
		t.Fatalf("发头失败: %v", err)
	}

	status := make([]byte, 1)
	if _, err := io.ReadFull(conn, status); err != nil {
		t.Fatalf("读应答失败: %v", err)
	}
	if status[0] == statusErr {
		var n uint32
		_ = binary.Read(conn, binary.BigEndian, &n)
		msg := make([]byte, n)
		_, _ = io.ReadFull(conn, msg)
		conn.Close()
		t.Fatalf("服务端拒绝: %s", msg)
	}
	if status[0] != statusGo {
		conn.Close()
		t.Fatalf("应答状态不对: %#x", status[0])
	}
	var off uint64
	if err := binary.Read(conn, binary.BigEndian, &off); err != nil {
		t.Fatalf("读偏移量失败: %v", err)
	}
	return conn, int64(off)
}

// startServer 起一个真的服务端，返回地址和输出目录。
func startServer(t *testing.T) (string, string) {
	t.Helper()
	out := t.TempDir()
	s := New(out, "127.0.0.1")

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("监听失败: %v", err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go s.handleTCP(conn)
		}
	}()
	return ln.Addr().String(), out
}

func randomBytes(n int) []byte {
	b := make([]byte, n)
	r := rand.New(rand.NewSource(42))
	_, _ = r.Read(b)
	return b
}

// 断在中途，重连之后只补剩下的部分，最终内容完整。
// 这是整个功能的主线场景：传 100GB 断在 99GB，不能从头再来。
func TestResumeSendsOnlyTheRemainder(t *testing.T) {
	addr, out := startServer(t)
	const name = "video.mp4"
	data := randomBytes(512 * 1024)
	size := int64(len(data))

	// 第一次：只发前 200KB 就把连接掐了
	const firstChunk = 200 * 1024
	conn, off := dial(t, addr, name, size)
	if off != 0 {
		t.Fatalf("全新文件的偏移量应该是 0，拿到 %d", off)
	}
	if _, err := conn.Write(data[:firstChunk]); err != nil {
		t.Fatalf("写第一段失败: %v", err)
	}
	conn.Close()

	// 等服务端把已收到的字节落盘
	part := PartPath(filepath.Join(out, name), size)
	waitFor(t, func() bool {
		st, err := os.Stat(part)
		return err == nil && st.Size() == firstChunk
	}, "分片没有停在断点上")

	// 目标路径上绝不能出现半个文件
	if _, err := os.Stat(filepath.Join(out, name)); !os.IsNotExist(err) {
		t.Fatal("传了一半，目标路径上却已经有文件了 —— 用户会以为它是好的")
	}

	// 第二次：服务端应该告诉我们从 200KB 接着发
	conn2, off2 := dial(t, addr, name, size)
	defer conn2.Close()
	if off2 != firstChunk {
		t.Fatalf("续传偏移量不对：应该是 %d，拿到 %d", firstChunk, off2)
	}
	if _, err := conn2.Write(data[firstChunk:]); err != nil {
		t.Fatalf("写剩余部分失败: %v", err)
	}
	expectDone(t, conn2)

	got, err := os.ReadFile(filepath.Join(out, name))
	if err != nil {
		t.Fatalf("目标文件没落盘: %v", err)
	}
	if !bytes.Equal(got, data) {
		t.Fatalf("内容对不上：期望 %d 字节，实际 %d 字节", len(data), len(got))
	}
	if _, err := os.Stat(part); !os.IsNotExist(err) {
		t.Error("转正之后分片文件应该消失")
	}
}

// 已经完整存在的文件，服务端直接回 offset == size，客户端一个字节都不用发。
func TestAlreadyCompleteNeedsNoBytes(t *testing.T) {
	addr, out := startServer(t)
	const name = "done.bin"
	data := randomBytes(4096)
	if err := os.WriteFile(filepath.Join(out, name), data, 0o644); err != nil {
		t.Fatal(err)
	}

	conn, off := dial(t, addr, name, int64(len(data)))
	defer conn.Close()
	if off != int64(len(data)) {
		t.Fatalf("已完整的文件应该回 offset==size，拿到 %d", off)
	}
	expectDone(t, conn)
}

// 同名但大小不同 —— 相册里同名文件遍地都是，绝不能续错。
func TestDifferentSizeDoesNotResumeWrongFile(t *testing.T) {
	addr, out := startServer(t)
	const name = "IMG_0001.JPG"

	// 先留下一个 100 字节文件的半截分片
	old := randomBytes(100)
	oldPart := PartPath(filepath.Join(out, name), int64(len(old)))
	if err := os.WriteFile(oldPart, old[:60], 0o644); err != nil {
		t.Fatal(err)
	}

	// 换一个 300 字节的同名文件过来
	data := randomBytes(300)
	conn, off := dial(t, addr, name, int64(len(data)))
	defer conn.Close()
	if off != 0 {
		t.Fatalf("大小不同就不该续传，却拿到偏移量 %d", off)
	}
	if _, err := conn.Write(data); err != nil {
		t.Fatal(err)
	}
	expectDone(t, conn)

	got, _ := os.ReadFile(filepath.Join(out, name))
	if !bytes.Equal(got, data) {
		t.Fatal("续错了文件，内容被污染")
	}
	if _, err := os.Stat(oldPart); !os.IsNotExist(err) {
		t.Error("转正时应该顺手清掉同名的旧分片")
	}
}

// 分片比服务端上次报出去的偏移量还长时，多出来的必须截掉，
// 否则文件中间会多出一段重复字节，内容静默损坏。
func TestOverlongPartIsTruncated(t *testing.T) {
	out := t.TempDir()
	dest := filepath.Join(out, "a.bin")
	if err := os.WriteFile(PartPath(dest, 100), randomBytes(80), 0o644); err != nil {
		t.Fatal(err)
	}
	f, err := OpenPart(dest, 100, 50)
	if err != nil {
		t.Fatal(err)
	}
	_ = f.Close()
	st, _ := os.Stat(PartPath(dest, 100))
	if st.Size() != 50 {
		t.Fatalf("应该截到 50 字节，实际 %d", st.Size())
	}
}

// 空间不够要在传之前就拒绝，而不是传了几十 GB 才 ENOSPC。
func TestEnsureSpaceRejectsImpossibleSize(t *testing.T) {
	if err := EnsureSpace(t.TempDir(), 1<<62); err == nil {
		t.Fatal("要 4EB 空间居然通过了")
	}
	if err := EnsureSpace(t.TempDir(), 1024); err != nil {
		t.Fatalf("1KB 都放不下？%v", err)
	}
}

func expectDone(t *testing.T, conn net.Conn) {
	t.Helper()
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	b := make([]byte, 1)
	if _, err := io.ReadFull(conn, b); err != nil {
		t.Fatalf("没等到收尾应答: %v", err)
	}
	if b[0] == statusErr {
		var n uint32
		_ = binary.Read(conn, binary.BigEndian, &n)
		msg := make([]byte, n)
		_, _ = io.ReadFull(conn, msg)
		t.Fatalf("服务端报错: %s", msg)
	}
	if b[0] != statusDone {
		t.Fatalf("收尾状态不对: %#x", b[0])
	}
}

func waitFor(t *testing.T, cond func() bool, msg string) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal(msg)
}

var _ = fmt.Sprintf

// 大文件额度：超过上限要在客户端发出任何字节之前就被拒绝。
//
// 传到 3 GB 才说「太大了」是最糟的做法 —— 大小在 ATF3 的头里就有，
// 握手那一步就该给出结论。
func TestLargeFileRejectedAtHandshake(t *testing.T) {
	out := t.TempDir()
	s := New(out, "127.0.0.1")
	s.SetMaxFileSize(func() int64 { return 4 << 30 })

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go s.handleTCP(c)
		}
	}()

	conn, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()

	var h bytes.Buffer
	h.WriteString("ATF3")
	_ = binary.Write(&h, binary.BigEndian, uint32(0))
	_ = binary.Write(&h, binary.BigEndian, uint32(len("big.mov")))
	h.WriteString("big.mov")
	_ = binary.Write(&h, binary.BigEndian, uint64(5<<30)) // 5 GB
	if _, err := conn.Write(h.Bytes()); err != nil {
		t.Fatal(err)
	}

	st := make([]byte, 1)
	if _, err := io.ReadFull(conn, st); err != nil {
		t.Fatal(err)
	}
	// 必须是「需要升级」而不是普通错误，客户端才能给出升级入口
	if st[0] != statusUpgrade {
		t.Fatalf("状态字应该是 statusUpgrade(0x03)，拿到 %#x", st[0])
	}
	var n uint32
	_ = binary.Read(conn, binary.BigEndian, &n)
	msg := make([]byte, n)
	_, _ = io.ReadFull(conn, msg)
	if !bytes.Contains(msg, []byte("Pro")) {
		t.Errorf("拒绝原因里没提 Pro：%s", msg)
	}
	// 目标位置不该留下任何东西
	if entries, _ := os.ReadDir(out); len(entries) != 0 {
		t.Errorf("被拒之后输出目录里多了 %d 个东西", len(entries))
	}
}

// 额度内的文件照常传。
func TestFileWithinQuotaPasses(t *testing.T) {
	addr, out := startServer(t)
	data := randomBytes(2048)
	conn, off := dial(t, addr, "small.jpg", int64(len(data)))
	defer conn.Close()
	if off != 0 {
		t.Fatalf("偏移量应为 0，拿到 %d", off)
	}
	if _, err := conn.Write(data); err != nil {
		t.Fatal(err)
	}
	expectDone(t, conn)
	if _, err := os.Stat(filepath.Join(out, "small.jpg")); err != nil {
		t.Fatal("额度内的文件没落盘")
	}
}
