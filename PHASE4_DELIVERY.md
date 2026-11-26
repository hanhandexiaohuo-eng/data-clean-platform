# 第四阶段交付说明：生产发布前运行验证与网关加固

> 本文档只记录脱敏结果。真实密码、Cookie、Token、数据库连接串、证书私钥、AI Key 与访问令牌均未写入本文档、脚本输出或提交内容。
> 未切换任何真实生产流量，未执行破坏性回滚，未删除真实数据或上传卷。

## 1. 交付结论

第四阶段目标为「生产发布前的运行验证与网关加固」。本机环境可执行的部分均已实际执行并通过：

| 维度 | 结论 |
|---|---|
| 代码与构建 | 后端 85 个单测全部通过；`mvn package` 成功；前端 `vite build` 成功 |
| 敏感信息 | `scripts/scan-secrets.mjs` 未发现疑似明文凭据 |
| 网关静态边界 | Compose/Nginx 静态校验 `PASS=29 FAIL=0` |
| Nginx + TLS 实测 | 真实 Windows Nginx 1.27.4 + 自签名证书，`PASS=42 FAIL=0` |
| 扫描链路与指标实测 | 真实后端 + 隔离 MySQL + 模拟扫描服务，`PASS=22 FAIL=0` |
| 数据库迁移 | V1–V8 已按顺序应用于隔离库（临时实例） |

**结论：本阶段可以生成「预发发布建议」，但不能据此认定生产可用。** 以下项目受本机环境限制未实际验证，必须在预发/生产环境补齐后才能批准发布：

1. `docker compose up` 真实容器编排、容器网络隔离与端口暴露；
2. 公网侧端口扫描、真实域名/证书、真实 DNS 与外部访问链路；
3. 接入真实恶意文件扫描产品（本阶段使用受控模拟服务验证协议与失败关闭）；
4. AI 开启后的配额 429 与熔断 503 运行时验证（本阶段仅验证默认关闭 403）；
5. Prometheus 实际抓取与告警规则触发。

发布动作需要在上述项目补齐后由用户单独确认；本文档不构成发布授权。

## 2. 验证环境与限制

- 本机 Windows，无 Docker、无 Nginx、无 OpenSSL、无管理员权限，无法启动容器编排或修改宿主防火墙。
- Nginx 验收使用脚本下载的官方 Windows 版 `nginx/1.27.4`，监听测试端口（HTTP 18080 / HTTPS 18443），配合 `keytool` 生成的自签名证书与桩后端。
- 后端验收使用隔离实例：`APP_MODE=public`、`SERVER_PORT=19080`、`MANAGEMENT_PORT=19081`、隔离 MySQL `127.0.0.1:13306`、隔离上传目录、`UPLOAD_SCAN_PROVIDER=http` 指向本机模拟扫描服务。
- 隔离库与上传目录均位于临时工作区，未连接生产库、未触碰生产上传卷。
- 本机 `npm` 安装损坏，前端构建通过直接调用 `node node_modules/vite/bin/vite.js build` 完成，构建结果与 `npm run build` 等价。

## 3. 修改文件清单

### 3.1 后端新增

| 文件 | 作用 |
|---|---|
| `backend/src/main/java/com/dataclean/config/NetworkSecurityProperties.java` | `app.network.trusted-proxies` 绑定，默认仅回环 |
| `backend/src/main/java/com/dataclean/config/ScanProperties.java` | `upload.scan.*`（provider/required/url/timeout/响应上限/token） |
| `backend/src/main/java/com/dataclean/config/MonitoringProperties.java` | 采集周期、容量与告警阈值 |
| `backend/src/main/java/com/dataclean/util/ClientIpResolver.java` | 仅受信代理采信 `X-Real-IP`，`X-Forwarded-For` 取最右跳，非法值回退 TCP 源地址 |
| `backend/src/main/java/com/dataclean/service/HttpFileScanService.java` | HTTP 恶意文件扫描，流式上传，失败/超时/不可用/检出全部拒绝 |
| `backend/src/main/java/com/dataclean/metrics/RuntimeMetrics.java` | 会话/上传/AI/清理/HTTP 状态/容量/连接池/孤儿表指标与告警评估 |
| `backend/src/main/java/com/dataclean/filter/HttpStatusMetricsFilter.java` | 最高优先级统计 429/4xx/5xx |
| `backend/src/test/java/com/dataclean/util/ClientIpResolverTest.java` | 伪造头、非法值、IPv6 等 5 个用例 |
| `backend/src/test/java/com/dataclean/service/HttpFileScanServiceTest.java` | 通过/检出/超时/不可用/5xx/歧义/超大响应/空地址 8 个用例 |

### 3.2 后端修改

| 文件 | 变更 |
|---|---|
| `backend/pom.xml` | 增加 `spring-boot-starter-actuator`、`micrometer-registry-prometheus` |
| `backend/src/main/resources/application.yml` | `server.forward-headers-strategy: none`；扫描/网络/监控/管理端口配置；`show-details: never` |
| `backend/src/main/resources/application.yml.example` | 同步新增配置项（不含密钥） |
| `config/PublicModeConfig.java` | 注册新增配置属性类 |
| `config/PublicModeStartupValidator.java` | 新增 `validateUploadScan()`：public 模式必须 `provider=http` 且地址非空 |
| `service/AnonymousSessionService.java` | 使用可信 IP 解析与指标计数 |
| `service/DatasetService.java` | 上传成功/拒绝/扫描失败分类计数，扫描异常不落原始异常信息 |
| `service/PublicAiService.java` | AI 调用成功/失败/限流/熔断/Token 计数 |
| `service/PublicCleanupService.java` | 清理耗时、失败数、孤儿表指标 |
| `mapper/DatasetMapper.java` | `countDynamicTables()` 统计 `data!_%` 动态表 |
| `filter/AnonymousSessionFilter.java` | 放行 `/actuator/**`（仅绑定内网管理端口），公网入口仍由 Nginx 返回 404 |

### 3.3 部署与网关

| 文件 | 变更 |
|---|---|
| `deploy/nginx/default.conf.template` | 日志格式去除 `$remote_addr` 与查询串、`error_log crit`、CSP/HSTS 等安全头、429 状态、502/504 与 404 JSON 脱敏、`/actuator/` 与 `/api/ops/` 404 |
| `deploy/nginx/default.https.conf.template` | HTTP 301、TLS1.2/1.3、HSTS、非法 Host 返回 444、端口 80 访问日志补齐 |
| `deploy/nginx/docker-entrypoint.d/40-render-nginx-conf.sh` | HTTPS 模板强制域名非 `_` 且证书可读，否则退出 1 |
| `docker-compose.yml` | backend 透传扫描/受信代理/监控变量并 `expose` 8080/8081；nginx 注入 `NGINX_SERVER_NAME` |
| `deploy/docker-compose.https.yml` | 443 映射、证书与 HTTPS 模板只读挂载 |
| `.env.public.example` | 扫描、受信代理、管理端口、监控与告警阈值、端口变量模板 |
| `deploy/monitoring/prometheus.yml` | 抓取 `backend:8081/actuator/prometheus` |
| `deploy/monitoring/alerts.yml` | 5xx/429、后端存活、容量、扫描失败、清理失败、孤儿表、AI 预算/熔断告警 |

### 3.4 验收脚本

| 文件 | 作用 |
|---|---|
| `scripts/tools/phase4_validate_compose.py` | Compose/Nginx 静态边界断言（端口、network internal、只读挂载、envsubst 白名单、模板变量、忽略规则） |
| `scripts/tools/phase4_stub_backend.py` | Nginx 验收桩后端（正常、502、Cookie 回显） |
| `scripts/tools/PemCertExporter.java` | PKCS12 → PEM 证书导出 |
| `scripts/tools/phase4_mock_scanner.py` | 受控模拟扫描服务（通过/检出/超时/异常/不可用），不落日志 |
| `scripts/phase4-nginx-tls-check.ps1` | 真实 Nginx + TLS 42 项检查 |
| `scripts/phase4-scan-runtime-check.ps1` | 真实后端扫描链路、Cookie、伪造头、指标 22 项检查 |

## 4. 预发拓扑与配置要点

```
Internet ── 80/443 ──> nginx（唯一公网入口）
                         └─ proxy_pass ──> backend:8080（仅内网 expose）
                                            ├─ mysql:3306（internal 网络，无端口映射）
                                            └─ 扫描服务（受控地址，非公网）
backend:8081（管理端口，仅内网；Nginx 对 /actuator/ 返回 404）
```

- 仅映射 `80`/`443`；`backend` 与 `mysql` 不发布宿主机端口，`db` 网络 `internal: true`。
- 预发使用独立的数据库、上传卷、域名、证书与环境变量；`.env`、证书、上传卷、备份目录均不入镜像与仓库。
- `docker compose config` 已静态核对：无额外端口、db 网络 internal、Nginx 仅白名单变量、证书只读。
- 迁移前必须备份数据库与上传卷，并验证可恢复。

关键环境变量（模板见 `.env.public.example`）：

| 变量 | 说明 |
|---|---|
| `PUBLIC_ALLOWED_ORIGIN` / `CORS_ALLOWED_ORIGINS` | 必须与浏览器地址栏完全一致（含 `https://`） |
| `PUBLIC_IP_HASH_PEPPER` | ≥32 字符，且与 `JWT_SECRET` 不同 |
| `TRUSTED_PROXY_CIDRS` | 仅填写 Nginx 所在网段；直连客户端不采信代理头 |
| `UPLOAD_SCAN_PROVIDER=http` / `UPLOAD_SCAN_URL` | public 模式必填，缺失拒绝启动 |
| `MANAGEMENT_PORT` | 默认 8081，仅内网 |
| `MONITORING_*` / `ALERT_*` | 采集周期与告警阈值 |

## 5. HTTPS / Nginx / 网络边界（已实际验证部分）

真实 Nginx 1.27.4 + 自签名证书，测试结果 `TOTAL=42 PASS=42 FAIL=0`：

| 验证项 | 结果 |
|---|---|
| HTTP 80 → HTTPS 301（保留路径与查询串） | 通过 |
| TLS 1.2 / 1.3 可握手，TLS 1.0/1.1 被拒 | 通过 |
| HSTS、CSP、`X-Content-Type-Options`、`X-Frame-Options`、版本隐藏 | 通过 |
| Cookie `__Host-dc_anon; HttpOnly; Secure; SameSite=Strict; Path=/` 且无 Domain | 通过（属性透传） |
| 渲染后 `$uri/$host/$remote_addr/$proxy_add_x_forwarded_for` 非空 | 通过 |
| 非法 Host 返回 444 | 通过 |
| `/actuator/`、`/api/ops/` 外部 404 | 通过 |
| 502/504 与 404 返回脱敏 JSON | 通过 |
| 超大请求体（30MB）返回 413 | 通过 |
| 上传接口超频返回 429 | 通过 |
| 静态资源长缓存并保留 HSTS | 通过 |
| 访问日志不含来源 IP 明文与查询串敏感值 | 通过 |
| 仅监听测试的 HTTP/HTTPS 端口 | 通过 |

应用侧补充：

- `server.forward-headers-strategy: none`：应用不自行解析 `X-Forwarded-*`，完全依赖 `ClientIpResolver`。
- `ClientIpResolver`：直连地址不在 `TRUSTED_PROXY_CIDRS` 内时忽略一切代理头；受信时仅采信 `X-Real-IP`，`X-Forwarded-For` 仅取最右跳并校验为字面量 IP。
- 实测：直接向应用发送伪造 `X-Real-IP` 无法绕过单 IP 活跃上限，第 4 个会话返回 429。

**未验证（环境限制）**：真实公网端口扫描、外部网络直连 backend/MySQL 的连通性探测、真实证书链与域名解析。

## 6. 恶意文件扫描

- 扫描提供方由 `UPLOAD_SCAN_PROVIDER` 选择；public 模式下必须为 `http` 且地址非空，否则拒绝启动。
- `HttpFileScanService` 以 multipart 流式上传文件，仅接受 `200` 且响应体 `{"clean":true}`：
  - `clean=false` → `400 文件未通过安全检测，已拒绝上传`；
  - 非 200 / 响应不可解析 / 超时 / 连接失败 / 响应超限 → `503 文件安全检测暂不可用，请稍后重试`。
- 扫描发生在解析建表与持久化之前，失败默认拒绝；扫描服务不接触数据库凭据、AI Key、Cookie，也不读取临时目录之外的任何文件。
- 日志仅记录文件类型、大小与异常类型，不含扫描地址、Token 或样本内容。
- 上传目录不注册静态资源、不可执行；落盘文件名为服务端生成；绑定会话并按 TTL 清理。

实测（`PASS=22 FAIL=0`）：

| 场景 | 结果 |
|---|---|
| 正常 CSV 扫描通过并导入 | 200 |
| 扫描判定风险 | 400，响应不含扫描细节 |
| EICAR 样本 | 400 |
| 扫描超时 | 503 |
| 扫描服务 5xx | 503，响应不含内部地址 |
| 扫描服务不可用 | 503 |

**未验证（环境限制）**：真实杀毒/沙箱产品对接。当前协议与失败关闭行为已由受控模拟服务验证，接入真实产品后需按同一脚本再跑一轮。

## 7. 运行监控与告警

指标（`/actuator/prometheus`，仅内网 8081）：

- 会话：`dc_sessions_active`、`dc_session_created_total`、`dc_session_rejected_total{reason}`；
- 上传：`dc_upload_total{result=success|rejected|scan_failed}`、`dc_scan_failures_interval`；
- 流量：`dc_http_responses_total{status=429|4xx|5xx}`；
- AI：`dc_ai_calls_total{result}`、`dc_ai_tokens_estimated_total`、`dc_ai_budget_used/total`；
- 清理：`dc_cleanup_runs_total`、`dc_cleanup_failures_total`、`dc_cleanup_duration_seconds`、`dc_orphan_tables`；
- 容量：`dc_upload_volume_bytes/capacity_bytes`、`dc_dynamic_tables`、`dc_db_pool_active/max`；
- 告警状态：`dc_alert_active{rule}`。

告警规则见 `deploy/monitoring/alerts.yml`：5xx 比例、429 比例、后端存活、活跃会话容量、上传卷水位、连接池占用、扫描失败、清理失败、孤儿表、AI 预算与熔断。

日志脱敏：访问日志不含 IP 明文/Cookie/查询串；应用日志不记录 Cookie、Token、AI Key、数据库密码、完整 Prompt、完整响应或原始数据。

## 8. 测试与验收结果

| 项目 | 命令/入口 | 结果 |
|---|---|---|
| 后端单测 | `mvn test` | 通过：85 个测试，0 失败 0 错误 |
| 后端打包 | `mvn -DskipTests package` | 通过 |
| 前端构建 | `node node_modules/vite/bin/vite.js build` | 通过 |
| 敏感信息扫描 | `node scripts/scan-secrets.mjs` | 通过：未发现疑似明文凭据 |
| Compose/Nginx 静态校验 | `python scripts/tools/phase4_validate_compose.py` | `TOTAL=29 PASS=29 FAIL=0` |
| Nginx + TLS 实测 | `scripts/phase4-nginx-tls-check.ps1` | `TOTAL=42 PASS=42 FAIL=0` |
| 扫描链路与指标实测 | `scripts/phase4-scan-runtime-check.ps1` | `TOTAL=22 PASS=22 FAIL=0` |
| 隔离库迁移 | 按顺序应用 V1–V8 | 成功，库内表结构就绪 |
| 演示数据初始化 | 后端启动 | 演示数据集就绪（40 行） |

脱敏证据文件（临时工作区，不入仓库）：

- `phase4-nginx-tls.log`
- `phase4-scan-runtime.log`
- `phase4-it/dataclean.log`
- `phase4-it/run*.out.log`

## 9. 验收矩阵

| 编号 | 验收项 | 状态 | 证据/说明 |
|---|---|---|---|
| B1 | Nginx 唯一公网入口，仅映射 80/443 | 静态检查 | Compose 静态校验 29/29；容器运行需预发复验 |
| B2 | backend/MySQL 仅内网，db 网络 internal | 静态检查 | Compose 校验断言 |
| B3 | 独立 DB/上传卷/域名/证书/环境变量 | 静态检查 | Compose + 环境变量模板 |
| B4 | `.env`/证书/上传卷/备份不入镜像仓库 | 静态检查 | `.gitignore`/`.dockerignore` 断言 |
| B5 | 迁移前备份且可恢复 | 未验证 | 隔离库已演练，生产备份需运维执行 |
| B6 | HTTP 301 跳转 HTTPS | 已实际验证 | Nginx TLS 脚本 |
| B7 | 仅 TLS1.2/1.3、HSTS | 已实际验证 | Nginx TLS 脚本 |
| B8 | Cookie 属性与无 Domain | 已实际验证 | Nginx 透传 + 后端响应头断言 |
| B9 | 非 Secure Cookie 拒绝启动 | 已实际验证 | 第三阶段启动拒绝用例（沿用） |
| B10 | 运行期变量非空 | 已实际验证 | Nginx TLS 脚本 |
| B11 | 安全头/CSP/版本隐藏/缓存/体积/错误页脱敏 | 已实际验证 | Nginx TLS 脚本 |
| B12 | 外部无法直连 backend/MySQL、内部路径不可公网 | 静态检查 | Nginx 404 规则 + Compose 端口；公网探测未执行 |
| B13 | 非法 Host/跨源/超频/超大请求拒绝 | 已实际验证 | Nginx TLS 脚本 + 后端 Origin 校验 |
| B14 | 真实客户端 IP 链路可信、伪造代理头失效 | 已实际验证 | 扫描实测脚本（伪造 `X-Real-IP` 仍 429） |
| B15 | 扫描不可用时默认拒绝 | 已实际验证 | 扫描实测脚本（503） |
| B16 | 恶意/超时/5xx/EICAR 拒绝，正常文件可导入 | 已实际验证 | 扫描实测脚本 |
| B17 | 错误响应不泄露扫描地址/路径/样本 | 已实际验证 | 扫描实测脚本 |
| B18 | 指标覆盖会话/上传/AI/清理/容量/连接池 | 已实际验证 | `/actuator/prometheus` 断言 |
| B19 | 告警阈值可配置 | 静态检查 | `alerts.yml` + `MonitoringProperties` |
| B20 | 日志不含 Cookie/Token/IP/密钥/Prompt | 已实际验证 | 访问日志断言 + 代码审阅 |
| B21 | 运维诊断接口仅内网 | 已实际验证 | 8081 仅内网绑定；Nginx `/actuator/` 404 |
| B22 | Docker Compose 实际启动 | 未验证（环境限制） | 需预发执行 |
| B23 | 公网端口扫描 | 未验证（环境限制） | 需预发执行 |
| B24 | 真实扫描产品接入 | 未验证 | 模拟服务已验证协议 |
| B25 | AI 开启后配额/熔断运行时验证 | 未验证 | 本阶段仅验证默认关闭 403 |

## 10. 三轮自查记录

### 第一轮：网络暴露与真实 IP

发现并修复：

1. **管理端口被匿名会话过滤器拦截**：public 模式下 `AnonymousSessionFilter` 对 `/actuator/health` 返回 401，导致 Prometheus/健康检查不可用。已放行 `/actuator/**`，并确认其只绑定内网管理端口、Nginx 对公网 `/actuator/` 返回 404。
2. **Nginx 80 端口访问日志继承默认格式**：会记录来源 IP 明文。已在 HTTP server 显式指定脱敏 `log_format`。
3. **可信代理默认值**：默认仅回环，避免误把公网直连当作可信代理；实测受信列表不含回环时伪造 `X-Real-IP` 无效。

### 第二轮：敏感信息与错误响应

- `scan-secrets.mjs` 全量扫描通过。
- 扫描服务日志只记录类型/大小/异常类型；错误响应统一为固定文案，不含扫描地址、内部路径或样本。
- Nginx 502/504/404 返回固定 JSON；应用异常只记录类型。
- 验收脚本与日志不打印 Cookie 值、Token、密码或连接串。

### 第三轮：临时数据、恶意文件与 AI 成本

- 隔离库与上传目录均位于临时工作区；验收结束后停止测试进程，未触碰生产数据。
- 上传链路在解析/建表前完成扫描，四类失败（检出/超时/5xx/不可用）全部拒绝，正常 CSV 可导入。
- AI 默认关闭返回 403；配额与熔断路径保持原有四层限制，未在本阶段开启真实供应商，避免成本失控。

## 11. 风险项与未完成项

1. **容器编排未实跑**：无 Docker 环境，Compose 仅完成静态校验；容器网络隔离、只读挂载、健康检查需预发复验。
2. **公网边界未实测**：端口扫描、外部直连探测、真实证书链/域名解析未执行。
3. **扫描服务为模拟实现**：协议、超时与失败关闭已验证，但真实产品行为（响应格式、超大文件、并发）需接入后复测。
4. **AI 供应商链路未实测**：开启 AI 后的配额 429、熔断 503、Token 计量需在预发配置真实供应商后验证。
5. **告警规则未触发验证**：规则文件已就绪，需在预发接入 Prometheus/Alertmanager 后做一次人为触发。
6. **单实例限流**：并发信号量、指标与清理均为单实例语义，多副本需网关或 Redis 分布式限流。
7. **备份恢复未在生产演练**：隔离库已验证迁移脚本，生产备份/恢复需运维按手册执行并留存记录。

## 12. 部署前检查表

- [ ] 预发域名与证书就绪；`PUBLIC_ALLOWED_ORIGIN`、`CORS_ALLOWED_ORIGINS` 与浏览器地址完全一致。
- [ ] `.env` 中 `PUBLIC_IP_HASH_PEPPER` ≥32 字符且与 `JWT_SECRET` 不同；`AI_SECRET_KEY` 为 Base64 32 字节。
- [ ] `UPLOAD_SCAN_PROVIDER=http`、`UPLOAD_SCAN_URL` 指向受控扫描服务，且已完成一次真实样本验证。
- [ ] `TRUSTED_PROXY_CIDRS` 仅为 Nginx 网段，不含公网网段。
- [ ] `docker compose config` 无 80/443 之外端口；db 网络 internal；证书与模板只读挂载。
- [ ] 启动日志确认 `Cookie=__Host-dc_anon`、`上传扫描: provider=http`、`AI=off`（或按计划开启）。
- [ ] 预发抓取：80→443、TLS1.2/1.3、HSTS、CSP、Cookie 属性、`/actuator/` 与 `/api/ops/` 404。
- [ ] 宿主外部端口扫描：仅 80/443 开放，3306/8080/8081 不可达。
- [ ] 执行 `phase4_validate_compose.py`、`phase4-nginx-tls-check.ps1`、`phase4-scan-runtime-check.ps1` 并留存脱敏结果。
- [ ] 备份数据库与上传卷，并在隔离库演练恢复。
- [ ] 接入 Prometheus，确认抓取成功并人为触发一条告警。
- [ ] 确认日志中无 Cookie、Token、IP 明文、AI Key、数据库密码、完整 Prompt/响应。

## 13. 发布与回滚流程（需用户单独确认后执行）

1. **备份**：数据库全量备份 + 上传卷快照；记录备份校验值；在隔离库验证可恢复。
2. **迁移**：先执行迁移容器，确认 V1–V8 版本记录与表结构；失败立即停止。
3. **灰度/维护窗口**：按计划切换流量；Nginx 为唯一入口，backend 保持内网。
4. **健康检查**：`/api/health`、管理端口 `/actuator/health`、Nginx 443 握手与首页。
5. **冒烟**：会话创建、上传正常 CSV、恶意样本拒绝、演示数据只读、双会话隔离。
6. **监控**：观察 5xx、429、扫描失败、清理失败、上传卷容量与连接池。
7. **回滚阈值**：5xx 持续超阈值、扫描服务不可用、健康检查失败或关键冒烟失败时，立即停止放量并按备份恢复。
8. **回滚**：停止写入 → 恢复数据库与上传卷 → 回退镜像版本 → 复验健康检查与冒烟。禁止在未备份时执行 `docker compose down -v`。

## 14. 仍需运维确认的事项

1. 预发/生产域名、证书签发方式与 HSTS 有效期。
2. 真实恶意文件扫描产品选型、超时与降级策略，以及并发与文件大小上限。
3. 真实客户端 IP 链路：确认 Nginx 到 backend 的网段与 `TRUSTED_PROXY_CIDRS` 完全一致。
4. 容器资源配额、MySQL 连接池与 `PUBLIC_MAX_ACTIVE_SESSIONS` 的容量匹配。
5. Prometheus/Alertmanager 部署位置、告警接收人与静默策略。
6. 备份保留周期、恢复演练频率与责任人。
7. 是否需要在网关层叠加验证码/WAF。
