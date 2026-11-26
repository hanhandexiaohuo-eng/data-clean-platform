# ============================================================
# 扫描网关镜像（Node 22 LTS + 单依赖 busboy，固定版本）
# ------------------------------------------------------------
# 不包含证书、密钥或应用代码；以非 root 用户运行，容器根文件系统只读。
# ============================================================
FROM node:22-alpine

WORKDIR /app

RUN npm install --omit=dev --no-audit --no-fund --no-package-lock busboy@1.6.0

COPY server.mjs ./

USER node

EXPOSE 8080

CMD ["node", "server.mjs"]
