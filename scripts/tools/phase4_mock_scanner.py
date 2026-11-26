#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第四阶段本地模拟扫描服务（仅验收使用，不进入生产镜像）。

协议与 HttpFileScanService 一致：
    POST /scan  ->  200 {"clean": true|false}
    GET  /__mode/<clean|malicious|timeout|error>  -> 切换行为

安全说明：不记录请求体、文件名与来源地址；EICAR 测试串始终判定为风险。
"""
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODE_FILE = sys.argv[2] if len(sys.argv) > 2 else "scan-mode.txt"


def read_mode():
    try:
        with open(MODE_FILE, "r", encoding="utf-8") as f:
            return f.read().strip() or "clean"
    except OSError:
        return "clean"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def _json(self, status, payload):
        import json
        data = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.startswith("/__mode/"):
            mode = self.path.rsplit("/", 1)[-1]
            if mode not in ("clean", "malicious", "timeout", "error"):
                self._json(400, {"ok": False})
                return
            with open(MODE_FILE, "w", encoding="utf-8") as f:
                f.write(mode)
            self._json(200, {"mode": mode})
            return
        self._json(404, {"ok": False})

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""
        mode = read_mode()
        if b"EICAR-STANDARD-ANTIVIRUS-TEST-FILE" in body:
            self._json(200, {"clean": False})
            return
        if mode == "malicious":
            self._json(200, {"clean": False})
        elif mode == "timeout":
            time.sleep(10)
            self._json(200, {"clean": True})
        elif mode == "error":
            self._json(500, {"error": "scanner-internal"})
        else:
            self._json(200, {"clean": True})


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18090
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
