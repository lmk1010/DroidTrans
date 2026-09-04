package fast

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"os"
	"testing"
)

// iOS 端生成的 ATF3 帧，用服务端真正在用的 readHeader 解析回来。
//
// 协议现在有三份实现（Go 服务端、Java 安卓端、Swift iOS 端），
// 各自的单测只能证明「自己和自己一致」。字段错位、大小端写反、
// 长度按字符而不是字节算 —— 这些在真机上都表现为「传到一半失败」，
// 是最难查的一类问题，所以这里让服务端亲自读一遍客户端发出的字节。
//
// 向量由 ios/Tools/atf3vec/run.sh 生成。改了线格式就重新跑一次。
type atf3Vector struct {
	Desc  string `json:"desc"`
	Name  string `json:"name"`
	Size  int64  `json:"size"`
	Token string `json:"token"`
	Hex   string `json:"hex"`
}

func TestATF3VectorsFromSwift(t *testing.T) {
	raw, err := os.ReadFile("testdata/atf3_vectors.json")
	if err != nil {
		t.Fatalf("读向量失败（先跑 ios/Tools/atf3vec/run.sh）: %v", err)
	}
	var vectors []atf3Vector
	if err := json.Unmarshal(raw, &vectors); err != nil {
		t.Fatalf("向量解析失败: %v", err)
	}
	if len(vectors) == 0 {
		t.Fatal("向量是空的")
	}

	for _, v := range vectors {
		t.Run(v.Desc, func(t *testing.T) {
			b, err := hex.DecodeString(v.Hex)
			if err != nil {
				t.Fatalf("hex 解不开: %v", err)
			}
			// 帧后面紧跟文件内容，这里只给帧 —— readHeader 不该多读一个字节，
			// 多读了就会把文件的头几个字节吃掉，内容静默损坏
			r := bytes.NewReader(b)
			name, size, token, err := readHeader(r)
			if err != nil {
				t.Fatalf("readHeader 失败: %v", err)
			}
			if name != v.Name {
				t.Errorf("文件名不一致\n  Swift 发的: %q\n  Go  读到的: %q", v.Name, name)
			}
			if size != v.Size {
				t.Errorf("大小不一致: Swift %d, Go %d", v.Size, size)
			}
			if token != v.Token {
				t.Errorf("令牌不一致: Swift %q, Go %q", v.Token, token)
			}
			if n := r.Len(); n != 0 {
				t.Errorf("帧尾还剩 %d 字节没读完 —— 文件内容会被截掉这么多", n)
			}
		})
	}
}
