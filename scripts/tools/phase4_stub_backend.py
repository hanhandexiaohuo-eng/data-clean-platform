#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第四阶段本地联调桩后端（仅测试使用，不进入生产镜像）。

用法：python phase4_stub_backend.py <port>
功能：
  - /api/echo   回显代理头与 Host，用于验证 $host/$remote_addr/$proxy_add_x_forwarded_for 渲染
  - /api/boom   返回 502 与内部标记，用于验证网关错误页脱敏
  - /api/cookie 返回 __Host-dc_anon Cookie，用于验证边缘 Cookie 透传属性
  - 其它路径    返回 200 JSON

日志不输出请求行与来源地址，避免测试输出出现 IP。
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        # 静默：测试输出不落来源地址
        pass

    def _send(self, status, payload, extra_headers=None):
        data = payload if isinstance(payload, bytes) else json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        for key, value in (extra_headers or []):
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path.startswith("/api/boom"):
            self._send(502, b"upstream-secret-stack")
            return
        if path.startswith("/api/cookie"):
            self._send(200, {"ok": True},
                       [("Set-Cookie",
                         "__Host-dc_anon=testtoken; Path=/; Secure; HttpOnly; SameSite=Strict")])
            return
        if path.startswith("/api/echo"):
            self._send(200, {
                "host": self.headers.get("Host"),
                "x_real_ip": self.headers.get("X-Real-IP"),
                "x_forwarded_for": self.headers.get("X-Forwarded-For"),
                "x_forwarded_proto": self.headers.get("X-Forwarded-Proto"),
                "uri": self.path,
            })
            return
        self._send(200, {"ok": True, "path": path})

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        self._send(200, {"ok": True, "received": length})


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18080
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
