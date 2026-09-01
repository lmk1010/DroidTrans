package license

// 功能门控。
//
// 原则：基础能力一分不砍。
//
// 局域网互传、断点续传、配对、系统分享菜单这些全部免费且不设限 ——
// 这部分正面对上 LocalSend，阉割只会把人推走，何况它们本来就是
// 这个软件存在的理由。付费买的是「省事」，不是「能用」。
//
// 所以这里只列 Pro 功能。不在这张表里的一律放行，
// 将来加免费功能不需要动这里；加 Pro 功能只加一个常量。
type Feature string

const (
	// FeatureIncrementalSync 增量同步：只传上次之后新增的，已传过的自动跳过。
	// 这是最主要的付费理由 —— 每周往电脑倒照片的人，和手动挑一遍是天壤之别。
	FeatureIncrementalSync Feature = "incremental_sync"

	// FeatureAutoArchive 自动归档：按日期和来源设备分批整理。
	FeatureAutoArchive Feature = "auto_archive"

	// FeatureDedupe 跨批次去重：识别多次传输里重复的同一张照片。
	FeatureDedupe Feature = "dedupe"

	// FeatureUSBBulk USB 批量导入：插上线一次拉走整个相册。
	// 手动选文件传照片是免费的，这里买的是「不用一张张选」。
	FeatureUSBBulk Feature = "usb_bulk"
)

// proFeatures 是需要授权的功能集合。
// 判定只看「有没有有效授权」，不区分档位 —— 年付和终生买到的东西一样，
// 差别只在能用多久。将来真要按档位分，在这里加一层映射即可。
var proFeatures = map[Feature]bool{
	FeatureIncrementalSync: true,
	FeatureAutoArchive:     true,
	FeatureDedupe:          true,
	FeatureUSBBulk:         true,
}

// IsPro 判断一个功能是否需要付费。不认识的功能一律当免费放行 ——
// 宁可漏掉一个收费点，也不能因为拼错常量把免费功能锁上。
func IsPro(f Feature) bool {
	return proFeatures[f]
}

// Allowed 判断当前授权能否使用某功能。
func Allowed(lic *License, f Feature) bool {
	if !IsPro(f) {
		return true
	}
	// 过期、或者太久没回连过，Pro 功能都暂停。
	// 后者联一次网就自动恢复，不需要用户做任何事。
	return lic != nil && !lic.Expired() && !lic.Stale()
}

// Status 是给界面用的授权快照。
type Status struct {
	Active   bool     `json:"active"`
	Expired  bool     `json:"expired"`
	Stale    bool     `json:"stale"`
	Plan     string   `json:"plan,omitempty"`
	Email    string   `json:"email,omitempty"`
	Expires  string   `json:"expires,omitempty"`
	Summary  string   `json:"summary"`
	Features []string `json:"features"`
}

// Describe 把许可证整理成界面能直接用的形状。
func Describe(lic *License) Status {
	st := Status{Summary: lic.Summary(), Features: []string{}}
	if lic != nil {
		st.Plan = lic.Plan
		st.Email = lic.Email
		st.Expires = lic.Expires
		st.Expired = lic.Expired()
		st.Stale = lic.Stale()
		st.Active = !st.Expired && !st.Stale
	}
	for f := range proFeatures {
		if Allowed(lic, f) {
			st.Features = append(st.Features, string(f))
		}
	}
	return st
}
