#!/usr/bin/env python3
"""800MB protocol bench: tcp / ftp / http_put / http_multipart.

Does not use USB/ADB. Binds on the LAN NIC so numbers include Wi-Fi stack,
not just loopback.
"""
from __future__ import annotations

import argparse
import http.server
import os
import socket
import struct
import sys
import tempfile
import threading
import time
from urllib.parse import unquote

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fast_transfer

SIZE_800M = 800 * 1024 * 1024
BLOCK = 1024 * 1024


def lan_ip() -> str:
    if sys.platform == "darwin":
        import subprocess
        try:
            p = subprocess.run(["ipconfig", "getifaddr", "en0"], capture_output=True, text=True, timeout=1)
            if p.returncode == 0 and p.stdout.strip():
                return p.stdout.strip().splitlines()[0]
        except Exception:
            pass
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ip = s.getsockname()[0]
        s.close()
        if ip and not ip.startswith("127.") and not ip.startswith("198.18."):
            return ip
    except Exception:
        pass
    return "127.0.0.1"


def make_payload(path: str, size: int):
    block = bytes([i % 256 for i in range(BLOCK)])
    with open(path, "wb") as f:
        left = size
        while left > 0:
            n = min(BLOCK, left)
            f.write(block[:n])
            left -= n


def send_tcp(host, port, path):
    size = os.path.getsize(path)
    with open(path, "rb") as f:
        def reader(n):
            return f.read(n)
        ok = fast_transfer.send_tcp(host, port, os.path.basename(path), size, reader)
    if not ok:
        raise RuntimeError("tcp not OK")


def send_ftp(host, port, path):
    size = os.path.getsize(path)
    ctrl = socket.create_connection((host, port), 10)
    def line():
        buf = b""
        while not buf.endswith(b"\n"):
            buf += ctrl.recv(1)
        return buf.decode().strip()
    def cmd(c):
        ctrl.sendall((c + "\r\n").encode())
        return line()
    line()
    cmd("USER a")
    cmd("PASS a")
    cmd("TYPE I")
    resp = cmd("PASV")
    inner = resp[resp.find("(") + 1 : resp.find(")")]
    parts = inner.split(",")
    data_port = int(parts[4]) * 256 + int(parts[5])
    data = socket.create_connection((host, data_port), 10)
    ctrl.sendall(f"STOR {os.path.basename(path)}\r\n".encode())
    line()
    with open(path, "rb") as f:
        while True:
            chunk = f.read(256 * 1024)
            if not chunk:
                break
            data.sendall(chunk)
    data.close()
    line()
    cmd("QUIT")
    ctrl.close()


class _PutHandler(http.server.BaseHTTPRequestHandler):
    dest_dir = "/tmp"

    def log_message(self, fmt, *args):
        pass

    def do_PUT(self):
        length = int(self.headers.get("Content-Length", "0"))
        name = self.headers.get("X-Filename") or "put.bin"
        dest = os.path.join(self.dest_dir, os.path.basename(name))
        remaining = length
        with open(dest, "wb") as f:
            while remaining > 0:
                chunk = self.rfile.read(min(256 * 1024, remaining))
                if not chunk:
                    break
                f.write(chunk)
                remaining -= len(chunk)
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"success":true}')

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        dest = os.path.join(self.dest_dir, "multipart.bin")
        remaining = length
        with open(dest, "wb") as f:
            while remaining > 0:
                chunk = self.rfile.read(min(256 * 1024, remaining))
                if not chunk:
                    break
                f.write(chunk)
                remaining -= len(chunk)
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"success":true}')


def start_http(port, dest_dir):
    _PutHandler.dest_dir = dest_dir
    httpd = http.server.ThreadingHTTPServer(("0.0.0.0", port), _PutHandler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd


def send_http_put(host, port, path):
    size = os.path.getsize(path)
    body = open(path, "rb")
    req = f"PUT /api/fast/put HTTP/1.1\r\nHost: {host}\r\nContent-Length: {size}\r\nX-Filename: {os.path.basename(path)}\r\nConnection: close\r\n\r\n"
    sock = socket.create_connection((host, port), 10)
    sock.sendall(req.encode())
    while True:
        chunk = body.read(256 * 1024)
        if not chunk:
            break
        sock.sendall(chunk)
    body.close()
    sock.recv(4096)
    sock.close()


def send_http_multipart(host, port, path):
    boundary = "----ATBenchBoundary"
    filename = os.path.basename(path)
    head = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        f"Content-Type: application/octet-stream\r\n\r\n"
    ).encode()
    tail = f"\r\n--{boundary}--\r\n".encode()
    size = os.path.getsize(path)
    total = len(head) + size + len(tail)
    req = (
        f"POST /api/wifi/upload_photo HTTP/1.1\r\n"
        f"Host: {host}\r\n"
        f"Content-Type: multipart/form-data; boundary={boundary}\r\n"
        f"Content-Length: {total}\r\n"
        f"Connection: close\r\n\r\n"
    ).encode()
    sock = socket.create_connection((host, port), 10)
    sock.sendall(req + head)
    with open(path, "rb") as f:
        while True:
            chunk = f.read(256 * 1024)
            if not chunk:
                break
            sock.sendall(chunk)
    sock.sendall(tail)
    sock.recv(4096)
    sock.close()


def timed(label, fn):
    t0 = time.perf_counter()
    fn()
    sec = time.perf_counter() - t0
    mb = SIZE_800M / (1024 * 1024)
    speed = mb / sec if sec else 0
    print(f"{label:16}  {sec:7.2f} s   {speed:6.1f} MB/s")
    return label, sec, speed


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mb", type=int, default=800)
    parser.add_argument("--host", default="")
    args = parser.parse_args()
    global SIZE_800M
    SIZE_800M = args.mb * 1024 * 1024
    host = args.host or lan_ip()
    dest = tempfile.mkdtemp(prefix="at-bench-")
    payload = os.path.join(tempfile.gettempdir(), f"at-payload-{args.mb}mb.bin")
    print(f"payload {args.mb} MB -> {payload}")
    print(f"target host {host}  (LAN NIC, not USB)")
    if not os.path.exists(payload) or os.path.getsize(payload) != SIZE_800M:
        t0 = time.perf_counter()
        make_payload(payload, SIZE_800M)
        print(f"created payload in {time.perf_counter()-t0:.1f}s")

    fast_transfer.start_fast_servers(dest, host)
    httpd = start_http(9503, dest)
    time.sleep(0.3)

    results = []
    results.append(timed("tcp", lambda: send_tcp(host, fast_transfer.TCP_PORT, payload)))
    results.append(timed("ftp", lambda: send_ftp(host, fast_transfer.FTP_PORT, payload)))
    results.append(timed("http_put", lambda: send_http_put(host, 9503, payload)))
    results.append(timed("http_multipart", lambda: send_http_multipart(host, 9503, payload)))

    print("-" * 44)
    fastest = min(results, key=lambda r: r[1])
    print(f"fastest: {fastest[0]}")
    httpd.shutdown()


if __name__ == "__main__":
    main()
