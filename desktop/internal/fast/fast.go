package fast

import (
	"bufio"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	TCPPort = 9501
	FTPPort = 9502
	Chunk   = 1024 * 1024
)

type FileHandler func(name string, size int64, dest string)
type ProgressHandler func(name string, written, total int64)

type Server struct {
	mu         sync.RWMutex
	outputDir  string
	lanIP      string
	onReceived FileHandler
	onProgress ProgressHandler
	tcpUp      bool
	ftpUp      bool
	auth       func(token string) bool
	maxFile    func() int64
}

// SetMaxFileSize 单个文件的免费上限。fn 返回 0 表示不限。
//
// 用回调而不是一个数：授权状态随时会变（用户当场激活），
// 存成快照的话得等重启才生效。
func (s *Server) SetMaxFileSize(fn func() int64) {
	s.mu.Lock()
	s.maxFile = fn
	s.mu.Unlock()
}

func (s *Server) fileLimit() int64 {
	s.mu.RLock()
	fn := s.maxFile
	s.mu.RUnlock()
	if fn == nil {
		return 0
	}
	return fn()
}

// SetAuth 设置令牌校验。返回 nil 表示不需要配对。
func (s *Server) SetAuth(fn func(token string) bool) {
	s.mu.Lock()
	s.auth = fn
	s.mu.Unlock()
}

// checkToken 需要配对时校验令牌；不需要配对时一律放行。
func (s *Server) checkToken(token string) bool {
	s.mu.RLock()
	fn := s.auth
	s.mu.RUnlock()
	if fn == nil {
		return true
	}
	return fn(token)
}

func (s *Server) setUp(tcp, up bool) {
	s.mu.Lock()
	if tcp {
		s.tcpUp = up
	} else {
		s.ftpUp = up
	}
	s.mu.Unlock()
}

// Live 返回两个高速通道当前是否真的在监听。
// 端口被占时不能照旧通告给手机：手机会先去连一个不存在的端口，白等一次超时。
func (s *Server) Live() (tcp bool, ftp bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.tcpUp, s.ftpUp
}

// listenRetry 端口刚被上一个实例释放时会短暂占用，重试几次再放弃。
func listenRetry(addr string) (net.Listener, error) {
	var err error
	for i := 0; i < 6; i++ {
		var ln net.Listener
		ln, err = net.Listen("tcp", addr)
		if err == nil {
			return ln, nil
		}
		time.Sleep(500 * time.Millisecond)
	}
	return nil, err
}

func New(outputDir, lanIP string) *Server {
	return &Server{outputDir: outputDir, lanIP: lanIP}
}

func (s *Server) SetOutputDir(dir string) {
	s.mu.Lock()
	s.outputDir = dir
	s.mu.Unlock()
}

func (s *Server) OutputDir() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.outputDir
}

func (s *Server) SetLANIP(ip string) {
	s.mu.Lock()
	s.lanIP = ip
	s.mu.Unlock()
}

func (s *Server) SetOnReceived(fn FileHandler) {
	s.mu.Lock()
	s.onReceived = fn
	s.mu.Unlock()
}

func (s *Server) SetOnProgress(fn ProgressHandler) {
	s.mu.Lock()
	s.onProgress = fn
	s.mu.Unlock()
}

func (s *Server) progress(name string, written, total int64) {
	s.mu.RLock()
	fn := s.onProgress
	s.mu.RUnlock()
	if fn != nil {
		fn(name, written, total)
	}
}

func (s *Server) emit(dest string) {
	name := filepath.Base(dest)
	var size int64
	if st, err := os.Stat(dest); err == nil {
		size = st.Size()
	}
	s.mu.RLock()
	fn := s.onReceived
	s.mu.RUnlock()
	if fn != nil {
		fn(name, size, dest)
	}
}

func (s *Server) Start() {
	go s.serveTCP()
	go s.serveFTP()
}

func SafeJoin(base, relative string) (string, error) {
	relative = strings.TrimLeft(strings.ReplaceAll(relative, "\\", "/"), "/")
	if relative == "" {
		relative = "unnamed.bin"
	}
	dest := filepath.Join(base, filepath.FromSlash(relative))
	root, err := filepath.Abs(base)
	if err != nil {
		return "", err
	}
	abs, err := filepath.Abs(dest)
	if err != nil {
		return "", err
	}
	sep := string(os.PathSeparator)
	if abs != root && !strings.HasPrefix(abs, root+sep) {
		abs = filepath.Join(root, filepath.Base(relative))
	}
	if err := os.MkdirAll(filepath.Dir(abs), 0o755); err != nil {
		return "", err
	}
	return abs, nil
}

func (s *Server) serveTCP() {
	ln, err := listenRetry(fmt.Sprintf("0.0.0.0:%d", TCPPort))
	if err != nil {
		fmt.Println("tcp listen:", err)
		s.setUp(true, false)
		return
	}
	s.setUp(true, true)
	defer s.setUp(true, false)
	fmt.Println("⚡ TCP :" + strconv.Itoa(TCPPort))
	for {
		conn, err := ln.Accept()
		if err != nil {
			continue
		}
		go s.handleTCP(conn)
	}
}

func (s *Server) handleTCP(conn net.Conn) {
	defer conn.Close()
	if tc, ok := conn.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}
	name, size, token, err := readHeader(conn)
	if err != nil {
		writeReject(conn, err.Error())
		return
	}
	if !s.checkToken(token) {
		// 高速通道也要认令牌，否则配对形同虚设
		writeReject(conn, "pairing required")
		return
	}
	// 大小在头里就有，所以能在客户端发出任何一个字节之前拒绝。
	// 传到 3 GB 才说「太大了」是最糟的做法。
	if limit := s.fileLimit(); limit > 0 && size > limit {
		writeUpgrade(conn, fmt.Sprintf("单个文件超过 %s 需要 Pro（这个文件 %s）",
			humanBytes(limit), humanBytes(size)))
		return
	}

	dest, err := SafeJoin(s.OutputDir(), name)
	if err != nil {
		writeReject(conn, err.Error())
		return
	}

	// 已经有多少可以不用再传。这一步必须在客户端发任何文件字节之前完成，
	// 否则「续传」就退化成「传完再丢掉重复的部分」，一点带宽都省不下来。
	offset, done := ResumeOffset(dest, size)
	if done {
		// 目标文件已经完整。告诉客户端「你不用发了」，它就直接收尾。
		if err := writeAccept(conn, size); err != nil {
			return
		}
		s.progress(name, size, size)
		s.emit(dest)
		writeDone(conn)
		return
	}

	if err := EnsureSpace(filepath.Dir(dest), size-offset); err != nil {
		writeReject(conn, err.Error())
		return
	}

	f, err := OpenPart(dest, size, offset)
	if err != nil {
		writeReject(conn, err.Error())
		return
	}

	if err := writeAccept(conn, offset); err != nil {
		_ = f.Close()
		return
	}

	// 客户端从 offset 开始发，所以这里只等剩下的那些字节。
	remaining := int64(-1)
	if size > 0 {
		remaining = size - offset
	}
	err = writeInto(f, conn, remaining, offset, func(n int64) {
		s.progress(name, n, size)
	})
	closeErr := f.Close()
	if err == nil {
		err = closeErr
	}
	if err != nil {
		// 分片留着不删 —— 那正是下次续传的起点。
		writeReject(conn, err.Error())
		return
	}

	if size > 0 {
		if err := CommitPart(dest, size); err != nil {
			writeReject(conn, err.Error())
			return
		}
	}
	s.emit(dest)
	writeDone(conn)
}

// ---- 应答 ----
//
// 应答一律是二进制，不再是以前的 "OK\n" / "ERR ...\n" 文本。
// 因为现在中途要回一个 u64 偏移量，二进制数字和换行分隔的文本混在一条流上，
// 解析起来极易出错（偏移量里恰好有 0x0A 就会被当成行尾）。

const (
	statusGo   byte = 0x00 // 继续，后面跟 u64 偏移量
	statusErr  byte = 0x01 // 拒绝，后面跟 u32 长度 + utf8 原因
	statusDone byte = 0x02 // 收完了，落盘成功
	// statusUpgrade 超出免费额度。格式和 statusErr 一样，但分开一个状态字，
	// 客户端才能给出「升级」入口 —— 混在普通错误里，用户只会以为传输坏了。
	statusUpgrade byte = 0x03
)

// writeAccept 告诉客户端「从第 offset 字节开始发」。
func writeAccept(w io.Writer, offset int64) error {
	buf := make([]byte, 9)
	buf[0] = statusGo
	binary.BigEndian.PutUint64(buf[1:], uint64(offset))
	_, err := w.Write(buf)
	return err
}

func writeReject(w io.Writer, msg string) {
	writeStatusMsg(w, statusErr, msg)
}

func writeStatusMsg(w io.Writer, status byte, msg string) {
	b := []byte(msg)
	if len(b) > 4096 {
		b = b[:4096]
	}
	buf := make([]byte, 5, 5+len(b))
	buf[0] = status
	binary.BigEndian.PutUint32(buf[1:], uint32(len(b)))
	_, _ = w.Write(append(buf, b...))
}

// writeUpgrade 超出免费额度。和 writeReject 同样的帧，只是状态字不同。
func writeUpgrade(w io.Writer, msg string) {
	writeStatusMsg(w, statusUpgrade, msg)
}

func writeDone(w io.Writer) {
	_, _ = w.Write([]byte{statusDone})
}

// readHeader 读传输头。
//
//	ATF3: magic | tokenLen | token | nameLen | name | size(u64)
//
// 读完头之后服务端必须先回一个偏移量应答，客户端才会开始发文件字节 ——
// 这一次往返就是断点续传的全部代价，换来的是不用重传已经落盘的部分。
//
// ATF1（无令牌）和 ATF2（无偏移协商）都已经删掉。发布之前没有存量客户端，
// 留着两套解析分支只会让线格式长期背着包袱。
func readHeader(r io.Reader) (name string, size int64, token string, err error) {
	head := make([]byte, 4)
	if _, err = io.ReadFull(r, head); err != nil {
		return "", 0, "", err
	}
	if string(head) != "ATF3" {
		return "", 0, "", fmt.Errorf("bad magic")
	}
	token, err = readStr(r, 512)
	if err != nil {
		return "", 0, "", err
	}
	name, err = readStr(r, 4096)
	if err != nil {
		return "", 0, "", err
	}
	var raw uint64
	if err = binary.Read(r, binary.BigEndian, &raw); err != nil {
		return "", 0, "", err
	}
	if int64(raw) < 0 {
		return "", 0, "", fmt.Errorf("bad size")
	}
	return name, int64(raw), token, nil
}

func readStr(r io.Reader, max uint32) (string, error) {
	var n uint32
	if err := binary.Read(r, binary.BigEndian, &n); err != nil {
		return "", err
	}
	if n > max {
		return "", fmt.Errorf("field too long")
	}
	buf := make([]byte, n)
	if _, err := io.ReadFull(r, buf); err != nil {
		return "", err
	}
	return string(buf), nil
}

type countWriter struct {
	w      io.Writer
	n      int64
	last   time.Time
	report func(int64)
}

func (c *countWriter) Write(p []byte) (int, error) {
	n, err := c.w.Write(p)
	c.n += int64(n)
	if c.report != nil {
		now := time.Now()
		if c.last.IsZero() || now.Sub(c.last) >= 200*time.Millisecond || err != nil {
			c.last = now
			c.report(c.n)
		}
	}
	return n, err
}

// writeInto 把 r 的内容写进已经定位好的 f。
//
// base 是这个文件此前已经落盘的字节数 —— 进度要从它接着报，
// 否则续传时进度条会从 0 重新爬一遍，用户会以为又从头传了。
//
// remaining < 0 表示大小未知（FTP 那条老路），一直读到 EOF。
func writeInto(f io.Writer, r io.Reader, remaining, base int64, report func(int64)) error {
	cw := &countWriter{w: f, n: base, report: report}
	var err error
	if remaining >= 0 {
		_, err = io.CopyN(cw, r, remaining)
	} else {
		_, err = io.Copy(cw, r)
	}
	if report != nil {
		report(cw.n)
	}
	return err
}

// writeStreamAtomic 大小未知时的落盘（FTP）。
//
// 即使不能续传，也绝不把没写完的东西留在目标路径上 —— 先写临时文件，
// 干净结束才 rename。半个文件躺在输出目录里比传输失败更糟：
// 用户不知道它是坏的。
func writeStreamAtomic(r io.Reader, dest string, report func(int64)) error {
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(dest), "."+filepath.Base(dest)+".*"+partSuffix)
	if err != nil {
		return err
	}
	name := tmp.Name()
	if err := writeInto(tmp, r, -1, 0, report); err != nil {
		_ = tmp.Close()
		_ = os.Remove(name)
		return err
	}
	if err := tmp.Close(); err != nil {
		_ = os.Remove(name)
		return err
	}
	return os.Rename(name, dest)
}

func (s *Server) serveFTP() {
	ln, err := listenRetry(fmt.Sprintf("0.0.0.0:%d", FTPPort))
	if err != nil {
		fmt.Println("ftp listen:", err)
		s.setUp(false, false)
		return
	}
	s.setUp(false, true)
	defer s.setUp(false, false)
	fmt.Println("⚡ FTP :" + strconv.Itoa(FTPPort))
	for {
		conn, err := ln.Accept()
		if err != nil {
			continue
		}
		go s.handleFTP(conn)
	}
}

func ftpSend(w io.Writer, msg string) {
	_, _ = io.WriteString(w, msg+"\r\n")
}

func (s *Server) handleFTP(conn net.Conn) {
	defer conn.Close()
	br := bufio.NewReader(conn)
	ftpSend(conn, "220 DroidTrans FTP")
	var dataLn net.Listener
	defer func() {
		if dataLn != nil {
			_ = dataLn.Close()
		}
	}()
	filename := "unnamed.bin"
	pass := ""
	for {
		line, err := br.ReadString('\n')
		if err != nil {
			return
		}
		line = strings.TrimSpace(line)
		cmd, arg, _ := strings.Cut(line, " ")
		cmd = strings.ToUpper(cmd)
		switch cmd {
		case "USER":
			ftpSend(conn, "331 Need password")
		case "PASS":
			// 配对令牌就当密码用，标准 FTP 客户端也能接
			pass = strings.TrimSpace(arg)
			if !s.checkToken(pass) {
				ftpSend(conn, "530 pairing required")
				continue
			}
			ftpSend(conn, "230 OK")
		case "TYPE":
			ftpSend(conn, "200 Type set")
		case "SYST":
			ftpSend(conn, "215 UNIX Type: L8")
		case "FEAT":
			ftpSend(conn, "211 No Features")
		case "PWD":
			ftpSend(conn, `257 "/"`)
		case "CWD":
			ftpSend(conn, "250 OK")
		case "PASV":
			if dataLn != nil {
				_ = dataLn.Close()
			}
			dataLn, err = net.Listen("tcp", "0.0.0.0:0")
			if err != nil {
				ftpSend(conn, "425 PASV failed")
				continue
			}
			port := dataLn.Addr().(*net.TCPAddr).Port
			s.mu.RLock()
			ip := s.lanIP
			s.mu.RUnlock()
			if ip == "" {
				ip = "127.0.0.1"
			}
			p1, p2 := port/256, port%256
			ftpSend(conn, fmt.Sprintf("227 Entering Passive Mode (%s,%d,%d)", strings.ReplaceAll(ip, ".", ","), p1, p2))
		case "STOR":
			if !s.checkToken(pass) {
				ftpSend(conn, "530 pairing required")
				continue
			}
			if strings.TrimSpace(arg) != "" {
				filename = strings.TrimSpace(arg)
			}
			if dataLn == nil {
				ftpSend(conn, "425 Use PASV first")
				continue
			}
			ftpSend(conn, "150 Opening data connection")
			dataConn, err := dataLn.Accept()
			if err != nil {
				ftpSend(conn, "425 data accept failed")
				continue
			}
			dest, err := SafeJoin(s.OutputDir(), filename)
			if err == nil {
				_ = writeStreamAtomic(dataConn, dest, func(n int64) {
					s.progress(filename, n, 0)
				})
				s.emit(dest)
			}
			_ = dataConn.Close()
			_ = dataLn.Close()
			dataLn = nil
			ftpSend(conn, "226 Transfer complete")
		case "QUIT", "BYE":
			ftpSend(conn, "221 Bye")
			return
		default:
			ftpSend(conn, "502 Not implemented")
		}
	}
}

// Caps 只通告真正在监听的通道。
func (s *Server) Caps(lanIP string, httpPort int) map[string]any {
	tcpUp, ftpUp := s.Live()
	prefer := []string{}
	protocols := []map[string]any{}
	if tcpUp {
		prefer = append(prefer, "tcp")
		protocols = append(protocols, map[string]any{"id": "tcp", "port": TCPPort, "priority": 1})
	}
	if ftpUp {
		prefer = append(prefer, "ftp")
		protocols = append(protocols, map[string]any{"id": "ftp", "port": FTPPort, "priority": 2})
	}
	prefer = append(prefer, "http_put", "http_multipart")
	protocols = append(protocols,
		map[string]any{"id": "http_put", "path": "/api/fast/put", "priority": 3},
		map[string]any{"id": "http_multipart", "path": "/api/wifi/upload_photo", "priority": 4},
	)
	out := map[string]any{
		"success":   true,
		"ip":        lanIP,
		"http_port": httpPort,
		"prefer":    prefer,
		"protocols": protocols,
	}
	if tcpUp {
		out["tcp_port"] = TCPPort
	}
	if ftpUp {
		out["ftp_port"] = FTPPort
	}
	return out
}
