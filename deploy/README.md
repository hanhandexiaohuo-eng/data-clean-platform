# DataClean 部署与运维基线（第二阶段）

本目录/文件描述如何用 Docker Compose 部署、升级、备份、恢复与回滚 DataClean。
本阶段只建立可重复部署的运行基础：**不执行数据清洗/导入/回滚，不删除任何真实数据**。

## 一、运行拓扑与端口边界

| 服务 | 宿主机端口 | 容器端口 | 网络 | 说明 |
|---|---|---|---|---|
| `nginx` | `80`（`HTTP_PORT`） | `80` | `app_net` | 唯一对外入口，提供静态前端与 `/api/` 反代 |
| `backend` | **不发布** | `8080` | `app_net` + `db_net` | 仅能被 nginx 与迁移容器访问 |
| `mysql` | **不发布** | `3306` | `db_net` | 仅内部网络，禁止公网/宿主机直连 |
| `migrate` | 无 | 无监听 | `db_net` | 一次性迁移任务，成功后退出 |

- `app_net`：固定子网 `172.30.0.0/24` 的 bridge 网络，供 nginx ↔ backend 通信，并允许 backend 访问外部 AI 接口；`TRUSTED_PROXY_CIDRS` 默认与该子网一致（如冲突需两处同步修改）。
- `db_net`：`internal: true`，仅 mysql/backend/migrate，阻断数据库出网。
- 命名卷：`dataclean_mysql_data`、`dataclean_uploads`、`dataclean_logs`，容器重建不丢数据。
- 模板文件为 `deploy/nginx/default.conf.template`（HTTP）与 `deploy/nginx/default.https.conf.template`（HTTPS）；旧的非模板 `default.conf` 已删除，避免误改。
- 生产/预发完整栈的启动命令见第十三节。

## 二、首次部署

### 1. 准备环境变量

```bash
cp .env.example .env
# 编辑 .env，填入真实值；.env 已被 .gitignore 忽略，禁止提交或打包
```

必填项：`MYSQL_ROOT_PASSWORD`、`MYSQL_USER`、`MYSQL_PASSWORD`、`JWT_SECRET`、`AI_SECRET_KEY`。
生成示例（PowerShell）：

```powershell
# JWT_SECRET（>= 32 位）
-join ((48..57)+(65..90)+(97..122) | Get-Random -Count 48 | % {[char]$_})
# AI_SECRET_KEY（Base64 编码 32 字节）
[Convert]::ToBase64String((1..32 | % { Get-Random -Maximum 256 }))
```

### 2. 静态校验与构建

```bash
docker compose config --quiet          # 语法与变量校验
docker compose build                   # 构建 backend / nginx / migrate 镜像
```

### 3. 启动（单命令完成迁移 + 启动）

```bash
docker compose up -d
docker compose ps                      # 三个常驻服务应为 healthy
```

启动顺序由健康检查与依赖条件保证：
`mysql healthy` → `migrate` 执行并成功退出 → `backend healthy` → `nginx healthy`。

### 4. 验收

```bash
# 前端（同源）
curl -I http://127.0.0.1/
# API 反代 + 最小健康检查（仅回环/私网可访问）
curl -fsS http://127.0.0.1/api/health
# 宿主机不应能直连 backend / mysql
curl --connect-timeout 3 http://127.0.0.1:8080/api/health   # 应失败
# Windows: Test-NetConnection -ComputerName 127.0.0.1 -Port 3306  # 应为 False
```

预期 `/api/health` 仅返回：

```json
{"status":"UP","checks":{"database":"UP"}}
```

不包含数据库地址、账号、版本、堆栈或配置。

## 三、迁移机制

详见 [`db/README.md`](../db/README.md)。

- 迁移文件位于 `db/migration/V<数字>__<描述>.sql`，按数字顺序执行。
- `migrate` 服务通过 `schema_migration` 表记录版本、校验和、耗时与成功状态。
- **新库**：从 V1 开始执行全部迁移。
- **存量库首次接入**：自动识别基线（1=核心表；2=核心表+用户表），只执行基线之后的增量。
- **重复启动**：已成功版本直接跳过，不会重复执行。
- **失败处理**：记录 `success=0` 并终止启动链；迁移文件均为幂等增量，修复原因后可安全重试。
- **校验和**：已成功迁移文件内容变化会中止执行，防止历史迁移被篡改。
- 迁移全部为纯增量：只新增表/列/索引，不删除真实表、不删除业务数据、不无条件重建表。

手动单独执行迁移（例如升级前预演）：

```bash
docker compose run --rm migrate
```

## 四、升级部署

```bash
# 1. 备份（强烈建议，见第五节）
# 2. 更新代码/镜像
docker compose build
# 3. 启动；migrate 会先执行未完成的增量迁移
docker compose up -d
# 4. 观察
docker compose ps
docker compose logs --tail=100 migrate
docker compose logs --tail=100 backend
```

说明：

- 禁止修改已发布的迁移文件；新增变更必须新建更高版本迁移。
- 高版本应用回滚到旧版本前，先确认旧版本能兼容新增的可空列/新表；不能兼容时按第六节恢复备份。

## 五、备份与恢复

### 1. 备份检查项（每次迁移/升级前）

- [ ] 确认当前 `docker compose ps` 中 mysql 为 healthy；
- [ ] 确认备份目录剩余空间 ≥ 数据库大小 × 2；
- [ ] 数据库备份与 `AI_SECRET_KEY` **分开存放**（备份含密文与业务数据，密钥丢失将无法解密 AI Key）；
- [ ] 上传目录另行备份；
- [ ] 记录当前应用镜像标签/提交号与 `schema_migration` 版本。

### 2. 数据库备份（不中断写入）

```bash
mkdir -p backups
# Windows PowerShell 使用：$ts = Get-Date -Format 'yyyyMMdd_HHmmss'
docker compose exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_PASSWORD" mysqldump -u"$MYSQL_USER" --single-transaction --routines --triggers --set-gtid-purged=OFF "$MYSQL_DATABASE"' \
  > backups/dataclean_$(date +%Y%m%d_%H%M%S).sql
```

> 备份文件包含业务数据与 AI Key 密文，按敏感数据管理：限制权限、加密存放、禁止提交仓库。

### 3. 上传文件备份

```bash
docker run --rm -v dataclean_uploads:/data -v "$PWD/backups:/backup" alpine:3.20 \
  tar czf /backup/uploads_$(date +%Y%m%d_%H%M%S).tar.gz -C /data .
```

### 4. 恢复（破坏性，先停写）

```bash
docker compose stop nginx backend
# 先确认备份文件可读，并优先在临时库演练
docker compose exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE"' \
  < backups/dataclean_YYYYmmdd_HHMMSS.sql
docker compose start backend nginx
```

恢复演练建议：每季度在隔离的临时库中执行一次「备份 → 恢复 → 校验表数量与关键行数」，不触碰生产库。

## 六、回滚

1. **应用回滚**：切回上一版本代码/镜像后 `docker compose up -d`；先确认旧版本兼容当前表结构。
2. **迁移回滚**：
   - 优先恢复迁移前备份（最可靠）；
   - 纯新增列的迁移（V3/V6）可使用 `db/rollback/` 下脚本原地回滚，执行前确认无数据需要保留；
   - V4 索引回滚不涉及数据；
   - V5 回滚会删除 `async_task` 表及其任务记录，属破坏性操作，必须先备份；
   - V9 回滚（`db/rollback/R9__data_version_async_rollback.sql`）会删除版本、任务、审计、统计缓存与备份表登记元数据，**属破坏性操作**：先停止任务（`JOBS_ENABLED=false`）、导出 `audit_log/dataset_version/async_job` 并完成全量备份；历史版本物理表不会被删除，将变为孤儿表，只能人工核对后清理；
   - V1/V2 为基线建表，**不支持原地回滚**（会删除业务数据），只能恢复备份。
3. **禁止**在未备份的情况下执行 `docker compose down -v`；该命令会删除命名卷中的真实数据。

## 七、日志与故障排查

- 应用文件日志：`dataclean_logs` 卷内 `dataclean.log`，按天 + 50MB 滚动，保留 14 天、总容量 1GB（可通过 `LOG_*` 调整）。
- 容器 stdout：Compose 已配置 `json-file` 轮转（`max-size=10m`、`max-file=5`）。
- 常用命令：

```bash
docker compose logs -f backend
docker compose logs -f nginx
docker compose logs --tail=200 migrate
docker volume inspect dataclean_logs
```

- 日志与错误响应不输出数据库密码、Cookie、JWT、AI Key、原始敏感数据或完整 SQL 参数。
- 若 `migrate` 失败：查看其日志中的版本号，修复原因后重新 `docker compose up -d`；不要手工修改 `schema_migration` 历史记录。

## 八、第二阶段当时的边界（已被第三阶段覆盖）

以下条目描述第二阶段基线，第三阶段已在其上扩展，详见 [`PHASE3_DELIVERY.md`](../PHASE3_DELIVERY.md)：

- ~~未启用 HTTPS~~：第三阶段提供 `deploy/docker-compose.https.yml` 与 `default.https.conf.template`（TLS/HSTS）。
- ~~未启用 Nginx 限流~~：第三阶段模板已启用 `limit_req` 与请求体上限。
- ~~未实现访问令牌 Cookie~~：第三阶段为公开模式新增匿名会话 Cookie `__Host-dc_anon`（`Secure; HttpOnly; SameSite=Strict; Path=/`、无 Domain）；私有模式仍保留登录。
- `SSE /api/ai/generate/stream` 在公开模式返回 404，仅私有模式可用。

## 九、第三阶段（公开模式）部署要点

```bash
cp .env.public.example .env
# 必填：PUBLIC_ALLOWED_ORIGIN、PUBLIC_IP_HASH_PEPPER（≥32 位，且勿与 JWT_SECRET 相同）
docker compose config --quiet
docker compose build
docker compose up -d
```

- 公开模式要求 Cookie `__Host-dc_anon` + `Secure`：生产必须走 HTTPS（`docker compose -f docker-compose.yml -f deploy/docker-compose.https.yml up -d`），否则后端拒绝启动；启动时会强校验名称、`Secure`、`HttpOnly`、`SameSite=Strict`、`Path=/` 与无 `Domain`。
- 本地 HTTP 调试只使用 `SPRING_PROFILES_ACTIVE=local` 与 `backend/local/application-local.yml`（通过 `--spring.config.additional-location=file:./local/` 加载）；该文件不在 `resources` 下，不会进入镜像，也不要写入 `.env`。生产镜像内已确认不包含任何 `application-local.yml`。
- 会话创建为受控流程：唯一入口 `POST /api/public/session`；超阈值需一次性挑战；单 IP 每日上限（`PUBLIC_SESSION_CREATE_PER_IP_PER_DAY`）、单 IP 活跃上限与全站活跃上限（`PUBLIC_MAX_ACTIVE_SESSIONS`）超限返回 429，不产生半成品会话；未建立会话时仅 `/api/health`、`/api/public/config`、会话入口与静态资源可访问。
- 上传目录（`UPLOAD_PATH`）不挂到 Nginx，也不注册静态资源处理器；上传临时文件位于 `uploads/tmp`，请求结束即删除并由 TTL 任务兜底；第四阶段已接入 HTTP 恶意文件扫描（`UPLOAD_SCAN_PROVIDER=http`，失败默认拒绝），详见第十节。
- 安全组仅放行 80/443；`backend` 与 `mysql` 不发布宿主机端口。
- 清理任务默认每 60 分钟运行；过期会话在宽限期（默认 60 分钟）后被删除。从 public 切回 private 前，先等待或手动触发一轮清理。

## 十、第四阶段（发布前验证）运维要点

详见 [`PHASE4_DELIVERY.md`](../PHASE4_DELIVERY.md)。以下为需要运维确认与执行的部分。

### 1. 恶意文件扫描服务

- public 模式必须 `UPLOAD_SCAN_PROVIDER=http` 且 `UPLOAD_SCAN_URL` 非空，否则后端拒绝启动。
- 仓库提供可直接运行的扫描栈覆盖 `deploy/docker-compose.scan.yml`：ClamAV（仅 internal 网络）+ `deploy/scan-adapter` HTTP 网关（multipart → clamd INSTREAM），对外契约与后端一致。启用后设置 `UPLOAD_SCAN_URL=http://scanner:8080/scan`；如接入外部扫描网关，保持同一契约即可。
- 协议：`POST` multipart，字段 `file`；`200` 且响应 `{"clean":true}` 视为通过；`{"clean":false}` 返回 400；超时/连接失败/非 200/响应不可解析/超过 `UPLOAD_SCAN_MAX_RESPONSE_BYTES` 返回 503。
- 扫描失败默认拒绝（fail-closed），不使用降级放行。
- 扫描服务不得接触数据库凭据、AI Key、Cookie；超时默认 5000ms，可按产品调整 `UPLOAD_SCAN_TIMEOUT_MS`。
- 上传目录不注册静态资源、不可执行；落盘名为服务端生成，会话绑定 + TTL 清理。
- 内网无法访问 `database.clamav.net`（容器 DNS 受限）时，采用「宿主机预置病毒库 + 关闭 freshclam」：
  1. 宿主机下载 `main.cvd`、`daily.cvd`、`bytecode.cvd` 到 `/opt/clamav-db`；
  2. `docker run --rm --user root -v dataclean_clamav_db:/var/lib/clamav -v /opt/clamav-db:/src:ro clamav/clamav:stable sh -c 'cp -f /src/*.cvd /var/lib/clamav/ && chown -R clamav:clamav /var/lib/clamav'`；
  3. `.env` 设置 `CLAMAV_NO_FRESHCLAMD=true`，并保持 `deploy/docker-compose.scan.yml` 中直接运行 `clamd` 的 `command`（绕过入口脚本「缺失 main.cvd 即联网首次下载」的逻辑）。
- 内存小于 4GB 的主机加载 `main.cvd` 会触发 OOM：可仅保留 `daily.cvd`（EICAR 等常用签名可用），并通过 `CLAMD_CONF_MaxThreads` 降低线程数；生产环境应使用 ≥4GB 内存或独立扫描节点，并恢复完整病毒库与自动更新。

### 2. 监控与告警

- 管理端口 `MANAGEMENT_PORT`（默认 8081）仅在内网 `expose`，不映射宿主机；Nginx 对公网 `/actuator/` 返回 404。
- Prometheus 抓取 `backend:8081/actuator/prometheus`（见 `deploy/monitoring/prometheus.yml`），并由 `blackbox-exporter` 从容器网络内部探测 `nginx`、`backend` 与 `scanner` 健康端点；启动方式见第十三节。
- 告警阈值可通过 `ALERT_*` 与 `MONITORING_INTERVAL_SECONDS` 调整，规则见 `deploy/monitoring/alerts.yml`。
- 关键指标：`dc_sessions_active`、`dc_upload_total{result}`、`dc_http_responses_total{status}`、`dc_ai_tokens_estimated_total`、`dc_cleanup_failures_total`、`dc_orphan_tables`、`dc_upload_volume_bytes`、`dc_db_pool_active`。

### 3. 证书部署

- 证书目录（如 `deploy/certs/`）仅通过只读卷挂载给 nginx，不入镜像、不入仓库。
- HTTPS 模板要求 `NGINX_SERVER_NAME` 非 `_` 且证书/私钥可读，否则渲染脚本退出 1。
- 证书更新流程：替换宿主机证书文件 → `docker compose restart nginx` → 复验 443 握手与 HSTS。

### 4. 备份与恢复

- 迁移/升级前：数据库全量备份 + 上传卷快照，记录校验值，并在隔离库验证可恢复。
- 恢复顺序：停止写入 → 恢复数据库 → 恢复上传卷 → 回退镜像 → 健康检查与冒烟。
- 禁止在未备份时执行 `docker compose down -v`。

### 5. 发布与回滚

按「备份 → 迁移 → 灰度/维护窗口 → 健康检查 → 冒烟 → 监控观察 → 回滚阈值」执行；出现 5xx 超阈值、扫描服务不可用、健康检查失败或关键冒烟失败时立即停止放量并回滚。

### 6. 故障排查

| 现象 | 排查 |
|---|---|
| 后端启动即退出 | 查看启动校验日志：Cookie 属性、`PUBLIC_ALLOWED_ORIGIN`、`PUBLIC_IP_HASH_PEPPER`、`UPLOAD_SCAN_PROVIDER/URL` |
| 上传全部 503 | 扫描服务不可达或超时；检查 `UPLOAD_SCAN_URL` 与网络策略 |
| 上传全部 400 | 扫描判定风险，或文件类型/大小/行列超限 |
| Prometheus 抓取失败 | 确认 8081 仅内网可达、`/actuator/prometheus` 已暴露 |
| 限流不生效 | 确认 Nginx 为唯一入口，`TRUSTED_PROXY_CIDRS` 与 Nginx 网段一致 |
| 真实 IP 全为代理地址 | `TRUSTED_PROXY_CIDRS` 未包含 Nginx 网段 |

## 十一、第五阶段（版本化清洗 / 异步任务 / 统计性能）运维要点

详见 [`PHASE5_DELIVERY.md`](../PHASE5_DELIVERY.md)。

### 1. 新增配置（均有默认值，可按部署规模调整）

| 变量 | 默认 | 说明 |
|---|---|---|
| `JOBS_ENABLED` | `true` | 是否启用后台任务执行器；回滚/维护窗口可临时关闭 |
| `JOBS_WORKER_THREADS` | `2` | 任务执行线程数，建议 ≤ 数据库连接池上限的 1/4 |
| `JOBS_POLL_INTERVAL_MS` | `2000` | 轮询间隔 |
| `JOBS_MAX_RUNTIME_SECONDS` | `1800` | 单任务最长运行时间，超时标记为可重试 |
| `JOBS_ASYNC_IMPORT_ROWS` / `JOBS_ASYNC_IMPORT_BYTES` | `20000` / `5242880` | 超过任一门限的导入自动转异步 |
| `JOBS_CHUNK_SIZE` | `1000` | 流式写入批量大小 |
| `JOBS_PRIVATE_MAX_ACTIVE` / `JOBS_PRIVATE_MAX_PER_DAY` / `JOBS_PRIVATE_MAX_RETRIES` | `4` / `100` / `3` | 私有模式任务配额 |
| `JOBS_PUBLIC_MAX_ACTIVE_PER_SESSION` / `JOBS_PUBLIC_MAX_PER_DAY` / `JOBS_PUBLIC_MAX_RETRIES` | `1` / `20` / `2` | 公开模式（按匿名会话）任务配额 |
| `JOBS_VERSION_KEEP` / `JOBS_VERSION_MAX_AGE_DAYS` | `5` / `30` | 私有数据集版本保留数量与最长保留天数（至少保留 1 个可恢复版本） |
| `APP_UPLOAD_MAX_ROWS` / `APP_UPLOAD_MAX_COLUMNS` / `APP_UPLOAD_MAX_EXCEL_SHEETS` | `500000` / `500` / `20` | 流式导入的行/列/工作表上限 |
| `APP_JOBS_POLL_INTERVAL_MS` | — | 兼容 `JOBS_POLL_INTERVAL_MS` 的旧键，二者含义一致 |

### 2. 任务与版本

- 任务状态机：`PENDING → RUNNING → SUCCEEDED / FAILED`，取消走 `CANCEL_REQUESTED → CANCELLED`，重启时 `RUNNING → RECOVERABLE`（可重试）。
- 任务中心接口：`GET /api/job`、`GET /api/job/{id}`、`POST /api/job/{id}/cancel`、`POST /api/job/{id}/retry`；公开模式仅能查看/操作所属匿名会话的任务，跨会话统一 404。
- 数据集版本接口：`GET /api/dataset/{id}/versions`、`POST /api/dataset/{id}/versions/{versionId}/restore?confirm=true`、`DELETE /api/dataset/{id}/versions/{versionId}?confirm=true`（删除至少保留 2 个版本，ACTIVE/BUILDING 不可删）。
- 每次导入/清洗生成独立物理表 `data_<name>_<ts>_v<n>`；回滚只切换 `dataset.current_version_id` 与表指针，不覆盖历史版本。
- 公开会话版本随会话 TTL 清理；私有版本按 `JOBS_VERSION_KEEP` + `JOBS_VERSION_MAX_AGE_DAYS` 清理。
- 孤儿表（无归属的 `data_*`）只上报（`dc_orphan_tables`），**不会自动删除**，需人工核对。

### 3. 故障恢复手册

| 现象 | 处理 |
|---|---|
| 服务重启后任务停留在 `RECOVERABLE` | 任务可安全重试：`POST /api/job/{id}/retry`；临时文件已过 TTL 的导入任务需重新上传 |
| 任务长时间 `RUNNING` 无进度 | 检查 `JOBS_MAX_RUNTIME_SECONDS`（默认 30 分钟）与日志中的阶段；超时会自动转为可重试 |
| 清洗任务 `FAILED` 但数据未被修改 | 失败会清理 BUILDING 版本与半成品表，当前版本保持不变；重试次数用尽（429）后需新建清洗任务 |
| 取消后任务未立即停止 | 取消在安全检查点生效，最多等待一个批次（`JOBS_CHUNK_SIZE` 行）；取消成功后不保留半成品表与版本行 |
| 版本恢复失败 | 校验目标版本状态非 BUILDING、物理表存在、校验摘要一致；不一致时禁止切换并返回 400 |
| 统计结果标记 `approximate=true` | 数据集行数超过 10 万，统计基于 5 万行受限抽样；需要精确值请缩小数据集 |
| 需要临时停止后台任务 | 设置 `JOBS_ENABLED=false` 后重启后端；已提交的 PENDING 任务保留，恢复后继续执行 |

### 4. 备份与回滚

- 升级前除整库备份外，建议额外导出 `dataset_version`、`async_job`、`audit_log`，便于回滚后追溯。
- 回滚顺序：停止后台任务 → 备份 → 应用回滚 → 需要时执行 `R9`（破坏性，见 `db/rollback/`）→ 健康检查与冒烟。
- 回滚到旧版本后，`dataset.table_name` 仍指向当前生效版本，旧版读取不受影响；历史版本物理表成为孤儿表，按需人工清理。

## 十二、第六阶段（可视化与 AI 治理）运维要点

详见 [`PHASE6_DELIVERY.md`](../PHASE6_DELIVERY.md)。

### 1. 新增配置

| 变量 | 默认 | 说明 |
|---|---|---|
| `AI_GOVERNANCE_ENABLED` | `true` | AI 统一调用治理开关；关闭后回退旧版 `ai_config` 直连，不建议长期关闭 |
| `AI_AUDIT_PEPPER` | 空 | 审计主体 HMAC 盐，**生产必须配置**；为空时使用固定盐并输出 WARN |
| `APP_AI_MAX_PROMPT_CHARS` | `20000` | 送入模型的 Prompt 最大字符数，超出截断 |

### 2. 升级步骤

1. 整库备份 + 上传卷快照；
2. 配置 `AI_SECRET_KEY`（既有）与 `AI_AUDIT_PEPPER`（新增）；
3. 执行 `V10__visualization_ai_governance.sql`（容器可用 `migrate` 服务）；
4. 部署后端与前端，检查 `/api/health`；
5. 私有模式进入「AI 治理」，配置供应商 → 模型 → 场景路由，执行一次试调用；
6. 冒烟：推荐 → 生成 → 保存 → 看板添加卡片 → 导出 CSV/PNG → 审计页出现记录。

### 3. AI 治理与密钥

- 管理接口：`/api/ai/governance/providers|models|scenes|calls|usage|circuits`；写操作需 `X-Confirm: yes`；公开模式全部 404。
- Key 只写不读：新增必须填写，编辑留空表示保持；接口仅返回 `apiKeyMask` 与 `keyConfigured`。
- 审计仅记录场景/供应商/模型/主体哈希/耗时/token/成本/结果/错误类型；不记录 Key、Prompt、响应、Cookie 与原始敏感数据。
- 场景：`DATASET_SUMMARY`、`FIELD_RELATION`、`OUTLIER_ANALYSIS`、`CHART_INSIGHT`（公开可用）、`CLEAN_SUGGESTION`（仅私有）。
- 配额：`ai_quota` 支持 private / public_session / public_ip / public_global；公开上线前必须显式配置，否则未配置即放行。
- 熔断：连续失败 3 次或 429/预算耗尽立即打开，冷却 60s 后单探测；可在治理页查看与重置。

### 4. 图表与看板

- 图表单次最多 2000 点；分类默认 Top N 50（上限 500）、饼图 12、热力 500。
- 保存图表/看板按归属隔离：公开模式仅本匿名会话并在 TTL 到期后清理；私有模式按 `ownerType=private`。
- 数据版本变化时返回 409，前端提示「数据已更新，需要刷新」；字段失效提示重新配置，不会静默套用旧配置。
- 看板 12 列栅格：`gridX 0–11`、`gridW 2–12`、`x+w ≤ 12`、`gridH 2–12`。

### 5. 回滚与故障恢复

- 回滚顺序：下线前端新入口 → 备份 → 执行 `R10`（仅删除本阶段 10 张表）→ 回退镜像 → 健康检查与冒烟。
- `R10` 不影响既有数据集、版本、清洗与审计数据；重升级前需按 `db/README.md` 处理迁移历史或恢复备份。
- 常见问题：

| 现象 | 处理 |
|---|---|
| AI 调用 503「暂无可用模型」 | 检查场景路由与模型状态；查看熔断页是否 OPEN，必要时重置 |
| 大量 429 | 配额/并发触顶；超限不会调用供应商，按需调整 `ai_quota` 或模型并发 |
| 熔断后长时间不可用 | 冷却 60s 后自动单探测；探测失败会再次打开，检查供应商可用性 |
| 图表 409 | 数据版本已变化或字段失效；确认刷新或重新配置 |
| 图表为空 | 响应 `empty=true` 表示无数据而非报错，检查过滤条件 |
| 导出被拒 | 公开模式行数/次数限制或归属不符；私有确认 `X-Confirm: yes` |

## 十三、第七阶段：完整预发/生产栈

预发环境使用与生产一致的叠加栈，`nginx` 仍是唯一公网入口：

```bash
docker compose -f docker-compose.yml \
  -f deploy/docker-compose.https.yml \
  -f deploy/docker-compose.scan.yml \
  -f deploy/docker-compose.monitoring.yml \
  config --quiet
docker compose -f docker-compose.yml \
  -f deploy/docker-compose.https.yml \
  -f deploy/docker-compose.scan.yml \
  -f deploy/docker-compose.monitoring.yml \
  up -d --build
```

- 端口边界：仅 `80`、`443` 发布到宿主机；`mysql`/`backend`/`migrate`/`scanner`/`clamav` 不发布端口；Prometheus 仅绑定 `127.0.0.1:${PROMETHEUS_PORT}`，经 SSH 隧道访问。
- `.env` 必须显式设置：`UPLOAD_SCAN_PROVIDER=http`、`UPLOAD_SCAN_URL=http://scanner:8080/scan`、`PUBLIC_SERVER_NAME`、`PUBLIC_ALLOWED_ORIGIN=https://<域名>`、`PUBLIC_IP_HASH_PEPPER`、`AI_AUDIT_PEPPER`、`TRUSTED_PROXY_CIDRS=172.30.0.0/24`。
- 证书挂载：`deploy/certs/fullchain.pem` 与 `privkey.pem`（只读、不入镜像、不入仓库）；渲染脚本在 HTTPS 模板下强制要求真实域名与可读证书，否则拒绝启动。
- 扫描栈病毒库需提前就绪：联网环境由 freshclam 自动更新（`start_period` 300s）；受限网络按第十节「宿主机预置病毒库」方式准备。健康检查通过前 `scanner` 不启动。
- 预发实测：1.87GB 内存主机无法同时长期运行 ClamAV 完整库与 Prometheus，生产建议 ≥4GB 或拆分扫描/监控节点。
- 安全组仅放行 `80/443`；SSH 仅允许密钥认证与可信来源。
- 预发验收矩阵、阻塞项与发布门禁见仓库根目录 `PHASE7_PRERELEASE_ACCEPTANCE.md`。未完成该报告中的运行时验收前，禁止生产发布。

