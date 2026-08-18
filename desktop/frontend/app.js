const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];

const state = {
  lang: localStorage.getItem('droidtrans.lang') || 'zh',
  view: 'home',
  albums: {},
  selectedAlbums: new Set(),
  currentAlbum: null,
  selectedPhotos: new Set(),
  usbOut: '',
};

const I18N = {
  zh: { scan: '扫描相册', xfer: '开始传输', waiting: '等待设备', save: '保存到', copy: '复制', open: '打开文件夹' },
  en: { scan: 'Scan albums', xfer: 'Transfer', waiting: 'Waiting for device', save: 'Save to', copy: 'Copy', open: 'Open folder' },
};

async function api(path, opts = {}) {
  const res = await fetch(path, {
    headers: { 'Content-Type': 'application/json', ...(opts.headers || {}) },
    ...opts,
  });
  const text = await res.text();
  try { return JSON.parse(text); } catch { return { success: false, error: text }; }
}

function show(view) {
  state.view = view;
  $$('.view').forEach((el) => el.classList.toggle('active', el.id === 'view-' + view));
  $$('nav button').forEach((b) => b.classList.toggle('active', b.dataset.view === view));
  if (view === 'usb') refreshUsb();
  if (view === 'wifi') refreshWifi();
  if (view === 'history') refreshHistory();
}

$$('nav button').forEach((b) => b.addEventListener('click', () => show(b.dataset.view)));
$$('[data-go]').forEach((b) => b.addEventListener('click', () => show(b.dataset.go)));

async function refreshHome() {
  const [dev, health, wifi] = await Promise.all([
    api('/api/device_status'),
    api('/api/health'),
    api('/api/wifi/info'),
  ]);
  $('#adbHint').textContent = (dev.adb || 'adb') + (dev.connected ? `\n${dev.model || dev.selected}` : '\n未连接设备');
  $('#homeMeta').innerHTML = `
    <div><dt>USB</dt><dd>${dev.connected ? (dev.model || '已连接') : '未连接'}</dd></div>
    <div><dt>局域网</dt><dd>${wifi.ip || '—'}</dd></div>
    <div><dt>引擎</dt><dd>${health.engine || 'go'}</dd></div>`;
  if (!state.usbOut && health.root) {
    state.usbOut = health.root;
    $('#usbOut').value = health.root;
    $('#wifiOut').value = health.root;
  }
}

async function refreshUsb() {
  const dev = await api('/api/device_status');
  $('#usbDevice').textContent = dev.connected
    ? `${dev.model || ''}  ·  ${dev.selected || ''}`
    : (dev.unauthorized_devices?.length ? '设备未授权 USB 调试' : '等待设备');
  const result = await api('/api/scan_result');
  if (result.albums) renderAlbums(result.albums);
}

function renderAlbums(albums) {
  state.albums = albums || {};
  const grid = $('#albumGrid');
  grid.innerHTML = '';
  Object.entries(state.albums).forEach(([path, al]) => {
    const btn = document.createElement('button');
    btn.className = 'album' + (state.selectedAlbums.has(path) ? ' on' : '');
    const src = al.cover ? `/api/thumb?path=${encodeURIComponent(al.cover)}` : '';
    btn.innerHTML = `<img alt="" src="${src}" /><figcaption><strong>${al.name || path}</strong><small>${al.total_count || 0} 项</small></figcaption>`;
    btn.addEventListener('click', (e) => {
      if (e.shiftKey) openAlbum(path);
      else {
        if (state.selectedAlbums.has(path)) state.selectedAlbums.delete(path);
        else state.selectedAlbums.add(path);
        renderAlbums(state.albums);
        $('#xferBtn').disabled = state.selectedAlbums.size === 0 && state.selectedPhotos.size === 0;
      }
    });
    btn.addEventListener('dblclick', () => openAlbum(path));
    grid.appendChild(btn);
  });
}

async function openAlbum(path) {
  state.currentAlbum = path;
  const data = await api(`/api/album_photos?album=${encodeURIComponent(path)}&limit=200`);
  $('#photoGrid').classList.remove('hidden');
  $('#photoGrid').innerHTML = '';
  (data.photos || []).forEach((p) => {
    const b = document.createElement('button');
    b.className = 'photo' + (state.selectedPhotos.has(p.path) ? ' on' : '');
    b.innerHTML = `<img alt="" src="/api/thumb?path=${encodeURIComponent(p.path)}" /><figcaption><strong>${p.name}</strong></figcaption>`;
    b.addEventListener('click', () => {
      if (state.selectedPhotos.has(p.path)) state.selectedPhotos.delete(p.path);
      else state.selectedPhotos.add(p.path);
      openAlbum(path);
      $('#xferBtn').disabled = state.selectedAlbums.size === 0 && state.selectedPhotos.size === 0;
    });
    $('#photoGrid').appendChild(b);
  });
}

$('#scanBtn').addEventListener('click', async () => {
  $('#scanBtn').disabled = true;
  await api('/api/scan', { method: 'POST', body: '{}' });
  const t0 = Date.now();
  while (Date.now() - t0 < 60000) {
    const st = await api('/api/scan_status');
    if (!st.is_running && (st.stage === 'done' || st.stage === 'error')) {
      if (st.error) alert(st.error);
      break;
    }
    await new Promise((r) => setTimeout(r, 400));
  }
  const result = await api('/api/scan_result');
  renderAlbums(result.albums);
  $('#scanBtn').disabled = false;
});

$('#xferBtn').addEventListener('click', async () => {
  const output_dir = $('#usbOut').value.trim();
  const body = {
    output_dir,
    selection: { albums: [...state.selectedAlbums], singles: [...state.selectedPhotos], exclude: {} },
  };
  const res = await api('/api/transfer', { method: 'POST', body: JSON.stringify(body) });
  if (!res.success) {
    alert(res.error || '无法开始传输');
    return;
  }
  $('#xferBar').classList.remove('hidden');
  pollXfer();
});

async function pollXfer() {
  const st = await api('/api/transfer_status');
  const pct = st.percent_completed || 0;
  $('#xferFill').style.width = pct + '%';
  $('#xferText').textContent = st.is_running
    ? `${st.current || 0}/${st.total || 0}  ${st.current_file || ''}  ${(st.speed_mbps || 0).toFixed(1)} MB/s`
    : `完成 ${st.completed_count || 0}，失败 ${(st.failed || []).length}`;
  if (st.is_running) setTimeout(pollXfer, 400);
}

async function refreshWifi() {
  const info = await api('/api/wifi/info');
  $('#wifiURL').textContent = info.url || '—';
  if (info.url && !$('#wifiOut').value) {
    const h = await api('/api/health');
    $('#wifiOut').value = h.root || '';
  }
  const online = info.connected_devices || [];
  $('#onlineList').innerHTML = online.length
    ? online.map((d) => `<li><b>${d.name || d.id}</b><span>${d.id}</span></li>`).join('')
    : '<li>还没有手机连上来</li>';
  const hist = await api('/api/history/batches');
  $('#batchList').innerHTML = (hist.batches || []).slice(0, 12).map((b) =>
    `<li><b>${b.device_id}</b><span>${b.batch_id} · ${b.photo_count} 张</span></li>`
  ).join('') || '<li>暂无批次</li>';
}

$('#copyUrl').addEventListener('click', async () => {
  await navigator.clipboard.writeText($('#wifiURL').textContent);
  $('#copyUrl').textContent = '已复制';
  setTimeout(() => { $('#copyUrl').textContent = '复制'; }, 1200);
});

$('#openOut').addEventListener('click', () => {
  api('/api/wifi/open_folder', { method: 'POST', body: JSON.stringify({ folder_path: $('#wifiOut').value }) });
});

$('#wifiOut').addEventListener('change', () => {
  api('/api/wifi/set_output_dir', { method: 'POST', body: JSON.stringify({ output_dir: $('#wifiOut').value }) });
});

async function refreshHistory() {
  const hist = await api('/api/history/batches');
  $('#histList').innerHTML = (hist.batches || []).map((b) =>
    `<li><b>${b.device_id} · ${b.batch_id}</b><span>${b.photo_count} 张 · ${b.duration_sec || 0}s</span></li>`
  ).join('') || '<li>没有记录</li>';
}

$('#clearHist').addEventListener('click', async () => {
  if (!confirm('清空所有传输记录？')) return;
  await api('/api/history/clear', { method: 'POST', body: '{}' });
  refreshHistory();
});

$('#langBtn').addEventListener('click', () => {
  state.lang = state.lang === 'zh' ? 'en' : 'zh';
  localStorage.setItem('droidtrans.lang', state.lang);
  $('#langBtn').textContent = state.lang === 'zh' ? 'EN' : '中文';
});

refreshHome();
setInterval(refreshHome, 4000);
