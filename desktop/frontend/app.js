const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];

const state = {
  lang: localStorage.getItem('droidtrans.lang') || 'zh',
  view: 'home',
  albums: {},
  selectedAlbums: new Set(),
  currentAlbum: null,
  albumPhotos: [],
  selectedPhotos: new Set(),
  usbOut: '',
  names: {},
  usbConnected: false,
};

let viewerPhotos = [];
let viewerIndex = 0;
let inboxBatch = { device: '', batch: '', folder: '' };
let lastXfer = { device: '', batch: '', folder: '' };

const I18N = {
  zh: {
    navHome: '总览', navHist: '图库',
    homeTitle: '把手机里的照片，搬到这台电脑',
    usbTile: '有线快传', wifiTile: '手机直传',
    scan: '扫描相册', scanning: '扫描中…', xfer: '开始传输', waiting: '等待设备', save: '保存到',
    selAll: '全选', selNone: '取消全选',
    usbEmptyHint: '用线连上手机，在手机上允许 USB 调试。',
    usbNoAlbum: '还没有相册', usbScanHint: '连上后会自动扫。选出要传的，再开始传输。',
    copy: '复制', copied: '已复制', open: '打开文件夹', openShort: '打开',
    wifiTitle: 'Wi-Fi 接收', wifiSub: '手机打开卓传，同一 Wi-Fi 会自己连上。也可以扫码。',
    wifiHint: '手机打开卓传，会自己搜到。也可以扫左边的码。',
    localAddr: '本机地址', online: '在线设备', batches: '最近图库',
    noPhone: '还没有手机连上来', noPhoneHint: '打开手机 App，搜到这台电脑即可',
    homeNextUsb: '手机已连上，去 USB 选相册。',
    homeNextOnline: '手机已在线，打开图库看刚传过来的。',
    homeNextWifi: 'Wi-Fi 已就绪。手机打开卓传会自己连。',
    homeNextIdle: '插上线，或让手机和电脑在同一 Wi-Fi。',
    openThisPhone: '打开这台手机的图库',
    noBatch: '还没有收到文件', histTitle: '图库', clear: '清空',
    noHist: '图库还是空的', noHistHint: '从 USB 或 Wi-Fi 传过来，就会出现在这里。',
    unauth: '设备未授权 USB 调试', offline: '未连接设备',
    recv: '正在接收', got: '已收到', photos: '张',
    recentGallery: '最近图库', openGallery: '打开图库', reveal: '在访达中显示',
    copyPath: '复制路径', delBatch: '删除这一批', copyAddr: '复制本机地址',
    goUsb: '打开 USB', goWifi: '打开 Wi-Fi', goHist: '打开图库',
    seeGallery: '查看', backAlbums: '← 返回相册',
    copyName: '复制名称', copyFile: '复制文件名',
  },
  en: {
    navHome: 'Home', navHist: 'Gallery',
    homeTitle: 'Move photos from your phone to this Mac',
    usbTile: 'USB transfer', wifiTile: 'Wi-Fi transfer',
    scan: 'Scan albums', scanning: 'Scanning…', xfer: 'Transfer', waiting: 'Waiting for device', save: 'Save to',
    selAll: 'Select all', selNone: 'Clear selection',
    usbEmptyHint: 'Connect the phone and allow USB debugging on the device.',
    usbNoAlbum: 'No albums yet', usbScanHint: 'Albums scan automatically. Pick what to send, then transfer.',
    copy: 'Copy', copied: 'Copied', open: 'Open folder', openShort: 'Open',
    wifiTitle: 'Wi-Fi receive', wifiSub: 'Open DroidTrans on the phone. Same Wi-Fi connects by itself. Or scan the code.',
    wifiHint: 'The phone finds this computer on its own. You can also scan the code.',
    localAddr: 'This computer', online: 'Online', batches: 'Recent gallery',
    noPhone: 'No phone yet', noPhoneHint: 'Open the app on your phone and find this computer',
    homeNextUsb: 'Phone connected. Open USB to pick albums.',
    homeNextOnline: 'Phone is online. Open the gallery for what just arrived.',
    homeNextWifi: 'Wi-Fi is ready. The phone app will connect itself.',
    homeNextIdle: 'Plug in a cable, or put the phone on the same Wi-Fi.',
    openThisPhone: 'Open this phone’s gallery',
    noBatch: 'Nothing received yet', histTitle: 'Gallery', clear: 'Clear',
    noHist: 'Gallery is empty', noHistHint: 'Files you send over USB or Wi-Fi show up here.',
    unauth: 'USB debugging not authorized', offline: 'No device',
    recv: 'Receiving', got: 'Received', photos: 'photos',
    recentGallery: 'Recent', openGallery: 'Open gallery', reveal: 'Reveal in Finder',
    copyPath: 'Copy path', delBatch: 'Delete batch', copyAddr: 'Copy address',
    goUsb: 'Open USB', goWifi: 'Open Wi-Fi', goHist: 'Open gallery',
    seeGallery: 'View', backAlbums: '← Back to albums',
    copyName: 'Copy name', copyFile: 'Copy filename',
  },
};

const t = (k) => I18N[state.lang][k] || I18N.zh[k] || k;

function applyLang() {
  document.documentElement.lang = state.lang === 'zh' ? 'zh-CN' : 'en';
  $$('[data-i18n]').forEach((el) => { el.textContent = t(el.dataset.i18n); });
  $$('[data-i18n-title]').forEach((el) => {
    const label = t(el.dataset.i18nTitle);
    el.title = label;
    el.setAttribute('aria-label', label);
  });
  $('#langBtn').textContent = state.lang === 'zh' ? 'EN' : '中文';
}

async function api(path, opts = {}) {
  const res = await fetch(path, {
    headers: { 'Content-Type': 'application/json', ...(opts.headers || {}) },
    ...opts,
  });
  const text = await res.text();
  try { return JSON.parse(text); } catch { return { success: false, error: text }; }
}

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function shortId(id) {
  if (!id) return '';
  if (id.length <= 12) return id;
  return id.slice(0, 6) + '…' + id.slice(-4);
}

function deviceLabel(id) {
  const name = state.names[id];
  if (name && name !== id) return name;
  return shortId(id);
}

function formatBatch(id) {
  const m = String(id || '').match(/^(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})$/);
  if (!m) return id || '';
  if (state.lang === 'en') return `${m[1]}-${m[2]}-${m[3]} ${m[4]}:${m[5]}`;
  return `${Number(m[2])}月${Number(m[3])}日 ${m[4]}:${m[5]}`;
}

function emptyHTML(icon, title, hint) {
  return `<div class="empty">${icon}<div><div>${esc(title)}</div>${hint ? `<small>${esc(hint)}</small>` : ''}</div></div>`;
}

const I_PHONE = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="7" y="3" width="10" height="18" rx="2"/><path d="M11 18h2"/></svg>';
const I_DEVICE = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><rect x="8" y="2.5" width="8" height="14" rx="1.6"/><path d="M10 18.5h4"/><path d="M7 21h10"/></svg>';
const I_STACK = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M4 8l8-4 8 4-8 4-8-4z"/><path d="M4 12l8 4 8-4"/><path d="M4 16l8 4 8-4"/></svg>';

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

async function refreshNames() {
  const hist = await api('/api/history/devices');
  (hist.devices || []).forEach((d) => {
    if (d.device_id && d.device_name) state.names[d.device_id] = d.device_name;
  });
}

async function refreshHome() {
  const [dev, health, wifi] = await Promise.all([
    api('/api/device_status'),
    api('/api/health'),
    api('/api/wifi/info'),
  ]);
  const adb = dev.adb || 'adb';
  state.usbConnected = !!dev.connected;
  if (dev.connected) {
    const who = dev.model || dev.selected || t('waiting');
    $('#adbHint').textContent = who;
    $('#adbHint').title = adb + ' · ' + who;
    $('#homeUsb').textContent = who;
    $('#homeUsb').classList.add('on');
  } else {
    const usb = dev.unauthorized_devices?.length ? t('unauth') : t('offline');
    $('#adbHint').textContent = usb;
    $('#adbHint').title = adb;
    $('#homeUsb').textContent = usb;
    $('#homeUsb').classList.remove('on');
  }
  const ip = wifi.ip || '—';
  const n = (wifi.connected_devices || []).length;
  $('#homeWifi').textContent = n
    ? (state.lang === 'zh' ? `${ip}  ·  ${n} 台在线` : `${ip}  ·  ${n} online`)
    : ip;
  $('#homeWifi').classList.toggle('on', n > 0);
  if (!state.usbOut && health.root) {
    setOut(health.root, false);
  }
  (wifi.connected_devices || []).forEach((d) => {
    if (d.id && d.name) state.names[d.id] = d.name;
  });
  const next = $('#homeNext');
  if (next) {
    if (dev.connected) next.textContent = t('homeNextUsb');
    else if (n) next.textContent = t('homeNextOnline');
    else if (wifi.ip) next.textContent = t('homeNextWifi');
    else next.textContent = t('homeNextIdle');
  }
  maybeAutoScan(dev);
}

async function refreshUsb() {
  const dev = await api('/api/device_status');
  state.usbConnected = !!dev.connected;
  $('#usbDevice').textContent = dev.connected
    ? `${dev.model || ''}  ·  ${dev.selected || ''}`
    : (dev.unauthorized_devices?.length ? t('unauth') : t('waiting'));
  $('#scanBtn').disabled = !dev.connected || usbScanning;
  const result = await api('/api/scan_result');
  renderAlbums(result.albums);
  setUsbEmpty(dev, result.albums);
  maybeAutoScan(dev);
}

function setUsbEmpty(dev, albums) {
  const el = $('#usbEmpty');
  const n = Object.keys(albums || {}).length;
  if (n || state.currentAlbum) {
    el.classList.add('hidden');
    return;
  }
  el.classList.remove('hidden');
  if (!dev.connected) {
    el.innerHTML = emptyHTML(I_PHONE, t('waiting'), t('usbEmptyHint'));
  } else {
    el.innerHTML = emptyHTML(I_STACK, t('usbNoAlbum'), t('usbScanHint'));
  }
}

function updateSelAll() {
  const inPhotos = !!state.currentAlbum;
  const has = inPhotos ? state.albumPhotos.length : Object.keys(state.albums).length;
  $('#selAll').classList.toggle('hidden', !has);
  const allOn = inPhotos
    ? state.albumPhotos.length > 0 && state.selectedPhotos.size >= state.albumPhotos.length
    : Object.keys(state.albums).length > 0 && state.selectedAlbums.size >= Object.keys(state.albums).length;
  $('#selAll').textContent = allOn ? t('selNone') : t('selAll');
}

function updateXferBtn() {
  $('#xferBtn').disabled = state.selectedAlbums.size === 0 && state.selectedPhotos.size === 0;
}

function hidePhotos() {
  state.currentAlbum = null;
  state.albumPhotos = [];
  $('#photoGrid').classList.add('hidden');
  $('#photoGrid').innerHTML = '';
  $('#photoBack').classList.add('hidden');
  $('#albumGrid').classList.remove('hidden');
  updateSelAll();
}

function bindImg(root) {
  (root.querySelectorAll ? root.querySelectorAll('img') : []).forEach((img) => {
    img.addEventListener('error', () => { img.style.opacity = '0.18'; });
  });
}

function renderAlbums(albums) {
  state.albums = albums || {};
  const grid = $('#albumGrid');
  grid.innerHTML = '';
  Object.entries(state.albums).forEach(([path, al]) => {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'album' + (state.selectedAlbums.has(path) ? ' on' : '');
    const src = al.cover ? `/api/thumb?path=${encodeURIComponent(al.cover)}` : '';
    btn.innerHTML = `<img alt="" src="${src}" /><figcaption><strong>${esc(al.name || path)}</strong><small>${al.total_count || 0} ${state.lang === 'zh' ? '项' : 'items'}</small></figcaption>`;
    btn.addEventListener('click', (e) => {
      if (e.shiftKey) {
        openAlbum(path);
        return;
      }
      if (state.selectedAlbums.has(path)) state.selectedAlbums.delete(path);
      else state.selectedAlbums.add(path);
      renderAlbums(state.albums);
      updateXferBtn();
    });
    btn.addEventListener('dblclick', () => openAlbum(path));
    grid.appendChild(btn);
  });
  bindImg(grid);
  updateSelAll();
  updateXferBtn();
  if (!state.currentAlbum) $('#albumGrid').classList.toggle('hidden', Object.keys(state.albums).length === 0);
}

async function openAlbum(path) {
  state.currentAlbum = path;
  const data = await api(`/api/album_photos?album=${encodeURIComponent(path)}&limit=200`);
  state.albumPhotos = data.photos || [];
  $('#usbEmpty').classList.add('hidden');
  $('#albumGrid').classList.add('hidden');
  $('#photoBack').classList.remove('hidden');
  $('#photoGrid').classList.remove('hidden');
  $('#photoGrid').innerHTML = '';
  state.albumPhotos.forEach((p) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'photo' + (state.selectedPhotos.has(p.path) ? ' on' : '');
    b.innerHTML = `<img alt="" src="/api/thumb?path=${encodeURIComponent(p.path)}" /><figcaption><strong>${esc(p.name)}</strong></figcaption>`;
    b.addEventListener('click', () => {
      if (state.selectedPhotos.has(p.path)) state.selectedPhotos.delete(p.path);
      else state.selectedPhotos.add(p.path);
      openAlbum(path);
      updateXferBtn();
    });
    b.addEventListener('dblclick', (e) => {
      e.preventDefault();
      viewerPhotos = state.albumPhotos;
      const i = viewerPhotos.findIndex((x) => x.path === p.path);
      showLightbox(i < 0 ? 0 : i);
    });
    $('#photoGrid').appendChild(b);
  });
  bindImg($('#photoGrid'));
  updateSelAll();
  updateXferBtn();
}

$('#photoBack').addEventListener('click', hidePhotos);

$('#selAll').addEventListener('click', () => {
  if (state.currentAlbum) {
    const all = state.albumPhotos.map((p) => p.path);
    if (state.selectedPhotos.size >= all.length) all.forEach((p) => state.selectedPhotos.delete(p));
    else all.forEach((p) => state.selectedPhotos.add(p));
    openAlbum(state.currentAlbum);
  } else {
    const keys = Object.keys(state.albums);
    if (state.selectedAlbums.size >= keys.length) state.selectedAlbums.clear();
    else keys.forEach((k) => state.selectedAlbums.add(k));
    renderAlbums(state.albums);
  }
  updateXferBtn();
});

let usbAutoSerial = '';
let usbScanning = false;

async function maybeAutoScan(dev) {
  if (!dev || !dev.connected) {
    usbAutoSerial = '';
    return;
  }
  const serial = dev.selected || '';
  if (!serial || serial === usbAutoSerial || usbScanning) return;
  usbAutoSerial = serial;
  const ok = await startUsbScan();
  if (!ok) usbAutoSerial = '';
}

async function startUsbScan() {
  if (!state.usbConnected || usbScanning) return false;
  usbScanning = true;
  $('#scanBtn').disabled = true;
  $('#scanBtn').textContent = t('scanning');
  hidePhotos();
  const empty = $('#usbEmpty');
  if (empty && !state.currentAlbum) {
    empty.classList.remove('hidden');
    empty.innerHTML = emptyHTML(I_STACK, t('scanning'), t('usbScanHint'));
  }
  await api('/api/scan', { method: 'POST', body: '{}' });
  const t0 = Date.now();
  let err = '';
  while (Date.now() - t0 < 60000) {
    const st = await api('/api/scan_status');
    if (!st.is_running && (st.stage === 'done' || st.stage === 'error')) {
      err = st.error || '';
      break;
    }
    await new Promise((r) => setTimeout(r, 400));
  }
  const result = await api('/api/scan_result');
  if (state.view === 'usb') {
    renderAlbums(result.albums);
    if (err) {
      empty.classList.remove('hidden');
      empty.innerHTML = emptyHTML(I_PHONE, err, t('usbScanHint'));
    } else {
      setUsbEmpty({ connected: state.usbConnected }, result.albums);
    }
  }
  $('#scanBtn').textContent = t('scan');
  $('#scanBtn').disabled = !state.usbConnected;
  usbScanning = false;
  return !err;
}

$('#scanBtn').addEventListener('click', () => startUsbScan());

$('#xferBtn').addEventListener('click', async () => {
  const output_dir = $('#usbOut').value.trim();
  const body = {
    output_dir,
    selection: { albums: [...state.selectedAlbums], singles: [...state.selectedPhotos], exclude: {} },
  };
  const res = await api('/api/transfer', { method: 'POST', body: JSON.stringify(body) });
  if (!res.success) {
    $('#xferBar').classList.remove('hidden');
    $('#xferSee').classList.add('hidden');
    $('#xferText').textContent = res.error || (state.lang === 'zh' ? '无法开始传输' : 'Could not start');
    return;
  }
  $('#xferBar').classList.remove('hidden');
  $('#xferSee').classList.add('hidden');
  pollXfer();
});

async function pollXfer() {
  const st = await api('/api/transfer_status');
  const pct = st.percent_completed || 0;
  $('#xferFill').style.width = pct + '%';
  if (st.is_running) {
    $('#xferSee').classList.add('hidden');
    $('#xferText').textContent = `${st.current || 0}/${st.total || 0}  ${st.current_file || ''}  ${(st.speed_mbps || 0).toFixed(1)} MB/s`;
    setTimeout(pollXfer, 400);
    return;
  }
  const n = st.completed_count || 0;
  const fail = (st.failed || []).length;
  $('#xferText').textContent = state.lang === 'zh'
    ? `完成 ${n} 张${fail ? `，失败 ${fail}` : ''}`
    : `Done ${n}${fail ? `, failed ${fail}` : ''}`;
  $('#xferSee').classList.toggle('hidden', n === 0);
  lastXfer = { device: st.device_id || '', batch: st.batch_id || '', folder: st.output_dir || '' };
}

$('#xferSee').addEventListener('click', () => {
  show('history');
  if (lastXfer.device && lastXfer.batch) {
    openViewer(lastXfer.device, lastXfer.batch, lastXfer.folder);
  }
});

function fileURL(p) {
  return `/api/thumb?path=${encodeURIComponent(p)}`;
}

function renderGallery(target, batches, limit) {
  const el = $(target);
  const items = (batches || []).slice(0, limit || 48);
  if (!items.length) {
    el.innerHTML = `<div class="empty empty-go">${I_STACK}<div>
      <div>${esc(t('noHist'))}</div>
      <small>${esc(t('noHistHint'))}</small>
      <div class="empty-acts">
        <button type="button" class="ghost" data-go="usb">USB</button>
        <button type="button" class="ghost" data-go="wifi">Wi-Fi</button>
      </div>
    </div></div>`;
    el.querySelectorAll('[data-go]').forEach((b) => b.addEventListener('click', () => show(b.dataset.go)));
    return;
  }
  el.innerHTML = items.map((b) => {
    const cover = b.cover
      ? `<img class="cover" alt="" src="${fileURL(b.cover)}" />`
      : `<div class="cover ph">${I_STACK}</div>`;
    const name = b.device_name || deviceLabel(b.device_id);
    return `<button type="button" class="shot"
      data-device="${esc(b.device_id)}" data-batch="${esc(b.batch_id)}" data-folder="${esc(b.folder || '')}" data-name="${esc(name)}">
      ${cover}
      <span class="count">${b.photo_count || 0} ${t('photos')}</span>
      <figcaption><strong>${esc(name)}</strong><small>${esc(formatBatch(b.batch_id))}</small></figcaption>
    </button>`;
  }).join('');
  bindImg(el);
  el.querySelectorAll('.shot').forEach((btn) => {
    bindOpenAndMenu(
      btn,
      () => openViewer(btn.dataset.device, btn.dataset.batch, btn.dataset.folder),
      () => [
        { label: t('openGallery'), act: () => openViewer(btn.dataset.device, btn.dataset.batch, btn.dataset.folder) },
        { label: t('open'), act: () => openFolder(btn.dataset.folder) },
        { label: t('reveal'), act: () => reveal(btn.dataset.folder) },
        { label: t('copyPath'), act: () => copyText(btn.dataset.folder) },
        { label: t('copyName'), act: () => copyText(btn.dataset.name) },
        { sep: true },
        { label: t('delBatch'), danger: true, act: () => deleteBatch(btn.dataset.device, btn.dataset.batch) },
      ],
    );
  });
}

let skipClickUntil = 0;
document.addEventListener('click', (e) => {
  if (Date.now() >= skipClickUntil) return;
  if ($('#menu')?.contains(e.target)) return;
  e.preventDefault();
  e.stopImmediatePropagation();
}, true);

function bindOpenAndMenu(el, onOpen, menuItems) {
  el.addEventListener('pointerup', (e) => {
    if (e.button !== 0) return;
    if (Date.now() < skipClickUntil) return;
    onOpen();
  });
  el.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    e.stopPropagation();
    e.stopImmediatePropagation();
    skipClickUntil = Date.now() + 500;
    showMenu(e.clientX, e.clientY, menuItems());
  });
  el.addEventListener('auxclick', (e) => {
    if (e.button === 2) {
      e.preventDefault();
      e.stopPropagation();
    }
  });
}

function openFolder(path) {
  if (!path) return;
  api('/api/wifi/open_folder', { method: 'POST', body: JSON.stringify({ folder_path: path }) });
}
function reveal(path) {
  if (!path) return;
  api('/api/reveal', { method: 'POST', body: JSON.stringify({ path }) });
}
async function copyText(s) {
  if (!s) return;
  try { await navigator.clipboard.writeText(s); } catch { /* ignore */ }
}
async function deleteBatch(device, batch) {
  if (!confirm(state.lang === 'zh' ? '删除这一批文件？' : 'Delete this batch?')) return;
  await api('/api/wifi/delete_batch', { method: 'POST', body: JSON.stringify({ device_id: device, batch_id: batch }) });
  refreshHome();
  if (state.view === 'wifi') refreshWifi();
  if (state.view === 'history') refreshHistory();
}

let viewerFolder = '';
function showLightbox(i) {
  if (!viewerPhotos.length) return;
  viewerIndex = (i + viewerPhotos.length) % viewerPhotos.length;
  const p = viewerPhotos[viewerIndex];
  $('#lightboxImg').src = fileURL(p.path);
  $('#lightboxImg').alt = p.name || '';
  $('#lightbox').classList.remove('hidden');
  const many = viewerPhotos.length > 1;
  $('#lbPrev').classList.toggle('hidden', !many);
  $('#lbNext').classList.toggle('hidden', !many);
}

async function openViewer(device, batch, folder) {
  hideMenu();
  viewerFolder = folder || '';
  const data = await api(`/api/gallery/batch?device=${encodeURIComponent(device)}&batch=${encodeURIComponent(batch)}`);
  $('#lightbox').classList.add('hidden');
  $('#viewer').classList.remove('hidden');
  $('#viewerTitle').textContent = `${deviceLabel(device)} · ${formatBatch(batch)}`;
  viewerPhotos = data.photos || [];
  if (data.folder) viewerFolder = data.folder;
  $('#viewerGrid').innerHTML = viewerPhotos.map((p, i) => `
    <button type="button" data-i="${i}" data-path="${esc(p.path)}" data-name="${esc(p.name)}">
      <img alt="${esc(p.name)}" src="${fileURL(p.path)}" />
    </button>`).join('') || emptyHTML(I_STACK, t('noHist'));
  bindImg($('#viewerGrid'));
  $('#viewerGrid').querySelectorAll('button[data-path]').forEach((btn) => {
    bindOpenAndMenu(
      btn,
      () => showLightbox(Number(btn.dataset.i)),
      () => [
        { label: state.lang === 'zh' ? '查看大图' : 'View', act: () => showLightbox(Number(btn.dataset.i)) },
        { label: t('reveal'), act: () => reveal(btn.dataset.path) },
        { label: t('open'), act: () => openFolder(btn.dataset.path.replace(/[/\\][^/\\]+$/, '')) },
        { label: t('copyPath'), act: () => copyText(btn.dataset.path) },
        { label: t('copyFile'), act: () => copyText(btn.dataset.name) },
      ],
    );
  });
}

$('#viewerBack').addEventListener('click', () => {
  $('#lightbox').classList.add('hidden');
  $('#viewer').classList.add('hidden');
});
$('#viewerOpen').addEventListener('click', () => openFolder(viewerFolder));
$('#lightbox').addEventListener('click', (e) => {
  if (e.target === $('#lightbox')) $('#lightbox').classList.add('hidden');
});
$('#lightboxImg').addEventListener('click', (e) => e.stopPropagation());
$('#lbPrev').addEventListener('click', (e) => { e.stopPropagation(); showLightbox(viewerIndex - 1); });
$('#lbNext').addEventListener('click', (e) => { e.stopPropagation(); showLightbox(viewerIndex + 1); });

let menuCloser = null;
function showMenu(x, y, items) {
  hideMenu();
  const menu = $('#menu');
  menu.innerHTML = items.map((it) => {
    if (it.sep) return '<hr />';
    return `<button type="button" class="${it.danger ? 'danger' : ''}">${esc(it.label)}</button>`;
  }).join('');
  menu.classList.remove('hidden');
  const pad = 8;
  requestAnimationFrame(() => {
    const w = menu.offsetWidth;
    const h = menu.offsetHeight;
    menu.style.left = Math.min(x, window.innerWidth - w - pad) + 'px';
    menu.style.top = Math.min(y, window.innerHeight - h - pad) + 'px';
  });
  [...menu.querySelectorAll('button')].forEach((btn, i) => {
    const it = items.filter((row) => !row.sep)[i];
    if (it) btn.addEventListener('click', (e) => { e.stopPropagation(); hideMenu(); it.act(); });
  });
  menuCloser = (e) => {
    if (menu.contains(e.target)) return;
    hideMenu();
  };
  setTimeout(() => document.addEventListener('pointerdown', menuCloser, true), 80);
}
function hideMenu() {
  if (menuCloser) {
    document.removeEventListener('pointerdown', menuCloser, true);
    menuCloser = null;
  }
  $('#menu').classList.add('hidden');
}
document.addEventListener('keydown', (e) => {
  if (e.key === 'ArrowLeft' && !$('#lightbox').classList.contains('hidden')) {
    showLightbox(viewerIndex - 1);
    return;
  }
  if (e.key === 'ArrowRight' && !$('#lightbox').classList.contains('hidden')) {
    showLightbox(viewerIndex + 1);
    return;
  }
  if (e.key !== 'Escape') return;
  if (!$('#menu').classList.contains('hidden')) {
    hideMenu();
    return;
  }
  if (!$('#lightbox').classList.contains('hidden')) {
    $('#lightbox').classList.add('hidden');
    return;
  }
  $('#viewer').classList.add('hidden');
});

function renderOnline(online) {
  const el = $('#onlineList');
  if (!online.length) {
    el.innerHTML = emptyHTML(I_DEVICE, t('noPhone'), t('noPhoneHint'));
    return;
  }
  el.innerHTML = online.map((d) => {
    const id = d.id || d.device_id || '';
    const name = d.name || state.names[id] || shortId(id);
    if (d.name) state.names[id] = d.name;
    return `<button type="button" class="row-btn" data-device="${esc(id)}" title="${esc(t('openThisPhone'))}"><span class="live" aria-hidden="true">${I_DEVICE}</span><span class="who"><b>${esc(name)}</b><small>${esc(shortId(id))}</small></span></button>`;
  }).join('');
  el.querySelectorAll('[data-device]').forEach((btn) => {
    btn.addEventListener('click', () => openDeviceGallery(btn.dataset.device));
  });
}

async function openDeviceGallery(deviceId) {
  if (!deviceId) {
    show('history');
    return;
  }
  const gal = await api('/api/gallery');
  const batch = (gal.batches || []).find((b) => b.device_id === deviceId);
  show('history');
  if (batch) openViewer(batch.device_id, batch.batch_id, batch.folder || '');
}

let lastQR = '';
function renderQR(url) {
  const box = $('#wifiQR');
  if (!box || typeof QRCode === 'undefined') return;
  if (!url || url === '—') return;
  if (url === lastQR && box.childElementCount) return;
  lastQR = url;
  box.innerHTML = '';
  new QRCode(box, {
    text: url,
    width: 132,
    height: 132,
    colorDark: '#f4f5f7',
    colorLight: '#121317',
    correctLevel: QRCode.CorrectLevel.M,
  });
}

async function refreshWifi() {
  const [info, gal] = await Promise.all([api('/api/wifi/info'), api('/api/gallery')]);
  const url = info.url || '—';
  $('#wifiURL').textContent = url;
  $('#wifiURL').title = url;
  renderQR(info.url || '');
  if (info.url && !$('#wifiOut').value) {
    const h = await api('/api/health');
    setOut(h.root || '', false);
  }
  renderOnline(info.connected_devices || []);
  const n = (gal.batches || []).length;
  const jump = $('#wifiRecent');
  if (n) {
    jump.classList.remove('hidden');
    jump.textContent = state.lang === 'zh'
      ? `已收到 ${n} 批，去图库看`
      : `${n} batches received — open gallery`;
  } else {
    jump.classList.add('hidden');
  }
}

function setOut(path, persist = true) {
  if (!path) return;
  $('#usbOut').value = path;
  $('#wifiOut').value = path;
  state.usbOut = path;
  if (persist) {
    api('/api/wifi/set_output_dir', { method: 'POST', body: JSON.stringify({ output_dir: path }) });
  }
}

async function copyURL() {
  const url = $('#wifiURL').textContent;
  if (!url || url === '—') return;
  try { await navigator.clipboard.writeText(url); } catch { /* ignore */ }
  const btn = $('#copyUrl');
  btn.classList.add('ok');
  btn.querySelector('.i-copy').classList.add('hidden');
  btn.querySelector('.i-check').classList.remove('hidden');
  btn.title = t('copied');
  setTimeout(() => {
    btn.classList.remove('ok');
    btn.querySelector('.i-copy').classList.remove('hidden');
    btn.querySelector('.i-check').classList.add('hidden');
    btn.title = t('copy');
  }, 1400);
}

$('#copyUrl').addEventListener('click', copyURL);
$('#wifiURL').addEventListener('click', copyURL);
$('#wifiRecent').addEventListener('click', () => show('history'));

$('#openOut').addEventListener('click', () => openFolder($('#wifiOut').value));
$('#openUsbOut').addEventListener('click', () => openFolder($('#usbOut').value));

$('#wifiOut').addEventListener('change', () => setOut($('#wifiOut').value.trim()));
$('#usbOut').addEventListener('change', () => setOut($('#usbOut').value.trim()));

async function refreshHistory() {
  await refreshNames();
  const gal = await api('/api/gallery');
  renderGallery('#histList', gal.batches || []);
}

$('#clearHist').addEventListener('click', async () => {
  if (!confirm(state.lang === 'zh' ? '清空所有传输记录？' : 'Clear all transfer history?')) return;
  await api('/api/history/clear', { method: 'POST', body: '{}' });
  refreshHistory();
});

$('#langBtn').addEventListener('click', () => {
  state.lang = state.lang === 'zh' ? 'en' : 'zh';
  localStorage.setItem('droidtrans.lang', state.lang);
  applyLang();
  refreshHome();
  if (state.view === 'wifi') refreshWifi();
  if (state.view === 'history') refreshHistory();
  if (state.view === 'usb') refreshUsb();
});

applyLang();
refreshHome();
refreshNames();
setInterval(refreshHome, 4000);
setInterval(() => {
  if (state.view === 'wifi') refreshWifi();
}, 2000);
setInterval(() => {
  refreshHome();
  refreshNames();
  if (state.view === 'wifi') refreshWifi();
  if (state.view === 'history') refreshHistory();
}, 10 * 60 * 1000);

let inboxDismissed = -1;
let inboxHideTimer = 0;
let inboxBooted = false;
let inboxSeqSeen = -1;
let hideThisBurst = false;

function hideInbox() {
  $('#inbox').classList.add('hidden');
}

async function pollInbox() {
  try {
    const box = await api('/api/inbox');
    const el = $('#inbox');
    const seq = box.seq || 0;
    if (!inboxBooted) {
      inboxBooted = true;
      if (!box.receiving) inboxDismissed = seq;
    }
    if (!box.receiving) hideThisBurst = false;
    const fresh = seq > inboxSeqSeen;
    if (fresh) inboxSeqSeen = seq;

    const showRecv = box.receiving && !hideThisBurst;
    const showDone = !box.receiving && seq > inboxDismissed && (box.completed || 0) > 0;
    if (!showRecv && !showDone) {
      setTimeout(pollInbox, 900);
      return;
    }

    const who = box.device || deviceLabel(box.device_id) || (state.lang === 'zh' ? '手机' : 'Phone');
    const n = box.completed || 0;
    const total = box.total || 0;
    const file = box.last_file || '';
    inboxBatch = { device: box.device_id || '', batch: box.batch_id || '', folder: '' };
    el.classList.remove('hidden');
    el.classList.toggle('done', !box.receiving);
    if (box.receiving) {
      $('#inboxTitle').textContent = total ? `${t('recv')} ${n}/${total}` : `${t('recv')} ${n}`;
      $('#inboxFill').style.width = total ? Math.min(100, (n / total) * 100) + '%' : '40%';
      clearTimeout(inboxHideTimer);
    } else {
      $('#inboxTitle').textContent = `${t('got')} ${n} ${t('photos')}`;
      $('#inboxFill').style.width = '100%';
      if (fresh) {
        clearTimeout(inboxHideTimer);
        inboxHideTimer = setTimeout(() => {
          inboxDismissed = seq;
          hideInbox();
        }, 5000);
      }
    }
    $('#inboxText').textContent = file ? `${who} · ${file}` : who;
    $('#inboxText').title = $('#inboxText').textContent;
    if (fresh && state.view === 'wifi') refreshWifi();
    if (fresh && state.view === 'history') refreshHistory();
  } catch (_) { /* keep polling */ }
  setTimeout(pollInbox, 700);
}

$('#inboxOpen').addEventListener('click', () => {
  hideThisBurst = true;
  inboxDismissed = inboxSeqSeen;
  hideInbox();
  show('history');
  if (inboxBatch.device && inboxBatch.batch) {
    openViewer(inboxBatch.device, inboxBatch.batch, inboxBatch.folder);
  }
});
$('#inboxDismiss').addEventListener('click', () => {
  hideThisBurst = true;
  inboxDismissed = inboxSeqSeen;
  hideInbox();
});

pollInbox();
