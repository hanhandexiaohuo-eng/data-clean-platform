#!/bin/sh
# ============================================================
# 渲染 Nginx 配置（仅替换白名单变量）
# ------------------------------------------------------------
# 严禁直接对整个 conf 做无参数 envsubst，否则 $uri/$host/$remote_addr
# 等 Nginx 内置变量会被替换为空，导致代理/限流/日志异常。
# ============================================================
set -eu

TEMPLATE="${NGINX_TEMPLATE:-/etc/nginx/default.conf.template}"
OUTPUT="/etc/nginx/conf.d/default.conf"

: "${NGINX_CLIENT_MAX_BODY_SIZE:=25m}"
: "${NGINX_SERVER_NAME:=_}"
: "${NGINX_HTTPS_CERT:=/etc/nginx/certs/fullchain.pem}"
: "${NGINX_HTTPS_KEY:=/etc/nginx/certs/privkey.pem}"

export NGINX_CLIENT_MAX_BODY_SIZE NGINX_SERVER_NAME NGINX_HTTPS_CERT NGINX_HTTPS_KEY

# HTTPS 模板必须配置真实域名：禁止以 "_" 启动，否则非法 Host 无法拒绝
case "$TEMPLATE" in
    *https*)
        if [ -z "$NGINX_SERVER_NAME" ] || [ "$NGINX_SERVER_NAME" = "_" ]; then
            echo "[nginx] ERROR: HTTPS 模板必须设置 NGINX_SERVER_NAME（真实域名），拒绝启动" >&2
            exit 1
        fi
        if [ ! -r "$NGINX_HTTPS_CERT" ] || [ ! -r "$NGINX_HTTPS_KEY" ]; then
            echo "[nginx] ERROR: HTTPS 证书或私钥不可读，拒绝启动" >&2
            exit 1
        fi
        ;;
esac

envsubst '${NGINX_CLIENT_MAX_BODY_SIZE} ${NGINX_SERVER_NAME} ${NGINX_HTTPS_CERT} ${NGINX_HTTPS_KEY}' \
    < "$TEMPLATE" > "$OUTPUT"

echo "[nginx] rendered $OUTPUT from $TEMPLATE"
