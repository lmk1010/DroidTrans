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
import shutil
from pathlib import Path, PurePosixPath
from flask import Flask, render_template, jsonify, request, send_file
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor, as_completed
import threading
from werkzeug.utils import secure_filename
import re
import time
import base64

def _parse_timestamp_from_name(name: str) -> int | None:
    """尽量从常见相机/截图命名中解析拍摄时间（秒）。

    支持示例：
    - IMG_20241005_101058.jpg
    - VID_20241005_101058.mp4
    - PXL_20241005_101058123.jpg
    - MVIMG_20241005_101058.jpg
    - Screenshot_2024-10-05-10-10-58.png
    - 20241005_101058.jpg / 2024-10-05 10.10.58.jpg
    - mmexport1696501234567.jpg（13位毫秒时间戳）
    - 含连续14位时间戳的文件名：20241005101058.*
    返回 Unix 时间戳（秒）；无法解析返回 None。
    """
    s = name or ''
    try:
        # mmexport + 13位毫秒时间戳
        m = re.search(r'mmexport(\d{13})', s)
        if m:
            ms = int(m.group(1))
            return ms // 1000
        # 连续14位时间：YYYYMMDDHHMMSS
        m = re.search(r'(\d{14})', s)
        if m:
            t = time.strptime(m.group(1), '%Y%m%d%H%M%S')
            return int(time.mktime(t))
        # IMG_/VID_/PXL_/MVIMG_ YYYYMMDD_HHMMSS[任意尾随]
        m = re.search(r'(?:IMG|VID|PXL|MVIMG|Screenshot)[_-]?(\d{4})(\d{2})(\d{2})[T _-]?(\d{2})(\d{2})(\d{2})', s, re.IGNORECASE)
        if m:
            y, mo, d, hh, mm, ss = map(int, m.groups())
            dt = datetime(y, mo, d, hh, mm, ss)
            return int(dt.timestamp())
        # 2024-10-05-10-10-58 或 2024-10-05 10-10-58
        m = re.search(r'(\d{4})[-_](\d{2})[-_](\d{2})[ T_-](\d{2})[-_](\d{2})[-_](\d{2})', s)
        if m:
            y, mo, d, hh, mm, ss = map(int, m.groups())
            dt = datetime(y, mo, d, hh, mm, ss)
            return int(dt.timestamp())
        # YYYYMMDD_HHMMSS（无前缀）
        m = re.search(r'(\d{4})(\d{2})(\d{2})[ _-](\d{2})(\d{2})(\d{2})', s)
        if m:
            y, mo, d, hh, mm, ss = map(int, m.groups())
            dt = datetime(y, mo, d, hh, mm, ss)
            return int(dt.timestamp())
    except Exception:
        return None
    return None

# ADB Burst Mode 配置
ADB_BURST_MODE_ENABLED = os.getenv('ADB_BURST_MODE', '1')  # 默认启用Burst模式
FAST_ALBUM_SCAN = os.getenv('FAST_ALBUM_SCAN', '1') in ('1','true','on','yes')  # 仅相册封面快速扫描

app = Flask(__name__)

# 线程锁，保护 transfer_status 的并发更新
transfer_status_lock = threading.Lock()
# 扫描状态锁，避免首次扫描时竞态导致前端拿到 idle
scan_status_lock = threading.Lock()
scan_kick_ts = 0.0  # 最近一次“请求开始扫描”的时间戳（用于idle兜底）
scan_guard_until = 0.0  # 在该时间点之前，scan_status不返回idle（除非明确error/done）
scan_generation = 0  # 扫描序号（自增），用于彻底避免旧结果干扰

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
# 缩略图生成参数（可通过环境变量调优）- 极速查看默认
# 目标边长像素（默认 192，更快，观感影响小）
THUMB_TARGET_PX = int(os.getenv('THUMB_TARGET_PX', '192'))
# 图片JPEG质量（1-95，越低越快、体积更小）。默认 38
THUMB_IMG_QUALITY = int(os.getenv('THUMB_IMG_QUALITY', '38'))
# 视频缩略图 -q:v 参数（2-31，越大越快）。默认 12
THUMB_VIDEO_QV = int(os.getenv('THUMB_VIDEO_QV', '12'))
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
    'paused': False,
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
    'thumbs_done': 0,
    'started_ts': 0.0,
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

usb_speed_probe_running = False
usb_speed_probe_device = None
usb_speed_probe_lock = threading.Lock()
usb_speed_probe_ts = 0.0
usb_speed_tested_devices = set()  # 已测速的设备集合（生命周期内只测一次）

# USB测速结果持久化文件
USB_SPEED_CACHE_FILE = os.path.join(os.path.expanduser('~'), 'Documents', 'AndroidTransfer', 'usb_speed_cache.json')

def _load_usb_speed_cache():
    """从本地文件加载USB测速缓存"""
    try:
        if os.path.exists(USB_SPEED_CACHE_FILE):
            with open(USB_SPEED_CACHE_FILE, 'r', encoding='utf-8') as f:
                data = json.load(f)
                print(f"📂 已加载USB测速缓存: {len(data)} 个设备")
                return data
    except Exception as e:
        print(f"⚠️ 加载USB测速缓存失败: {e}")
    return {}

def _save_usb_speed_cache(device_serial, speed_data):
    """保存USB测速结果到本地文件"""
    try:
        # 确保目录存在
        os.makedirs(os.path.dirname(USB_SPEED_CACHE_FILE), exist_ok=True)

        # 读取现有缓存
        cache = _load_usb_speed_cache()

        # 更新缓存
        cache[device_serial] = {
            'speed_data': speed_data,
            'timestamp': time.time(),
            'date': time.strftime('%Y-%m-%d %H:%M:%S')
        }

        # 保存到文件
        with open(USB_SPEED_CACHE_FILE, 'w', encoding='utf-8') as f:
            json.dump(cache, f, indent=2, ensure_ascii=False)

        print(f"💾 已保存USB测速结果: {device_serial} -> {speed_data.get('measured_mbps', 0):.1f} MB/s")
    except Exception as e:
        print(f"⚠️ 保存USB测速缓存失败: {e}")

def _get_cached_usb_speed(device_serial):
    """获取缓存的USB测速结果"""
    try:
        cache = _load_usb_speed_cache()
        if device_serial in cache:
            cached = cache[device_serial]
            # 检查缓存是否过期（7天）
            if time.time() - cached.get('timestamp', 0) < 7 * 24 * 3600:
                print(f"📂 使用缓存的USB测速结果: {device_serial}")
                return cached.get('speed_data')
    except Exception as e:
        print(f"⚠️ 获取缓存的USB测速结果失败: {e}")
    return None

# 缩略图后台执行器与任务去重
THUMB_MAX_WORKERS = int(os.getenv('THUMB_MAX_WORKERS', '32'))
thumb_executor = ThreadPoolExecutor(max_workers=max(2, min(32, THUMB_MAX_WORKERS)))
thumb_inflight = set()  # set of remote_path

# 缓存清理防抖
_last_cache_clear_ts = 0.0
_cache_clear_lock = threading.Lock()

def _clear_thumb_cache_dirs(device_id=None):
    """删除缩略图及预览缓存目录，在设备断开时调用。

    Args:
        device_id: 设备ID，如果指定则只清理该设备的缓存；如果为None则清理所有缓存
    """
    global _last_cache_clear_ts

    # 防抖：10秒内只允许清理一次
    with _cache_clear_lock:
        now = time.time()
        if now - _last_cache_clear_ts < 10.0:
            return
        _last_cache_clear_ts = now

    try:
        # 确保基础目录存在
        os.makedirs(PREVIEW_DIR, exist_ok=True)
        os.makedirs(THUMB_DIR, exist_ok=True)

        cleared_count = 0
        cleared_size = 0

        if device_id:
            # 只清理指定设备的缓存
            # device_id 可以是设备标签字符串，或者是 'current' 表示当前设备
            if device_id == 'current':
                device_label = _get_current_device_label()
            else:
                device_label = device_id

            if not device_label or device_label == 'unknown_device':
                print(f"⚠️ 无效的设备标签，跳过缓存清理")
                return

            # 清理该设备的预览缓存
            device_preview_dir = os.path.join(PREVIEW_DIR, device_label)
            if os.path.isdir(device_preview_dir):
                try:
                    # 统计大小
                    for root, dirs, files in os.walk(device_preview_dir):
                        for f in files:
                            fp = os.path.join(root, f)
                            try:
                                cleared_size += os.path.getsize(fp)
                                cleared_count += 1
                            except:
                                pass

                    shutil.rmtree(device_preview_dir, ignore_errors=True)
                    print(f"🧹 已清理设备 {device_label} 的预览缓存: {cleared_count} 个文件, {cleared_size/1024/1024:.2f} MB")
                except Exception as e:
                    print(f"⚠️ 清理设备预览缓存失败: {device_preview_dir} -> {e}")

            # 清理该设备的缩略图缓存
            device_thumb_dir = os.path.join(THUMB_DIR, device_label)
            if os.path.isdir(device_thumb_dir):
                try:
                    # 统计大小
                    thumb_count = 0
                    thumb_size = 0
                    for root, dirs, files in os.walk(device_thumb_dir):
                        for f in files:
                            fp = os.path.join(root, f)
                            try:
                                thumb_size += os.path.getsize(fp)
                                thumb_count += 1
                            except:
                                pass

                    shutil.rmtree(device_thumb_dir, ignore_errors=True)
                    print(f"🧹 已清理设备 {device_label} 的缩略图缓存: {thumb_count} 个文件, {thumb_size/1024/1024:.2f} MB")
                    cleared_count += thumb_count
                    cleared_size += thumb_size
                except Exception as e:
                    print(f"⚠️ 清理设备缩略图缓存失败: {device_thumb_dir} -> {e}")
        else:
            # 清理所有缓存（保留用于手动清理或特殊情况）
            if os.path.isdir(THUMB_DIR):
                for entry in os.listdir(THUMB_DIR):
                    path = os.path.join(THUMB_DIR, entry)
                    try:
                        if os.path.isdir(path):
                            # 统计大小
                            for root, dirs, files in os.walk(path):
                                for f in files:
                                    fp = os.path.join(root, f)
                                    try:
                                        cleared_size += os.path.getsize(fp)
                                        cleared_count += 1
                                    except:
                                        pass
                            shutil.rmtree(path, ignore_errors=True)
                        else:
                            try:
                                cleared_size += os.path.getsize(path)
                                cleared_count += 1
                            except:
                                pass
                            os.remove(path)
                    except Exception as e:
                        print(f"⚠️ 清理缓存失败: {path} -> {e}")

            if cleared_count > 0:
                print(f"🧹 已清空所有缩略图缓存: {cleared_count} 个文件, {cleared_size/1024/1024:.2f} MB")

        # 确保基础目录存在
        os.makedirs(THUMB_DIR, exist_ok=True)

    except Exception as e:
        print(f"⚠️ 缩略图缓存清理异常: {e}")

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
        pending_list = [p for p in photos if p['path'] not in completed_files]
        total_count = len(completed_files) + len(pending_list)

        progress_data = {
            'timestamp': datetime.now().isoformat(),
            'output_dir': output_dir,
            'total': total_count,
            'completed': len(completed_files),
            'completed_files': list(completed_files),
            'failed_files': failed_files,
            'pending_photos': pending_list
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
    global device_status, selected_device, usb_speed_probe_running

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

    just_connected = connected and not was_connected

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
            just_connected = True

    # 如果之前连接但现在断开，标记断开事件
    if not connected and device_status.get('was_connected', False):
        device_status['disconnect_detected'] = True
        print(f"\n⚠️  检测到设备断开！")

        # 在设备标识被清空前，保存设备标签用于清理缓存
        disconnected_device_label = None
        disconnected_device_serial = selected_device
        try:
            if selected_device:
                disconnected_device_label = _get_current_device_label()
        except Exception:
            pass

        try:
            usb_speed_cache['device'] = None
            usb_speed_cache['data'] = None
            usb_speed_cache['ts'] = 0
        except Exception:
            pass
        usb_speed_probe_running = False

        # 清除该设备的测速记录，下次连接时重新测速
        try:
            if disconnected_device_serial and disconnected_device_serial in usb_speed_tested_devices:
                usb_speed_tested_devices.discard(disconnected_device_serial)
                print(f"🧹 已清除设备 {disconnected_device_serial} 的测速记录")
        except Exception:
            pass

        # 只清理当前设备的缓存
        try:
            if disconnected_device_label:
                _clear_thumb_cache_dirs(device_id=disconnected_device_label)
            else:
                print(f"⚠️ 无法获取设备标签，跳过缓存清理")
        except Exception as e:
            print(f"⚠️ 清理设备缓存时出错: {e}")

        try:
            thumb_inflight.clear()
        except Exception:
            pass

    device_status['was_connected'] = connected

    # 维护选中设备
    if connected:
        if not selected_device or selected_device not in devices:
            selected_device = devices[0]
    else:
        selected_device = None

    if connected and just_connected:
        # 抑制重复触发：同一瞬间多线程或多路检查导致的抖动
        from time import time as _now
        now = _now()
        last_cts = device_status.get('last_connect_ts', 0)
        if now - float(last_cts or 0) > 5:
            device_status['last_connect_ts'] = now
            trigger_usb_speed_probe()

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
    # 多策略，避免网络受限时抛错
    # 1) macOS 常见网卡 en0
    if sys.platform == 'darwin':
        try:
            p = subprocess.run(['ipconfig', 'getifaddr', 'en0'], capture_output=True, text=True, timeout=1)
            if p.returncode == 0 and p.stdout.strip():
                return p.stdout.strip().splitlines()[0]
        except Exception:
            pass
    # 2) UDP socket 方法（无需真正联网）
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))  # 不可达保留地址，只用于获取本机绑定IP
        ip = s.getsockname()[0]
        s.close()
        if ip and not ip.startswith('127.'):
            return ip
    except Exception:
        pass
    # 3) 回退环回地址
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

def scan_photos_thread(expected_scan_id=None):
    """后台扫描照片线程 - 极速优化版"""
    global scan_status

    print(f"🔍 [DEBUG] 扫描线程开始执行: expected_scan_id={expected_scan_id}")

    with scan_status_lock:
        scan_status['is_running'] = True
        scan_status['stage'] = 'finding'
        scan_status['started_ts'] = time.time()
        if expected_scan_id is not None:
            scan_status['scan_id'] = expected_scan_id
        # 移到锁内，避免竞态条件
        scan_status['files_found'] = 0
        scan_status['files_processed'] = 0
        scan_status['total_files'] = 0
        scan_status['photos'] = []
        scan_status['albums'] = {}
        scan_status['error'] = None
        scan_status['albums_preview'] = {}
        scan_status['albums_map'] = {}
        print(f"🔍 [DEBUG] 扫描状态已更新: is_running={scan_status['is_running']}, stage={scan_status['stage']}, scan_id={scan_status.get('scan_id')}")

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
                # 提前投递预览占位，便于前端首屏尽快展示相册卡片
                try:
                    album_name_quick = os.path.basename(dir_path.rstrip('/')) or 'Pictures'
                    with scan_status_lock:
                        if 'albums_preview' not in scan_status or not isinstance(scan_status['albums_preview'], dict):
                            scan_status['albums_preview'] = {}
                        scan_status['albums_preview'][dir_path] = {
                            'name': album_name_quick,
                            'icon': '📁',
                            'priority': 10,
                            'total_count': 0,
                            'total_size': 0,
                            'cover': cover,
                        }
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
                # 跳过空相册（0 文件）
                if total_count <= 0:
                    # 同步预览（保持为0），但不纳入最终albums
                    try:
                        ap = scan_status.get('albums_preview') or {}
                        if dir_path in ap:
                            ap[dir_path]['total_count'] = 0
                            ap[dir_path]['total_size'] = 0
                    except Exception:
                        pass
                    continue
                albums[dir_path] = {
                    'name': album_name,
                    'icon': '📁',
                    'priority': 10,
                    'photos': [],  # 延迟加载
                    'total_count': total_count,
                    'total_size': total_size,
                    'cover': cover
                }
                # 增量更新预览计数
                try:
                    ap = scan_status.get('albums_preview') or {}
                    if dir_path in ap:
                        ap[dir_path]['total_count'] = total_count
                        ap[dir_path]['total_size'] = total_size
                except Exception:
                    pass
            # 使用锁确保状态更新的原子性
            with scan_status_lock:
                print(f"🔍 [DEBUG] 设置状态前: stage={scan_status.get('stage')}, is_running={scan_status.get('is_running')}, started_ts={scan_status.get('started_ts')}")
                scan_status['albums'] = albums
                scan_status['stage'] = 'done'
                scan_status['is_running'] = False  # 重要：标记扫描完成
                # 保留 started_ts，不要重置
                print(f"🔍 [DEBUG] 设置状态后: stage={scan_status.get('stage')}, is_running={scan_status.get('is_running')}, started_ts={scan_status.get('started_ts')}, albums_count={len(scan_status.get('albums', {}))}")
            print(f"✅ [DEBUG] 快速扫描完成，状态更新: stage={scan_status['stage']}, is_running={scan_status['is_running']}, albums={len(albums)}")
            # 异步封面缩略图生成
            try:
                covers = [a.get('cover') for a in albums.values() if a.get('cover')]
                print(f"🎯 封面预生成(后台): {len(covers)}")
                for c in covers:
                    try:
                        tp = _thumb_local_path(c, size=256)
                        if os.path.exists(tp) and os.path.getsize(tp) > 0:
                            continue
                        thumb_executor.submit(lambda p=c, t=tp: _ensure_thumb_from_remote(p, t, 256))
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
                # 区分“未连接设备”与“设备断开”两种场景，避免在从未连接过时提示断开
                if device_status.get('was_connected', False):
                    msg = '设备连接已断开'
                else:
                    msg = '未检测到设备连接'
                print(f"\n❌ {msg}，停止扫描")
                scan_status['error'] = msg
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
                with scan_status_lock:
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
    import concurrent.futures as cf

    with transfer_status_lock:
        transfer_status['is_running'] = True
        transfer_status['paused'] = False
        if resume:
            existing_completed = transfer_status.get('completed_files', set())
            if not isinstance(existing_completed, set):
                existing_completed = set(existing_completed or [])
                transfer_status['completed_files'] = existing_completed
        else:
            transfer_status['completed_files'] = set()
        completed_count = len(transfer_status.get('completed_files', set()))
        transfer_status['total'] = len(photos) + completed_count
        transfer_status['current'] = completed_count
        transfer_status['failed'] = []
        transfer_status['error'] = None
        transfer_status['output_dir'] = output_dir

    os.makedirs(output_dir, exist_ok=True)

    resume_base = completed_count

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
    transfer_status['last_bytes'] = 0
    transfer_status['last_ts'] = start_time
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

    # 使用线程池并限制在途任务，支持暂停
    executor = ThreadPoolExecutor(max_workers=MAX_WORKERS, thread_name_prefix='TransferWorker')
    try:
        next_idx = 0
        futures = {}
        last_progress = -1
        completed = 0
        last_device_check = time.time()
        device_check_interval = 5

        def submit_one():
            nonlocal next_idx
            if next_idx >= len(photos):
                return False
            ph = photos[next_idx]
            next_idx += 1
            fut = executor.submit(transfer_single_photo, ph, output_dir)
            futures[fut] = ph
            return True

        # 初始填满工作队列
        while len(futures) < MAX_WORKERS and next_idx < len(photos):
            submit_one()

        while True:
            # 停止请求：取消未开始的任务并退出
            if not transfer_status['is_running']:
                for f in list(futures.keys()):
                    f.cancel()
                break

            # 设备心跳检查
            current_time = time.time()
            if current_time - last_device_check > device_check_interval:
                connected, _ = check_adb_connection()
                if not connected:
                    # 区分“未连接设备”与“设备断开”两种场景
                    if device_status.get('was_connected', False):
                        msg = '设备连接已断开'
                    else:
                        msg = '未检测到设备连接'
                    print(f"\n❌ {msg}，停止传输！")
                    transfer_status['error'] = msg
                    transfer_status['is_running'] = False
                    for f in list(futures.keys()):
                        f.cancel()
                    break
                last_device_check = current_time

            # 若未暂停则补充任务
            while (not transfer_status.get('paused', False)) and len(futures) < MAX_WORKERS and next_idx < len(photos):
                submit_one()

            if not futures:
                # 没有在途任务
                if next_idx >= len(photos):
                    break
                # 暂停中，稍候
                time.sleep(0.2)
                continue

            # 等待任一完成（短超时以便响应暂停/停止）
            done = []
            for fut in list(futures.keys()):
                try:
                    # 非阻塞式检查
                    res = fut.result(timeout=0.01)
                    done.append((fut, res))
                except cf.TimeoutError:
                    # 未就绪
                    continue
                except Exception as e:
                    # 任务异常，视为完成交由下方处理
                    done.append((fut, e))

            if not done:
                time.sleep(0.05)
                continue

            for fut, res in done:
                photo = futures.pop(fut, None) or {'path': 'unknown', 'name': 'unknown'}
                try:
                    if isinstance(res, Exception):
                        raise res
                    result_photo, success, msg = res
                except Exception as e:
                    completed += 1
                    overall_done = completed + resume_base
                    transfer_status['current'] = overall_done
                    transfer_status['failed'].append({
                        'path': photo.get('path', ''),
                        'error': str(e)
                    })
                    print(f"❌ 异常: {photo.get('name', 'Unknown')} - {str(e)}")
                    continue

                completed += 1
                overall_done = completed + resume_base
                transfer_status['current'] = overall_done
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

                    # 计算本地文件大小（即使是已存在也计入）
                    try:
                        sz = int(result_photo.get('size', 0))
                    except Exception:
                        sz = 0
                    if sz <= 0:
                        try:
                            rel_path = result_photo['path'].replace('/sdcard/', '').replace('/storage/emulated/0/', '').replace('/storage/self/primary/', '')
                            local_fp = os.path.join(output_dir, rel_path)
                            if os.path.exists(local_fp):
                                sz = os.path.getsize(local_fp)
                        except Exception:
                            pass

                    if "已存在" in msg:
                        skipped_count += 1

                    # 累加字节并更新时间/速度（平均）
                    with transfer_status_lock:
                        transfer_status['bytes_done'] = transfer_status.get('bytes_done', 0) + max(0, int(sz))
                        now = time.time()
                        transfer_status['elapsed_sec'] = max(0.0, now - transfer_status.get('start_ts', start_time))
                        if transfer_status['elapsed_sec'] > 0:
                            transfer_status['speed_mbps'] = max(0.0, (transfer_status['bytes_done'] / 1024.0 / 1024.0) / transfer_status['elapsed_sec'])
                        else:
                            transfer_status['speed_mbps'] = 0.0
                        transfer_status['last_bytes'] = transfer_status['bytes_done']
                        transfer_status['last_ts'] = now
                        if transfer_status['speed_mbps'] > 0 and transfer_status.get('bytes_total', 0) > 0:
                            remaining_mb = max(0.0, (transfer_status.get('bytes_total', 0) - transfer_status['bytes_done']) / 1024.0 / 1024.0)
                            transfer_status['eta_sec'] = remaining_mb / transfer_status['speed_mbps']
                
                # 定期自动保存进度
                if completed - last_save_count >= AUTO_SAVE_INTERVAL:
                    print(f"\n💾 自动保存进度... ({overall_done}/{transfer_status['total']})")
                    all_photos = photos  # 保存原始照片列表
                    save_progress(all_photos, output_dir, transfer_status['completed_files'], transfer_status['failed'])
                    last_save_count = completed
                
                # 优化进度显示：只在进度变化5%或每完成100个文件时显示
                progress = int(overall_done / transfer_status['total'] * 100) if transfer_status['total'] else 100
                if (progress - last_progress >= 5) or (completed % 100 == 0) or (overall_done == transfer_status['total']):
                    elapsed = time.time() - start_time
                    speed = completed / elapsed if elapsed > 0 else 0
                    eta = (transfer_status['total'] - overall_done) / speed if speed > 0 else 0
                    
                    print(f"⚡ 进度: {progress}% ({overall_done}/{transfer_status['total']}) | "
                          f"速度: {speed:.1f}文件/秒 | "
                          f"预计剩余: {int(eta)}秒 | "
                          f"已跳过: {skipped_count}")
                    last_progress = progress
            
    finally:
        try:
            executor.shutdown(wait=False, cancel_futures=True)
        except Exception:
            pass

    # 结束前对齐一次状态，保证最终快照一致
    try:
        with transfer_status_lock:
            transfer_status['current'] = transfer_status.get('total', 0)
            # 若已完成字节统计可对齐到总字节
            if transfer_status.get('bytes_total', 0) > 0:
                transfer_status['bytes_done'] = max(transfer_status.get('bytes_done', 0), transfer_status['bytes_total'])
            transfer_status['eta_sec'] = 0.0
    except Exception:
        pass
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

    # 将本次USB批次写入数据库（基于输出目录扫描），便于历史记录立即可见
    try:
        save_usb_batch_from_folder(output_dir)
    except Exception as e:
        print(f"⚠️ 保存USB批次到数据库失败: {e}")

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

# Chrome 120+ 会在打开 DevTools 时尝试加载域定制配置：
# /.well-known/appspecific/com.chrome.devtools.json
# 404 虽无害，但可能在某些环境下触发额外的等待或报错提示。
# 这里返回一个空对象并设置短期缓存，避免无谓的404日志、提升首屏观感。
@app.route('/.well-known/appspecific/com.chrome.devtools.json')
def chrome_devtools_well_known():
    try:
        from flask import make_response
        resp = make_response('{}', 200)
        resp.mimetype = 'application/json'
        resp.headers['Cache-Control'] = 'public, max-age=86400'
        return resp
    except Exception:
        return ('{}', 200, {'Content-Type': 'application/json'})

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
    trigger_usb_speed_probe()
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

    print(f"🔍 [DEBUG] 收到扫描请求, 当前状态: is_running={scan_status.get('is_running')}, stage={scan_status.get('stage')}")

    if scan_status['is_running']:
        print(f"⚠️ [DEBUG] 扫描已在进行中，拒绝重复启动")
        return jsonify({
            'success': False,
            'error': '扫描正在进行中'
        }), 400

    # 记录"请求开始扫描"的时刻（即使线程尚未真正启动，也避免前端误判idle）
    global scan_kick_ts, scan_guard_until, scan_generation
    scan_kick_ts = time.time()
    # 在启动后的保护窗口内，/api/scan_status 不返回 idle（防抖30秒）
    scan_guard_until = scan_kick_ts + 30.0

    # 重置状态（加锁，保证前端第一轮轮询拿到finding）
    with scan_status_lock:
        # 扫描序号自增
        try:
            scan_generation = int(scan_generation) + 1
        except Exception:
            scan_generation = 1
        scan_status['is_running'] = True
        scan_status['stage'] = 'finding'
        scan_status['current_dir'] = ''
        scan_status['files_found'] = 0
        scan_status['files_processed'] = 0
        scan_status['total_files'] = 0
        scan_status['photos'] = []
        scan_status['error'] = None
        scan_status['albums_preview'] = {}
        scan_status['albums_map'] = {}
        scan_status['thumbs_total'] = 0
        scan_status['thumbs_done'] = 0
        scan_status['started_ts'] = time.time()
        scan_status['scan_id'] = scan_generation

    print(f"✅ [DEBUG] 扫描状态已重置: scan_id={scan_generation}, is_running={scan_status['is_running']}, stage={scan_status['stage']}")

    # 启动后台扫描线程
    thread = threading.Thread(target=scan_photos_thread, args=(scan_generation,))
    thread.daemon = True
    thread.start()

    print(f"🚀 [DEBUG] 扫描线程已启动: thread={thread.name}, scan_id={scan_generation}")

    return jsonify({
        'success': True,
        'message': '扫描已开始',
        'scan_id': scan_generation
    })

@app.route('/api/scan_status')
def get_scan_status():
    """获取扫描状态"""
    now = time.time()  # 提前定义 now，避免重复调用
    try:
        with scan_status_lock:
            # 拷贝快照，避免遍历时被并发修改
            # 使用 dict() 而不是直接赋值，避免浅拷贝问题
            albums_preview_copy = {}
            try:
                # 安全复制 albums_preview，避免并发修改异常
                if scan_status.get('albums_preview'):
                    albums_preview_copy = dict(scan_status['albums_preview'])
            except (RuntimeError, ValueError) as e:
                # 字典在迭代时被修改，使用空字典
                print(f"⚠️ [DEBUG] albums_preview 复制失败: {e}")
                albums_preview_copy = {}

            st = {
                'is_running': scan_status.get('is_running', False),
                'stage': scan_status.get('stage', 'idle'),
                'current_dir': scan_status.get('current_dir', ''),
                'files_found': scan_status.get('files_found', 0),
                'files_processed': scan_status.get('files_processed', 0),
                'total_files': scan_status.get('total_files', 0),
                'photo_count': len(scan_status.get('photos') or []),
                'error': scan_status.get('error'),
                'albums_preview': albums_preview_copy,
                'thumbs_total': scan_status.get('thumbs_total', 0),
                'thumbs_done': scan_status.get('thumbs_done', 0),
                'started_ts': scan_status.get('started_ts', 0.0),
                'scan_id': scan_status.get('scan_id', 0),
            }
            # 在锁内读取 has_albums，避免并发问题
            has_albums = bool(scan_status.get('albums'))

            print(f"🔍 [DEBUG] scan_status 原始状态: is_running={st['is_running']}, stage={st['stage']}, started_ts={st['started_ts']}")

        # 若刚刚触发扫描，允许短期将 idle 纠正为 finding，避免前端误判中断
        try:
            if (not st['is_running']) and st.get('stage') == 'idle':
                now = time.time()
                started = float(st.get('started_ts') or 0.0)
                # 1) 若 started_ts 近，直接视为 finding
                if started > 0 and (now - started) <= 30.0:
                    st['is_running'] = True
                    st['stage'] = 'finding'
                    print(f"🔍 [DEBUG] 根据 started_ts 纠正状态为 finding")
                else:
                    # 2) 若 started_ts 为0，但刚调用过 /api/scan，也视为 finding
                    try:
                        global scan_kick_ts, scan_guard_until
                    except Exception:
                        scan_kick_ts = 0.0
                        scan_guard_until = 0.0
                    # 保护窗口：kick后30秒内不报告idle
                    if (scan_kick_ts > 0 and (now - scan_kick_ts) <= 30.0) or (scan_guard_until and now <= scan_guard_until):
                        st['is_running'] = True
                        st['stage'] = 'finding'
                        st['started_ts'] = scan_kick_ts
                        print(f"🔍 [DEBUG] 根据 scan_kick_ts 纠正状态为 finding")
        except Exception as e:
            print(f"⚠️ [DEBUG] 状态纠正异常: {e}")
            pass

        # 诊断日志：若出现 idle 但已有相册或预览，打印并兜底为 done
        try:
            ap_len = len(st.get('albums_preview') or {})
        except Exception:
            ap_len = 0

        # 更严格的检查：任何有数据的 idle 状态都应该是 done
        if (not st['is_running']) and st.get('stage') == 'idle':
            # 检查是否有任何扫描数据
            if ap_len > 0 or has_albums:
                print(f"[SCAN_STATUS] Unexpected idle with data: albums_preview={ap_len}, has_albums={has_albums}, 纠正为 done")
                st['stage'] = 'done'
            # 额外检查：如果 started_ts 不为 0，且在 60 秒内，说明扫描刚完成
            elif st.get('started_ts', 0) > 0 and (now - st.get('started_ts', 0)) < 60:
                print(f"[SCAN_STATUS] Unexpected idle with recent started_ts: {st.get('started_ts')}, 纠正为 finding")
                st['is_running'] = True
                st['stage'] = 'finding'

        return jsonify(st)
    except Exception as e:
        # 避免接口异常导致前端中断
        # 不要返回 idle，而是返回更保守的状态
        print(f"❌ [ERROR] get_scan_status 异常: {e}")
        import traceback
        traceback.print_exc()

        # 尝试从全局变量读取最后已知状态
        try:
            with scan_status_lock:
                last_stage = scan_status.get('stage', 'error')
                last_running = scan_status.get('is_running', False)
        except:
            last_stage = 'error'
            last_running = False

        return jsonify({
            'is_running': last_running,
            'stage': last_stage if last_stage != 'idle' else 'error',  # 不返回 idle
            'error': f'获取状态异常: {str(e)}',
            'albums_preview': {},
            'started_ts': 0.0,
            'scan_id': 0
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
    """根据远程路径生成缓存原始文件本地路径（保留原始扩展名）。

    说明：Android 同一文件可能存在多种常见别名（如 /sdcard 与
    /storage/emulated/0）。缩略图/预览缓存依赖路径哈希，为避免别名
    导致的缓存未命中，这里统一先进行路径标准化再计算哈希。
    """
    # 统一远程路径，避免 /sdcard 与 /storage/emulated/0 别名导致哈希不一致
    try:
        from pathlib import PurePosixPath as _PP
        norm_remote = normalize_remote_path(remote_path)
    except Exception:
        norm_remote = remote_path

    ext = PurePosixPath(norm_remote).suffix.lower()
    if not ext:
        ext = '.bin'
    h = hashlib.sha1(norm_remote.encode('utf-8', errors='ignore')).hexdigest()
    dev = _get_current_device_label()
    return os.path.join(PREVIEW_DIR, dev, f"{h}{ext}")

def _adb_prefix() -> str:
    try:
        if selected_device:
            return f"adb -s {selected_device}"
    except Exception:
        pass
    return "adb"

def _adb_cat_bytes(remote_path: str, timeout: int = 20) -> bytes | None:
    """通过 adb exec-out cat 读取远程文件的原始字节，不落地缓存。"""
    try:
        rp = normalize_remote_path(remote_path)
        esc = rp.replace("'", "'\\''")
        cmd = f"{_adb_prefix()} exec-out cat '{esc}'"
        env = os.environ.copy()
        if ADB_BURST_MODE_ENABLED == '1':
            env['ADB_DELAYED_ACK'] = '1'
        p = subprocess.run(cmd, shell=True, capture_output=True, text=False, timeout=timeout, env=env)
        if p.returncode == 0 and p.stdout:
            return p.stdout
    except subprocess.TimeoutExpired:
        return None
    except Exception:
        return None
    return None

def _ensure_thumbnail_from_bytes(data: bytes, dst_thumb_path: str, max_size: int = 512, quality: int = 70) -> bool:
    try:
        from PIL import Image, ImageOps, ImageFile
        if not data:
            return False
        ImageFile.LOAD_TRUNCATED_IMAGES = True
        from io import BytesIO
        bio = BytesIO(data)
        with Image.open(bio) as im:
            try:
                im.draft('RGB', (max_size * 2, max_size * 2))
            except Exception:
                pass
            try:
                im = ImageOps.exif_transpose(im)
            except Exception:
                pass
            if im.mode not in ('RGB', 'L'):
                im = im.convert('RGB')
            try:
                from PIL import Image as _I
                Resampling = getattr(_I, 'Resampling', None)
                resample = Resampling.BILINEAR if Resampling else 2
            except Exception:
                resample = 2
            im.thumbnail((max_size, max_size), resample)
            os.makedirs(os.path.dirname(dst_thumb_path), exist_ok=True)
            tmp = dst_thumb_path + '.part.jpg'
            im.save(tmp, format='JPEG', quality=quality, optimize=True)
            _safe_atomic_replace(tmp, dst_thumb_path)
            if not _is_valid_jpeg(dst_thumb_path):
                try:
                    os.remove(dst_thumb_path)
                except Exception:
                    pass
                return False
        return True
    except Exception:
        return False

def _ensure_video_thumb_stream(remote_path: str, dst_thumb_path: str, max_size: int = 320, quality: int = 6, ss: float = 0.5) -> bool:
    """通过管道从设备读取视频并在本机ffmpeg抽帧，不缓存原视频。"""
    try:
        rp = normalize_remote_path(remote_path)
        esc = rp.replace("'", "'\\''")
        vf = f"scale={max_size}:-2:force_original_aspect_ratio=decrease"
        tmp = dst_thumb_path + '.part.jpg'
        os.makedirs(os.path.dirname(dst_thumb_path), exist_ok=True)
        adb = _adb_prefix()
        cmd = (
            f"{adb} exec-out cat '{esc}' | "
            f"ffmpeg -v error -nostdin -y -i - -ss {ss} -an -map 0:v:0 -frames:v 1 -vf '{vf}' -vcodec mjpeg -q:v {quality} -f image2 '{tmp}'"
        )
        env = os.environ.copy()
        if ADB_BURST_MODE_ENABLED == '1':
            env['ADB_DELAYED_ACK'] = '1'
        p = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=12, env=env)
        if p.returncode == 0 and os.path.exists(tmp) and os.path.getsize(tmp) > 0 and _safe_atomic_replace(tmp, dst_thumb_path):
            if not _is_valid_jpeg(dst_thumb_path):
                try:
                    os.remove(dst_thumb_path)
                except Exception:
                    pass
                print(f"[preview] video thumb failed: not a jpeg (header) -> {dst_thumb_path}")
                return False
            return True
        else:
            msg = (p.stderr or '').strip()
            if msg:
                print(f"[preview] video stream ffmpeg failed rc={p.returncode}: {msg[:400]}" )
            # 若检测到部分内容/无效数据，直接生成视频占位图，避免重复重试
            lowered = (p.stderr or '').lower()
            if ('partial file' in lowered) or ('invalid data found when processing input' in lowered):
                if _write_video_placeholder_jpeg(dst_thumb_path, size=max_size):
                    print(f"[preview] video placeholder generated (stream-fallback): {dst_thumb_path}")
                    return True
            try:
                if os.path.exists(tmp):
                    os.remove(tmp)
            except Exception:
                pass
            return False
    except subprocess.TimeoutExpired:
        print("[preview] video stream ffmpeg timeout")
        return False
    except Exception as e:
        print(f"[preview] video stream exception: {e}")
        return False

def _ensure_video_thumb_via_temp_pull(remote_path: str, dst_thumb_path: str, max_size: int = 320, quality: int = 6, ss: float = 0.5) -> bool:
    """稳定路径：临时拉取到本地临时文件后，用本机ffmpeg抽帧并删除临时文件。"""
    try:
        rp = normalize_remote_path(remote_path)
        # 建立临时文件路径（不进入 previews，避免被误认为缓存）
        tmpdir = os.path.join(OUTPUT_DIR, 'tmp')
        os.makedirs(tmpdir, exist_ok=True)
        base = hashlib.sha1(rp.encode('utf-8', errors='ignore')).hexdigest()
        tmp_local = os.path.join(tmpdir, base + '.mp4')
        # 若意外存在同名目录，避让到唯一临时文件路径（不删除未知目录）
        if os.path.isdir(tmp_local):
            try:
                import tempfile
                fd, alt_path = tempfile.mkstemp(prefix=base + '_', suffix='.mp4', dir=tmpdir)
                try:
                    os.close(fd)
                except Exception:
                    pass
                tmp_local = alt_path
            except Exception:
                # 回退到带时间戳的路径
                tmp_local = os.path.join(tmpdir, f"{base}_{int(time.time()*1000)}.mp4")
        else:
            try:
                if os.path.exists(tmp_local):
                    os.remove(tmp_local)
            except Exception:
                pass

        # 拉取（允许 Burst）
        quoted_remote = rp.replace('"', '\\"')
        quoted_local = tmp_local.replace('"', '\\"')
        ok, _, _ = run_adb_command(f'adb pull "{quoted_remote}" "{quoted_local}"', timeout=60, enable_burst=True)
        if (not ok) or (not os.path.exists(tmp_local)) or (os.path.getsize(tmp_local) <= 0):
            try:
                if os.path.exists(tmp_local):
                    os.remove(tmp_local)
            except Exception:
                pass
            return False

        vf = f"scale={max_size}:-2:force_original_aspect_ratio=decrease"
        tmp_jpg = dst_thumb_path + '.part.jpg'
        cmd = [
            'ffmpeg', '-v', 'error', '-nostdin', '-y',
            '-ss', str(ss), '-i', tmp_local,
            '-an', '-map', '0:v:0',
            '-frames:v', '1', '-vf', vf,
            '-vcodec', 'mjpeg', '-q:v', str(quality),
            '-f', 'image2', tmp_jpg
        ]
        p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            os.remove(tmp_local)
        except Exception:
            pass
        if p.returncode == 0 and os.path.exists(tmp_jpg) and os.path.getsize(tmp_jpg) > 0 and _safe_atomic_replace(tmp_jpg, dst_thumb_path):
            if not _is_valid_jpeg(dst_thumb_path):
                try: os.remove(dst_thumb_path)
                except Exception: pass
                return False
            return True
        else:
            try:
                if os.path.exists(tmp_jpg): os.remove(tmp_jpg)
            except Exception:
                pass
            if p.stderr:
                print(f"[preview] video temp pull ffmpeg failed rc={p.returncode}: {p.stderr[:400]}")
                lowered = p.stderr.lower()
                if ('partial file' in lowered) or ('invalid data found when processing input' in lowered):
                    if _write_video_placeholder_jpeg(dst_thumb_path, size=max_size):
                        print(f"[preview] video placeholder generated (temp-pull-fallback): {dst_thumb_path}")
                        return True
            return False
    except Exception as e:
        print(f"[preview] video temp pull exception: {e}")
        try:
            if os.path.exists(tmp_local): os.remove(tmp_local)
        except Exception:
            pass
        return False

def _ensure_thumb_from_remote(remote_path: str, thumb_path: str, size: int) -> bool:
    """不缓存原文件，直接从设备流式生成缩略图（JPEG）。"""
    try:
        suf = PurePosixPath(remote_path).suffix.lower()
        if suf in PREVIEW_IMAGE_EXTS:
            data = _adb_cat_bytes(remote_path, timeout=20)
            if not data:
                return False
            return _ensure_thumbnail_from_bytes(data, thumb_path, max_size=size, quality=THUMB_IMG_QUALITY)
        else:
            # 暂不解析视频，直接生成占位图以极致提速
            return _write_video_placeholder_jpeg(thumb_path, size=max(128, size))
    except Exception:
        return False

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
    """缩略图缓存路径（统一jpg扩展，按设备分目录）。

    注意：为避免 /sdcard 与 /storage/emulated/0 等别名导致同一文件
    生成不同哈希，这里先对路径做 normalize。
    """
    try:
        norm_remote = normalize_remote_path(remote_path)
    except Exception:
        norm_remote = remote_path
    h = hashlib.sha1(norm_remote.encode('utf-8', errors='ignore')).hexdigest()
    dev = _get_current_device_label()
    return os.path.join(THUMB_DIR, dev, f"{h}_{size}.jpg")

def _thumb_any_path(remote_path: str, size: int = 512) -> str | None:
    """在所有设备子目录中寻找对应缩略图。

    同样对路径进行标准化，以保证别名一致性。
    """
    try:
        norm_remote = normalize_remote_path(remote_path)
        h = hashlib.sha1(norm_remote.encode('utf-8', errors='ignore')).hexdigest()
        target = f"{h}_{size}.jpg"
        # 先查当前设备label
        p = _thumb_local_path(remote_path, size)
        if os.path.exists(p) and os.path.getsize(p) > 0:
            return p
        # 遍历子目录
        for root, dirs, files in os.walk(THUMB_DIR):
            if target in files:
                fp = os.path.join(root, target)
                if os.path.getsize(fp) > 0:
                    return fp
        return None
    except Exception:
        return None

def _safe_atomic_replace(tmp_path: str, final_path: str):
    try:
        os.makedirs(os.path.dirname(final_path), exist_ok=True)
        os.replace(tmp_path, final_path)
        return True
    except Exception:
        try:
            if os.path.exists(tmp_path):
                os.remove(tmp_path)
        except Exception:
            pass
        return False

def _write_placeholder_jpeg(dst_thumb_path: str, size: int = 192) -> bool:
    """生成图片类占位缩略图：使用资源图 video-bac.jpeg。"""
    # 直接使用资源图，统一风格
    ok = _write_placeholder_from_asset(dst_thumb_path, size=size, asset_name='video-bac.jpeg')
    if ok:
        return True
    # 回退到简易绘制（极少数找不到资源时）
    try:
        from PIL import Image, ImageDraw
        s = max(64, int(size))
        os.makedirs(os.path.dirname(dst_thumb_path), exist_ok=True)
        tmp = dst_thumb_path + '.tmp'
        img = Image.new('RGB', (s, s), color=(244, 246, 248))
        d = ImageDraw.Draw(img)
        d.rectangle([(int(s*0.2), int(s*0.2)), (int(s*0.8), int(s*0.8))], outline=(200,200,200), width=max(2, s//64))
        img.save(tmp, format='JPEG', quality=60, optimize=True)
        return _safe_atomic_replace(tmp, dst_thumb_path)
    except Exception:
        return False

def _write_video_placeholder_jpeg(dst_thumb_path: str, size: int = 192) -> bool:
    """生成视频类占位缩略图：使用资源图 video-bac.jpeg。"""
    ok = _write_placeholder_from_asset(dst_thumb_path, size=size, asset_name='video-bac.jpeg')
    if ok:
        return True
    # 回退（极少数情况）：简易播放图标
    try:
        from PIL import Image, ImageDraw
        os.makedirs(os.path.dirname(dst_thumb_path), exist_ok=True)
        tmp = dst_thumb_path + '.part.jpg'
        s = max(64, int(size))
        img = Image.new('RGB', (s, s), color=(30, 30, 30))
        d = ImageDraw.Draw(img)
        cx = cy = s // 2
        tri = [(cx - s*0.15, cy - s*0.22), (cx - s*0.15, cy + s*0.22), (cx + s*0.28, cy)]
        d.polygon(tri, fill=(255, 255, 255))
        img.save(tmp, format='JPEG', quality=60, optimize=True)
        return _safe_atomic_replace(tmp, dst_thumb_path)
    except Exception:
        return False

def _is_valid_jpeg(path: str) -> bool:
    try:
        with open(path, 'rb') as f:
            hdr = f.read(3)
        return hdr == b'\xff\xd8\xff'
    except Exception:
        return False

def _ensure_thumbnail(src_local_path: str, dst_thumb_path: str, max_size: int = 512, quality: int = 70) -> bool:
    """将本地原图生成压缩缩略图到 dst_thumb_path。返回是否成功。"""
    try:
        from PIL import Image
    except Exception:
        return False

    try:
        # 源文件必须存在、是常规文件且非空
        try:
            if (not os.path.exists(src_local_path)) or (not os.path.isfile(src_local_path)) or os.path.getsize(src_local_path) <= 0:
                return False
        except Exception:
            return False
        os.makedirs(os.path.dirname(dst_thumb_path), exist_ok=True)
        from PIL import ImageFile
        try:
            ImageFile.LOAD_TRUNCATED_IMAGES = True
        except Exception:
            pass
        with Image.open(src_local_path) as im:
            # 尝试 draft 降采样（对JPEG等有效），降低解码/缩放成本
            try:
                im.draft('RGB', (max_size * 2, max_size * 2))
            except Exception:
                pass
            try:
                # 修正EXIF方向，避免缩略图方向错误
                from PIL import ImageOps
                im = ImageOps.exif_transpose(im)
            except Exception:
                pass
            # 转换到RGB以统一输出jpg
            if im.mode not in ('RGB', 'L'):
                im = im.convert('RGB')
            # 使用较快的双线性重采样以提升速度
            try:
                Resampling = getattr(__import__('PIL.Image', fromlist=['Image']).Image, 'Resampling', None)
                resample = Resampling.BILINEAR if Resampling else 2  # 2 == Image.BILINEAR
            except Exception:
                resample = 2
            im.thumbnail((max_size, max_size), resample)
            tmp = dst_thumb_path + '.part.jpg'
            im.save(tmp, format='JPEG', quality=quality, optimize=True)
            _safe_atomic_replace(tmp, dst_thumb_path)
            if not _is_valid_jpeg(dst_thumb_path):
                try:
                    os.remove(dst_thumb_path)
                except Exception:
                    pass
                return False
        try:
            sz = os.path.getsize(dst_thumb_path)
            print(f"[preview] thumb generated: {dst_thumb_path} ({sz/1024:.1f} KB) | thread={threading.current_thread().name}")
        except Exception:
            pass
        return True
    except Exception as e:
        # Pillow失败时，优先尝试ffmpeg对图像进行一次转码/缩放（不限格式）
        try:
            import shutil, subprocess
            # 若源不是常规文件，直接放弃（ffmpeg 也无法处理目录）
            try:
                if (not os.path.exists(src_local_path)) or (not os.path.isfile(src_local_path)):
                    return False
            except Exception:
                return False
            if shutil.which('ffmpeg'):
                os.makedirs(os.path.dirname(dst_thumb_path), exist_ok=True)
                vf = f"scale=if(gt(iw,ih),{max_size},-2):if(gt(iw,ih),-2,{max_size})"
                tmp = dst_thumb_path + '.part.jpg'
                cmd = ['ffmpeg','-v','error','-y','-i',src_local_path,
                       '-frames:v','1','-vf',vf,'-q:v','6',
                       '-f','image2','-vcodec','mjpeg', tmp]
                subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
                if os.path.exists(tmp) and os.path.getsize(tmp) > 0 and _safe_atomic_replace(tmp, dst_thumb_path):
                    if not _is_valid_jpeg(dst_thumb_path):
                        try:
                            os.remove(dst_thumb_path)
                        except Exception:
                            pass
                        return False
                    print(f"[preview] ffmpeg(image) thumb generated: {dst_thumb_path}")
                    return True
        except Exception:
            pass
        # 生成占位缩略图，避免重复失败和日志噪音
        if _write_placeholder_jpeg(dst_thumb_path, size=max_size):
            print(f"[preview] thumb placeholder generated: {dst_thumb_path}")
            return True
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
    # 若源不是常规文件（可能被误建为目录），直接生成视频占位图避免ffmpeg报错
    try:
        if (not os.path.exists(src_local_path)) or (not os.path.isfile(src_local_path)):
            return _write_video_placeholder_jpeg(dst_thumb_path, size=max_size)
    except Exception:
        return False
    if not shutil.which('ffmpeg'):
        # 无ffmpeg时，使用统一的视频占位图样式（居中播放图标）
        return _write_video_placeholder_jpeg(dst_thumb_path, size=max_size)
    try:
        os.makedirs(os.path.dirname(dst_thumb_path), exist_ok=True)
        vf = f"scale={max_size}:-2:force_original_aspect_ratio=decrease"
        tmp = dst_thumb_path + '.part.jpg'
        # 单一稳定方案：CPU 解码 + 输入后定位 + 明确输出 JPEG
        cmd = [
            'ffmpeg', '-v', 'error', '-nostdin', '-y',
            '-i', src_local_path, '-ss', '0.5',
            '-an', '-map', '0:v:0',
            '-frames:v', '1', '-vf', vf,
            '-vcodec', 'mjpeg', '-q:v', str(quality),
            '-f', 'image2', tmp
        ]
        try:
            proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=8)
        except subprocess.TimeoutExpired:
            print(f"[preview] video thumb failed: ffmpeg timeout (8s)")
            try:
                if os.path.exists(tmp):
                    os.remove(tmp)
            except Exception:
                pass
            # 生成视频占位图
            if _write_video_placeholder_jpeg(dst_thumb_path, size=max_size):
                print(f"[preview] video placeholder generated (timeout): {dst_thumb_path}")
                return True
            return False
        if proc.returncode == 0 and os.path.exists(tmp) and os.path.getsize(tmp) > 0 and _safe_atomic_replace(tmp, dst_thumb_path):
            if not _is_valid_jpeg(dst_thumb_path):
                try:
                    os.remove(dst_thumb_path)
                except Exception:
                    pass
                print(f"[preview] video thumb failed: not a jpeg (header) -> {dst_thumb_path}")
                return False
            try:
                sz = os.path.getsize(dst_thumb_path)
                print(f"[preview] video thumb generated: {dst_thumb_path} ({sz/1024:.1f} KB) | mode=sw-once | thread={threading.current_thread().name}")
            except Exception:
                pass
            return True
        # 失败详细日志
        err = (proc.stderr or '').strip()
        msg = err if len(err) <= 400 else err[:400] + '…'
        print(f"[preview] video thumb failed rc={proc.returncode}: {msg}")
        try:
            if os.path.exists(tmp):
                os.remove(tmp)
        except Exception:
            pass
        # 失败：生成视频占位图（轻量）
        if _write_video_placeholder_jpeg(dst_thumb_path, size=max_size):
            print(f"[preview] video placeholder generated: {dst_thumb_path}")
            return True
        print(f"[preview] video thumb generation failed for {src_local_path}")
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
            # 统一远程路径，避免别名导致 pull 失败或缓存错配
            remote_path = normalize_remote_path(remote_path)
            quoted_remote = remote_path.replace('"', '\\"')
            quoted_local = local_path.replace('"', '\\"')
            os.makedirs(os.path.dirname(local_path), exist_ok=True)
            ok, _, _ = run_adb_command(f'adb pull "{quoted_remote}" "{quoted_local}"', timeout=60, enable_burst=True)
            # 校验文件存在且非空
            if (not ok) or (not os.path.exists(local_path)) or (os.path.getsize(local_path) <= 0):
                try:
                    if os.path.exists(local_path):
                        os.remove(local_path)
                except Exception:
                    pass
                return False
        # 生成更小的缩略图（320px, 质量 55），视频提取首帧
        need = (not os.path.exists(thumb_path)) or (os.path.getmtime(thumb_path) < os.path.getmtime(local_path))
        if need:
            ext = PurePosixPath(remote_path).suffix.lower()
            if ext in PREVIEW_IMAGE_EXTS:
                # 先走 Pillow，失败则回退到 ffmpeg 提帧（部分 JPEG/WEBP 在 Pillow 下可能异常）
                ok = _ensure_thumbnail(local_path, thumb_path, max_size=THUMB_TARGET_PX, quality=THUMB_IMG_QUALITY)
                if not ok:
                    ok = _ensure_video_thumbnail(local_path, thumb_path, max_size=THUMB_TARGET_PX, quality=THUMB_VIDEO_QV)
            elif ext in PREVIEW_VIDEO_EXTS:
                ok = _ensure_video_thumbnail(local_path, thumb_path, max_size=THUMB_TARGET_PX, quality=THUMB_VIDEO_QV)
            else:
                # 无扩展名：尝试内容探测
                ok = False
                try:
                    ok = _ensure_thumbnail(local_path, thumb_path, max_size=THUMB_TARGET_PX, quality=THUMB_IMG_QUALITY)
                    if not ok:
                        ok = _ensure_video_thumbnail(local_path, thumb_path, max_size=THUMB_TARGET_PX, quality=THUMB_VIDEO_QV)
                except Exception:
                    ok = False
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

    # 统一路径别名，确保缩略图/预览缓存一致
    remote_path = normalize_remote_path(remote_path)

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
        # 必须存在且非空
        if (not ok) or (not os.path.exists(local_path)) or (os.path.getsize(local_path) <= 0):
            try:
                if os.path.exists(local_path):
                    os.remove(local_path)
            except Exception:
                pass
            return jsonify({'success': False, 'error': '预览拉取失败'}), 500

    # 生成缩略图（如必要）并返回缩略图
    try:
        need_generate = (not os.path.exists(thumb_path)) or (os.path.getmtime(thumb_path) < os.path.getmtime(local_path))
    except Exception:
        need_generate = True

    if need_generate:
        if ext in PREVIEW_IMAGE_EXTS:
            ok = _ensure_thumbnail(local_path, thumb_path, max_size=THUMB_TARGET_PX, quality=THUMB_IMG_QUALITY)
        else:
            ok = _ensure_video_thumbnail(local_path, thumb_path, max_size=THUMB_TARGET_PX, quality=THUMB_VIDEO_QV)
        if not ok:
            # 若缩略图生成失败，尝试占位图已在函数内处理；此处只在不存在缩略图时兜底
            if not os.path.exists(thumb_path):
                return jsonify({'success': False, 'error': '缩略图生成失败'}), 500
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
    """返回缩略图；若不存在则尝试即时生成一次。

    之前策略：仅返回已存在文件，不存在直接404，交给前端批量生成。
    实测在部分环境下会出现前端批量请求未触发或延迟较大，导致大量404、首屏体验差。
    新策略：当未命中缓存时，后端尝试同步生成一次缩略图；若生成成功立即返回，
    失败则维持404，交由前端的重试与批处理逻辑兜底。
    """
    remote_path = request.args.get('path', '').strip()
    if not remote_path:
        return jsonify({'success': False, 'error': '缺少路径参数'}), 400
    # 标准化路径，避免别名导致哈希不一致
    remote_path = normalize_remote_path(remote_path)
    # 允许自定义目标尺寸（64~512），默认使用全局配置
    try:
        req_size = int(request.args.get('size', str(THUMB_TARGET_PX)))
    except Exception:
        req_size = THUMB_TARGET_PX
    req_size = max(64, min(512, req_size))
    thumb_path = _thumb_local_path(remote_path, size=req_size)
    # 若不存在，尝试其它设备目录；若存在但为0字节，也视为无效
    def _size_ok(p: str) -> bool:
        try:
            return os.path.exists(p) and os.path.getsize(p) > 0
        except Exception:
            return False

    if not _size_ok(thumb_path):
        anyp = _thumb_any_path(remote_path, size=256)
        if anyp and _size_ok(anyp):
            thumb_path = anyp
        else:
            # 若有空文件，先清掉再生成
            try:
                if os.path.exists(thumb_path) and os.path.getsize(thumb_path) == 0:
                    os.remove(thumb_path)
            except Exception:
                pass
            # 尝试即时生成一次（同步），不落地原文件
            try:
                ok = _ensure_thumb_from_remote(remote_path, thumb_path, req_size)
                if not _size_ok(thumb_path):
                    # 兜底：写入占位图并排队后台生成，避免首屏大量404
                    suf = PurePosixPath(remote_path).suffix.lower()
                    if suf in PREVIEW_VIDEO_EXTS:
                        _write_video_placeholder_jpeg(thumb_path, size=max(64, req_size))
                    else:
                        _write_placeholder_jpeg(thumb_path, size=max(64, req_size))
                    try:
                        if remote_path not in thumb_inflight:
                            thumb_inflight.add(remote_path)
                            def _bg():
                                try:
                                    _ensure_thumb_from_remote(remote_path, thumb_path, req_size)
                                finally:
                                    try: thumb_inflight.discard(remote_path)
                                    except Exception: pass
                            thumb_executor.submit(_bg)
                    except Exception:
                        pass
            except Exception:
                # 同上兜底
                try:
                    suf = PurePosixPath(remote_path).suffix.lower()
                    if suf in PREVIEW_VIDEO_EXTS:
                        _write_video_placeholder_jpeg(thumb_path, size=max(64, req_size))
                    else:
                        _write_placeholder_jpeg(thumb_path, size=max(64, req_size))
                except Exception:
                    pass
    print(f"[thumb-serve] {thumb_path} | thread={threading.current_thread().name}")
    resp = send_file(thumb_path, mimetype='image/jpeg', conditional=True)
    resp.headers['Cache-Control'] = 'public, max-age=31536000, immutable'
    return resp

@app.route('/api/thumb_exists')
def photo_thumb_exists():
    path = request.args.get('path', '').strip()
    if not path:
        return jsonify({'success': False, 'error': '缺少路径参数'}), 400
    # 统一路径
    path = normalize_remote_path(path)
    thumb_path = _thumb_local_path(path, size=256)
    return jsonify({'success': True, 'exists': os.path.exists(thumb_path)})

@app.route('/api/thumb_status', methods=['POST'])
def photo_thumb_status():
    data = request.get_json(silent=True) or {}
    paths = data.get('paths') or []
    try:
        size = int(data.get('size', THUMB_TARGET_PX))
    except Exception:
        size = THUMB_TARGET_PX
    size = max(64, min(512, size))
    res = []
    for p in paths:
        try:
            p = normalize_remote_path(p)
            ok = os.path.exists(_thumb_local_path(p, size=size))
            if not ok:
                ok = _thumb_any_path(p, size=size) is not None
            res.append(ok)
        except Exception:
            res.append(False)
    return jsonify({'success': True, 'ready': res})

@app.route('/api/thumb_batch_generate', methods=['POST'])
def photo_thumb_batch_generate():
    data = request.get_json(silent=True) or {}
    paths = data.get('paths') or []
    batch_size = int(data.get('batch_size') or 30)
    size = int(data.get('size') or 256)
    force = bool(data.get('force') or False)
    submitted = 0
    for p in paths[:batch_size]:
        try:
            p = normalize_remote_path(p)
            thumb_path = _thumb_local_path(p, size=size)
            if os.path.exists(thumb_path):
                try:
                    if os.path.getsize(thumb_path) > 0 and not force:
                        continue
                    if force:
                        os.remove(thumb_path)
                except Exception:
                    if not force:
                        continue
            if p in thumb_inflight:
                continue
            thumb_inflight.add(p)
            def _task(remote=p, tp=thumb_path):
                try:
                    _ensure_thumb_from_remote(remote, tp, size)
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

@app.route('/api/thumb_batch_fetch', methods=['POST'])
def photo_thumb_batch_fetch():
    """批量生成并返回缩略图（base64），用于减少前端小图的并发HTTP请求。

    请求: { paths: [..], size?: int, limit?: int }
    响应: { success: true, items: [{ path, b64? , error? }] }
    """
    try:
        data = request.get_json(silent=True) or {}
        paths_raw = data.get('paths') or []
        # 确保paths是字符串列表,处理可能传入的对象数组
        normalized_paths = []
        for p in paths_raw:
            if isinstance(p, dict):
                # 如果是字典对象,提取path字段
                path_str = p.get('path', '')
            else:
                # 否则直接转为字符串
                path_str = str(p) if p else ''
            if path_str:
                normalized_paths.append(path_str)
        # 去重保持顺序
        paths = list(dict.fromkeys(normalized_paths))
        try:
            size = int(data.get('size', THUMB_TARGET_PX))
        except Exception:
            size = THUMB_TARGET_PX
        try:
            limit = int(data.get('limit', 64))
        except Exception:
            limit = 64
        size = max(64, min(512, size))
        limit = max(1, min(512, limit))

        items = []
        for p in paths[:limit]:
            try:
                rp = normalize_remote_path(str(p))
                tp = _thumb_local_path(rp, size=size)
                # 若无现成则尝试即时生成（含占位退化）
                if (not os.path.exists(tp)) or (os.path.getsize(tp) <= 0):
                    ok = False
                    try:
                        ok = _ensure_thumb_from_remote(rp, tp, size)
                    except Exception:
                        ok = False
                    if not ok or (not os.path.exists(tp)) or os.path.getsize(tp) <= 0:
                        suf = PurePosixPath(rp).suffix.lower()
                        if suf in PREVIEW_VIDEO_EXTS:
                            _write_video_placeholder_jpeg(tp, size=max(64, size))
                        else:
                            _write_placeholder_jpeg(tp, size=max(64, size))
                # 读入并返回base64
                with open(tp, 'rb') as f:
                    b64 = base64.b64encode(f.read()).decode('ascii')
                items.append({'path': rp, 'b64': b64})
            except Exception as e:
                items.append({'path': str(p), 'error': str(e)})

        return jsonify({'success': True, 'items': items, 'count': len(items)})
    except Exception as e:
        return jsonify({'success': False, 'error': str(e)}), 500

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

    # 稳定分页：直接在设备侧按修改时间倒序分页，再在服务端过滤支持的扩展
    # 为避免设备端 grep/egrep 兼容性问题，这里一次拉一个较大窗口，再在本地过滤并截断到 limit
    try:
        # 注意：offset 基于“媒体文件数”（已过滤），而 ls 输出包含所有文件。
        # 为避免偏移错位，这里从头取到 (offset+limit)*系数，再在本地过滤并裁剪真正的媒体分页。
        factor = 6  # 经验系数：非媒体/媒体混杂时尽量足够
        end = max(1, (offset + limit) * factor)
        quoted = album.replace("'", "'\\''")
        cmd = (
            "adb shell 'cd " + quoted +
            f" 2>/dev/null && ls -1t 2>/dev/null | sed -n \"1,{end}p\"'"
        )
        ok, out, _ = run_adb_command(cmd, timeout=8)
        names = [l.strip() for l in (out.splitlines() if (ok and out) else []) if l.strip()]
        media = []
        for nm in names:
            p = album.rstrip('/') + '/' + nm
            suf = PurePosixPath(p).suffix.lower()
            if (suf in (PREVIEW_IMAGE_EXTS | PREVIEW_VIDEO_EXTS)) or (suf == ''):
                media.append((p, nm))
        # 取媒体分页
        page_items = media[offset: offset + limit]
        photos = [{
            'path': p,
            'name': nm,
            'size': 0,
            'size_mb': 0.0,
            'mtime': _parse_timestamp_from_name(nm) or 0,
            'date': ''
        } for (p, nm) in page_items]
        return jsonify({'success': True, 'photos': photos, 'total': offset + len(photos)})
    except Exception as e:
        return jsonify({'success': False, 'error': str(e)}), 500

def _list_album_media(album: str):
    try:
        entries = _album_entries_with_times(album)
        # 排序：时间倒序
        entries.sort(key=lambda x: (x[2] or 0), reverse=True)
        return [p for p, _, _ in entries]
    except Exception:
        return []

def _album_entries_with_times(album: str):
    """使用 adb shell + toybox stat 尝试获取目录内每个文件的创建时间(若有)或修改时间，返回列表[(path, name, ts)].

    - 优先使用 birth time (%W). 若为0或不支持，则回退到 mtime (%Y)。
    - 在出现错误时回退为仅列名 + 文件名时间解析。
    """
    entries = []
    try:
        # 在设备端进入目录并遍历；注意转义单引号
        quoted = album.replace("'", "'\\''")
        cmd = (
            "adb shell 'cd " + quoted +
            " 2>/dev/null && for f in *; do stat -c \"%W|%Y|%n\" \"$f\" 2>/dev/null; done'"
        )
        ok, out, _ = run_adb_command(cmd, timeout=12)
        if ok and out:
            for line in out.splitlines():
                line = line.strip()
                if not line or '|' not in line:
                    continue
                parts = line.split('|', 2)
                if len(parts) != 3:
                    continue
                try:
                    w = int(parts[0]) if parts[0].isdigit() else 0
                except Exception:
                    w = 0
                try:
                    y = int(parts[1]) if parts[1].isdigit() else 0
                except Exception:
                    y = 0
                nm = parts[2]
                if not nm:
                    continue
                ts = w if w > 0 else y if y > 0 else None
                p = album.rstrip('/') + '/' + nm
                suf = PurePosixPath(p).suffix.lower()
                if (suf in (PREVIEW_IMAGE_EXTS | PREVIEW_VIDEO_EXTS)) or (suf == ''):
                    entries.append((p, nm, ts))
            if entries:
                return entries
        # 回退：仅列名
        ok2, out2, _ = run_adb_command(f"adb shell 'ls -1 " + quoted + " 2>/dev/null'", timeout=8)
        if ok2 and out2:
            names = [l.strip() for l in out2.splitlines() if l.strip()]
            for nm in names:
                p = album.rstrip('/') + '/' + nm
                suf = PurePosixPath(p).suffix.lower()
                if (suf in (PREVIEW_IMAGE_EXTS | PREVIEW_VIDEO_EXTS)) or (suf == ''):
                    ts = _parse_timestamp_from_name(nm)
                    entries.append((p, nm, ts))
        return entries
    except Exception:
        return entries

@app.route('/api/selection_expand', methods=['POST'])
def selection_expand():
    data = request.get_json(silent=True) or {}
    albums = data.get('albums') or []
    exclude = data.get('exclude') or {}
    singles = data.get('singles') or []
    result = []
    seen = set()
    try:
        # 展开相册
        for alb in albums:
            files = _list_album_media(alb)
            ex = set(exclude.get(alb, []))
            for p in files:
                if p in ex: continue
                if p in seen: continue
                seen.add(p)
                result.append({
                    'path': p,
                    'name': os.path.basename(p),
                    'size': 0,
                    'size_mb': 0.0,
                    'mtime': 0,
                    'date': ''
                })
        # 追加单独选中的
        for p in singles:
            if p in seen: continue
            seen.add(p)
            result.append({
                'path': p,
                'name': os.path.basename(p),
                'size': 0,
                'size_mb': 0.0,
                'mtime': 0,
                'date': ''
            })
        return jsonify({'success': True, 'photos': result, 'total': len(result)})
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


def trigger_usb_speed_probe(force: bool = False):
    """后台异步触发USB测速，避免阻塞主流程。

    每个设备在程序运行期间只会测速一次（除非使用 force=True）。
    如果有持久化的测速结果，直接使用缓存。
    """
    global usb_speed_probe_running, usb_speed_probe_device, usb_speed_probe_ts, usb_speed_tested_devices

    if not selected_device:
        return

    # 优先检查内存集合，避免不必要的磁盘IO
    if not force and selected_device in usb_speed_tested_devices:
        # 已跳过或已测速，直接返回，不打印日志（避免刷屏）
        return

    # 再检查持久化缓存
    if not force:
        cached_speed = _get_cached_usb_speed(selected_device)
        if cached_speed:
            # 有缓存，直接使用
            usb_speed_cache.update({
                'device': selected_device,
                'data': cached_speed,
                'ts': time.time()
            })
            usb_speed_tested_devices.add(selected_device)
            print(f"✅ 使用缓存的USB测速结果: {selected_device} -> {cached_speed.get('measured_mbps', 0):.1f} MB/s")
            return

    # 防抖 + 并发保护：同一设备15秒内只启动一次
    with usb_speed_probe_lock:
        from time import time as _now
        now = _now()
        if (now - usb_speed_probe_ts) < 15:
            # 防抖中，不打印日志
            return
        if usb_speed_probe_running and usb_speed_probe_device == selected_device:
            # 测速进行中，不打印日志
            return
        usb_speed_probe_running = True
        usb_speed_probe_device = selected_device
        usb_speed_probe_ts = now

    def _runner():
        global usb_speed_probe_running, usb_speed_probe_device, usb_speed_tested_devices
        # 保存当前设备序列号，避免测速过程中设备变化
        current_device = selected_device
        print(f"🔍 [DEBUG] 开始测速: current_device={current_device}")
        try:
            result = get_usb_speed(force=force)
            print(f"🔍 [DEBUG] 测速结果: result={result}")
            # 只有测速成功且有有效速度时才标记为已测速
            if result and result.get('success') and not result.get('probing'):
                measured_mbps = result.get('measured_mbps', 0)
                print(f"🔍 [DEBUG] measured_mbps={measured_mbps}, type={type(measured_mbps)}")
                # 如果测速失败（速度为0），不标记为已完成，允许重试
                if measured_mbps and measured_mbps > 0:
                    usb_speed_tested_devices.add(current_device)
                    print(f"✅ 设备 {current_device} 测速完成并已标记 ({measured_mbps:.1f} MB/s)")
                    # 保存到持久化文件
                    _save_usb_speed_cache(current_device, result)
                else:
                    print(f"⚠️ 设备 {current_device} 测速失败 (速度: {measured_mbps} MB/s)，允许重试")
            else:
                print(f"🔍 [DEBUG] 测速结果不符合条件: success={result.get('success')}, probing={result.get('probing')}")
        except Exception as e:
            print(f"⚠️ USB测速任务失败: {e}")
            import traceback
            traceback.print_exc()
        finally:
            with usb_speed_probe_lock:
                usb_speed_probe_running = False
                usb_speed_probe_device = None
            print(f"🔍 [DEBUG] 测速线程结束")

    try:
        t = threading.Thread(target=_runner, daemon=True)
        t.start()
        print(f"🚀 已启动USB测速后台线程 (设备: {selected_device})")
    except Exception as e:
        print(f"⚠️ 启动USB测速线程失败: {e}")
        with usb_speed_probe_lock:
            usb_speed_probe_running = False
            usb_speed_probe_device = None

def get_usb_speed(force: bool = False):
    """获取USB速率信息（字典），供内部调用与API复用。"""
    global usb_speed_probe_running
    connected, _ = check_adb_connection()
    if not connected:
        return {'success': False, 'error': '设备未连接'}

    # 缓存命中
    if not force:
        try:
            if usb_speed_cache.get('device') == selected_device and usb_speed_cache.get('data'):
                d = dict(usb_speed_cache['data'])
                d['cached'] = True
                d['success'] = True
                d.pop('probing', None)
                return d
        except Exception:
            pass
        if usb_speed_probe_running:
            return {
                'success': True,
                'probing': True,
                'label': 'USB 测速中',
                'measured_mbps': 0,
                'gbps': 0
            }

    # 基准测试（独占ADB）：单轮测速
    bench = None
    try:
        bench = _benchmark_usb_speed()
        if bench and bench.get('measured_mbps', 0) > 0:
            bench.update({'success': True, 'source': 'bench', 'probing': False})
            usb_speed_cache.update({'device': selected_device, 'data': bench, 'ts': time.time()})
            return bench
    except Exception as e:
        print(f"⚠️ 基准测速异常: {e}")
        import traceback
        traceback.print_exc()

    # 回退：如基准测试失败，再尝试sysfs/dumpsys 以给出基本信息
    try:
        cmd1 = "adb shell 'for f in /sys/class/udc/*/current_speed; do cat \"$f\"; done 2>/dev/null'"
        ok, out, err = run_adb_command(cmd1, timeout=3)
        speed_line = ''
        if ok and out.strip():
            speed_line = out.strip().split('\n')[0]
        else:
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
            m.update({'success': True, 'source': 'sysfs', 'raw': out.strip() if isinstance(out, str) else '', 'probing': False})
            usb_speed_cache.update({'device': selected_device, 'data': m, 'ts': time.time()})
            return m
    except Exception:
        pass

    try:
        ok, out, err = run_adb_command("adb shell dumpsys usb", timeout=4)
        if ok and out:
            raw = out
            line = ''
            for l in raw.splitlines():
                ls = l.strip()
                if 'speed' in ls.lower():
                    line = ls
                    break
            if line:
                m = _map_usb_speed(line)
                m.update({'success': True, 'source': 'dumpsys', 'raw': line, 'probing': False})
                usb_speed_cache.update({'device': selected_device, 'data': m, 'ts': time.time()})
                return m
    except Exception:
        pass

    m = _map_usb_speed('')
    m.update({'success': True, 'source': 'none', 'raw': '', 'probing': False})
    usb_speed_cache.update({'device': selected_device, 'data': m, 'ts': time.time()})
    return m

@app.route('/api/usb/speed')
def api_usb_speed():
    """API封装：返回USB速率信息，包含测速完成状态"""
    data = get_usb_speed()

    # 添加测速完成标记
    # 如果有有效的测速结果（缓存或刚测完），标记为已完成
    if selected_device:
        # 检查是否在已测速集合中
        in_tested_set = selected_device in usb_speed_tested_devices

        # 或者检查是否有有效的缓存数据
        has_valid_cache = (
            data.get('success') and
            not data.get('probing') and
            data.get('measured_mbps', 0) > 0
        )

        # 如果有有效缓存但不在集合中，添加到集合
        if has_valid_cache and not in_tested_set:
            usb_speed_tested_devices.add(selected_device)
            print(f"✅ 从缓存恢复设备 {selected_device} 的测速状态")

        data['speed_test_done'] = in_tested_set or has_valid_cache
    else:
        data['speed_test_done'] = False

    # 检测测速失败（速度为0或无效）
    measured_mbps = data.get('measured_mbps', 0)
    if data.get('success') and not data.get('probing') and (not measured_mbps or measured_mbps <= 0):
        data['test_failed'] = True
    else:
        data['test_failed'] = False

    if not data.get('success', True) and data.get('error'):
        return jsonify(data), 400
    return jsonify(data)


@app.route('/api/usb/retry_speed_test', methods=['POST'])
def retry_speed_test():
    """重新进行USB速度测试"""
    global usb_speed_tested_devices
    if not selected_device:
        return jsonify({'success': False, 'error': '设备未连接'}), 400

    try:
        # 清除已测速标记和缓存
        usb_speed_tested_devices.discard(selected_device)
        usb_speed_cache['device'] = None
        usb_speed_cache['data'] = None
        usb_speed_cache['ts'] = 0

        # 触发新的测速
        trigger_usb_speed_probe(force=True)

        return jsonify({
            'success': True,
            'message': 'USB速度测试已重新启动'
        })
    except Exception as e:
        return jsonify({'success': False, 'error': str(e)}), 500


@app.route('/api/usb/skip_speed_test', methods=['POST'])
def skip_speed_test():
    """跳过USB速度测试，直接标记为已完成"""
    global usb_speed_tested_devices, usb_speed_cache
    if not selected_device:
        return jsonify({'success': False, 'error': '设备未连接'}), 400

    try:
        # 标记为已测速（即使没有实际测速）
        usb_speed_tested_devices.add(selected_device)

        # 创建虚拟测速结果，避免前端重复弹出对话框
        skip_speed_data = {
            'success': True,
            'label': 'USB 已跳过测速',
            'measured_mbps': 40.0,  # 给一个假定的速度，避免前端检查失败
            'gbps': 0.32,
            'skipped': True,
            'source': 'skipped',
            'probing': False
        }

        # 保存到内存缓存
        usb_speed_cache.update({
            'device': selected_device,
            'data': skip_speed_data,
            'ts': time.time()
        })

        # 保存到持久化文件
        _save_usb_speed_cache(selected_device, skip_speed_data)

        print(f"⏭️ 用户跳过设备 {selected_device} 的USB速度测试")

        return jsonify({
            'success': True,
            'message': '已跳过USB速度测试'
        })
    except Exception as e:
        return jsonify({'success': False, 'error': str(e)}), 500


def _benchmark_usb_speed(size_mb: int = 64):
    """通过adb在/sdcard/Download生成临时文件并pull到本地，估算实际MB/s。
    优化：默认1轮、较小文件，减少抖动与设备负担；可通过环境变量 USB_BENCH_FILE_MB 与 USB_BENCH_ROUNDS 调整。
    """
    import time as _t

    try:
        # 允许通过环境变量覆盖
        env_mb = int(os.getenv('USB_BENCH_FILE_MB', '0'))
        if env_mb > 0:
            size_mb = env_mb
    except Exception:
        pass

    print(f"🚀 开始USB速度基准测试 (测试文件: {size_mb}MB)...")

    remote_path = f"/sdcard/Download/.usb_speed_test.bin"
    local_dir = OUTPUT_DIR
    os.makedirs(local_dir, exist_ok=True)
    local_path = os.path.join(local_dir, 'usb_speed_test.bin')

    # 生成/复用远程测试文件（持久化以加速下次测速）
    expected_bytes = int(size_mb) * 1024 * 1024
    try:
        ok_stat, out_stat, _ = run_adb_command(
            f"adb shell 'stat -c %s \"{remote_path}\" 2>/dev/null || echo 0'", timeout=5
        )
        current_size = int(out_stat.strip().split()[0]) if ok_stat and out_stat.strip().split()[0].isdigit() else 0
    except Exception:
        current_size = 0

    if current_size >= expected_bytes:
        print(f"📝 复用已存在的测试文件: {remote_path} ({current_size/1024/1024:.0f}MB)")
    else:
        print(f"📝 生成测试文件: {remote_path} (Burst模式: {ADB_BURST_MODE_ENABLED == '1'})")
        ok1, _, _ = run_adb_command(
            f"adb shell 'dd if=/dev/zero of=\"{remote_path}\" bs=1M count={size_mb} 2>/dev/null'",
            timeout=60,
        )
        if not ok1:
            print("❌ 无法生成测试文件，基准测试失败")
            return None

    # 轮次（默认1轮）：减少抖动
    try:
        test_rounds = int(os.getenv('USB_BENCH_ROUNDS', '1'))
    except Exception:
        test_rounds = 1
    speed_samples = []

    for i in range(test_rounds):
        if test_rounds > 1:
            print(f"⚡ 第 {i+1}/{test_rounds} 轮测试...")
        else:
            print("⚡ 测速中...")

        # 确保本地文件不存在
        if os.path.exists(local_path):
            os.remove(local_path)

        # 拉取并计时（强制启用Burst模式以获得最佳性能）
        start = _t.time()
        ok2, _, _ = run_adb_command(
            f"adb pull \"{remote_path}\" \"{local_path}\"", timeout=60, enable_burst=True
        )
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

    # 不再清理远程文件（持久化保留，加速下次测速）

    if not speed_samples:
        # 测速失败，返回失败结果
        print(f"❌ USB速度测试失败：无有效测速样本")
        return {
            'code': 'bench-unknown',
            'label': 'USB 未知 (估算)',
            'gbps': 0,
            'measured_mbps': 0,
            'avg_mbps': 0,
            'max_mbps': 0,
            'min_mbps': 0,
            'test_rounds': test_rounds,
            'test_size_mb': size_mb,
        }

    # 计算平均速度和最大速度
    avg_speed = sum(speed_samples) / len(speed_samples)
    max_speed = max(speed_samples)
    min_speed = min(speed_samples)

    if test_rounds > 1:
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
        'test_size_mb': size_mb,
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

    with transfer_status_lock:
        if transfer_status['is_running']:
            return jsonify({
                'success': False,
                'error': '传输正在进行中'
            }), 400
        transfer_status['is_running'] = True
        transfer_status['paused'] = False
        transfer_status['error'] = None
        transfer_status['current'] = 0
        transfer_status['total'] = 0
        transfer_status['failed'] = []
        transfer_status['current_file'] = ''
        transfer_status['bytes_done'] = 0
        transfer_status['bytes_total'] = 0
        transfer_status['start_ts'] = time.time()
        transfer_status['elapsed_sec'] = 0.0
        transfer_status['eta_sec'] = 0.0
        transfer_status['speed_mbps'] = 0.0
        transfer_status['speed_samples'] = []
        transfer_status['usb_info'] = {}
        transfer_status['completed_files'] = set()

    def reset_running_state():
        with transfer_status_lock:
            transfer_status['is_running'] = False
            transfer_status['paused'] = False
            transfer_status['current_file'] = ''
            transfer_status['eta_sec'] = 0.0

    progress = load_progress()
    if not progress:
        reset_running_state()
        return jsonify({
            'success': False,
            'error': '没有找到未完成的传输任务'
        }), 400

    pending_photos = progress.get('pending_photos', [])
    if not pending_photos:
        reset_running_state()
        return jsonify({
            'success': False,
            'error': '所有文件已传输完成'
        }), 400

    output_dir = progress.get('output_dir', OUTPUT_DIR)

    # 恢复已完成文件列表
    completed_set = progress.get('completed_files', set())
    if not isinstance(completed_set, set):
        completed_set = set(completed_set or [])
    with transfer_status_lock:
        transfer_status['completed_files'] = completed_set
        transfer_status['total'] = len(pending_photos) + len(completed_set)
        transfer_status['current'] = len(completed_set)
        transfer_status['output_dir'] = output_dir

    # 启动后台传输线程（resume=True）
    transfer_started = False
    try:
        thread = threading.Thread(target=transfer_photos_thread, args=(pending_photos, output_dir, True))
        thread.daemon = True
        thread.start()
        transfer_started = True

        return jsonify({
            'success': True,
            'message': f'继续传输 {len(pending_photos)} 个文件'
        })
    except Exception:
        if not transfer_started:
            reset_running_state()
        raise

@app.route('/api/clear_progress', methods=['POST'])
def api_clear_progress():
    """清除上次未完成的传输进度文件，用于用户选择忽略时。"""
    try:
        clear_progress()
        return jsonify({'success': True})
    except Exception as e:
        return jsonify({'success': False, 'error': str(e)}), 500

@app.route('/api/transfer', methods=['POST'])
def transfer():
    """开始传输照片"""
    global transfer_status

    with transfer_status_lock:
        if transfer_status['is_running']:
            return jsonify({
                'success': False,
                'error': '传输正在进行中'
            }), 400
        transfer_status['is_running'] = True
        transfer_status['paused'] = False
        transfer_status['error'] = None
        transfer_status['current'] = 0
        transfer_status['total'] = 0
        transfer_status['failed'] = []
        transfer_status['current_file'] = ''
        transfer_status['bytes_done'] = 0
        transfer_status['bytes_total'] = 0
        transfer_status['start_ts'] = time.time()
        transfer_status['elapsed_sec'] = 0.0
        transfer_status['eta_sec'] = 0.0
        transfer_status['speed_mbps'] = 0.0
        transfer_status['speed_samples'] = []
        transfer_status['usb_info'] = {}
        transfer_status['completed_files'] = set()

    def reset_running_state():
        with transfer_status_lock:
            transfer_status['is_running'] = False
            transfer_status['paused'] = False
            transfer_status['current_file'] = ''
            transfer_status['eta_sec'] = 0.0

    try:
        data = request.get_json()
    except Exception:
        reset_running_state()
        raise
    print(f"DEBUG: 接收到的数据: keys={list(data.keys()) if isinstance(data, dict) else type(data)}")

    photos = []
    output_dir = (data.get('output_dir') if isinstance(data, dict) else None) or OUTPUT_DIR

    # 新增：支持选择摘要，由服务端展开，避免前端分页压力
    sel = (data.get('selection') if isinstance(data, dict) else None) or None
    if sel:
        try:
            albums = sel.get('albums') or []
            exclude = sel.get('exclude') or {}
            singles = sel.get('singles') or []
            seen = set()
            # 展开相册
            for alb in albums:
                ex = set(exclude.get(alb, []))
                files = _list_album_media(alb) or []
                for p in files:
                    if p in ex: continue
                    if p in seen: continue
                    seen.add(p)
                    photos.append({
                        'path': p,
                        'name': os.path.basename(p),
                        'size': 0,
                        'mtime': 0,
                        'size_mb': 0.0,
                        'date': ''
                    })
            # 追加独立选择
            for p in singles:
                if p in seen: continue
                seen.add(p)
                photos.append({
                    'path': p,
                    'name': os.path.basename(p),
                    'size': 0,
                    'mtime': 0,
                    'size_mb': 0.0,
                    'date': ''
                })
            print(f"DEBUG: 服务端展开 selection 完成: {len(photos)} 项")
            # 基于相册聚合大小快速初始化总字节，避免大规模逐个stat阻塞
            try:
                approx_total = 0
                albums_map = scan_status.get('albums', {}) if isinstance(scan_status.get('albums', {}), dict) else {}
                for alb in albums:
                    info = albums_map.get(alb)
                    if info and isinstance(info.get('total_size', 0), (int, float)):
                        approx_total += int(info.get('total_size', 0))
                with transfer_status_lock:
                    if approx_total > 0:
                        transfer_status['bytes_total'] = approx_total
            except Exception:
                pass
        except Exception as e:
            print(f"ERROR: 展开 selection 失败: {e}")
            reset_running_state()
            return jsonify({'success': False, 'error': f'展开选择失败: {e}'}), 400
    else:
        photos = (data.get('photos') if isinstance(data, dict) else None) or []

    print(f"DEBUG: 照片数量: {len(photos)}")
    print(f"DEBUG: 输出目录: {output_dir}")
    if photos:
        try:
            print(f"DEBUG: 第一张照片示例: {photos[0]}")
        except Exception:
            pass

    if not photos:
        reset_running_state()
        return jsonify({
            'success': False,
            'error': '没有选择照片'
        }), 400

    # 为了尽快返回API响应，将USB速度检测与大小预估改为异步，不阻塞启动
    def _async_prefetch():
        # USB 速度检测
        try:
            print("🔍 正在检测USB设备速度...")
            usb_info = get_usb_speed()
            if usb_info and usb_info.get('success'):
                with transfer_status_lock:
                    transfer_status['usb_info'] = {
                        'label': usb_info.get('label', 'USB 未知'),
                        'measured_mbps': usb_info.get('measured_mbps', 0),
                        'gbps': usb_info.get('gbps', 0)
                    }
                print(f"📡 USB设备检测: {usb_info.get('label')} - {usb_info.get('measured_mbps', 0):.1f} MB/s")
            else:
                with transfer_status_lock:
                    transfer_status['usb_info'] = {'label': 'USB 未知', 'measured_mbps': 0, 'gbps': 0}
        except Exception as e:
            print(f"⚠️ USB速度检测失败: {e}")
            with transfer_status_lock:
                transfer_status['usb_info'] = {'label': 'USB 未知', 'measured_mbps': 0, 'gbps': 0}

        # 大小预估（后台补全，供ETA使用）
        try:
            def _stat_size_one(pth: str) -> int:
                try:
                    ok, out, _ = run_adb_command("adb shell 'stat -c %s " + pth.replace("'","'\\''") + "'", timeout=4)
                    if ok and out.strip().isdigit():
                        return int(out.strip())
                except Exception:
                    pass
                return 0

            from concurrent.futures import ThreadPoolExecutor, as_completed
            max_workers = min(16, max(4, (os.cpu_count() or 8)))
            total_bytes = 0
            with ThreadPoolExecutor(max_workers=max_workers) as ex:
                futs = {}
                for ph in photos:
                    if not ph.get('name'):
                        ph['name'] = os.path.basename(ph.get('path',''))
                    if int(ph.get('size', 0)) > 0:
                        total_bytes += int(ph.get('size', 0))
                        continue
                    futs[ex.submit(_stat_size_one, ph.get('path',''))] = ph
                for f in as_completed(futs):
                    sz = f.result() or 0
                    ph = futs[f]
                    ph['size'] = sz
                    total_bytes += sz
                    # 分段更新 bytes_total，提高前端ETA及时性
                    if total_bytes % (50*1024*1024) < 1024:  # 每累计约50MB就刷新一次
                        with transfer_status_lock:
                            transfer_status['bytes_total'] = total_bytes
            with transfer_status_lock:
                transfer_status['bytes_total'] = total_bytes
        except Exception as e:
            print(f"⚠️ 预估大小失败: {e}")

    # 依据设备ID/机型建立批次文件夹: <base>/<device_label>/<YYYYMMDD_HHMMSS>
    try:
        device_label = _get_current_device_label() if 'selected_device' in globals() else 'unknown_device'
    except Exception:
        device_label = 'unknown_device'
    try:
        ts_folder = datetime.now().strftime('%Y%m%d_%H%M%S')
    except Exception:
        ts_folder = 'batch'
    final_output_dir = os.path.join(output_dir, device_label, ts_folder)
    os.makedirs(final_output_dir, exist_ok=True)

    # 记录当前USB设备到 devices 表，便于历史记录显示友好名称
    try:
        dev_name = _get_current_device_name()
        upsert_device_info(device_label, dev_name)
    except Exception:
        pass

    # 立即启动后台传输线程
    transfer_started = False
    try:
        t1 = threading.Thread(target=transfer_photos_thread, args=(photos, final_output_dir, False), daemon=True)
        t1.start()
        transfer_started = True
        # 异步启动USB速度和大小预估
        t2 = threading.Thread(target=_async_prefetch, daemon=True)
        t2.start()

        return jsonify({
            'success': True,
            'message': '传输已开始',
            'output_dir': final_output_dir
        })
    except Exception:
        if not transfer_started:
            reset_running_state()
        raise

@app.route('/api/transfer_status')
def get_transfer_status():
    """获取传输状态"""
    # 复制状态并转换set为list（加锁，避免并发读到不一致快照）
    with transfer_status_lock:
        status_copy = transfer_status.copy()
    status_copy['completed_count'] = len(transfer_status.get('completed_files', set()))
    status_copy.pop('completed_files', None)  # 移除set，避免JSON序列化错误
    return jsonify(status_copy)

@app.route('/api/pause_transfer', methods=['POST'])
def pause_transfer():
    """暂停当前传输（不再提交新任务，已在途任务完成后等待）"""
    transfer_status['paused'] = True
    return jsonify({'success': True, 'paused': True})

@app.route('/api/resume_live', methods=['POST'])
def resume_live():
    """恢复当前传输（继续提交新任务）"""
    if not transfer_status.get('is_running', False):
        return jsonify({'success': False, 'error': '没有正在进行的传输'}), 400
    transfer_status['paused'] = False
    return jsonify({'success': True, 'paused': False})

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

def upsert_device_info(device_id: str, device_name: str):
    """将设备信息写入 devices 表（若存在则更新名称与心跳）。"""
    try:
        init_database()
        conn = sqlite3.connect(DB_FILE)
        cur = conn.cursor()
        now = datetime.now().isoformat()
        # 插入或更新设备信息
        cur.execute('''
            INSERT INTO devices (device_id, device_name, last_heartbeat, connected_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(device_id) DO UPDATE SET
                device_name=excluded.device_name,
                last_heartbeat=excluded.last_heartbeat
        ''', (device_id, device_name, now, now))
        conn.commit()
        conn.close()
        return True
    except Exception as e:
        print(f"⚠️ 写入设备信息失败: {e}")
        return False

def _get_current_device_name() -> str:
    """返回设备名称（仅使用 ro.product.model），用于历史记录展示。"""
    try:
        ok_m, out_m, _ = run_adb_command("adb shell getprop ro.product.model", timeout=3)
        model = (out_m or '').strip()
        return model or 'Android 设备'
    except Exception:
        return 'Android 设备'

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

def save_usb_batch_from_folder(output_dir: str):
    """扫描单个USB输出批次目录并保存到数据库。

    output_dir 形如: <OUTPUT_DIR>/<device_label>/<YYYYMMDD_HHMMSS>
    """
    try:
        init_database()
        if not output_dir:
            return False
        # 解析 device_id 与 batch_id
        batch_id = os.path.basename(output_dir.rstrip(os.sep))
        device_dir = os.path.dirname(output_dir.rstrip(os.sep))
        device_id = os.path.basename(device_dir)

        if not os.path.isdir(output_dir):
            return False

        # 扫描文件
        photos = []
        total_size = 0
        for root, dirs, files in os.walk(output_dir):
            # 只统计该批次目录下的直接文件（按照保存策略，扁平化存放）
            for fn in files:
                if fn.startswith('.'):
                    continue
                fp = os.path.join(root, fn)
                try:
                    st = os.stat(fp)
                    size = int(st.st_size)
                    mtime = datetime.fromtimestamp(st.st_mtime).strftime('%Y-%m-%d %H:%M:%S')
                except Exception:
                    size = 0
                    mtime = ''
                total_size += size
                photos.append({
                    'name': fn,
                    'path': fn,
                    'size': size,
                    'size_mb': round(size / 1024.0 / 1024.0, 2),
                    'date': mtime,
                })

        if not photos:
            return False

        # 解析时间戳
        try:
            timestamp_str = datetime.strptime(batch_id, '%Y%m%d_%H%M%S').strftime('%Y-%m-%d %H:%M:%S')
        except Exception:
            timestamp_str = datetime.now().strftime('%Y-%m-%d %H:%M:%S')

        batch_info = {
            'batch_id': batch_id,
            'timestamp': timestamp_str,
            'photo_count': len(photos),
            'total_size': total_size,
            'total_size_mb': round(total_size / 1024.0 / 1024.0, 2),
            'photos': photos,
            'status': 'completed',
        }
        save_batch_to_db(device_id, batch_info)
        return True
    except Exception as e:
        print(f"⚠️ save_usb_batch_from_folder 失败: {e}")
        return False

@app.route('/api/history/clear', methods=['POST'])
def api_history_clear():
    """清空历史备份记录。可选是否同时删除本地备份文件。"""
    try:
        init_database()
        data = request.get_json(silent=True) or {}
        delete_files = bool(data.get('delete_files'))

        # 清空数据库记录
        conn = sqlite3.connect(DB_FILE)
        cur = conn.cursor()
        cur.execute('DELETE FROM photos')
        cur.execute('DELETE FROM batches')
        conn.commit()
        conn.close()

        # 清空内存缓存
        global device_upload_batches, device_photos
        device_upload_batches = {}
        device_photos = {}

        removed = 0
        if delete_files:
            base = Path(OUTPUT_DIR).expanduser().resolve(strict=False)
            if base.exists() and base.is_dir():
                for entry in base.iterdir():
                    try:
                        # 仅删除目录或普通文件，忽略隐藏文件
                        if entry.name.startswith('.'):
                            continue
                        if entry.is_dir():
                            import shutil
                            shutil.rmtree(entry, ignore_errors=True)
                            removed += 1
                        elif entry.is_file():
                            entry.unlink(missing_ok=True)
                            removed += 1
                    except Exception:
                        pass

        return jsonify({'success': True, 'deleted_files': removed})
    except Exception as e:
        return jsonify({'success': False, 'error': str(e)}), 500

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

# ==================== 历史备份记录（通用/USB视图） ====================

def _safe_int(v, default=0):
    try:
        return int(v)
    except Exception:
        return default

def _count_files_recursive(base_dir: str) -> int:
    try:
        cnt = 0
        for root, dirs, files in os.walk(base_dir):
            # 可选：忽略隐藏目录
            dirs[:] = [d for d in dirs if not d.startswith('.')]
            for f in files:
                if f.startswith('.'):
                    continue
                fp = os.path.join(root, f)
                try:
                    if os.path.isfile(fp):
                        cnt += 1
                except Exception:
                    pass
        return cnt
    except Exception:
        return 0

@app.route('/api/history/batches')
def api_history_batches():
    """返回历史备份批次摘要（可按当前USB设备或全部设备）。

    Query:
      - current: '1' 时优先使用当前USB设备标签；若无法获取则返回全部
      - device: 指定设备标签（与输出目录下的设备文件夹名一致）
      - q: 模糊查询（匹配 batch_id 或 timestamp）
    返回字段：device_id, batch_id, timestamp, photo_count, total_size_mb, base_path, exists_count, missing_count
    """
    try:
        # 确保数据库表存在
        try:
            init_database()
        except Exception:
            pass

        want_current = (request.args.get('current') in ('1', 'true', 'yes', 'on'))
        q = (request.args.get('q') or '').strip()
        device = (request.args.get('device') or '').strip()

        # 解析当前USB设备的标签（用于USB页面）
        if want_current and not device:
            try:
                if selected_device:
                    device = _get_current_device_label()
            except Exception:
                device = ''

        conn = sqlite3.connect(DB_FILE)
        conn.row_factory = sqlite3.Row
        cur = conn.cursor()

        sql = "SELECT device_id, batch_id, timestamp, photo_count, total_size_mb, is_legacy FROM batches"
        params = []
        conds = []
        if device:
            conds.append("device_id = ?")
            params.append(device)
        if q:
            conds.append("(batch_id LIKE ? OR timestamp LIKE ?)")
            like = f"%{q}%"
            params.extend([like, like])
        if conds:
            sql += " WHERE " + " AND ".join(conds)
        sql += " ORDER BY timestamp DESC, batch_id DESC"

        rows = cur.execute(sql, params).fetchall()

        # 加载设备名称映射（若存在devices表记录则使用，否则回退到从device_id推测）
        device_name_map = {}
        try:
            cur2 = conn.cursor()
            try:
                for drow in cur2.execute('SELECT device_id, device_name FROM devices'):
                    did = drow[0]
                    dname = drow[1]
                    if did and dname:
                        device_name_map[did] = dname
            except Exception:
                pass
        except Exception:
            pass

        def _guess_device_name_from_id(did: str) -> str:
            try:
                if not did:
                    return ''
                # 尝试去掉首段（可能是序列号），其余替换下划线为空格
                parts = did.split('_')
                if len(parts) >= 2:
                    return ' '.join(parts[1:])
                return did.replace('_', ' ')
            except Exception:
                return did or ''

        # 若数据库为空，尝试从文件系统重建一次（懒加载）
        if not rows:
            try:
                scan_and_rebuild_batches()
                rows = cur.execute(sql, params).fetchall()
            except Exception:
                pass

        results = []
        for r in rows:
            device_id = r['device_id']
            batch_id = r['batch_id']
            ts = r['timestamp']
            photo_count = _safe_int(r['photo_count'])
            total_size_mb = float(r['total_size_mb'] or 0.0)
            is_legacy = bool(r['is_legacy']) if 'is_legacy' in r.keys() else False

            # 构造本地批次路径
            if is_legacy:
                base_path = os.path.join(OUTPUT_DIR, device_id)
            else:
                base_path = os.path.join(OUTPUT_DIR, device_id, batch_id)

            exists_count = 0
            missing_count = 0
            try:
                if os.path.isdir(base_path):
                    exists_count = _count_files_recursive(base_path)
                    missing_count = max(0, photo_count - exists_count)
                else:
                    exists_count = 0
                    missing_count = max(0, photo_count)
            except Exception:
                exists_count = 0
                missing_count = max(0, photo_count)

            results.append({
                'device_id': device_id,
                'device_name': device_name_map.get(device_id) or _guess_device_name_from_id(device_id),
                'batch_id': batch_id,
                'timestamp': ts,
                'photo_count': photo_count,
                'total_size_mb': round(total_size_mb, 2),
                'base_path': os.path.abspath(base_path),
                'exists_count': exists_count,
                'missing_count': missing_count,
                'is_legacy': is_legacy,
            })

        conn.close()
        return jsonify({'success': True, 'batches': results, 'count': len(results), 'device': device or None})
    except Exception as e:
        return jsonify({'success': False, 'error': str(e)}), 500

@app.route('/api/history/devices')
def api_history_devices():
    """返回历史记录中出现过的设备列表（来自batches表），附带设备名称、批次数和累计照片数量。"""
    try:
        try:
            init_database()
        except Exception:
            pass

        conn = sqlite3.connect(DB_FILE)
        conn.row_factory = sqlite3.Row
        cur = conn.cursor()

        rows = cur.execute(
            'SELECT device_id, COUNT(*) AS batches, SUM(photo_count) AS photos FROM batches GROUP BY device_id'
        ).fetchall()

        # 若没有数据，尝试从文件系统扫描一次
        if not rows:
            try:
                scan_and_rebuild_batches()
                rows = cur.execute(
                    'SELECT device_id, COUNT(*) AS batches, SUM(photo_count) AS photos FROM batches GROUP BY device_id'
                ).fetchall()
            except Exception:
                pass

        # 设备名映射
        name_map = {}
        try:
            for drow in conn.execute('SELECT device_id, device_name FROM devices'):
                if drow[0] and drow[1]:
                    name_map[drow[0]] = drow[1]
        except Exception:
            pass

        def _guess_name(did: str) -> str:
            try:
                if not did:
                    return ''
                parts = did.split('_')
                if len(parts) >= 2:
                    return ' '.join(parts[1:])
                return did.replace('_', ' ')
            except Exception:
                return did or ''

        devices = []
        for r in rows:
            did = r['device_id']
            devices.append({
                'device_id': did,
                'device_name': name_map.get(did) or _guess_name(did),
                'batches': int(r['batches'] or 0),
                'photos': int(r['photos'] or 0),
            })

        conn.close()
        return jsonify({'success': True, 'devices': devices, 'count': len(devices)})
    except Exception as e:
        return jsonify({'success': False, 'error': str(e)}), 500

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
    # 根据环境切换运行模式：默认关闭 debug 与重载器，避免双进程与阻塞
    debug_flag = os.getenv('AT_DEBUG', '0').lower() in ('1','true','yes','on')
    try:
        app.run(host='0.0.0.0', port=9500, threaded=True, debug=debug_flag, use_reloader=False)
    except TypeError:
        # 兼容旧版本 Flask 没有 use_reloader 参数位置变化
        app.run(host='0.0.0.0', port=9500, threaded=True, debug=debug_flag)
def _resolve_asset_path(filename: str) -> str | None:
    """在已知位置查找资源文件，返回可读路径或None。

    优先顺序：
    1) 与仓库根同级（app.py 所在目录的上级）
    2) 当前工作目录
    3) app.py 同级目录
    """
    try:
        here = os.path.dirname(__file__)
        root = os.path.abspath(os.path.join(here, os.pardir))
        cand = [
            os.path.join(root, filename),
            os.path.join(os.getcwd(), filename),
            os.path.join(here, filename),
        ]
        for p in cand:
            try:
                if os.path.isfile(p) and os.path.getsize(p) > 0:
                    return p
            except Exception:
                continue
    except Exception:
        pass
    return None

def _write_placeholder_from_asset(dst_thumb_path: str, size: int, asset_name: str = 'video-bac.jpeg') -> bool:
    """用指定资源图片生成占位缩略图（裁剪居中为方形，缩放到 size）。"""
    try:
        src = _resolve_asset_path(asset_name)
        if not src:
            return False
        from PIL import Image
        with Image.open(src) as im:
            # 转RGB，兼容性更好
            if im.mode not in ('RGB', 'L'):
                im = im.convert('RGB')
            w, h = im.size
            # 居中裁剪成正方形
            if w != h:
                if w > h:
                    # 裁左右
                    off = (w - h) // 2
                    im = im.crop((off, 0, off + h, h))
                else:
                    # 裁上下
                    off = (h - w) // 2
                    im = im.crop((0, off, w, off + w))
            # 缩放到目标尺寸
            try:
                Resampling = getattr(__import__('PIL.Image', fromlist=['Image']).Image, 'Resampling', None)
                resample = Resampling.BILINEAR if Resampling else 2
            except Exception:
                resample = 2
            s = max(64, int(size))
            im = im.resize((s, s), resample)
            os.makedirs(os.path.dirname(dst_thumb_path), exist_ok=True)
            tmp = dst_thumb_path + '.part.jpg'
            im.save(tmp, format='JPEG', quality=70, optimize=True)
            return _safe_atomic_replace(tmp, dst_thumb_path)
    except Exception:
        return False
