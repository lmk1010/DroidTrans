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
	//
	// 【当前没有接门控】落盘路径 输出目录/设备ID/批次ID 是所有人都走的，
	// 免费版同样按设备和批次分好了 —— 也就是说这条现在不构成付费理由。
	// 付费弹层里已经把它撤下来了。要真拿它收费，得先想清楚是做点
	// 免费版没有的东西（比如按日期归并、重命名规则），还是从免费用户
	// 手里收回现有行为 —— 后者不要做。
	FeatureAutoArchive Feature = "auto_archive"

	// FeatureDedupe 跨批次去重：识别多次传输里重复的同一张照片。
	FeatureDedupe Feature = "dedupe"

	// FeatureUSBBulk USB 批量导入：插上线一次拉走整个相册。
	// 手动选文件传照片是免费的，这里买的是「不用一张张选」。
	FeatureUSBBulk Feature = "usb_bulk"

	// FeatureLargeFiles 传输超过免费额度的大文件。
	//
	// 免费额度是单个文件 4 GB。这个数字不是拍的：iPhone 4K60 视频约
	// 400 MB/分钟，4 GB 正好是 10 分钟 —— 日常的照片和短视频完全碰不到，
	// 碰到的是长录像、录屏、电影、整包备份这类，那才是愿意付钱的人。
	//
	// 2 GB 不行：那只有 5 分钟，随手拍段孩子的演出就超了，会砸在普通用户身上。
	//
	// 局域网传输按大小分档不是我们首创 —— Send Anywhere 免费版单次上限 2 GB。
	FeatureLargeFiles Feature = "large_files"

	// FeaturePhotosRescue 从 macOS「照片」图库里把原片救出来。
	//
	// 对着系统自带导出器的短板打：那个东西导几千张会崩，每两千张里
	// 大约丢一百张，报的错还看不懂；图库本身是黑盒，Catalina 之后
	// 用户在访达里按原始文件名根本找不到自己的照片。
	// 我们绕开它直接读 Photos.sqlite，按原始文件名摊成普通文件夹。
	//
	// 扫描（看看有多少张、有多少只在 iCloud）是免费的 —— 得先让用户
	// 确认这东西对他有用；真正往外导才要 Pro。
	FeaturePhotosRescue Feature = "photos_rescue"
)

// proFeatures 是需要授权的功能集合。
// 判定只看「有没有有效授权」，不区分档位 —— 年付和终生买到的东西一样，
// 差别只在能用多久。将来真要按档位分，在这里加一层映射即可。
var proFeatures = map[Feature]bool{
	FeatureIncrementalSync: true,
	FeatureAutoArchive:     true,
	FeatureDedupe:          true,
	FeatureUSBBulk:         true,
	FeaturePhotosRescue:    true,
	FeatureLargeFiles:      true,
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
