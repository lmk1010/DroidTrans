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
  names: {},
};

const I18N = {
  zh: {
    navHome: '总览', navHist: '图库',
    homeTitle: '把手机里的照片，搬到这台电脑',
    homeSub: '一根线，或同一局域网。安静、直接、本地完成。',
    usbTile: '有线快传', usbTileSub: '开启 USB 调试后扫描相册，并发 pull 到本地。',
    wifiTile: '手机直传', wifiTileSub: 'App 搜到这台电脑，文件走 TCP / FTP / HTTP。',
    scan: '扫描相册', xfer: '开始传输', waiting: '等待设备', save: '保存到',
    copy: '复制', copied: '已复制', open: '打开文件夹', openShort: '打开',
    wifiTitle: 'Wi-Fi 接收', wifiSub: '手机 App 填这个地址，或在同一局域网里搜索。',
    localAddr: '本机地址', online: '在线设备', batches: '最近图库',
    noPhone: '还没有手机连上来', noPhoneHint: '打开手机 App，搜到这台电脑即可',
    noBatch: '还没有收到文件', histTitle: '图库', clear: '清空',
    noHist: '图库还是空的', unauth: '设备未授权 USB 调试', offline: '未连接设备',
    recv: '正在接收', got: '已收到', photos: '张',
    recentGallery: '最近图库', openGallery: '打开图库', reveal: '在访达中显示',
    copyPath: '复制路径', delBatch: '删除这一批', copyAddr: '复制本机地址',
    goUsb: '打开 USB', goWifi: '打开 Wi-Fi', goHist: '打开图库',
  },
  en: {
    navHome: 'Home', navHist: 'Gallery',
    homeTitle: 'Move photos from your phone to this Mac',
    homeSub: 'A cable, or the same Wi-Fi. Quiet, local, done.',
    usbTile: 'Wired transfer', usbTileSub: 'Enable USB debugging, scan albums, pull concurrently.',
    wifiTile: 'Phone transfer', wifiTileSub: 'The app finds this computer over TCP / FTP / HTTP.',
    scan: 'Scan albums', xfer: 'Transfer', waiting: 'Waiting for device', save: 'Save to',
    copy: 'Copy', copied: 'Copied', open: 'Open folder', openShort: 'Open',
    wifiTitle: 'Wi-Fi receive', wifiSub: 'Enter this address in the phone app, or search the LAN.',
    localAddr: 'This computer', online: 'Online', batches: 'Recent gallery',
    noPhone: 'No phone yet', noPhoneHint: 'Open the app on your phone and find this computer',
    noBatch: 'Nothing received yet', histTitle: 'Gallery', clear: 'Clear',
    noHist: 'Gallery is empty', unauth: 'USB debugging not authorized', offline: 'No device',
    recv: 'Receiving', got: 'Received', photos: 'photos',
    recentGallery: 'Recent', openGallery: 'Open gallery', reveal: 'Reveal in Finder',
    copyPath: 'Copy path', delBatch: 'Delete batch', copyAddr: 'Copy address',
    goUsb: 'Open USB', goWifi: 'Open Wi-Fi', goHist: 'Open gallery',
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
  const status = dev.connected ? (dev.model || dev.selected) : t('offline');
  $('#adbHint').textContent = status;
  $('#adbHint').title = adb + (dev.connected ? ` · ${dev.model || dev.selected}` : '');
  const usbText = dev.connected ? (dev.model || (state.lang === 'zh' ? '已连接' : 'On')) : (state.lang === 'zh' ? '未连接' : 'Off');
  $('#homeStatus').textContent = `USB ${usbText}  ·  ${wifi.ip || '—'}  ·  ${health.engine || 'go'}`;
  if (!state.usbOut && health.root) {
    state.usbOut = health.root;
    $('#usbOut').value = health.root;
    $('#wifiOut').value = health.root;
  }
  (wifi.connected_devices || []).forEach((d) => {
    if (d.id && d.name) state.names[d.id] = d.name;
  });
}

async function refreshUsb() {
  const dev = await api('/api/device_status');
  $('#usbDevice').textContent = dev.connected
    ? `${dev.model || ''}  ·  ${dev.selected || ''}`
    : (dev.unauthorized_devices?.length ? t('unauth') : t('waiting'));
  const result = await api('/api/scan_result');
  if (result.albums) renderAlbums(result.albums);
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
    b.type = 'button';
    b.className = 'photo' + (state.selectedPhotos.has(p.path) ? ' on' : '');
    b.innerHTML = `<img alt="" src="/api/thumb?path=${encodeURIComponent(p.path)}" /><figcaption><strong>${esc(p.name)}</strong></figcaption>`;
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

function fileURL(p) {
  return `/api/thumb?path=${encodeURIComponent(p)}`;
}

function renderGallery(target, batches, limit) {
  const el = $(target);
  const items = (batches || []).slice(0, limit || 48);
  if (!items.length) {
    el.innerHTML = emptyHTML(I_STACK, t('noBatch'));
    return;
  }
  el.innerHTML = items.map((b) => {
    const cover = b.cover
      ? `<img class="cover" alt="" src="${fileURL(b.cover)}" />`
      : `<div class="cover ph">${I_STACK}</div>`;
    const name = b.device_name || deviceLabel(b.device_id);
    return `<button type="button" class="shot"
      data-device="${esc(b.device_id)}" data-batch="${esc(b.batch_id)}" data-folder="${esc(b.folder || '')}">
      ${cover}
      <span class="count">${b.photo_count || 0} ${t('photos')}</span>
      <figcaption><strong>${esc(name)}</strong><small>${esc(formatBatch(b.batch_id))}</small></figcaption>
    </button>`;
  }).join('');
  el.querySelectorAll('.shot').forEach((btn) => {
    btn.addEventListener('click', () => openViewer(btn.dataset.device, btn.dataset.batch, btn.dataset.folder));
    btn.addEventListener('contextmenu', (e) => {
      e.preventDefault();
      e.stopPropagation();
      e.stopImmediatePropagation();
      showMenu(e.clientX, e.clientY, [
        { label: t('openGallery'), act: () => openViewer(btn.dataset.device, btn.dataset.batch, btn.dataset.folder) },
        { label: t('open'), act: () => openFolder(btn.dataset.folder) },
        { label: t('reveal'), act: () => reveal(btn.dataset.folder) },
        { label: t('copyPath'), act: () => copyText(btn.dataset.folder) },
        { sep: true },
        { label: t('delBatch'), danger: true, act: () => deleteBatch(btn.dataset.device, btn.dataset.batch) },
      ]);
    });
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
async function openViewer(device, batch, folder) {
  hideMenu();
  viewerFolder = folder || '';
  const data = await api(`/api/gallery/batch?device=${encodeURIComponent(device)}&batch=${encodeURIComponent(batch)}`);
  $('#lightbox').classList.add('hidden');
  $('#viewer').classList.remove('hidden');
  $('#viewerTitle').textContent = `${deviceLabel(device)} · ${formatBatch(batch)}`;
  const photos = data.photos || [];
  $('#viewerGrid').innerHTML = photos.map((p) => `
    <button type="button" data-path="${esc(p.path)}" data-name="${esc(p.name)}">
      <img alt="${esc(p.name)}" src="${fileURL(p.path)}" />
    </button>`).join('') || emptyHTML(I_STACK, t('noHist'));
  $('#viewerGrid').querySelectorAll('button[data-path]').forEach((btn) => {
    btn.addEventListener('click', () => {
      $('#lightboxImg').src = fileURL(btn.dataset.path);
      $('#lightbox').classList.remove('hidden');
    });
    btn.addEventListener('contextmenu', (e) => {
      e.preventDefault();
      e.stopPropagation();
      e.stopImmediatePropagation();
      showMenu(e.clientX, e.clientY, [
        { label: state.lang === 'zh' ? '查看大图' : 'View', act: () => { $('#lightboxImg').src = fileURL(btn.dataset.path); $('#lightbox').classList.remove('hidden'); } },
        { label: t('reveal'), act: () => reveal(btn.dataset.path) },
        { label: t('copyPath'), act: () => copyText(btn.dataset.path) },
      ]);
    });
  });
}

$('#viewerBack').addEventListener('click', () => {
  $('#lightbox').classList.add('hidden');
  $('#viewer').classList.add('hidden');
});
$('#viewerOpen').addEventListener('click', () => openFolder(viewerFolder));
$('#lightbox').addEventListener('click', () => $('#lightbox').classList.add('hidden'));

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
    el.innerHTML = emptyHTML(I_PHONE, t('noPhone'), t('noPhoneHint'));
    return;
  }
  el.innerHTML = online.map((d) => {
    const id = d.id || d.device_id || '';
    const name = d.name || state.names[id] || shortId(id);
    if (d.name) state.names[id] = d.name;
    return `<div class="row-btn" role="listitem"><span class="dot"></span><span class="who"><b>${esc(name)}</b><small>${esc(shortId(id))}</small></span></div>`;
  }).join('');
}

async function refreshWifi() {
  const [info, gal] = await Promise.all([api('/api/wifi/info'), api('/api/gallery')]);
  const url = info.url || '—';
  $('#wifiURL').textContent = url;
  $('#wifiURL').title = url;
  if (info.url && !$('#wifiOut').value) {
    const h = await api('/api/health');
    $('#wifiOut').value = h.root || '';
  }
  renderOnline(info.connected_devices || []);
  renderGallery('#batchList', gal.batches || [], 12);
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

$('#openOut').addEventListener('click', () => {
  api('/api/wifi/open_folder', { method: 'POST', body: JSON.stringify({ folder_path: $('#wifiOut').value }) });
});

$('#wifiOut').addEventListener('change', () => {
  api('/api/wifi/set_output_dir', { method: 'POST', body: JSON.stringify({ output_dir: $('#wifiOut').value }) });
});

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
  api('/api/wifi/open_folder', { method: 'POST', body: JSON.stringify({ folder_path: $('#wifiOut').value }) });
});
$('#inboxDismiss').addEventListener('click', () => {
  hideThisBurst = true;
  inboxDismissed = inboxSeqSeen;
  hideInbox();
});

pollInbox();
