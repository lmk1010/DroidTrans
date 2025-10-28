#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import os
import sys
import json
import subprocess
import threading
import socket
import sqlite3
import hashlib
from pathlib import Path, PurePosixPath
from flask import Flask, render_template, jsonify, request, send_file
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor, as_completed
import threading
from werkzeug.utils import secure_filename
import re

# ADB Burst Mode 配置
ADB_BURST_MODE_ENABLED = os.getenv('ADB_BURST_MODE', '1')  # 默认启用Burst模式
FAST_ALBUM_SCAN = os.getenv('FAST_ALBUM_SCAN', '1') in ('1','true','on','yes')  # 仅相册封面快速扫描

app = Flask(__name__)

# 线程锁，保护 transfer_status 的并发更新
transfer_status_lock = threading.Lock()

# 配置 - M4 Mac 超级并发优化
# 智能选择输出目录：开发环境用本地，打包后用用户文档目录
def get_output_dir():
    """获取合适的输出目录"""
    # 检查是否在打包后的应用中运行
    if getattr(sys, 'frozen', False):
        # 打包后：使用用户的文档目录
        home = os.path.expanduser("~")
        output_dir = os.path.join(home, "Documents", "AndroidTransfer")
    else:
        # 开发环境：使用当前目录
        output_dir = "./photos_output"
    
    # 确保目录存在
    os.makedirs(output_dir, exist_ok=True)
    return output_dir

OUTPUT_DIR = get_output_dir()  # 照片输出目录
BATCH_PREVIEW_DIR_NAME = 'previews'
PREVIEW_DIR = os.path.join(OUTPUT_DIR, BATCH_PREVIEW_DIR_NAME)
os.makedirs(PREVIEW_DIR, exist_ok=True)
THUMB_DIR = os.path.join(PREVIEW_DIR, 'thumbs')
os.makedirs(THUMB_DIR, exist_ok=True)
THUMB_CACHE_MAX_MB = int(os.getenv('THUMB_CACHE_MAX_MB', '1024'))  # 默认 1GB 上限
PREVIEW_ORIG_MAX_MB = int(os.getenv('PREVIEW_ORIG_MAX_MB', '8192'))  # 原图缓存上限 8GB
BATCH_SIZE = 50  # 每批传输的照片数量（已废弃，使用并发传输）
MAX_RETRIES = 2  # 最大重试次数（降低以加快失败文件的处理）

# 并发配置：打包后降低并发数以防止内存泄漏
if getattr(sys, 'frozen', False):
    # 打包后：保守配置，防止内存泄漏
    MAX_WORKERS = 4  # 降低到 4 线程（防止 OOM）
    SCAN_CONCURRENT = 4  # 扫描并发数
    SCAN_BATCH_SIZE = 100  # 每批扫描文件数
else:
    # 开发环境：高性能配置
    MAX_WORKERS = 16  # 并发传输线程数（M4 Mac超级优化：16线程！）
    SCAN_CONCURRENT = 12  # 扫描并发数（M4 Mac优化：12线程）
    SCAN_BATCH_SIZE = 500  # 每批扫描文件数（M4 Mac优化：500文件/批）

# 性能优化开关
SKIP_SIZE_CHECK = False  # 是否跳过已存在文件的大小检查（加速但不推荐）
FAST_MODE = True  # 极速模式：减少stat调用，直接传输

# 断点续传配置
PROGRESS_FILE = "./transfer_progress.json"  # 进度保存文件
AUTO_SAVE_INTERVAL = 100  # 每传输多少个文件自动保存一次进度

# 全局状态
transfer_status = {
    'is_running': False,
    'total': 0,
    'current': 0,
    'failed': [],
    'current_file': '',
    'error': None,
    'completed_files': set(),  # 已完成的文件路径集合
    'output_dir': OUTPUT_DIR,
    'bytes_total': 0,
    'bytes_done': 0,
    'start_ts': 0.0,
    'speed_mbps': 0.0,
    'elapsed_sec': 0.0,
    'eta_sec': 0.0,
    'speed_samples': [],  # 存储速度样本，用于计算平均值
    'speed_estimated': False,  # 是否已完成速度估算
    'usb_info': {}  # 存储USB设备信息
}

scan_status = {
    'is_running': False,
    'stage': 'idle',  # idle, finding, getting_info, done
    'current_dir': '',
    'files_found': 0,
    'files_processed': 0,
    'total_files': 0,
    'photos': [],
    'error': None,
    'albums_preview': {},
    'albums_map': {},
    'thumbs_total': 0,
    'thumbs_done': 0
}

# 设备连接状态
device_status = {
    'connected': False,
    'devices': [],
    'last_check_time': None,
    'disconnect_detected': False,  # 标记是否检测到设备断开
    'fail_count': 0,               # 连续检测失败次数（ADB异常时使用）
    'adb_issue': False,            # ADB 调用异常
    'adb_error': ''
}

# 当前选中的ADB设备序列号
selected_device = None

# 设备监控线程启动标记
device_monitor_started = False

# WiFi模式状态
wifi_mode_status = {
    'enabled': False,
    'connected_devices': {},  # 连接的设备字典 {device_id: {'name': '', 'last_heartbeat': timestamp, 'connected_at': timestamp}}
    'photos_received': 0,
    'last_sync_time': None,
    'output_dir': OUTPUT_DIR  # WiFi模式的输出目录
}

# USB 速率缓存（按当前选中设备缓存一次，断开或切换设备后清空）
usb_speed_cache = {
    'device': None,
    'data': None,
    'ts': 0,
}

# 缩略图后台执行器与任务去重
THUMB_MAX_WORKERS = int(os.getenv('THUMB_MAX_WORKERS', '8'))
thumb_executor = ThreadPoolExecutor(max_workers=max(2, min(32, THUMB_MAX_WORKERS)))
thumb_inflight = set()  # set of remote_path

# 按设备存储照片记录（按批次组织）
device_photos = {}  # 旧格式，保留兼容
device_upload_batches = {}  # {device_id: [{'batch_id': '', 'timestamp': '', 'photos': [...], 'total_size': 0}, ...]}

# 上传进度跟踪（按设备）
upload_progress_data = {}  # {device_id: {'files': [...], 'current_index': 0, 'completed': 0, 'failed': 0, 'is_uploading': False, 'batch_id': ''}}

# 批次信息持久化 - 使用SQLite数据库
# 数据库也放在用户文档目录
def get_db_path():
    """获取数据库文件路径"""
    if getattr(sys, 'frozen', False):
        # 打包后：数据库放在用户文档目录
        home = os.path.expanduser("~")
        db_dir = os.path.join(home, "Documents", "AndroidTransfer")
        os.makedirs(db_dir, exist_ok=True)
        return os.path.join(db_dir, 'android_transfer.db')
    else:
        # 开发环境：放在当前目录
        return os.path.join(os.path.dirname(__file__), 'android_transfer.db')

DB_FILE = get_db_path()

# 设备心跳超时时间（秒）
DEVICE_TIMEOUT = 300  # 5分钟

INCLUDE_PICTURES_DIRS = os.environ.get('INCLUDE_PICTURES_DIRS', '0').strip().lower() in {'1', 'true', 'yes', 'on'}

# 手机照片常见目录（按别名整理，默认仅扫描 DCIM 相关路径，避免重复）
BASE_PHOTO_DIRS = [
    '/sdcard/DCIM',
    '/sdcard/DCIM/Camera',
    '/sdcard/DCIM/Screenshots',
    '/sdcard/Screenshots',
    '/sdcard/Download',
    '/storage/emulated/0/DCIM',
    '/storage/emulated/0/DCIM/Camera',
    '/storage/emulated/0/DCIM/Screenshots',
    '/storage/emulated/0/Screenshots',
    '/storage/self/primary/DCIM',
    '/storage/self/primary/DCIM/Camera',
    '/storage/self/primary/DCIM/Screenshots',
    '/storage/self/primary/Screenshots',
]

PICTURE_DIRS = [
    '/sdcard/Pictures',
    '/sdcard/Pictures/Camera',
    '/sdcard/Pictures/Screenshots',
    '/storage/emulated/0/Pictures',
    '/storage/emulated/0/Pictures/Camera',
    '/storage/emulated/0/Pictures/Screenshots',
    '/storage/self/primary/Pictures',
    '/storage/self/primary/Pictures/Camera',
    '/storage/self/primary/Pictures/Screenshots',
]

PHOTO_DIRS = BASE_PHOTO_DIRS.copy()

if INCLUDE_PICTURES_DIRS:
    PHOTO_DIRS.extend(PICTURE_DIRS)

SUPPORTED_EXTENSIONS = {
    # 图片格式
    '.jpg', '.jpeg', '.jpe', '.jif', '.jfif', '.jfi',
    '.png', '.gif', '.bmp', '.webp', '.wbmp', '.ico',
    '.heic', '.heics', '.heif', '.avif', '.svg', '.svgz',
    '.tif', '.tiff', '.psd', '.ai', '.eps', '.raw', '.dng',
    '.raf', '.crw', '.cr2', '.cr3', '.nef', '.nrw', '.arw',
    '.sr2', '.srf', '.srw', '.pef', '.orf', '.rw2', '.kdc',
    '.mos', '.mef', '.mrw', '.x3f', '.3fr', '.erf', '.iiq',
    '.bay', '.rwl', '.r3d', '.cap', '.fff', '.ptx', '.pxn',
    '.dcr', '.dcs', '.rwz', '.jp2', '.j2k', '.jpf', '.jpx', '.jpm', '.mj2', '.dds', '.exr',
    # 动图/实况照片
    '.mpo', '.livp',
    # 视频格式
    '.mp4', '.m4v', '.mov', '.qt', '.3gp', '.3g2', '.3gpp', '.3gp2',
    '.mkv', '.webm', '.avi', '.wmv', '.flv', '.mts', '.m2ts', '.ts',
    '.mxf', '.mpg', '.mpeg', '.mpv', '.ogv', '.hevc', '.h265', '.h264',
}

# 预览仅支持的图片扩展名（封面用）
PREVIEW_IMAGE_EXTS = {'.jpg', '.jpeg', '.png', '.gif', '.webp', '.bmp', '.heic', '.heif'}
PREVIEW_VIDEO_EXTS = {'.mp4', '.m4v', '.mov', '.3gp', '.3g2', '.webm', '.mkv', '.avi', '.flv'}


def has_supported_extension(file_path):
    """判断文件是否为支持的照片/视频格式"""
    suffix = PurePosixPath(file_path).suffix.lower()
    return suffix in SUPPORTED_EXTENSIONS


REMOTE_PATH_ALIASES = [
    ('/sdcard/', '/storage/emulated/0/'),
    ('/storage/self/primary/', '/storage/emulated/0/'),
    ('/storage/emulated/legacy/', '/storage/emulated/0/'),
]


def normalize_remote_path(path):
    """统一远程路径以移除常见别名，便于去重"""
    if not path:
        return path

    normalized = path
    for alias, canonical in REMOTE_PATH_ALIASES:
        if normalized.startswith(alias):
            normalized = canonical + normalized[len(alias):]
            break

    try:
        return str(PurePosixPath(normalized))
    except Exception:
        return normalized.rstrip('/')

def save_progress(photos, output_dir, completed_files, failed_files):
    """保存传输进度到文件"""
    try:
        progress_data = {
            'timestamp': datetime.now().isoformat(),
            'output_dir': output_dir,
            'total': len(photos),
            'completed': len(completed_files),
            'completed_files': list(completed_files),
            'failed_files': failed_files,
            'pending_photos': [p for p in photos if p['path'] not in completed_files]
        }
        
        with open(PROGRESS_FILE, 'w', encoding='utf-8') as f:
            json.dump(progress_data, f, ensure_ascii=False, indent=2)
        
        return True
    except Exception as e:
        print(f"⚠️ 保存进度失败: {str(e)}")
        return False

def load_progress():
    """加载上次的传输进度"""
    try:
        if not os.path.exists(PROGRESS_FILE):
            return None
        
        with open(PROGRESS_FILE, 'r', encoding='utf-8') as f:
            progress_data = json.load(f)
        
        # 转换completed_files为set
        progress_data['completed_files'] = set(progress_data.get('completed_files', []))
        return progress_data
    except Exception as e:
        print(f"⚠️ 加载进度失败: {str(e)}")
        return None

def clear_progress():
    """清除进度文件"""
    try:
        if os.path.exists(PROGRESS_FILE):
            os.remove(PROGRESS_FILE)
        return True
    except Exception as e:
        print(f"⚠️ 清除进度失败: {str(e)}")
        return False

def _maybe_inject_serial(command: str) -> str:
    """在adb命令中注入 -s <serial>，对 'adb devices'/'adb start-server'/'adb kill-server' 跳过。"""
    try:
        cmd = command.strip()
        if not cmd.startswith('adb'):
            return command
        # 跳过无需设备绑定的命令
        for prefix in ('adb devices', 'adb start-server', 'adb kill-server'):
            if cmd.startswith(prefix):
                return command
        if selected_device:
            return command.replace('adb ', f'adb -s {selected_device} ', 1)
        return command
    except Exception:
        return command


def run_adb_command(command, timeout=30, enable_burst=None):
    """执行ADB命令，支持Burst Mode"""
    try:
        command = _maybe_inject_serial(command)

        # 确定是否启用Burst模式
        burst_enabled = ADB_BURST_MODE_ENABLED == '1' if enable_burst is None else enable_burst

        # 设置环境变量以启用Burst模式
        env = os.environ.copy()
        if burst_enabled:
            env['ADB_DELAYED_ACK'] = '1'

        result = subprocess.run(
            command,
            shell=True,
            capture_output=True,
            text=True,
            timeout=timeout,
            env=env
        )
        return result.returncode == 0, result.stdout, result.stderr
    except subprocess.TimeoutExpired:
        return False, "", "命令超时"
    except Exception as e:
        return False, "", str(e)

def check_adb_connection():
    """检查ADB连接状态并更新全局设备状态"""
    global device_status, selected_device

    success, stdout, stderr = run_adb_command("adb devices")
    was_connected = device_status.get('connected', False)
    prev_devices = set(device_status.get('devices', []))

    connected = False
    devices = []

    if success and stdout:
        lines = stdout.strip().split('\n')
        if len(lines) > 1:
            devices = [line.split('\t')[0] for line in lines[1:] if '\tdevice' in line]
            connected = len(devices) > 0
        device_status['fail_count'] = 0
        device_status['adb_issue'] = False
        device_status['adb_error'] = ''
    else:
        # ADB失败：快速二次确认，避免长时间误判
        retry_ok, retry_out, _ = run_adb_command("adb devices", timeout=3)
        if retry_ok and retry_out:
            lines = retry_out.strip().split('\n')
            if len(lines) > 1:
                devices = [line.split('\t')[0] for line in lines[1:] if '\tdevice' in line]
                connected = len(devices) > 0
            device_status['fail_count'] = 0
            device_status['adb_issue'] = False
            device_status['adb_error'] = ''
        else:
            device_status['fail_count'] = device_status.get('fail_count', 0) + 1
            device_status['adb_issue'] = True
            device_status['adb_error'] = (stderr.decode('utf-8', errors='ignore') if isinstance(stderr, (bytes, bytearray)) else str(stderr)) or 'ADB不可用'
            if device_status['fail_count'] < 2 and was_connected:
                # 暂时保留上次状态一小段时间，避免抖动
                connected = was_connected
                devices = list(prev_devices)
            else:
                connected = False
                devices = []

    # 检查设备状态变化
    was_connected_devices = set(device_status.get('devices', []))
    current_devices = set(devices)

    # 更新全局设备状态
    device_status['connected'] = connected
    device_status['devices'] = devices
    device_status['last_check_time'] = datetime.now().isoformat()

    # 检测新设备插入
    if connected and not was_connected:
        device_status['connect_detected'] = True
        print(f"\n🎉 检测到新设备连接: {', '.join(devices)}")
        # 设备变更：清空USB速率缓存
        try:
            usb_speed_cache['device'] = None
            usb_speed_cache['data'] = None
            usb_speed_cache['ts'] = 0
        except Exception:
            pass
    elif connected and was_connected:
        # 检查是否有新设备插入（多个设备情况）
        new_devices = current_devices - was_connected_devices
        if new_devices:
            device_status['connect_detected'] = True
            print(f"\n🎉 检测到新设备连接: {', '.join(new_devices)}")

    # 如果之前连接但现在断开，标记断开事件
    if not connected and device_status.get('was_connected', False):
        device_status['disconnect_detected'] = True
        print(f"\n⚠️  检测到设备断开！")

    device_status['was_connected'] = connected

    # 维护选中设备
    if connected:
        if not selected_device or selected_device not in devices:
            selected_device = devices[0]
    else:
        selected_device = None

    return connected, devices


def _device_monitor_loop(interval=2):
    """后台设备监控循环，周期性检查ADB设备连接状态"""
    import time
    while True:
        try:
            check_adb_connection()
        except Exception as _:
            # 避免监控线程因为异常退出
            pass
        time.sleep(max(1, int(interval)))


def start_device_monitor():
    """启动设备监控线程（只启动一次）"""
    global device_monitor_started
    if device_monitor_started:
        return
    try:
        t = threading.Thread(target=_device_monitor_loop, kwargs={'interval': 2}, daemon=True)
        t.start()
        device_monitor_started = True
        print("🔎 已启动设备监控线程（2s轮询）")
    except Exception as e:
        print(f"⚠️ 启动设备监控线程失败: {e}")


# 注意：Flask 3.0 移除了 before_first_request，这里不使用该钩子。

def get_local_ip():
    """获取本机局域网IP地址"""
    try:
        # 创建一个UDP socket
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        # 连接到外部地址（不会真正发送数据）
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"

def organize_photos_by_album(photos):
    """按文件夹组织照片为相册"""
    albums = {}

    # 定义相册映射规则
    album_mappings = {
        # 相机相册
        'Camera': {
            'paths': ['/DCIM/Camera', '/Pictures/Camera', '/DCIM/100MEDIA', '/DCIM/100ANDRO'],
            'name': '相机',
            'icon': '📷',
            'priority': 1
        },
        # 截屏相册
        'Screenshots': {
            'paths': ['/DCIM/Screenshots', '/Pictures/Screenshots', '/Screenshots', '/DCIM/Screenshots/Instagram'],
            'name': '截屏',
            'icon': '📱',
            'priority': 2
        },
        # 微信相册
        'WeChat': {
            'paths': ['/Pictures/WeiXin', '/Pictures/WeChat', '/Pictures/微信', '/Pictures/tencent/MicroMsg/WeiXin'],
            'name': '微信',
            'icon': '💬',
            'priority': 3
        },
        # 知乎相册
        'Zhihu': {
            'paths': ['/Pictures/Zhihu', '/Pictures/知乎'],
            'name': '知乎',
            'icon': '📚',
            'priority': 4
        },
        # 美团相册
        'Meituan': {
            'paths': ['/Pictures/Meituan', '/Pictures/美团'],
            'name': '美团',
            'icon': '🥡',
            'priority': 5
        },
        # 微博相册
        'Weibo': {
            'paths': ['/Pictures/Weibo', '/Pictures/微博', '/Pictures/sina/weibo'],
            'name': '微博',
            'icon': '🌟',
            'priority': 6
        },
        # QQ相册
        'QQ': {
            'paths': ['/Pictures/QQ', '/Pictures/Tencent', '/Pictures/tencent'],
            'name': 'QQ',
            'icon': '🐧',
            'priority': 7
        },
        # 下载相册
        'Download': {
            'paths': ['/Download', '/Pictures/Download'],
            'name': '下载',
            'icon': '⬇️',
            'priority': 8
        },
        # 其他相册
        'Others': {
            'paths': [],
            'name': '其他相册',
            'icon': '📁',
            'priority': 99
        }
    }

    # 初始化相册
    for album_key, album_info in album_mappings.items():
        albums[album_key] = {
            'name': album_info['name'],
            'icon': album_info['icon'],
            'priority': album_info['priority'],
            'photos': [],
            'total_count': 0,
            'total_size': 0
        }

    # 分类照片
    for photo in photos:
        photo_path = photo['path']
        categorized = False

        # 检查属于哪个相册
        for album_key, album_info in album_mappings.items():
            if album_key == 'Others':
                continue

            for path_pattern in album_info['paths']:
                if path_pattern in photo_path:
                    albums[album_key]['photos'].append(photo)
                    albums[album_key]['total_count'] += 1
                    albums[album_key]['total_size'] += photo['size']
                    categorized = True
                    break

            if categorized:
                break

        # 如果没有匹配到任何相册，归为其他
        if not categorized:
            albums['Others']['photos'].append(photo)
            albums['Others']['total_count'] += 1
            albums['Others']['total_size'] += photo['size']

    # 过滤掉空的相册（除了"其他"相册）
    filtered_albums = {}
    for album_key, album_data in albums.items():
        if album_data['total_count'] > 0:
            filtered_albums[album_key] = album_data

    return filtered_albums

def scan_photos_thread():
    """后台扫描照片线程 - 极速优化版"""
    global scan_status

    scan_status['is_running'] = True
    scan_status['stage'] = 'finding'
    scan_status['files_found'] = 0
    scan_status['files_processed'] = 0
    scan_status['total_files'] = 0
    scan_status['photos'] = []
    scan_status['albums'] = {}
    scan_status['error'] = None
    scan_status['albums_preview'] = {}
    scan_status['albums_map'] = {}

    seen_files = set()
    seen_dirs = set()

    try:
        print("\n" + "=" * 60)
        print("🚀 开始极速扫描照片和视频...")
        print("=" * 60)

        # 检查设备连接状态
        print("\n🔌 检查设备连接...")
        connected, devices = check_adb_connection()
        if not connected:
            print("   ✗ 设备未连接")
            scan_status['error'] = '设备未连接，请检查USB连接和调试权限'
            scan_status['stage'] = 'error'
            scan_status['is_running'] = False
            return
        print(f"   ✓ 设备已连接: {', '.join(devices)}")

        # 首先检测实际存在的主目录
        print("\n🔍 检测存储路径...")
        storage_paths = ['/sdcard', '/storage/emulated/0', '/storage/self/primary']
        actual_storage = None

        for path in storage_paths:
            check_cmd = f'adb shell "test -d {path} && echo EXISTS"'
            success, stdout, _ = run_adb_command(check_cmd, timeout=5)
            if success and 'EXISTS' in stdout:
                actual_storage = path
                print(f"   ✓ 找到存储路径: {actual_storage}")
                break

        if not actual_storage:
            print("   ✗ 未找到有效的存储路径")
            scan_status['error'] = '无法访问手机存储，请检查USB调试权限'
            scan_status['stage'] = 'error'
            scan_status['is_running'] = False
            return

        # 仅扫描内部存储的 Pictures 目录（避免全盘扫描导致手机卡顿）
        pictures_root = f"{actual_storage}/Pictures"

        # 检查 Pictures 是否存在
        check_pictures_cmd = f'adb shell "test -d {pictures_root} && echo EXISTS"'
        success, stdout, _ = run_adb_command(check_pictures_cmd, timeout=5)
        if not success or 'EXISTS' not in stdout:
            print("   ✗ 未找到 Pictures 目录，仅支持扫描内部存储的 Pictures 目录")
            scan_status['error'] = '未找到内部存储的 Pictures 目录，请确认手机路径 /storage/emulated/0/Pictures 是否存在'
            scan_status['stage'] = 'error'
            scan_status['is_running'] = False
            return

        # 列出一级子目录作为相册；若没有子目录，则扫描 Pictures 根目录
        list_albums_cmd = f'adb shell "find {pictures_root} -mindepth 1 -maxdepth 1 -type d 2>/dev/null"'
        success, stdout, _ = run_adb_command(list_albums_cmd, timeout=10)
        album_dirs = []
        if success and stdout.strip():
            album_dirs = [line.strip() for line in stdout.strip().split('\n') if line.strip()]
            # 过滤隐藏相册（以 . 开头的目录）
            album_dirs = [d for d in album_dirs if not os.path.basename(d.rstrip('/')).startswith('.')]
        else:
            album_dirs = [pictures_root]

        # 单独增加 DCIM/Camera（相机）相册，如果存在
        camera_dir = f"{actual_storage}/DCIM/Camera"
        has_camera = False
        cam_check_cmd = f'adb shell "test -d {camera_dir} && echo EXISTS"'
        ok_cam, cam_out, _ = run_adb_command(cam_check_cmd, timeout=5)
        if ok_cam and 'EXISTS' in cam_out:
            has_camera = True

        # 合并列表并去重
        all_album_dirs = []
        if has_camera:
            all_album_dirs.append(camera_dir)
        all_album_dirs.extend(album_dirs)
        # 确保 Pictures 根目录优先，再按名称排序子目录
        album_dirs = sorted(set(all_album_dirs), key=lambda p: (p != pictures_root and p != camera_dir, p.lower()))

        if FAST_ALBUM_SCAN:
            print("\n⚡ 启用快速相册扫描模式（仅封面与目录列表）")
            albums = {}
            for dir_path in album_dirs:
                if not scan_status['is_running']:
                    break
                # 选择封面（优先图片，其次任意文件）
                cover = ''
                try:
                    # -p 为目录名添加斜杠，便于过滤；-t 时间倒序
                    ok_ls, out_ls, _ = run_adb_command(f"adb shell 'ls -1pt " + dir_path.replace("'","'\''") + " 2>/dev/null'", timeout=6)
                    if ok_ls and out_ls:
                        entries = [l.strip() for l in out_ls.splitlines() if l.strip()]
                        files = [nm for nm in entries if not nm.endswith('/')]
                        # 先选图片封面
                        for nm in files:
                            p = dir_path.rstrip('/') + '/' + nm
                            if PurePosixPath(p).suffix.lower() in PREVIEW_IMAGE_EXTS:
                                cover = p
                                break
                        # 再选视频封面
                        if not cover:
                            for nm in files:
                                p = dir_path.rstrip('/') + '/' + nm
                                if PurePosixPath(p).suffix.lower() in PREVIEW_VIDEO_EXTS:
                                    cover = p
                                    break
                except Exception:
                    pass
                # 统计文件数（快速方式，可能包含少量非媒体）
                total_count = 0
                try:
                    ok_cnt, out_cnt, _ = run_adb_command(f"adb shell 'ls -1 " + dir_path.replace("'","'\''") + " 2>/dev/null | wc -l'", timeout=5)
                    if ok_cnt and out_cnt.strip().isdigit():
                        total_count = int(out_cnt.strip())
                except Exception:
                    pass
                # 统计目录大小（KB）
                total_size = 0
                try:
                    ok_sz, out_sz, _ = run_adb_command(f"adb shell 'du -sk " + dir_path.replace("'","'\''") + " 2>/dev/null'", timeout=6)
                    if ok_sz and out_sz.strip():
                        first = out_sz.strip().split()[0]
                        if first.isdigit():
                            total_size = int(first) * 1024
                except Exception:
                    pass
                album_name = os.path.basename(dir_path.rstrip('/')) or 'Pictures'
                albums[dir_path] = {
                    'name': album_name,
                    'icon': '📁',
                    'priority': 10,
                    'photos': [],  # 延迟加载
                    'total_count': total_count,
                    'total_size': total_size,
                    'cover': cover
                }
            scan_status['albums'] = albums
            scan_status['stage'] = 'done'
            # 异步封面缩略图生成
            try:
                covers = [a.get('cover') for a in albums.values() if a.get('cover')]
                print(f"🎯 封面预生成(后台): {len(covers)}")
                for c in covers:
                    try:
                        lp = _preview_local_path(c)
                        tp = _thumb_local_path(c, size=256)
                        if os.path.exists(tp) and os.path.getsize(tp) > 0:
                            continue
                        thumb_executor.submit(lambda p=c, l=lp, t=tp: _ensure_local_and_thumb(p, l, t, -1))
                    except Exception:
                        pass
            except Exception:
                pass
            print(f"✅ 快速相册扫描完成: {len(albums)} 个相册")
            return

        for dir_path in album_dirs:
            if not scan_status['is_running']:
                break

            # 定期检查设备连接状态
            connected, _ = check_adb_connection()
            if not connected:
                print(f"\n❌ 设备已断开，停止扫描")
                scan_status['error'] = '设备连接已断开'
                scan_status['stage'] = 'error'
                scan_status['is_running'] = False
                return

            normalized_dir = normalize_remote_path(dir_path)

            if normalized_dir in seen_dirs:
                continue
            seen_dirs.add(normalized_dir)

            scan_status['current_dir'] = dir_path
            print(f"\n📂 扫描相册目录: {dir_path}")

            # 先检查目录是否存在
            check_cmd = f'adb shell "test -d {dir_path} && echo EXISTS"'
            success, stdout, _ = run_adb_command(check_cmd, timeout=5)
            
            if not success or 'EXISTS' not in stdout:
                print(f"   ⊗ 目录不存在，跳过")
                continue

            # 超级并发优化：先快速find，再暴力并发stat
            print(f"   → ⚡ 第1步：快速查找文件...")
            
            # 第1步：快速查找所有文件（只要文件名，不要详情）
            find_cmd = (
                f'adb shell "'
                f'find {dir_path} -type f \\( '
                f'-iname \"*.jpg\" -o -iname \"*.jpeg\" -o -iname \"*.png\" -o '
                f'-iname \"*.mp4\" -o -iname \"*.mov\" -o -iname \"*.3gp\" -o '
                f'-iname \"*.heic\" -o -iname \"*.heif\" -o -iname \"*.gif\" -o '
                f'-iname \"*.webp\" -o -iname \"*.bmp\" -o -iname \"*.dng\" -o '
                f'-iname \"*.raw\" -o -iname \"*.m4v\" -o -iname \"*.avi\" '
                f'\\) 2>/dev/null"'
            )
            
            success, stdout, stderr = run_adb_command(find_cmd, timeout=180)
            
            if not success or not stdout.strip():
                print(f"   ⊗ 未找到文件")
                continue
            
            # 获取文件列表
            files = [f.strip() for f in stdout.strip().split('\n') if f.strip()]
            
            if not files:
                print(f"   ⊗ 未找到文件")
                continue
            
            print(f"   ✓ 找到 {len(files)} 个文件")
            print(f"   → ⚡ 第2步：{SCAN_CONCURRENT*2}线程暴力并发获取信息...")
            
            # 第2步：超高并发批量获取文件信息（限定当前相册目录）
            batch_size = 300  # 每批300个文件
            max_concurrent = SCAN_CONCURRENT * 2  # 加倍并发数（24线程）
            previous_count = len(scan_status['photos'])
            album_photos = []
            
            def ultra_fast_stat(batch_files, batch_index):
                """超高速批量stat"""
                results = []
                
                # 构建超大批量stat命令
                stat_cmds = []
                for f in batch_files:
                    # 简化转义，只处理必要的字符
                    escaped = f.replace('"', '\\"').replace('$', '\\$')
                    stat_cmds.append(f'stat -c "%n<SEP>%s<SEP>%Y" "{escaped}" 2>/dev/null')
                
                # 使用 && 连接命令（比分号更快）
                batch_cmd = f'adb shell \'{" && ".join(stat_cmds)}\''
                success, stdout, _ = run_adb_command(batch_cmd, timeout=90)
                
                if success and stdout:
                    for line in stdout.strip().split('\n'):
                        if '<SEP>' not in line:
                            continue
                        parts = line.rsplit('<SEP>', 2)
                        if len(parts) == 3:
                            try:
                                size = int(parts[1])
                                if size > 0:
                                    results.append({
                                        'path': parts[0],
                                        'size': size,
                                        'mtime': int(parts[2])
                                    })
                            except (ValueError, IndexError):
                                continue
                
                return batch_index, results
            
            # 创建批次
            batches = [(files[i:i+batch_size], i) for i in range(0, len(files), batch_size)]
            total_batches = len(batches)
            
            print(f"   → 启动 {max_concurrent} 个线程处理 {total_batches} 个批次")
            
            # 超高并发处理
            import time
            start = time.time()
            
            with ThreadPoolExecutor(max_workers=max_concurrent, thread_name_prefix='UltraFastScan') as executor:
                futures = {executor.submit(ultra_fast_stat, batch, idx): idx for batch, idx in batches}
                
                completed = 0
                last_log = 0
                
                for future in as_completed(futures):
                    if not scan_status['is_running']:
                        for f in futures:
                            f.cancel()
                        break
                    
                    try:
                        batch_idx, results = future.result(timeout=100)
                        
                        # 批量处理结果
                        for item in results:
                            file_path = item['path']
                            normalized_file = normalize_remote_path(file_path)
                            
                            if normalized_file not in seen_files and has_supported_extension(file_path):
                                seen_files.add(normalized_file)
                                photo_info = {
                                    'path': file_path,
                                    'name': os.path.basename(file_path),
                                    'size': item['size'],
                                    'size_mb': round(item['size'] / 1024 / 1024, 2),
                                    'mtime': item['mtime'],
                                    'date': datetime.fromtimestamp(item['mtime']).strftime('%Y-%m-%d %H:%M:%S') if item['mtime'] > 0 else 'Unknown'
                                }
                                scan_status['photos'].append(photo_info)
                                album_photos.append(photo_info)
                        
                        completed += 1
                        
                        # 每20%显示一次进度
                        progress = int(completed / total_batches * 100)
                        if progress - last_log >= 20 or completed == total_batches:
                            elapsed = time.time() - start
                            speed = (completed * batch_size) / elapsed if elapsed > 0 else 0
                            print(f"   ⚡ {progress}%完成 | 速度: {speed:.0f}文件/秒 | 已添加: {len(scan_status['photos']) - previous_count}")
                            last_log = progress
                        
                    except Exception as e:
                        completed += 1
                        continue
            
            added_count = len(scan_status['photos']) - previous_count
            elapsed_total = time.time() - start
            final_speed = added_count / elapsed_total if elapsed_total > 0 else 0
            print(f"   ✓ 完成！用时: {elapsed_total:.1f}秒 | 平均速度: {final_speed:.0f}文件/秒")
            scan_status['files_found'] = len(scan_status['photos'])
            scan_status['files_processed'] = len(scan_status['photos'])
            
            if added_count > 0:
                print(f"   ✓ 本相册新增 {added_count} 个文件（总计: {len(scan_status['photos'])}）")

            # 相册完成后，增量更新相册预览（仅该相册）
            try:
                album_name = os.path.basename(dir_path.rstrip('/')) or 'Pictures'
                total_size = sum(p['size'] for p in album_photos)
                cover_path = album_photos[0]['path'] if album_photos else ''
                if 'albums_preview' not in scan_status or not isinstance(scan_status['albums_preview'], dict):
                    scan_status['albums_preview'] = {}
                scan_status['albums_preview'][dir_path] = {
                    'name': album_name,
                    'icon': '📁',
                    'priority': 10,
                    'total_count': len(album_photos),
                    'total_size': total_size,
                    'cover': cover_path
                }
                # 保存该相册的完整照片列表，供最终返回
                scan_status['albums_map'][dir_path] = album_photos
            except Exception:
                pass

        # 不做兜底扫描：严格限定在 Pictures/Camera 范围，避免卡顿

        if not scan_status['photos']:
            print("\n⚠️  未找到任何照片/视频文件")
            print("💡 提示：请确保手机中存在照片或视频文件")
            scan_status['stage'] = 'done'
            scan_status['is_running'] = False
            return

        # 按时间排序
        scan_status['stage'] = 'getting_info'
        scan_status['total_files'] = len(scan_status['photos'])

        print(f"\n📊 正在排序 {len(scan_status['photos'])} 个文件...")
        scan_status['photos'].sort(key=lambda x: x['mtime'], reverse=True)

        # 使用目录相册（Pictures一级子目录）组织，而非类别聚合
        print(f"📁 正在整理相册（按目录）...")
        albums = {}
        for album_path, photos in scan_status.get('albums_map', {}).items():
            if not photos:
                continue
            album_name = os.path.basename(album_path.rstrip('/')) or 'Pictures'
            total_size = sum(p['size'] for p in photos)
            albums[album_path] = {
                'name': album_name,
                'icon': '📁',
                'priority': 10,
                'photos': photos,
                'total_count': len(photos),
                'total_size': total_size
            }
        # 如果没有子目录，仅将全部照片归为 Pictures
        if not albums and scan_status['photos']:
            total_size = sum(p['size'] for p in scan_status['photos'])
            albums['Pictures'] = {
                'name': 'Pictures',
                'icon': '📁',
                'priority': 10,
                'photos': scan_status['photos'],
                'total_count': len(scan_status['photos']),
                'total_size': total_size
            }
        scan_status['albums'] = albums

        # 统计信息
        total_size_mb = sum(p['size_mb'] for p in scan_status['photos'])
        video_extensions = ['.mp4', '.mov', '.3gp', '.avi', '.mkv', '.m4v', '.webm', '.flv']
        video_count = sum(1 for p in scan_status['photos'] if any(p['path'].lower().endswith(ext) for ext in video_extensions))
        image_count = len(scan_status['photos']) - video_count

        print(f"\n{'='*60}")
        print(f"✅ 扫描完成!")
        print(f"   📷 图片: {image_count} 张")
        print(f"   🎬 视频: {video_count} 个")
        print(f"   📦 总大小: {total_size_mb:.2f} MB ({total_size_mb/1024:.2f} GB)")
        print(f"   📁 相册数: {len(scan_status['albums'])} 个")

        # 显示相册统计
        for album_key, album_data in sorted(scan_status['albums'].items(), key=lambda x: x[1]['priority']):
            print(f"   {album_data['icon']} {album_data['name']}: {album_data['total_count']} 张 ({album_data['total_size']/1024/1024:.2f} MB)")

        # 默认不在扫描阶段批量生成缩略图（避免首屏等待）。
        # 相册封面优先生成（不阻塞）：每个相册选封面1张生成缩略图
        try:
            covers = []
            for album_key, album in scan_status.get('albums', {}).items():
                cover = None
                # 优先选择图片作为封面
                for p in album.get('photos', []):
                    ext = PurePosixPath(p['path']).suffix.lower()
                    if ext in PREVIEW_IMAGE_EXTS:
                        cover = p['path']
                        break
                if not cover and album.get('photos'):
                    cover = album['photos'][0]['path']
                if cover:
                    covers.append(cover)
            print(f"🎯 准备相册封面 {len(covers)} 张（后台生成）")
            def _submit_cover(path):
                try:
                    local_path = _preview_local_path(path)
                    thumb_path = _thumb_local_path(path, size=256)
                    if os.path.exists(thumb_path) and os.path.getsize(thumb_path) > 0:
                        return
                    thumb_executor.submit(lambda: _ensure_local_and_thumb(path, local_path, thumb_path, -1))
                except Exception:
                    pass
            for c in covers:
                _submit_cover(c)
        except Exception:
            pass

        print(f"{'='*60}\n")
        scan_status['stage'] = 'done'

    except Exception as e:
        import traceback
        error_msg = f"{str(e)}\n{traceback.format_exc()}"
        print(f"\n❌ 扫描出错: {error_msg}\n")
        scan_status['error'] = str(e)
        scan_status['stage'] = 'error'
    finally:
        scan_status['is_running'] = False

def transfer_photo(photo_path, output_dir, retries=0):
    """传输单个照片 - 极速优化版"""
    try:
        # 创建相对路径结构
        rel_path = photo_path.replace('/sdcard/', '').replace('/storage/emulated/0/', '').replace('/storage/self/primary/', '')
        local_path = os.path.join(output_dir, rel_path)
        local_dir = os.path.dirname(local_path)

        # 创建目录（使用exist_ok避免重复检查）
        os.makedirs(local_dir, exist_ok=True)

        # 极速模式：快速检查文件是否存在
        if os.path.exists(local_path):
            if SKIP_SIZE_CHECK:
                # 跳过大小检查，直接认为已存在
                return True, "已存在"
            
            # 快速大小检查（不查询远程，使用缓存的大小）
            local_size = os.path.getsize(local_path)
            if local_size > 0:  # 只要本地文件非空就跳过
                return True, "已存在"

        # 使用adb pull传输（优化超时时间）
        # 根据文件大小动态调整超时：小文件30秒，大文件适当延长
        timeout = 30 if retries == 0 else 45
        pull_cmd = f'adb pull "{photo_path}" "{local_path}"'
        success, stdout, stderr = run_adb_command(pull_cmd, timeout=timeout)

        if success:
            return True, "成功"
        else:
            # 快速重试（减少重试次数）
            if retries < MAX_RETRIES:
                return transfer_photo(photo_path, output_dir, retries + 1)
            return False, stderr or "传输失败"

    except Exception as e:
        if retries < MAX_RETRIES:
            return transfer_photo(photo_path, output_dir, retries + 1)
        return False, str(e)

def transfer_single_photo(photo, output_dir):
    """传输单个照片（用于并发）"""
    success, msg = transfer_photo(photo['path'], output_dir)
    return photo, success, msg

def transfer_photos_thread(photos, output_dir, resume=False):
    """后台传输照片线程 - M4超级并发优化版（支持断点续传）"""
    global transfer_status
    import time

    transfer_status['is_running'] = True
    transfer_status['total'] = len(photos)
    transfer_status['current'] = 0
    transfer_status['failed'] = []
    transfer_status['error'] = None
    transfer_status['output_dir'] = output_dir

    if not resume:
        transfer_status['completed_files'] = set()

    os.makedirs(output_dir, exist_ok=True)

    print(f"\n{'='*60}")
    if resume:
        print(f"🔄 恢复传输任务...")
        print(f"   已完成: {len(transfer_status['completed_files'])} 个")
        print(f"   剩余: {len(photos)} 个")
    else:
        print(f"🚀 M4超级并发传输 {len(photos)} 个文件...")
    print(f"   并发线程: {MAX_WORKERS} 个")
    print(f"   重试次数: {MAX_RETRIES} 次")
    print(f"   极速模式: {'开启' if FAST_MODE else '关闭'}")
    print(f"   自动保存: 每 {AUTO_SAVE_INTERVAL} 个文件")
    print(f"{'='*60}\n")

    # 检查设备连接状态
    print("🔌 检查设备连接...")
    connected, devices = check_adb_connection()
    if not connected:
        print("   ✗ 设备未连接，无法开始传输")
        transfer_status['error'] = '设备未连接，请检查USB连接'
        transfer_status['is_running'] = False
        return
    print(f"   ✓ 设备已连接: {', '.join(devices)}\n")

    start_time = time.time()
    # 初始化速度统计
    try:
        transfer_status['bytes_total'] = sum(int(p.get('size', 0)) for p in photos)
    except Exception:
        transfer_status['bytes_total'] = 0
    transfer_status['bytes_done'] = 0
    transfer_status['start_ts'] = start_time
    transfer_status['speed_mbps'] = 0.0
    transfer_status['elapsed_sec'] = 0.0
    transfer_status['eta_sec'] = 0.0
    transfer_status['bytes_total'] = sum(int(p.get('size', 0)) for p in photos)
    transfer_status['bytes_done'] = 0
    transfer_status['start_ts'] = start_time
    transfer_status['speed_mbps'] = 0.0
    transfer_status['speed_samples'] = []  # 存储速度样本，用于计算平均值
    transfer_status['speed_estimated'] = False  # 是否已完成速度估算
    skipped_count = 0
    last_save_count = 0
    
    # 预创建所有目录（避免并发时的目录创建冲突）
    print("📁 预创建目录结构...")
    unique_dirs = set()
    for photo in photos:
        rel_path = photo['path'].replace('/sdcard/', '').replace('/storage/emulated/0/', '').replace('/storage/self/primary/', '')
        local_path = os.path.join(output_dir, rel_path)
        unique_dirs.add(os.path.dirname(local_path))
    
    for dir_path in unique_dirs:
        os.makedirs(dir_path, exist_ok=True)
    
    print(f"✅ 已创建 {len(unique_dirs)} 个目录\n")

    # 使用线程池超高并发传输
    with ThreadPoolExecutor(max_workers=MAX_WORKERS, thread_name_prefix='TransferWorker') as executor:
        # 提交所有任务
        future_to_photo = {
            executor.submit(transfer_single_photo, photo, output_dir): photo 
            for photo in photos
        }

        last_progress = -1
        completed = 0
        last_device_check = time.time()
        device_check_interval = 5  # 每5秒检查一次设备连接

        # 处理完成的任务
        for future in as_completed(future_to_photo):
            if not transfer_status['is_running']:
                # 快速取消所有未完成的任务
                for f in future_to_photo:
                    f.cancel()
                break

            # 定期检查设备连接（每5秒检查一次）
            current_time = time.time()
            if current_time - last_device_check > device_check_interval:
                connected, _ = check_adb_connection()
                if not connected:
                    print(f"\n❌ 设备已断开，停止传输！")
                    transfer_status['error'] = '设备连接已断开'
                    transfer_status['is_running'] = False
                    # 取消所有未完成的任务
                    for f in future_to_photo:
                        f.cancel()
                    break
                last_device_check = current_time

            photo = future_to_photo[future]
            try:
                result_photo, success, msg = future.result(timeout=90)

                completed += 1
                transfer_status['current'] = completed
                transfer_status['current_file'] = result_photo['name']

                if not success:
                    transfer_status['failed'].append({
                        'path': result_photo['path'],
                        'error': msg
                    })
                    print(f"❌ 失败: {result_photo['name']} - {msg}")
                else:
                    # 记录已完成的文件
                    transfer_status['completed_files'].add(result_photo['path'])

                    if "已存在" in msg:
                        skipped_count += 1
                    else:
                        # 成功传输，累加字节并更新速度
                        try:
                            sz = int(result_photo.get('size', 0))
                        except Exception:
                            sz = 0
                        # 使用线程锁保护并发更新
                        with transfer_status_lock:
                            transfer_status['bytes_done'] = transfer_status.get('bytes_done', 0) + sz

                            # 只估算3次速度，然后取平均值
                            if not transfer_status.get('speed_estimated', False) and len(transfer_status.get('speed_samples', [])) < 3:
                                elapsed = max(0.001, time.time() - transfer_status.get('start_ts', start_time))
                                transfer_status['elapsed_sec'] = elapsed
                                current_speed = (transfer_status['bytes_done'] / 1024.0 / 1024.0) / elapsed
                                transfer_status['speed_samples'].append(current_speed)

                                if len(transfer_status['speed_samples']) == 3:
                                    # 计算3次样本的平均速度
                                    avg_speed = sum(transfer_status['speed_samples']) / len(transfer_status['speed_samples'])
                                    transfer_status['speed_mbps'] = avg_speed
                                    transfer_status['speed_estimated'] = True
                                    print(f"⚡ 速度估算完成: {avg_speed:.2f} MB/s")
                                else:
                                    # 使用当前速度作为临时显示
                                    transfer_status['speed_mbps'] = current_speed
                            elif transfer_status.get('speed_estimated', False):
                                # 估算完成后，不再更新 elapsed_sec，保持显示稳定
                                pass

                            if transfer_status['speed_mbps'] > 0:
                                remaining_mb = max(0.0, (transfer_status.get('bytes_total', 0) - transfer_status['bytes_done']) / 1024.0 / 1024.0)
                                transfer_status['eta_sec'] = remaining_mb / transfer_status['speed_mbps']
                
                # 定期自动保存进度
                if completed - last_save_count >= AUTO_SAVE_INTERVAL:
                    print(f"\n💾 自动保存进度... ({completed}/{transfer_status['total']})")
                    all_photos = photos  # 保存原始照片列表
                    save_progress(all_photos, output_dir, transfer_status['completed_files'], transfer_status['failed'])
                    last_save_count = completed
                
                # 优化进度显示：只在进度变化5%或每完成100个文件时显示
                progress = int(completed / transfer_status['total'] * 100)
                if (progress - last_progress >= 5) or (completed % 100 == 0) or (completed == transfer_status['total']):
                    elapsed = time.time() - start_time
                    speed = completed / elapsed if elapsed > 0 else 0
                    eta = (transfer_status['total'] - completed) / speed if speed > 0 else 0
                    
                    print(f"⚡ 进度: {progress}% ({completed}/{transfer_status['total']}) | "
                          f"速度: {speed:.1f}文件/秒 | "
                          f"预计剩余: {int(eta)}秒 | "
                          f"已跳过: {skipped_count}")
                    last_progress = progress

            except Exception as e:
                completed += 1
                transfer_status['current'] = completed
                transfer_status['failed'].append({
                    'path': photo['path'],
                    'error': str(e)
                })
                print(f"❌ 异常: {photo.get('name', 'Unknown')} - {str(e)}")

    transfer_status['is_running'] = False
    transfer_status['current_file'] = '完成'
    
    elapsed_time = time.time() - start_time
    success_count = transfer_status['total'] - len(transfer_status['failed'])
    avg_speed = transfer_status['total'] / elapsed_time if elapsed_time > 0 else 0
    
    # 最终保存进度
    print(f"\n💾 保存最终进度...")
    save_progress(photos, output_dir, transfer_status['completed_files'], transfer_status['failed'])
    
    print(f"\n{'='*60}")
    print(f"✅ 传输完成!")
    print(f"   总文件: {transfer_status['total']} 个")
    print(f"   成功: {success_count} 个")
    print(f"   跳过: {skipped_count} 个")
    print(f"   失败: {len(transfer_status['failed'])} 个")
    print(f"   总耗时: {int(elapsed_time)} 秒 ({elapsed_time/60:.1f} 分钟)")
    print(f"   平均速度: {avg_speed:.1f} 文件/秒")
    
    # 如果全部成功且没有失败，清除进度文件
    if len(transfer_status['failed']) == 0:
        print(f"\n🎉 所有文件传输成功，清除进度文件")
        clear_progress()
    else:
        print(f"\n💡 有 {len(transfer_status['failed'])} 个文件失败")
        print(f"   可以点击「继续传输」重试失败的文件")
    
    print(f"{'='*60}\n")

@app.route('/')
def index():
    """主页 - 模式选择"""
    return render_template('index.html')

@app.route('/usb')
def usb_mode():
    """USB模式页面"""
    default_output_dir = str(Path(OUTPUT_DIR).expanduser().resolve(strict=False))
    project_root = str(Path.cwd().resolve())
    return render_template('usb_mode.html', default_output_dir=default_output_dir, project_root=project_root, mode='usb')

@app.route('/wifi')
def wifi_mode():
    """WiFi模式页面"""
    default_output_dir = str(Path(OUTPUT_DIR).expanduser().resolve(strict=False))
    project_root = str(Path.cwd().resolve())
    return render_template('wifi_mode.html', default_output_dir=default_output_dir, project_root=project_root, mode='wifi')

@app.route('/upload_progress')
def upload_progress():
    """上传进度页面"""
    return render_template('upload_progress.html')


@app.route('/api/directories/list', methods=['POST'])
def list_directories():
    """列出指定目录下的子目录"""
    data = request.get_json(silent=True) or {}
    requested_path = data.get('path') or OUTPUT_DIR

    try:
        path_obj = Path(requested_path).expanduser()
        if not path_obj.is_absolute():
            path_obj = Path.cwd() / path_obj
        resolved_path = path_obj.resolve(strict=False)
    except Exception as exc:
        return jsonify({
            'success': False,
            'error': f'路径解析失败: {exc}'
        }), 400

    fallback_used = False

    if resolved_path.is_file():
        resolved_path = resolved_path.parent

    if not resolved_path.exists():
        fallback_used = True
        for parent in [resolved_path, *resolved_path.parents]:
            if parent.exists():
                resolved_path = parent
                break
        else:
            resolved_path = Path.cwd().resolve()

    entries = []
    try:
        for entry in sorted(resolved_path.iterdir(), key=lambda p: p.name.lower()):
            if entry.is_dir():
                entries.append({
                    'name': entry.name,
                    'path': str(entry.resolve(strict=False))
                })
    except PermissionError:
        return jsonify({
            'success': False,
            'error': '没有权限访问该目录'
        }), 403
    except FileNotFoundError:
        return jsonify({
            'success': False,
            'error': '目录不存在'
        }), 404

    parent_path = None
    if resolved_path.parent != resolved_path:
        parent_path = str(resolved_path.parent.resolve(strict=False))

    return jsonify({
        'success': True,
        'current': str(resolved_path),
        'parent': parent_path,
        'entries': entries,
        'fallback_used': fallback_used,
        'requested': requested_path
    })

@app.route('/api/check_device')
def check_device():
    """检查设备连接"""
    connected, devices = check_adb_connection()
    return jsonify({
        'connected': connected,
        'devices': devices
    })

@app.route('/api/device_status')
def get_device_status():
    """获取设备状态（包括连接和断开检测）"""
    global device_status
    # 确保监控线程已启动
    try:
        start_device_monitor()
    except Exception:
        pass
    # 主动刷新一次设备状态，提升响应速度
    try:
        check_adb_connection()
    except Exception:
        pass

    # 返回当前设备状态
    status_copy = {
        'connected': device_status['connected'],
        'devices': device_status['devices'],
        'selected': selected_device,
        'last_check_time': device_status['last_check_time'],
        'disconnect_detected': device_status.get('disconnect_detected', False),
        'connect_detected': device_status.get('connect_detected', False),
        'adb_issue': device_status.get('adb_issue', False),
        'adb_error': device_status.get('adb_error', '')
    }

    # 重置检测标记（前端已经收到通知）
    if device_status.get('disconnect_detected', False):
        device_status['disconnect_detected'] = False
    if device_status.get('connect_detected', False):
        device_status['connect_detected'] = False

    return jsonify(status_copy)


@app.route('/api/devices')
def api_list_devices():
    """列出ADB设备并返回当前选中设备"""
    connected, devices = check_adb_connection()
    return jsonify({
        'connected': connected,
        'devices': devices,
        'selected': selected_device
    })


@app.route('/api/select_device', methods=['POST'])
def api_select_device():
    """选择当前操作的ADB设备"""
    global selected_device
    data = request.get_json(silent=True) or {}
    serial = (data.get('serial') or '').strip()
    _, devices = check_adb_connection()
    if not serial:
        return jsonify({'success': False, 'error': '缺少serial'}), 400
    if serial not in devices:
        return jsonify({'success': False, 'error': '设备不在连接列表中'}), 400
    selected_device = serial
    return jsonify({'success': True, 'selected': selected_device})


@app.route('/api/adb_restart', methods=['POST'])
def adb_restart():
    """尝试重启ADB服务（kill-server/start-server）"""
    try:
        ok1, _, err1 = run_adb_command('adb kill-server', timeout=5)
        ok2, _, err2 = run_adb_command('adb start-server', timeout=10)
        # 重启后立即刷新一次状态
        check_adb_connection()
        if ok2:
            return jsonify({'success': True, 'message': 'ADB服务已重启'})
        else:
            return jsonify({'success': False, 'error': (err2 or '重启失败')}), 500
    except Exception as e:
        return jsonify({'success': False, 'error': str(e)}), 500

@app.route('/api/scan', methods=['POST'])
def scan():
    """开始扫描照片"""
    global scan_status

    if scan_status['is_running']:
        return jsonify({
            'success': False,
            'error': '扫描正在进行中'
        }), 400

    # 重置状态
    scan_status = {
        'is_running': True,
        'stage': 'finding',
        'current_dir': '',
        'files_found': 0,
        'files_processed': 0,
        'total_files': 0,
        'photos': [],
        'error': None
    }

    # 启动后台扫描线程
    thread = threading.Thread(target=scan_photos_thread)
    thread.daemon = True
    thread.start()

    return jsonify({
        'success': True,
        'message': '扫描已开始'
    })

@app.route('/api/scan_status')
def get_scan_status():
    """获取扫描状态"""
    return jsonify({
        'is_running': scan_status['is_running'],
        'stage': scan_status['stage'],
        'current_dir': scan_status['current_dir'],
        'files_found': scan_status['files_found'],
        'files_processed': scan_status['files_processed'],
        'total_files': scan_status['total_files'],
        'photo_count': len(scan_status['photos']),
        'error': scan_status['error'],
        'albums_preview': scan_status.get('albums_preview', {}),
        'thumbs_total': scan_status.get('thumbs_total', 0),
        'thumbs_done': scan_status.get('thumbs_done', 0)
    })

@app.route('/api/scan_result')
def get_scan_result():
    """获取扫描结果"""
    if scan_status['stage'] == 'done':
        return jsonify({
            'success': True,
            'count': len(scan_status['photos']),
            'photos': scan_status['photos'],
            'albums': scan_status.get('albums', {})
        })
    else:
        return jsonify({
            'success': False,
            'error': '扫描未完成'
        }), 400


def _guess_mimetype_from_ext(path):
    ext = PurePosixPath(path).suffix.lower()
    if ext in {'.jpg', '.jpeg', '.jpe'}:
        return 'image/jpeg'
    if ext == '.png':
        return 'image/png'
    if ext == '.gif':
        return 'image/gif'
    if ext == '.webp':
        return 'image/webp'
    if ext == '.bmp':
        return 'image/bmp'
    return 'application/octet-stream'


def _preview_local_path(remote_path: str) -> str:
    """根据远程路径生成缓存原始文件本地路径（保留原始扩展名）。"""
    ext = PurePosixPath(remote_path).suffix.lower()
    if not ext:
        ext = '.bin'
    h = hashlib.sha1(remote_path.encode('utf-8', errors='ignore')).hexdigest()
    return os.path.join(PREVIEW_DIR, f"{h}{ext}")

def _slug(s: str) -> str:
    s = s.strip().replace(' ', '_')
    return re.sub(r'[^A-Za-z0-9_\-\.]+', '_', s)[:64] or 'unknown'

device_label_cache = {}

def _get_current_device_label() -> str:
    """获取当前设备的标签（serial_model），用于按机型分目录保存缩略图。"""
    try:
        if not selected_device:
            return 'unknown_device'
        if selected_device in device_label_cache:
            return device_label_cache[selected_device]
        ok_m, out_m, _ = run_adb_command("adb shell getprop ro.product.model", timeout=3)
        ok_b, out_b, _ = run_adb_command("adb shell getprop ro.product.brand", timeout=3)
        model = (out_m or '').strip() or 'device'
        brand = (out_b or '').strip()
        label = _slug(f"{selected_device}_{brand}_{model}")
        device_label_cache[selected_device] = label
        return label
    except Exception:
        return 'unknown_device'

def _thumb_local_path(remote_path: str, size: int = 512) -> str:
    """缩略图缓存路径（统一jpg扩展，按设备分目录）。"""
    h = hashlib.sha1(remote_path.encode('utf-8', errors='ignore')).hexdigest()
    dev = _get_current_device_label()
    return os.path.join(THUMB_DIR, dev, f"{h}_{size}.jpg")

def _ensure_thumbnail(src_local_path: str, dst_thumb_path: str, max_size: int = 512, quality: int = 70) -> bool:
    """将本地原图生成压缩缩略图到 dst_thumb_path。返回是否成功。"""
    try:
        from PIL import Image
    except Exception:
        return False

    try:
        os.makedirs(os.path.dirname(dst_thumb_path), exist_ok=True)
        with Image.open(src_local_path) as im:
            im.load()
            # 转换到RGB以统一输出jpg
            if im.mode not in ('RGB', 'L'):
                im = im.convert('RGB')
            im.thumbnail((max_size, max_size))
            im.save(dst_thumb_path, format='JPEG', quality=quality, optimize=True)
        try:
            sz = os.path.getsize(dst_thumb_path)
            print(f"[preview] thumb generated: {dst_thumb_path} ({sz/1024:.1f} KB) | thread={threading.current_thread().name}")
        except Exception:
            pass
        return True
    except Exception as e:
        # Pillow 失败，尝试 ffmpeg 作为兜底（处理HEIC/特殊格式）
        try:
            import shutil, subprocess
            if shutil.which('ffmpeg'):
                vf = f"scale=if(gt(iw,ih),{max_size},-2):if(gt(iw,ih),-2,{max_size})"
                cmd = [
                    'ffmpeg', '-y', '-i', src_local_path,
                    '-frames:v', '1',
                    '-vf', vf,
                    '-q:v', '6',
                    dst_thumb_path
                ]
                subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
                try:
                    sz = os.path.getsize(dst_thumb_path)
                    print(f"[preview] ffmpeg thumb generated: {dst_thumb_path} ({sz/1024:.1f} KB) | thread={threading.current_thread().name}")
                except Exception:
                    pass
                return True
        except Exception as ee:
            print(f"[preview] ffmpeg fallback failed: {ee}")
        try:
            if os.path.exists(dst_thumb_path):
                os.remove(dst_thumb_path)
        except Exception:
            pass
        print(f"[preview] thumb generation failed: {e}")
        return False

def _ensure_video_thumbnail(src_local_path: str, dst_thumb_path: str, max_size: int = 320, quality: int = 6) -> bool:
    """使用ffmpeg从视频提取封面帧并保存为jpeg缩略图。quality为ffmpeg -q:v (2~31，越小越好)。"""
    import shutil, subprocess
    if not shutil.which('ffmpeg'):
        # 尝试占位图生成
        try:
            from PIL import Image, ImageDraw
            os.makedirs(os.path.dirname(dst_thumb_path), exist_ok=True)
            img = Image.new('RGB', (max_size, max_size), (32, 32, 32))
            d = ImageDraw.Draw(img)
            # 画一个播放三角形
            w, h = max_size, max_size
            tri = [(w*0.35, h*0.3), (w*0.35, h*0.7), (w*0.7, h*0.5)]
            d.polygon(tri, fill=(220,220,220))
            img.save(dst_thumb_path, format='JPEG', quality=65, optimize=True)
            return True
        except Exception:
            return False
    try:
        os.makedirs(os.path.dirname(dst_thumb_path), exist_ok=True)
        # 简化滤镜，提升兼容性
        vf = f"scale={max_size}:-2:force_original_aspect_ratio=decrease"
        base = ['ffmpeg', '-v', 'error', '-nostdin', '-y']
        attempts = [
            base + ['-ss', '0.5', '-i', src_local_path, '-an', '-frames:v', '1', '-vf', vf, '-q:v', str(quality), dst_thumb_path],
            base + ['-i', src_local_path, '-ss', '0.5', '-an', '-frames:v', '1', '-vf', vf, '-q:v', str(quality), dst_thumb_path],
            base + ['-i', src_local_path, '-an', '-frames:v', '1', '-vf', vf, '-q:v', str(quality), dst_thumb_path],
        ]
        for cmd in attempts:
            try:
                subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
                if os.path.exists(dst_thumb_path) and os.path.getsize(dst_thumb_path) > 0:
                    try:
                        sz = os.path.getsize(dst_thumb_path)
                        print(f"[preview] video thumb generated: {dst_thumb_path} ({sz/1024:.1f} KB) | thread={threading.current_thread().name}")
                    except Exception:
                        pass
                    return True
            except subprocess.CalledProcessError:
                continue
        # 全部失败
        try:
            if os.path.exists(dst_thumb_path):
                os.remove(dst_thumb_path)
        except Exception:
            pass
        print(f"[preview] video thumb generation failed: all attempts failed for {src_local_path}")
        return False
    except Exception as e:
        try:
            if os.path.exists(dst_thumb_path):
                os.remove(dst_thumb_path)
        except Exception:
            pass
        print(f"[preview] video thumb generation failed: {e}")
        return False

def _dir_usage_bytes(dir_path: str):
    total = 0
    files = []
    for root, _, filenames in os.walk(dir_path):
        for fn in filenames:
            fp = os.path.join(root, fn)
            try:
                st = os.stat(fp)
                total += st.st_size
                files.append((fp, st.st_size, st.st_mtime))
            except FileNotFoundError:
                continue
            except Exception:
                continue
    return total, files

def _cleanup_dir_cap(dir_path: str, cap_mb: int, target_ratio: float = 0.9):
    """若目录占用超过 cap_mb，则按最久未修改时间开始删除，直到降到 cap_mb*target_ratio。"""
    cap_bytes = cap_mb * 1024 * 1024
    total, files = _dir_usage_bytes(dir_path)
    if total <= cap_bytes:
        return False, total
    files.sort(key=lambda x: x[2])  # 按mtime升序，最老的优先删
    target_bytes = int(cap_bytes * target_ratio)
    removed = 0
    for fp, sz, _ in files:
        try:
            os.remove(fp)
            removed += sz
            total -= sz
            if total <= target_bytes:
                break
        except Exception:
            continue
    print(f"[cache] cleanup {dir_path}: removed {removed/1024/1024:.1f} MB, remain {total/1024/1024:.1f} MB")
    return True, total

def _ensure_local_and_thumb(remote_path: str, local_path: str, thumb_path: str, idx: int = -1) -> bool:
    """确保拉取原图到本地并生成缩略图（极小尺寸），返回是否成功。"""
    try:
        if idx >= 0:
            print(f"[thumb]#{idx} start {remote_path} | thread={threading.current_thread().name}")
        if not os.path.exists(local_path):
            quoted_remote = remote_path.replace('"', '\\"')
            quoted_local = local_path.replace('"', '\\"')
            os.makedirs(os.path.dirname(local_path), exist_ok=True)
            ok, _, _ = run_adb_command(f'adb pull "{quoted_remote}" "{quoted_local}"', timeout=60, enable_burst=True)
            if not ok or not os.path.exists(local_path):
                return False
        # 生成更小的缩略图（320px, 质量 55），视频提取首帧
        need = (not os.path.exists(thumb_path)) or (os.path.getmtime(thumb_path) < os.path.getmtime(local_path))
        if need:
            ext = PurePosixPath(remote_path).suffix.lower()
            if ext in PREVIEW_IMAGE_EXTS:
                ok = _ensure_thumbnail(local_path, thumb_path, max_size=256, quality=50)
            elif ext in PREVIEW_VIDEO_EXTS:
                ok = _ensure_video_thumbnail(local_path, thumb_path, max_size=256, quality=8)
            else:
                return False
        else:
            ok = True
        if idx >= 0:
            try:
                sz = os.path.getsize(thumb_path) if os.path.exists(thumb_path) else 0
            except Exception:
                sz = 0
            print(f"[thumb]#{idx} done {remote_path} -> {os.path.basename(thumb_path)} ({sz/1024:.1f} KB) | thread={threading.current_thread().name}")
        return ok
    except Exception:
        return False


@app.route('/api/preview')
def photo_preview():
    """相册/照片预览：拉取到本地后返回压缩缩略图，避免MB级原图占用带宽。"""
    remote_path = request.args.get('path', '').strip()
    if not remote_path:
        return jsonify({'success': False, 'error': '缺少路径参数'}), 400

    ext = PurePosixPath(remote_path).suffix.lower()
    if ext not in PREVIEW_IMAGE_EXTS and ext not in PREVIEW_VIDEO_EXTS:
        return jsonify({'success': False, 'error': '不支持的预览格式'}), 415

    local_path = _preview_local_path(remote_path)
    thumb_path = _thumb_local_path(remote_path, size=512)

    if not os.path.exists(local_path):
        connected, _ = check_adb_connection()
        if not connected:
            return jsonify({'success': False, 'error': '设备未连接'}), 503

        os.makedirs(os.path.dirname(local_path), exist_ok=True)
        quoted_remote = remote_path.replace('"', '\\"')
        quoted_local = local_path.replace('"', '\\"')
        pull_cmd = f'adb pull "{quoted_remote}" "{quoted_local}"'
        ok, stdout, stderr = run_adb_command(pull_cmd, timeout=60)
        if not ok or not os.path.exists(local_path):
            return jsonify({'success': False, 'error': '预览拉取失败'}), 500

    # 生成缩略图（如必要）并返回缩略图
    try:
        need_generate = (not os.path.exists(thumb_path)) or (os.path.getmtime(thumb_path) < os.path.getmtime(local_path))
    except Exception:
        need_generate = True

    if need_generate:
        if ext in PREVIEW_IMAGE_EXTS:
            ok = _ensure_thumbnail(local_path, thumb_path, max_size=256, quality=50)
        else:
            ok = _ensure_video_thumbnail(local_path, thumb_path, max_size=256, quality=8)
        if not ok:
            # Pillow 不可用或失败时，退回原图（但仍加上缓存头）
            try:
                resp = send_file(local_path, mimetype=_guess_mimetype_from_ext(local_path), conditional=True)
                resp.headers['Cache-Control'] = 'public, max-age=86400'
                return resp
            except Exception as e:
                return jsonify({'success': False, 'error': str(e)}), 500
        else:
            # 每次生成后检查缩略图目录容量（轻量）
            _cleanup_dir_cap(THUMB_DIR, THUMB_CACHE_MAX_MB)

    try:
        print(f"[thumb-serve] {thumb_path} | thread={threading.current_thread().name}")
        resp = send_file(thumb_path, mimetype='image/jpeg', conditional=True)
        # 强缓存，提升刷新体验（文件名基于路径哈希+尺寸，长期有效）
        resp.headers['Cache-Control'] = 'public, max-age=31536000, immutable'
        return resp
    except Exception as e:
        return jsonify({'success': False, 'error': str(e)}), 500

@app.route('/api/thumb')
def photo_thumb():
    """仅返回已存在的缩略图；不存在则返回404（由前端批处理异步生成）。"""
    remote_path = request.args.get('path', '').strip()
    if not remote_path:
        return jsonify({'success': False, 'error': '缺少路径参数'}), 400
    thumb_path = _thumb_local_path(remote_path, size=256)
    if not os.path.exists(thumb_path):
        return ('', 404)
    print(f"[thumb-serve] {thumb_path} | thread={threading.current_thread().name}")
    resp = send_file(thumb_path, mimetype='image/jpeg', conditional=True)
    resp.headers['Cache-Control'] = 'public, max-age=31536000, immutable'
    return resp

@app.route('/api/thumb_exists')
def photo_thumb_exists():
    path = request.args.get('path', '').strip()
    if not path:
        return jsonify({'success': False, 'error': '缺少路径参数'}), 400
    thumb_path = _thumb_local_path(path, size=256)
    return jsonify({'success': True, 'exists': os.path.exists(thumb_path)})

@app.route('/api/thumb_status', methods=['POST'])
def photo_thumb_status():
    data = request.get_json(silent=True) or {}
    paths = data.get('paths') or []
    res = []
    for p in paths:
        try:
            res.append(os.path.exists(_thumb_local_path(p, size=256)))
        except Exception:
            res.append(False)
    return jsonify({'success': True, 'ready': res})

@app.route('/api/thumb_batch_generate', methods=['POST'])
def photo_thumb_batch_generate():
    data = request.get_json(silent=True) or {}
    paths = data.get('paths') or []
    batch_size = int(data.get('batch_size') or 30)
    size = int(data.get('size') or 256)
    submitted = 0
    for p in paths[:batch_size]:
        try:
            local_path = _preview_local_path(p)
            thumb_path = _thumb_local_path(p, size=size)
            if os.path.exists(thumb_path) and os.path.getsize(thumb_path) > 0:
                continue
            if p in thumb_inflight:
                continue
            thumb_inflight.add(p)
            def _task(remote=p, lp=local_path, tp=thumb_path):
                try:
                    _ensure_local_and_thumb(remote, lp, tp, -1)
                finally:
                    try:
                        thumb_inflight.discard(remote)
                    except Exception:
                        pass
            thumb_executor.submit(_task)
            submitted += 1
        except Exception:
            continue
    return jsonify({'success': True, 'submitted': submitted, 'total': len(paths)})

@app.route('/api/album_photos')
def api_album_photos():
    album = request.args.get('album', '').strip()
    try:
        offset = int(request.args.get('offset', '0'))
        limit = int(request.args.get('limit', '120'))
    except Exception:
        offset, limit = 0, 120
    if not album:
        return jsonify({'success': False, 'error': '缺少相册路径'}), 400

    # 快速列出按时间倒序的文件名
    try:
        ok, out, _ = run_adb_command(f"adb shell 'ls -1t " + album.replace("'","'\''") + " 2>/dev/null'", timeout=8)
        files = []
        if ok and out:
            names = [l.strip() for l in out.splitlines() if l.strip()]
            # 只要支持的媒体
            for nm in names:
                p = album.rstrip('/') + '/' + nm
                if PurePosixPath(p).suffix.lower() in (PREVIEW_IMAGE_EXTS | PREVIEW_VIDEO_EXTS):
                    files.append(p)
        # 分页
        page = files[offset: offset+limit]
        # 生成简要元数据（避免额外stat）：按顺序构造递减mtime
        now = int(datetime.now().timestamp())
        photos = []
        for i, p in enumerate(page):
            name = os.path.basename(p)
            mtime = now - (offset + i)
            photos.append({
                'path': p,
                'name': name,
                'size': 0,
                'size_mb': 0.0,
                'mtime': mtime,
                'date': ''
            })
        return jsonify({'success': True, 'photos': photos, 'total': len(files)})
    except Exception as e:
        return jsonify({'success': False, 'error': str(e)}), 500

@app.route('/api/cache_stats')
def cache_stats():
    """返回缓存目录占用信息，便于前端或用户查看。"""
    t_total, _ = _dir_usage_bytes(THUMB_DIR)
    p_total, _ = _dir_usage_bytes(PREVIEW_DIR)
    return jsonify({
        'success': True,
        'thumbs_mb': round(t_total/1024/1024, 2),
        'previews_mb': round(p_total/1024/1024, 2),
        'thumb_dir': THUMB_DIR,
        'preview_dir': PREVIEW_DIR,
        'thumb_cap_mb': THUMB_CACHE_MAX_MB,
        'preview_cap_mb': PREVIEW_ORIG_MAX_MB
    })

@app.route('/api/cache_cleanup', methods=['POST'])
def cache_cleanup():
    """手动触发缓存清理。"""
    changed1, t_total = _cleanup_dir_cap(THUMB_DIR, THUMB_CACHE_MAX_MB)
    changed2, p_total = _cleanup_dir_cap(PREVIEW_DIR, PREVIEW_ORIG_MAX_MB)
    return jsonify({
        'success': True,
        'thumbs_mb': round(t_total/1024/1024, 2),
        'previews_mb': round(p_total/1024/1024, 2),
        'thumb_dir': THUMB_DIR,
        'preview_dir': PREVIEW_DIR,
        'changed': changed1 or changed2
    })


def _map_usb_speed(speed_str: str):
    s = (speed_str or '').strip().lower()
    if 'super-speed-plus' in s:
        return {'code': 'super-speed-plus', 'label': 'USB 3.1/3.2 (10–20 Gbps)', 'gbps': 10}
    if 'super-speed' in s:
        return {'code': 'super-speed', 'label': 'USB 3.0/3.1 Gen1 (5 Gbps)', 'gbps': 5}
    if 'high-speed' in s:
        return {'code': 'high-speed', 'label': 'USB 2.0 (480 Mbps)', 'gbps': 0.48}
    if 'full-speed' in s:
        return {'code': 'full-speed', 'label': 'USB 1.1 (12 Mbps)', 'gbps': 0.012}
    return {'code': 'unknown', 'label': '未知速率', 'gbps': 0}


@app.route('/api/usb/burst_mode', methods=['GET', 'POST'])
def adb_burst_mode():
    """获取或设置ADB Burst模式状态"""
    global ADB_BURST_MODE_ENABLED

    if request.method == 'GET':
        return jsonify({
            'success': True,
            'enabled': ADB_BURST_MODE_ENABLED == '1',
            'status': '已启用' if ADB_BURST_MODE_ENABLED == '1' else '已禁用'
        })

    elif request.method == 'POST':
        data = request.get_json()
        enabled = data.get('enabled', True)

        # 更新全局变量
        ADB_BURST_MODE_ENABLED = '1' if enabled else '0'

        # 设置环境变量
        os.environ['ADB_DELAYED_ACK'] = ADB_BURST_MODE_ENABLED

        print(f"🚀 ADB Burst模式 {'启用' if enabled else '禁用'}")

        return jsonify({
            'success': True,
            'enabled': enabled,
            'message': f"ADB Burst模式已{'启用' if enabled else '禁用'}"
        })

@app.route('/api/usb/speed')
def api_usb_speed():
    """获取Android端当前USB速率（通过ADB读取sysfs/dumpsys）"""
    connected, _ = check_adb_connection()
    if not connected:
        return jsonify({'success': False, 'error': '设备未连接'}), 400

    # 缓存命中则直接返回
    try:
        if usb_speed_cache.get('device') == selected_device and usb_speed_cache.get('data'):
            d = dict(usb_speed_cache['data'])
            d['cached'] = True
            return jsonify(d)
    except Exception:
        pass

    # 1) sysfs: /sys/class/udc/*/current_speed 或通过 usb_gadget 获取 UDC 名称
    try:
        # 优先直接读取 current_speed
        cmd1 = "adb shell 'for f in /sys/class/udc/*/current_speed; do cat \"$f\"; done 2>/dev/null'"
        ok, out, err = run_adb_command(cmd1, timeout=3)
        speed_line = ''
        if ok and out.strip():
            speed_line = out.strip().split('\n')[0]
        else:
            # 尝试通过 usb_gadget 找到 UDC 名称后读取
            cmd_udc = "adb shell 'for g in /sys/class/usb_gadget/*/UDC; do cat \"$g\"; done 2>/dev/null'"
            ok2, out2, _ = run_adb_command(cmd_udc, timeout=3)
            if ok2 and out2.strip():
                udc = out2.strip().split('\n')[0]
                cmd2 = f"adb shell 'cat /sys/class/udc/{udc}/current_speed 2>/dev/null'"
                ok3, out3, _ = run_adb_command(cmd2, timeout=3)
                if ok3 and out3.strip():
                    speed_line = out3.strip().split('\n')[0]
        if speed_line:
            m = _map_usb_speed(speed_line)
            m.update({'success': True, 'source': 'sysfs', 'raw': out.strip()})
            usb_speed_cache.update({'device': selected_device, 'data': m})
            return jsonify(m)
    except Exception:
        pass

    # 2) dumpsys usb（某些机型会包含速度字段）
    try:
        ok, out, err = run_adb_command("adb shell dumpsys usb", timeout=4)
        if ok and out:
            # 在 Python 侧查找包含 speed 的行
            raw = out
            line = ''
            for l in raw.splitlines():
                ls = l.strip()
                if 'speed' in ls.lower():
                    line = ls
                    break
            if line:
                m = _map_usb_speed(line)
                m.update({'success': True, 'source': 'dumpsys', 'raw': line})
                usb_speed_cache.update({'device': selected_device, 'data': m})
                return jsonify(m)
    except Exception:
        pass

    # 3) 基于快速拉取的基准测试（非root可用）
    try:
        bench = _benchmark_usb_speed()
        if bench and bench.get('measured_mbps', 0) > 0:
            bench.update({'success': True, 'source': 'bench'})
            usb_speed_cache.update({'device': selected_device, 'data': bench})
            return jsonify(bench)
    except Exception:
        pass

    # 最后返回未知（占位）
    m = _map_usb_speed('')
    m.update({'success': True, 'source': 'none', 'raw': ''})
    usb_speed_cache.update({'device': selected_device, 'data': m})
    return jsonify(m)


def _benchmark_usb_speed(size_mb: int = 64):
    """通过adb在/sdcard/Download生成临时文件并pull到本地，估算实际MB/s。改进版本支持USB 3.2高速检测。"""
    import time as _t

    print(f"🚀 开始USB速度基准测试 (测试文件: {size_mb}MB)...")

    remote_path = f"/sdcard/Download/.usb_speed_test.bin"
    local_dir = PREVIEW_DIR if os.path.isdir(PREVIEW_DIR) else OUTPUT_DIR
    os.makedirs(local_dir, exist_ok=True)
    local_path = os.path.join(local_dir, 'usb_speed_test.bin')

    # 生成远程测试文件（更大的文件以获得准确的高速测试）
    print(f"📝 生成测试文件: {remote_path} (Burst模式: {ADB_BURST_MODE_ENABLED == '1'})")
    ok1, _, _ = run_adb_command(f"adb shell 'dd if=/dev/zero of=\"{remote_path}\" bs=1M count={size_mb} 2>/dev/null'", timeout=30)
    if not ok1:
        print("❌ 无法生成测试文件，基准测试失败")
        return None

    # 多次测试以获得更准确的结果
    speed_samples = []
    test_rounds = 3  # 进行3轮测试

    for i in range(test_rounds):
        print(f"⚡ 第 {i+1}/{test_rounds} 轮测试...")

        # 确保本地文件不存在
        if os.path.exists(local_path):
            os.remove(local_path)

        # 拉取并计时（强制启用Burst模式以获得最佳性能）
        start = _t.time()
        ok2, _, _ = run_adb_command(f"adb pull \"{remote_path}\" \"{local_path}\"", timeout=60, enable_burst=True)
        elapsed = max(0.001, _t.time() - start)

        if ok2 and os.path.exists(local_path):
            size = os.path.getsize(local_path)
            speed_mbps = (size / 1024.0 / 1024.0) / elapsed
            speed_samples.append(speed_mbps)
            print(f"📊 第 {i+1} 轮结果: {speed_mbps:.1f} MB/s")

            # 清理本地文件
            os.remove(local_path)
        else:
            print(f"❌ 第 {i+1} 轮测试失败")

    # 清理远程文件
    run_adb_command(f"adb shell 'rm -f \"{remote_path}\"'", timeout=10)

    if not speed_samples:
        print("❌ 所有测试轮次都失败了")
        return None

    # 计算平均速度和最大速度
    avg_speed = sum(speed_samples) / len(speed_samples)
    max_speed = max(speed_samples)
    min_speed = min(speed_samples)

    print(f"📈 速度测试结果:")
    print(f"   平均速度: {avg_speed:.1f} MB/s")
    print(f"   最高速度: {max_speed:.1f} MB/s")
    print(f"   最低速度: {min_speed:.1f} MB/s")
    print(f"   速度范围: {max_speed - min_speed:.1f} MB/s")

    # 使用最高速度作为USB速度等级判断依据（更能反映真实性能）
    measured_mbps = max_speed

    # 更新USB速度等级判断阈值，支持USB 3.2高速传输
    if measured_mbps >= 1000:  # USB 3.2 Gen 2x2: 20Gbps = 2500MB/s
        code, label = 'bench-3.2-2x2', 'USB 3.2 Gen 2x2 (估算)'
    elif measured_mbps >= 500:  # USB 3.2 Gen 2: 10Gbps = 1250MB/s
        code, label = 'bench-3.2', 'USB 3.2 Gen 2 (估算)'
    elif measured_mbps >= 250:  # USB 3.1/3.2 Gen 1: 5Gbps = 625MB/s
        code, label = 'bench-3.1', 'USB 3.1/3.2 Gen 1 (估算)'
    elif measured_mbps >= 150:  # USB 3.0: 5Gbps = 625MB/s
        code, label = 'bench-3.0', 'USB 3.0 (估算)'
    elif measured_mbps >= 40:  # USB 2.0 高端
        code, label = 'bench-2.0-hi', 'USB 2.0 高速 (估算)'
    elif measured_mbps >= 15:  # USB 2.0 标准
        code, label = 'bench-2.0', 'USB 2.0 (估算)'
    else:
        code, label = 'bench-unknown', 'USB 未知 (估算)'

    # 计算等效的Gbps
    gbps = measured_mbps * 8 / 1024  # MB/s to Gbps conversion

    print(f"🎯 检测结果: {label} - {measured_mbps:.1f} MB/s ({gbps:.2f} Gbps)")

    return {
        'code': code,
        'label': label,
        'gbps': round(gbps, 2),
        'measured_mbps': round(measured_mbps, 1),
        'avg_mbps': round(avg_speed, 1),
        'max_mbps': round(max_speed, 1),
        'min_mbps': round(min_speed, 1),
        'test_rounds': test_rounds,
        'test_size_mb': size_mb
    }

@app.route('/api/check_progress')
def check_progress():
    """检查是否有未完成的传输任务"""
    progress = load_progress()
    
    if progress and len(progress.get('pending_photos', [])) > 0:
        return jsonify({
            'has_progress': True,
            'total': progress['total'],
            'completed': progress['completed'],
            'pending': len(progress['pending_photos']),
            'failed': len(progress.get('failed_files', [])),
            'output_dir': progress.get('output_dir', OUTPUT_DIR),
            'timestamp': progress.get('timestamp', '')
        })
    else:
        return jsonify({'has_progress': False})

@app.route('/api/resume_transfer', methods=['POST'])
def resume_transfer():
    """恢复未完成的传输"""
    global transfer_status

    if transfer_status['is_running']:
        return jsonify({
            'success': False,
            'error': '传输正在进行中'
        }), 400

    progress = load_progress()
    if not progress:
        return jsonify({
            'success': False,
            'error': '没有找到未完成的传输任务'
        }), 400

    pending_photos = progress.get('pending_photos', [])
    if not pending_photos:
        return jsonify({
            'success': False,
            'error': '所有文件已传输完成'
        }), 400

    output_dir = progress.get('output_dir', OUTPUT_DIR)
    
    # 恢复已完成文件列表
    transfer_status['completed_files'] = progress.get('completed_files', set())

    # 启动后台传输线程（resume=True）
    thread = threading.Thread(target=transfer_photos_thread, args=(pending_photos, output_dir, True))
    thread.daemon = True
    thread.start()

    return jsonify({
        'success': True,
        'message': f'继续传输 {len(pending_photos)} 个文件'
    })

@app.route('/api/transfer', methods=['POST'])
def transfer():
    """开始传输照片"""
    global transfer_status

    if transfer_status['is_running']:
        return jsonify({
            'success': False,
            'error': '传输正在进行中'
        }), 400

    data = request.get_json()
    photos = data.get('photos', [])
    output_dir = data.get('output_dir', OUTPUT_DIR)

    if not photos:
        return jsonify({
            'success': False,
            'error': '没有选择照片'
        }), 400

    # 检测USB速度并保存到传输状态
    try:
        print("🔍 正在检测USB设备速度...")
        usb_info = get_usb_speed()
        if usb_info and usb_info.get('success'):
            transfer_status['usb_info'] = {
                'label': usb_info.get('label', 'USB 未知'),
                'measured_mbps': usb_info.get('measured_mbps', 0),
                'gbps': usb_info.get('gbps', 0)
            }
            print(f"📡 USB设备检测: {usb_info.get('label')} - {usb_info.get('measured_mbps', 0):.1f} MB/s")
        else:
            print("⚠️ 无法检测USB速度")
            transfer_status['usb_info'] = {'label': 'USB 未知', 'measured_mbps': 0, 'gbps': 0}
    except Exception as e:
        print(f"⚠️ USB速度检测失败: {e}")
        transfer_status['usb_info'] = {'label': 'USB 未知', 'measured_mbps': 0, 'gbps': 0}

    # 启动后台传输线程
    thread = threading.Thread(target=transfer_photos_thread, args=(photos, output_dir, False))
    thread.daemon = True
    thread.start()

    return jsonify({
        'success': True,
        'message': '传输已开始'
    })

@app.route('/api/transfer_status')
def get_transfer_status():
    """获取传输状态"""
    # 复制状态并转换set为list
    status_copy = transfer_status.copy()
    status_copy['completed_count'] = len(transfer_status.get('completed_files', set()))
    status_copy.pop('completed_files', None)  # 移除set，避免JSON序列化错误
    return jsonify(status_copy)

@app.route('/api/stop_transfer', methods=['POST'])
def stop_transfer():
    """停止传输"""
    global transfer_status
    transfer_status['is_running'] = False
    return jsonify({'success': True})

# ==================== WiFi模式相关API ====================

def init_database():
    """初始化SQLite数据库"""
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        
        # 创建设备表
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS devices (
                device_id TEXT PRIMARY KEY,
                device_name TEXT,
                last_heartbeat TEXT,
                connected_at TEXT,
                photo_count INTEGER DEFAULT 0
            )
        ''')
        
        # 创建批次表
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS batches (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                batch_id TEXT NOT NULL,
                timestamp TEXT,
                photo_count INTEGER DEFAULT 0,
                total_size INTEGER DEFAULT 0,
                total_size_mb REAL DEFAULT 0,
                status TEXT DEFAULT 'completed',
                is_legacy INTEGER DEFAULT 0,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(device_id, batch_id)
            )
        ''')
        
        # 创建照片表
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS photos (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                batch_id TEXT NOT NULL,
                name TEXT NOT NULL,
                path TEXT NOT NULL,
                size INTEGER DEFAULT 0,
                size_mb REAL DEFAULT 0,
                date TEXT,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(device_id, batch_id, name)
            )
        ''')
        
        # 创建索引
        cursor.execute('CREATE INDEX IF NOT EXISTS idx_batches_device ON batches(device_id)')
        cursor.execute('CREATE INDEX IF NOT EXISTS idx_photos_batch ON photos(device_id, batch_id)')
        
        conn.commit()
        conn.close()
        print(f"✅ 数据库初始化完成: {DB_FILE}")
        return True
    except Exception as e:
        print(f"❌ 数据库初始化失败: {str(e)}")
        return False

def save_batch_to_db(device_id, batch_info):
    """保存单个批次到数据库"""
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        
        # 插入或更新批次信息
        cursor.execute('''
            INSERT OR REPLACE INTO batches 
            (device_id, batch_id, timestamp, photo_count, total_size, total_size_mb, status, is_legacy)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ''', (
            device_id,
            batch_info['batch_id'],
            batch_info['timestamp'],
            batch_info['photo_count'],
            batch_info.get('total_size', 0),
            batch_info['total_size_mb'],
            batch_info.get('status', 'completed'),
            1 if batch_info.get('is_legacy', False) else 0
        ))
        
        # 删除旧的照片记录
        cursor.execute('DELETE FROM photos WHERE device_id = ? AND batch_id = ?', 
                      (device_id, batch_info['batch_id']))
        
        # 插入照片信息
        for photo in batch_info['photos']:
            cursor.execute('''
                INSERT INTO photos (device_id, batch_id, name, path, size, size_mb, date)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            ''', (
                device_id,
                batch_info['batch_id'],
                photo['name'],
                photo['path'],
                photo.get('size', 0),
                photo['size_mb'],
                photo['date']
            ))
        
        conn.commit()
        conn.close()
        return True
    except Exception as e:
        print(f"❌ 保存批次到数据库失败: {str(e)}")
        return False

def load_batches_from_db():
    """从数据库加载批次信息到内存"""
    global device_upload_batches, device_photos
    try:
        conn = sqlite3.connect(DB_FILE)
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        
        # 加载所有批次
        cursor.execute('SELECT * FROM batches ORDER BY device_id, batch_id DESC')
        batches_rows = cursor.fetchall()
        
        device_upload_batches = {}
        device_photos = {}
        
        for batch_row in batches_rows:
            device_id = batch_row['device_id']
            batch_id = batch_row['batch_id']
            
            if device_id not in device_upload_batches:
                device_upload_batches[device_id] = []
            if device_id not in device_photos:
                device_photos[device_id] = []
            
            # 加载该批次的照片
            cursor.execute('SELECT * FROM photos WHERE device_id = ? AND batch_id = ?',
                          (device_id, batch_id))
            photos_rows = cursor.fetchall()
            
            photos = []
            for photo_row in photos_rows:
                photo_info = {
                    'name': photo_row['name'],
                    'path': photo_row['path'],
                    'size': photo_row['size'],
                    'size_mb': photo_row['size_mb'],
                    'date': photo_row['date']
                }
                photos.append(photo_info)
            
            batch_info = {
                'batch_id': batch_id,
                'timestamp': batch_row['timestamp'],
                'photo_count': batch_row['photo_count'],
                'total_size': batch_row['total_size'],
                'total_size_mb': batch_row['total_size_mb'],
                'status': batch_row['status'],
                'is_legacy': bool(batch_row['is_legacy']),
                'photos': photos
            }
            
            device_upload_batches[device_id].append(batch_info)
            device_photos[device_id].extend(photos)
        
        conn.close()
        
        total_batches = sum(len(batches) for batches in device_upload_batches.values())
        total_photos = sum(len(photos) for photos in device_photos.values())
        print(f"📂 从数据库加载了 {len(device_upload_batches)} 个设备，{total_batches} 个批次，{total_photos} 张照片")
        return True
    except Exception as e:
        print(f"❌ 从数据库加载失败: {str(e)}")
        return False

def scan_and_rebuild_batches():
    """扫描文件系统重建批次信息"""
    global device_upload_batches, device_photos
    
    print(f"\n🔍 开始扫描文件系统重建批次信息...")
    base_dir = OUTPUT_DIR
    
    if not os.path.exists(base_dir):
        print(f"⚠️ 输出目录不存在: {base_dir}")
        return
    
    scanned_devices = 0
    scanned_batches = 0
    scanned_photos = 0
    
    # 遍历设备文件夹
    for device_id in os.listdir(base_dir):
        device_path = os.path.join(base_dir, device_id)
        if not os.path.isdir(device_path) or device_id.startswith('.'):
            continue
        
        scanned_devices += 1
        
        if device_id not in device_upload_batches:
            device_upload_batches[device_id] = []
        
        if device_id not in device_photos:
            device_photos[device_id] = []
        
        # 先处理设备根目录下的旧文件（没有批次文件夹的）
        root_photos = []
        root_total_size = 0
        
        for item in os.listdir(device_path):
            item_path = os.path.join(device_path, item)
            if os.path.isfile(item_path) and not item.startswith('.'):
                # 这是设备根目录下的文件（旧上传格式）
                file_size = os.path.getsize(item_path)
                file_mtime = os.path.getmtime(item_path)
                
                photo_info = {
                    'name': item,
                    'path': item,  # 旧格式：直接文件名，不带批次ID
                    'size': file_size,
                    'size_mb': round(file_size / 1024.0 / 1024.0, 2),
                    'date': datetime.fromtimestamp(file_mtime).strftime('%Y-%m-%d %H:%M:%S')
                }
                root_photos.append(photo_info)
                root_total_size += file_size
                scanned_photos += 1
        
        # 如果有旧文件，创建一个"历史上传"批次
        if root_photos:
            # 使用最旧文件的时间作为批次ID
            oldest_mtime = min(os.path.getmtime(os.path.join(device_path, p['name'])) for p in root_photos)
            legacy_batch_id = datetime.fromtimestamp(oldest_mtime).strftime('%Y%m%d_000000')
            
            # 检查是否已存在
            if not any(b['batch_id'] == legacy_batch_id for b in device_upload_batches[device_id]):
                batch_info = {
                    'batch_id': legacy_batch_id,
                    'timestamp': '历史上传（批次系统前）',
                    'photo_count': len(root_photos),
                    'total_size': root_total_size,
                    'total_size_mb': round(root_total_size / 1024.0 / 1024.0, 2),
                    'photos': root_photos,
                    'status': 'completed',
                    'is_legacy': True  # 标记为旧格式
                }
                device_upload_batches[device_id].append(batch_info)
                device_photos[device_id].extend(root_photos)
                scanned_batches += 1
        
        # 遍历批次文件夹
        for batch_id in os.listdir(device_path):
            batch_path = os.path.join(device_path, batch_id)
            if not os.path.isdir(batch_path) or batch_id.startswith('.'):
                continue
            
            # 检查批次是否已存在
            if any(b['batch_id'] == batch_id for b in device_upload_batches[device_id]):
                continue
            
            scanned_batches += 1
            
            # 扫描批次中的照片
            batch_photos = []
            total_size = 0
            
            for filename in os.listdir(batch_path):
                file_path = os.path.join(batch_path, filename)
                if os.path.isfile(file_path) and not filename.startswith('.'):
                    file_size = os.path.getsize(file_path)
                    file_mtime = os.path.getmtime(file_path)
                    
                    photo_info = {
                        'name': filename,
                        'path': filename,
                        'size': file_size,
                        'size_mb': round(file_size / 1024.0 / 1024.0, 2),
                        'date': datetime.fromtimestamp(file_mtime).strftime('%Y-%m-%d %H:%M:%S')
                    }
                    batch_photos.append(photo_info)
                    total_size += file_size
                    scanned_photos += 1
            
            if batch_photos:
                # 尝试从批次ID解析时间戳
                try:
                    # 批次ID格式: 20251009_141520
                    timestamp_str = datetime.strptime(batch_id, '%Y%m%d_%H%M%S').strftime('%Y-%m-%d %H:%M:%S')
                except:
                    timestamp_str = batch_photos[0]['date']  # 使用第一张照片的时间
                
                batch_info = {
                    'batch_id': batch_id,
                    'timestamp': timestamp_str,
                    'photo_count': len(batch_photos),
                    'total_size': total_size,
                    'total_size_mb': round(total_size / 1024.0 / 1024.0, 2),
                    'photos': batch_photos,
                    'status': 'completed'
                }
                device_upload_batches[device_id].append(batch_info)
                device_photos[device_id].extend(batch_photos)
        
        # 按批次ID排序（最新的在前）
        if device_upload_batches[device_id]:
            device_upload_batches[device_id].sort(key=lambda x: x['batch_id'], reverse=True)
    
    print(f"✅ 扫描完成:")
    print(f"   设备数: {scanned_devices}")
    print(f"   批次数: {scanned_batches}")
    print(f"   照片数: {scanned_photos}")
    
    # 保存扫描结果到数据库
    if scanned_batches > 0:
        for device_id, batches in device_upload_batches.items():
            for batch_info in batches:
                save_batch_to_db(device_id, batch_info)
        print(f"💾 已将 {scanned_batches} 个批次保存到数据库")

def cleanup_expired_devices():
    """清理超时的设备"""
    global wifi_mode_status
    current_time = datetime.now()
    expired_devices = []

    for device_id, device_info in list(wifi_mode_status['connected_devices'].items()):
        last_heartbeat = datetime.fromisoformat(device_info['last_heartbeat'])
        if (current_time - last_heartbeat).total_seconds() > DEVICE_TIMEOUT:
            expired_devices.append(device_id)

    for device_id in expired_devices:
        del wifi_mode_status['connected_devices'][device_id]
        print(f"🔌 设备断开: {device_id} (超时)")

    return len(expired_devices)

@app.route('/api/wifi/connect', methods=['POST'])
def wifi_connect():
    """设备连接接口 - Android设备点击服务器时调用此接口注册"""
    global wifi_mode_status, device_photos
    
    try:
        data = request.get_json() or {}
        device_id = data.get('device_id', 'unknown')
        device_name = data.get('device_name', f'设备 {device_id[:8]}')
        
        print(f"\n🔌 设备连接请求:")
        print(f"   设备ID: {device_id}")
        print(f"   设备名称: {device_name}")
        
        # 注册设备到连接列表
        if device_id not in wifi_mode_status['connected_devices']:
            wifi_mode_status['connected_devices'][device_id] = {
                'name': device_name,
                'last_heartbeat': datetime.now().isoformat(),
                'connected_at': datetime.now().isoformat(),
                'photo_count': len(device_photos.get(device_id, []))
            }
            print(f"✨ 新设备已连接: {device_name} ({device_id[:8]}...)")
        else:
            # 更新现有设备
            wifi_mode_status['connected_devices'][device_id]['last_heartbeat'] = datetime.now().isoformat()
            wifi_mode_status['connected_devices'][device_id]['name'] = device_name
            print(f"🔄 设备重新连接: {device_name} ({device_id[:8]}...)")
        
        print(f"📊 当前已连接设备数: {len(wifi_mode_status['connected_devices'])}")
        
        return jsonify({
            'success': True,
            'message': '设备连接成功',
            'device_id': device_id,
            'connected_devices': len(wifi_mode_status['connected_devices'])
        })
    
    except Exception as e:
        print(f"❌ 设备连接失败: {str(e)}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/api/wifi/info')
def wifi_info():
    """获取WiFi模式信息（本机IP、端口等）"""
    global wifi_mode_status

    local_ip = get_local_ip()
    port = 9500

    # 获取设备ID和设备名称
    device_id = request.args.get('device_id')
    device_name = request.args.get('device_name')

    # 只有当明确提供了device_id时才记录设备连接（Android设备调用）
    # Web页面调用时不会提供device_id，因此不会被记录为设备
    if device_id:
        # 清理超时设备
        cleanup_expired_devices()

        # 更新WiFi模式状态 - 记录设备连接
        wifi_mode_status['enabled'] = True
        current_time = datetime.now()

        if device_id not in wifi_mode_status['connected_devices']:
            # 新设备连接
            wifi_mode_status['connected_devices'][device_id] = {
                'name': device_name or '未知设备',
                'last_heartbeat': current_time.isoformat(),
                'connected_at': current_time.isoformat()
            }
            print(f"\n📱 新设备连接: {device_name or '未知设备'} ({device_id})")
        else:
            # 更新心跳时间
            wifi_mode_status['connected_devices'][device_id]['last_heartbeat'] = current_time.isoformat()

        wifi_mode_status['last_sync_time'] = current_time.isoformat()

        print(f"   当前在线设备: {len(wifi_mode_status['connected_devices'])} 个")

    # 构造返回的设备列表（仅包含设备ID和名称）
    devices_list = [
        {'id': dev_id, 'name': dev_info['name']}
        for dev_id, dev_info in wifi_mode_status['connected_devices'].items()
    ]

    return jsonify({
        'success': True,
        'ip': local_ip,
        'port': port,
        'url': f'http://{local_ip}:{port}',
        'connected_devices': devices_list,
        'device_count': len(devices_list)
    })

@app.route('/api/wifi/upload_photo_list', methods=['POST'])
def wifi_upload_photo_list():
    """WiFi模式：接收手机发送的照片列表"""
    global scan_status, device_photos
    
    try:
        data = request.get_json()
        if not data:
            return jsonify({
                'success': False,
                'error': '无效的数据格式'
            }), 400
        
        device_id = data.get('device_id', 'unknown')
        photos = data.get('photos', [])
        
        print(f"\n📱 收到照片列表上传请求:")
        print(f"   设备ID: {device_id}")
        print(f"   照片数量: {len(photos)}")
        
        if not photos:
            return jsonify({
                'success': False,
                'error': '照片列表为空'
            }), 400
        
        # 初始化设备的照片列表（如果不存在）
        if device_id not in device_photos:
            device_photos[device_id] = []
        
        # 累积添加新照片（检查去重）
        existing_paths = {photo['path'] for photo in device_photos[device_id]}
        new_photos = []
        for photo in photos:
            if photo.get('path') not in existing_paths:
                new_photos.append(photo)
                device_photos[device_id].append(photo)
        
        # 更新扫描状态（仅用于兼容性）
        scan_status['photos'] = device_photos.get(device_id, [])
        scan_status['stage'] = 'done'
        scan_status['files_found'] = len(device_photos.get(device_id, []))
        scan_status['files_processed'] = len(device_photos.get(device_id, []))
        scan_status['total_files'] = len(device_photos.get(device_id, []))
        scan_status['is_running'] = False
        
        # 更新WiFi模式状态
        total_photos = sum(len(photos) for photos in device_photos.values())
        wifi_mode_status['photos_received'] = total_photos
        wifi_mode_status['last_sync_time'] = datetime.now().isoformat()
        
        # 更新设备心跳时间（如果设备已连接）
        if device_id in wifi_mode_status['connected_devices']:
            wifi_mode_status['connected_devices'][device_id]['last_heartbeat'] = datetime.now().isoformat()
            wifi_mode_status['connected_devices'][device_id]['photo_count'] = len(device_photos[device_id])
            print(f"🔄 更新已有设备: {device_id[:8]}...")
        else:
            # 如果设备尚未连接，添加设备信息
            wifi_mode_status['connected_devices'][device_id] = {
                'name': f'设备 {device_id[:8]}',
                'last_heartbeat': datetime.now().isoformat(),
                'connected_at': datetime.now().isoformat(),
                'photo_count': len(device_photos[device_id])
            }
            print(f"✨ 新设备注册: {device_id[:8]}... - {len(device_photos[device_id])} 张照片")
        
        print(f"✅ WiFi模式：收到来自设备 {device_id[:8]}... 的 {len(new_photos)} 个新照片（总计 {len(device_photos[device_id])} 个）")
        print(f"📊 当前已连接设备数: {len(wifi_mode_status['connected_devices'])}")
        
        return jsonify({
            'success': True,
            'message': f'成功接收 {len(new_photos)} 个新照片信息',
            'count': len(new_photos),
            'total': len(device_photos[device_id])
        })
    
    except Exception as e:
        print(f"❌ WiFi模式上传照片列表失败: {str(e)}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/api/wifi/upload_photo', methods=['POST'])
def wifi_upload_photo():
    """WiFi模式：接收手机上传的照片文件 - 流式处理防止内存泄漏 + 断点续传支持"""
    global wifi_mode_status, upload_progress_data
    
    try:
        if 'file' not in request.files:
            return jsonify({
                'success': False,
                'error': '没有文件'
            }), 400
        
        file = request.files['file']
        if file.filename == '':
            return jsonify({
                'success': False,
                'error': '文件名为空'
            }), 400
        
        # 获取设备ID
        device_id = request.form.get('device_id', 'unknown')
        
        # 获取当前批次ID
        batch_id = None
        if device_id in upload_progress_data:
            batch_id = upload_progress_data[device_id].get('batch_id')
        
        # 获取相对路径（从手机端传来）
        relative_path = request.form.get('relative_path', '')
        # 优先使用WiFi模式设置的输出目录
        base_output_dir = request.form.get('output_dir') or wifi_mode_status.get('output_dir') or OUTPUT_DIR
        
        # 根据设备ID和批次ID创建子文件夹
        if batch_id:
            output_dir = os.path.join(base_output_dir, device_id, batch_id)
        else:
            # 如果没有批次ID，使用默认的设备文件夹
            output_dir = os.path.join(base_output_dir, device_id)
        
        if not relative_path:
            # 如果没有相对路径，使用文件名
            relative_path = secure_filename(file.filename)
        
        # 构建本地保存路径
        local_path = os.path.join(output_dir, relative_path)
        local_dir = os.path.dirname(local_path)
        
        # 创建目录
        os.makedirs(local_dir, exist_ok=True)
        
        # 🔥 断点续传：检查文件是否已存在
        expected_size = request.form.get('file_size')
        if expected_size:
            expected_size = int(expected_size)
        
        if os.path.exists(local_path):
            # 文件已存在，检查大小是否匹配
            existing_size = os.path.getsize(local_path)
            
            if expected_size and existing_size == expected_size:
                # 文件已完整上传，跳过
                batch_info_str = f", 批次: {batch_id}" if batch_id else ""
                print(f"⏭️  文件已存在，跳过: {relative_path} (设备: {device_id[:8]}...{batch_info_str})")
                
                # 更新设备心跳时间
                if device_id in wifi_mode_status['connected_devices']:
                    wifi_mode_status['connected_devices'][device_id]['last_heartbeat'] = datetime.now().isoformat()
                
                return jsonify({
                    'success': True,
                    'message': '文件已存在，跳过上传',
                    'skipped': True,
                    'path': local_path
                })
            elif existing_size > 0 and not expected_size:
                # 没有提供预期大小，但文件存在且非空，也跳过
                batch_info_str = f", 批次: {batch_id}" if batch_id else ""
                print(f"⏭️  文件已存在且非空，跳过: {relative_path} ({existing_size} bytes)")
                
                if device_id in wifi_mode_status['connected_devices']:
                    wifi_mode_status['connected_devices'][device_id]['last_heartbeat'] = datetime.now().isoformat()
                
                return jsonify({
                    'success': True,
                    'message': '文件已存在，跳过上传',
                    'skipped': True,
                    'path': local_path
                })
            else:
                # 文件大小不匹配，删除并重新上传
                print(f"⚠️  文件大小不匹配，重新上传: {relative_path} (现有: {existing_size}, 期望: {expected_size})")
                os.remove(local_path)
        
        # 使用流式保存，避免大文件加载到内存（重要！防止 OOM）
        CHUNK_SIZE = 8192  # 8KB 块大小
        with open(local_path, 'wb') as f:
            while True:
                chunk = file.stream.read(CHUNK_SIZE)
                if not chunk:
                    break
                f.write(chunk)
        
        # 验证文件大小
        actual_size = os.path.getsize(local_path)
        if expected_size and actual_size != expected_size:
            print(f"⚠️  警告：上传文件大小不匹配！期望: {expected_size}, 实际: {actual_size}")
        
        # 更新设备心跳时间
        if device_id in wifi_mode_status['connected_devices']:
            wifi_mode_status['connected_devices'][device_id]['last_heartbeat'] = datetime.now().isoformat()
        
        batch_info_str = f", 批次: {batch_id}" if batch_id else ""
        print(f"✅ 已保存到 {output_dir}: {relative_path} (设备: {device_id[:8]}...{batch_info_str})")
        
        return jsonify({
            'success': True,
            'message': '上传成功',
            'skipped': False,
            'path': local_path
        })
    
    except Exception as e:
        print(f"❌ WiFi模式上传照片失败: {str(e)}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500
    finally:
        # 确保文件流关闭（防止内存泄漏）
        try:
            if 'file' in request.files:
                request.files['file'].close()
        except:
            pass

@app.route('/api/wifi/batch_upload', methods=['POST'])
def wifi_batch_upload():
    """WiFi模式：批量上传照片（支持多文件）"""
    try:
        files = request.files.getlist('files')
        output_dir = request.form.get('output_dir', OUTPUT_DIR)
        
        if not files:
            return jsonify({
                'success': False,
                'error': '没有文件'
            }), 400
        
        uploaded = []
        failed = []
        
        for file in files:
            try:
                if file.filename == '':
                    continue
                
                # 获取文件的相对路径
                file_index = files.index(file)
                relative_path = request.form.get(f'relative_path_{file_index}', file.filename)
                
                # 构建本地保存路径
                local_path = os.path.join(output_dir, relative_path)
                local_dir = os.path.dirname(local_path)
                
                # 创建目录
                os.makedirs(local_dir, exist_ok=True)
                
                # 保存文件
                file.save(local_path)
                uploaded.append(relative_path)
                print(f"✅ 已保存: {relative_path}")
                
            except Exception as e:
                failed.append({
                    'filename': file.filename,
                    'error': str(e)
                })
                print(f"❌ 保存失败: {file.filename} - {str(e)}")
        
        return jsonify({
            'success': True,
            'uploaded': len(uploaded),
            'failed': len(failed),
            'uploaded_files': uploaded,
            'failed_files': failed
        })
    
    except Exception as e:
        print(f"❌ WiFi模式批量上传失败: {str(e)}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/api/wifi/heartbeat', methods=['POST'])
def wifi_heartbeat():
    """设备心跳接口"""
    global wifi_mode_status

    try:
        data = request.get_json() or {}
        device_id = data.get('device_id', 'unknown')

        # 清理超时设备
        cleanup_expired_devices()

        if device_id in wifi_mode_status['connected_devices']:
            # 更新心跳时间
            wifi_mode_status['connected_devices'][device_id]['last_heartbeat'] = datetime.now().isoformat()
            return jsonify({
                'success': True,
                'message': '心跳已更新'
            })
        else:
            return jsonify({
                'success': False,
                'error': '设备未连接，请先调用 /api/wifi/info'
            }), 404
    except Exception as e:
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/api/wifi/status')
def wifi_status():
    """获取WiFi模式状态"""
    global wifi_mode_status

    # 清理超时设备
    cleanup_expired_devices()

    # 构造设备列表
    devices_list = [
        {
            'id': dev_id,
            'name': dev_info['name'],
            'connected_at': dev_info['connected_at'],
            'last_heartbeat': dev_info['last_heartbeat']
        }
        for dev_id, dev_info in wifi_mode_status['connected_devices'].items()
    ]

    return jsonify({
        'success': True,
        'status': {
            'enabled': wifi_mode_status['enabled'],
            'connected_devices': devices_list,
            'device_count': len(devices_list),
            'photos_received': wifi_mode_status['photos_received'],
            'last_sync_time': wifi_mode_status['last_sync_time'],
            'output_dir': wifi_mode_status.get('output_dir', OUTPUT_DIR)
        }
    })

@app.route('/api/wifi/set_output_dir', methods=['POST'])
def wifi_set_output_dir():
    """设置WiFi模式的输出目录"""
    global wifi_mode_status
    
    try:
        data = request.get_json() or {}
        output_dir = data.get('output_dir', OUTPUT_DIR)
        
        # 验证目录是否存在或可创建
        try:
            os.makedirs(output_dir, exist_ok=True)
        except Exception as e:
            return jsonify({
                'success': False,
                'error': f'无法创建目录: {str(e)}'
            }), 400
        
        wifi_mode_status['output_dir'] = output_dir
        print(f"📁 WiFi模式输出目录已设置为: {output_dir}")
        
        return jsonify({
            'success': True,
            'message': '输出目录设置成功',
            'output_dir': output_dir
        })
    
    except Exception as e:
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/api/wifi/open_folder', methods=['POST'])
def wifi_open_folder():
    """打开存储文件夹（Mac使用Finder，Windows使用资源管理器）"""
    try:
        data = request.get_json() or {}
        folder_path = data.get('folder_path')
        
        if not folder_path:
            # 如果没有指定路径，使用WiFi模式的输出目录
            folder_path = wifi_mode_status.get('output_dir', OUTPUT_DIR)
        
        # 确保目录存在
        if not os.path.exists(folder_path):
            os.makedirs(folder_path, exist_ok=True)
        
        folder_path = os.path.abspath(folder_path)
        
        # 根据操作系统选择不同的打开方式
        import platform
        system = platform.system()
        
        print(f"\n📂 打开文件夹请求:")
        print(f"   路径: {folder_path}")
        print(f"   系统: {system}")
        
        if system == 'Darwin':  # macOS
            subprocess.run(['open', folder_path], check=True)
            print(f"✅ 已在 Finder 中打开文件夹")
        elif system == 'Windows':
            subprocess.run(['explorer', folder_path], check=True)
            print(f"✅ 已在资源管理器中打开文件夹")
        elif system == 'Linux':
            # Linux 尝试使用 xdg-open
            subprocess.run(['xdg-open', folder_path], check=True)
            print(f"✅ 已在文件管理器中打开文件夹")
        else:
            return jsonify({
                'success': False,
                'error': f'不支持的操作系统: {system}'
            }), 400
        
        return jsonify({
            'success': True,
            'message': f'已打开文件夹: {folder_path}',
            'folder_path': folder_path
        })
    
    except subprocess.CalledProcessError as e:
        print(f"❌ 打开文件夹失败: {str(e)}")
        return jsonify({
            'success': False,
            'error': f'无法打开文件夹: {str(e)}'
        }), 500
    except Exception as e:
        print(f"❌ 打开文件夹失败: {str(e)}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/api/wifi/devices', methods=['GET'])
def get_wifi_devices():
    """获取所有已连接的WiFi设备列表"""
    global wifi_mode_status, device_photos
    
    try:
        # 清理超时设备
        cleanup_expired_devices()
        
        devices = []
        for device_id, device_info in wifi_mode_status['connected_devices'].items():
            photo_count = len(device_photos.get(device_id, []))
            devices.append({
                'id': device_id,
                'name': device_info.get('name', f'设备 {device_id[:8]}'),
                'photo_count': photo_count,
                'connected_at': device_info.get('connected_at'),
                'last_heartbeat': device_info.get('last_heartbeat')
            })
        
        print(f"\n📋 返回设备列表: {len(devices)} 个设备")
        for device in devices:
            print(f"   - {device['name']} ({device['id'][:8]}...): {device['photo_count']} 张照片")
        
        return jsonify({
            'success': True,
            'devices': devices,
            'total': len(devices)
        })
    
    except Exception as e:
        print(f"❌ 获取WiFi设备列表失败: {str(e)}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/api/wifi/device_batches/<device_id>', methods=['GET'])
def get_device_batches(device_id):
    """获取指定设备的批次列表"""
    global device_upload_batches
    
    try:
        batches = device_upload_batches.get(device_id, [])
        
        # 返回批次摘要信息
        batch_summaries = []
        for batch in batches:
            batch_summaries.append({
                'batch_id': batch['batch_id'],
                'timestamp': batch['timestamp'],
                'photo_count': batch['photo_count'],
                'total_size_mb': batch['total_size_mb'],
                'status': batch.get('status', 'completed')
            })
        
        return jsonify({
            'success': True,
            'device_id': device_id,
            'batches': batch_summaries,
            'total': len(batch_summaries)
        })
    
    except Exception as e:
        print(f"❌ 获取设备 {device_id} 的批次列表失败: {str(e)}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/api/wifi/batch_photos/<device_id>/<batch_id>', methods=['GET'])
def get_batch_photos(device_id, batch_id):
    """获取指定批次的照片列表"""
    global device_upload_batches
    
    try:
        batches = device_upload_batches.get(device_id, [])
        batch_data = None
        
        for batch in batches:
            if batch['batch_id'] == batch_id:
                batch_data = batch
                break
        
        if not batch_data:
            return jsonify({
                'success': False,
                'error': '批次不存在'
            }), 404
        
        return jsonify({
            'success': True,
            'device_id': device_id,
            'batch_id': batch_id,
            'timestamp': batch_data['timestamp'],
            'photos': batch_data['photos'],
            'total': len(batch_data['photos']),
            'total_size_mb': batch_data['total_size_mb'],
            'is_legacy': batch_data.get('is_legacy', False)  # 告诉前端这是旧格式
        })
    
    except Exception as e:
        print(f"❌ 获取批次 {batch_id} 的照片失败: {str(e)}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/api/wifi/device_photos/<device_id>', methods=['GET'])
def get_device_photos(device_id):
    """获取指定设备的所有照片列表（所有批次合并，兼容旧接口）"""
    global device_photos
    
    try:
        photos = device_photos.get(device_id, [])
        
        return jsonify({
            'success': True,
            'device_id': device_id,
            'photos': photos,
            'total': len(photos)
        })
    
    except Exception as e:
        print(f"❌ 获取设备 {device_id} 的照片列表失败: {str(e)}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/api/wifi/photo/<device_id>/<path:photo_path>')
def serve_photo(device_id, photo_path):
    """提供照片文件服务（用于显示缩略图）
    支持：
    - 新格式带批次：/api/wifi/photo/device_id/batch_id/filename.jpg
    - 旧格式：/api/wifi/photo/device_id/filename.jpg
    """
    try:
        # 使用WiFi模式的输出目录，并包含设备ID子文件夹
        base_output_dir = wifi_mode_status.get('output_dir', OUTPUT_DIR)
        device_output_dir = os.path.join(base_output_dir, device_id)
        full_path = os.path.join(device_output_dir, photo_path)
        
        print(f"\n📸 请求照片:")
        print(f"   设备ID: {device_id[:8]}...")
        print(f"   照片路径: {photo_path}")
        print(f"   完整路径: {full_path}")
        print(f"   文件存在: {os.path.exists(full_path)}")
        
        # 安全检查：确保路径在设备输出目录内
        full_path = os.path.abspath(full_path)
        device_output_dir_abs = os.path.abspath(device_output_dir)
        
        if not full_path.startswith(device_output_dir_abs):
            print(f"❌ 非法路径访问: {full_path}")
            return jsonify({
                'success': False,
                'error': '非法路径'
            }), 403
        
        if os.path.exists(full_path):
            print(f"✅ 返回照片文件")
            return send_file(full_path, mimetype='image/jpeg')
        else:
            print(f"❌ 文件不存在: {full_path}")
            # 列出目录中的文件帮助调试
            if os.path.exists(device_output_dir):
                files = os.listdir(device_output_dir)
                print(f"   目录内容: {files[:10]}")  # 只显示前10个
            return jsonify({
                'success': False,
                'error': '文件不存在'
            }), 404
    
    except Exception as e:
        print(f"❌ 提供照片文件失败: {str(e)}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/api/wifi/open_photo_folder', methods=['POST'])
def wifi_open_photo_folder():
    """打开照片所在的文件夹"""
    try:
        data = request.get_json() or {}
        device_id = data.get('device_id')
        batch_id = data.get('batch_id')
        photo_path = data.get('photo_path')
        is_legacy = data.get('is_legacy', False)
        
        if not device_id or not photo_path:
            return jsonify({
                'success': False,
                'error': '缺少必要参数'
            }), 400
        
        # 构建照片所在目录
        base_output_dir = wifi_mode_status.get('output_dir', OUTPUT_DIR)
        device_output_dir = os.path.join(base_output_dir, device_id)
        
        # 根据批次类型确定目录
        if is_legacy or not batch_id:
            # 旧格式：照片在设备根目录
            folder_path = device_output_dir
        else:
            # 新格式：照片在批次文件夹内
            folder_path = os.path.join(device_output_dir, batch_id)
        
        # 确保目录存在
        if not os.path.exists(folder_path):
            return jsonify({
                'success': False,
                'error': '文件夹不存在'
            }), 404
        
        folder_path = os.path.abspath(folder_path)
        
        # 根据操作系统选择不同的打开方式
        import platform
        system = platform.system()
        
        print(f"\n📂 打开照片文件夹:")
        print(f"   设备ID: {device_id[:8]}...")
        print(f"   批次ID: {batch_id}")
        print(f"   照片: {photo_path}")
        print(f"   文件夹: {folder_path}")
        print(f"   系统: {system}")
        
        if system == 'Darwin':  # macOS
            subprocess.run(['open', folder_path], check=True)
            print(f"✅ 已在 Finder 中打开文件夹")
        elif system == 'Windows':
            subprocess.run(['explorer', folder_path], check=True)
            print(f"✅ 已在资源管理器中打开文件夹")
        elif system == 'Linux':
            subprocess.run(['xdg-open', folder_path], check=True)
            print(f"✅ 已在文件管理器中打开文件夹")
        else:
            return jsonify({
                'success': False,
                'error': f'不支持的操作系统: {system}'
            }), 400
        
        return jsonify({
            'success': True,
            'message': f'已打开文件夹',
            'folder_path': folder_path
        })
    
    except subprocess.CalledProcessError as e:
        print(f"❌ 打开文件夹失败: {str(e)}")
        return jsonify({
            'success': False,
            'error': f'无法打开文件夹: {str(e)}'
        }), 500
    except Exception as e:
        print(f"❌ 打开文件夹失败: {str(e)}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/api/wifi/delete_photo', methods=['POST'])
def wifi_delete_photo():
    """删除指定照片"""
    try:
        data = request.get_json() or {}
        device_id = data.get('device_id')
        batch_id = data.get('batch_id')
        photo_path = data.get('photo_path')
        is_legacy = data.get('is_legacy', False)
        
        if not device_id or not photo_path:
            return jsonify({
                'success': False,
                'error': '缺少必要参数'
            }), 400
        
        # 构建照片完整路径
        base_output_dir = wifi_mode_status.get('output_dir', OUTPUT_DIR)
        device_output_dir = os.path.join(base_output_dir, device_id)
        
        # 根据批次类型确定路径
        if is_legacy or not batch_id:
            # 旧格式：照片在设备根目录
            full_path = os.path.join(device_output_dir, photo_path)
        else:
            # 新格式：照片在批次文件夹内
            full_path = os.path.join(device_output_dir, batch_id, photo_path)
        
        # 安全检查：确保路径在设备输出目录内
        full_path = os.path.abspath(full_path)
        device_output_dir_abs = os.path.abspath(device_output_dir)
        
        if not full_path.startswith(device_output_dir_abs):
            print(f"❌ 非法路径访问: {full_path}")
            return jsonify({
                'success': False,
                'error': '非法路径'
            }), 403
        
        # 检查文件是否存在
        if not os.path.exists(full_path):
            return jsonify({
                'success': False,
                'error': '文件不存在'
            }), 404
        
        print(f"\n🗑️ 删除照片:")
        print(f"   设备ID: {device_id[:8]}...")
        print(f"   批次ID: {batch_id}")
        print(f"   照片: {photo_path}")
        print(f"   完整路径: {full_path}")
        
        # 删除文件
        os.remove(full_path)
        print(f"✅ 照片已删除")
        
        # 从内存中的照片记录中移除
        if not is_legacy and batch_id:
            # 新格式：从批次记录中移除
            if device_id in device_upload_batches:
                if batch_id in device_upload_batches[device_id]['batches']:
                    batch_data = device_upload_batches[device_id]['batches'][batch_id]
                    # 移除照片记录
                    batch_data['photos'] = [p for p in batch_data['photos'] if p['path'] != photo_path and p['name'] != photo_path]
                    print(f"   从批次记录中移除，剩余 {len(batch_data['photos'])} 张")
        else:
            # 旧格式：从设备记录中移除
            if device_id in device_photos:
                device_photos[device_id] = [p for p in device_photos[device_id] if p['path'] != photo_path and p['name'] != photo_path]
                print(f"   从设备记录中移除，剩余 {len(device_photos[device_id])} 张")
        
        return jsonify({
            'success': True,
            'message': '照片已删除'
        })
    
    except Exception as e:
        print(f"❌ 删除照片失败: {str(e)}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/api/wifi/delete_batch', methods=['POST'])
def wifi_delete_batch():
    """删除指定批次及其所有照片"""
    try:
        data = request.get_json() or {}
        device_id = data.get('device_id')
        batch_id = data.get('batch_id')

        if not device_id or not batch_id:
            return jsonify({
                'success': False,
                'error': '缺少必要参数'
            }), 400

        # 构建批次目录路径
        base_output_dir = wifi_mode_status.get('output_dir', OUTPUT_DIR)
        device_output_dir = os.path.join(base_output_dir, device_id)
        batch_dir = os.path.join(device_output_dir, batch_id)

        # 安全检查：确保路径在设备输出目录内
        batch_dir_abs = os.path.abspath(batch_dir)
        device_output_dir_abs = os.path.abspath(device_output_dir)

        if not batch_dir_abs.startswith(device_output_dir_abs):
            print(f"❌ 非法路径访问: {batch_dir_abs}")
            return jsonify({
                'success': False,
                'error': '非法路径'
            }), 403

        # 检查批次目录是否存在
        if not os.path.exists(batch_dir_abs):
            return jsonify({
                'success': False,
                'error': '批次目录不存在'
            }), 404

        print(f"\n🗑️ 删除批次:")
        print(f"   设备ID: {device_id[:8]}...")
        print(f"   批次ID: {batch_id}")
        print(f"   批次路径: {batch_dir_abs}")

        # 计算要删除的文件数量
        photo_count = 0
        for root, dirs, files in os.walk(batch_dir_abs):
            photo_count += len([f for f in files if f.lower().endswith(('.jpg', '.jpeg', '.png', '.gif', '.bmp', '.webp', '.heic'))])

        print(f"   包含 {photo_count} 张照片")

        # 删除整个批次目录
        import shutil
        shutil.rmtree(batch_dir_abs)
        print(f"✅ 批次目录已删除")

        # 从内存中的批次记录中移除
        if device_id in device_upload_batches:
            if batch_id in device_upload_batches[device_id].get('batches', {}):
                del device_upload_batches[device_id]['batches'][batch_id]
                print(f"   从内存记录中移除")

        # 从数据库中删除批次记录
        try:
            conn = get_db_connection()
            cursor = conn.cursor()

            # 删除批次中的所有照片记录
            cursor.execute("""
                DELETE FROM photos
                WHERE device_id = ? AND batch_id = ?
            """, (device_id, batch_id))

            # 删除批次记录
            cursor.execute("""
                DELETE FROM batches
                WHERE device_id = ? AND batch_id = ?
            """, (device_id, batch_id))

            conn.commit()
            conn.close()
            print(f"   从数据库中删除")
        except Exception as db_error:
            print(f"⚠️ 删除数据库记录失败: {str(db_error)}")

        return jsonify({
            'success': True,
            'message': f'批次已删除，共删除 {photo_count} 张照片'
        })

    except Exception as e:
        print(f"❌ 删除批次失败: {str(e)}")
        import traceback
        traceback.print_exc()
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/api/upload/init', methods=['POST'])
def init_upload():
    """初始化上传会话"""
    global upload_progress_data, wifi_mode_status, device_photos, device_upload_batches
    
    try:
        data = request.get_json() or {}
        device_id = data.get('device_id', 'unknown')
        files = data.get('files', [])
        
        print(f"\n📱 收到上传初始化请求:")
        print(f"   设备ID: {device_id}")
        print(f"   文件数量: {len(files)}")
        if len(files) > 0:
            print(f"   第一个文件: {files[0]}")
        
        if not files:
            print(f"⚠️ 文件列表为空！")
            return jsonify({
                'success': False,
                'error': '文件列表为空'
            }), 400
        
        # 生成批次ID（使用时间戳）
        batch_id = datetime.now().strftime('%Y%m%d_%H%M%S')
        batch_timestamp = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
        
        print(f"📦 创建新批次: {batch_id}")
        
        # 初始化上传进度
        upload_progress_data[device_id] = {
            'files': files,
            'current_index': 0,
            'completed': 0,
            'failed': 0,
            'is_uploading': True,
            'start_time': datetime.now().isoformat(),
            'file_status': {i: 'pending' for i in range(len(files))},
            'batch_id': batch_id  # 记录批次ID
        }
        
        # 初始化批次存储
        if device_id not in device_upload_batches:
            device_upload_batches[device_id] = []
        
        # 创建新批次
        batch_photos = []
        total_size = 0
        
        for file_info in files:
            file_name = file_info.get('name', '')
            file_size = file_info.get('size', 0)
            
            photo_info = {
                'name': file_name,
                'path': file_name,  # 使用文件名作为相对路径
                'size': file_size,
                'size_mb': round(file_size / 1024.0 / 1024.0, 2),
                'date': batch_timestamp
            }
            batch_photos.append(photo_info)
            total_size += file_size
        
        # 添加批次信息
        batch_info = {
            'batch_id': batch_id,
            'timestamp': batch_timestamp,
            'photo_count': len(batch_photos),
            'total_size': total_size,
            'total_size_mb': round(total_size / 1024.0 / 1024.0, 2),
            'photos': batch_photos,
            'status': 'uploading'
        }
        device_upload_batches[device_id].insert(0, batch_info)  # 最新的在前面
        
        # 同时更新旧格式以保持兼容
        if device_id not in device_photos:
            device_photos[device_id] = []
        device_photos[device_id].extend(batch_photos)
        
        # 注册或更新设备信息
        if device_id not in wifi_mode_status['connected_devices']:
            wifi_mode_status['connected_devices'][device_id] = {
                'name': f'设备 {device_id[:8]}',
                'last_heartbeat': datetime.now().isoformat(),
                'connected_at': datetime.now().isoformat(),
                'photo_count': len(device_photos[device_id])
            }
            print(f"✨ 新设备注册: {device_id[:8]}... (通过上传初始化)")
        else:
            wifi_mode_status['connected_devices'][device_id]['last_heartbeat'] = datetime.now().isoformat()
            wifi_mode_status['connected_devices'][device_id]['photo_count'] = len(device_photos[device_id])
            print(f"🔄 更新设备心跳: {device_id[:8]}... (通过上传初始化)")
        
        print(f"📤 设备 {device_id[:8]}... 初始化上传会话: {len(files)} 个文件")
        print(f"📦 批次ID: {batch_id}")
        print(f"📸 本批次照片数: {len(batch_photos)} 张，大小: {batch_info['total_size_mb']} MB")
        print(f"📊 该设备总批次数: {len(device_upload_batches[device_id])}")
        print(f"📊 当前已连接设备数: {len(wifi_mode_status['connected_devices'])}")
        
        # 保存批次信息到数据库
        save_batch_to_db(device_id, batch_info)
        
        return jsonify({
            'success': True,
            'message': '上传会话已初始化',
            'session_id': device_id,
            'batch_id': batch_id,
            'total_files': len(files)
        })
    
    except Exception as e:
        print(f"❌ 初始化上传会话失败: {str(e)}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/api/upload/progress/<device_id>', methods=['GET'])
def get_upload_progress(device_id):
    """获取上传进度"""
    global upload_progress_data
    
    try:
        if device_id not in upload_progress_data:
            return jsonify({
                'success': False,
                'error': '未找到上传会话'
            }), 404
        
        progress = upload_progress_data[device_id]
        
        return jsonify({
            'success': True,
            'device_id': device_id,
            'total': len(progress['files']),
            'completed': progress['completed'],
            'failed': progress['failed'],
            'current_index': progress['current_index'],
            'is_uploading': progress['is_uploading'],
            'file_status': progress['file_status'],
            'files': progress['files']
        })
    
    except Exception as e:
        print(f"❌ 获取上传进度失败: {str(e)}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/api/upload/update', methods=['POST'])
def update_upload_progress():
    """更新文件上传进度"""
    global upload_progress_data, device_upload_batches
    
    try:
        data = request.get_json() or {}
        device_id = data.get('device_id', 'unknown')
        file_index = data.get('file_index')
        status = data.get('status')  # 'uploading', 'completed', 'failed'
        
        if device_id not in upload_progress_data:
            return jsonify({
                'success': False,
                'error': '未找到上传会话'
            }), 404
        
        progress = upload_progress_data[device_id]
        
        if file_index is not None and 0 <= file_index < len(progress['files']):
            progress['file_status'][file_index] = status
            progress['current_index'] = file_index
            
            if status == 'completed':
                progress['completed'] += 1
            elif status == 'failed':
                progress['failed'] += 1
        
        # 检查是否全部完成
        if progress['completed'] + progress['failed'] >= len(progress['files']):
            progress['is_uploading'] = False
            progress['end_time'] = datetime.now().isoformat()
            
            # 更新批次状态为已完成
            batch_id = progress.get('batch_id')
            if batch_id and device_id in device_upload_batches:
                for batch in device_upload_batches[device_id]:
                    if batch['batch_id'] == batch_id:
                        batch['status'] = 'completed'
                        # 保存到数据库
                        save_batch_to_db(device_id, batch)
                        break
            
            print(f"✅ 设备 {device_id} 上传完成: 成功 {progress['completed']}, 失败 {progress['failed']}")
        
        return jsonify({
            'success': True,
            'message': '进度已更新'
        })
    
    except Exception as e:
        print(f"❌ 更新上传进度失败: {str(e)}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/api/upload/cancel/<device_id>', methods=['POST'])
def cancel_upload(device_id):
    """取消上传"""
    global upload_progress_data
    
    try:
        if device_id in upload_progress_data:
            upload_progress_data[device_id]['is_uploading'] = False
            upload_progress_data[device_id]['cancelled'] = True
            upload_progress_data[device_id]['end_time'] = datetime.now().isoformat()
            print(f"🚫 设备 {device_id} 取消上传")
        
        return jsonify({
            'success': True,
            'message': '上传已取消'
        })
    
    except Exception as e:
        print(f"❌ 取消上传失败: {str(e)}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/api/wifi/check_files', methods=['POST'])
def wifi_check_files():
    """检查哪些文件已经存在（用于断点续传）"""
    try:
        data = request.get_json() or {}
        device_id = data.get('device_id', 'unknown')
        batch_id = data.get('batch_id')
        files = data.get('files', [])  # [{'name': 'xxx.jpg', 'size': 12345}, ...]
        
        if not files:
            return jsonify({
                'success': False,
                'error': '文件列表为空'
            }), 400
        
        # 获取输出目录
        base_output_dir = wifi_mode_status.get('output_dir', OUTPUT_DIR)
        
        # 根据批次ID确定目录
        if batch_id:
            output_dir = os.path.join(base_output_dir, device_id, batch_id)
        else:
            output_dir = os.path.join(base_output_dir, device_id)
        
        # 检查每个文件
        existing_files = []
        missing_files = []
        
        for file_info in files:
            file_name = file_info.get('name')
            expected_size = file_info.get('size')
            
            if not file_name:
                continue
            
            file_path = os.path.join(output_dir, file_name)
            
            if os.path.exists(file_path):
                actual_size = os.path.getsize(file_path)
                
                # 如果提供了预期大小，检查是否匹配
                if expected_size:
                    if actual_size == expected_size:
                        existing_files.append({
                            'name': file_name,
                            'size': actual_size,
                            'status': 'complete'
                        })
                    else:
                        # 大小不匹配，需要重新上传
                        missing_files.append({
                            'name': file_name,
                            'size': expected_size,
                            'actual_size': actual_size,
                            'status': 'incomplete'
                        })
                else:
                    # 没有提供预期大小，只要文件存在就算完成
                    if actual_size > 0:
                        existing_files.append({
                            'name': file_name,
                            'size': actual_size,
                            'status': 'complete'
                        })
                    else:
                        missing_files.append({
                            'name': file_name,
                            'size': 0,
                            'status': 'empty'
                        })
            else:
                missing_files.append({
                    'name': file_name,
                    'size': expected_size,
                    'status': 'missing'
                })
        
        print(f"\n🔍 文件检查结果 (设备: {device_id[:8]}...):")
        print(f"   已存在: {len(existing_files)} 个")
        print(f"   需上传: {len(missing_files)} 个")
        
        return jsonify({
            'success': True,
            'device_id': device_id,
            'batch_id': batch_id,
            'total': len(files),
            'existing': len(existing_files),
            'missing': len(missing_files),
            'existing_files': existing_files,
            'missing_files': missing_files
        })
    
    except Exception as e:
        print(f"❌ 检查文件失败: {str(e)}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

if __name__ == '__main__':
    print("=" * 60)
    print("⚡⚡⚡ Android照片传输工具 - M4 超级并发版 ⚡⚡⚡")
    print("=" * 60)
    print(f"📁 输出目录: {OUTPUT_DIR}")
    print(f"🔥 扫描并发: {SCAN_CONCURRENT}线程 × {SCAN_BATCH_SIZE}文件/批")
    print(f"🚀 传输并发: {MAX_WORKERS}线程（史无前例！）")
    print(f"♻️  重试次数: {MAX_RETRIES}次")
    print(f"⚡ 极速模式: {'开启' if FAST_MODE else '关闭'}")
    print("=" * 60)
    
    # 初始化SQLite数据库
    print("\n🗄️  初始化数据库...")
    init_database()
    
    # 加载批次信息
    print("\n📂 从数据库加载历史批次信息...")
    if not load_batches_from_db():
        print("💡 数据库为空，开始扫描文件系统...")
        scan_and_rebuild_batches()
    else:
        # 即使数据库有数据，也检查是否有新的批次文件夹
        print("🔍 检查是否有新批次...")
        scan_and_rebuild_batches()
    
    print("\n🎯 M4 Mac 超级优化:")
    print(f"  • 16线程超高并发传输（突破性能极限！）")
    print(f"  • 24线程暴力并发扫描（每批300文件）")
    print(f"  • 断点续传（每{AUTO_SAVE_INTERVAL}个文件自动保存）")
    print(f"  • 预创建目录结构（消除并发冲突）")
    print(f"  • 智能跳过检查（减少远程查询）")
    print("  • 两阶段扫描（find快速定位 + 并发stat）")
    print("  • 实时速度预估（ETA显示）")
    print("=" * 60)
    print("\n⚡ 预期性能（M4 Mac + USB 3.0）:")
    print("  • 扫描速度: 1000-3000 文件/秒（24线程暴力并发）")
    print("  • 传输速度: 50-80 MB/s（16线程并发）")
    print("  • 26,000 文件扫描: 约10-20秒")
    print("  • 180GB传输: 约40-60分钟（首次）")
    print("  • 断点续传: 秒级恢复")
    print("  • 已存在文件: 毫秒级跳过")
    print("=" * 60)
    print("\n💡 性能提示:")
    print("  • USB 3.0连接速度最快（推荐）")
    print("  • 保持手机屏幕常亮")
    print("  • 关闭其他占用USB的程序")
    print("  • 首次传输较慢，增量传输极快")
    print("=" * 60)
    print("\n📊 技术栈:")
    print("  ✓ ThreadPoolExecutor 16并发")
    print("  ✓ as_completed 异步处理")
    print("  ✓ 预创建目录结构")
    print("  ✓ 智能去重算法")
    print("  ✓ 动态超时机制")
    print("=" * 60)
    print("\n📋 准备工作:")
    print("  1. ADB工具已安装 ✓")
    print("  2. USB 3.0连接（Type-C最佳）")
    print("  3. 开启USB调试并授权")
    print("  4. 手机设置为「文件传输模式」")
    print("\n🌐 服务启动中...")
    print("   访问 → http://127.0.0.1:9500")
    print("   M4芯片性能全开，准备起飞！🚀")
    print("=" * 60 + "\n")

    # 在服务启动前启动设备监控线程（避免debug模式重复启动，使用标记控制）
    start_device_monitor()
    app.run(debug=True, host='0.0.0.0', port=9500, threaded=True)
