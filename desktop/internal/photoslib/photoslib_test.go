package photoslib

// 用一个合成图库来测。
//
// 不拿开发机上那个真图库当测试依赖：它的内容随时会变，而且大部分人的
// 图库开了 iCloud「优化储存空间」，本地一张原片都没有，测不出东西来。
// 这里把 Photos.sqlite 的关键结构照着真库复刻一份，行为就能稳定复现。

import (
	"context"
	"database/sql"
	"os"
	"path/filepath"
	"testing"
	"time"

	_ "modernc.org/sqlite"
)

// buildLibrary 造一个最小可用的图库。
func buildLibrary(t *testing.T) string {
	t.Helper()
	lib := filepath.Join(t.TempDir(), "Test.photoslibrary")
	if err := os.MkdirAll(filepath.Join(lib, "database"), 0o755); err != nil {
		t.Fatal(err)
	}

	db, err := sql.Open("sqlite", filepath.Join(lib, "database", "Photos.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	mustExec(t, db, `CREATE TABLE ZASSET (
		Z_PK INTEGER PRIMARY KEY, ZUUID TEXT, ZDIRECTORY TEXT, ZFILENAME TEXT,
		ZDATECREATED REAL, ZKIND INTEGER, ZKINDSUBTYPE INTEGER, ZTRASHEDSTATE INTEGER)`)
	mustExec(t, db, `CREATE TABLE ZADDITIONALASSETATTRIBUTES (
		Z_PK INTEGER PRIMARY KEY, ZASSET INTEGER,
		ZORIGINALFILENAME TEXT, ZORIGINALFILESIZE INTEGER)`)

	// 2026-09-02 的 Core Data 时间戳
	when := float64(time.Date(2026, 9, 2, 10, 0, 0, 0, time.UTC).Unix() - coreDataEpoch)

	add := func(pk int, dir, fname, orig string, kind, subtype, trashed int, onDisk bool, live bool) {
		mustExec(t, db, `INSERT INTO ZASSET VALUES (?,?,?,?,?,?,?,?)`,
			pk, "uuid-"+fname, dir, fname, when, kind, subtype, trashed)
		mustExec(t, db, `INSERT INTO ZADDITIONALASSETATTRIBUTES VALUES (?,?,?,?)`,
			pk, pk, orig, 100)
		if onDisk {
			d := filepath.Join(lib, "originals", dir)
			if err := os.MkdirAll(d, 0o755); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(filepath.Join(d, fname), []byte("photo-"+fname), 0o644); err != nil {
				t.Fatal(err)
			}
			if live {
				stem := fname[:len(fname)-len(filepath.Ext(fname))]
				if err := os.WriteFile(filepath.Join(d, stem+".mov"), []byte("motion"), 0o644); err != nil {
					t.Fatal(err)
				}
			}
		}
	}

	add(1, "0", "aaa.HEIC", "IMG_0001.HEIC", 0, 0, 0, true, false)  // 普通照片，本地有
	add(2, "1", "bbb.HEIC", "IMG_0002.HEIC", 0, 2, 0, true, true)   // 实况照片，本地有
	add(3, "2", "ccc.HEIC", "IMG_0003.HEIC", 0, 0, 0, false, false) // 只在 iCloud
	add(4, "3", "ddd.MOV", "IMG_0004.MOV", 1, 0, 0, true, false)    // 视频，本地有
	add(5, "4", "eee.HEIC", "IMG_0005.HEIC", 0, 0, 1, true, false)  // 在最近删除里
	add(6, "5", "fff.HEIC", "IMG_0001.HEIC", 0, 0, 0, true, false)  // 和 #1 原始文件名撞名

	return lib
}

func mustExec(t *testing.T, db *sql.DB, q string, args ...any) {
	t.Helper()
	if _, err := db.Exec(q, args...); err != nil {
		t.Fatalf("%s: %v", q, err)
	}
}

func TestScanSeparatesLocalFromCloud(t *testing.T) {
	lib := buildLibrary(t)
	scan, err := ScanLibrary(lib)
	if err != nil {
		t.Fatal(err)
	}
	// 6 条记录：1 张在回收站，1 张只在 iCloud，剩下 4 张能导
	if len(scan.Assets) != 4 {
		t.Errorf("可导出的应该是 4 张，拿到 %d", len(scan.Assets))
	}
	// 这个数字必须报出来。静默产出几张就说导完了，正是我们要取代的烂体验。
	if scan.InCloudOnly != 1 {
		t.Errorf("只在 iCloud 的应该是 1 张，拿到 %d", scan.InCloudOnly)
	}
	if scan.Trashed != 1 {
		t.Errorf("回收站里的应该是 1 张，拿到 %d", scan.Trashed)
	}
}

func TestUsesOriginalFilenameNotUUID(t *testing.T) {
	lib := buildLibrary(t)
	scan, _ := ScanLibrary(lib)
	for _, a := range scan.Assets {
		if a.OriginalName == filepath.Base(a.Path) {
			t.Errorf("导出用的还是图库内部名 %q —— 用户按这个名字找不到自己的照片", a.OriginalName)
		}
	}
}

func TestExportLayoutAndLivePhoto(t *testing.T) {
	lib := buildLibrary(t)
	scan, _ := ScanLibrary(lib)
	out := t.TempDir()

	var e Exporter
	e.Run(context.Background(), scan.Assets, out, Options{})

	p := e.Progress()
	if !p.Finished || p.Failed != 0 {
		t.Fatalf("导出没干净结束: %+v", p)
	}

	dir := filepath.Join(out, "2026", "2026-09")
	// 静态图
	if _, err := os.Stat(filepath.Join(dir, "IMG_0002.HEIC")); err != nil {
		t.Fatal("实况照片的静态图没导出来")
	}
	// 动态部分：必须和静态图同名，只换扩展名
	if _, err := os.Stat(filepath.Join(dir, "IMG_0002.MOV")); err != nil {
		t.Fatal("实况照片的动态部分没跟出来 —— 那 3 秒就永久丢了")
	}
}

func TestDuplicateNamesDoNotOverwrite(t *testing.T) {
	lib := buildLibrary(t)
	scan, _ := ScanLibrary(lib)
	out := t.TempDir()

	var e Exporter
	e.Run(context.Background(), scan.Assets, out, Options{})

	dir := filepath.Join(out, "2026", "2026-09")
	// 两张原始文件名都叫 IMG_0001.HEIC，内容不同，一张都不能少
	first, err := os.ReadFile(filepath.Join(dir, "IMG_0001.HEIC"))
	if err != nil {
		t.Fatal(err)
	}
	second, err := os.ReadFile(filepath.Join(dir, "IMG_0001 (1).HEIC"))
	if err != nil {
		t.Fatal("撞名的第二张被吃掉了 —— 这是最不可接受的一类 bug")
	}
	if string(first) == string(second) {
		t.Fatal("两个文件内容一样，说明其中一张被覆盖了")
	}
}

// 导出会被反复执行（又拍了几百张再导一次）。
// 每次都把几十 GB 重抄一遍，用户第二次就不会再用了。
func TestSecondRunSkipsWhatIsAlreadyThere(t *testing.T) {
	lib := buildLibrary(t)
	scan, _ := ScanLibrary(lib)
	out := t.TempDir()

	var e1 Exporter
	e1.Run(context.Background(), scan.Assets, out, Options{})
	if e1.Progress().Bytes == 0 {
		t.Fatal("第一次就没写出任何字节")
	}

	var e2 Exporter
	e2.Run(context.Background(), scan.Assets, out, Options{})
	if b := e2.Progress().Bytes; b != 0 {
		t.Errorf("第二次不该再抄一遍，却又写了 %d 字节", b)
	}
	if e2.Progress().Failed != 0 {
		t.Error("第二次出现了失败")
	}
}

// 中途取消不能在目标位置留下半张照片 ——
// 用户会以为它是好的，然后把手机上的原件删了。
func TestCancelLeavesNoHalfFiles(t *testing.T) {
	lib := buildLibrary(t)
	scan, _ := ScanLibrary(lib)
	out := t.TempDir()

	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	var e Exporter
	e.Run(ctx, scan.Assets, out, Options{})

	var partials int
	_ = filepath.Walk(out, func(p string, info os.FileInfo, err error) error {
		if err == nil && !info.IsDir() && filepath.Ext(p) == ".dtpart" {
			partials++
		}
		return nil
	})
	if partials != 0 {
		t.Errorf("留下了 %d 个没写完的临时文件", partials)
	}
}

// 图库结构和预期不符时要给一句人话，而不是把 SQL 错误直接抛给用户。
func TestBrokenLibraryGivesReadableError(t *testing.T) {
	lib := filepath.Join(t.TempDir(), "Broken.photoslibrary")
	_ = os.MkdirAll(filepath.Join(lib, "database"), 0o755)
	db, _ := sql.Open("sqlite", filepath.Join(lib, "database", "Photos.sqlite"))
	mustExec(t, db, `CREATE TABLE SOMETHING_ELSE (x INTEGER)`)
	db.Close()

	if _, err := ScanLibrary(lib); err == nil {
		t.Fatal("结构不对却没报错")
	}
}

func TestMissingLibrary(t *testing.T) {
	if _, err := ScanLibrary(filepath.Join(t.TempDir(), "nope.photoslibrary")); err == nil {
		t.Fatal("图库不存在却没报错")
	}
}

// 扩展名统一成小写。.JPG 和 .jpg 混着，跨平台同步和查重工具
// 会把同一张照片当成两个文件。
func TestLowercaseExtension(t *testing.T) {
	lib := buildLibrary(t)
	scan, _ := ScanLibrary(lib)
	out := t.TempDir()

	var e Exporter
	e.Run(context.Background(), scan.Assets, out, Options{LowercaseExt: true})

	dir := filepath.Join(out, "2026", "2026-09")
	if _, err := os.Stat(filepath.Join(dir, "IMG_0002.heic")); err != nil {
		t.Error("扩展名没转成小写")
	}
	// 实况照片的动态部分要跟着一起小写，否则一对文件一个大写一个小写
	if _, err := os.Stat(filepath.Join(dir, "IMG_0002.mov")); err != nil {
		t.Error("实况照片的动态部分扩展名没跟着小写")
	}
	// 主名不能动 —— 那是用户/相机起的名字，改了就对不上号
	if _, err := os.Stat(filepath.Join(dir, "IMG_0004.mov")); err != nil {
		t.Error("视频的主名被改了或扩展名没小写")
	}
}

// 图库发现。只认默认位置的话，把图库放在外置硬盘上的用户
// （也就是照片最多、最该付费的那批）会直接被告知「没找到照片图库」。
func TestDiscoverRecognisesRealLibrariesOnly(t *testing.T) {
	if !isLibrary(buildLibrary(t)) {
		t.Error("造出来的合成图库没被认出来")
	}

	// 只有后缀、里面什么都没有 —— 不能算数，否则扫描时才失败，
	// 用户会以为是我们的 bug
	empty := filepath.Join(t.TempDir(), "Fake.photoslibrary")
	if err := os.MkdirAll(empty, 0o755); err != nil {
		t.Fatal(err)
	}
	if isLibrary(empty) {
		t.Error("空壳目录被当成了图库")
	}

	// 后缀不对
	if isLibrary(t.TempDir()) {
		t.Error("普通目录被当成了图库")
	}
}

func TestOriginalsSizeCountsOnlyOriginals(t *testing.T) {
	lib := buildLibrary(t)
	// 往缓存目录里塞点东西，不该被算进去 ——
	// 把缩略图和渲染缓存算进来，用户对「能导出多少」的预期就是错的
	junk := filepath.Join(lib, "resources", "derivatives")
	if err := os.MkdirAll(junk, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(junk, "big.bin"), make([]byte, 4096), 0o644); err != nil {
		t.Fatal(err)
	}
	if n := originalsSize(lib); n == 0 || n > 4096 {
		t.Errorf("originals 体积算错了：%d", n)
	}
}

// 免费额度：一趟导 200 项，再点一次要接着往下导。
//
// 如果额度数的是「处理过的个数」而不是「真正抄过去的个数」，
// 第二次点导出时前 200 项全被跳过、一个新的都不出来 ——
// 用户看到的就是「点了没反应」，比直接挡住还糟。
func TestFreeQuotaAdvancesAcrossRuns(t *testing.T) {
	lib := buildLibrary(t)
	scan, _ := ScanLibrary(lib)
	out := t.TempDir()
	if len(scan.Assets) < 4 {
		t.Fatalf("样本不够：%d", len(scan.Assets))
	}

	// 额度设成 2，一共 4 项，需要两趟才导完
	var e1 Exporter
	e1.Run(context.Background(), scan.Assets, out, Options{MaxItems: 2})
	p1 := e1.Progress()
	if p1.Copied != 2 || !p1.LimitHit {
		t.Fatalf("第一趟应该抄 2 个并触到额度，实际 copied=%d hit=%v", p1.Copied, p1.LimitHit)
	}

	var e2 Exporter
	e2.Run(context.Background(), scan.Assets, out, Options{MaxItems: 2})
	p2 := e2.Progress()
	if p2.Copied == 0 {
		t.Fatal("第二趟一个都没导出来 —— 用户会以为点了没反应")
	}

	// 两趟之后 4 项应该都在
	var got int
	_ = filepath.Walk(out, func(p string, info os.FileInfo, err error) error {
		if err == nil && !info.IsDir() && filepath.Ext(p) != ".dtpart" {
			got++
		}
		return nil
	})
	if got < 4 {
		t.Errorf("两趟之后只有 %d 个文件，应该至少 4 个", got)
	}
}

// 额度为 0 就是不限。
func TestZeroQuotaMeansUnlimited(t *testing.T) {
	lib := buildLibrary(t)
	scan, _ := ScanLibrary(lib)
	out := t.TempDir()
	var e Exporter
	e.Run(context.Background(), scan.Assets, out, Options{MaxItems: 0})
	p := e.Progress()
	if p.LimitHit {
		t.Error("不限额度却报了触顶")
	}
	if p.Done != len(scan.Assets) {
		t.Errorf("应该全部处理完，实际 %d/%d", p.Done, len(scan.Assets))
	}
}
