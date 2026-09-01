// Package license 校验授权，并把结果存在本机。
//
// 校验**完全离线**：客户端内置签发方的公钥，验签不需要任何网络。
// 只有第一次拿激活码换许可证时联一次网。
//
// 为什么非要这样：卓传是局域网工具，用户插着数据线往电脑倒照片时，
// 那台机器很可能压根没连外网。做成联网校验，等于把已经付过钱的人
// 关在门外 —— 这是这类软件最常见、也最伤用户的事故。
package license

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// License 是许可证里的内容，字段和授权服务签发时保持一致。
//
// 一个激活码通吃 macOS / Android / iOS，所以这里没有平台字段 ——
// 几块钱的东西让用户每端各买一次，光解释这件事就够呛。
type License struct {
	V       int    `json:"v"`
	Product string `json:"product"`
	// Plan 是档位：year / years3 / lifetime
	Plan string `json:"plan"`
	Code string `json:"code"`
	// DeviceID is absent on early v2 licenses; new licenses are bound to one install.
	DeviceID string `json:"deviceId,omitempty"`
	Email    string `json:"email"`
	Issued   string `json:"issued"`
	// Expires 是到期时间；终生买断为空字符串
	Expires    string `json:"expires"`
	MaxVersion string `json:"maxVersion"`
}

// 回连节奏。
//
// 这是防破解里唯一不伤害离线用户的一层：许可证里的签发时间是签过名的，
// 改不了；客户端据此知道自己多久没和服务器对过话。
//
//	到 RefreshAfter：有网就悄悄换一份新的，用户毫无感觉
//	到 StaleAfter  ：一直连不上（或者码已被吊销换不到新的）才降级
//
// 45 天的宽限足够覆盖任何正常的断网场景。真正会被卡住的，
// 是那些拿着一份许可证长期离线使用的复制品。
const (
	RefreshAfter = 14 * 24 * time.Hour
	StaleAfter   = 45 * 24 * time.Hour
)

var (
	ErrMalformed = errors.New("许可证格式不对")
	ErrSignature = errors.New("许可证签名无效")
	ErrProduct   = errors.New("许可证不是给这个产品的")
	ErrDevice    = errors.New("许可证不属于这台设备")
	ErrExpired   = errors.New("许可证已过期")
	ErrStale     = errors.New("许可证太久没有联网校验")
)

var (
	pubOnce sync.Once
	pubKey  ed25519.PublicKey
	pubErr  error
)

func publicKey() (ed25519.PublicKey, error) {
	pubOnce.Do(func() {
		block, _ := pem.Decode([]byte(publicKeyPEM))
		if block == nil {
			pubErr = errors.New("内置公钥无法解析")
			return
		}
		key, err := x509.ParsePKIXPublicKey(block.Bytes)
		if err != nil {
			pubErr = err
			return
		}
		k, ok := key.(ed25519.PublicKey)
		if !ok {
			pubErr = errors.New("内置公钥不是 Ed25519")
			return
		}
		pubKey = k
	})
	return pubKey, pubErr
}

// Verify 校验一份许可证。
//
// 串的形式是 base64url(JSON) + "." + base64url(签名)，
// 签名覆盖的是前半段那串字符本身，不是解码后的 JSON ——
// 两边必须一致，否则 Node 签出来的东西这里永远验不过。
func Verify(token string) (*License, error) {
	key, err := publicKey()
	if err != nil {
		return nil, err
	}

	dot := strings.IndexByte(token, '.')
	if dot <= 0 || dot == len(token)-1 {
		return nil, ErrMalformed
	}
	body, sigPart := token[:dot], token[dot+1:]

	sig, err := base64.RawURLEncoding.DecodeString(sigPart)
	if err != nil || len(sig) != ed25519.SignatureSize {
		return nil, ErrMalformed
	}
	if !ed25519.Verify(key, []byte(body), sig) {
		return nil, ErrSignature
	}

	raw, err := base64.RawURLEncoding.DecodeString(body)
	if err != nil {
		return nil, ErrMalformed
	}
	var lic License
	if err := json.Unmarshal(raw, &lic); err != nil {
		return nil, ErrMalformed
	}
	if lic.V != 2 || lic.Product != "droidtrans-pro" {
		return nil, ErrProduct
	}
	// 过期要排在「太久没回连」前面判断：
	// 过期是更根本的状态，联网续签也救不回来（服务端会签发同样已过期的到期时间），
	// 用户需要的是「续费」而不是「联网一次」。顺序反了会给出误导性的提示。
	if lic.Expired() {
		// 仍然把内容带回去，界面才能提示续期而不是一句冷冰冰的「无效」。
		return &lic, ErrExpired
	}
	if lic.Stale() {
		return &lic, ErrStale
	}
	return &lic, nil
}

// VerifyForDevice additionally enforces the signed device binding on new v2 licenses.
// Licenses issued before device binding was introduced have an empty DeviceID and remain valid.
func VerifyForDevice(token, expectedDeviceID string) (*License, error) {
	lic, err := Verify(token)
	if lic != nil && !deviceMatches(lic, expectedDeviceID) {
		return nil, ErrDevice
	}
	return lic, err
}

func deviceMatches(lic *License, expectedDeviceID string) bool {
	return lic == nil || lic.DeviceID == "" || expectedDeviceID == "" || lic.DeviceID == expectedDeviceID
}

// issuedAt 解析签发时间。解析不了就当作刚签发，
// 宁可放过也不要因为一个格式问题把付费用户挡在外面。
func (l *License) issuedAt() time.Time {
	if l == nil {
		return time.Now()
	}
	t, err := time.Parse(time.RFC3339, l.Issued)
	if err != nil {
		return time.Now()
	}
	return t
}

// NeedsRefresh 表示该找服务器换一份新的了。有网时后台悄悄做，失败也不影响使用。
func (l *License) NeedsRefresh() bool {
	return l != nil && time.Since(l.issuedAt()) > RefreshAfter
}

// Stale 表示太久没有成功回连过，Pro 功能应当暂停，
// 直到联网续签一次。这不是「过期」，联一次网就恢复。
func (l *License) Stale() bool {
	return l != nil && time.Since(l.issuedAt()) > StaleAfter
}

// Expired 判断是否过期。终生买断（Expires 为空）永不过期。
//
// 时间取自本机时钟，用户改系统时间可以绕过 —— 这是离线校验的固有代价。
// 要堵死就得联网核对时间，那会把没有外网的正当用户一起挡住，
// 对这个价位的产品不划算。
func (l *License) Expired() bool {
	if l == nil || l.Expires == "" {
		return false
	}
	t, err := time.Parse(time.RFC3339, l.Expires)
	if err != nil {
		return false
	}
	return time.Now().After(t)
}

// DefaultDir 是许可证的存放位置。
//
// 必须是固定的应用配置目录，**不能**跟着「保存到」那个输出目录走：
// 输出目录是用户随时会改的设置，把授权放在那里，
// 用户换一次保存位置就等于把自己买的东西弄丢了。
//
// macOS 落在 ~/Library/Application Support/DroidTrans，
// Linux 落在 ~/.config/DroidTrans，Windows 落在 %AppData%\DroidTrans。
func DefaultDir() string {
	if d, err := os.UserConfigDir(); err == nil && d != "" {
		return filepath.Join(d, "DroidTrans")
	}
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		return ".droidtrans"
	}
	return filepath.Join(home, ".droidtrans")
}

// Path 是许可证文件的完整路径。传空串表示用默认位置。
func Path(dir string) string {
	if dir == "" {
		dir = DefaultDir()
	}
	return filepath.Join(dir, "license.txt")
}

// DeviceID 返回这个桌面安装稳定的随机 ID，用于授权服务按设备计算名额。
// 它不包含硬件信息，也不会跟着许可证一起被删除；同一台电脑重新激活
// 仍然认作同一个设备。
func DeviceID(dir string) (string, error) {
	if dir == "" {
		dir = DefaultDir()
	}
	path := filepath.Join(dir, "license-device-id")
	if b, err := os.ReadFile(path); err == nil {
		id := strings.TrimSpace(string(b))
		if len(id) == 32 {
			if _, err := hex.DecodeString(id); err == nil {
				return id, nil
			}
		}
	}

	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	raw := make([]byte, 16)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	id := hex.EncodeToString(raw)
	if err := os.WriteFile(path, []byte(id), 0o600); err != nil {
		return "", err
	}
	return id, nil
}

// Save 把许可证写到本机。先验一遍，不让无效的东西落盘 ——
// 否则下次启动会拿着一份垃圾反复报错。
// 过期的许可证照存，界面才能提示续期。
func Save(dir, token string) (*License, error) {
	deviceID, err := DeviceID(dir)
	if err != nil {
		return nil, err
	}
	lic, err := VerifyForDevice(token, deviceID)
	if err != nil && !errors.Is(err, ErrExpired) && !errors.Is(err, ErrStale) {
		return nil, err
	}
	verr := err
	if dir == "" {
		dir = DefaultDir()
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	// 0600：许可证里有购买者邮箱，同机器上的其他账户不该读到
	if err := os.WriteFile(Path(dir), []byte(strings.TrimSpace(token)), 0o600); err != nil {
		return nil, err
	}
	return lic, verr
}

// Load 读取本机已保存的许可证。
//
// 过期的也会返回（连同 ErrExpired），让界面能提示续期；
// 签名不对或文件损坏则返回 nil，按未激活处理。
func Load(dir string) (*License, error) {
	b, err := os.ReadFile(Path(dir))
	if err != nil {
		return nil, nil
	}
	deviceID, err := DeviceID(dir)
	if err != nil {
		return nil, err
	}
	lic, err := VerifyForDevice(strings.TrimSpace(string(b)), deviceID)
	// 过期和「太久没回连」都要把内容带回去，界面才能给出对应的提示；
	// 只有签名不对或文件损坏才当作未激活。
	if err != nil && err != ErrExpired && err != ErrStale {
		return nil, nil
	}
	return lic, err
}

// Remove 清掉本机的许可证，用于「注销」。
func Remove(dir string) error {
	err := os.Remove(Path(dir))
	if err != nil && !os.IsNotExist(err) {
		return err
	}
	return nil
}

// Summary 给界面用的一句话描述。
func (l *License) Summary() string {
	if l == nil {
		return "未激活"
	}
	if l.Expired() {
		if t, err := time.Parse(time.RFC3339, l.Expires); err == nil {
			return fmt.Sprintf("已过期 · %s 到期", t.Format("2006-01-02"))
		}
		return "已过期"
	}
	if l.Stale() {
		return "需要联网校验一次"
	}
	if l.Expires == "" {
		return fmt.Sprintf("已激活 · 终生 · %s", l.Email)
	}
	t, err := time.Parse(time.RFC3339, l.Expires)
	if err != nil {
		return fmt.Sprintf("已激活 · %s", l.Email)
	}
	if l.Expired() {
		return fmt.Sprintf("已过期 · %s 到期", t.Format("2006-01-02"))
	}
	if l.Stale() {
		return "需要联网校验一次"
	}
	return fmt.Sprintf("已激活 · %s 到期 · %s", t.Format("2006-01-02"), l.Email)
}
