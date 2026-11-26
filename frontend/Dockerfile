# ============================================================
# 前端 + Nginx 镜像（多阶段构建）
# ------------------------------------------------------------
# 基础镜像策略：
#   - 构建阶段固定 Node 22 大版本；
#   - 运行阶段固定 Nginx 1.27 大版本，不使用 latest；
#   - 升级策略：随 Node LTS / Nginx 稳定分支升级，升级前回归静态资源与
#     /api 反向代理冒烟。
# ============================================================
FROM node:22-alpine AS build

WORKDIR /app

COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund --registry=https://registry.npmmirror.com

COPY frontend/ ./
RUN npm run build

FROM nginx:1.27-alpine

RUN rm -f /etc/nginx/conf.d/default.conf

# 模板在容器启动时由白名单 envsubst 渲染（见 docker-entrypoint.d 脚本）
COPY deploy/nginx/default.conf.template /etc/nginx/default.conf.template
COPY deploy/nginx/docker-entrypoint.d/40-render-nginx-conf.sh /docker-entrypoint.d/40-render-nginx-conf.sh
RUN chmod 0555 /docker-entrypoint.d/40-render-nginx-conf.sh

COPY --from=build /app/dist /usr/share/nginx/html

RUN chown -R nginx:nginx /usr/share/nginx/html

EXPOSE 80

# 实际健康检查由 compose 定义（/nginx-health）
