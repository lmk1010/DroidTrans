#!/usr/bin/env python3
"""界面静态检查：抓那些跑起来才会炸、但看代码就能发现的问题。

  ./scripts/check-frontend.py

1. 悬空引用：$('#foo') 指向 index.html 里不存在的 id，且没写 ?. 兜底。
   删页面时最容易留下这种——一个 null.addEventListener 就把后面的脚本全带崩。
2. 带 body 却没写 method 的 fetch 调用（会退成 GET 直接抛异常）。
3. index.html 里引用了但字典里没有的 i18n key。
"""
import re
import sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent / "desktop" / "frontend"
html = (root / "index.html").read_text()
js = (root / "app.js").read_text()

problems = []

# ---- 1. 悬空的 $('#id') ----
html_ids = set(re.findall(r'id="([^"]+)"', html))
# 运行时才创建的元素
runtime_ids = {"fatal"}
for m in re.finditer(r"""\$\((['"])#([\w-]+)\1\)(\??)""", js):
    ident, guarded = m.group(2), m.group(3)
    if ident in html_ids or ident in runtime_ids:
        continue
    line = js[: m.start()].count("\n") + 1
    if guarded == "?":
        continue  # 写了 ?. 的算显式兜底
    problems.append(f"app.js:{line} 引用了不存在的 #{ident}（且没有 ?. 兜底）")

# ---- 2. 带 body 却没 method 的 fetch ----
for m in re.finditer(r"fetch\(([^;]*?)\)\s*[;.]", js, re.S):
    call = m.group(1)
    if "body" in call and "method" not in call:
        line = js[: m.start()].count("\n") + 1
        problems.append(f"app.js:{line} fetch 带了 body 却没写 method，会退成 GET 抛异常")

# ---- 3. 同一字典里重复定义的 key ----
for lang in ("zh", "en"):
    block = re.search(r"%s:\s*\{(.*?)\n  \},?" % lang, js, re.S)
    if not block:
        continue
    found = re.findall(r"(\w+):\s*'", block.group(1))
    for k in {x for x in found if found.count(x) > 1}:
        problems.append(f"app.js 的 {lang} 文案里 {k} 重复定义了（后面的会悄悄覆盖前面的）")

# ---- 4. 缺失的 i18n key ----
zh = re.search(r"zh:\s*\{(.*?)\n  \},", js, re.S)
keys = set(re.findall(r"(\w+):\s*'", zh.group(1))) if zh else set()
for m in re.finditer(r'data-i18n(?:-ph|-title)?="([\w]+)"', html):
    if m.group(1) not in keys:
        problems.append(f"index.html 用了未定义的文案 key: {m.group(1)}")

if problems:
    print("界面检查发现 %d 个问题：" % len(problems))
    for p in problems:
        print("  ❌", p)
    sys.exit(1)

print("界面检查通过：无悬空引用、无 GET 带 body、文案 key 齐全")
