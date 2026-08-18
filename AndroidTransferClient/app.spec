# -*- mode: python ; coding: utf-8 -*-
"""
PyInstaller配置文件 - Android Transfer 后端打包
用于将Flask应用打包成独立可执行文件
"""

import sys
from pathlib import Path

block_cipher = None

# 当前目录
current_dir = Path('.').absolute()

# 需要包含的数据文件（仅模板；输出目录改为用户系统目录，不随应用打包）
datas = [
    ('templates', 'templates'),  # HTML模板
]

# 需要包含的隐藏导入（Flask相关）
hiddenimports = [
    'flask',
    'jinja2',
    'werkzeug',
    'click',
    'itsdangerous',
    'blinker',
    'sqlite3',
    'queue',
]

a = Analysis(
    ['app.py'],
    pathex=[],
    binaries=[],
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name='app',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    console=True,  # 保留控制台以便查看日志
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)

coll = COLLECT(
    exe,
    a.binaries,
    a.zipfiles,
    a.datas,
    strip=False,
    upx=True,
    upx_exclude=[],
    name='app',
)
