package adb

import (
	"bytes"
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

var versionRe = regexp.MustCompile(`Version\s+(\d+)\.(\d+)\.(\d+)`)

type Device struct {
	Serial string `json:"serial"`
	State  string `json:"state"`
}

type Client struct {
	mu     sync.RWMutex
	bin    string
	serial string
	burst  bool
}

func ResolveBin() string {
	home, _ := os.UserHomeDir()
	candidates := []string{
		filepath.Join(home, "Library/Android/sdk/platform-tools/adb"),
		filepath.Join(home, "Android/Sdk/platform-tools/adb"),
		"/opt/homebrew/bin/adb",
		"/usr/local/bin/adb",
	}
	if p, err := exec.LookPath("adb"); err == nil {
		candidates = append(candidates, p)
	}
	best, bestVer := "", [3]int{}
	seen := map[string]bool{}
	for _, p := range candidates {
		if p == "" || seen[p] {
			continue
		}
		seen[p] = true
		if st, err := os.Stat(p); err != nil || st.IsDir() {
			continue
		}
		out, err := exec.Command(p, "version").CombinedOutput()
		if err != nil {
			if best == "" {
				best = p
			}
			continue
		}
		ver := parseVersion(string(out))
		if verGte(ver, bestVer) {
			best, bestVer = p, ver
		}
	}
	if best == "" {
		return "adb"
	}
	return best
}

func parseVersion(s string) [3]int {
	m := versionRe.FindStringSubmatch(s)
	if m == nil {
		return [3]int{}
	}
	a, _ := strconv.Atoi(m[1])
	b, _ := strconv.Atoi(m[2])
	c, _ := strconv.Atoi(m[3])
	return [3]int{a, b, c}
}

func verGte(a, b [3]int) bool {
	for i := 0; i < 3; i++ {
		if a[i] != b[i] {
			return a[i] > b[i]
		}
	}
	return true
}

func New() *Client {
	return &Client{bin: ResolveBin(), burst: true}
}

func (c *Client) Bin() string { return c.bin }

func (c *Client) SetSerial(s string) {
	c.mu.Lock()
	c.serial = s
	c.mu.Unlock()
}

func (c *Client) Serial() string {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.serial
}

func (c *Client) SetBurst(on bool) {
	c.mu.Lock()
	c.burst = on
	c.mu.Unlock()
}

func (c *Client) Burst() bool {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.burst
}

func (c *Client) prefix() []string {
	c.mu.RLock()
	serial := c.serial
	c.mu.RUnlock()
	if serial == "" {
		return nil
	}
	return []string{"-s", serial}
}

func (c *Client) env() []string {
	env := os.Environ()
	c.mu.RLock()
	burst := c.burst
	c.mu.RUnlock()
	if burst {
		env = append(env, "ADB_DELAYED_ACK=1")
	}
	return env
}

func (c *Client) run(timeout time.Duration, args ...string) (string, string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	all := append(c.prefix(), args...)
	cmd := exec.CommandContext(ctx, c.bin, all...)
	cmd.Env = c.env()
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	err := cmd.Run()
	return stdout.String(), stderr.String(), err
}

func (c *Client) RunRaw(timeout time.Duration, args ...string) (string, string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, c.bin, args...)
	cmd.Env = c.env()
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	err := cmd.Run()
	return stdout.String(), stderr.String(), err
}

func (c *Client) Devices() (ready []Device, unauthorized []Device, err error) {
	out, stderr, e := c.RunRaw(8*time.Second, "devices")
	if e != nil {
		return nil, nil, fmt.Errorf("%v: %s", e, stderr)
	}
	lines := strings.Split(strings.TrimSpace(out), "\n")
	for i, line := range lines {
		if i == 0 {
			continue
		}
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		parts := strings.Fields(line)
		if len(parts) < 2 {
			continue
		}
		d := Device{Serial: parts[0], State: parts[1]}
		switch parts[1] {
		case "device":
			ready = append(ready, d)
		case "unauthorized":
			unauthorized = append(unauthorized, d)
		}
	}
	return ready, unauthorized, nil
}

func (c *Client) Shell(timeout time.Duration, script string) (string, error) {
	out, stderr, err := c.run(timeout, "shell", script)
	if err != nil {
		return out, fmt.Errorf("%v: %s", err, stderr)
	}
	return out, nil
}

func (c *Client) DirExists(path string) bool {
	out, err := c.Shell(5*time.Second, "test -d "+shellQuote(path)+" && echo EXISTS")
	return err == nil && strings.Contains(out, "EXISTS")
}

func (c *Client) Pull(remote, local string, timeout time.Duration) error {
	if err := os.MkdirAll(filepath.Dir(local), 0o755); err != nil {
		return err
	}
	_, stderr, err := c.run(timeout, "pull", remote, local)
	if err != nil {
		return fmt.Errorf("%v: %s", err, stderr)
	}
	return nil
}

func (c *Client) ExecOut(timeout time.Duration, args ...string) ([]byte, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	all := append(append(c.prefix(), "exec-out"), args...)
	cmd := exec.CommandContext(ctx, c.bin, all...)
	cmd.Env = c.env()
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		return nil, fmt.Errorf("%v: %s", err, stderr.String())
	}
	return stdout.Bytes(), nil
}

func (c *Client) Prop(name string) string {
	out, err := c.Shell(3*time.Second, "getprop "+name)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(out)
}

func shellQuote(s string) string {
	return "'" + strings.ReplaceAll(s, "'", `'"'"'`) + "'"
}

func ShellQuote(s string) string { return shellQuote(s) }
