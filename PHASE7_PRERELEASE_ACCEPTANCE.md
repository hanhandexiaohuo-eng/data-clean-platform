# 第七阶段预发运行验收与生产发布准备报告

> 项目：`data-clean-platform`
> 阶段：第七阶段「预发运行验收与生产发布准备」
> 日期：2026-11-21
> 前提：第一至第六阶段代码、迁移、测试与交付文档已完成（`PHASE6_DELIVERY.md`，后端 152 项测试通过）。

---

## 0. 结论（先说结果）

**当前仍不满足生产发布条件，不给出生产发布建议；但核心运行时验收已在阿里云 ECS 预发环境真实执行并通过。**

本轮已把应用部署到阿里云 ECS（`<PRERELEASE_HOST>`，2 核 / 1.87GB / 2GB swap），以独立 Compose 项目 `dataclean`、独立 MySQL 卷、独立上传卷、真实 Nginx + MySQL + backend + ClamAV + 扫描网关运行，并完成了网关内部 HTTPS、公开模式隔离、配额、扫描链路、清理任务、监控告警与备份恢复的实际验证。

仍不满足发布门禁的原因（均为环境/配置侧，非代码缺陷）：

| 门禁项 | 当前状态 | 是否阻塞 |
|---|---|---|
| HTTPS、端口边界、真实 IP 链路实际验证 | 外部 HTTPS/端口边界已在放行 443 后验证通过；真实客户端 IP 在应用侧仅静态核对 | 通过（真实 IP 待抓包复核） |
| 恶意文件扫描服务真实可用性 | 已实际验证（fail-closed / 正常 CSV / EICAR / 临时文件清理） | 通过（受限：见 R-09 内存限制） |
| 跨会话越权实际验证 | 已实际验证（双会话读/删/导出全部失败，属主数据不受影响） | 通过 |
| 私有数据或密钥泄露检查 | 仓库扫描通过；运行时错误响应无路径/密钥泄露 | 通过（外部抓包未做） |
| 清理任务误删私有数据 | 已实际验证（模拟 TTL 到期，仅删除过期匿名会话数据，活跃会话不受影响） | 通过 |
| AI 配额、熔断、脱敏运行时有效性 | 公开模式默认关闭下受控拒绝已验证；**配额/熔断/降级未在受限配置下实测** | **阻塞**（若要开放 AI） |
| 备份恢复路径可用 | 已实际验证（数据库 dump 恢复至临时库、上传卷归档/恢复） | 通过 |
| 监控抓取与告警 | 已验证（targets up、14 条规则加载、scanner 宕机告警真实触发）；未接 Alertmanager | 部分通过 |

**放行条件**：完成 AI 受限配置下的运行时验证、接入告警通知渠道、完成版本回退演练（外部网络验收已于 2026-11-22 安全组放行 443 后通过）；之后重新出具本报告，方可讨论生产发布。

---

## 1. 验收环境与限制（证据边界）

### 1.1 本次执行环境

- 部署主机：阿里云 ECS（杭州）`<PRERELEASE_HOST>`，2 核 / 1870MB 内存 / 2047MB swap / 40GB 磁盘（可用 24GB）。
- 运行时：Docker Engine + Docker Compose v2；部署目录 `/opt/data-clean-platform`。
- 组件：`dataclean-mysql-1`、`dataclean-backend-1`、`dataclean-nginx-1`、`dataclean-clamav-1`、`dataclean-scanner-1`（另有一次性 `dataclean-migrate-1` 退出码 0）。
- 网络：容器网络 `app_net` 固定 `172.30.0.0/24`；`scan_net` 为 internal；Nginx 仅 80/443 对外，Prometheus 仅回环 `127.0.0.1:9090`。
- 证书：使用 CN/SAN=`<PRERELEASE_HOST>` 的自签名证书，仅用于预发；**可信证书项仍为未验证**。
- 云侧限制：阿里云安全组当前仅放行 22 端口，**外部 80/443 不可达**；本文所有网络结论均为「容器网络内部 + `--resolve` 回环」口径。
- 资源限制：为给 `data-clean-platform` 腾出内存，已按用户指示 `docker stop` 原 ServiceFlow 四个容器（未删除容器、卷与数据）；验收结束后可按需 `docker start` 恢复。
- 本机（Windows）仅用于编辑仓库与通过 SSH/SCP 下发脚本，不参与运行验收。

### 1.2 状态定义

- **已实际验证**：本次执行中真实运行并获得可复现结果。
- **仅静态检查**：仅审查配置/代码与执行语法、单元测试，未在预发环境运行。
- **未验证**：未执行，或所需环境缺失，不能给出通过结论。

---

## 2. 本轮实际完成的验证（可复现证据）

| 编号 | 项目 | 状态 | 证据 |
|---|---|---|---|
| E-01 | 仓库敏感信息扫描 | 已实际验证 | `node scripts/scan-secrets.mjs` → `未发现疑似明文凭据` |
| E-02 | 后端全量单元/集成测试 | 已实际验证 | 29 个测试类、152 项测试、0 失败 0 跳过（`p6-backend-build/target/surefire-reports`，2026-11-21 12:59） |
| E-03 | 前端生产构建 | 已实际验证 | `p6-frontend-build/dist` 生成 29 个文件，含 `index.html` |
| E-04 | 新增/修改 YAML 语法 | 已实际验证 | PyYAML 解析 7 个文件全部通过（compose ×4、prometheus、alerts、blackbox） |
| E-05 | 扫描网关 JS 语法 | 已实际验证 | `node --check deploy/scan-adapter/server.mjs` → 通过 |
| E-06 | Nginx 渲染脚本逻辑（白名单 envsubst、HTTPS 强校验） | 仅静态检查 | 脚本审查；本机无 Nginx 无法渲染实测 |
| E-07 | Compose 端口边界与网络隔离 | 仅静态检查 | `docker-compose*.yml` 审查；无 Docker 无法 `compose config`/`up` |
| E-08 | 备份/恢复 SQL 与卷恢复 | 已实际验证 | 见 E-17 |

### 2.1 阿里云 ECS 运行时验证（2026-11-22）

| 编号 | 项目 | 状态 | 证据（服务器上的实际输出） |
|---|---|---|---|
| E-09 | 镜像构建与启动 | 已实际验证 | backend 293MB、web 52.7MB、scanner 170MB、migrate 810MB；mysql/backend/nginx/clamav/scanner 全部 healthy，migrate 退出码 0 |
| E-10 | 数据库迁移 | 已实际验证 | Flyway V1–V10 全部成功（新执行 10，跳过 0）；演示数据 40 行，管理员账号已创建 |
| E-11 | HTTP→HTTPS 与 TLS | 已实际验证（容器内） | 80 全量 301；TLS1.1 被拒（alert 70），TLS1.2 成功（ECDHE-RSA-AES256-GCM-SHA384） |
| E-12 | 安全响应头与版本隐藏 | 已实际验证 | HSTS `max-age=31536000; includeSubDomains`；CSP/nosniff/SAMEORIGIN/Referrer-Policy/Permissions-Policy 齐全；`server: nginx` 无版本号 |
| E-13 | Host 伪造与内部端点隔离 | 已实际验证 | 伪造 Host 连接被断开（curl code 000）；`/api/health`、`/actuator/*`、`/api/admin/maintenance/cleanup`、`/api/ai/governance/config` 均 404 |
| E-14 | 匿名会话 Cookie | 已实际验证 | `POST /api/public/session` → 200；`__Host-dc_anon=...; Path=/; Max-Age=86400; Secure; HttpOnly; SameSite=Strict`，无 Domain |
| E-15 | 跨会话隔离 | 已实际验证 | 会话 B 访问 A 的 dataset 2：detail/preview/columns/statistics/analysis 均 404；带 `X-Confirm: yes` 的 DELETE、EXPORT 仍 404；A 访问仍 200 |
| E-16 | 配额与上限 | 已实际验证 | 单会话第 5 个数据集上传后，第 6 个返回 `429 数据集数量已达上限（5 个）`；21MB 文件返回 `400 文件大小超过公开模式上限（20MB）`；无 Cookie 访问返回 401 |
| E-17 | 恶意文件扫描链路 | 已实际验证 | scanner 未就绪 → `503 文件安全检测暂不可用`（fail-closed）；正常 CSV → 200 并落库；EICAR 原始样本（伪装 .csv）→ `400 文件未通过安全检测`；拒绝响应无路径/组件泄露；上传临时目录残留 0 |
| E-18 | AI 关闭行为 | 已实际验证 | `PUBLIC_AI_ENABLED=false` 下 `POST /api/ai/generate` → `403 公开演示环境暂未开放 AI 分析` |
| E-19 | 清理任务 | 已实际验证 | 模拟会话 TTL 到期后重启后端触发清理：`cleanup_log: sessions_cleaned=1, datasets_removed=1, tables_dropped=1, orphan=0`；活跃会话 A 的 dataset 2 预览仍 200，数据集总数 8→7 |
| E-20 | 监控与告警 | 已实际验证 | Prometheus `/api/v1/targets`：backend、nginx、backend-health 均 up，scanner 停机时 `probe_success=0`；14 条规则加载成功；停机 2 分钟后 `DataCleanHealthProbeFailed(P0)` 与 `DataCleanScannerDown(P1)` 真实触发（证据存于服务器 `evidence/`） |
| E-21 | 备份与恢复 | 已实际验证 | `mysqldump` 12.7KB 恢复至临时库：41 张表与生产库一致、dataset 行数 7=7；上传卷 tar 归档/解包成功（当前上传卷文件数为 0，符合导入后即时清理的设计） |
| E-22 | 外部网络验收（安全组放行 443 后） | 已实际验证 | 外部 `Test-NetConnection 443=True`；`https://<PRERELEASE_HOST>/` → 200 且 HSTS/CSP 等安全头齐全；`http://` → 301；TLS1.1 外部握手失败；3306/8080/9090/6379 外部均不可达 |

> 说明：E-11～E-14 为避免安全组未放行导致的外部不可达，使用 `curl --resolve <PRERELEASE_HOST>:443:127.0.0.1` 在服务器本机验证；这**不能替代**外部端口扫描与真实客户端 IP 链路验证（见 3.2 未验证项）。
> 说明：为在 1.87GB 内存主机上稳定运行，ClamAV 仅加载 `daily.cvd`（病毒库较少），并关闭 freshclam 自动更新（容器 DNS 受限）；扫描链路与 EICAR 验证有效，**不等于生产完整病毒库覆盖**（见 R-09）。

> 注：本轮未修改任何 Java 业务代码；E-02 的 152 项测试结论对当前代码仍然有效。新增的扫描网关为独立 Node 服务，仅完成语法检查。

---

## 3. 三态验收矩阵

### 3.1 预发环境要求（对应任务第一节）

| 编号 | 验收项 | 状态 | 说明 / 证据 |
|---|---|---|---|
| P7-ENV-01 | 独立域名/子域名 + 可信 HTTPS 证书 | 未验证 | 预发使用 IP 自签名证书；可信证书来源与域名待运维确认 |
| P7-ENV-02 | 独立数据库、独立上传卷、独立环境变量 | 已实际验证 | `dataclean_mysql_data`、`dataclean_uploads` 独立卷，独立 `.env` |
| P7-ENV-03 | 真实 Compose + Nginx + MySQL + backend + 扫描 + 监控启动 | 已实际验证 | 核心栈 + 扫描栈已启动；监控栈单独验证后因内存不足停止（见 R-09） |
| P7-ENV-04 | Nginx 为唯一公网入口 | 已实际验证（容器内） | `docker ps` 仅 nginx 映射 80/443；其余仅 `expose` |
| P7-ENV-05 | backend/MySQL/migrate/运维端点不映射公网端口 | 已实际验证 | 无对应 `ports`；Prometheus 仅 `127.0.0.1:9090` |
| P7-ENV-06 | 安全组仅 80/443；SSH 仅密钥 + 可信来源 | 未验证 | 当前安全组仅放行 22；80/443 需运维放行后复验 |
| P7-ENV-07 | 真实密钥仅经安全环境变量/密钥管理注入 | 已实际验证 | 全部经 `.env` 注入；`AI_SECRET_KEY` 格式校验生效（错误 hex 曾导致启动失败，已改为 Base64 32 字节） |

### 3.2 网关、HTTPS 与网络实测（对应第二节）

| 编号 | 验收项 | 状态 | 说明 / 证据 |
|---|---|---|---|
| P7-GW-01 | HTTP 全量 301 跳转 HTTPS | 已实际验证 | 服务器内 `curl` 80 端口返回 301 到 `https://<PRERELEASE_HOST>/` |
| P7-GW-02 | TLS 仅 1.2/1.3 | 已实际验证 | `openssl s_client -tls1_1` 失败（alert 70）；`-tls1_2` 成功 |
| P7-GW-03 | HSTS 生效 | 已实际验证 | 响应头 `strict-transport-security: max-age=31536000; includeSubDomains` |
| P7-GW-04 | CSP 与安全响应头生效 | 已实际验证 | CSP、nosniff、SAMEORIGIN、Referrer-Policy、Permissions-Policy 实测存在 |
| P7-GW-05 | 版本号隐藏 | 已实际验证 | 响应 `server: nginx`，无版本号 |
| P7-GW-06 | 静态资源缓存 | 仅静态检查 | `/assets/` 7d immutable、`index.html` no-cache；未做浏览器缓存复验 |
| P7-GW-07 | Cookie `__Host-dc_anon` 属性 | 已实际验证 | Set-Cookie 实测含 `Secure; HttpOnly; SameSite=Strict; Path=/` 且无 Domain |
| P7-GW-08 | Nginx 渲染未被 envsubst 清空 | 已实际验证 | 容器内配置渲染成功且 nginx 启动、健康检查通过 |
| P7-GW-09 | 外部仅 80/443 可达 | 已实际验证 | 安全组放行 443 后，外部 80/443 均可达（E-22） |
| P7-GW-10 | backend/MySQL 端口不可达 | 已实际验证 | 外部扫描 3306/8080/9090/6379 全部不可达（E-22） |
| P7-GW-11 | `/api/health`、管理、迁移、运维接口不公开 | 已实际验证 | `/api/health`、`/actuator/*`、`/api/admin/maintenance/cleanup`、`/api/ai/governance/config` 均 404 |
| P7-GW-12 | Host 伪造被拒绝 | 已实际验证 | 伪造 Host 请求连接被断开（curl code 000） |
| P7-GW-13 | 跨域请求被拒绝 | 已实际验证 | 跨域 `POST /api/public/session` 返回 403 |
| P7-GW-14 | 超大请求被拒绝 | 已实际验证 | 21MB 上传返回 400（公开模式 20MB 上限） |
| P7-GW-15 | 高频请求被限流 | 已实际验证 | 连续上传触发 `429 Too Many Requests`（nginx upload_zone 10r/m） |
| P7-GW-16 | 真实客户端 IP 链路 | 部分验证 | 受信代理 CIDR 已与 `app_net` 对齐；**真实公网客户端 IP 需安全组放行后验证** |
| P7-GW-17 | 伪造转发头不能绕过限流/审计 | 仅静态检查 | `ClientIpResolver` 有单元测试；未做公网伪造实测 |

### 3.3 公开模式运行验收（对应第三节）

| 编号 | 验收项 | 状态 | 说明 |
|---|---|---|---|
| P7-PUB-01 | 双浏览器匿名会话隔离 | 已实际验证 | 两个独立 Cookie 会话；B 无法读取 A 的数据集（404） |
| P7-PUB-02 | 跨会话访问/修改/清洗/回滚/导出/图表统一失败 | 已实际验证（读/删/导出） | detail/preview/columns/statistics/analysis 均 404；带 `X-Confirm` 的 DELETE/EXPORT 仍 404；清洗/回滚/图表为同一属主校验链路，未逐一构造请求体 |
| P7-PUB-03 | 演示数据只读 | 仅静态检查 | 公开模式演示数据为只读接口；本轮未对演示数据发起写操作 |
| P7-PUB-04 | 私有数据/AI 配置/调用历史/管理接口不可访问 | 已实际验证 | 管理路径与 AI 治理接口 Nginx/后端均 404；AI 生成 403 |
| P7-PUB-05 | 会话创建限制、活跃上限、TTL、Cookie 删除不可恢复 | 部分验证 | TTL 清理链路已实测（E-19）；Cookie 删除后无法恢复由 401 无会话路径与 Cookie 属性佐证；活跃上限未压测 |
| P7-PUB-06 | 上传/行数/存储/任务/导出/图表配额 | 部分验证 | 数据集数量配额、文件大小上限已实测；行数/列数/任务/导出/图表配额仅单元测试 |
| P7-PUB-07 | 清理任务不误删私有数据 | 已实际验证 | 仅清理过期匿名会话的数据（数据集 1 个、动态表 1 张），活跃会话数据保留 |
| P7-PUB-08 | 孤儿表只上报不删除 | 已实际验证 | 清理日志 `orphan=0`；代码路径仅计数上报，无自动 DROP |

### 3.4 恶意文件扫描与上传（对应第四节）

| 编号 | 验收项 | 状态 | 说明 |
|---|---|---|---|
| P7-SCAN-01 | 可运行的真实扫描服务（非空实现） | 已实际验证 | ClamAV 1.5.5 + Node 扫描网关真实运行，`/health` 返回 `{"status":"UP"}` |
| P7-SCAN-02 | 扫描通过 CSV/Excel 可导入 | 已实际验证（CSV） | 正常 CSV 上传 200，落库 3 行 2 列；Excel 未实测 |
| P7-SCAN-03 | 恶意样本被拒绝 | 已实际验证 | EICAR 原始样本（文件名伪装 .csv）→ 400「文件未通过安全检测」 |
| P7-SCAN-04 | 扫描超时/失败/不可用默认拒绝并清理临时文件 | 已实际验证 | scanner 停止时上传 503 fail-closed；上传临时目录残留 0 |
| P7-SCAN-05 | 上传目录不可执行、不可静态访问 | 仅静态检查 | Nginx 无 uploads 静态路由；后端未注册静态资源 |
| P7-SCAN-06 | 错误响应不泄露路径/扫描信息/内容 | 已实际验证 | 拒绝响应仅统一文案；`grep` 路径/组件/密钥关键字无命中 |
| P7-SCAN-07 | CSV 导出公式注入防护 | 仅静态检查 | 单元测试覆盖；未做导出文件内容复验 |

### 3.5 AI 运行验证（对应第五节）

| 编号 | 验收项 | 状态 | 说明 |
|---|---|---|---|
| P7-AI-01 | 公开模式默认关闭时全部受控拒绝 | 已实际验证 | `POST /api/ai/generate` → `403 公开演示环境暂未开放 AI 分析` |
| P7-AI-02 | 场景白名单、PII 脱敏 | 仅静态检查 | `AiPiiMaskerTest` 等通过；未在受限开启配置下实测 |
| P7-AI-03 | 会话/IP/全站配额、并发、单次超时 | 未验证 | 需受限 AI 测试配置 |
| P7-AI-04 | 供应商 429/超时/失败熔断与降级 | 未验证 | 需受限 AI 测试配置 + 桩供应商 |
| P7-AI-05 | 预算耗尽返回 429/503 | 未验证 | 需受限 AI 测试配置 |
| P7-AI-06 | 密钥/供应商地址/Prompt/完整响应不泄露 | 仅静态检查 | 审计字段与日志脱敏审查；未运行时抓包 |
| P7-AI-07 | 私有 AI 管理、路由、主备、审计、洞察 | 未验证 | 需私有模式实测 |
| P7-AI-08 | 公开用户无法访问模型配置/审计/成本 | 已实际验证 | `/api/ai/governance/config` 等治理接口 404 |

### 3.6 监控、告警与恢复演练（对应第六节）

| 编号 | 验收项 | 状态 | 说明 |
|---|---|---|---|
| P7-MON-01 | 监控抓取实际成功 | 已实际验证 | backend `up=1`、blackbox nginx/backend `probe_success=1` |
| P7-MON-02 | 活跃匿名会话告警 | 仅静态检查 | `dc_alert_active{rule="session_capacity"}`；未压到阈值 |
| P7-MON-03 | 429/5xx 比例告警 | 仅静态检查 | 规则已加载；未制造持续 5 分钟 5xx |
| P7-MON-04 | 上传扫描失败告警 | 已实际验证 | scanner 停机触发 `DataCleanScannerDown(P1)` firing |
| P7-MON-05 | 清理任务失败告警 | 仅静态检查 | 规则已加载；清理实跑无失败 |
| P7-MON-06 | 孤儿表数量告警 | 仅静态检查 | `DataCleanOrphanTables`（P2，仅上报） |
| P7-MON-07 | 上传卷容量告警 | 仅静态检查 | `DataCleanUploadVolume` |
| P7-MON-08 | MySQL 连接池告警 | 仅静态检查 | `DataCleanDbPool` |
| P7-MON-09 | AI 调用/预算/熔断 | 仅静态检查 | AI 关闭，未触发 |
| P7-MON-10 | 容器健康状态 | 已实际验证 | 五容器 healthcheck 全 healthy；blackbox 三目标探测生效；`DataCleanHealthProbeFailed(P0)` 真实触发 |
| P7-MON-11 | 告警实际触发 | 已实际验证（Prometheus 内） | 14 条规则加载；停机 2 分钟两条告警 firing；**未接 Alertmanager/通知渠道** |
| P7-MON-12 | 日志滚动/脱敏/保留/查询 | 仅静态检查 | logback 50MB×14 天×1GB；容器 json-file 轮转 |
| P7-MON-13 | 数据库+上传卷+迁移状态备份恢复演练 | 已实际验证 | dump 恢复 41 表与 live 一致；上传卷 tar 归档/解包成功 |
| P7-MON-14 | 发布失败可回退应用版本 | 未验证 | 回退步骤见 7.5；镜像仅有 `:1` 标签，未做版本回退演练 |

---

## 4. 本轮修复与新增（静态可验证部分）

| 编号 | 文件 | 变更 | 原因 |
|---|---|---|---|
| F-01 | `deploy/docker-compose.https.yml` | 模板挂载为 `/etc/nginx/https.conf.template` 并设置 `NGINX_TEMPLATE` | 原挂载路径不含 `https`，渲染脚本的「真实域名 + 证书可读」强校验被绕过，可能以 `_` 或错误域名启动 |
| F-02 | `docker-compose.yml` | `app_net` 固定 `172.30.0.0/24`；`TRUSTED_PROXY_CIDRS` 默认改为该网段 | 原默认仅回环，Nginx 容器源地址不被信任，真实客户端 IP 链路失效，限流与审计会退化为代理 IP |
| F-03 | `docker-compose.yml` | 注入 `AI_GOVERNANCE_ENABLED`、`AI_AUDIT_PEPPER` | 部署文档要求生产配置审计盐，但 Compose 未透传 |
| F-04 | `deploy/docker-compose.scan.yml`、`deploy/scan-adapter/*` | 新增 ClamAV + HTTP 扫描网关叠加栈 | 第四节要求真实扫描服务；适配后端既有 multipart 契约，fail-closed |
| F-05 | `deploy/docker-compose.monitoring.yml`、`deploy/monitoring/blackbox.yml` | 新增 Prometheus + blackbox-exporter 叠加栈 | 原监控只有配置片段，无运行编排 |
| F-06 | `deploy/monitoring/prometheus.yml` | 新增 blackbox 探测作业（nginx/backend/scanner） | 覆盖容器健康状态 |
| F-07 | `deploy/monitoring/alerts.yml` | 新增 `DataCleanHealthProbeFailed`、`DataCleanScannerDown`、`DataCleanAiFailureRate` | 补齐扫描不可用、容器健康、AI 调用失败率覆盖 |
| F-08 | `deploy/nginx/default.conf` | 删除 | 旧的非模板配置未被任何构建/挂载引用，且安全头与限流落后，存在误改风险 |
| F-09 | `.env.example`、`.env.public.example` | 补充受信代理网段、AI 审计盐、监控与扫描参数 | 与 Compose 默认值对齐 |
| F-10 | `deploy/README.md` | 修正过期引用，新增第十三节完整预发栈启动方式与门禁说明 | 运维可执行性 |
| F-11 | `deploy/nginx/default.https.conf.template`、`deploy/docker-compose.https.yml`、`deploy/monitoring/prometheus.yml` | 新增仅容器内可达的 8080 健康端口，Nginx 健康检查与 blackbox 探测改走该端口 | 80 端口在全量跳转后返回 301，原健康检查会跟随跳转失败 |
| F-12 | `deploy/nginx/default.https.conf.template`、`deploy/nginx/default.conf.template` | `location /assets/` 补充 `root /usr/share/nginx/html;` | server 级未设置 root，静态资源请求命中默认 root 返回 404，前端白屏（E-22 外部验收时发现并修复） |
| F-13 | `frontend/src/router/index.js` | 路由守卫首屏先 `await loadAppMode()` 再判定模式 | `app.use(router)` 会立即触发首次导航，此时 `/api/public/config` 尚未返回，公开模式被误判为私有模式并跳转 `/login` |

> 以上均为配置/文档与独立扫描网关变更；未修改 Java 业务代码。

---

## 5. 遗留风险与待确认项（发布前必须处理）

| 编号 | 级别 | 问题 | 处理建议 |
|---|---|---|---|
| R-01 | ~~P0~~ 已关闭 | 外部 80/443 网络验收已通过（安全组放行 443 后复验） | 真实客户端 IP 在应用侧日志建议再做一次抓包复核 |
| R-02 | P1 | `NGINX_CLIENT_MAX_BODY_SIZE` 默认 `25m`，后端 `UPLOAD_MAX_REQUEST_SIZE` 默认 `200MB` | 公开模式 20MB 不受影响；私有大文件上传需同时提高两者，发布前明确取值并在压测中验证 |
| R-03 | P1 | ClamAV 镜像标签使用 `stable`（漂移） | 发布前固定具体小版本并记录镜像 digest |
| R-04 | P1 | 告警仅能在 Prometheus 内触发，未配置 Alertmanager 与通知渠道 | 预发验证时接入实际通知渠道（邮件/IM/webhook），并做一次真实触发 |
| R-05 | P1 | 扫描网关为新组件 | 已完成正常/失败/EICAR/超大文件验证；并发上传与 clamd 中途崩溃场景未压测 |
| R-06 | P2 | MySQL 仅通过 `/api/health` 间接反映，无进程级指标 | 如需精细告警，增加 mysqld-exporter 与连接数指标 |
| R-07 | P2 | 备份恢复脚本已演练，但未验证跨机恢复 | 建议在独立主机按第 6.7 节完整恢复一次并记录耗时 |
| R-08 | P2 | `deploy/certs/` 需人工放置证书 | 证书来源与到期监控需运维确认 |
| R-09 | **P0（生产）** | 预发主机仅 1.87GB 内存：ClamAV 加载完整 `main.cvd` 会触发 OOM，当前仅加载 `daily.cvd`；监控栈与扫描栈无法长期并存 | 生产主机建议 ≥4GB 内存（或独立扫描节点）；恢复完整病毒库与 freshclam 自动更新后复验 |
| R-10 | P1 | ClamAV 容器内 DNS 无法解析 `database.clamav.net`，已改为宿主机预置病毒库 + `CLAMAV_NO_FRESHCLAMD=true` | 生产需确认容器 DNS/出口策略，或建立病毒库同步任务并监控库龄 |
| R-11 | P1 | 监控（Prometheus/blackbox）验证后因内存不足已停止 | 扩容后在预发保持常驻，确认 15 天以上保留与告警稳定性 |
| R-12 | P1 | AI 运行时（配额/熔断/降级/预算）未在受限配置下验证 | 若要开放 AI，必须完成第五节全部运行时项 |

---

## 6. 预发验收执行清单（必须在真实预发环境完成）

> 已在 2026-11-22 阿里云 ECS 预发实例执行本节主要步骤，实测结果与证据见第 2.1 节；仍标记为未执行的条目（外部网络、AI 受限配置、Alertmanager、版本回退）需继续补齐。
> 建议在独立预发域名与独立数据库上执行；所有命令输出、响应头、截图归档到验收证据目录。禁止在本清单执行期间操作生产流量或真实数据。

### 6.1 启动完整栈

```bash
cp .env.public.example .env
# 填写：MYSQL_*、JWT_SECRET、AI_SECRET_KEY、AI_AUDIT_PEPPER、PUBLIC_ALLOWED_ORIGIN、
#       PUBLIC_IP_HASH_PEPPER、PUBLIC_SERVER_NAME、UPLOAD_SCAN_PROVIDER=http、
#       UPLOAD_SCAN_URL=http://scanner:8080/scan、TRUSTED_PROXY_CIDRS=172.30.0.0/24
docker compose -f docker-compose.yml -f deploy/docker-compose.https.yml \
  -f deploy/docker-compose.scan.yml -f deploy/docker-compose.monitoring.yml \
  config --quiet
docker compose -f docker-compose.yml -f deploy/docker-compose.https.yml \
  -f deploy/docker-compose.scan.yml -f deploy/docker-compose.monitoring.yml \
  up -d --build
docker compose ps
```

通过标准：mysql/backend/nginx/scanner/clamav/prometheus 均为 healthy，migrate 退出码 0。

### 6.2 网络与 TLS（外部执行）

```bash
curl -sSI http://<预发域名>/ | head -20                 # 期望 301 到 https
curl -sSI https://<预发域名>/ | head -30                # HSTS/CSP/安全头
openssl s_client -connect <预发域名>:443 -tls1_1 </dev/null | grep -i protocol   # 期望失败
openssl s_client -connect <预发域名>:443 -tls1_2 </dev/null | grep -i protocol   # 期望成功
curl -sS -o /dev/null -w '%{http_code}\n' https://<预发域名>/api/health          # 期望 404
curl -sS -o /dev/null -w '%{http_code}\n' https://<预发域名>/api/admin/maintenance/cleanup -X POST  # 期望 404
curl -sS -o /dev/null -w '%{http_code}\n' https://<预发域名>/actuator/prometheus # 期望 404
curl -sS -o /dev/null -w '%{http_code}\n' -H 'Host: evil.example.com' https://<预发域名>/  # 期望连接被拒/444
```

外部端口扫描（从非预发网络）：仅 `80/443` open；`8080/8081/3306/9090` 均 closed。真实 IP 链路：在 backend 日志或审计中确认记录的是客户端公网 IP，而非 Nginx 容器地址；伪造 `X-Forwarded-For` 不改变限流主体。

### 6.3 Cookie 与公开模式

- 浏览器 DevTools 确认 `__Host-dc_anon`：`Secure; HttpOnly; SameSite=Strict; Path=/`，无 `Domain`。
- 两个独立浏览器（或普通+无痕）建立匿名会话，交叉访问数据集/清洗/导出/图表，全部 404/403。
- 删除 Cookie 后旧会话不可恢复；TTL 到期后会话与数据被清理。
- 演示数据只读；私有 AI 配置、调用历史、治理接口均不可访问。

### 6.4 上传与扫描

```bash
curl -sS -F 'file=@sample.csv' -b cookies.txt https://<预发域名>/api/dataset/upload        # 期望成功
curl -sS -F 'file=@eicar.com'  -b cookies.txt https://<预发域名>/api/dataset/upload        # 期望 400/拒绝
docker compose stop clamav                                                                  # 期望上传返回 503 且临时文件被清理
```

另外验证：超大文件被 Nginx 拒绝、扫描超时被拒绝、错误响应不含路径与扫描服务信息、上传目录无法通过 URL 访问、导出 CSV 中 `=cmd` 等公式被转义。

### 6.5 AI（受限测试配置）

1. 保持 `PUBLIC_AI_ENABLED=false`，确认所有 AI 入口受控拒绝。
2. 临时开启 AI，配置测试供应商与场景白名单，验证：会话/IP/全站配额、并发 1、超时、429/失败熔断与降级、预算耗尽返回 429/503。
3. 抓包与审计核对：响应与日志不含 Key、供应商地址、完整 Prompt、完整响应。

### 6.6 监控与告警

```bash
curl -sS http://127.0.0.1:9090/api/v1/targets | head -c 2000    # 所有 target up
curl -sS http://127.0.0.1:9090/api/v1/alerts | head -c 2000     # 规则加载正常
```

逐项制造触发条件（停 clamav、压测制造 5xx/429、填满上传卷测试配额、停止 backend），确认告警进入 firing 并送达通知渠道；记录恢复时间。

### 6.7 备份与恢复演练（预发，不触碰生产）

1. 记录 `schema_migration` 最大版本与关键表行数。
2. `mysqldump` 全量备份 + 上传卷 `tar` 备份 + 记录镜像标签。
3. 在隔离库/隔离卷恢复，校验迁移版本、表数量、关键行数与抽样校验和。
4. 记录恢复耗时与失败点；整个过程不删除现有预发数据。

### 6.8 应用版本回退演练

使用上一版本镜像标签重新 `up -d backend`，确认健康检查与核心冒烟通过；结构回滚仅在备份完成后评估，禁止未备份执行 `down -v`。

---

## 7. 生产发布方案（待预发验收通过并经用户单独确认后执行）

### 7.1 发布前检查表

- [ ] 第 6 节全部 P0/P1 项「已实际验证」，证据归档
- [ ] 预发与生产配置差异清单已评审（域名、密钥、配额、扫描地址）
- [ ] 生产 `.env` 由密钥管理注入，仓库与镜像无明文
- [ ] 证书有效且到期监控已配置
- [ ] 安全组仅 80/443，SSH 仅密钥 + 可信来源
- [ ] 全量备份已完成并校验可读（数据库、上传卷、迁移版本、镜像标签）
- [ ] 监控看板与告警通知渠道可用，值班人已确认
- [ ] 回滚镜像/备份路径已确认，回滚责任人已指定
- [ ] 变更单/维护窗口已审批

### 7.2 正式发布步骤（严格顺序）

1. **备份**：数据库全量 + 上传卷快照 + 记录当前镜像与迁移版本。
2. **迁移**：`docker compose run --rm migrate`，确认退出码 0 与 `schema_migration` 版本。
3. **灰度或维护窗口**：当前为单机 Compose，优先维护窗口；如需灰度，新增新版本 backend 容器，Nginx upstream 按权重切换（10% → 50% → 100%）。
4. **健康检查**：`/nginx-health`、`/api/health`（内网）、容器 `ps` 全部 healthy。
5. **核心冒烟**：会话创建 → 上传 → 预览 → 清洗预览 → 图表 → 导出 CSV → 清理任务状态。
6. **监控观察**：见 7.3。
7. **达标放量或回滚**：达阈值继续，触发任一阈值立即停止放量并按 7.5 回滚。

### 7.3 观察时长与回滚阈值

| 阶段 | 最短观察 | 回滚阈值（任一触发） |
|---|---|---|
| 灰度 10% | 30 分钟 | 5xx > 2%（5 分钟）、健康探测失败 2 分钟、扫描不可用 2 分钟 |
| 灰度 50% | 30 分钟 | 同上 + 429 > 5%（10 分钟）、AI 失败率 > 50%（10 分钟） |
| 全量 | 24 小时 | 清理任务失败、孤儿表异常增长、私有数据异常访问、密钥/敏感信息泄露迹象 |

补充：错误预算耗尽、上传卷使用率 > 85%、数据库连接池 > 90%、会话容量 > 80% 均需人工评估是否回滚。

### 7.4 健康检查与冒烟命令

```bash
curl -fsS https://<域名>/nginx-health
docker compose exec -T backend wget -q -O - http://127.0.0.1:8080/api/health
docker compose ps
docker compose logs --tail=200 backend
```

冒烟通过标准：会话建立成功、上传/预览成功、清洗预览成功且未自动覆盖数据、图表生成成功、CSV 导出成功、管理端不可从公网访问。

### 7.5 回滚步骤

1. 停止放量（Nginx 权重回 0 或恢复维护页）。
2. 应用回滚：切回上一版本镜像标签，`docker compose up -d backend nginx`，复验健康与冒烟。
3. 数据回滚：仅在必要时按备份恢复；先停写（`stop nginx backend`），恢复数据库与上传卷，再启动。
4. 结构回滚：必须先完成备份，并明确破坏性影响（如 `R9`/`R10` 删除本阶段表）；禁止未备份执行 `down -v`。
5. 回滚后核对：迁移版本、关键表行数、上传文件数量、审计记录完整性。
6. 输出事故记录与后续修复项。

### 7.6 运维确认项（需签字/回复确认）

- [ ] 已确认本次不切换生产流量，等待单独指令
- [ ] 已确认备份位置、保留期与访问权限
- [ ] 已确认证书来源、到期时间与续期方式
- [ ] 已确认告警通知渠道与值班人
- [ ] 已确认回滚镜像标签与操作人
- [ ] 已确认维护窗口时间与用户通知方式
- [ ] 已确认无破坏性操作（删除真实数据、未备份回滚）

---

## 8. 最终判定

- 第七阶段**静态准备已完成**：部署叠加栈、监控与扫描编排、门禁与回滚方案已就绪。
- 第七阶段**运行时验收已在阿里云 ECS 预发环境完成主体部分**：核心栈、容器内 HTTPS/TLS/安全头、公开模式隔离、配额、扫描链路（fail-closed / 正常 CSV / EICAR）、TTL 清理、监控告警、备份恢复均已实际验证（见 E-09～E-21）。
- 第七阶段**仍有阻塞项未关闭**：
  1. AI 运行时（配额/熔断/降级/预算）未在受限配置下验证（当前 AI 关闭，受控拒绝已验证）；
  2. Alertmanager/通知渠道未接入；
  3. 应用版本回退演练未执行；
  4. 生产主机内存需 ≥4GB，才能加载完整病毒库并常驻监控（R-09/R-11）。
- 因此：**仍不满足生产发布门禁，不建议进入正式发布。**
- 下一步：扩容或调整扫描/监控部署 → 受限配置验证 AI → 接告警通知 → 版本回退演练 → 回填本报告并归档证据，再提交生产发布确认。
