(function (global) {
  const STORAGE_KEY = 'droidtrans.lang';

  const STRINGS = {
    zh: {
      app_name: '卓传',
      app_tagline: '安卓快传',
      app_full_title: '卓传 · 安卓快传',
      header_subtitle: '高性能 · 多模式 · 智能传输',
      choose_mode: '选择传输模式',
      choose_mode_sub: '选择最适合您的传输方式',
      usb_mode: 'USB 模式',
      usb_mode_desc: '有线连接，高速稳定<br>支持大批量快速传输<br>需开启 USB 调试',
      wifi_mode: 'WiFi 模式',
      wifi_mode_desc: '无线传输，便捷灵活<br>无需数据线连接<br>需在同一局域网',
      lang_zh: '中文',
      lang_en: 'EN',

      back_modes: '返回模式选择',
      wifi_header: '卓传 - WiFi 模式',
      wifi_header_sub: '无线传输 · 便捷灵活 · 实时同步',
      server_address: '服务器地址',
      fetching: '正在获取...',
      wifi_tips: '使用说明：确保手机和电脑在同一WiFi网络，打开APP会自动扫描并连接到服务器，选择照片后即可上传',
      device: '设备',
      batch: '批次',
      actions: '操作',
      loading_ellipsis: '正在加载...',
      select_device_first: '请先选择设备',
      delete_batch_title: '删除当前批次及其所有照片',
      refresh: '刷新',
      open: '打开',
      save_dir: '保存目录',
      save_path_placeholder: '照片保存路径',
      save: '保存',
      reset: '重置',
      received_photos: '已接收的照片',
      no_device: '未选择设备',
      photo_count: '{n} 张',
      no_photos: '暂无照片',
      no_photos_hint: '手机端上传后会自动显示在这里',
      first_page: '首页',
      prev_page: '上一页',
      next_page: '下一页',
      last_page: '尾页',
      page_info: '第 {current} 页 / 共 {total} 页',
      no_device_upload: '暂无设备上传',
      device_option: '{name} ({count} 张照片)',
      no_upload_records: '暂无上传记录',
      batch_label: '{time} ({count} 张照片, {size} MB)',
      batch_legacy: '{time} - {count} 张照片, {size} MB',
      preview: '预览',
      open_folder: '打开文件夹',
      delete: '删除',
      delete_photo_confirm: '确定要删除照片 "{name}" 吗？此操作不可恢复！',
      photo_deleted: '照片已删除',
      delete_photo_failed: '删除照片失败',
      delete_batch_confirm: '确定要删除批次 "{name}" 及其所有照片吗？\n\n此操作不可恢复！所有照片将被永久删除！',
      load_batch_failed: '加载批次照片失败',

      usb_header: '卓传 - USB 模式',
      usb_header_sub: '通过 USB 连接传输照片 · 高速稳定',
      usb_usage_tip: '使用提示：必须在手机上开启开发者模式和USB调试，否则无法传输照片。',
      how_to_enable: '查看如何开启 →',
      usb_debug_required: '必须开启 USB 调试',
      usb_debug_required_text: '检测到您的设备未开启开发者模式或未允许USB调试，无法正常传输照片。请立即开启USB调试功能。',
      view_guide: '查看开启教程',
      i_know: '我知道了',
      check_device: '检查设备',
      scan_photos: '扫描照片',
      start_transfer: '开始传输',
      resume_transfer: '继续传输',
      pause: '暂停',
      resume: '继续',
      stop_transfer: '停止传输',
      disconnected: '未连接',
      connected: '已连接',
      developer_guide: '开发者模式教程',
      history_backup: '历史备份记录',
      more_settings: '更多配置',
      output_dir: '输出目录',
      choose_dir: '选择目录',
      restore_default: '恢复默认',
      open_folder_btn: '打开文件夹',
      waiting_start: '等待开始...',
      speed: '速度: {n} MB/s',
      transferred: '已传输: {done} / {total} MB',
      eta: '预计剩余: {t}',
      total_eta: '总预计: {t}',
      photo_total: '照片总数',
      selected: '已选择',
      total_size: '总大小',
      selected_size: '已选大小',
      album_view: '相册视图',
      list_view: '列表视图',
      search_photos: '搜索照片名称...',
      sort_newest: '最新优先',
      sort_oldest: '最旧优先',
      sort_name_az: '名称 A-Z',
      sort_name_za: '名称 Z-A',
      sort_size_desc: '大小降序',
      sort_size_asc: '大小升序',
      no_photos_usb: '暂无照片',
      no_photos_usb_hint: '请先检查设备连接，然后扫描照片',
      transfer_in_progress: '传输进行中，请勿操作',
      prompt: '提示',
      confirm_action: '确认操作',
      ok: '确定',
      cancel: '取消',
      device_not_connected: '设备未连接',
      connect_then_scan: '请连接设备后点击“扫描照片”',
      ready_to_transfer: '准备好传输照片与视频',
      scanning_photos: '正在扫描手机照片，请稍候...',
      finding_photos: '正在查找照片文件...',
      getting_info: '正在获取照片信息...',
      unfinished_transfer: '发现未完成的传输',
      unfinished_transfer_msg: '发现未完成的传输任务：\n\n总文件: {total}\n已完成: {completed}\n剩余: {pending}\n失败: {failed}\n\n要如何处理？',
      no_device_title: '未检测到设备',
      usb_speed_fail_title: 'USB速度测试失败',
      usb_speed_fail_msg: 'USB速度测试失败，可能原因：\n\n1. USB连接不稳定\n2. 手机权限限制\n3. ADB传输异常\n\n建议：\n• 重新插拔USB线\n• 切换USB端口\n• 更换USB线缆\n\n请选择如何继续：',
      how_usb_debug: '如何开启 USB 调试',
      adb_abnormal: 'ADB 状态异常',
      device_disconnected: '设备已断开',
      resume_failed: '恢复传输失败: {err}',

      upload_in_progress: '照片上传中',
      uploading_to_server: '正在将照片传输到服务器',
      total_files: '总文件数',
      completed: '已完成',
      failed: '失败',
      preparing_upload: '准备上传...',
      upload_complete: '上传完成！',
      all_uploaded: '所有照片已成功上传到服务器',
      cancel_upload: '取消上传',
      done: '完成',
      no_upload_activity: '暂无上传活动',
      wait_phone_upload: '等待手机开始上传照片...',
      uploading: '上传中',
      uploading_file: '正在上传第 {current} / {total} 个文件',
      uploaded_ok: '成功上传 {n} 张照片到服务器',
      partial_upload: '部分上传完成',
      partial_upload_desc: '成功上传 {ok} 张，失败 {fail} 张照片',
      cancel_upload_confirm: '确定要通知手机端取消上传吗？',
      cancel_requested: '已请求取消上传',
      cancel_failed: '取消上传失败',
      load_progress_failed: '加载上传进度失败',
    },
    en: {
      app_name: 'DroidTrans',
      app_tagline: 'Fast Android Transfer',
      app_full_title: 'DroidTrans',
      header_subtitle: 'Fast · Multi-mode · Smart transfer',
      choose_mode: 'Choose a transfer mode',
      choose_mode_sub: 'Pick the method that fits you best',
      usb_mode: 'USB Mode',
      usb_mode_desc: 'Wired, fast and stable<br>Great for large batches<br>USB debugging required',
      wifi_mode: 'Wi-Fi Mode',
      wifi_mode_desc: 'Wireless and flexible<br>No cable needed<br>Same local network required',
      lang_zh: '中文',
      lang_en: 'EN',

      back_modes: 'Back to modes',
      wifi_header: 'DroidTrans - Wi-Fi',
      wifi_header_sub: 'Wireless · Flexible · Live sync',
      server_address: 'Server address',
      fetching: 'Fetching...',
      wifi_tips: 'Make sure the phone and computer are on the same Wi-Fi. Open the app to scan and connect, then select photos to upload.',
      device: 'Device',
      batch: 'Batch',
      actions: 'Actions',
      loading_ellipsis: 'Loading...',
      select_device_first: 'Select a device first',
      delete_batch_title: 'Delete this batch and all of its photos',
      refresh: 'Refresh',
      open: 'Open',
      save_dir: 'Save folder',
      save_path_placeholder: 'Photo save path',
      save: 'Save',
      reset: 'Reset',
      received_photos: 'Received photos',
      no_device: 'No device selected',
      photo_count: '{n}',
      no_photos: 'No photos yet',
      no_photos_hint: 'Photos appear here after the phone uploads them',
      first_page: 'First',
      prev_page: 'Prev',
      next_page: 'Next',
      last_page: 'Last',
      page_info: 'Page {current} of {total}',
      no_device_upload: 'No devices have uploaded yet',
      device_option: '{name} ({count} photos)',
      no_upload_records: 'No upload records',
      batch_label: '{time} ({count} photos, {size} MB)',
      batch_legacy: '{time} - {count} photos, {size} MB',
      preview: 'Preview',
      open_folder: 'Open folder',
      delete: 'Delete',
      delete_photo_confirm: 'Delete photo "{name}"? This cannot be undone.',
      photo_deleted: 'Photo deleted',
      delete_photo_failed: 'Failed to delete photo',
      delete_batch_confirm: 'Delete batch "{name}" and all of its photos?\n\nThis cannot be undone.',
      load_batch_failed: 'Failed to load batch photos',

      usb_header: 'DroidTrans - USB',
      usb_header_sub: 'Transfer photos over USB · Fast and stable',
      usb_usage_tip: 'Tip: Enable Developer options and USB debugging on the phone, or transfer will fail.',
      how_to_enable: 'How to enable →',
      usb_debug_required: 'USB debugging required',
      usb_debug_required_text: 'Developer options or USB debugging is off. Enable USB debugging to transfer photos.',
      view_guide: 'View guide',
      i_know: 'Got it',
      check_device: 'Check device',
      scan_photos: 'Scan photos',
      start_transfer: 'Start transfer',
      resume_transfer: 'Resume transfer',
      pause: 'Pause',
      resume: 'Resume',
      stop_transfer: 'Stop',
      disconnected: 'Disconnected',
      connected: 'Connected',
      developer_guide: 'Developer options guide',
      history_backup: 'Backup history',
      more_settings: 'More settings',
      output_dir: 'Output folder',
      choose_dir: 'Choose folder',
      restore_default: 'Restore default',
      open_folder_btn: 'Open folder',
      waiting_start: 'Waiting to start...',
      speed: 'Speed: {n} MB/s',
      transferred: 'Transferred: {done} / {total} MB',
      eta: 'ETA: {t}',
      total_eta: 'Total ETA: {t}',
      photo_total: 'Photos',
      selected: 'Selected',
      total_size: 'Total size',
      selected_size: 'Selected size',
      album_view: 'Albums',
      list_view: 'List',
      search_photos: 'Search photo names...',
      sort_newest: 'Newest first',
      sort_oldest: 'Oldest first',
      sort_name_az: 'Name A-Z',
      sort_name_za: 'Name Z-A',
      sort_size_desc: 'Size descending',
      sort_size_asc: 'Size ascending',
      no_photos_usb: 'No photos yet',
      no_photos_usb_hint: 'Check the device connection, then scan photos',
      transfer_in_progress: 'Transfer in progress. Please wait.',
      prompt: 'Notice',
      confirm_action: 'Confirm',
      ok: 'OK',
      cancel: 'Cancel',
      device_not_connected: 'Device not connected',
      connect_then_scan: 'Connect a device, then tap Scan photos',
      ready_to_transfer: 'Ready to transfer photos and videos',
      scanning_photos: 'Scanning photos on the phone...',
      finding_photos: 'Finding photo files...',
      getting_info: 'Getting photo info...',
      unfinished_transfer: 'Unfinished transfer found',
      unfinished_transfer_msg: 'Unfinished transfer:\n\nTotal: {total}\nDone: {completed}\nRemaining: {pending}\nFailed: {failed}\n\nWhat do you want to do?',
      no_device_title: 'No device detected',
      usb_speed_fail_title: 'USB speed test failed',
      usb_speed_fail_msg: 'USB speed test failed. Possible causes:\n\n1. Unstable USB connection\n2. Phone permission limits\n3. ADB transfer error\n\nTry:\n• Unplug and replug the cable\n• Switch USB ports\n• Use another cable\n\nHow do you want to continue?',
      how_usb_debug: 'How to enable USB debugging',
      adb_abnormal: 'ADB status abnormal',
      device_disconnected: 'Device disconnected',
      resume_failed: 'Failed to resume transfer: {err}',

      upload_in_progress: 'Uploading photos',
      uploading_to_server: 'Transferring photos to the server',
      total_files: 'Total files',
      completed: 'Done',
      failed: 'Failed',
      preparing_upload: 'Preparing upload...',
      upload_complete: 'Upload complete!',
      all_uploaded: 'All photos were uploaded to the server',
      cancel_upload: 'Cancel upload',
      done: 'Done',
      no_upload_activity: 'No upload in progress',
      wait_phone_upload: 'Waiting for the phone to start uploading...',
      uploading: 'Uploading',
      uploading_file: 'Uploading {current} / {total}',
      uploaded_ok: 'Uploaded {n} photos to the server',
      partial_upload: 'Partial upload complete',
      partial_upload_desc: 'Uploaded {ok}, failed {fail}',
      cancel_upload_confirm: 'Ask the phone to cancel the upload?',
      cancel_requested: 'Cancel requested',
      cancel_failed: 'Failed to cancel upload',
      load_progress_failed: 'Failed to load upload progress',
    }
  };

  function detectLang() {
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      if (saved === 'zh' || saved === 'en') return saved;
    } catch (e) {}
    const nav = (navigator.language || navigator.userLanguage || 'zh').toLowerCase();
    return nav.startsWith('zh') ? 'zh' : 'en';
  }

  let current = detectLang();

  function t(key, vars) {
    const table = STRINGS[current] || STRINGS.zh;
    let s = table[key];
    if (s == null) s = (STRINGS.zh[key] || key);
    if (vars) {
      Object.keys(vars).forEach((k) => {
        s = s.replace(new RegExp('\\{' + k + '\\}', 'g'), vars[k]);
      });
    }
    return s;
  }

  function apply(root) {
    const scope = root || document;
    scope.querySelectorAll('[data-i18n]').forEach((el) => {
      el.innerHTML = t(el.getAttribute('data-i18n'));
    });
    scope.querySelectorAll('[data-i18n-placeholder]').forEach((el) => {
      el.setAttribute('placeholder', t(el.getAttribute('data-i18n-placeholder')));
    });
    scope.querySelectorAll('[data-i18n-title]').forEach((el) => {
      el.setAttribute('title', t(el.getAttribute('data-i18n-title')));
    });
    document.title = t(document.documentElement.getAttribute('data-i18n-title-page') || 'app_full_title');
    document.documentElement.lang = current === 'zh' ? 'zh-CN' : 'en';
    document.querySelectorAll('[data-lang]').forEach((btn) => {
      btn.classList.toggle('active', btn.getAttribute('data-lang') === current);
    });
  }

  function setLang(lang) {
    current = lang === 'en' ? 'en' : 'zh';
    try { localStorage.setItem(STORAGE_KEY, current); } catch (e) {}
    apply();
  }

  function langSwitcherHtml() {
    return '<div class="lang-switcher" role="group" aria-label="Language">' +
      '<button type="button" data-lang="zh" onclick="I18N.setLang(\'zh\')">中文</button>' +
      '<button type="button" data-lang="en" onclick="I18N.setLang(\'en\')">EN</button>' +
      '</div>';
  }

  const style = document.createElement('style');
  style.textContent = `
    .lang-switcher { display:inline-flex; border:1px solid #dadce0; border-radius:16px; overflow:hidden; flex-shrink:0; white-space:nowrap; }
    .lang-switcher button { border:0; background:#fff; color:#5f6368; padding:6px 12px; font-size:12px; cursor:pointer; white-space:nowrap; width:auto !important; flex:none !important; }
    .lang-switcher button.active { background:#1a73e8; color:#fff; }
    h1, h2, h3 { word-break: keep-all; line-break: strict; }
  `;
  document.head.appendChild(style);

  global.I18N = { t, apply, setLang, langSwitcherHtml, get lang() { return current; } };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => apply());
  } else {
    apply();
  }
})(window);
