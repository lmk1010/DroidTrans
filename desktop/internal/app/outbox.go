package app

import (
	"crypto/sha1"
	"encoding/hex"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// Outbox 是「电脑发给手机」的待取队列。
//
// 这里只登记路径，不复制文件：手机来取的时候直接从原位置流式读。
// 几十 GB 的视频也不会先在临时目录里躺一份。
type Outbox struct {
	mu    sync.RWMutex
	items map[string]*OutItem
	seq   int
}

type OutItem struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Path    string `json:"path"`
	Size    int64  `json:"size"`
	Rel     string `json:"rel"` // 文件夹内的相对路径，手机端照此重建目录
	AddedAt string `json:"added_at"`
	Taken   int    `json:"taken"` // 被领取过几次
	order   int
}

func NewOutbox() *Outbox {
	return &Outbox{items: map[string]*OutItem{}}
}

func outID(path string) string {
	sum := sha1.Sum([]byte(path))
	return hex.EncodeToString(sum[:8])
}

// Add 登记一个文件或整个目录（目录会展开成多条，保留相对路径）。
func (o *Outbox) Add(path string) (int, error) {
	abs, err := filepath.Abs(path)
	if err != nil {
		return 0, err
	}
	st, err := os.Stat(abs)
	if err != nil {
		return 0, err
	}
	if !st.IsDir() {
		o.add(abs, st.Size(), filepath.Base(abs))
		return 1, nil
	}
	root := filepath.Dir(abs)
	n := 0
	err = filepath.Walk(abs, func(p string, info os.FileInfo, err error) error {
		if err != nil || info == nil || info.IsDir() {
			return nil
		}
		if strings.HasPrefix(info.Name(), ".") {
			return nil
		}
		rel, rerr := filepath.Rel(root, p)
		if rerr != nil {
			rel = info.Name()
		}
		o.add(p, info.Size(), filepath.ToSlash(rel))
		n++
		return nil
	})
	return n, err
}

func (o *Outbox) add(path string, size int64, rel string) {
	o.mu.Lock()
	defer o.mu.Unlock()
	id := outID(path)
	if old, ok := o.items[id]; ok {
		old.Size = size
		return
	}
	o.seq++
	o.items[id] = &OutItem{
		ID:      id,
		Name:    filepath.Base(path),
		Path:    path,
		Size:    size,
		Rel:     rel,
		AddedAt: time.Now().Format(time.RFC3339),
		order:   o.seq,
	}
}

func (o *Outbox) List() []*OutItem {
	o.mu.RLock()
	defer o.mu.RUnlock()
	out := make([]*OutItem, 0, len(o.items))
	for _, it := range o.items {
		clone := *it
		out = append(out, &clone)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].order < out[j].order })
	return out
}

func (o *Outbox) Get(id string) (*OutItem, bool) {
	o.mu.RLock()
	defer o.mu.RUnlock()
	it, ok := o.items[id]
	if !ok {
		return nil, false
	}
	clone := *it
	return &clone, true
}

func (o *Outbox) MarkTaken(id string) {
	o.mu.Lock()
	defer o.mu.Unlock()
	if it, ok := o.items[id]; ok {
		it.Taken++
	}
}

func (o *Outbox) Remove(id string) {
	o.mu.Lock()
	defer o.mu.Unlock()
	delete(o.items, id)
}

func (o *Outbox) Clear() {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.items = map[string]*OutItem{}
}

func (o *Outbox) Stats() (count int, size int64) {
	o.mu.RLock()
	defer o.mu.RUnlock()
	for _, it := range o.items {
		count++
		size += it.Size
	}
	return count, size
}

// ---- HTTP ----

func (a *App) outboxList(w http.ResponseWriter, r *http.Request) {
	items := a.Out.List()
	count, size := a.Out.Stats()
	// 文件可能在登记之后被删掉/移走，这里顺手标出来
	for _, it := range items {
		if st, err := os.Stat(it.Path); err != nil || st.Size() != it.Size {
			it.Size = -1
		}
	}
	writeJSON(w, 200, map[string]any{
		"success": true, "items": items, "count": count,
		"total_size": size, "total_size_h": humanBytes(size),
	})
}

func (a *App) outboxAdd(w http.ResponseWriter, r *http.Request) {
	body := readJSON(r)
	var paths []string
	if s, ok := body["path"].(string); ok && s != "" {
		paths = append(paths, s)
	}
	if arr, ok := body["paths"].([]any); ok {
		for _, v := range arr {
			if s, ok := v.(string); ok && s != "" {
				paths = append(paths, s)
			}
		}
	}
	if len(paths) == 0 {
		writeJSON(w, 400, map[string]any{"success": false, "error": "缺少路径"})
		return
	}
	added := 0
	for _, p := range paths {
		n, err := a.Out.Add(p)
		if err != nil {
			writeJSON(w, 400, map[string]any{"success": false, "error": err.Error()})
			return
		}
		added += n
	}
	count, size := a.Out.Stats()
	writeJSON(w, 200, map[string]any{"success": true, "added": added, "count": count, "total_size": size})
}

// outboxPick 唤起系统的文件选择面板（macOS）。
func (a *App) outboxPick(w http.ResponseWriter, r *http.Request) {
	if a.OnPickFiles == nil {
		writeJSON(w, 400, map[string]any{"success": false, "error": "当前平台不支持选择面板"})
		return
	}
	a.OnPickFiles()
	writeJSON(w, 200, map[string]any{"success": true})
}

func (a *App) outboxRemove(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if id == "" {
		a.Out.Clear()
	} else {
		a.Out.Remove(id)
	}
	count, size := a.Out.Stats()
	writeJSON(w, 200, map[string]any{"success": true, "count": count, "total_size": size})
}

// outboxFile 手机来取文件：直接从原路径流式发出，支持 Range 断点续传。
func (a *App) outboxFile(w http.ResponseWriter, r *http.Request) {
	it, ok := a.Out.Get(r.PathValue("id"))
	if !ok {
		http.NotFound(w, r)
		return
	}
	f, err := os.Open(it.Path)
	if err != nil {
		http.Error(w, "文件已不在", http.StatusGone)
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		http.Error(w, "读不到文件", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename*=UTF-8''%s", pathEscape(it.Name)))
	w.Header().Set("X-Relative-Path", it.Rel)
	http.ServeContent(w, r, it.Name, st.ModTime(), f)
	if r.Header.Get("Range") == "" {
		a.Out.MarkTaken(it.ID)
	}
}
