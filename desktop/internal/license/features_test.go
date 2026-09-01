package license

import (
	"testing"
	"time"
)

func TestFreeFeaturesAreNeverGated(t *testing.T) {
	// 没有授权时，不在 Pro 表里的东西必须照常可用。
	// 这条如果挂了，说明有人把基础功能锁进了付费墙。
	for _, f := range []Feature{"wifi_transfer", "resume", "pairing", "share_sheet", "text_clip"} {
		if IsPro(f) {
			t.Errorf("%s 是基础功能，不该需要付费", f)
		}
		if !Allowed(nil, f) {
			t.Errorf("%s 在未激活时也必须可用", f)
		}
	}
}

func TestProFeaturesNeedLicense(t *testing.T) {
	for f := range proFeatures {
		if Allowed(nil, f) {
			t.Errorf("%s 未激活时不该可用", f)
		}
	}
	valid := &License{V: 2, Product: "droidtrans-pro", Plan: "lifetime"}
	for f := range proFeatures {
		if !Allowed(valid, f) {
			t.Errorf("%s 在有效授权下应当可用", f)
		}
	}
}

func TestExpiredLosesProFeatures(t *testing.T) {
	expired := &License{V: 2, Product: "droidtrans-pro", Plan: "year", Expires: "2020-01-01T00:00:00Z"}
	if Allowed(expired, FeatureIncrementalSync) {
		t.Error("过期后不该还能用 Pro 功能")
	}
	// 但基础功能照旧
	if !Allowed(expired, "wifi_transfer") {
		t.Error("过期也不能影响基础功能")
	}
}

func TestDescribe(t *testing.T) {
	st := Describe(nil)
	if st.Active || len(st.Features) != 0 {
		t.Errorf("未激活时不该有可用的 Pro 功能：%+v", st)
	}
	st = Describe(&License{V: 2, Product: "droidtrans-pro", Plan: "lifetime", Email: "a@b.c"})
	if !st.Active || len(st.Features) != len(proFeatures) {
		t.Errorf("终生授权应当解锁全部 Pro 功能：%+v", st)
	}
}

func TestStaleSuspendsProUntilOnline(t *testing.T) {
	// 太久没回连：Pro 暂停，但基础功能一点不受影响
	stale := &License{
		V: 2, Product: "droidtrans-pro", Plan: "lifetime",
		Issued: time.Now().Add(-60 * 24 * time.Hour).Format(time.RFC3339),
	}
	if !stale.Stale() {
		t.Fatal("60 天没回连应当判为 stale")
	}
	if Allowed(stale, FeatureIncrementalSync) {
		t.Error("stale 时不该还能用 Pro 功能")
	}
	if !Allowed(stale, "wifi_transfer") {
		t.Error("stale 绝不能影响基础功能")
	}

	// 刚续签过的：一切正常
	fresh := &License{
		V: 2, Product: "droidtrans-pro", Plan: "lifetime",
		Issued: time.Now().Format(time.RFC3339),
	}
	if fresh.Stale() || fresh.NeedsRefresh() {
		t.Error("刚签发的不该需要刷新")
	}
	if !Allowed(fresh, FeatureIncrementalSync) {
		t.Error("刚签发的应当解锁 Pro")
	}

	// 到了该刷新的时候，但还没到宽限期尽头：功能照常可用
	due := &License{
		V: 2, Product: "droidtrans-pro", Plan: "lifetime",
		Issued: time.Now().Add(-20 * 24 * time.Hour).Format(time.RFC3339),
	}
	if !due.NeedsRefresh() {
		t.Error("20 天应当到了续签窗口")
	}
	if due.Stale() {
		t.Error("20 天还远没到宽限期尽头")
	}
	if !Allowed(due, FeatureIncrementalSync) {
		t.Error("等待续签期间 Pro 必须照常可用 —— 否则一断网就废了")
	}
}
