package app

// 配对记录不能越攒越多。
//
// 一台手机重装、换网、重连，一天能配十几次。原来每次都往表里追加一条，
// 界面上「已配对 N 台」显示的其实是「配对过 N 次」—— 开发机上跑到了 144，
// 而实际只有一部 iPhone。用户看到这个数字只会觉得有人偷偷连了他的电脑。

import (
	"path/filepath"
	"testing"
	"time"
)

func TestRepairingReplacesInsteadOfPilingUp(t *testing.T) {
	p := LoadPairing(filepath.Join(t.TempDir(), "pair.json"))
	code := p.Code

	var last string
	for i := 0; i < 5; i++ {
		tok, ok := p.Pair(code, "iphone-1", "iPhone 16 Pro")
		if !ok {
			t.Fatal("配对失败")
		}
		last = tok
	}

	_, _, peers := p.Snapshot()
	if len(peers) != 1 {
		t.Fatalf("同一台手机配了 5 次，记录却有 %d 条", len(peers))
	}
	if !p.Valid(last) {
		t.Error("最后那次配对拿到的令牌应当可用")
	}
}

// 重新配对之后，旧令牌必须立刻失效 —— 用户「重新配对」的动机
// 往往就是怀疑旧的泄漏了。
func TestOldTokenStopsWorkingAfterRepair(t *testing.T) {
	p := LoadPairing(filepath.Join(t.TempDir(), "pair.json"))
	old, _ := p.Pair(p.Code, "iphone-1", "iPhone")
	if !p.Valid(old) {
		t.Fatal("刚换到的令牌就不能用")
	}
	_, _ = p.Pair(p.Code, "iphone-1", "iPhone")
	if p.Valid(old) {
		t.Error("重新配对之后旧令牌还能用")
	}
}

// 不同设备互不影响。
func TestDifferentDevicesCoexist(t *testing.T) {
	p := LoadPairing(filepath.Join(t.TempDir(), "pair.json"))
	a, _ := p.Pair(p.Code, "iphone-1", "iPhone")
	b, _ := p.Pair(p.Code, "android-1", "小米")
	if !p.Valid(a) || !p.Valid(b) {
		t.Fatal("两台不同的设备应该都还能用")
	}
	if _, _, peers := p.Snapshot(); len(peers) != 2 {
		t.Fatalf("应该有 2 条记录，实际 %d", len(peers))
	}
}

// 已经攒了一堆重复的机器，启动时要收拾干净，并且留下的必须是最新那条 ——
// 手机手上拿着的就是它，收拾完不该被迫重新配对。
func TestLoadCleansUpExistingDuplicates(t *testing.T) {
	path := filepath.Join(t.TempDir(), "pair.json")
	p := LoadPairing(path)

	base := time.Now().Add(-time.Hour)
	var newest string
	for i := 0; i < 10; i++ {
		tok := newToken()
		when := base.Add(time.Duration(i) * time.Minute).Format(time.RFC3339)
		p.Tokens[tok] = &Peer{Name: "iPhone", DeviceID: "iphone-1", PairedAt: when, LastSeen: when}
		newest = tok
	}
	p.save()

	again := LoadPairing(path)
	if _, _, peers := again.Snapshot(); len(peers) != 1 {
		t.Fatalf("启动时应该收拾成 1 条，实际 %d 条", len(peers))
	}
	if !again.Valid(newest) {
		t.Error("留下的不是最新那条，手机会被迫重新配对")
	}
}
