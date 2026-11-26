#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第四阶段：Compose 与 Nginx 模板静态边界校验（不启动容器）。

用法（仓库根目录）：
    python scripts/tools/phase4_validate_compose.py

退出码 0 表示全部通过；1 表示存在失败项。
"""
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))


def load(path):
    with open(path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f)


def main():
    base = load(ROOT / "docker-compose.yml")
    https = load(ROOT / "deploy/docker-compose.https.yml")
    services = base.get("services", {})

    check("compose: 存在 mysql/backend/nginx",
          all(k in services for k in ("mysql", "backend", "nginx")))

    for name in ("mysql", "backend", "migrate"):
        if name in services:
            check(f"compose: {name} 无宿主机端口映射", "ports" not in services[name])

    backend = services.get("backend", {})
    expose = [str(x) for x in backend.get("expose", [])]
    check("compose: backend 仅暴露 8080/8081 内部端口",
          "8080" in expose and "8081" in expose and "ports" not in backend)

    networks = base.get("networks", {})
    check("compose: db_net 为 internal 网络",
          bool(networks.get("db_net", {}).get("internal")))

    nginx = services.get("nginx", {})
    ports = [str(p) for p in nginx.get("ports", [])]
    check("compose: 基线仅 nginx 发布 HTTP 端口", len(ports) == 1 and "80" in ports[0])

    only_nginx = [name for name, svc in services.items() if svc.get("ports")]
    check("compose: 只有 nginx 声明 ports", only_nginx == ["nginx"], str(only_nginx))

    https_nginx = https.get("services", {}).get("nginx", {})
    https_ports = [str(p) for p in https_nginx.get("ports", [])]
    check("compose: HTTPS 覆盖仅新增 443", len(https_ports) == 1 and "443" in https_ports[0])

    volumes = [str(v) for v in https_nginx.get("volumes", [])]
    check("compose: 证书只读挂载",
          any("deploy/certs" in v and v.endswith(":ro") for v in volumes))
    check("compose: HTTPS 模板只读挂载",
          any("default.https.conf.template" in v and v.endswith(":ro") for v in volumes))
    https_env = https_nginx.get("environment", {}) or {}
    check("compose: HTTPS 覆盖注入 NGINX_SERVER_NAME",
          "NGINX_SERVER_NAME" in https_env)

    entry = (ROOT / "deploy/nginx/docker-entrypoint.d/40-render-nginx-conf.sh").read_text(encoding="utf-8")
    m = re.search(r"envsubst\s+'([^']+)'", entry)
    whitelist = m.group(1).split() if m else []
    allowed = {"${NGINX_CLIENT_MAX_BODY_SIZE}", "${NGINX_SERVER_NAME}",
               "${NGINX_HTTPS_CERT}", "${NGINX_HTTPS_KEY}"}
    check("nginx: envsubst 仅使用白名单变量",
          bool(m) and set(whitelist) <= allowed, str(whitelist))
    for var in ("$uri", "$host", "$remote_addr", "$proxy_add_x_forwarded_for"):
        check(f"nginx: envsubst 白名单未包含运行期变量 {var}",
              var not in whitelist)

    for tpl_name in ("default.conf.template", "default.https.conf.template"):
        text = (ROOT / "deploy/nginx" / tpl_name).read_text(encoding="utf-8")
        for var in ("$uri", "$host", "$remote_addr", "$proxy_add_x_forwarded_for"):
            check(f"nginx: {tpl_name} 保留运行期变量 {var}", var in text)
        placeholders = set(re.findall(r"\$\{[A-Z0-9_]+\}", text))
        unknown = placeholders - {"${NGINX_CLIENT_MAX_BODY_SIZE}", "${NGINX_SERVER_NAME}",
                                  "${NGINX_HTTPS_CERT}", "${NGINX_HTTPS_KEY}"}
        check(f"nginx: {tpl_name} 无未知模板占位符", not unknown, str(unknown))

    gitignore = (ROOT / ".gitignore").read_text(encoding="utf-8") if (ROOT / ".gitignore").exists() else ""
    check("repo: .gitignore 忽略 .env", ".env" in gitignore)
    check("repo: .gitignore 忽略证书目录",
          "deploy/certs" in gitignore or "*.pem" in gitignore)

    print("== 第四阶段 Compose / Nginx 静态校验 ==")
    failed = 0
    for name, ok, detail in results:
        flag = "PASS" if ok else "FAIL"
        if not ok:
            failed += 1
        print(f"[{flag}] {name}" + (f" | {detail}" if detail else ""))
    print(f"TOTAL={len(results)} PASS={len(results) - failed} FAIL={failed}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
