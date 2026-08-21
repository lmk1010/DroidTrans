// 前端一旦抛异常，视图可能一个都没激活，用户看到的就是一片空白。
// 把错误显示出来，至少知道发生了什么、能截图反馈。
window.addEventListener('error', (e) => {
  showFatal(e.message + (e.filename ? `  (${String(e.filename).split('/').pop()}:${e.lineno})` : ''));
});
window.addEventListener('unhandledrejection', (e) => {
  showFatal(String(e.reason && e.reason.message ? e.reason.message : e.reason));
});

let lastFatal = '';
let fatalTimer = 0;

function showFatal(msg) {
  let el = document.getElementById('fatal');
  if (!el) {
    el = document.createElement('div');
    el.id = 'fatal';
    el.className = 'fatal';
    document.body.appendChild(el);
  }
  el.textContent = msg;

  // 瞬时错误（比如桌面端重启时的 Failed to fetch）别永远挂在屏幕上
  clearTimeout(fatalTimer);
  fatalTimer = setTimeout(() => el.remove(), 8000);

  // 同一条只报一次：上报给桌面端日志，不用盯着界面也能发现静默失效
  if (msg === lastFatal) return;
  lastFatal = msg;
  try {
    fetch('/api/client_error', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: String(msg), where: location.pathname }),
    }).catch(() => {});
  } catch (_) { /* 上报失败就算了，不能让兜底自己再抛 */ }
}

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
  wifiPick: '',
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
    usbNoAlbum: '还没有相册', usbScanHint: '连上后会自动扫。选出要传的，再开始传输。',
    copy: '复制', copied: '已复制', open: '打开文件夹', openShort: '打开',
    wifiTitle: 'Wi-Fi 接收', wifiSub: '手机打开卓传会自己连上。',
    wifiHint: '已装 App 时扫这个，或等它自己发现。',
    localAddr: '本机地址', online: '在线设备', batches: '最近图库', seeAll: '全部',
    noAppYet: '手机还没装卓传？', apkGet: '用相机扫上面的码',
    goneN: '{n} 批的文件已不在', goneClean: '清理这些记录', goneShow: '看看',
    paneRecv: '接收', paneSend: '发送', sendWaiting: '等手机来取',
    sendHead: '发到手机', sendSub: '拖文件进窗口，或粘一段文字。手机打开卓传就能取走。',
    pairManage: '管理', pairSummary: '配对码 {code} · 已配对 {n} 台', pairSummaryOff: '配对已关闭',
    hotspotOn: '正连着手机热点 · 不用路由器也能传',
    hotspotHint: '没有路由器？手机开个热点，电脑连上来一样传。',
    pairKicker: '配对码', pairNew: '换一个', pairOff: '关掉配对', pairOn: '开启配对',
    pairHint: '手机扫上面的二维码就自动配对；也可以手输这六位。',
    pairOffHint: '任何在同一网络里的设备都能连这台电脑。',
    pairPeers: '已配对 {n} 台',
    sendPick: '选择文件…', clearAll: '全部清空',
    tookN: '已取走 {n} 个', tookClear: '清掉',
    sendTextPh: '粘一段文字或链接，回车发过去', sendTextGo: '加入', kindText: '文字',
    sendEmpty: '把文件拖到窗口里', sendEmptyHint: '也可以点「选择文件…」。手机打开卓传就能取走。',
    fileGone: '文件已不在',
    firstRun: '第一次用', firstRunTitle: '手机扫码装卓传',
    firstRunHint: '装好打开就能连。也可以插数据线走 USB。',
    noPhone: '还没有手机连上来', noPhoneHint: '打开手机 App，搜到这台电脑即可',
    homeNextUsb: '手机已连上，去 USB 选相册。',
    homeNextAllow: '点 USB，页面会停在「允许调试」这一步。',
    homeNextOnline: '手机已在线，打开图库看刚传过来的。',
    homeNextWifi: 'Wi-Fi 已就绪。手机打开卓传会自己连。',
    homeNextIdle: '点 USB，页面只说你现在该做的那一步。',
    openThisPhone: '打开这台手机的图库', histTitle: '图库', clear: '清空',
    noHist: '图库还是空的', noHistHint: '从 USB 或 Wi-Fi 传过来，就会出现在这里。',
    unauth: '设备未授权 USB 调试', offline: '未连接设备',
    recv: '正在接收', got: '已收到', photos: '张', openGallery: '打开图库', reveal: '在访达中显示', forget: '从图库移除记录',
    copyPath: '复制路径', delBatch: '删除这一批', copyAddr: '复制本机地址', goWifi: '打开 Wi-Fi', goHist: '打开图库',
    seeGallery: '查看', backAlbums: '← 返回相册',
    toPhotos: '导入照片',
    copyName: '复制名称', copyFile: '复制文件名',
    avgSpeed: '均速',
    chipToday: '今天', chipWeek: '近 7 天', chipCamera: '整个相机',
    pause: '暂停', resume: '继续', stop: '停止', paused: '已暂停', stopping: '正在停止…',
    wizWifi: '搞不定？改用 Wi-Fi 传',
    retryFailed: '重试失败的', deviceLost: '手机断开了',
    xferN: '传输 {n} 张',
  },
  en: {
    navHome: 'Home', navHist: 'Gallery',
    homeTitle: 'Move photos from your phone to this Mac',
    usbTile: 'USB transfer', wifiTile: 'Wi-Fi transfer',
    scan: 'Scan albums', scanning: 'Scanning…', xfer: 'Transfer', waiting: 'Waiting for device', save: 'Save to',
    selAll: 'Select all', selNone: 'Clear selection',
    usbNoAlbum: 'No albums yet', usbScanHint: 'Albums scan automatically. Pick what to send, then transfer.',
    copy: 'Copy', copied: 'Copied', open: 'Open folder', openShort: 'Open',
    wifiTitle: 'Wi-Fi receive', wifiSub: 'The phone finds this Mac by itself.',
    wifiHint: 'Scan this if the app is already installed, or wait for it to appear.',
    localAddr: 'This computer', online: 'Online', batches: 'Recent gallery', seeAll: 'See all',
    noAppYet: 'No app on the phone yet?', apkGet: 'Scan the code above with the camera',
    goneN: '{n} batches are missing their files', goneClean: 'Remove these records', goneShow: 'Show',
    paneRecv: 'Receive', paneSend: 'Send', sendWaiting: 'Waiting for the phone',
    sendHead: 'Send to phone', sendSub: 'Drop files on the window, or paste text. Your phone picks them up.',
    pairManage: 'Manage', pairSummary: 'Code {code} · {n} paired', pairSummaryOff: 'Pairing is off',
    hotspotOn: 'On the phone’s hotspot — no router needed',
    hotspotHint: 'No router? Turn on the phone’s hotspot and join it from this computer.',
    pairKicker: 'Pairing code', pairNew: 'New code', pairOff: 'Turn off pairing', pairOn: 'Require pairing',
    pairHint: 'Scanning the code above pairs automatically; or type these six digits.',
    pairOffHint: 'Any device on this network can reach this computer.',
    pairPeers: '{n} paired',
    sendPick: 'Choose files…', clearAll: 'Clear all',
    tookN: '{n} picked up', tookClear: 'Clear',
    sendTextPh: 'Paste text or a link, press Enter', sendTextGo: 'Add', kindText: 'text',
    sendEmpty: 'Drop files onto this window', sendEmptyHint: 'Or use “Choose files…”. Your phone picks them up.',
    fileGone: 'file is gone',
    firstRun: 'First time', firstRunTitle: 'Scan to install the phone app',
    firstRunHint: 'Open it and it finds this computer. USB works too.',
    noPhone: 'No phone yet', noPhoneHint: 'Open the app on your phone and find this computer',
    homeNextUsb: 'Phone connected. Open USB to pick albums.',
    homeNextAllow: 'Open USB. The page will stop on Allow debugging.',
    homeNextOnline: 'Phone is online. Open the gallery for what just arrived.',
    homeNextWifi: 'Wi-Fi is ready. The phone app will connect itself.',
    homeNextIdle: 'Open USB. The page only shows the step you are on.',
    openThisPhone: 'Open this phone’s gallery', histTitle: 'Gallery', clear: 'Clear',
    noHist: 'Gallery is empty', noHistHint: 'Files you send over USB or Wi-Fi show up here.',
    unauth: 'USB debugging not authorized', offline: 'No device',
    recv: 'Receiving', got: 'Received', photos: 'photos', openGallery: 'Open gallery', reveal: 'Reveal in Finder', forget: 'Remove from gallery',
    copyPath: 'Copy path', delBatch: 'Delete batch', copyAddr: 'Copy address', goWifi: 'Open Wi-Fi', goHist: 'Open gallery',
    toPhotos: 'Add to Photos',
    seeGallery: 'View', backAlbums: '← Back to albums',
    copyName: 'Copy name', copyFile: 'Copy filename',
    avgSpeed: 'avg',
    chipToday: 'Today', chipWeek: 'Last 7 days', chipCamera: 'Whole camera',
    pause: 'Pause', resume: 'Resume', stop: 'Stop', paused: 'Paused', stopping: 'Stopping…',
    wizWifi: 'Stuck? Send over Wi-Fi instead',
    retryFailed: 'Retry failed', deviceLost: 'Phone disconnected',
    xferN: 'Transfer {n}',
  },
};

const t = (k) => I18N[state.lang][k] || I18N.zh[k] || k;

function applyLang() {
  document.documentElement.lang = state.lang === 'zh' ? 'zh-CN' : 'en';
  $$('[data-i18n]').forEach((el) => { el.textContent = t(el.dataset.i18n); });
  $$('[data-i18n-ph]').forEach((el) => { el.placeholder = t(el.dataset.i18nPh); });
  $$('[data-i18n-title]').forEach((el) => {
    const label = t(el.dataset.i18nTitle);
    el.title = label;
    el.setAttribute('aria-label', label);
  });
  $('#langBtn').textContent = state.lang === 'zh' ? 'EN' : '中文';
  if ($('#paneSend')) {
    showPane($('#paneSend').classList.contains('hidden') ? 'recv' : 'send');
  }
  updateXferBtn();
}

async function api(path, opts = {}) {
  // 带 body 却没写 method 的调用（扫描、暂停、停止、开始传输…）会退成 GET，
  // fetch 直接抛 "Request with GET/HEAD method cannot have body"，动作静默失效。
  const method = opts.method || (opts.body != null ? 'POST' : 'GET');
  const res = await fetch(path, {
    headers: { 'Content-Type': 'application/json', ...(opts.headers || {}) },
    ...opts,
    method,
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

// 批次是按「什么时候传的」找的，所以标题给时间：今天/昨天用相对说法，更快定位
function batchTitle(id) {
  const m = String(id || '').match(/^(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})$/);
  if (!m) return id || '';
  const zh = state.lang === 'zh';
  const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
  const today = new Date();
  const day = (x) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const diff = Math.round((day(today) - day(d)) / 86400000);
  const hhmm = `${m[4]}:${m[5]}`;
  if (diff === 0) return zh ? `今天 ${hhmm}` : `Today ${hhmm}`;
  if (diff === 1) return zh ? `昨天 ${hhmm}` : `Yesterday ${hhmm}`;
  return zh ? `${Number(m[2])}月${Number(m[3])}日 ${hhmm}` : `${m[2]}-${m[3]} ${hhmm}`;
}

function fmtBytes(n) {
  n = Number(n) || 0;
  if (n <= 0) return '';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(n >= 10 * 1024 * 1024 ? 0 : 1)} MB`;
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

function fmtSpeed(mbps) {
  const n = Number(mbps) || 0;
  if (n < 0.04) return '';
  if (n < 1) return `${(n * 1024).toFixed(0)} KB/s`;
  return `${n.toFixed(1)} MB/s`;
}

function fmtDur(sec) {
  sec = Math.round(Number(sec) || 0);
  if (sec < 1) return '';
  if (sec < 60) return state.lang === 'zh' ? `${sec}秒` : `${sec}s`;
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  if (m < 60) {
    if (state.lang === 'zh') return s ? `${m}分${s}秒` : `${m}分钟`;
    return s ? `${m}m ${s}s` : `${m}m`;
  }
  const h = Math.floor(m / 60);
  const mm = m % 60;
  if (state.lang === 'zh') return mm ? `${h}小时${mm}分` : `${h}小时`;
  return mm ? `${h}h ${mm}m` : `${h}h`;
}

function fmtEta(sec) {
  const d = fmtDur(sec);
  if (!d) return '';
  return state.lang === 'zh' ? `还剩 ${d}` : `${d} left`;
}

function paceLine(st) {
  const parts = [];
  const speed = fmtSpeed(st.speed_mbps);
  if (speed) parts.push(speed);
  const eta = fmtEta(st.eta_sec);
  if (eta) parts.push(eta);
  const size = fmtBytes(st.bytes_done);
  const total = fmtBytes(st.bytes_total);
  if (size && total) parts.push(`${size} / ${total}`);
  else if (size) parts.push(size);
  return parts.join('  ·  ');
}

// 全站空态统一走这一个样式：居中、图标在上、一行说明。
// 之前有的居中、有的缩在左上角，像是出错了而不是「还没有内容」。
function emptyHTML(icon, title, hint) {
  return `<div class="empty empty-go">${icon}<div><div>${esc(title)}</div>${hint ? `<small>${esc(hint)}</small>` : ''}</div></div>`;
}

const I_PHONE = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="7" y="3" width="10" height="18" rx="2"/><path d="M11 18h2"/></svg>';
const I_USB = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M8 11v5a4 4 0 0 0 8 0v-5"/><path d="M12 4.5v12"/><path d="M9.2 7.5h5.6"/><circle cx="12" cy="4.2" r="1.35" fill="currentColor" stroke="none"/></svg>';
const I_OK = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M5 12l5 5L20 7"/></svg>';
const I_DEVICE = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><rect x="8" y="2.5" width="8" height="14" rx="1.6"/><path d="M10 18.5h4"/><path d="M7 21h10"/></svg>';
const I_STACK = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M4 8l8-4 8 4-8 4-8-4z"/><path d="M4 12l8 4 8-4"/><path d="M4 16l8 4 8-4"/></svg>';
const I_UP = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" style="width:22px;height:22px;flex-shrink:0"><path d="M12 16V4"/><path d="M8 8l4-4 4 4"/><path d="M4 16v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3"/></svg>';
const I_CHECK = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>';
const I_FILM = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><rect x="3.5" y="5" width="17" height="14" rx="2"/><path d="M8 5v14M16 5v14M3.5 9h17M3.5 15h17"/></svg>';

const BRANDS = [
  { id: 'xiaomi', zh: '小米 / Redmi', en: 'Xiaomi', color: '#FF6900' },
  { id: 'huawei', zh: '华为 / 荣耀', en: 'Huawei', color: '#CF0A2C' },
  { id: 'oppo', zh: 'OPPO / 一加 / realme', en: 'OPPO', color: '#006B54' },
  { id: 'vivo', zh: 'vivo / iQOO', en: 'vivo', color: '#415FFF' },
  { id: 'samsung', zh: '三星', en: 'Samsung', color: '#1428A0' },
  { id: 'google', zh: 'Pixel', en: 'Pixel', color: '#4285F4' },
  { id: 'generic', zh: '其他安卓', en: 'Other', color: '#3DDC84' },
];

const BRAND_LOGO = {
  xiaomi: '<svg viewBox="0 0 32 32"><rect width="32" height="32" rx="8" fill="#FF6900"/><path d="M8 10h4.2v12H8V10zm5.3 0H21c1.9 0 3.2 1.3 3.2 3.2v5.6c0 1.9-1.3 3.2-3.2 3.2h-7.5V10zm4.2 3.4v5.2H21c.5 0 .8-.3.8-.8v-3.6c0-.5-.3-.8-.8-.8h-3.5z" fill="#fff"/></svg>',
  huawei: '<svg viewBox="0 0 32 32"><rect width="32" height="32" rx="8" fill="#CF0A2C"/><path d="M16 7l2.4 5.2 5.6.6-4.2 3.8 1.2 5.5L16 19.6 11 22.1l1.2-5.5-4.2-3.8 5.6-.6L16 7z" fill="#fff"/></svg>',
  oppo: '<svg viewBox="0 0 32 32"><rect width="32" height="32" rx="8" fill="#006B54"/><rect x="7" y="12" width="18" height="8" rx="4" fill="none" stroke="#fff" stroke-width="2.2"/></svg>',
  vivo: '<svg viewBox="0 0 32 32"><rect width="32" height="32" rx="8" fill="#415FFF"/><path d="M8 11l8 11 8-11h-3.4L16 18.2 11.4 11H8z" fill="#fff"/></svg>',
  samsung: '<svg viewBox="0 0 32 32"><rect width="32" height="32" rx="8" fill="#1428A0"/><rect x="6.5" y="12" width="19" height="8" rx="4" fill="none" stroke="#fff" stroke-width="2"/><path d="M12 16h8" stroke="#fff" stroke-width="2" stroke-linecap="round"/></svg>',
  google: '<svg viewBox="0 0 32 32"><rect width="32" height="32" rx="8" fill="#fff"/><path d="M16.7 16.9v-2.7h7.3c.2.8.3 1.7.3 2.7 0 4.6-3.1 7.9-7.8 7.9A8 8 0 1 1 16.7 8c2.2 0 4 .8 5.4 2.1l-2.2 2.1c-.8-.7-1.9-1.2-3.2-1.2a4.9 4.9 0 1 0 0 9.8c2.5 0 3.7-1.4 4.1-2.2h-4.1v-2.8h.7z" fill="#4285F4"/></svg>',
  generic: '<svg viewBox="0 0 32 32"><rect width="32" height="32" rx="8" fill="#3DDC84"/><path d="M11 9.5 9.4 7.2 10.6 6l2 2.4a8 8 0 0 1 6.8 0L21.4 6l1.2 1.2-1.6 2.3A7.5 7.5 0 0 1 23.5 16v5.2a2 2 0 0 1-2 2H20v3h-2.5v-3h-3v3H12v-3h-1.5a2 2 0 0 1-2-2V16c0-2.5.9-4.7 2.5-6.5zM12.8 13.2a1 1 0 1 0 0 2 1 1 0 0 0 0-2zm6.4 0a1 1 0 1 0 0 2 1 1 0 0 0 0-2z" fill="#053218"/></svg>',
};

const APP_LOGO = '<svg viewBox="0 0 1024 1024"><path d="M0 0m512 0l0 0q512 0 512 512l0 0q0 512-512 512l0 0q-512 0-512-512l0 0q0-512 512-512Z" fill="#4C8DFF"/><path d="M260.654545 620.897745a37.236364 37.236364 0 1 1-74.472727-1.340509l3.165091-175.476363a186.181818 186.181818 0 0 1 186.144582-182.830546H679.005091a37.236364 37.236364 0 1 1 0 74.472728H375.491491a111.709091 111.709091 0 0 0-111.709091 109.698327L260.654545 620.897745z" fill="#fff"/><path d="M697.455709 257.805964a27.927273 27.927273 0 1 1-33.214836 44.907054l-122.842764-90.875345a27.927273 27.927273 0 0 1 33.214836-44.907055l122.842764 90.875346z" fill="#fff"/><path d="M675.579345 277.355055a27.927273 27.927273 0 1 1 35.355928 43.250036l-142.391855 116.363636a27.927273 27.927273 0 0 1-35.337309-43.250036l142.373236-116.363636z" fill="#fff"/><path d="M800.581818 403.102255a37.236364 37.236364 0 1 1 74.472727 1.340509l-3.16509 175.476363a186.181818 186.181818 0 0 1-186.144582 182.830546H382.231273a37.236364 37.236364 0 1 1 0-74.472728h303.532218a111.709091 111.709091 0 0 0 111.709091-109.698327L800.581818 403.102255z" fill="#fff"/><path d="M363.780655 766.194036a27.927273 27.927273 0 1 1 33.214836-44.907054l122.842764 90.875345a27.927273 27.927273 0 0 1-33.214837 44.907055l-122.842763-90.875346z" fill="#fff"/><path d="M385.657018 746.644945a27.927273 27.927273 0 1 1-35.355927-43.250036l142.391854-116.363636a27.927273 27.927273 0 0 1 35.33731 43.250036l-142.373237 116.363636z" fill="#fff"/></svg>';

function brandOf(id) {
  return BRANDS.find((b) => b.id === id) || null;
}

function brandName(id) {
  const b = brandOf(id);
  if (!b) return '';
  return state.lang === 'zh' ? b.zh : b.en;
}

function phoneFrame(inner) {
  return `<svg class="phone-svg" viewBox="0 0 72 112" fill="none" aria-hidden="true">
    <rect x="14" y="8" width="44" height="88" rx="10" fill="#16181f" stroke="rgba(255,255,255,0.22)" stroke-width="1.7"/>
    <rect x="30" y="13" width="12" height="3" rx="1.5" fill="rgba(255,255,255,0.22)"/>
    ${inner}
    <rect x="32" y="86" width="8" height="3" rx="1.5" fill="rgba(255,255,255,0.16)"/>
  </svg>`;
}

function sceneHTML(scene) {
  if (scene === 'brand') {
    return `<div class="scene-art scene-pick">
      <span class="scene-app">${APP_LOGO}</span>
      <div class="scene-brands">${BRANDS.map((b) => `<span style="background:${b.color}"></span>`).join('')}</div>
    </div>`;
  }
  if (scene === 'dev') {
    return `<div class="scene-art">${phoneFrame(`
      <g class="tap-ring" transform="translate(36 48)">
        <circle r="16" stroke="rgba(76,141,255,0.28)" stroke-width="1.4"/>
        <circle r="22" class="tap-pulse" stroke="rgba(76,141,255,0.45)" stroke-width="1.2"/>
        <circle r="7" fill="rgba(76,141,255,0.18)" stroke="#4C8DFF" stroke-width="1.8"/>
        <text y="4" text-anchor="middle" fill="#4C8DFF" font-size="9" font-weight="700" font-family="-apple-system,sans-serif">7</text>
      </g>
    `)}</div>`;
  }
  if (scene === 'debug') {
    return `<div class="scene-art">${phoneFrame(`
      <rect x="24" y="40" width="24" height="14" rx="7" fill="#4C8DFF"/>
      <circle cx="41" cy="47" r="5.2" fill="#fff"/>
      <path d="M26 64h20" stroke="rgba(255,255,255,0.18)" stroke-width="2.2" stroke-linecap="round"/>
      <path d="M28 70h16" stroke="rgba(255,255,255,0.1)" stroke-width="2.2" stroke-linecap="round"/>
    `)}</div>`;
  }
  if (scene === 'cable') {
    return `<div class="scene-art scene-link">
      <svg class="mac-svg" viewBox="0 0 56 40" fill="none"><rect x="4" y="4" width="48" height="28" rx="4" fill="#16181f" stroke="rgba(255,255,255,0.22)" stroke-width="1.6"/><rect x="10" y="10" width="36" height="16" rx="2" fill="rgba(76,141,255,0.18)"/><path d="M18 36h20" stroke="rgba(255,255,255,0.35)" stroke-width="2.4" stroke-linecap="round"/></svg>
      <span class="cable"><i></i></span>
      ${phoneFrame('<rect x="24" y="36" width="24" height="28" rx="3" fill="rgba(76,141,255,0.16)"/>')}
    </div>`;
  }
  if (scene === 'allow') {
    return `<div class="scene-art">${phoneFrame(`
      <rect x="22" y="32" width="28" height="36" rx="5" fill="rgba(255,255,255,0.08)" stroke="rgba(255,255,255,0.16)"/>
      <rect x="26" y="50" width="20" height="8" rx="4" fill="#4C8DFF"/>
      <path d="M28 40h16M28 45h10" stroke="rgba(255,255,255,0.35)" stroke-width="1.6" stroke-linecap="round"/>
    `)}<span class="ring"></span><span class="ring delay"></span></div>`;
  }
  if (scene === 'mtp') {
    return `<div class="scene-art">${phoneFrame(`
      <rect x="22" y="28" width="28" height="10" rx="2" fill="rgba(255,211,106,0.22)"/>
      <rect x="26" y="48" width="20" height="16" rx="3" fill="none" stroke="#4C8DFF" stroke-width="1.8"/>
      <path d="M32 56h8M36 52v8" stroke="#4C8DFF" stroke-width="1.8" stroke-linecap="round"/>
    `)}</div>`;
  }
  if (scene === 'offline') {
    return `<div class="scene-art scene-link">
      <svg class="mac-svg" viewBox="0 0 56 40" fill="none"><rect x="4" y="4" width="48" height="28" rx="4" fill="#16181f" stroke="rgba(255,255,255,0.22)" stroke-width="1.6"/></svg>
      <span class="cable broken"><i></i></span>
      ${phoneFrame('')}
    </div>`;
  }
  if (scene === 'done') {
    return `<div class="scene-art scene-ok"><span class="ok-ring"></span><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M5 12l5 5L20 7"/></svg></div>`;
  }
  return `<div class="scene-art scene-wait"><span class="ring"></span><span class="ring delay"></span></div>`;
}

function liveCopy(kind) {
  const zh = state.lang === 'zh';
  if (kind === 'done') return zh ? '已连上' : 'Connected';
  if (kind === 'allow') return zh ? '等你在手机上点允许' : 'Waiting for Allow on the phone';
  if (kind === 'mtp') return zh ? '改成传输文件即可' : 'Switch to File transfer';
  if (kind === 'offline') return zh ? '线掉了，请再插一次' : 'Cable dropped, plug in again';
  if (kind === 'brand') return zh ? '选好品牌后开始听线' : 'Pick a brand, then I’ll listen';
  if (kind === 'adb') return zh ? 'USB 工具还没就绪' : 'USB tools not ready';
  return zh ? '插上线会自己往下跳' : 'I’ll jump when you plug in';
}

let guideBrand = localStorage.getItem('droidtrans.brand') || '';
let lastGuide = { dev: null, wifiN: 0 };
let wizardOpen = false;
let setupIdx = 0;
let wizardTimer = 0;
let wizardKind = '';
let lastUsbCode = '';
let wizardPaintKey = '';

function isVideoPath(p) {
  return /\.(mp4|mov|m4v|mkv|webm|avi|3gp)$/i.test(p || '');
}

function usbCodeOf(dev) {
  if (dev?.usb_code) return dev.usb_code;
  if (dev?.connected) return 'ready';
  if (dev?.unauthorized_devices?.length) return 'unauthorized';
  return 'no_device';
}

function setupSeq(brand) {
  const zh = state.lang === 'zh';
  const tap = {
    xiaomi: zh
      ? ['打开设置 → 我的设备 → 全部参数', '连点「MIUI / 澎湃版本」7 次，直到提示已成为开发者']
      : ['Open Settings → My device → All specs', 'Tap MIUI / HyperOS version 7 times until it says you are a developer'],
    huawei: zh
      ? ['打开设置 → 关于手机', '连点「版本号」7 次，直到提示已成为开发者']
      : ['Open Settings → About phone', 'Tap Build number 7 times until it says you are a developer'],
    oppo: zh
      ? ['打开设置 → 关于本机', '连点「版本号」7 次，直到提示已成为开发者']
      : ['Open Settings → About phone', 'Tap Version 7 times until it says you are a developer'],
    vivo: zh
      ? ['打开设置 → 关于手机', '连点「软件版本号」7 次，直到提示已成为开发者']
      : ['Open Settings → About phone', 'Tap Software version 7 times until it says you are a developer'],
    samsung: zh
      ? ['打开设置 → 关于手机 → 软件信息', '连点「编译编号」7 次，直到提示已成为开发者']
      : ['Open Settings → About phone → Software information', 'Tap Build number 7 times until it says you are a developer'],
    google: zh
      ? ['打开设置 → 关于手机', '连点「编译编号」7 次，直到提示已成为开发者']
      : ['Open Settings → About phone', 'Tap Build number 7 times until it says you are a developer'],
    generic: zh
      ? ['打开设置 → 关于手机', '连点「版本号」7 次，直到提示已成为开发者']
      : ['Open Settings → About phone', 'Tap Build number 7 times until it says you are a developer'],
  }[brand] || (zh
    ? ['打开设置 → 关于手机', '连点「版本号」7 次，直到提示已成为开发者']
    : ['Open Settings → About phone', 'Tap Build number 7 times']);
  const debug = {
    xiaomi: zh
      ? ['返回 设置 → 更多设置 → 开发者选项', '打开「USB 调试」，以及「USB 调试（安全设置）」']
      : ['Go to Settings → Additional settings → Developer options', 'Turn on USB debugging and USB debugging (Security settings)'],
    huawei: zh
      ? ['打开 设置 → 系统和更新 → 开发人员选项', '打开 USB 调试，并打开「仅充电时允许 ADB 调试」']
      : ['Open Settings → System & updates → Developer options', 'Enable USB debugging and ADB in charge only mode'],
    oppo: zh
      ? ['打开 设置 → 系统和更新 → 开发者选项', '打开 USB 调试，并关掉「权限监控」']
      : ['Open Settings → System → Developer options', 'Enable USB debugging and turn off permission monitoring'],
    vivo: zh
      ? ['打开开发者选项（设置或 i 管家里）', '打开 USB 调试']
      : ['Open Developer options', 'Turn on USB debugging'],
    samsung: zh
      ? ['打开 设置 → 开发者选项', '打开 USB 调试']
      : ['Open Settings → Developer options', 'Turn on USB debugging'],
    google: zh
      ? ['打开 设置 → 系统 → 开发者选项', '打开 USB 调试']
      : ['Open Settings → System → Developer options', 'Turn on USB debugging'],
    generic: zh
      ? ['返回设置 → 系统 → 开发者选项', '打开 USB 调试']
      : ['Go back to Settings → System → Developer options', 'Turn on USB debugging'],
  }[brand] || (zh ? ['打开开发者选项', '打开 USB 调试'] : ['Open Developer options', 'Turn on USB debugging']);
  return zh ? [
    { title: '先告诉我手机品牌', scene: 'brand' },
    { title: '去打开开发者模式', lines: tap, scene: 'dev' },
    { title: '打开 USB 调试', lines: debug, scene: 'debug' },
    { title: '用原装线插上这台 Mac', lines: ['插好后下拉通知栏，USB 用途改成「传输文件 / MTP」', '不要停在仅充电。插上我会自己接着检测。'], scene: 'cable' },
  ] : [
    { title: 'Pick your phone brand', scene: 'brand' },
    { title: 'Turn on Developer options', lines: tap, scene: 'dev' },
    { title: 'Turn on USB debugging', lines: debug, scene: 'debug' },
    { title: 'Plug into this Mac', lines: ['Use the original cable. Set USB to File transfer, not Charge only.', 'I’ll detect the phone when it appears.'], scene: 'cable' },
  ];
}

function wizardState(dev) {
  const zh = state.lang === 'zh';
  const code = usbCodeOf(dev);
  const seq = setupSeq(guideBrand || 'generic');
  const total = 5;
  if (code === 'ready') {
    const who = (dev?.model || '').trim();
    return {
      scene: 'done', i: 4, n: total,
      kicker: '5 / 5',
      title: zh ? '好了，手机已经连上' : 'The phone is connected',
      lines: who
        ? [zh ? `现在是 ${who}` : `Detected ${who}`, zh ? '可以去选今天的照片或视频。' : 'You can pick today’s photos.']
        : [zh ? '可以去选今天的照片或视频。' : 'You can pick today’s photos.'],
      primary: zh ? '去选照片' : 'Pick photos',
      secondary: '',
    };
  }
  if (code === 'unauthorized') {
    return {
      scene: 'allow', i: 3, n: total,
      kicker: '4 / 5',
      title: zh ? '看手机，点允许' : 'Tap Allow on the phone',
      lines: zh
        ? ['线已经通了，手机会弹出「允许 USB 调试」', '点允许，并勾选始终允许这台电脑。我在这里等。']
        : ['The cable is working. Tap Allow USB debugging.', 'Always allow this computer. I’ll wait here.'],
      primary: zh ? '我点过了，再检测' : 'I allowed it, check again',
      secondary: zh ? '没弹窗' : 'No prompt',
    };
  }
  if (code === 'no_storage') {
    return {
      scene: 'mtp', i: 3, n: total,
      kicker: '4 / 5',
      title: zh ? '改成传输文件' : 'Switch to File transfer',
      lines: zh
        ? ['下拉通知栏，USB 用途选「传输文件 / MTP」', '小米还要开「USB 调试（安全设置）」，华为打开「仅充电时允许 ADB」。']
        : ['Set USB to File transfer / MTP in the notification shade.', 'Xiaomi also needs USB debugging (Security settings).'],
      primary: zh ? '我改好了，再检测' : 'I switched it, check again',
      secondary: '',
    };
  }
  if (code === 'offline') {
    return {
      scene: 'offline', i: 3, n: total,
      kicker: '4 / 5',
      title: zh ? '重新插一下线' : 'Plug the cable in again',
      lines: zh
        ? ['换原装线，或换 Mac 上另一个口', '通知栏不要停在仅充电。']
        : ['Try the original cable or another port.', 'Don’t leave USB on Charge only.'],
      primary: zh ? '我插好了，再检测' : 'Reconnected, check again',
      secondary: zh ? '重启 ADB' : 'Restart ADB',
    };
  }
  if (code === 'adb_missing' || code === 'adb_error') {
    return {
      scene: 'adb', i: 0, n: total,
      kicker: zh ? '电脑' : 'This Mac',
      title: zh ? 'USB 工具还没就绪' : 'USB tools are not ready',
      lines: zh
        ? ['找不到 adb。点重启试试', '仍不行就关掉卓传再开一次。']
        : ['adb was not found. Restart it.', 'Or quit DroidTrans and open it again.'],
      primary: zh ? '重启 ADB' : 'Restart ADB',
      secondary: '',
    };
  }
  if (!guideBrand) {
    return {
      scene: 'brand', i: 0, n: total,
      kicker: '1 / 5',
      title: seq[0].title,
      lines: zh ? ['选对了，后面每一步只说这一家的路径。'] : ['Pick the brand so the next step matches your phone.'],
      primary: '',
      secondary: '',
      brands: true,
    };
  }
  const steps = setupSeq(guideBrand).slice(1);
  const i = Math.min(Math.max(setupIdx, 0), steps.length - 1);
  const cur = steps[i];
  return { scene: cur.scene || 'dev', i: i + 1, n: total,
    kicker: `${i + 2} / 5`,
    title: cur.title,
    lines: cur.lines || [],
    primary: zh ? (i === steps.length - 1 ? '我插好了，开始检测' : '做好了，下一步') : (i === steps.length - 1 ? 'Plugged in, detect' : 'Done, next step'),
    secondary: i > 0 ? (zh ? '上一步' : 'Back') : '',
  };
}

function setText(el, text) {
  if (!el || el.textContent === text) return;
  el.textContent = text;
}

function setHTML(el, html) {
  if (!el || el._html === html) return;
  el._html = html;
  el.innerHTML = html;
}

function paintWizard(force) {
  if (!wizardOpen) return;
  const st = wizardState(lastGuide.dev || {});
  if (st.kind === 'done') {
    hideGuidePanel();
    return;
  }
  const live = liveCopy(st.kind);
  const key = [st.kind, st.scene, st.i, guideBrand, st.title, live, st.primary, st.secondary, (st.lines || []).join('\n'), st.brands ? '1' : ''].join('|');
  if (!force && key === wizardPaintKey) return;
  wizardPaintKey = key;
  const jumped = wizardKind && wizardKind !== st.kind;
  wizardKind = st.kind;
  const card = $('#usbGuide');
  if (card) card.dataset.kind = st.kind;
  setText($('#wizKicker'), st.kicker);
  setText($('#wizTitle'), st.title);
  const stepsEl = $('#wizSteps');
  const lines = st.lines || [];
  let stepsHTML = '';
  if (lines.length && st.brands) {
    stepsHTML = `<li class="note"><span>${esc(lines[0])}</span></li>`;
  } else if (lines.length) {
    stepsHTML = lines.map((line, i) => `<li><i>${i + 1}</i><span>${esc(line)}</span></li>`).join('');
  }
  if (stepsHTML) {
    stepsEl.classList.remove('hidden');
    stepsEl.classList.toggle('note-only', !!st.brands);
    setHTML(stepsEl, stepsHTML);
  } else {
    stepsEl.classList.add('hidden');
    setHTML(stepsEl, '');
  }
  const fill = $('#wizFill');
  if (fill) fill.style.width = `${Math.round(((st.i + 1) / st.n) * 100)}%`;
  const brandEl = $('#wizBrand');
  const b = brandOf(guideBrand);
  const canSwap = st.kind === 'setup' || st.kind === 'allow' || st.kind === 'mtp' || st.kind === 'offline';
  if (b) {
    setHTML(brandEl, `<span class="brand-logo">${BRAND_LOGO[b.id]}</span><span class="wiz-brand-meta"><b>${esc(brandName(b.id))}</b><small>${canSwap ? (state.lang === 'zh' ? '更换品牌' : 'Change brand') : 'DroidTrans'}</small></span>`);
    brandEl.classList.toggle('swap', canSwap);
  } else {
    setHTML(brandEl, `<span class="wiz-app-logo">${APP_LOGO}</span><span class="wiz-brand-meta"><b>DroidTrans</b><small>${state.lang === 'zh' ? 'USB 引导' : 'USB setup'}</small></span>`);
    brandEl.classList.remove('swap');
  }
  const scene = $('#wizScene');
  const sceneKey = `${st.scene || st.kind}:${guideBrand || ''}`;
  if (scene && scene.dataset.key !== sceneKey) {
    scene.dataset.key = sceneKey;
    scene.innerHTML = sceneHTML(st.scene || st.kind);
  }
  const liveEl = $('#wizLive');
  if (liveEl) liveEl.classList.toggle('wait', st.kind === 'allow' || st.kind === 'mtp' || st.kind === 'offline');
  const icon = st.kind === 'allow' ? I_PHONE : I_USB;
  setHTML($('#wizLiveIcon'), icon);
  setText($('#wizLiveText'), live);
  const brands = $('#wizBrands');
  if (st.brands) {
    brands.classList.remove('hidden');
    setHTML(brands, BRANDS.map((item) => `<button type="button" class="brand-tile${item.id === guideBrand ? ' on' : ''}" data-brand="${item.id}"><span class="brand-logo">${BRAND_LOGO[item.id]}</span><span>${esc(state.lang === 'zh' ? item.zh : item.en)}</span></button>`).join(''));
  } else {
    brands.classList.add('hidden');
    setHTML(brands, '');
  }
  const primary = $('#wizPrimary');
  const secondary = $('#wizSecondary');
  if (st.primary) {
    primary.classList.remove('hidden');
    setText(primary, st.primary);
  } else {
    primary.classList.add('hidden');
  }
  if (st.secondary) {
    secondary.classList.remove('hidden');
    setText(secondary, st.secondary);
  } else {
    secondary.classList.add('hidden');
  }
  const actions = $('#usbGuide .wiz-actions');
  if (actions) actions.classList.toggle('hidden', !st.primary && !st.secondary);
  if (jumped) flashDetect(live);
}

function flashDetect(msg) {
  const el = $('#wizFlash');
  if (!el || !msg) return;
  el.textContent = msg;
  el.classList.remove('hidden');
  el.classList.remove('in');
  void el.offsetWidth;
  el.classList.add('in');
  clearTimeout(el._hide);
  el._hide = setTimeout(() => el.classList.add('hidden'), 1600);
}

async function probeUsb() {
  if (state.view !== 'usb') return;
  const dev = await api('/api/device_status');
  lastGuide.dev = dev;
  const code = usbCodeOf(dev);
  if (dev?.brand && dev.brand !== 'generic' && !guideBrand) {
    guideBrand = dev.brand;
    localStorage.setItem('droidtrans.brand', guideBrand);
  }
  const jumped = lastUsbCode && lastUsbCode !== code;
  lastUsbCode = code;
  if (code === 'ready') {
    if (wizardOpen) {
      hideGuidePanel();
      await refreshUsb();
    }
    return;
  }
  if (jumped && wizardOpen) {
    const zh = state.lang === 'zh';
    const note = {
      unauthorized: zh ? '检测到手机，等你点允许' : 'Phone detected, tap Allow',
      no_storage: zh ? '连上了，还要改成传输文件' : 'Connected, switch to File transfer',
      offline: zh ? '线又掉了' : 'The cable dropped',
    }[code];
    if (note) flashDetect(note);
  }
  if (!wizardOpen) startGuide();
  else paintWizard();
}

function hideGuidePanel() {
  wizardOpen = false;
  wizardPaintKey = '';
  wizardKind = '';
  const panel = $('#usbGuide');
  if (panel) panel.classList.add('hidden');
  $('#view-usb')?.classList.remove('guiding');
}

function armUsbWatch() {
  if (wizardTimer) return;
  wizardTimer = setInterval(probeUsb, 2500);
}

function disarmUsbWatch() {
  clearInterval(wizardTimer);
  wizardTimer = 0;
  hideGuidePanel();
}

function startGuide() {
  const panel = $('#usbGuide');
  if (panel) panel.classList.remove('hidden');
  $('#view-usb')?.classList.add('guiding');
  // 引导起来时把相册区一并收掉：设备中途掉线时 probeUsb 会直接叫起引导，
  // 之前留下的相册网格和「还没有相册」空态会跟引导卡叠在一页上。
  ['#albumGrid', '#photoGrid', '#photoBack', '#usbChips', '#usbEmpty'].forEach((sel) => {
    $(sel)?.classList.add('hidden');
  });
  if (!wizardOpen) {
    lastUsbCode = lastUsbCode || '';
    if (lastGuide.dev?.brand && lastGuide.dev.brand !== 'generic' && !localStorage.getItem('droidtrans.brand')) {
      guideBrand = lastGuide.dev.brand;
    }
    if (usbCodeOf(lastGuide.dev) === 'no_device' && !guideBrand) setupIdx = 0;
  }
  wizardOpen = true;
  armUsbWatch();
  paintWizard();
}

function goUsb() {
  show('usb');
}

async function wizardPrimary() {
  const kind = wizardKind;
  if (kind === 'done') {
    hideGuidePanel();
    refreshUsb();
    return;
  }
  if (kind === 'setup') {
    const steps = setupSeq(guideBrand).slice(1);
    if (setupIdx < steps.length - 1) setupIdx += 1;
    paintWizard();
    await probeUsb();
    return;
  }
  if (kind === 'adb') {
    await api('/api/adb_restart', { body: '{}' });
  }
  await probeUsb();
}

async function wizardSecondary() {
  if (wizardKind === 'setup') {
    if (setupIdx > 0) {
      setupIdx = Math.max(0, setupIdx - 1);
      paintWizard();
      return;
    }
    guideBrand = '';
    localStorage.removeItem('droidtrans.brand');
    setupIdx = 0;
    paintWizard();
    return;
  }
  if (wizardKind === 'offline' || wizardKind === 'adb') {
    await api('/api/adb_restart', { body: '{}' });
    await probeUsb();
    return;
  }
  if (wizardKind === 'allow') {
    setupIdx = setupSeq(guideBrand).slice(1).length - 1;
    paintWizard();
  }
}

document.addEventListener('click', (e) => {
  const brandChip = e.target.closest('#wizBrand.swap');
  if (brandChip) {
    guideBrand = '';
    localStorage.removeItem('droidtrans.brand');
    setupIdx = 0;
    paintWizard();
    return;
  }
  const brandBtn = e.target.closest('#wizBrands [data-brand]');
  if (brandBtn) {
    guideBrand = brandBtn.dataset.brand;
    localStorage.setItem('droidtrans.brand', guideBrand);
    setupIdx = 0;
    paintWizard();
    return;
  }
  const act = e.target.closest('[data-act]');
  if (!act) return;
  if (act.dataset.act === 'apk') show('wifi');
  if (act.dataset.act === 'wizard') show('usb');
  if (act.dataset.act === 'usb') show('usb');
  if (act.dataset.act === 'wifi') show('wifi');
});

function show(view) {
  state.view = view;
  // 后端本来就把 /usb /wifi /history 当页面提供，地址栏跟着走，
  // 直接打开这些路径时才不会莫名其妙回到总览。
  let path = view === 'home' ? '/' : '/' + view;
  if (view === 'wifi' && !$('#paneSend')?.classList.contains('hidden')) {
    path = '/send';
  }
  if (location.pathname !== path) {
    try { history.replaceState(null, '', path); } catch (_) { /* file:// 下忽略 */ }
  }
  $$('.view').forEach((el) => {
    const on = el.id === 'view-' + view;
    el.classList.toggle('active', on);
    el.classList.remove('enter');
    if (on) {
      void el.offsetWidth;
      el.classList.add('enter');
      setTimeout(() => el.classList.remove('enter'), 400);
    }
  });
  $$('nav button').forEach((b) => b.classList.toggle('active', b.dataset.view === view));
  if (view !== 'usb') disarmUsbWatch();
  if (view === 'usb') refreshUsb();
  if (view === 'wifi') refreshWifi();
  if (view === 'history') refreshHistory();
}

$$('nav button').forEach((b) => b.addEventListener('click', () => {
  if (!b.dataset.view) return;
  show(b.dataset.view);
}));
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
    const code = usbCodeOf(dev);
    const usb = code === 'unauthorized' ? t('unauth') : t('offline');
    $('#adbHint').textContent = usb;
    $('#adbHint').title = adb + (dev.usb_code ? ' · ' + dev.usb_code : '');
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
    // 这行本来就是「下一步该干什么」，之前用强调色却点不动，看着像坏掉的链接
    const code = usbCodeOf(dev);
    let go = 'usb';
    if (code === 'unauthorized') next.textContent = t('homeNextAllow');
    else if (dev.connected) next.textContent = t('homeNextUsb');
    else if (n) { next.textContent = t('homeNextOnline'); go = 'history'; }
    else if (wifi.ip) { next.textContent = t('homeNextWifi'); go = 'wifi'; }
    else next.textContent = t('homeNextIdle');
    next.dataset.go = go;
  }
  maybeAutoScan(dev);
  lastGuide = { dev, wifiN: n };
  renderHomeRecent();
}

let homeRecentKey = '';

// 总览页下半屏原来是空的。把最近几批放上来：传完之后「东西在哪」一眼可见。
async function renderHomeRecent() {
  const box = $('#homeRecent');
  if (!box) return;
  const gal = await api('/api/gallery');
  const items = (gal.batches || []).slice(0, 4);
  const start = $('#homeStart');
  if (!items.length) {
    box.classList.add('hidden');
    homeRecentKey = '';
    // 还没有任何记录：把下半屏让给「怎么开始」
    if (start) {
      start.classList.remove('hidden');
      ensureApkUrl().then((url) => renderHomeQR(url));
    }
    return;
  }
  if (start) start.classList.add('hidden');
  box.classList.remove('hidden');
  const key = items.map((b) => `${b.device_id}/${b.batch_id}/${b.photo_count}/${b.cover || ''}`).join('|');
  if (key === homeRecentKey) return;   // 每 4 秒刷新一次，内容没变就别重画，避免缩略图闪
  homeRecentKey = key;
  renderGallery('#homeGallery', items, 4);
}

async function refreshUsb() {
  // 传输条以前只有「本次在界面上点过开始」才出现：切到图库再回来、
  // 或者传输中途设备掉线回到引导，结果就看不见了。只要有状态就显示。
  syncXferBar();
  const dev = await api('/api/device_status');
  state.usbConnected = !!dev.connected;
  // 序列号对用户没意义，放进 title 里备查就行
  const who = (dev.model || '').trim() || dev.selected || '';
  $('#usbDevice').textContent = dev.connected
    ? who
    : (dev.unauthorized_devices?.length ? t('unauth') : t('waiting'));
  $('#usbDevice').title = dev.connected && dev.selected ? `${who} · ${dev.selected}` : '';
  $('#scanBtn').disabled = !dev.connected || usbScanning;
  const result = await api('/api/scan_result');
  lastGuide.dev = dev;
  const ready = usbCodeOf(dev) === 'ready';
  if (state.view === 'usb') armUsbWatch();
  if (state.view === 'usb' && !ready) {
    startGuide();
    $('#albumGrid').classList.add('hidden');
    $('#photoGrid').classList.add('hidden');
    $('#photoBack').classList.add('hidden');
    $('#usbChips').classList.add('hidden');
    $('#usbEmpty').classList.add('hidden');
    return;
  }
  hideGuidePanel();
  $('#albumGrid').classList.remove('hidden');
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
    el.classList.add('hidden');
    return;
  }
  el.innerHTML = emptyHTML(I_STACK, t('usbNoAlbum'), t('usbScanHint'));
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
  const n = state.selectedPhotos.size;
  const albums = state.selectedAlbums.size;
  $('#xferBtn').disabled = albums === 0 && n === 0;
  if (albums === 0 && n > 0) {
    $('#xferBtn').textContent = t('xferN').replace('{n}', String(n));
  } else {
    $('#xferBtn').textContent = t('xfer');
  }
}

function albumRank(path, al) {
  const n = String(al?.name || '').toLowerCase();
  const p = String(path || '').toLowerCase();
  if (n === '相机' || p.includes('/dcim/camera') || n === '100media' || n === '100andro') return 0;
  if (n.includes('截图') || p.includes('screenshot')) return 1;
  if (n.includes('微信') || p.includes('weixin') || p.includes('wechat')) return 2;
  return 10;
}

function setChip(id) {
  ['chipToday', 'chipWeek', 'chipCamera'].forEach((k) => {
    const el = $('#' + k);
    if (el) el.classList.toggle('on', k === id);
  });
}

function showUsbChips(albums) {
  const box = $('#usbChips');
  if (!box) return;
  const hasCam = Object.entries(albums || {}).some(([p, al]) => albumRank(p, al) <= 1);
  box.classList.toggle('hidden', !hasCam);
}

async function applyRecent(range) {
  hidePhotos();
  const data = await api('/api/recent_media?range=' + encodeURIComponent(range));
  const photos = data.photos || [];
  state.selectedAlbums.clear();
  state.selectedPhotos.clear();
  photos.forEach((p) => { if (p.path) state.selectedPhotos.add(p.path); });
  setChip(range === 'week' ? 'chipWeek' : 'chipToday');
  renderAlbums(state.albums);
  updateXferBtn();
  if (photos.length) {
    $('#usbEmpty').classList.add('hidden');
  }
}

function selectCameraAlbums() {
  hidePhotos();
  state.selectedPhotos.clear();
  state.selectedAlbums.clear();
  Object.entries(state.albums).forEach(([p, al]) => {
    if (albumRank(p, al) === 0) state.selectedAlbums.add(p);
  });
  setChip('chipCamera');
  renderAlbums(state.albums);
  updateXferBtn();
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

// 图片取不到时（文件被删/被移走）换成占位块，不要留一个裂图图标
function bindImg(root) {
  (root.querySelectorAll ? root.querySelectorAll('img') : []).forEach((img) => {
    img.addEventListener('error', () => {
      if (img.dataset.fallback) return;
      img.dataset.fallback = '1';
      const ph = document.createElement('div');
      ph.className = img.className ? `${img.className} ph gone` : 'ph gone';
      ph.innerHTML = I_STACK;
      img.replaceWith(ph);
    });
  });
}

// 这个相册里有多少张被单独勾中了
function selectedInAlbum(path) {
  const prefix = String(path).replace(/\/+$/, '') + '/';
  let n = 0;
  state.selectedPhotos.forEach((p) => { if (p.startsWith(prefix)) n += 1; });
  return n;
}

function albumMeta(al, picked, whole) {
  const zh = state.lang === 'zh';
  const total = al.total_count || 0;
  const unit = zh ? '项' : 'items';
  if (whole) return `${total} ${unit} · ${zh ? '全选' : 'all'}`;

  if (picked > 0) return `${total} ${unit} · ${zh ? `已选 ${picked}` : `${picked} picked`}`;
  return `${total} ${unit}`;
}

function renderAlbums(albums) {
  state.albums = albums || {};
  showUsbChips(state.albums);
  const grid = $('#albumGrid');
  grid.innerHTML = '';
  Object.entries(state.albums)
    .sort((a, b) => albumRank(a[0], a[1]) - albumRank(b[0], b[1]) || String(a[1].name).localeCompare(String(b[1].name)))
    .forEach(([path, al]) => {
    const btn = document.createElement('button');
    btn.type = 'button';
    // 按「今天 / 近 7 天」挑出来的是一张张单图，整册并没有被选中。
    // 以前卡片上一点痕迹都没有，按钮却写着「传输 16 张」，用户根本看不出选了什么。
    const picked = selectedInAlbum(path);
    const total = al.total_count || 0;
    // 散选正好覆盖整册时，就按「全选」显示，别让用户看着一横去数数
    const whole = state.selectedAlbums.has(path) || (total > 0 && picked >= total);
    btn.className = 'album' + (whole ? ' on' : (picked > 0 ? ' part' : ''));
    const src = al.cover ? `/api/thumb?path=${encodeURIComponent(al.cover)}` : '';
    // 勾选圈常驻，说明「点一下＝整册选中」；「挑单张」悬停出现，
    // 原来只有双击能进相册，界面上没有任何提示。
    btn.innerHTML = `<span class="thumb">
        <img alt="" src="${src}" />
        <span class="pick" aria-hidden="true">${I_CHECK}</span>
        <span class="peek" data-peek="1">${state.lang === 'zh' ? '挑单张' : 'Pick photos'}</span>
      </span>
      <figcaption><strong>${esc(al.name || path)}</strong><small>${albumMeta(al, picked, whole)}</small></figcaption>`;
    btn.addEventListener('click', (e) => {
      if (e.shiftKey || e.target.closest('[data-peek]')) {
        openAlbum(path);
        return;
      }
      const on = state.selectedAlbums.has(path);
      if (on) {
        state.selectedAlbums.delete(path);
      } else {
        state.selectedAlbums.add(path);
        const prefix = String(path).replace(/\/+$/, '') + '/';
        [...state.selectedPhotos].forEach((p) => { if (p.startsWith(prefix)) state.selectedPhotos.delete(p); });
      }
      setChip('');
      renderAlbums(state.albums);   // 整册与散选互相影响，这里要重算所有卡的状态
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
    const vid = p.video || isVideoPath(p.path || p.name);
    const media = vid
      ? `<span class="vid" aria-hidden="true">${I_FILM}</span>`
      : `<img alt="" src="/api/thumb?path=${encodeURIComponent(p.path)}" />`;
    b.innerHTML = `<span class="thumb">
        ${media}
        <span class="pick" aria-hidden="true">${I_CHECK}</span>
        <span class="peek" data-peek="1">${state.lang === 'zh' ? '看大图' : 'View'}</span>
      </span>
      <figcaption><strong>${esc(p.name)}</strong></figcaption>`;
    b.addEventListener('click', (e) => {
      if (e.target.closest('[data-peek]')) {
        viewerPhotos = state.albumPhotos;
        const idx = viewerPhotos.findIndex((x) => x.path === p.path);
        showLightbox(idx < 0 ? 0 : idx);
        return;
      }
      const on = state.selectedPhotos.has(p.path);
      if (on) state.selectedPhotos.delete(p.path);
      else state.selectedPhotos.add(p.path);
      // 原来每选一张都重新 openAlbum()：走一次 adb 拉全相册再整片重画。
      // 选 20 张就是 20 次设备往返，缩略图跟着闪。这里只切当前这张。
      b.classList.toggle('on', !on);
      updateSelAll();
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
    setChip('');
    renderAlbums(state.albums);
  }
  updateXferBtn();
});

let usbAutoSerial = '';
let usbScanning = false;
let bootScanOk = false;
setTimeout(() => { bootScanOk = true; }, 1200);

async function maybeAutoScan(dev) {
  if (!bootScanOk && state.view !== 'usb') return;
  if (!dev || !dev.connected) {
    usbAutoSerial = '';
    return;
  }
  const serial = dev.selected || '';
  if (!serial || serial === usbAutoSerial || usbScanning) return;
  usbAutoSerial = serial;
  const ok = await startUsbScan({ preset: true });
  if (!ok) usbAutoSerial = '';
}

async function startUsbScan(opts = {}) {
  if (!state.usbConnected || usbScanning) return false;
  usbScanning = true;
  $('#scanBtn').disabled = true;
  $('#scanBtn').classList.add('spinning');
  $('#scanBtn').title = t('scanning');
  hidePhotos();
  const empty = $('#usbEmpty');
  if (empty && !state.currentAlbum) {
    empty.classList.remove('hidden');
    empty.innerHTML = emptyHTML(I_STACK, t('scanning'), t('usbScanHint'));
  }
  const started = await api('/api/scan', { body: '{}' });
  if (started && started.success === false) {
    // 起不来就把状态放回去，别让「扫描中…」和转圈图标永远卡着
    usbScanning = false;
    $('#scanBtn').classList.remove('spinning');
    $('#scanBtn').disabled = !state.usbConnected;
    if (empty) empty.innerHTML = emptyHTML(I_PHONE, started.error || t('usbNoAlbum'), t('usbScanHint'));
    return false;
  }
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
  if (state.view === 'usb' || opts.preset) {
    renderAlbums(result.albums);
    if (err) {
      empty.classList.remove('hidden');
      empty.innerHTML = emptyHTML(I_PHONE, err, t('usbScanHint'));
    } else {
      setUsbEmpty({ connected: state.usbConnected }, result.albums);
    }
  }
  $('#scanBtn').classList.remove('spinning');
  $('#scanBtn').title = t('scan');
  $('#scanBtn').disabled = !state.usbConnected;
  usbScanning = false;
  if (!err && opts.preset) {
    await applyRecent('today');
  }
  return !err;
}

$('#scanBtn').addEventListener('click', () => startUsbScan());
$('#xferPause')?.addEventListener('click', async () => {
  const st = await api('/api/transfer_status');
  await api(st.paused ? '/api/resume_live' : '/api/pause_transfer', { body: '{}' });
  pollXfer();
});

$('#xferStop')?.addEventListener('click', async () => {
  $('#xferText').textContent = t('stopping');
  $('#xferPause').classList.add('hidden');
  $('#xferStop').classList.add('hidden');
  await api('/api/stop_transfer', { body: '{}' });
  pollXfer();
});

let lastFailed = [];

$('#xferRetry')?.addEventListener('click', async () => {
  if (!lastFailed.length) return;
  const photos = lastFailed.slice();
  $('#xferRetry').classList.add('hidden');
  const res = await api('/api/transfer', {
    body: JSON.stringify({ photos, output_dir: state.usbOut || undefined }),
  });
  if (res && res.success) {
    $('#xferBar').classList.remove('hidden');
    pollXfer();
  }
});

$('#wizWifi')?.addEventListener('click', () => show('wifi'));

$('#chipToday')?.addEventListener('click', () => applyRecent('today'));
$('#chipWeek')?.addEventListener('click', () => applyRecent('week'));
$('#chipCamera')?.addEventListener('click', () => selectCameraAlbums());

$('#xferBtn').addEventListener('click', async () => {
  const output_dir = $('#usbOut').value.trim();
  const body = {
    output_dir,
    selection: { albums: [...state.selectedAlbums], singles: [...state.selectedPhotos], exclude: {} },
  };
  const res = await api('/api/transfer', { body: JSON.stringify(body) });
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

let xferBarSynced = false;

async function syncXferBar() {
  if (xferBarSynced) return;
  const st = await api('/api/transfer_status');
  if (!st || !st.total) return;
  xferBarSynced = true;
  $('#xferBar').classList.remove('hidden');
  pollXfer();
}

async function pollXfer() {
  const st = await api('/api/transfer_status');
  const pct = st.percent_completed || 0;
  $('#xferFill').style.width = pct + '%';
  if (st.is_running) {
    $('#xferSee').classList.add('hidden');
    $('#xferImport')?.classList.add('hidden');
    // 传输中必须能停下来：以前这里一个控制都没有，只能退出 App
    $('#xferPause').classList.remove('hidden');
    $('#xferStop').classList.remove('hidden');
    $('#xferPause').textContent = st.paused ? t('resume') : t('pause');
    const pace = paceLine(st);
    const file = st.current_file || '';
    $('#xferText').textContent = st.paused
      ? `${t('paused')}  ·  ${st.current || 0}/${st.total || 0}`
      : `${st.current || 0}/${st.total || 0}${file ? '  ' + file : ''}${pace ? '  ·  ' + pace : ''}`;
    setTimeout(pollXfer, st.paused ? 900 : 400);
    return;
  }
  $('#xferPause').classList.add('hidden');
  $('#xferStop').classList.add('hidden');
  const n = st.completed_count || 0;
  const fail = (st.failed || []).length;
  const bits = [];
  const zh = state.lang === 'zh';
  // 停止过就别说「完成」——那是两回事
  if (st.device_lost) bits.push(`${t('deviceLost')} · ${zh ? `存下 ${n} 张` : `${n} saved`}`);
  else if (st.stopped) bits.push(zh ? `已停止 · 存下 ${n} 张` : `Stopped · ${n} saved`);
  else bits.push(zh ? `完成 ${n} 张` : `Done ${n}`);
  if (fail) bits.push(zh ? `失败 ${fail}` : `failed ${fail}`);
  const size = fmtBytes(st.bytes_done);
  if (size) bits.push(size);
  const dur = fmtDur(st.elapsed_sec);
  if (dur) bits.push(dur);
  const avg = fmtSpeed(st.speed_mbps);
  if (avg) bits.push(`${t('avgSpeed')} ${avg}`);
  $('#xferText').textContent = bits.join('  ·  ');
  $('#xferSee').classList.toggle('hidden', n === 0);
  $('#xferImport')?.classList.toggle('hidden', n === 0);
  // 失败的可以一键重来：插回线再点一下，不用重新挑一遍照片
  lastFailed = (st.failed || []).map((f) => f.path).filter(Boolean);
  $('#xferRetry')?.classList.toggle('hidden', lastFailed.length === 0);
  lastXfer = { device: st.device_id || '', batch: st.batch_id || '', folder: st.output_dir || '' };
}

$('#xferSee').addEventListener('click', () => {
  show('history');
  if (lastXfer.device && lastXfer.batch) {
    openViewer(lastXfer.device, lastXfer.batch, lastXfer.folder);
  }
});
$('#xferImport')?.addEventListener('click', () => importToPhotos(lastXfer.folder));

function importToPhotos(path) {
  if (!path) return;
  api('/api/import_photos', { body: JSON.stringify({ folder_path: path }) });
}

function fileURL(p) {
  return `/api/thumb?path=${encodeURIComponent(p)}`;
}

function renderGoneBar(n) {
  const bar = $('#goneBar');
  if (!bar) return;
  bar.classList.toggle('hidden', n === 0);
  if (n === 0) return;
  $('#goneText').textContent = t('goneN').replace('{n}', String(n));
  $('#goneShow').textContent = showGone ? t('close') || '收起' : t('goneShow');
}

$('#goneShow')?.addEventListener('click', () => {
  showGone = !showGone;
  refreshHistory();
});

$('#goneClean')?.addEventListener('click', async () => {
  const res = await api('/api/history/prune_missing', { body: '{}' });
  if (res && res.success) {
    showGone = false;
    homeRecentKey = '';
    refreshHistory();
    renderHomeRecent();
  }
});

// 只把记录从图库里去掉，磁盘上的文件不动
async function forgetBatch(device, batch) {
  await api('/api/history/forget', {
    body: JSON.stringify({ device_id: device, batch_id: batch }),
  });
  homeRecentKey = '';
  refreshHistory();
  renderHomeRecent();
}

let showGone = false;

function renderGallery(target, batches, limit) {
  const el = $(target);
  let all = batches || [];
  if (target === '#histList') {
    // 文件已经不在磁盘上的批次，默认不占位置：东西早就没了，看也没得看
    const gone = all.filter((b) => b.missing);
    if (!showGone) all = all.filter((b) => !b.missing);
    renderGoneBar(gone.length);
  }
  const items = all.slice(0, limit || 48);
  if (target === '#histList') {
    $('#clearHist')?.classList.toggle('hidden', items.length === 0);
  }
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
    const sz = fmtBytes(b.total_size);
    const dur = fmtDur(b.duration_sec);
    let avg = '';
    if (b.duration_sec > 0 && b.total_size > 0) {
      avg = fmtSpeed((b.total_size / 1024 / 1024) / b.duration_sec) || '';
    }
    // 卡片一行放得下三项就够：张数、大小、哪台手机。
    // 耗时和均速塞进去只会被省略号吃掉，放到悬停提示里。
    const extra = [`${b.photo_count || 0} ${t('photos')}`];
    if (sz) extra.push(sz);
    extra.push(name);
    const tip = [batchTitle(b.batch_id), `${b.photo_count || 0} ${t('photos')}`, sz, dur, avg, name]
      .filter(Boolean).join('  ·  ');
    const gone = !!b.missing;
    // 放在最前面：这行会被省略号截断，最该看到的是「文件已不在」
    if (gone) extra.unshift(state.lang === 'zh' ? '文件已不在' : 'files missing');
    return `<button type="button" class="shot${gone ? ' gone-batch' : ''}" title="${esc(tip)}"
      data-device="${esc(b.device_id)}" data-batch="${esc(b.batch_id)}" data-folder="${esc(b.folder || '')}" data-name="${esc(name)}" data-missing="${gone ? '1' : ''}">
      <span class="thumb">
        ${cover}
        <span class="count">${b.photo_count || 0} ${t('photos')}</span>
      </span>
      <figcaption><strong>${esc(batchTitle(b.batch_id))}</strong><small>${esc(extra.join(' · '))}</small></figcaption>
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
        { label: t('toPhotos'), act: () => importToPhotos(btn.dataset.folder) },
        { label: t('reveal'), act: () => reveal(btn.dataset.folder) },
        { label: t('copyPath'), act: () => copyText(btn.dataset.folder) },
        { label: t('copyName'), act: () => copyText(btn.dataset.name) },
        { sep: true },
        { label: t('forget'), act: () => forgetBatch(btn.dataset.device, btn.dataset.batch) },
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
  api('/api/wifi/open_folder', { body: JSON.stringify({ folder_path: path }) });
}
function reveal(path) {
  if (!path) return;
  api('/api/reveal', { body: JSON.stringify({ path }) });
}
async function copyText(s) {
  if (!s) return;
  try { await navigator.clipboard.writeText(s); } catch { /* ignore */ }
}
async function deleteBatch(device, batch) {
  if (!confirm(state.lang === 'zh' ? '删除这一批文件？' : 'Delete this batch?')) return;
  await api('/api/wifi/delete_batch', { body: JSON.stringify({ device_id: device, batch_id: batch }) });
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
  $('#viewerGrid').innerHTML = viewerPhotos.map((p, i) => {
    const vid = isVideoPath(p.path || p.name);
    const media = vid
      ? `<img alt="${esc(p.name)}" src="/api/thumb?path=${encodeURIComponent(p.path)}" />
         <span class="film" aria-hidden="true">${I_FILM}</span>`
      : `<img alt="${esc(p.name)}" src="${fileURL(p.path)}" />`;
    return `<button type="button" data-i="${i}" data-path="${esc(p.path)}" data-name="${esc(p.name)}"${vid ? ' data-video="1"' : ''}>
      ${media}
    </button>`;
  }).join('') || emptyHTML(I_STACK, t('noHist'));
  bindImg($('#viewerGrid'));
  $('#viewerGrid').querySelectorAll('button[data-path]').forEach((btn) => {
    // 视频交给系统播放器：塞进 <img> 的大图查看器只会显示一个裂图
    const openIt = () => (btn.dataset.video
      ? openFolder(btn.dataset.path)
      : showLightbox(Number(btn.dataset.i)));
    bindOpenAndMenu(
      btn,
      openIt,
      () => [
        { label: btn.dataset.video ? (state.lang === 'zh' ? '播放' : 'Play') : (state.lang === 'zh' ? '查看大图' : 'View'), act: openIt },
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
$('#lbClose')?.addEventListener('click', (e) => {
  e.stopPropagation();
  $('#lightbox').classList.add('hidden');
});
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

let pairInfo = { required: true, code: '' };

async function refreshPair() {
  const box = $('#pairBox');
  if (!box) return;
  const info = await api('/api/pair/info');
  if (!info || info.success !== true) return;
  pairInfo = { required: !!info.required, code: info.code || '' };
  const peersN = (info.peers || []).length;
  // 平时只留一行摘要：配对码天天占一大块没意义，要改的时候再展开
  const bar = $('#pairBar');
  if (bar) {
    bar.classList.remove('hidden');
    $('#pairSummary').textContent = pairInfo.required
      ? t('pairSummary').replace('{code}', pairInfo.code || '——').replace('{n}', String(peersN))
      : t('pairSummaryOff');
  }
  $('#pairCode').textContent = pairInfo.required ? (pairInfo.code || '——') : '—';
  $('#pairNew').classList.toggle('hidden', !pairInfo.required);
  $('#pairOff').textContent = pairInfo.required ? t('pairOff') : t('pairOn');
  const hint = pairInfo.required ? t('pairHint') : t('pairOffHint');
  $('#pairPeers').textContent = peersN
    ? `${hint}  ·  ${t('pairPeers').replace('{n}', String(peersN))}`
    : hint;
  // 二维码带上配对码，扫一下就连上了，不用手输
  setWifiURL(lastWifiURL);
}

$('#pairNew')?.addEventListener('click', async () => {
  await api('/api/pair/set', { body: JSON.stringify({ new_code: true }) });
  refreshPair();
});

$('#pairOff')?.addEventListener('click', async () => {
  await api('/api/pair/set', { body: JSON.stringify({ required: !pairInfo.required }) });
  refreshPair();
});

let outboxKey = null;   // null 表示还没画过；空清单的 key 是 ''，用 '' 当初值会把首次渲染挡掉

async function refreshOutbox(force) {
  const box = $('#outbox');
  if (!box) return;
  const data = await api('/api/outbox');
  const items = data.items || [];
  const key = items.map((i) => `${i.id}:${i.size}:${i.taken}`).join('|');
  if (!force && key === outboxKey) return;
  outboxKey = key;
  // 取走的不再占着清单：收成一行，跟图库里「文件已不在」同一套处理
  const taken = items.filter((i) => i.taken > 0);
  const waiting = items.filter((i) => !i.taken);
  const tookBar = $('#tookBar');
  if (tookBar) {
    tookBar.classList.toggle('hidden', taken.length === 0);
    $('#tookText').textContent = t('tookN').replace('{n}', String(taken.length));
  }
  $('#outClear')?.classList.toggle('hidden', items.length === 0);
  items.length = 0;
  items.push(...waiting);
  if (!items.length) {
    box.innerHTML = `<div class="out-empty">${I_UP}<div>
      <strong>${esc(t('sendEmpty'))}</strong>
      <small>${esc(t('sendEmptyHint'))}</small>
    </div></div>`;
    return;
  }
  box.innerHTML = items.map((it) => {
    const gone = it.size < 0;
    const meta = gone
      ? `<span class="meta gone">${esc(t('fileGone'))}</span>`
      : `<span class="meta">${esc(fmtBytes(it.size) || '')}</span>`;
    const kind = it.text ? `<span class="kind">${esc(t('kindText'))}</span>` : '';
    return `<div class="out-row" title="${esc(it.text || it.path)}">
      ${kind}
      <span class="name">${esc(it.text || it.rel || it.name)}</span>
      ${meta}
      <button type="button" class="out-x" data-id="${esc(it.id)}" aria-label="remove">✕</button>
    </div>`;
  }).join('');
  box.querySelectorAll('.out-x').forEach((b) => {
    b.addEventListener('click', async () => {
      await api(`/api/outbox/remove/${encodeURIComponent(b.dataset.id)}`, { body: '{}' });
      refreshOutbox(true);
    });
  });
}

$('#outPick')?.addEventListener('click', async () => {
  await api('/api/outbox/pick', { body: '{}' });
  // 面板是原生模态，关掉之后再刷新几次，等用户选完
  [600, 1500, 3000, 6000].forEach((ms) => setTimeout(() => refreshOutbox(true), ms));
});

async function sendText() {
  const input = $('#outText');
  const text = (input.value || '').trim();
  if (!text) return;
  const res = await api('/api/outbox/text', { body: JSON.stringify({ text }) });
  if (res && res.success) {
    input.value = '';
    refreshOutbox(true);
  }
}

function showPane(pane) {
  $$('.seg-btn').forEach((b) => b.classList.toggle('on', b.dataset.pane === pane));
  // 标题跟着走：在「发送」面板上还写「Wi-Fi 接收」会让人以为切错了
  const send = pane === 'send';
  $('#wifiH1').textContent = send ? t('sendHead') : t('wifiTitle');
  $('#wifiP').textContent = send ? t('sendSub') : t('wifiSub');
  $('#paneRecv').classList.toggle('hidden', pane !== 'recv');
  $('#paneSend').classList.toggle('hidden', pane !== 'send');
  if (pane === 'send') refreshOutbox(true);
  const path = pane === 'send' ? '/send' : '/wifi';
  if (state.view === 'wifi' && location.pathname !== path) {
    try { history.replaceState(null, '', path); } catch (_) { /* ignore */ }
  }
}

$$('.seg-btn').forEach((btn) => {
  btn.addEventListener('click', () => showPane(btn.dataset.pane));
});

$('#apkLink2')?.addEventListener('click', async () => {
  // 相机扫码进来的会看到手机落地页（装 App + 配对码），所以同一个码就够了
  const url = await ensureApkUrl();
  if (url) copyText(url);
  const el = $('#apkLink2');
  const old = el.textContent;
  el.textContent = t('copied');
  setTimeout(() => { el.textContent = old; }, 1400);
});

$('#pairToggle')?.addEventListener('click', () => {
  $('#pairBox').classList.toggle('hidden');
});

$('#outTextGo')?.addEventListener('click', sendText);
$('#outText')?.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') sendText();
});

$('#tookClear')?.addEventListener('click', async () => {
  await api('/api/outbox/clear_taken', { body: '{}' });
  refreshOutbox(true);
});

$('#outClear')?.addEventListener('click', async () => {
  await api('/api/outbox/remove', { body: '{}' });
  refreshOutbox(true);
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
let apkUrl = '';
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
    correctLevel: QRCode.CorrectLevel.M,
  });
}

let lastHomeQR = '';
function renderHomeQR(url) {
  const box = $('#homeQR');
  if (!box || typeof QRCode === 'undefined' || !url) return;
  if (url === lastHomeQR && box.childElementCount) return;
  lastHomeQR = url;
  box.innerHTML = '';
  new QRCode(box, {
    text: url,
    width: 132,
    height: 132,
    correctLevel: QRCode.CorrectLevel.M,
  });
}


let lastWifiURL = '';

function setWifiURL(url) {
  lastWifiURL = url || lastWifiURL;
  state.wifiPick = url || '';
  $('#wifiURL').textContent = url || '—';
  $('#wifiURL').title = url || '';
  // 二维码里带上配对码：手机扫一下直接配对连上，省掉手输六位
  const target = url && pairInfo.required && pairInfo.code
    ? `${url}/?c=${encodeURIComponent(pairInfo.code)}`
    : url;
  renderQR(target || '');
}

function renderWifiAlts(urls, current) {
  const box = $('#wifiAlts');
  if (!box) return;
  if (!urls || urls.length < 2) {
    box.classList.add('hidden');
    box.innerHTML = '';
    return;
  }
  box.classList.remove('hidden');
  box.innerHTML = urls.map((u) => {
    const label = String(u).replace(/^https?:\/\//, '');
    return `<button type="button" class="ghost${u === current ? ' on' : ''}" data-url="${esc(u)}">${esc(label)}</button>`;
  }).join('');
  box.querySelectorAll('button').forEach((b) => {
    b.addEventListener('click', () => {
      setWifiURL(b.dataset.url);
      renderWifiAlts(urls, b.dataset.url);
    });
  });
}

async function refreshWifi() {
  const [info, gal] = await Promise.all([api('/api/wifi/info'), api('/api/gallery')]);
  const urls = (info.urls && info.urls.length) ? info.urls : (info.url ? [info.url] : []);
  if (!state.wifiPick || !urls.includes(state.wifiPick)) {
    state.wifiPick = urls[0] || info.url || '';
  }
  setWifiURL(state.wifiPick);
  renderWifiAlts(urls, state.wifiPick);
  if (info.apk_url) apkUrl = info.apk_url;
  if (state.wifiPick && !$('#wifiOut').value) {
    const h = await api('/api/health');
    setOut(h.root || '', false);
  }
  // 连着手机热点时明说：这条路不需要路由器
  const hint = $('#wifiHint');
  if (hint) {
    hint.textContent = info.on_hotspot ? t('hotspotOn') : t('hotspotHint');
    hint.classList.toggle('on-hotspot', !!info.on_hotspot);
  }
  renderOnline(info.connected_devices || []);
  refreshOutbox();
  refreshPair();
  const n = (gal.batches || []).length;
  const jump = $('#wifiRecent');
  if (n) {
    jump.classList.remove('hidden');
    jump.textContent = state.lang === 'zh' ? `去图库 · ${n} 批` : `Gallery · ${n}`;
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
    api('/api/wifi/set_output_dir', { body: JSON.stringify({ output_dir: path }) });
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


function flashCopy(btn) {
  if (!btn) return;
  btn.classList.add('ok');
  const copy = btn.querySelector('.i-copy');
  const check = btn.querySelector('.i-check');
  if (copy) copy.classList.add('hidden');
  if (check) check.classList.remove('hidden');
  btn.title = t('copied');
  setTimeout(() => {
    btn.classList.remove('ok');
    if (copy) copy.classList.remove('hidden');
    if (check) check.classList.add('hidden');
    btn.title = t('copy');
  }, 1400);
}

async function ensureApkUrl() {
  if (apkUrl) return apkUrl;
  try {
    const info = await api('/api/wifi/info');
    if (info.apk_url) apkUrl = info.apk_url;
  } catch { /* ignore */ }
  if (!apkUrl) apkUrl = 'https://dl.neox-dev.com/droidtrans/latest.apk';
  return apkUrl;
}

$('#wizPrimary').addEventListener('click', wizardPrimary);
$('#wizSecondary').addEventListener('click', wizardSecondary);

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
  await api('/api/history/clear', { body: '{}' });
  refreshHistory();
});

$('#langBtn').addEventListener('click', () => {
  state.lang = state.lang === 'zh' ? 'en' : 'zh';
  localStorage.setItem('droidtrans.lang', state.lang);
  $('#homeNext')?.addEventListener('click', () => {
  const go = $('#homeNext').dataset.go;
  if (go) show(go);
});

applyLang();
  if (wizardOpen) paintWizard(true);
  refreshHome();
  if (state.view === 'wifi') refreshWifi();
  if (state.view === 'history') refreshHistory();
  if (state.view === 'usb') refreshUsb();
});

applyLang();
// 先记下启动路径：show() 会把地址栏改写掉，之后再判断就晚了
const bootPath = location.pathname;
const bootView = { '/usb': 'usb', '/wifi': 'wifi', '/send': 'wifi', '/history': 'history' }[bootPath];
if (bootView) {
  show(bootView);
  if (bootPath === '/send') showPane('send');
}
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
    const bytePct = box.bytes_total > 0 ? Math.min(100, (box.bytes_done / box.bytes_total) * 100) : 0;
    const filePct = total ? Math.min(100, (n / total) * 100) : 0;
    const pct = bytePct || filePct;
    if (box.receiving) {
      const pace = paceLine(box);
      $('#inboxTitle').textContent = total
        ? `${t('recv')} ${n}/${total}${pace ? '  ·  ' + pace : ''}`
        : `${t('recv')} ${n}${pace ? '  ·  ' + pace : ''}`;
      $('#inboxFill').style.width = pct ? pct + '%' : '40%';
      clearTimeout(inboxHideTimer);
    } else {
      const bits = [`${t('got')} ${n} ${t('photos')}`];
      const size = fmtBytes(box.bytes_done);
      if (size) bits.push(size);
      const dur = fmtDur(box.elapsed_sec);
      if (dur) bits.push(dur);
      const avg = fmtSpeed(box.speed_mbps);
      if (avg) bits.push(avg);
      $('#inboxTitle').textContent = bits.join('  ·  ');
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
