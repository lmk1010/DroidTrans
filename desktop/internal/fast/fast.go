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

var magic = []byte("ATF1")

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
	name, size, err := readATF1(conn)
	if err != nil {
		_, _ = conn.Write([]byte("ERR " + err.Error() + "\n"))
		return
	}
	dest, err := SafeJoin(s.OutputDir(), name)
	if err != nil {
		_, _ = conn.Write([]byte("ERR " + err.Error() + "\n"))
		return
	}
	if err := writeStream(conn, dest, size, func(n int64) {
		s.progress(name, n, size)
	}); err != nil {
		_, _ = conn.Write([]byte("ERR " + err.Error() + "\n"))
		return
	}
	s.emit(dest)
	_, _ = conn.Write([]byte("OK\n"))
}

func readATF1(r io.Reader) (string, int64, error) {
	head := make([]byte, 4)
	if _, err := io.ReadFull(r, head); err != nil {
		return "", 0, err
	}
	if string(head) != "ATF1" {
		return "", 0, fmt.Errorf("bad magic")
	}
	var nameLen uint32
	if err := binary.Read(r, binary.BigEndian, &nameLen); err != nil {
		return "", 0, err
	}
	if nameLen > 4096 {
		return "", 0, fmt.Errorf("name too long")
	}
	nameBuf := make([]byte, nameLen)
	if _, err := io.ReadFull(r, nameBuf); err != nil {
		return "", 0, err
	}
	var size uint64
	if err := binary.Read(r, binary.BigEndian, &size); err != nil {
		return "", 0, err
	}
	return string(nameBuf), int64(size), nil
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

func writeStream(r io.Reader, dest string, size int64, report func(int64)) error {
	f, err := os.Create(dest)
	if err != nil {
		return err
	}
	defer f.Close()
	cw := &countWriter{w: f, report: report}
	if size > 0 {
		_, err = io.CopyN(cw, r, size)
	} else {
		_, err = io.Copy(cw, r)
	}
	if report != nil {
		report(cw.n)
	}
	return err
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
	for {
		line, err := br.ReadString('\n')
		if err != nil {
			return
		}
		line = strings.TrimSpace(line)
		cmd, arg, _ := strings.Cut(line, " ")
		cmd = strings.ToUpper(cmd)
		switch cmd {
		case "USER", "PASS":
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
				_ = writeStream(dataConn, dest, 0, func(n int64) {
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
