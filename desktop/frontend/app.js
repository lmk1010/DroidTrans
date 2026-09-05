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
  // 默认跟系统语言走，认不出来就英文。
  //
  // 原来写死 'zh'：一台英文系统的 Mac 装上之后，打开是满屏中文，
  // 用户得自己去左下角找那个切换按钮。产品主要面向海外，
  // 这个默认值等于把第一印象让给了看不懂的界面。
  lang: localStorage.getItem('droidtrans.lang')
    || ((navigator.language || '').toLowerCase().startsWith('zh') ? 'zh' : 'en'),
  view: 'home',
  albums: {},
  deviceName: '',
  usbSelected: '',
  selectedAlbums: new Set(),
  currentAlbum: null,
  albumPhotos: [],
  selectedPhotos: new Set(),
  usbOut: '',
  names: {},
  usbConnected: false,
  wifiPick: '',
  // 「已接收」里当前只看哪台设备。'' = 全部（按设备分组展示）
  histDevice: '',
};

let viewerPhotos = [];
let viewerIndex = 0;
// 在线设备的对比基准。renderOnline 定义在这几个变量之前用到它们，
// 声明必须留在顶部 —— 挪到用它的函数旁边就会踩 let 的暂时性死区。
let onlineSeen = null;          // null = 还没拿到过第一批，别把开机时已在线的当成刚连上
let justOnline = new Set();
let justOnlineTimer = 0;
let connToastTimer = 0;
let inboxBatch = { device: '', batch: '', folder: '' };
let lastXfer = { device: '', batch: '', folder: '' };

const I18N = {
  zh: {
    navPhotosLib: '照片图库',
    homeTilePhotosKicker: '照片图库',
    homeTilePhotos: '导出原始文件',
    homeTilePhotosHint: '按拍摄日期整理为标准文件夹',
    plTitle: '照片图库导出',
    plSub: '直接读取「照片」图库，以原始文件名按拍摄日期导出为标准文件夹。',
    plIdleEmpty: '尚未扫描',
    plIdleHint: '扫描后可查看本机可导出的项目数量。',
    plScan: '扫描图库',
    plScanning: '扫描中…',
    plExportable: '可导出',
    plCloud: '仅在 iCloud',
    plLive: '实况照片',
    plSize: '总大小',
    plExport: '开始导出',
    plRescan: '重新扫描',
    plCancel: '取消',
    plCloudWarn: '%n 个项目仅存于 iCloud，本机没有原始文件，无法导出。请在「照片」中开启「下载原片到这台 Mac」，同步完成后重新扫描。',
    plNothing: '本机没有可导出的原始文件。',
    plNeedPro: '导出需要 Pro。扫描不限次数免费使用。',
    plDone: '导出完成 · %n 个项目 · %p',
    plFailed: '%n 个项目导出失败',
    plOutput: '导出位置 %p',
    plQuotaNotice: '免费版本次可导出 %n 项。已选 %t 项，其余 %r 项需要 Pro。',
    plQuotaBtn: '导出 %n 项',
    plQuotaDone: '本次导出 %n 项，已达免费额度。再点一次可继续导下一批，或升级 Pro 一次导完。',
    plQuotaUpgrade: '升级 Pro',
    plSectionOut: '导出设置',
    plSectionPick: '选择项目',
    plOutLabel: '导出到',
    plLowerExt: '扩展名转为小写（.JPG → .jpg）',
    plAll: '全选',
    plNone: '取消全选',
    plShowGrid: '逐项选择',
    plHideGrid: '收起',
    plMore: '加载更多',
    plPicked: '已选 %n / 共 %t',
    plReveal: '在访达中显示',
    plGrant: '前往「完全磁盘访问权限」',
    plLibsTitle: '选择图库',
    plLibDefault: '默认',
    plLibExternal: '外置',
    plNoLib: '未找到照片图库。若图库位于外置磁盘，请连接后重新扫描。',
    licColFree: '免费', licColPro: 'Pro',
    licColMine: 'Pro · 你的', licOwnedSummary: '已解锁 %n 项能力，额度全部解除',
    licYes: '✓', licNo: '—', licUnlimited: '不限',
    licFree4G: '4 GB', licFree1000: '1000 项/次',
    licRowLan: '局域网互传', licRowResume: '断点续传',
    licRowFileSize: '单个文件大小', licRowExport: '照片图库导出',
    licRowUsb: 'USB 整册导入', licRowSync: '相册增量同步', licRowDedupe: '跨批次去重',
    licFeatLarge: '单个文件不限大小', licFeatExport: '照片图库导出不限量',
    licTitle: '卓传 Pro', licActivate: '激活', licClose: '关闭', licBest: '最划算',
    licPlanYear: '一年', licPlanYears3: '三年', licPlanLifetime: '终生',
    licPlanHint3: '买两年送一年', licPlanHintLife: '一次买断',
    licOpening: '正在打开浏览器…', licOpenFailed: '打不开浏览器，请手动访问官网',
    licHaveCode: '已经有激活码？',
    licRemove: '注销这台设备', licBuy: '购买 Pro',
    licPlaceholder: 'DT-XXXX-XXXX-XXXX',
    licFree: '免费版 · 基础功能不受任何限制',
    licPro: 'Pro 已激活',
    licExpired: 'Pro 已过期，续期后可继续使用高级功能',
    licNeedCode: '请输入激活码',
    licOk: '激活成功',
    licRemoved: '已注销，这台设备回到免费版',
    licFeatIncremental: '相册增量同步',
    licFeatDedupe: '跨批次去重',
    licFeatUsb: 'USB 整册导入',
    navHome: '总览', navHist: '已接收',
    askClearHistTitle: '清空传输记录？',
    askClearHistNote: '只清掉记录列表，已经存到这台电脑上的文件不会被删除。',
    askDelBatchTitle: '删除这一批文件？',
    askDelBatchNote: '这些文件会从这台电脑上永久删除，无法撤销。手机上的原件不受影响。',
    askClearOutTitle: '清空待取件？',
    askClearOutNote: '手机还没取走的会一并移除。电脑上的原文件不会被删除。',
    askDeactivateTitle: '注销这台设备？',
    askDeactivateNote: '这台电脑会回到免费版。激活码本身仍然有效，可以在别的设备上继续用。',
    confirmTitle: '开始导入',
    confirmCount: '项目数', confirmDest: '导入到', confirmGo: '开始导入', cancel: '取消',
    confirmSub: '从「%d」导入到这台电脑',
    confirmNote: '导入过程中请保持数据线连接。可以随时暂停或停止。',
    xferBack: '返回', xferPrep: '准备中…', xferOf: '%n / %t',
    usbPickDevice: '选择设备', usbSwitching: '正在切换…', usbOneDevice: '只连着这一台',
    navGroupPhone: '手机', navGroupMac: '这台电脑',
    navUsb: 'USB 导入', navUsbHint: '数据线 · 仅安卓',
    navWifi: 'Wi-Fi 传输', navWifiHint: '无线 · 双向',
    navPhotosLibHint: '导出原始文件', navHistHint: '传过来的文件',
    homeTitle: '把照片和文件集中到这台电脑',
    usbTile: 'USB 导入', wifiTile: 'Wi-Fi 传输',
    scan: '扫描相册', scanning: '扫描中…', xfer: '开始传输', waiting: '等待设备', save: '接收位置',
    selAll: '全选', selNone: '取消全选',
    usbNoAlbum: '还没有相册', usbScanHint: '连上后会自动扫。选出要传的，再开始传输。',
    copy: '复制', copied: '已复制', open: '打开', openShort: '打开',
    saveShared: 'USB 与 Wi-Fi 共用此位置',
    wifiTitle: 'Wi-Fi 传输 · 接收', wifiSub: '手机打开卓传后会自动连接到这台电脑。',
    wifiHint: '已装 App 时扫这个，或等它自己发现。',
    localAddr: '本机地址', online: '在线设备', batches: '最近接收', seeAll: '全部',
    noAppYet: '手机还没装卓传？', apkGet: '用相机扫上面的码',
    goneN: '{n} 批的文件已不在', goneClean: '清理这些记录', goneShow: '看看',
    paneRecv: '接收', paneSend: '发送', sendWaiting: '等手机来取',
    sendHead: 'Wi-Fi 传输 · 发送', sendSub: '将文件拖入窗口或粘贴文字，手机打开卓传后即可取走。',
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
    homeNextUsb: '手机已连接，前往 USB 导入选择相册。',
    homeNextAllow: '前往 USB 导入，按提示在手机上允许调试。',
    homeNextOnline: '手机已在线，可在「已接收」中查看新文件。',
    homeNextWifi: 'Wi-Fi 已就绪，手机打开卓传即可自动连接。',
    homeNextIdle: '点 USB，页面只说你现在该做的那一步。',
    openThisPhone: '查看这台手机传来的文件', histTitle: '已接收', clear: '清空',
    devAll: '全部设备', devBatches: '批',
    connTitle: '{n} 已连接', connHint: '现在可以两边互传文件了',
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
    updateAvail: '有新版本 {v}',
    updateNow: '立即更新',
    updateLater: '稍后',
  },
  en: {
    navPhotosLib: 'Photos Library',
    homeTilePhotosKicker: 'PHOTOS LIBRARY',
    homeTilePhotos: 'Export originals',
    homeTilePhotosHint: 'Standard folders, organised by capture date',
    plTitle: 'Photos Library Export',
    plSub: 'Reads the Photos library directly and exports originals to standard folders by capture date, under their original filenames.',
    plIdleEmpty: 'Not scanned yet',
    plIdleHint: 'Scan to see how many items can be exported from this Mac.',
    plScan: 'Scan library',
    plScanning: 'Scanning…',
    plExportable: 'Exportable',
    plCloud: 'iCloud only',
    plLive: 'Live Photos',
    plSize: 'Total size',
    plExport: 'Start export',
    plRescan: 'Rescan',
    plCancel: 'Cancel',
    plCloudWarn: '%n items exist only in iCloud. Their originals are not on this Mac and cannot be exported. Enable Download Originals to this Mac in Photos, then rescan once syncing completes.',
    plNothing: 'No original files available on this Mac.',
    plNeedPro: 'Export requires Pro. Scanning is free and unlimited.',
    plDone: 'Export complete · %n items · %p',
    plFailed: '%n items failed to export',
    plOutput: 'Destination %p',
    plQuotaNotice: 'The free plan exports %n items per run. %t selected, the remaining %r need Pro.',
    plQuotaBtn: 'Export %n items',
    plQuotaDone: 'Exported %n items — the free limit for one run. Run it again for the next batch, or upgrade to Pro to do it in one go.',
    plQuotaUpgrade: 'Upgrade to Pro',
    plSectionOut: 'Export settings',
    plSectionPick: 'Select items',
    plOutLabel: 'Export to',
    plLowerExt: 'Lowercase file extensions (.JPG → .jpg)',
    plAll: 'Select all',
    plNone: 'Deselect all',
    plShowGrid: 'Select individually',
    plHideGrid: 'Collapse',
    plMore: 'Load more',
    plPicked: '%n of %t selected',
    plReveal: 'Show in Finder',
    plGrant: 'Open Full Disk Access',
    plLibsTitle: 'Select library',
    plLibDefault: 'Default',
    plLibExternal: 'External',
    plNoLib: 'No Photos library found. If it is on an external disk, connect it and rescan.',
    licColFree: 'Free', licColPro: 'Pro',
    licColMine: 'Pro · yours', licOwnedSummary: '%n capabilities unlocked, all limits removed',
    licYes: '✓', licNo: '—', licUnlimited: 'Unlimited',
    licFree4G: '4 GB', licFree1000: '1000 per run',
    licRowLan: 'Local network transfer', licRowResume: 'Resumable transfers',
    licRowFileSize: 'Single file size', licRowExport: 'Photos library export',
    licRowUsb: 'Whole-album USB import', licRowSync: 'Incremental library sync', licRowDedupe: 'Cross-batch deduplication',
    licFeatLarge: 'No file size limit', licFeatExport: 'Unlimited Photos library export',
    licTitle: 'DroidTrans Pro', licActivate: 'Activate', licClose: 'Close', licBest: 'BEST VALUE',
    licPlanYear: '1 year', licPlanYears3: '3 years', licPlanLifetime: 'Lifetime',
    licPlanHint3: 'Third year free', licPlanHintLife: 'Pay once',
    licOpening: 'Opening browser…', licOpenFailed: 'Could not open the browser — visit the site manually',
    licHaveCode: 'Already have a code?',
    licRemove: 'Deactivate this device', licBuy: 'Get Pro',
    licPlaceholder: 'DT-XXXX-XXXX-XXXX',
    licFree: 'Free — every basic feature, no limits',
    licPro: 'Pro is active',
    licExpired: 'Pro has expired. Renew to keep the advanced features.',
    licNeedCode: 'Enter your activation code',
    licOk: 'Activated',
    licRemoved: 'Deactivated — this device is back on the free version',
    licFeatIncremental: 'Incremental library sync',
    licFeatDedupe: 'Deduplicate across transfers',
    licFeatUsb: 'Whole-album USB import',
    navHome: 'Home', navHist: 'Received',
    askClearHistTitle: 'Clear transfer history?',
    askClearHistNote: 'This clears the list only. Files already saved on this Mac are not deleted.',
    askDelBatchTitle: 'Delete this batch?',
    askDelBatchNote: 'These files are permanently removed from this Mac and cannot be recovered. The originals on your phone are untouched.',
    askClearOutTitle: 'Clear pending files?',
    askClearOutNote: 'Anything your phone has not picked up yet is removed from the list. The original files on this Mac are not deleted.',
    askDeactivateTitle: 'Deactivate this device?',
    askDeactivateNote: 'This Mac returns to the free version. Your licence key stays valid and can be used on another device.',
    confirmTitle: 'Start import',
    confirmCount: 'Items', confirmDest: 'Import to', confirmGo: 'Start import', cancel: 'Cancel',
    confirmSub: 'From %d to this Mac',
    confirmNote: 'Keep the cable connected during import. You can pause or stop at any time.',
    xferBack: 'Back', xferPrep: 'Preparing…', xferOf: '%n / %t',
    usbPickDevice: 'Select device', usbSwitching: 'Switching…', usbOneDevice: 'Only this one is connected',
    navGroupPhone: 'Phone', navGroupMac: 'This Mac',
    navUsb: 'USB import', navUsbHint: 'Cable · Android only',
    navWifi: 'Wi-Fi transfer', navWifiHint: 'Wireless · both ways',
    navPhotosLibHint: 'Export originals', navHistHint: 'Files received',
    homeTitle: 'Bring photos and files together on this Mac',
    usbTile: 'USB import', wifiTile: 'Wi-Fi transfer',
    scan: 'Scan albums', scanning: 'Scanning…', xfer: 'Transfer', waiting: 'Waiting for device', save: 'Save to',
    selAll: 'Select all', selNone: 'Clear selection',
    usbNoAlbum: 'No albums yet', usbScanHint: 'Albums scan automatically. Pick what to send, then transfer.',
    copy: 'Copy', copied: 'Copied', open: 'Open', openShort: 'Open',
    saveShared: 'Shared by USB and Wi-Fi',
    wifiTitle: 'Wi-Fi transfer · Receive', wifiSub: 'The phone finds this Mac by itself.',
    wifiHint: 'Scan this if the app is already installed, or wait for it to appear.',
    localAddr: 'This computer', online: 'Online', batches: 'Recently received', seeAll: 'See all',
    noAppYet: 'No app on the phone yet?', apkGet: 'Scan the code above with the camera',
    goneN: '{n} batches are missing their files', goneClean: 'Remove these records', goneShow: 'Show',
    paneRecv: 'Receive', paneSend: 'Send', sendWaiting: 'Waiting for the phone',
    sendHead: 'Wi-Fi transfer · Send', sendSub: 'Drop files onto the window or paste text; your phone can then pick them up.',
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
    openThisPhone: 'Files from this phone', histTitle: 'Received', clear: 'Clear',
    devAll: 'All devices', devBatches: 'batches',
    connTitle: '{n} is connected', connHint: 'You can send files both ways now',
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
    updateAvail: 'Update available: {v}',
    updateNow: 'Update now',
    updateLater: 'Later',
  },
};

const t = (k) => I18N[state.lang][k] || I18N.zh[k] || k;

function applyLang() {
  document.documentElement.lang = state.lang === 'zh' ? 'zh-CN' : 'en';
  // 系统通知是 Go 那边发的，语言只有这里知道。不同步过去的话，
  // 英文界面会收到中文通知。
  api('/api/ui/lang', { body: JSON.stringify({ lang: state.lang }) }).catch(() => {});
  $$('[data-i18n]').forEach((el) => { el.textContent = t(el.dataset.i18n); });
  $$('[data-i18n-ph]').forEach((el) => { el.placeholder = t(el.dataset.i18nPh); });
  $$('[data-i18n-title]').forEach((el) => {
    const label = t(el.dataset.i18nTitle);
    el.title = label;
    el.setAttribute('aria-label', label);
  });
  $('#langBtnText').textContent = state.lang === 'zh' ? 'EN' : '中文';
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

// 英文的「1 photos」「1 batches」很显眼，尤其现在每一批各占一行。
// 中文没有单复数，照原样拼。
function nPhotos(n) {
  n = Number(n) || 0;
  if (state.lang !== 'en') return `${n} ${t('photos')}`;
  return `${n} ${n === 1 ? 'photo' : 'photos'}`;
}
function nBatches(n) {
  n = Number(n) || 0;
  if (state.lang !== 'en') return `${n} ${t('devBatches')}`;
  return `${n} ${n === 1 ? 'batch' : 'batches'}`;
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

/// 带插画的空态。
///
/// 一行灰字加个 18px 的小图标，在一整块空白区域里显得很敷衍。
/// 空态是用户停留时间最长的界面之一（东西还没传过来的时候），值得给张图。
function emptyArtHTML(art, title, hint) {
  return `<div class="empty with-art">`
    + `<img class="empty-art" src="/art/${art}.png" alt="">`
    + `<div>${esc(title)}</div>`
    + (hint ? `<small>${esc(hint)}</small>` : '')
    + `</div>`;
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

// 设备墙上的手机得看着像那台手机。
//
// 原来所有设备共用 USB 向导里那个 phoneFrame()：圆角矩形加一条听筒，
// 既不是 iPhone 也不是这几年的安卓机，五台设备长得一模一样——
// 而「哪台手机」正是设备墙唯一要回答的问题。
//
// 机身是出图服务渲染的写实产品图（见 scripts/gen-assets.sh）。这里不跟其他
// 图标共用那段黏土风格串：黏土风的手机不像手机，而这一屏要的就是「像」。
// 也不能拿厂商的官方产品图——那是有版权的素材，App 正在过审，用不得。
// 屏幕的位置由 scripts/prep-phone.py 从素材里量出来——手机边框只有几像素宽，
// 手填差一点就露白边或盖住边框。
//
// 灵动岛和打孔要用 CSS 再补一层压在封面上：素材是位图，封面一铺就把它们盖没了，
// 而那正是两种机型最认得出的地方。真机上它们本来也压在照片上面。
const PHONE_ART = {
  ios: {
    src: '/art/phone-ios.png',
    screen: { x: 4.23, y: 1.67, w: 91.76, h: 96.56, rx: 12, ry: 4.6 },
    notch: 'ios',
  },
  android: {
    src: '/art/phone-android.png',
    screen: { x: 2.58, y: 1.02, w: 93.99, h: 97.76, rx: 9, ry: 3.6 },
    notch: 'android',
  },
};

// iOS 端上报的名字基本都带 iPhone/iPad（系统默认就是机型名或「谁的 iPhone」）；
// 安卓端上报的是厂商+型号。认不出来的按安卓算——这个 App 的安卓用户占大头，
// 猜错的代价是把安卓画成安卓，而不是把 iPhone 画成安卓。
function phonePlatform(name, id) {
  const s = `${name || ''} ${id || ''}`.toLowerCase();
  return /iphone|ipad|ipod|\bios\b|macbook|apple/.test(s) ? 'ios' : 'android';
}

function phoneArt(name, id) {
  return PHONE_ART[phonePlatform(name, id)];
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
  if (view === 'photoslib') plEnter();
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
  // 名字先记下再比对，不然刚连上的那台在提示里只有一串 id
  noteOnline(wifi.connected_devices);
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
  $('#view-usb').classList.toggle('usb-connected', state.usbConnected);
  // 序列号对用户没意义，放进 title 里备查就行
  const who = (dev.model || '').trim() || dev.selected || '';
  state.deviceName = who;
  state.usbSelected = dev.selected || '';
  // 有第二台机器时才露出切换入口，只有一台的话多一个箭头是纯噪音
  usbSyncDeviceList(dev);
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
  el.innerHTML = emptyArtHTML('album', t('usbNoAlbum'), t('usbScanHint'));
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

/* 点「开始传输」先确认。一跑就是几分钟，跑错了只能等它结束 ——
   用户得有机会核对要导多少、导到哪里。 */
$('#xferBtn').addEventListener('click', async () => {
  const { n, bytes } = plannedTotals();
  if (!n) return;
  const zh = state.lang === 'zh';
  const size = fmtBytes(bytes);
  const okd = await askConfirm({
    title: t('confirmTitle'),
    sub: t('confirmSub').replace('%d', state.deviceName || (zh ? '手机' : 'your phone')),
    rows: [
      [t('confirmCount'), (zh ? `${n} 项` : `${n} items`) + (size ? `  ·  ${size}` : '')],
      [t('confirmDest'), $('#usbOut').value.trim() || '—'],
    ],
    note: t('confirmNote'),
    ok: t('confirmGo'),
  });
  if (!okd) return;
  await startTransferNow();
});

/* 选中的相册加单张，一共多少项、多大。
   字段名是 total_count / total_size（见 Go 侧的 Album 结构），
   写错了确认框会显示 0 项，然后什么都不会发生。 */
function plannedTotals() {
  let n = state.selectedPhotos.size;
  let bytes = 0;
  state.selectedAlbums.forEach((p) => {
    const al = state.albums[p];
    if (!al) return;
    n += al.total_count || 0;
    bytes += al.total_size || 0;
  });
  return { n, bytes };
}

function closeConfirm() { $('#confirmModal').hidden = true; }

/* 通用二次确认。
   破坏性操作一律走这里，不用原生 confirm() —— 那在 WebView 里是个
   系统丑框，和界面完全两套，而且没法说清楚「到底删的是什么」。

   note 必须写实话：删记录和删磁盘文件是两回事，用户分不清就会误删。 */
let confirmResolve = null;
function askConfirm({ title, sub = '', rows = [], note = '', ok, danger = false }) {
  $('#confirmTitle').textContent = title;
  $('#confirmSub').textContent = sub;
  $('#confirmNote').textContent = note;

  const list = $('#confirmRows');
  list.innerHTML = '';
  list.hidden = !rows.length;
  for (const [k, v] of rows) {
    const li = document.createElement('li');
    li.innerHTML = '<span></span><strong></strong>';
    li.querySelector('span').textContent = k;
    li.querySelector('strong').textContent = v;
    list.appendChild(li);
  }

  const go = $('#confirmGo');
  go.textContent = ok;
  go.classList.toggle('danger', danger);
  $('#confirmModal').hidden = false;

  return new Promise((resolve) => {
    if (confirmResolve) confirmResolve(false);
    confirmResolve = resolve;
  });
}

function settleConfirm(v) {
  closeConfirm();
  const r = confirmResolve;
  confirmResolve = null;
  if (r) r(v);
}

$('#confirmCancel')?.addEventListener('click', () => settleConfirm(false));
$('#confirmBackdrop')?.addEventListener('click', () => settleConfirm(false));
$('#confirmGo')?.addEventListener('click', () => settleConfirm(true));
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && !$('#confirmModal').hidden) settleConfirm(false);
});

async function startTransferNow() {
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
}

/* 传输中整屏只留进度。
   把进度面板挂在相册栅格下面的话，用户得往下滚才看得见 ——
   而这几分钟里他要看的就只有这一件事。 */
function xferFocus(on) {
  $('#view-usb')?.classList.toggle('xfer-running', on);
}

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
    xferFocus(true);
    $('#xferSee').classList.add('hidden');
    $('#xferImport')?.classList.add('hidden');
    $('#xferBack')?.classList.add('hidden');
    // 传输中必须能停下来：以前这里一个控制都没有，只能退出 App
    $('#xferPause').classList.remove('hidden');
    $('#xferStop').classList.remove('hidden');
    $('#xferPause').textContent = st.paused ? t('resume') : t('pause');
    $('#xferArt').src = '/art/transfer.png';
    $('#xferArt').classList.add('busy');
    // 大数字放进度，副行放速度和剩余时间 —— 盯着看的就是这两样
    $('#xferCount').textContent = t('xferOf')
      .replace('%n', st.current || 0).replace('%t', st.total || 0);
    const pace = paceLine(st);
    $('#xferText').textContent = st.paused ? t('paused') : (pace || t('xferPrep'));
    $('#xferFile').textContent = st.current_file || '';
    setTimeout(pollXfer, st.paused ? 900 : 400);
    return;
  }
  xferFocus(true);
  $('#xferArt').src = '/art/done.png';
  $('#xferArt').classList.remove('busy');
  $('#xferFile').textContent = '';
  $('#xferBack')?.classList.remove('hidden');
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
  $('#xferCount').textContent = zh ? `${n} 项` : `${n} items`;
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

// 「已接收」里的一批 = 一行。
//
// 原来这里跟首页一样铺缩略图卡片。首页只放 4 个，是个视觉锚点；
// 已接收页动辄几十批，铺开就是一堵墙 —— 每张图都在抢注意力，
// 结果是「哪一批是刚才那次」反而看不出来。
// 一行一批，把张数、大小、耗时、均速摊平成一行字，扫读比认图快得多；
// 左边留一个 40px 的小图当锚点，认得出画面的那一批仍然一眼能挑出来。
function renderBatchList(el, batches) {
  el.innerHTML = (batches || []).map((b) => {
    const name = b.device_name || deviceLabel(b.device_id);
    const cover = b.cover
      ? `<img alt="" src="${fileURL(b.cover)}" />`
      : `<span class="ph">${I_STACK}</span>`;
    const bits = [];
    const sz = fmtBytes(b.total_size);
    if (sz) bits.push(sz);
    const dur = fmtDur(b.duration_sec);
    if (dur) bits.push(dur);
    if (b.duration_sec > 0 && b.total_size > 0) {
      const avg = fmtSpeed((b.total_size / 1024 / 1024) / b.duration_sec);
      if (avg) bits.push(avg);
    }
    const gone = !!b.missing;
    if (gone) bits.unshift(state.lang === 'zh' ? '文件已不在' : 'files missing');
    return `<button type="button" class="batch-row${gone ? ' gone-batch' : ''}"
      data-device="${esc(b.device_id)}" data-batch="${esc(b.batch_id)}"
      data-folder="${esc(b.folder || '')}" data-name="${esc(name)}" data-missing="${gone ? '1' : ''}">
      <span class="row-thumb">${cover}</span>
      <span class="row-main">
        <b>${esc(batchTitle(b.batch_id))}</b>
        <small>${esc(bits.join('  ·  '))}</small>
      </span>
      <span class="row-count">${esc(nPhotos(b.photo_count))}</span>
    </button>`;
  }).join('');
  bindImg(el);
  el.querySelectorAll('.batch-row').forEach((btn) => {
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

// 首页那 4 个缩略图卡片还在用这个。已接收页已经改走 renderBatchList。
function renderGallery(target, batches, limit) {
  const el = typeof target === 'string' ? $(target) : target;
  if (!el) return;
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
    const sz = fmtBytes(b.total_size);
    const dur = fmtDur(b.duration_sec);
    let avg = '';
    if (b.duration_sec > 0 && b.total_size > 0) {
      avg = fmtSpeed((b.total_size / 1024 / 1024) / b.duration_sec) || '';
    }
    // 卡片一行放得下三项就够：张数、大小、哪台手机。
    // 耗时和均速塞进去只会被省略号吃掉，放到悬停提示里。
    const extra = [nPhotos(b.photo_count)];
    if (sz) extra.push(sz);
    extra.push(name);
    const tip = [batchTitle(b.batch_id), nPhotos(b.photo_count), sz, dur, avg, name]
      .filter(Boolean).join('  ·  ');
    const gone = !!b.missing;
    // 放在最前面：这行会被省略号截断，最该看到的是「文件已不在」
    if (gone) extra.unshift(state.lang === 'zh' ? '文件已不在' : 'files missing');
    return `<button type="button" class="shot${gone ? ' gone-batch' : ''}" title="${esc(tip)}"
      data-device="${esc(b.device_id)}" data-batch="${esc(b.batch_id)}" data-folder="${esc(b.folder || '')}" data-name="${esc(name)}" data-missing="${gone ? '1' : ''}">
      <span class="thumb">
        ${cover}
        <span class="count">${esc(nPhotos(b.photo_count))}</span>
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
  // 这个是真删磁盘文件（服务端 os.RemoveAll），不可撤销，必须写清楚
  if (!await askConfirm({
    title: t('askDelBatchTitle'), note: t('askDelBatchNote'),
    ok: t('delBatch'), danger: true,
  })) return;
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
  }).join('') || emptyArtHTML('album', t('noHist'));
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
    // 「把文件拖进来」是这个界面最主要的引导，给它一张图
    box.innerHTML = `<div class="out-empty with-art">
      <img class="empty-art" src="/art/drop.png" alt="">
      <div>
        <strong>${esc(t('sendEmpty'))}</strong>
        <small>${esc(t('sendEmptyHint'))}</small>
      </div>
    </div>`;
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
  if (!await askConfirm({
    title: t('askClearOutTitle'), note: t('askClearOutNote'),
    ok: t('clearAll'), danger: true,
  })) return;
  await api('/api/outbox/remove', { body: '{}' });
  refreshOutbox(true);
});

function renderOnline(online) {
  const el = $('#onlineList');
  if (!online.length) {
    el.innerHTML = emptyArtHTML('unplug', t('noPhone'), t('noPhoneHint'));
    return;
  }
  el.innerHTML = online.map((d) => {
    const id = d.id || d.device_id || '';
    const name = d.name || state.names[id] || shortId(id);
    if (d.name) state.names[id] = d.name;
    // 刚连上的那一行点一下亮：多出一行太安静，眼睛不一定会落到这里
    const fresh = justOnline.has(id) ? ' just-on' : '';
    return `<button type="button" class="row-btn${fresh}" data-device="${esc(id)}" title="${esc(t('openThisPhone'))}"><span class="live" aria-hidden="true">${I_DEVICE}</span><span class="who"><b>${esc(name)}</b><small>${esc(shortId(id))}</small></span></button>`;
  }).join('');
  el.querySelectorAll('[data-device]').forEach((btn) => {
    btn.addEventListener('click', () => openDeviceGallery(btn.dataset.device));
  });
}

// 点某台在线设备 = 「只看这台传来的东西」。
//
// 原来是直接把它最近一批的图片查看器怼到脸上 —— 想看的是「这台手机传过什么」，
// 弹出来的却是某一批的大图，还得先退出来。
// 手机连上来的提醒。
//
// 之前唯一的反馈是状态行悄悄从 IP 变成「IP · 1 台在线」，Wi-Fi 页多出一行 ——
// 用户连的时候正低头看手机，回过神来根本不知道到底连上没有。
function noteOnline(devices) {
  const ids = new Set((devices || []).map((d) => d.id || d.device_id).filter(Boolean));
  if (onlineSeen === null) {          // 开机时已经在线的不弹
    onlineSeen = ids;
    return;
  }
  const fresh = [...ids].filter((id) => !onlineSeen.has(id));
  onlineSeen = ids;
  if (!fresh.length) return;

  justOnline = new Set(fresh);
  clearTimeout(justOnlineTimer);
  justOnlineTimer = setTimeout(() => { justOnline = new Set(); }, 3000);

  const names = fresh.map((id) => deviceLabel(id));
  showConnToast(names.length > 1 ? names.join('、') : names[0], fresh[0]);
}

function showConnToast(name, deviceId) {
  const el = $('#connToast');
  if (!el) return;
  $('#connToastTitle').textContent = t('connTitle').replace('{n}', name);
  $('#connToastText').textContent = t('connHint');
  el.dataset.device = deviceId || '';
  el.classList.remove('hidden');
  clearTimeout(connToastTimer);
  connToastTimer = setTimeout(() => el.classList.add('hidden'), 6000);
}

$('#connToastOpen')?.addEventListener('click', () => {
  const id = $('#connToast').dataset.device || '';
  $('#connToast').classList.add('hidden');
  clearTimeout(connToastTimer);
  openDeviceGallery(id);
});
$('#connToastX')?.addEventListener('click', () => {
  $('#connToast').classList.add('hidden');
  clearTimeout(connToastTimer);
});

async function openDeviceGallery(deviceId) {
  state.histDevice = deviceId || '';
  show('history');
  await refreshHistory();
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
  if (!apkUrl) apkUrl = 'https://droid.mkstore.life/latest.apk';
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
  renderHistory(gal.batches || []);
}

// 「已接收」分两级：先是一台台手机，点进去才是那台手机传来的东西。
//
// 原来是一整片缩略图：七台设备的批次混在一起，设备名挤在卡片小字的第三项、
// 还常被省略号吃掉，想找「刚才那台手机传的」只能一张张认。
// 中间试过按设备分段——段是分了，段里仍是缩略图墙，等于把一堵墙切成几堵。
// 现在第一屏只回答「哪台手机」：一台设备一部手机，屏幕里放它最近一批的封面，
// 认得出画面、也数得清有几台；具体哪一批是进去之后的事。
function renderHistory(batches) {
  const all = batches || [];
  const list = $('#histList');
  const back = $('#histBack');

  // 按设备聚合，顺序沿用批次本身的顺序（新的在前）
  const groups = [];
  const byId = new Map();
  all.forEach((b) => {
    const id = b.device_id || '';
    let g = byId.get(id);
    if (!g) {
      g = { id, name: b.device_name || deviceLabel(id), batches: [] };
      byId.set(id, g);
      groups.push(g);
    }
    // 设备可能改过名，以最新一批为准
    if (b.device_name) g.name = b.device_name;
    g.batches.push(b);
  });

  // 选中的设备已经没有记录了（清空、删批次）就退回设备墙，
  // 否则会停在一个永远空着的页面上，看着像数据丢了
  if (state.histDevice && !byId.has(state.histDevice)) state.histDevice = '';

  // 文件已经不在磁盘上的批次，默认不占位置：东西早就没了，看也没得看
  const keep = (arr) => (showGone ? arr : arr.filter((b) => !b.missing));
  renderGoneBar(all.filter((b) => b.missing).length);

  const visible = groups
    .map((g) => ({ ...g, batches: keep(g.batches) }))
    .filter((g) => g.batches.length);
  const total = visible.reduce((s, g) => s + g.batches.length, 0);
  $('#clearHist')?.classList.toggle('hidden', total === 0);

  if (!total) {
    back.classList.add('hidden');
    list.innerHTML = `<div class="empty empty-go">${I_STACK}<div>
      <div>${esc(t('noHist'))}</div>
      <small>${esc(t('noHistHint'))}</small>
      <div class="empty-acts">
        <button type="button" class="ghost" data-go="usb">USB</button>
        <button type="button" class="ghost" data-go="wifi">Wi-Fi</button>
      </div>
    </div></div>`;
    list.querySelectorAll('[data-go]').forEach((b) => b.addEventListener('click', () => show(b.dataset.go)));
    return;
  }

  const picked = state.histDevice ? visible.find((g) => g.id === state.histDevice) : null;
  if (picked) {
    renderDevicePage(back, list, picked);
  } else {
    renderDeviceWall(back, list, visible);
  }
}

// 第一级：一台设备一部手机
function renderDeviceWall(back, list, groups) {
  back.classList.add('hidden');
  back.innerHTML = '';
  list.innerHTML = `<div class="dev-wall">${groups.map((g) => {
    const n = g.batches.reduce((s, b) => s + (b.photo_count || 0), 0);
    const size = fmtBytes(g.batches.reduce((s, b) => s + (b.total_size || 0), 0));
    const cover = g.batches.find((b) => b.cover)?.cover || '';
    const screen = cover
      ? `<img alt="" src="${fileURL(cover)}" />`
      : `<span class="ph">${I_STACK}</span>`;
    const meta = [nBatches(g.batches.length), nPhotos(n), size].filter(Boolean).join(' · ');
    const art = phoneArt(g.name, g.id);
    const sc = art.screen;
    const geom = `--sx:${sc.x}%;--sy:${sc.y}%;--sw:${sc.w}%;--sh:${sc.h}%;--srx:${sc.rx}%;--sry:${sc.ry}%`;
    return `<button type="button" class="dev-card" data-dev="${esc(g.id)}" title="${esc(t('openThisPhone'))}">
      <span class="dev-phone" style="${geom}">
        <img class="phone-body" alt="" src="${art.src}" />
        <span class="dev-screen">${screen}</span>
        <span class="dev-notch ${art.notch}"></span>
        <span class="dev-bar ${art.notch}"></span>
      </span>
      <span class="dev-name">${esc(g.name)}</span>
      <small>${esc(meta)}</small>
    </button>`;
  }).join('')}</div>`;
  bindImg(list);
  list.querySelectorAll('.dev-card').forEach((btn) => {
    btn.addEventListener('click', () => {
      state.histDevice = btn.dataset.dev;
      refreshHistory();
    });
  });
}

// 第二级：这台设备传来的每一批，一批一行
function renderDevicePage(back, list, g) {
  const n = g.batches.reduce((s, b) => s + (b.photo_count || 0), 0);
  const size = fmtBytes(g.batches.reduce((s, b) => s + (b.total_size || 0), 0));
  const meta = [nBatches(g.batches.length), nPhotos(n), size].filter(Boolean).join(' · ');
  back.classList.remove('hidden');
  back.innerHTML = `<button type="button" id="histBackBtn" class="ghost">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 5l-7 7 7 7"/></svg>
      <span>${esc(t('devAll'))}</span>
    </button>
    <span class="hist-who">${I_DEVICE}<b>${esc(g.name)}</b><small>${esc(meta)}</small></span>`;
  back.querySelector('#histBackBtn').addEventListener('click', () => {
    state.histDevice = '';
    refreshHistory();
  });
  renderBatchList(list, g.batches);
}

$('#clearHist').addEventListener('click', async () => {
  // 只清记录不删文件，这一点必须说死 —— 用户最怕的是照片没了
  if (!await askConfirm({
    title: t('askClearHistTitle'), note: t('askClearHistNote'),
    ok: t('clear'), danger: true,
  })) return;
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
      const bits = [`${t('got')} ${nPhotos(n)}`];
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

async function checkUpdateBanner() {
  const el = $('#updateBanner');
  if (!el) return;
  try {
    const st = await api('/api/version?refresh=1');
    if (!st || !st.available) {
      el.classList.add('hidden');
      return;
    }
    $('#updateText').textContent = t('updateAvail').replace('{v}', st.latest || '');
    $('#updateBtn').textContent = t('updateNow');
    $('#updateDismiss').textContent = t('updateLater');
    el.classList.remove('hidden');
  } catch (_) { /* 检查失败就静默 */ }
}

$('#updateBtn')?.addEventListener('click', async () => {
  try { await api('/api/update/open', { body: '{}' }); } catch (_) {}
});
$('#updateDismiss')?.addEventListener('click', () => {
  $('#updateBanner')?.classList.add('hidden');
});
setTimeout(checkUpdateBanner, 5000);

/* ==========================================================================
   授权
   --------------------------------------------------------------------------
   状态从桌面端自己的 /api/license 读，那边是离线判断的 ——
   这里不直接连授权服务器，界面不该因为没网就显示成未激活。
   只有点「激活」那一下才需要联网。
   ========================================================================== */

/* 只列真正被门控的能力。
   曾经这里还有一条 auto_archive「自动归档：按日期和设备分批」——
   但那件事免费版也在做（落盘路径本来就是 输出目录/设备/批次），
   license.FeatureAutoArchive 这个常量全项目没有任何使用点。
   把免费就有的东西摆进付费清单，用户买完会发现什么都没变。 */
/* 已激活时列出解锁了什么。key 要和 internal/license/features.go 里的常量对上，
   漏一个，用户买了却看不到自己拿到了什么。 */
const LIC_FEATURES = [
  ['large_files', 'licFeatLarge'],
  ['photos_rescue', 'licFeatExport'],
  ['incremental_sync', 'licFeatIncremental'],
  ['usb_bulk', 'licFeatUsb'],
  ['dedupe', 'licFeatDedupe'],
];

/* 未激活时的免费 / Pro 对照。
   免费那一列不是摆设 —— 局域网互传不限量、断点续传都在里面，
   那是我们对 LocalSend 的正面回应，也是这张表可信的前提：
   一张只写「Pro 有、免费没有」的表，用户第一反应是被阉割了。 */
const LIC_COMPARE = [
  ['licRowLan', 'licUnlimited', 'licUnlimited'],
  ['licRowResume', 'licYes', 'licYes'],
  ['licRowFileSize', 'licFree4G', 'licUnlimited'],
  ['licRowExport', 'licFree1000', 'licUnlimited'],
  ['licRowUsb', 'licNo', 'licYes'],
  ['licRowSync', 'licNo', 'licYes'],
  ['licRowDedupe', 'licNo', 'licYes'],
];

let licStatus = null;

/* 套餐。价格要和官网 site/src/data/plans.ts 保持一致 ——
   两处都硬编码是有意的：客户端不该为了显示一行价格去连服务器，
   那样没网就变成空白。改价时记得两边一起改。 */
const LIC_PLANS = [
  { id: 'year', price: '5.99', name: 'licPlanYear', hint: '' },
  { id: 'years3', price: '11.99', name: 'licPlanYears3', hint: 'licPlanHint3' },
  { id: 'lifetime', price: '14.99', name: 'licPlanLifetime', hint: 'licPlanHintLife', featured: true },
];

function licRender() {
  const st = licStatus || { active: false, features: [] };
  const btn = $('#licBtn');
  const btnText = $('#licBtnText');
  const state = $('#licState');

  if (btn) btn.classList.toggle('on', !!st.active);
  if (btnText) btnText.textContent = st.active ? 'Pro' : t('licActivate');

  if (state) {
    state.classList.toggle('on', !!st.active);
    state.classList.toggle('warn', !!st.expired);
    if (st.expired) state.textContent = t('licExpired');
    else if (st.active) state.textContent = `${t('licPro')} · ${st.email || ''}`.trim();
    else state.textContent = t('licFree');
  }

  const active = !!st.active && !st.expired;

  // 解锁清单只在未激活时没用；激活之后对比表本身就说明了一切，
  // 再列一遍是重复。
  const list = $('#licFeatures');
  if (list) list.hidden = true;

  /* 对比表两个状态都显示。
     以前激活之后就把它藏了，换成一排勾 —— 那恰恰把最该强化价值的时刻
     浪费掉了：付了钱的人看不到自己比免费版多拿了什么。
     现在激活后 Pro 那一列标成「你的」并高亮，差额一直摆在那儿。 */
  const cmp = $('#licCompare');
  if (cmp) {
    cmp.hidden = false;
    cmp.classList.toggle('owned', active);
    licRenderCompare(cmp, active);
  }

  // 激活后在表头上方点一句「你解锁了多少」，比让用户自己数强
  const sum = $('#licOwned');
  if (sum) {
    sum.hidden = !active;
    if (active) {
      const n = LIC_COMPARE.filter(([, free, pro]) => free !== pro).length;
      sum.textContent = t('licOwnedSummary').replace('%n', n);
    }
  }

  // 已激活就不必再看到输入框和套餐，但过期时要留着，方便直接续期
  const showBuy = !st.active || st.expired;
  const row = $('#licInputRow');
  if (row) row.hidden = !showBuy;
  const plans = $('#licPlans');
  if (plans) plans.hidden = !showBuy;
  const rm = $('#licRemove');
  if (rm) rm.hidden = !st.active && !st.expired;
  const hint = $('#licCodeHint');
  if (hint) hint.hidden = !showBuy;
}

/* 两列对照表。列宽在 CSS 里定死，各行的数字才对得齐。 */
function licRenderCompare(box, owned) {
  box.innerHTML = '';

  const head = document.createElement('div');
  head.className = 'lic-cmp-row lic-cmp-head';
  head.innerHTML = '<span></span><b class="free"></b><b class="pro"></b>';
  head.querySelector('.free').textContent = t('licColFree');
  head.querySelector('.pro').textContent = owned ? t('licColMine') : t('licColPro');
  box.appendChild(head);

  for (const [name, free, pro] of LIC_COMPARE) {
    const row = document.createElement('div');
    // 免费和 Pro 不一样的行才是「买到的东西」，标出来
    row.className = 'lic-cmp-row' + (free !== pro ? ' gain' : '');
    row.innerHTML = '<span></span><b class="free"></b><b class="pro"></b>';
    row.querySelector('span').textContent = t(name);
    row.querySelector('.free').textContent = t(free);
    row.querySelector('.pro').textContent = t(pro);
    box.appendChild(row);
  }
}

function licRenderPlans() {
  const box = $('#licPlans');
  if (!box) return;
  box.innerHTML = '';
  for (const p of LIC_PLANS) {
    const el = document.createElement('button');
    el.type = 'button';
    el.className = 'lic-plan' + (p.featured ? ' featured' : '');
    el.dataset.plan = p.id;
    el.innerHTML =
      (p.featured ? '<span class="p-badge"></span>' : '') +
      '<span class="p-name"></span>' +
      '<span class="p-price"><i>$</i></span>' +
      '<span class="p-hint"></span>';
    if (p.featured) el.querySelector('.p-badge').textContent = t('licBest');
    el.querySelector('.p-name').textContent = t(p.name);
    el.querySelector('.p-price').append(p.price);
    el.querySelector('.p-hint').textContent = p.hint ? t(p.hint) : '';
    el.addEventListener('click', () => licBuy(p.id));
    box.appendChild(el);
  }
}

/* 购买。外链必须由后端用系统命令打开 —— 界面跑在内嵌 WebView 里，
   <a target="_blank"> 点了没有任何反应。 */
async function licBuy(plan) {
  licMsg(t('licOpening'), true);
  try {
    const res = await api('/api/license/buy', { body: JSON.stringify({ plan, lang: state.lang }) });
    if (!res || !res.success) licMsg(t('licOpenFailed'));
    else setTimeout(() => licMsg(''), 2500);
  } catch (_) {
    licMsg(t('licOpenFailed'));
  }
}

async function licRefresh() {
  try {
    licStatus = await api('/api/license', { method: 'GET' });
  } catch (_) {
    licStatus = null;
  }
  licRender();
}

function licMsg(text, ok) {
  const el = $('#licMsg');
  if (!el) return;
  el.textContent = text || '';
  el.hidden = !text;
  el.classList.toggle('ok', !!ok);
}

async function licActivate() {
  const input = $('#licCode');
  const btn = $('#licActivate');
  const code = (input.value || '').trim();
  if (!code) { licMsg(t('licNeedCode')); return; }

  btn.disabled = true;
  licMsg('');
  try {
    const res = await api('/api/license/activate', { body: JSON.stringify({ code }) });
    if (res && res.success) {
      licStatus = res.status;
      input.value = '';
      licMsg(t('licOk'), true);
      licRender();
    } else {
      licMsg((res && res.error) || t('licNeedCode'));
    }
  } catch (e) {
    licMsg(String((e && e.message) || e));
  } finally {
    btn.disabled = false;
  }
}

async function licDeactivate() {
  // 一键把付过钱的许可证从这台机器上删掉，之前居然没有任何确认
  if (!await askConfirm({
    title: t('askDeactivateTitle'), note: t('askDeactivateNote'),
    ok: t('licRemove'), danger: true,
  })) return;
  try {
    const res = await api('/api/license/deactivate', { body: '{}' });
    licStatus = (res && res.status) || null;
    licMsg(t('licRemoved'), true);
    licRender();
  } catch (e) {
    licMsg(String((e && e.message) || e));
  }
}

function licOpen() {
  licMsg('');
  licRenderPlans();
  $('#licModal').hidden = false;
  licRefresh();
  // 未激活时直接把光标放进输入框，少一次点击
  if (!licStatus || !licStatus.active) setTimeout(() => $('#licCode') && $('#licCode').focus(), 40);
}

function licClose() { $('#licModal').hidden = true; }

function licBind() {
  const on = (sel, ev, fn) => { const el = $(sel); if (el) el.addEventListener(ev, fn); };
  on('#licBtn', 'click', licOpen);
  on('#licBuy', 'click', () => licBuy('lifetime'));
  on('#licClose', 'click', licClose);
  on('#licBackdrop', 'click', licClose);
  on('#licActivate', 'click', licActivate);
  on('#licRemove', 'click', licDeactivate);
  on('#licCode', 'keydown', (e) => { if (e.key === 'Enter') licActivate(); });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !$('#licModal').hidden) licClose();
  });
  licRenderPlans();
  licRefresh();

  // #lic 直达。将来 Pro 功能被点到时，提示里可以直接给这个链接跳过来。
  if (location.hash === '#lic') licOpen();
  window.addEventListener('hashchange', () => { if (location.hash === '#lic') licOpen(); });
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', licBind);
} else {
  licBind();
}


/* ==========================================================================
   照片图库
   --------------------------------------------------------------------------
   系统自带的导出器导几千张会崩、每两千张里丢一百张，而且图库是黑盒，
   用户在访达里按原始文件名根本找不到自己的照片。我们绕开它。

   这一屏最重要的不是「导出」按钮，是那个「只在 iCloud」的数字 ——
   开了「优化储存空间」的机器上，本地可能一张原片都没有。
   静默少导几千张、让用户以为备份好了，是这类工具最恶劣的失败方式。
   ========================================================================== */

let plScanData = null;
let plPoll = null;
/* 没勾中的那些。默认全选，所以只需要记「排除了谁」——
   几万张的库里，记「选了谁」会是个巨大的集合。 */
let plExcluded = new Set();
let plLoaded = 0;
/* 免费额度，0 表示不限。扫描时拿到，用来在开始之前就把话说清楚。 */
let plFreeLimit = 0;

function plShow(which) {
  for (const id of ['plIdle', 'plResult', 'plRunning']) {
    const el = $('#' + id);
    if (el) el.hidden = id !== which;
  }
}

function plMsg(text, kind) {
  const el = $('#plMsg');
  if (!el) return;
  el.hidden = !text;
  el.textContent = text || '';
  el.classList.toggle('ok', kind === 'ok');
  el.classList.toggle('err', kind === 'err');

  /* 没权限时光说「去系统设置里打开」是把用户扔在半路上 ——
     那个开关埋在五层菜单下面，很多人翻不到就放弃了。 */
  const grant = $('#plGrant');
  if (grant) grant.hidden = !(text && /完全磁盘访问|Full Disk Access/.test(text));
}

/* 进这一屏先看有没有正在跑的导出 —— 用户可能切走过又切回来 */
async function plEnter() {
  const st = await api('/api/photoslib/status').catch(() => null);
  if (st && st.running) {
    plShow('plRunning');
    plStartPolling();
    return;
  }
  if (!plScanData) {
    plShow('plIdle');
    plLoadLibraries();
  }
}

/* 把找到的图库列出来。只有一个（绝大多数人）就不占版面，
   直接扫那个 —— 多一步选择对他们是纯负担。 */
let plLibPath = '';
async function plLoadLibraries() {
  const box = $('#plLibs');
  if (!box) return;
  const r = await api('/api/photoslib/libraries').catch(() => null);
  const libs = (r && r.libraries) || [];
  box.innerHTML = '';
  plLibPath = r && r.current ? r.current : '';

  if (libs.length <= 1) {
    box.hidden = true;
    if (!libs.length) plMsg(t('plNoLib'));
    return;
  }
  box.hidden = false;

  const title = document.createElement('p');
  title.className = 'pl-libs-title';
  title.textContent = t('plLibsTitle');
  box.appendChild(title);

  // 上次用的那个优先选中；没有就用默认库
  if (!libs.some((l) => l.path === plLibPath)) {
    plLibPath = (libs.find((l) => l.default) || libs[0]).path;
  }

  for (const lib of libs) {
    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'pl-lib' + (lib.path === plLibPath ? ' on' : '');
    const tags = [];
    if (lib.default) tags.push(t('plLibDefault'));
    if (lib.external) tags.push(t('plLibExternal'));
    row.innerHTML =
      '<span class="pl-lib-name"></span>' +
      '<span class="pl-lib-meta"></span>';
    row.querySelector('.pl-lib-name').textContent = lib.name;
    // 体积比名字更能帮人认出「装着十年照片的那个」
    row.querySelector('.pl-lib-meta').textContent =
      [fmtBytes(lib.bytes) || '—', ...tags].join(' · ');
    row.title = lib.path;
    row.addEventListener('click', () => {
      plLibPath = lib.path;
      $$('#plLibs .pl-lib').forEach((x) => x.classList.remove('on'));
      row.classList.add('on');
    });
    box.appendChild(row);
  }
}

async function plScan() {
  const btn = $('#plScan');
  if (btn) { btn.disabled = true; btn.textContent = t('plScanning'); }
  plMsg('');
  try {
    const q = plLibPath ? '?library=' + encodeURIComponent(plLibPath) : '';
    const r = await api('/api/photoslib/scan' + q);
    if (!r || !r.success) throw new Error((r && r.error) || 'scan failed');
    plScanData = r;
    plExcluded = new Set();
    plPicked = new Set();
    plMode = 'except';
    plLoaded = 0;
    $('#plGrid').innerHTML = '';
    $('#plGrid').hidden = true;
    $('#plMore').hidden = true;
    $('#plToggleGrid').textContent = t('plShowGrid');
    plRenderScan(r);
    plShow('plResult');
  } catch (e) {
    plMsg(String(e.message || e), 'err');
    plShow('plIdle');
  } finally {
    if (btn) { btn.disabled = false; btn.textContent = t('plScan'); }
  }
}

function plRenderScan(r) {
  $('#plExportable').textContent = r.exportable;
  $('#plCloud').textContent = r.in_cloud_only;
  $('#plLive').textContent = r.live_photos;
  $('#plSize').textContent = fmtBytes(r.total_bytes);

  // 上次用的目标目录和选项，不用每次重指一遍
  plFreeLimit = r.free_limit || 0;
  if (r.output) $('#plOut').value = r.output;
  $('#plLowerExt').checked = !!r.lower_ext;

  const warn = $('#plCloudWarn');
  if (warn) {
    warn.hidden = !r.in_cloud_only;
    warn.textContent = t('plCloudWarn').replace('%n', r.in_cloud_only);
  }

  plUpdatePickCount();
  const bar = $('#plPickbar');
  if (bar) bar.hidden = !r.exportable;

  $('#plPath').textContent = '';
  if (!r.exportable) plMsg(t('plNothing'));
  else if (!r.can_export) plMsg(t('plNeedPro'));
  else plMsg('');
}

function plSelectedCount() {
  if (!plScanData) return 0;
  return plMode === 'except' ? plScanData.exportable - plExcluded.size : plPicked.size;
}

function plUpdatePickCount() {
  const el = $('#plPickCount');
  if (!el || !plScanData) return;
  const picked = plSelectedCount();
  el.textContent = t('plPicked')
    .replace('%n', picked)
    .replace('%t', plScanData.exportable);

  const btn = $('#plExport');
  if (btn) btn.disabled = picked === 0;

  /* 超额要在按钮上就说清楚，别等跑完才告诉用户「只导了一部分」——
     那时候他已经等完了，还得自己想明白该再点一次。 */
  const over = plFreeLimit > 0 && picked > plFreeLimit;
  const notice = $('#plQuota');
  if (notice) {
    notice.hidden = !over;
    if (over) {
      notice.textContent = t('plQuotaNotice')
        .replace('%n', plFreeLimit)
        .replace('%t', picked)
        .replace('%r', picked - plFreeLimit);
    }
  }
  const up = $('#plQuotaUp');
  if (up) up.hidden = !over;
  if (btn && !btn.disabled) {
    btn.textContent = over
      ? t('plQuotaBtn').replace('%n', plFreeLimit)
      : t('plExport');
  }
}

/* 网格。一次只取 200 条 —— 几万张一股脑塞进 DOM 会把界面卡死，
   用户会以为软件挂了。 */
async function plLoadMore() {
  const r = await api(`/api/photoslib/items?offset=${plLoaded}&limit=200`).catch(() => null);
  if (!r || !r.success) return;
  const grid = $('#plGrid');
  for (const it of r.items) {
    const cell = document.createElement('button');
    cell.type = 'button';
    cell.className = 'pl-cell' + (plIsOn(it.uuid) ? ' on' : '');
    // 逐格错开一点入场，超过 24 格就不再延迟，否则末尾要等太久
    cell.style.animationDelay = Math.min(grid.children.length, 24) * 12 + 'ms';
    cell.dataset.uuid = it.uuid;
    cell.title = `${it.name} · ${it.date}`;
    const img = document.createElement('img');
    img.loading = 'lazy';
    img.src = '/api/photoslib/thumb?uuid=' + encodeURIComponent(it.uuid);
    img.alt = it.name;
    cell.appendChild(img);
    if (it.live) {
      const b = document.createElement('span');
      b.className = 'pl-badge';
      b.textContent = 'LIVE';
      cell.appendChild(b);
    }
    cell.addEventListener('click', () => {
      plToggle(it.uuid);
      cell.classList.toggle('on', plIsOn(it.uuid));
      plUpdatePickCount();
    });
    grid.appendChild(cell);
  }
  plLoaded = r.offset + r.items.length;
  $('#plMore').hidden = plLoaded >= r.total;
}

async function plToggleGrid() {
  const grid = $('#plGrid');
  const btn = $('#plToggleGrid');
  if (grid.hidden) {
    grid.hidden = false;
    btn.textContent = t('plHideGrid');
    if (!plLoaded) await plLoadMore();
  } else {
    grid.hidden = true;
    $('#plMore').hidden = true;
    btn.textContent = t('plShowGrid');
  }
}

/* 选择状态。
   'except' —— 默认：全选，excluded 里是被取消的那几张
   'only'   —— 用户点了「全不选」之后：picked 里是他挑中的那几张

   两种模式而不是一个集合，是为了让前端不必持有全部 uuid：
   五万张的库光 uuid 就近 2MB，为了取消两张而把它们全记下来没道理。 */
let plMode = 'except';
let plPicked = new Set();

function plIsOn(uuid) {
  return plMode === 'except' ? !plExcluded.has(uuid) : plPicked.has(uuid);
}

function plToggle(uuid) {
  if (plMode === 'except') {
    if (plExcluded.has(uuid)) plExcluded.delete(uuid);
    else plExcluded.add(uuid);
  } else if (plPicked.has(uuid)) {
    plPicked.delete(uuid);
  } else {
    plPicked.add(uuid);
  }
}

function plSetAll(on) {
  plMode = on ? 'except' : 'only';
  plExcluded = new Set();
  plPicked = new Set();
  $$('#plGrid .pl-cell').forEach((c) => c.classList.toggle('on', on));
  plUpdatePickCount();
}

async function plExport() {
  plMsg('');
  // 一张都没动就什么都不传，后端按「全部」处理。
  const body = {
    output_dir: $('#plOut').value.trim() || undefined,
    lower_ext: $('#plLowerExt').checked,
  };
  if (plMode === 'except' && plExcluded.size) body.excluded = [...plExcluded];
  if (plMode === 'only') body.selected = [...plPicked];

  const r = await api('/api/photoslib/export', {
    body: JSON.stringify(body),
  }).catch((e) => ({ error: String(e) }));

  if (!r || !r.success) {
    // 402 是没激活 Pro。直接把授权弹层打开，别让用户自己去找在哪买。
    if (r && r.feature) {
      plMsg(t('plNeedPro'));
      licOpen();
      return;
    }
    plMsg((r && r.error) || 'export failed', 'err');
    return;
  }
  $('#plPath').textContent = t('plOutput').replace('%p', r.output);
  plShow('plRunning');
  plStartPolling();
}

function plStartPolling() {
  if (plPoll) clearInterval(plPoll);
  plPoll = setInterval(plTick, 400);
  plTick();
}

async function plTick() {
  const st = await api('/api/photoslib/status').catch(() => null);
  if (!st) return;

  const pct = st.total ? Math.round((st.done / st.total) * 100) : 0;
  const fill = $('#plBarFill');
  if (fill) fill.style.width = pct + '%';
  $('#plProgress').textContent = `${st.done} / ${st.total}  ·  ${fmtBytes(st.bytes || 0)}`;
  $('#plCurrent').textContent = st.current || '';

  if (st.finished || !st.running) {
    clearInterval(plPoll);
    plPoll = null;
    plShow('plResult');
    if (st.error) {
      plMsg(st.error, 'err');
    } else {
      let m = t('plDone').replace('%n', st.copied || st.done).replace('%p', st.output || '');
      if (st.failed) m += ' · ' + t('plFailed').replace('%n', st.failed);
      plMsg(m, 'ok');
      // 因为额度停下来的，要说清楚「再点一次会接着导」，
      // 否则用户以为导完了，剩下的就永远留在图库里
      if (st.limit_hit) {
        plMsg(t('plQuotaDone').replace('%n', st.copied || st.done), 'ok');
        const up = $('#plQuotaUp');
        if (up) up.hidden = false;
      }
      // 「会导到 xxx」和「导完了：存在 xxx」说的是同一件事，留一个
      $('#plPath').textContent = '';
      // 导完只给一行路径，用户还得自己去找 —— 差的就是这一下
      const rev = $('#plReveal');
      if (rev) rev.hidden = false;
    }
  }
}

$('#plScan')?.addEventListener('click', plScan);
$('#plRescan')?.addEventListener('click', plScan);
$('#plExport')?.addEventListener('click', plExport);
$('#plCancel')?.addEventListener('click', () => api('/api/photoslib/cancel', { body: '{}' }));
$('#plToggleGrid')?.addEventListener('click', plToggleGrid);
$('#plMore')?.addEventListener('click', plLoadMore);
$('#plAll')?.addEventListener('click', () => plSetAll(true));
$('#plNone')?.addEventListener('click', () => plSetAll(false));
$('#plReveal')?.addEventListener('click', () => api('/api/photoslib/reveal', { body: '{}' }));
$('#plGrant')?.addEventListener('click', () => api('/api/photoslib/open_privacy', { body: '{}' }));
$('#plQuotaUp')?.addEventListener('click', () => licOpen());

/* 传输面板收起，回到相册列表 */
$('#xferBack')?.addEventListener('click', () => {
  $('#xferBar').classList.add('hidden');
  xferFocus(false);
  xferBarSynced = false;
});

/* ==========================================================================
   多设备切换
   --------------------------------------------------------------------------
   后端一直支持（/api/devices、/api/select_device），但前端从来没调过 ——
   插着两台机器时用户只看得到当前那台的名字，另一台等于不存在。
   ========================================================================== */

let usbDevices = [];

async function usbSyncDeviceList(dev) {
  const many = (dev.devices || []).length > 1;
  const caret = $('#usbDeviceCaret');
  const btn = $('#usbDeviceBtn');
  if (caret) caret.classList.toggle('hidden', !many);
  if (btn) {
    btn.classList.toggle('switchable', many);
    btn.title = many ? t('usbPickDevice') : t('usbOneDevice');
  }
  if (!many) {
    usbDevices = [];
    closeDeviceMenu();
    return;
  }
  const r = await api('/api/devices').catch(() => null);
  usbDevices = (r && r.devices) || [];
}

function closeDeviceMenu() {
  const m = $('#usbDeviceMenu');
  if (m) m.hidden = true;
}

function renderDeviceMenu() {
  const m = $('#usbDeviceMenu');
  if (!m || usbDevices.length < 2) return;
  m.innerHTML = '';
  for (const d of usbDevices) {
    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'usb-device-item' + (d.serial === state.usbSelected ? ' on' : '');
    row.innerHTML = '<span class="d-name"></span><span class="d-serial"></span>';
    row.querySelector('.d-name').textContent = d.name || d.serial;
    // 型号可能重名（两台同款手机），序列号是唯一能区分的东西
    row.querySelector('.d-serial').textContent = d.serial;
    row.addEventListener('click', () => selectDevice(d.serial));
    m.appendChild(row);
  }
  m.hidden = false;
}

async function selectDevice(serial) {
  closeDeviceMenu();
  if (serial === state.usbSelected) return;
  $('#usbDevice').textContent = t('usbSwitching');
  await api('/api/select_device', { body: JSON.stringify({ serial }) });
  // 换了设备，之前扫出来的相册和勾选全都不作数了
  state.selectedAlbums.clear();
  state.selectedPhotos.clear();
  state.albums = {};
  $('#albumGrid').innerHTML = '';
  $('#usbChips')?.classList.add('hidden');
  await refreshUsb();
}

$('#usbDeviceBtn')?.addEventListener('click', (e) => {
  e.stopPropagation();
  if (usbDevices.length < 2) return;
  const m = $('#usbDeviceMenu');
  if (m && !m.hidden) closeDeviceMenu();
  else renderDeviceMenu();
});
document.addEventListener('click', (e) => {
  if (!$('#usbDeviceMenu')?.contains(e.target)) closeDeviceMenu();
});
