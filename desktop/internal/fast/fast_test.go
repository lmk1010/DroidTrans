package fast

import (
	"bytes"
	"encoding/binary"
	"path/filepath"
	"strings"
	"testing"
)

func TestSafeJoinKeepsFilesInsideRoot(t *testing.T) {
	root := t.TempDir()
	cases := []string{
		"../evil.jpg",
		"../../../../etc/evil.jpg",
		"/etc/evil.jpg",
		"..\\..\\evil.jpg",
		"a/../../evil.jpg",
	}
	for _, in := range cases {
		got, err := SafeJoin(root, in)
		if err != nil {
			t.Fatalf("SafeJoin(%q): %v", in, err)
		}
		if !strings.HasPrefix(got, root+string(filepath.Separator)) {
			t.Errorf("SafeJoin(%q) = %q, 逃出了 %q", in, got, root)
		}
	}
}

func TestSafeJoinKeepsSubdirs(t *testing.T) {
	root := t.TempDir()
	got, err := SafeJoin(root, "DCIM/Camera/IMG_1.jpg")
	if err != nil {
		t.Fatal(err)
	}
	want := filepath.Join(root, "DCIM", "Camera", "IMG_1.jpg")
	if got != want {
		t.Errorf("got %q want %q", got, want)
	}
}

func TestSafeJoinEmptyName(t *testing.T) {
	root := t.TempDir()
	got, err := SafeJoin(root, "")
	if err != nil {
		t.Fatal(err)
	}
	if filepath.Base(got) != "unnamed.bin" {
		t.Errorf("got %q", got)
	}
}

func TestCapsOnlyAdvertisesLiveChannels(t *testing.T) {
	s := New(t.TempDir(), "192.168.1.5")

	// 两个端口都没起来时，只能给 HTTP 兜底，不能把 tcp/ftp 端口报出去
	caps := s.Caps("192.168.1.5", 9500)
	if _, ok := caps["tcp_port"]; ok {
		t.Error("TCP 没在监听，却把端口通告出去了")
	}
	if _, ok := caps["ftp_port"]; ok {
		t.Error("FTP 没在监听，却把端口通告出去了")
	}
	prefer, _ := caps["prefer"].([]string)
	if len(prefer) != 2 || prefer[0] != "http_put" {
		t.Errorf("回退顺序不对: %v", prefer)
	}

	s.setUp(true, true)
	caps = s.Caps("192.168.1.5", 9500)
	if caps["tcp_port"] != TCPPort {
		t.Errorf("TCP 起来了却没通告: %v", caps["tcp_port"])
	}
	prefer, _ = caps["prefer"].([]string)
	if len(prefer) == 0 || prefer[0] != "tcp" {
		t.Errorf("TCP 可用时应当排在最前: %v", prefer)
	}
}

func TestListenRetryReportsLiveState(t *testing.T) {
	s := New(t.TempDir(), "127.0.0.1")
	if tcp, ftp := s.Live(); tcp || ftp {
		t.Error("还没 Start 就报告在监听")
	}
}

func TestReadHeaderATF3(t *testing.T) {
	buf := &bytes.Buffer{}
	buf.WriteString("ATF3")
	writeStr(buf, "tok123")
	writeStr(buf, "b.mp4")
	_ = binary.Write(buf, binary.BigEndian, uint64(99))
	name, size, token, err := readHeader(buf)
	if err != nil || name != "b.mp4" || size != 99 || token != "tok123" {
		t.Fatalf("ATF3 解析错了: %q %d %q %v", name, size, token, err)
	}
}

// ATF1（无令牌）和 ATF2（无偏移协商）已经删掉。
// 发布之前没有存量客户端，留着两套解析分支只会让线格式长期背包袱 ——
// 而且 ATF1 根本没有令牌，等于给 TCP 通道留了一个免鉴权的后门。
func TestOldMagicsAreGone(t *testing.T) {
	for _, magic := range []string{"ATF1", "ATF2"} {
		buf := &bytes.Buffer{}
		buf.WriteString(magic)
		writeStr(buf, "a.jpg")
		_ = binary.Write(buf, binary.BigEndian, uint64(1))
		if _, _, _, err := readHeader(buf); err == nil {
			t.Errorf("%s 不该再被接受", magic)
		}
	}
}

func TestReadHeaderRejectsGarbage(t *testing.T) {
	buf := bytes.NewBufferString("HTTP/1.1 200")
	if _, _, _, err := readHeader(buf); err == nil {
		t.Error("非法魔数应当报错")
	}
}

func TestCheckTokenGate(t *testing.T) {
	s := New(t.TempDir(), "127.0.0.1")
	if !s.checkToken("") {
		t.Error("没设置校验函数时应当放行")
	}
	s.SetAuth(func(tok string) bool { return tok == "good" })
	if s.checkToken("bad") {
		t.Error("错误令牌不该放行")
	}
	if !s.checkToken("good") {
		t.Error("正确令牌应当放行")
	}
}

func writeStr(b *bytes.Buffer, s string) {
	_ = binary.Write(b, binary.BigEndian, uint32(len(s)))
	b.WriteString(s)
}
