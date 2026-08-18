"""Fast LAN transfer: raw TCP + minimal FTP STOR + helpers.

Phone/PC auto-selects the fastest reachable channel. HTTP PUT lives in Flask.
"""
from __future__ import annotations

import os
import socket
import struct
import threading
from pathlib import Path

TCP_PORT = 9501
FTP_PORT = 9502
MAGIC = b"ATF1"
CHUNK = 256 * 1024

_output_dir = "."
_lan_ip = "127.0.0.1"
_started = False


def set_output_dir(path: str):
    global _output_dir
    _output_dir = path or "."
    os.makedirs(_output_dir, exist_ok=True)


def _safe_join(base: str, relative: str) -> str:
    relative = (relative or "unnamed.bin").lstrip("/\\")
    dest = os.path.abspath(os.path.join(base, relative))
    root = os.path.abspath(base)
    if not dest.startswith(root + os.sep) and dest != root:
        dest = os.path.join(root, os.path.basename(relative))
    os.makedirs(os.path.dirname(dest) or root, exist_ok=True)
    return dest


def recv_exact(conn: socket.socket, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = conn.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("connection closed")
        buf.extend(chunk)
    return bytes(buf)


def read_atf1(conn: socket.socket):
    magic = recv_exact(conn, 4)
    if magic != MAGIC:
        raise ValueError(f"bad magic {magic!r}")
    name_len = struct.unpack(">I", recv_exact(conn, 4))[0]
    if name_len > 4096:
        raise ValueError("name too long")
    name = recv_exact(conn, name_len).decode("utf-8", "replace")
    size = struct.unpack(">Q", recv_exact(conn, 8))[0]
    return name, size


def write_stream(conn: socket.socket, dest: str, size: int):
    received = 0
    with open(dest, "wb") as f:
        remaining = size
        while remaining > 0:
            chunk = conn.recv(min(CHUNK, remaining))
            if not chunk:
                break
            f.write(chunk)
            remaining -= len(chunk)
            received += len(chunk)
    return received


def handle_tcp_client(conn: socket.socket, addr):
    try:
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        name, size = read_atf1(conn)
        dest = _safe_join(_output_dir, name)
        received = write_stream(conn, dest, size)
        conn.sendall(b"OK\n")
        print(f"⚡ TCP 收完 {name} {received} bytes from {addr[0]}")
    except Exception as e:
        try:
            conn.sendall(f"ERR {e}\n".encode("utf-8", "replace"))
        except Exception:
            pass
        print(f"⚡ TCP 失败 {addr}: {e}")
    finally:
        try:
            conn.close()
        except Exception:
            pass


def tcp_server():
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", TCP_PORT))
    sock.listen(32)
    print(f"⚡ TCP 二进制通道 :{TCP_PORT}")
    while True:
        conn, addr = sock.accept()
        threading.Thread(target=handle_tcp_client, args=(conn, addr), daemon=True).start()


def _ftp_line(conn: socket.socket) -> str:
    buf = b""
    while not buf.endswith(b"\n"):
        chunk = conn.recv(1)
        if not chunk:
            return ""
        buf += chunk
    return buf.decode("utf-8", "replace").strip()


def _ftp_send(conn: socket.socket, msg: str):
    conn.sendall((msg + "\r\n").encode("ascii", "replace"))


def handle_ftp_client(conn: socket.socket, addr):
    data_sock = None
    try:
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        _ftp_send(conn, "220 AndroidTransfer FTP")
        filename = "unnamed.bin"
        while True:
            line = _ftp_line(conn)
            if not line:
                break
            cmd, _, arg = line.partition(" ")
            cmd = cmd.upper()
            if cmd in ("USER", "PASS"):
                _ftp_send(conn, "230 OK")
            elif cmd == "TYPE":
                _ftp_send(conn, "200 Type set")
            elif cmd == "SYST":
                _ftp_send(conn, "215 UNIX Type: L8")
            elif cmd == "FEAT":
                _ftp_send(conn, "211 No Features")
            elif cmd == "PWD":
                _ftp_send(conn, '257 "/"')
            elif cmd == "CWD":
                _ftp_send(conn, "250 OK")
            elif cmd == "PASV":
                if data_sock:
                    data_sock.close()
                data_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                data_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                data_sock.bind(("0.0.0.0", 0))
                data_sock.listen(1)
                data_sock.settimeout(30)
                port = data_sock.getsockname()[1]
                ip = _lan_ip.replace(".", ",")
                p1, p2 = port // 256, port % 256
                _ftp_send(conn, f"227 Entering Passive Mode ({ip},{p1},{p2})")
            elif cmd == "STOR":
                filename = arg.strip() or filename
                if not data_sock:
                    _ftp_send(conn, "425 Use PASV first")
                    continue
                _ftp_send(conn, "150 Opening data connection")
                data_conn, _ = data_sock.accept()
                dest = _safe_join(_output_dir, filename)
                received = 0
                with open(dest, "wb") as f:
                    while True:
                        chunk = data_conn.recv(CHUNK)
                        if not chunk:
                            break
                        f.write(chunk)
                        received += len(chunk)
                data_conn.close()
                data_sock.close()
                data_sock = None
                _ftp_send(conn, "226 Transfer complete")
                print(f"⚡ FTP STOR {filename} {received} bytes from {addr[0]}")
            elif cmd in ("QUIT", "BYE"):
                _ftp_send(conn, "221 Bye")
                break
            else:
                _ftp_send(conn, "502 Not implemented")
    except Exception as e:
        print(f"⚡ FTP 失败 {addr}: {e}")
    finally:
        try:
            if data_sock:
                data_sock.close()
        except Exception:
            pass
        try:
            conn.close()
        except Exception:
            pass


def ftp_server():
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", FTP_PORT))
    sock.listen(16)
    print(f"⚡ FTP 通道 :{FTP_PORT}")
    while True:
        conn, addr = sock.accept()
        threading.Thread(target=handle_ftp_client, args=(conn, addr), daemon=True).start()


def start_fast_servers(output_dir: str, lan_ip: str):
    global _started, _lan_ip
    if _started:
        return
    _started = True
    _lan_ip = lan_ip or "127.0.0.1"
    set_output_dir(output_dir)
    threading.Thread(target=tcp_server, daemon=True).start()
    threading.Thread(target=ftp_server, daemon=True).start()


def caps(lan_ip: str, http_port: int = 9500):
    return {
        "success": True,
        "ip": lan_ip,
        "http_port": http_port,
        "tcp_port": TCP_PORT,
        "ftp_port": FTP_PORT,
        "prefer": ["tcp", "ftp", "http_put", "http_multipart"],
        "protocols": [
            {"id": "tcp", "port": TCP_PORT, "priority": 1},
            {"id": "ftp", "port": FTP_PORT, "priority": 2},
            {"id": "http_put", "path": "/api/fast/put", "priority": 3},
            {"id": "http_multipart", "path": "/api/wifi/upload_photo", "priority": 4},
        ],
    }


def send_tcp(host: str, port: int, name: str, size: int, reader, timeout=30):
    sock = socket.create_connection((host, port), timeout=timeout)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    name_b = name.encode("utf-8")
    sock.sendall(MAGIC + struct.pack(">I", len(name_b)) + name_b + struct.pack(">Q", size))
    remaining = size
    while remaining > 0:
        chunk = reader(min(CHUNK, remaining))
        if not chunk:
            break
        sock.sendall(chunk)
        remaining -= len(chunk)
    resp = sock.recv(64)
    sock.close()
    return resp.startswith(b"OK")
