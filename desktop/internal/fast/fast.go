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
)

const (
	TCPPort = 9501
	FTPPort = 9502
	Chunk   = 256 * 1024
)

var magic = []byte("ATF1")

type Server struct {
	mu        sync.RWMutex
	outputDir string
	lanIP     string
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
	ln, err := net.Listen("tcp", fmt.Sprintf("0.0.0.0:%d", TCPPort))
	if err != nil {
		fmt.Println("tcp listen:", err)
		return
	}
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
	if err := writeStream(conn, dest, size); err != nil {
		_, _ = conn.Write([]byte("ERR " + err.Error() + "\n"))
		return
	}
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

func writeStream(r io.Reader, dest string, size int64) error {
	f, err := os.Create(dest)
	if err != nil {
		return err
	}
	defer f.Close()
	if size > 0 {
		_, err = io.CopyN(f, r, size)
		return err
	}
	_, err = io.Copy(f, r)
	return err
}

func (s *Server) serveFTP() {
	ln, err := net.Listen("tcp", fmt.Sprintf("0.0.0.0:%d", FTPPort))
	if err != nil {
		fmt.Println("ftp listen:", err)
		return
	}
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
				f, e := os.Create(dest)
				if e == nil {
					_, _ = io.Copy(f, dataConn)
					_ = f.Close()
				}
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

func Caps(lanIP string, httpPort int) map[string]any {
	return map[string]any{
		"success":   true,
		"ip":        lanIP,
		"http_port": httpPort,
		"tcp_port":  TCPPort,
		"ftp_port":  FTPPort,
		"prefer":    []string{"tcp", "ftp", "http_put", "http_multipart"},
		"protocols": []map[string]any{
			{"id": "tcp", "port": TCPPort, "priority": 1},
			{"id": "ftp", "port": FTPPort, "priority": 2},
			{"id": "http_put", "path": "/api/fast/put", "priority": 3},
			{"id": "http_multipart", "path": "/api/wifi/upload_photo", "priority": 4},
		},
	}
}
