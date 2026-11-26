# 第三阶段交付说明：公开演示与临时工作区

> 适用范围：`data-clean-platform` 第三阶段（公开模式 / 匿名临时工作区）。
> 前置阶段：第一阶段安全底线、第二阶段迁移与容器化基线已完成。
> 本文只描述本阶段新增与修改，不替代 `deploy/README.md`、`db/README.md` 中的基线说明。

## 1. 交付结论

- 新增 `APP_MODE=private|public` 双模式，默认 `private`，私有模式行为与第二阶段一致。
- 公开模式免登录；每个浏览器获得独立匿名会话，只能看到「官方演示数据 + 本会话数据」，其他会话与历史私有数据统一 404。
- 匿名会话 24h（可配 1–168h）过期，过期后由清理任务删除会话、动态表、备份表、图表、导出、AI 记录与用量。
- 公开 AI 默认关闭；开启后仅 4 个预置模板，受会话/IP/全站调用量、全站 token 预算、并发、超时与熔断约束。
- P0/P1/P2 评审项全部落地；三轮自查发现并修复 8 处问题（含 1 处会导致清理永不收敛的致命缺陷与 1 处会导致 Nginx 无法启动的配置缺失，见第 12 节）。
- 上线前安全加固已完成并在隔离环境实测：`__Host-dc_anon` Cookie 与启动强校验、受控会话启动（挑战 + 三重上限）、上传隔离与恶意文件拒绝、V8 增量迁移、双会话越权、清理故障注入、CSV 公式注入转义。网关/HTTPS 仅完成静态检查（本机无 Docker/Nginx/OpenSSL），未标记为运行时已验证。

## 2. 模式与关键默认值

| 配置 | 默认 | 说明 |
|---|---|---|
| `APP_MODE` | `private` | `public` 时跳过登录，启用匿名会话与全部公开限制 |
| `PUBLIC_ALLOWED_ORIGIN` | 空 | public 必填；固定源站白名单，禁止 `*`，非 local 必须 `https` |
| `PUBLIC_IP_HASH_PEPPER` | 空 | public 必填；独立 HMAC-SHA256 密钥（≥32 字符），不复用 JWT |
| `PUBLIC_SESSION_TTL_HOURS` | 24 | 允许 1–168 |
| `PUBLIC_MAX_ACTIVE_SESSIONS` | 5000 | 全站活跃会话上限，超限 429 |
| `PUBLIC_MAX_ACTIVE_SESSIONS_PER_IP` | 10 | 单 IP 活跃会话上限 |
| `PUBLIC_SESSION_CREATE_PER_IP_PER_DAY` | 20 | 单 IP 每自然日创建次数上限（名称与统计口径一致） |
| `PUBLIC_SESSION_CHALLENGE_ENABLED` | true | 创建达到阈值后要求一次性挑战令牌 |
| `PUBLIC_SESSION_CHALLENGE_THRESHOLD` | 3 | 当日第 N 次起需要挑战 |
| `PUBLIC_COOKIE_NAME` | `__Host-dc_anon` | 生产固定；不合规拒绝启动 |
| `PUBLIC_AI_ENABLED` | false | 公开 AI 总开关 |
| `PUBLIC_CLEANUP_INTERVAL_MINUTES` | 60 | 清理周期 |
| `PUBLIC_CLEANUP_GRACE_MINUTES` | 60 | 会话过期后的宽限期 |

启动校验（`PublicModeStartupValidator`）：public 下 origin 必填、非 `*`、非 local 必须 https；pepper ≥32；Cookie 名称必须 `__Host-dc_anon` 且 `Secure/HttpOnly/SameSite=Strict/Path=/`、无 `Domain`，否则拒绝启动；本地覆盖必须同时满足 local Profile + `local-insecure-cookie=true`，且不得使用 `__Host-` 名称；TTL 越界拒绝启动。启动日志只打印模式、Cookie 名、TTL 与布尔开关。

## 3. 修改文件清单

### 3.1 后端新增

| 文件 | 作用 |
|---|---|
| `config/AppMode.java` | 模式开关，非法值启动失败 |
| `config/PublicModeProperties.java` | `app.public.*` 全部配置与 MB 换算 |
| `config/PublicModeStartupValidator.java` | 公开模式启动安全校验 |
| `config/PublicModeConfig.java` | 开启定时任务与配置绑定 |
| `util/RequestContext.java` | 请求级会话 ID / IP 摘要 / 模式上下文 |
| `filter/AnonymousSessionFilter.java` | Cookie 解析/签发、上下文注入 |
| `filter/PublicOriginGuardFilter.java` | 非 GET 写操作 Origin 强校验与二次确认标记 |
| `service/AnonymousSessionService.java` | 令牌签发、SHA-256 存储、IP 摘要、创建限流 |
| `service/OwnershipService.java` | 统一可见性与可写性校验 |
| `service/PublicQuotaService.java` | 数据集/存储/行列/清洗/图表/导出配额 |
| `service/PublicCleanupService.java` | 清理状态机与故障恢复 |
| `service/DemoDataService.java` | 启动时创建合成演示数据 |
| `service/PublicAiService.java` | 公开 AI 白名单、配额、并发、超时、熔断 |
| `service/HumanChallengeService.java` | 绑定会话/IP/过期的一次性挑战 |
| `service/ExportService.java` | 受控 CSV 导出（BOM、公式转义、行数上限） |
| `controller/ExportController.java` | `POST /api/export/dataset/{id}` |
| `controller/PublicController.java` | 公开配置、挑战、私有维护入口 |
| `controller/PublicSessionController.java` | 受控会话启动：`POST /api/public/session`（幂等/428）与 `/challenge` |
| `config/UploadStorageConfig.java` | 创建上传卷与 `tmp/`（POSIX 0700，不可静态访问） |
| `service/FileScanService.java`、`service/NoopFileScanService.java` | 恶意文件扫描预留接口（默认 `none` 空实现） |
| `service/UploadTempCleaner.java` | 上传临时文件 TTL 定时清理 |
| `filter/UploadTempCleanupFilter.java` | 请求结束即删除 multipart 临时文件（失败/成功都覆盖） |
| `entity/AnonSession.java`、`CleanupItem.java`、`AiChallenge.java`、`ChartView.java`、`ExportRecord.java`、`AnonUsageDaily.java`、`CleanupLog.java` | 公开模式实体 |
| `mapper/AnonSessionMapper.java`、`AnonUsageMapper.java`、`CleanupItemMapper.java`、`AiChallengeMapper.java`、`ChartViewMapper.java`、`ExportRecordMapper.java`、`CleanupLogMapper.java` | 对应 Mapper |
| `resources/demo/demo_sales.csv` | 40 行人工构造销售样例（非真实数据） |
| `local/application-local.yml` | 仅本机 HTTP 调试的独立 local Profile 覆盖（不在 `resources` 下，不进入镜像） |

### 3.2 后端修改

`config/SecurityConfig.java`（public/private 双链）、`filter/JwtAuthFilter.java`（public 跳过）、`entity/Dataset.java`、`CleanRule.java`、`CleanLog.java`、`AiGenerationLog.java`（归属列与序列化收敛）、`mapper/DatasetMapper.java`（匿名统计与孤儿表计数）、`mapper/CleanLogMapper.java`、`service/DatasetService.java`、`CleanService.java`、`AnalysisService.java`、`controller/AiController.java`、`exception/GlobalExceptionHandler.java`（503）、`util/FileParserUtil.java`（Reader 解析复用）、`dto/AiGenerateDTO.java`（挑战令牌）、`resources/application.yml`（配置项与默认值）。

### 3.3 前端

新增 `stores/appMode.js`、`api/publicSession.js`（受控会话启动、428 挑战与 401 自动恢复）；修改 `main.js`、`router/index.js`、`utils/request.js`、`components/layout/Layout.vue`、`api/ai.js`、`api/dataset.js`、`views/Dataset.vue`、`Preview.vue`、`Clean.vue`、`Analysis.vue`。

### 3.4 数据库与部署

| 文件 | 作用 |
|---|---|
| `db/migration/V7__public_workspace.sql` | 7 张新表 + 4 张表归属列/索引，全部幂等 |
| `db/rollback/R7__public_workspace.sql` | 第三阶段对象回滚（破坏性，先备份） |
| `db/migration/V8__public_hardening.sql` | 会话挑战：`ai_challenge.purpose`、`anon_session_id` 可空、`idx_purpose_expires`，纯增量 |
| `db/rollback/R8__public_hardening.sql` | 删除 V8 列/索引（不回退 NULL 约束） |
| `deploy/nginx/default.conf.template` | 限流、安全头、脱敏日志、健康检查与管理路径 404 |
| `deploy/nginx/default.https.conf.template` | 80→443、TLS、HSTS |
| `deploy/nginx/docker-entrypoint.d/40-render-nginx-conf.sh` | 仅白名单变量 envsubst |
| `deploy/docker-compose.https.yml` | HTTPS 覆盖（443、证书卷） |
| `frontend/Dockerfile` | 复制模板与 entrypoint 脚本 |
| `docker-compose.yml` | 透传 `APP_MODE/PUBLIC_*` 与 `NGINX_CLIENT_MAX_BODY_SIZE` |
| `.env.example`、`.env.public.example` | 环境变量模板（不含 Cookie Secure 覆盖项） |
| `scripts/phase3-isolation-check.ps1` | 双会话隔离、Origin、Cookie 属性、会话挑战/限流、生命周期验收 |
| `scripts/phase3-cleanup-faults.ps1` | 三种清理故障注入与私有数据不变验收 |
| `scripts/phase3-upload-hardening.ps1` | 伪造扩展名/二进制伪 CSV/超大/超行列/压缩炸弹/异常 Excel 与 CSV 公式注入转义 |
| `scripts/rehearse-migrations.ps1` | 扩展到 V8/R8、失败重试与重复执行收敛 |

## 4. 数据库迁移

```bash
docker compose run --rm migrate       # 自动按版本执行 V1..V8
docker compose exec mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" -D "$MYSQL_DATABASE" -e "SELECT version,description,success FROM schema_migration ORDER BY version"'
```

V7 内容：

- 新表：`anon_session`、`cleanup_item`、`ai_challenge`、`chart_view`、`export_record`、`anon_usage_daily`、`cleanup_log`；
- `dataset.owner_type`（默认 `private`）、`dataset.anon_session_id` 及索引；
- `clean_rule/clean_log/ai_generation_log.anon_session_id` 及索引；
- 所有 DDL 均通过 `INFORMATION_SCHEMA` 判断，重复执行安全；不删除、不重建任何既有表。

V8 内容（纯增量）：`ai_challenge.purpose`（默认 `ai`，会话挑战为 `session`）、`ai_challenge.anon_session_id` 改为可空、`idx_purpose_expires(purpose, expires_at)`；不修改任何历史业务数据。

回滚：`db/rollback/R7__public_workspace.sql`（破坏性）与 `db/rollback/R8__public_hardening.sql`（删除 V8 列/索引，不回退 NULL 约束；执行前确认无 `anon_session_id IS NULL` 的挑战记录）。回滚后需同步回退应用版本。

迁移演练（隔离 MySQL，端口 13306）：

- 空库全量执行 V1–V8，`schema_migration` 计数 8；
- 含历史数据的存量库执行 V7/V8，仅新增列/索引/表，业务行数与关键字段不变；
- 重复执行 V7/V8 幂等；失败后重试可收敛（删除对应版本记录后重跑）；
- A5 场景验证 R8 → R7 回滚脚本可执行；V7/V8 只做增量，不删除历史业务数据。

## 5. 匿名会话与隔离

- Cookie：生产为 `__Host-dc_anon`，`HttpOnly; Secure; SameSite=Strict; Path=/`，不设置 `Domain`，TTL 与会话一致；服务端只存 SHA-256，不写 localStorage；启动时强校验，不合规拒绝启动。
- 本地调试：仅 `local` Profile + `backend/local/application-local.yml`（`dc_anon_local`、非 Secure、`local-insecure-cookie=true`），通过 `--spring.config.additional-location=file:./local/` 加载；生产 jar 已确认不包含任何 `application-local.yml`。
- 受控会话启动：唯一入口 `POST /api/public/session`；未持有效会话访问业务 API 返回 401 + `X-Session-Required: 1`，不隐式签发；仅 `/api/health`、`/api/public/config`、会话入口/挑战与静态资源可匿名访问。
- 会话创建防护：单 IP 自然日上限、单 IP 活跃上限、全站活跃上限；达到阈值返回 428 + `X-Session-Challenge-Required: 1`，领取一次性令牌后重试；任一上限超限返回 429 且不产生半成品会话。拒绝原因以 `session_reject_<reason>` 脱敏计数。
- IP 摘要：`HMAC-SHA256(PUBLIC_IP_HASH_PEPPER, "ip:" + ip)`，与 JWT 密钥完全独立；优先取 Nginx 注入的 `X-Real-IP`；日志与统计不记录原始 IP。
- 可见性统一由 `OwnershipService` 提供：public = `owner_type='demo' OR (owner_type='anon' AND anon_session_id=当前会话)`；不可见一律 404，不泄露资源是否存在。
- 演示数据：允许读取、预览、统计、生成临时图表；清洗、修改、删除、导出统一 403。
- 响应序列化：`Dataset/CleanRule/CleanLog.anonSessionId` 使用 `@JsonIgnore`，不向前端暴露会话 ID。

## 6. 清理任务说明

状态机：`active → purging → purged`（失败为 `failed`，下一轮自动重试；`purging` 超过 10 分钟可被重新认领）。

执行顺序：

1. 选出「已注销或过期超过宽限期」的会话，以及僵死的 `purging` 会话；
2. `claimForPurge` 原子认领，避免多实例/多轮重复处理；
3. 从数据库元数据生成 `cleanup_item`：仅 `dataset.table_name`、`clean_log.backup_table`，以及按会话删除的规则/日志/图表/导出/AI 记录/挑战/用量；
   - 用量计数表只保留 7 天，按日期滚动删除，避免计数表无限增长；全站预算行同样保留 7 天，不影响当日统计；
4. 逐项执行，先落库再执行，单项失败记录 `error_type` 不中断其他项；
5. 动态表删除前做两层校验：
   - 表名必须是合法标识符且非系统库前缀；
   - 必须能在本会话的 `dataset.table_name` 或 `clean_log.backup_table` 中精确找到来源，否则**跳过并计入孤儿表**，绝不猜测删除；
6. 确认无数据集元数据残留后，才删除会话级记录与会话本身；
7. 孤儿表只统计上报到 `cleanup_log.orphan_tables_reported`，不自动删除。

三种故障场景的收敛方式：

| 场景 | 处理 |
|---|---|
| 表已删、元数据未删 | 重新生成明细，`DROP TABLE IF EXISTS` 幂等通过，随后删除元数据与会话 |
| 元数据已删、表残留 | 明细无法证明归属 → 跳过删除，表作为孤儿表上报，会话正常收敛 |
| 进程中断 | 会话保持 `purging`，10 分钟后重新认领，`done` 项跳过，未完成项续跑 |

## 7. 配额与容量配置

| 维度 | 默认上限 | 超限 |
|---|---|---|
| 单会话数据集 | 5 | 429 |
| 单文件 | 20MB | 400 |
| 单会话总存储 | 100MB | 429 |
| 行数 / 列数 | 100000 / 200 | 400 |
| 清洗执行 | 50 次 | 429 |
| 图表生成 | 100 次 | 429 |
| 导出 | 20 次，单次最多 10000 行 | 429 / 截断标记 |
| AI 会话/日 | 20 | 429 |
| AI 单 IP/日 | 60 | 429 |
| AI 全站/日调用 | 500 | 503 + 熔断 |
| AI 全站/日 token 预算 | 200000 | 503 + 熔断至次日 |

容量规划建议：按「单会话峰值 × `max-active-sessions`」估算磁盘与连接数；`max-active-sessions` 默认 5000 仅适用于 2C4G 级别演示机，扩大前需同步评估 MySQL 连接池（Druid `max-active=20`）与动态表数量。

## 7.1 公开上传隔离

- 保留扩展名/文件头/大小/行数/列数/总存储与压缩炸弹限制；CSV 拒绝二进制内容，Excel 使用 `ZipSecureFile` 限制单条目解压大小。
- 上传目录（`UPLOAD_PATH`）不注册静态资源处理器、不挂给 Nginx；`UploadStorageConfig` 以 POSIX 0700 创建。
- 原始文件名不落盘：解析后直接入库；临时文件为容器生成名，位于 `uploads/tmp`。
- `UploadTempCleanupFilter` 在每次 multipart 请求结束后立即删除所有 Part 临时文件（成功/失败都覆盖），`UploadTempCleaner` 按 TTL 兜底。
- 恶意文件扫描：`FileScanService` 接口 + `NoopFileScanService` 默认空实现（`UPLOAD_SCAN_PROVIDER=none`），**上线前必须接入真实扫描服务**；上传文件不会被后端执行，也不提供浏览器直接下载路径。
- CSV 导出继续对 `= + - @` 前缀加 `'` 转义，防公式注入。

## 8. AI 保护

- 默认关闭；仅 `dataset_summary`、`field_relation`、`outlier_analysis`、`chart_insight` 四个模板。
- 拒绝 `configId`、`customPrompt`、未知 `extraContext`；自定义变量最多 8 个、每个 200 字符。
- Prompt 仅包含数据集名称、行列数、字段名/类型、聚合统计与脱敏摘要，不发送原始数据行。
- 四层配额：会话日调用、IP 日调用、全站日调用、全站日 token 预算；预算耗尽熔断至次日（503）。
- 并发信号量 + 超时；供应商异常/超时统一 503，不向前端暴露地址、Key 或原始错误。
- 挑战：绑定匿名会话 ID、IP 摘要、过期时间；成功后 `UPDATE ... WHERE used_at IS NULL` 原子核销，重复使用/跨会话/跨 IP 返回 403/429。

## 9. 部署步骤

### 9.1 HTTP（仅限内网/临时验收）

```bash
cp .env.public.example .env
# 填写 PUBLIC_ALLOWED_ORIGIN、PUBLIC_IP_HASH_PEPPER，以及 MySQL/JWT/AI_SECRET_KEY
docker compose config --quiet
docker compose build
docker compose up -d
docker compose ps
```

注意：公开模式要求 `__Host-dc_anon` + Secure Cookie，**纯 HTTP 域名在生产会拒绝启动**；本地调试请使用 `SPRING_PROFILES_ACTIVE=local` 与 `backend/local/application-local.yml`（`--spring.config.additional-location=file:./local/`），该文件不在 `resources` 下，不会进入镜像。

### 9.2 HTTPS（生产）

```bash
docker compose -f docker-compose.yml -f deploy/docker-compose.https.yml up -d
```

前置条件：

- 证书放入 `deploy/certs/fullchain.pem` 与 `privkey.pem`（该目录被 `.gitignore`/`.dockerignore` 排除）；
- 在 `.env` 设置 `PUBLIC_SERVER_NAME`（Compose 会映射为容器内 `NGINX_SERVER_NAME`）；
- 安全组仅放行 80/443；`backend`、`mysql` 不发布宿主机端口；
- `PUBLIC_ALLOWED_ORIGIN` 必须与浏览器地址栏完全一致（含 `https://`）。

Nginx 模板只替换白名单变量（`NGINX_CLIENT_MAX_BODY_SIZE`、`NGINX_SERVER_NAME`、`NGINX_HTTPS_CERT`、`NGINX_HTTPS_KEY`），`$uri/$host/$remote_addr` 等运行期变量不会被 envsubst 清空。

## 10. 备份、升级与回滚

备份（升级/迁移前）：

```bash
docker compose exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_PASSWORD" mysqldump -u"$MYSQL_USER" --single-transaction --routines --triggers --set-gtid-purged=OFF "$MYSQL_DATABASE"' \
  > backups/dataclean_$(date +%Y%m%d_%H%M%S).sql
docker run --rm -v dataclean_uploads:/data -v "$PWD/backups:/backup" alpine:3.20 \
  tar czf /backup/uploads_$(date +%Y%m%d_%H%M%S).tar.gz -C /data .
```

升级：备份 → `docker compose build` → `docker compose up -d`（`migrate` 先执行 V1..V8）→ 检查 `migrate/backend/nginx` 日志与 `/api/health`。

回滚：

1. 应用回滚：切回上一版本镜像并 `docker compose up -d`；旧版本可兼容新增可空列与新表。
2. 需要回退结构时执行 `R8`（删除挑战新增列/索引，非破坏业务数据）再按需执行 `R7`（破坏性，先备份），或直接恢复备份。
3. **从 public 切回 private 前**：先保持 public 运行至少一个清理周期，或手动触发清理，确保过期匿名会话已删除；切回 private 后定时清理不再运行，残留匿名数据不会被自动删除。
4. 禁止在未备份时执行 `docker compose down -v`。

## 11. 测试与验收结果

隔离环境：临时 MySQL（`127.0.0.1:13306`，库 `dataclean_it`）、临时上传卷与本机后端（`127.0.0.1:8081`，`APP_MODE=public`，local Profile）。未连接生产 MySQL，未执行任何生产迁移/清理/导入/清洗/回滚。真实密码、Cookie、Token、连接串与密钥均未写入本文件或测试日志。

| 项目 | 命令/入口 | 结果 |
|---|---|---|
| 后端单测 | `mvn test` | 通过：72 个测试，0 失败 0 错误 |
| 后端打包 | `mvn clean package -DskipTests` | 通过；jar 内不含 `application-local.yml` |
| 前端构建 | `vite build` | 通过 |
| 敏感信息扫描 | `node scripts/scan-secrets.mjs` | 通过：未发现疑似明文凭据 |
| 迁移演练 | `scripts/rehearse-migrations.ps1`（隔离库） | 通过：空库/存量库/重复执行/失败重试/R7、R8 回滚 |
| 双会话隔离 | `scripts/phase3-isolation-check.ps1` | 通过：`PASS=82 FAIL=0 SKIP=0` |
| 清理故障注入 | `scripts/phase3-cleanup-faults.ps1` | 通过：`PASS=18 FAIL=0`（三场景 + private 数据不变 + 孤儿表仅上报） |
| 上传加固 | `scripts/phase3-upload-hardening.ps1` | 通过：`PASS=20 FAIL=0`（7 类恶意/异常文件 + 公式转义 + 静态访问/文件名落盘） |
| 全局活跃上限 | 独立实例 `PUBLIC_MAX_ACTIVE_SESSIONS=2` | 通过：第 3 次创建 429，无 Set-Cookie，计数 `session_reject_global_active=1` |
| 坏 Cookie 启动拒绝 | 生产参数 + `cookie-secure=false` | 通过：拒绝启动，退出码 1，原因明确 |
| 坏 Cookie 名称拒绝 | 生产参数 + `cookie-name=dc_anon` | 通过：拒绝启动 |
| 会话挑战 | `POST /api/public/session` → 428 → `/challenge` | 通过：挑战后创建成功，令牌单次可用 |
| 上传临时文件清理 | 上传前后 `uploads/tmp` 计数 | 通过：请求结束后计数归零 |
| 网关/HTTPS 静态检查 | Compose 与 Nginx 模板审阅 | 通过（仅静态）：端口收敛、健康/管理路径 404、白名单 envsubst、TLS/HSTS 配置齐全 |

未执行（受环境限制）：Nginx 实际启动、80→443 跳转抓取、HSTS/安全响应头与 TLS 版本握手验证、真实公网端口探测。本机无 Docker / Nginx / OpenSSL，**不标记为运行时已验证**，已列入第 15 节运维确认项。

## 12. 三轮自查记录

### 第一轮：跨会话隔离与清理正确性

发现并修复：

1. **清理表名前缀错误（严重）**：动态表实际为 `data_<名称>_<时间戳>`、备份表为 `<表名>_backup_<时间戳>`，原清理逻辑只接受 `ds_/bk_` 前缀，导致所有公开会话永远清理失败。已改为「合法标识符 + `dataset.table_name` / `clean_log.backup_table` 精确来源校验」。
2. **孤儿表统计前缀错误**：同步改为 `data_%` 与 `%_backup_%`，仅统计不删除。
3. **响应泄露会话 ID**：`Dataset/CleanRule/CleanLog.anonSessionId` 增加 `@JsonIgnore`。
4. **跨源请求先建会话**：将 `PublicOriginGuardFilter` 调整到匿名会话过滤器之前，跨源写请求不消耗会话配额。
5. **Nginx 启动失败（严重）**：模板使用 `limit_conn addr` 但未定义 `limit_conn_zone`，Nginx 会直接拒绝启动；两套模板均已补齐。

### 第二轮：敏感信息与仓库卫生

- 确认错误响应不含 SQL/堆栈/路径/密钥；`GlobalExceptionHandler` 只记录异常类型。
- Nginx 日志已去除 query/Cookie/Authorization。
- 修复 `.gitignore`：补 `!.env.*.example`（否则 `.env.public.example` 会被忽略）与演示 CSV 例外（否则 `demo_sales.csv` 不会随代码发布）。
- 确认 `application-local.yml`、`deploy/certs`、`.env` 均被 `.gitignore` 与 `.dockerignore` 排除。
- 启动校验加固：非 Secure Cookie 必须同时满足「local Profile」与「localInsecureCookie=true」，单独通过环境变量打开仍会拒绝启动；Origin 比较统一做小写归一化。

### 第三轮：临时数据清理、资源耗尽与 AI 成本

- 复核清理状态机与三种故障场景：均可在下一轮收敛，私有数据不在任何删除条件内。
- 复核会话创建三重限流、上传/存储/行列/清洗/图表/导出配额、AI 四层配额与熔断。
- 复核挑战单次核销与跨会话/跨 IP 拒绝。
- 补充用量计数 7 天滚动清理，防止 `anon_usage_daily` 无限增长；确认全站预算行不会被会话清理删除，避免通过“过期会话回收”重置全站预算。
- 结论：无新增阻塞问题；剩余风险列入第 13 节。

### 第四轮：上线前安全加固（本轮）

发现并修复：

1. **清洗去重规则在 MySQL 8 失败（严重）**：`INSERT ... SELECT * ... GROUP BY` 触发 `only_full_group_by`，导致清洗执行 500、无法产生清洗日志，跨会话回滚验收因此被跳过。已改为 `WHERE _id IN (SELECT MIN(_id) ... GROUP BY ...)`，实测清洗与回滚恢复正常。
2. **本地 Profile 配置残留进 jar**：`mvn package` 未清理旧 `target/classes` 时会把已删除的 `application-local.yml` 打进镜像。已改为 `mvn clean package` 并以 `jar tf` 断言为 0。
3. **生产镜像配置模板缺失新配置项**：`application.yml.example` 未包含 `app.public.*` 与上传临时/扫描配置，导致容器内 `PUBLIC_*` 环境变量不生效。已补齐（不含任何密钥）。
4. **上传临时文件未及时删除**：容器只在 GC/退出时清理 multipart 临时文件。已新增 `UploadTempCleanupFilter`，请求结束即删除，实测 `uploads/tmp` 计数归零。
5. **清理验收脚本单值解析错误**：PowerShell 将单元素数组展开为字符串，`$rows[0]` 取到首字符，导致会话 ID 恒为 1、场景 A/B 误判。已修正 `Scalar-Sql`，复跑 `PASS=18 FAIL=0`。
6. **私有接口错误码**：匿名用户访问私有接口曾返回空 403，已统一 `authenticationEntryPoint` 与 `accessDeniedHandler` 返回 404。

## 13. 风险项与已知限制

1. **人机挑战是「一次性令牌」而非算力 PoW**：挑战绑定过期且只能核销一次，可防重放与跨会话盗用；但脚本仍可直接领取挑战，机器人防护主要依赖创建频率、全站上限与网关限流。如需强人机验证，建议在 Nginx 前接入验证码服务。
2. **并发信号量为单实例内存实现**：多副本部署时每个实例各自限流，需在网关或 Redis 侧做分布式限流。
3. **孤儿表不会自动删除**：元数据缺失时无法证明归属，只统计上报，需要 DBA 人工确认后清理。
4. **网关/HTTPS 未运行时验证**：本机无 Docker/Nginx/OpenSSL，80→443、HSTS、TLS 版本、安全头抓取、端口收敛均只做静态检查，上线前必须在预发环境复验。
5. **public → private 切换不会自动清理残留匿名数据**：需先等待/触发清理，见第 10 节。
6. **恶意文件扫描未接入**：当前为空实现，上传仅依赖扩展名/文件头/大小/行列/压缩炸弹限制；上线前必须接入真实扫描服务（`UPLOAD_SCAN_PROVIDER`）。
7. **单 IP 创建限流为自然日窗口**：名称为 `..._PER_DAY`，与实现一致；如需滚动一小时窗口需扩展用量表粒度。
8. **AI 供应商链路未实测**：隔离环境未配置供应商，公开 AI 配额/熔断/挑战仅通过单测与静态检查。

## 14. 部署前检查表

- [ ] 域名与证书就绪，`PUBLIC_ALLOWED_ORIGIN` 与浏览器地址栏完全一致（含 `https://`）。
- [ ] `.env` 中 `PUBLIC_IP_HASH_PEPPER` ≥32 字符且与 `JWT_SECRET` 不同；`AI_SECRET_KEY` 为 Base64 32 字节。
- [ ] 使用 HTTPS 覆盖配置启动；确认启动日志 `Cookie=__Host-dc_anon` 且无「本地调试模式」告警。
- [ ] 反向确认 backend/mysql 未映射宿主机端口；安全组仅 80/443。
- [ ] 确认 `/api/health`、`/api/admin/`、迁移容器不对公网开放。
- [ ] 预发环境抓取响应头：80→443、HSTS、`X-Content-Type-Options`、`X-Frame-Options`、CSP、TLS1.2/1.3。
- [ ] 确认 Nginx 模板渲染后 `$uri/$host/$remote_addr` 未为空。
- [ ] 接入真实恶意文件扫描服务并完成一次样本验证；确认 `UPLOAD_SCAN_PROVIDER` 非 `none`。
- [ ] 备份数据库与上传卷；在隔离库完整跑通 `rehearse-migrations.ps1`。
- [ ] 预发执行 `phase3-isolation-check.ps1`、`phase3-cleanup-faults.ps1`、`phase3-upload-hardening.ps1` 并留存脱敏结果。
- [ ] 配置并发/会话容量与告警（`session_reject_*`、429 比例、清理失败项）。

## 15. 仍需运维确认的事项

1. 证书与域名、HSTS 有效期、TLS 版本策略由运维按上线规范确认。
2. 生产 MySQL 连接池与 `PUBLIC_MAX_ACTIVE_SESSIONS` 的容量匹配（默认 5000 仅适用于 2C4G 演示机）。
3. 反向代理真实 IP 头（`X-Real-IP`）链路确认，确保 IP 摘要与限流基于真实来源。
4. 恶意文件扫描服务选型、接入方式与超时降级策略。
5. 监控与告警：429/428 比例、会话创建拒绝计数、清理失败项、磁盘与上传卷水位。
6. 是否需要网关层验证码或 WAF；当前仅内置一次性挑战。

## 16. 验收矩阵（对应 checklist）

| 编号 | 验收项 | 状态 | 证据 |
|---|---|---|---|
| A1 | 公开模式免登录访问 | 已实测 | 前端路由 + `/api/public/config` |
| A2 | 双会话数据隔离 | 已实测 | `phase3-isolation-check.ps1` 交叉 404（PASS=82） |
| A3 | 演示数据只读 | 已实测 | 同上：预览 200、清洗/导出/删除 403 |
| A4 | Cookie 属性与不可恢复 | 已实测（local）/ 静态（生产 HTTPS） | 脚本断言 + 启动校验；生产头待预发抓取 |
| A5 | 缺 Origin / 跨源 403 | 已实测 | `phase3-isolation-check.ps1` |
| A6 | 会话创建限流/挑战/上限 429 | 已实测 | `phase3-isolation-check.ps1` + 全站上限短跑 |
| A7 | 清理三种故障收敛 | 已实测 | `phase3-cleanup-faults.ps1`（PASS=18） |
| A8 | 清理不触碰私有数据 | 已实测 | 同上：私有数据集/物理表快照前后一致 |
| A9 | AI 默认 403、白名单、挑战单次 | 静态检查 | `PublicAiService` + `HumanChallengeService` + 单测 |
| A10 | 全站 AI 预算与熔断 | 静态检查 | `checkQuota` + 503 |
| A11 | 网关仅 80/443、健康检查不公开 | 静态检查 | Nginx 模板 + Compose 端口审计 |
| A12 | 错误响应无敏感信息 | 已实测 | 隔离脚本与上传脚本断言 |
| A13 | 上传恶意/异常文件拒绝 | 已实测 | `phase3-upload-hardening.ps1`（PASS=20） |
| A14 | 上传隔离与临时文件清理 | 已实测 | 上传脚本 + `uploads/tmp` 计数归零 |
| A15 | CSV 公式注入转义 | 已实测 | 上传脚本导出断言 |
| A16 | V8 增量迁移与回滚 | 已实测 | `rehearse-migrations.ps1` |
| A17 | 生产 Cookie 不合规拒绝启动 | 已实测 | 两次坏配置启动退出码非 0 |
| A18 | Nginx 启动与 TLS 握手 | 未验证（环境限制） | 需预发环境执行 |
