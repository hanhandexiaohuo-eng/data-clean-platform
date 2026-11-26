# 第三阶段规格：公开演示与临时工作区模式（修订版 v2）

> 状态：待确认（Spec Mode，已吸收 P0/P1/P2 评审意见）
> 目标仓库：`C:\Users\zengyun\Desktop\Kimi_Agent_数据导入可视化平台\data-clean-platform`
> 前置：第一阶段安全底线、第二阶段迁移与容器化基线已完成。

## 1. 范围与硬约束

### 1.1 两种运行模式

| 模式 | 入口 | 身份 | 数据 | AI / 管理能力 |
|---|---|---|---|---|
| `public` 公开演示 | 免登录，任何访问者 | 匿名会话 Cookie | 演示数据（只读）+ 本会话临时数据 | AI 默认关闭（可受限开启）、无任何配置/历史/审计/运维 |
| `private` 私有工作区 | 保留现有登录页 | JWT 登录 | 全部真实历史数据 | 完整 AI 配置、模板、历史、审计、运维 |

模式由 `APP_MODE` 决定，默认 `private`。公开模式看不到任何 `owner_type='private'` 数据；私有模式拒绝 `/api/public/**` 业务端点（仅保留只读 `/api/public/config`）。

### 1.2 硬约束

1. 不删除真实数据；V7 迁移纯增量，只加表/列/索引。
2. 不把真实数据迁入公开空间；演示数据由内置合成 CSV 生成。
3. 不降低第一阶段要求：AI 密钥 AES-GCM 只写不读；SQL 标识符白名单 + 参数绑定；上传扩展名+文件头双重校验；AI 输入 PII 脱敏。
4. 不取消登录页；私有模式与第二阶段一致。
5. 本机无 Docker/WSL、无可信证书：HTTPS 只交付配置与步骤，HTTP 基线可运行。
6. 公开模式 Cookie 必须 `Secure`；生产启动时若公开模式且 Cookie 非 Secure，**拒绝启动**。本地 HTTP 仅能通过独立 `local` Profile 覆盖。

## 2. 配置项

| 变量 | 默认 | 说明 |
|---|---|---|
| `APP_MODE` | `private` | `public` / `private` |
| `PUBLIC_ALLOWED_ORIGIN` | 无（public 必填） | 固定公开源站，如 `https://demo.example.com`；逗号分隔可多个；禁止 `*`；public 缺失则启动失败 |
| `PUBLIC_IP_HASH_PEPPER` | 无（public 必填） | 独立 HMAC 密钥，>= 32 字符；不依赖 JWT；public 缺失则启动失败 |
| `PUBLIC_SESSION_TTL_HOURS` | `24` | 1–168，硬上限 168 |
| `PUBLIC_MAX_ACTIVE_SESSIONS` | `5000` | 全站活跃匿名会话上限 |
| `PUBLIC_MAX_ACTIVE_SESSIONS_PER_IP` | `10` | 单 IP 活跃会话上限 |
| `PUBLIC_SESSION_CREATE_PER_IP_PER_DAY` | `20` | 单 IP 每自然日新建会话上限（统计口径与名称一致） |
| `PUBLIC_MAX_DATASETS` | `5` | 每会话数据集数 |
| `PUBLIC_MAX_FILE_SIZE_MB` | `20` | 单文件大小 |
| `PUBLIC_MAX_TOTAL_STORAGE_MB` | `100` | 每会话总存储 |
| `PUBLIC_MAX_ROWS` | `100000` | 单数据集行数 |
| `PUBLIC_MAX_COLUMNS` | `200` | 单数据集列数 |
| `PUBLIC_MAX_CLEAN_TASKS` | `50` | 每会话清洗执行次数 |
| `PUBLIC_MAX_CHARTS` | `100` | 每会话图表生成次数 |
| `PUBLIC_MAX_EXPORTS` | `20` | 每会话导出次数 |
| `PUBLIC_EXPORT_MAX_ROWS` | `10000` | 单次导出最大行数 |
| `PUBLIC_AI_ENABLED` | `false` | 公开 AI 总开关 |
| `PUBLIC_AI_MAX_CALLS_PER_DAY` | `20` | 每会话每日调用 |
| `PUBLIC_AI_MAX_CALLS_PER_IP_PER_DAY` | `60` | 每 IP 每日调用 |
| `PUBLIC_AI_GLOBAL_DAILY_CALLS` | `500` | 全站每日调用上限 |
| `PUBLIC_AI_GLOBAL_DAILY_TOKEN_BUDGET` | `200000` | 全站每日估算 token 预算 |
| `PUBLIC_AI_MAX_INPUT_CHARS` | `4000` | 单次输入上限（脱敏后） |
| `PUBLIC_AI_MAX_OUTPUT_TOKENS` | `1024` | 单次输出上限 |
| `PUBLIC_AI_MAX_CONCURRENCY` | `1` | 单会话并发 |
| `PUBLIC_AI_TIMEOUT_MS` | `30000` | 单次超时 |
| `PUBLIC_AI_BREAKER_ENABLED` | `true` | 全局预算耗尽后熔断至次日 |
| `PUBLIC_AI_CHALLENGE_ENABLED` | `true` | 公开 AI PoW 人机挑战 |
| `PUBLIC_AI_CHALLENGE_DIFFICULTY` | `18` | 前导零 bit 数 |
| `PUBLIC_AI_CHALLENGE_TTL_SECONDS` | `120` | 挑战有效期 |
| `PUBLIC_DEMO_DATA_ENABLED` | `true` | 启动时确保演示数据 |
| `PUBLIC_CLEANUP_ENABLED` | `true` | 定时清理 |
| `PUBLIC_CLEANUP_INTERVAL_MINUTES` | `60` | 清理周期 |
| `PUBLIC_CLEANUP_GRACE_MINUTES` | `60` | 会话过期后宽限 |
| `NGINX_CLIENT_MAX_BODY_SIZE` | `25m`（公开）/`200m`（私有） | Nginx 请求体上限 |

`application.yml` 新增：

```yaml
app:
  mode: ${APP_MODE:private}
  public:
    allowed-origins: ${PUBLIC_ALLOWED_ORIGIN:}
    ip-hash-pepper: ${PUBLIC_IP_HASH_PEPPER:}
    session-ttl-hours: ${PUBLIC_SESSION_TTL_HOURS:24}
    cookie-name: __Host-dc_anon
    # 固定 true；本地仅可通过独立 local Profile 覆盖
    cookie-secure: true
    local-insecure-cookie: false
    cookie-http-only: true
    cookie-same-site: Strict
    cookie-path: /
    cookie-domain: ""
    max-active-sessions: ${PUBLIC_MAX_ACTIVE_SESSIONS:5000}
    max-active-sessions-per-ip: ${PUBLIC_MAX_ACTIVE_SESSIONS_PER_IP:10}
    session-create-per-ip-per-day: ${PUBLIC_SESSION_CREATE_PER_IP_PER_DAY:20}
    session-challenge-enabled: ${PUBLIC_SESSION_CHALLENGE_ENABLED:true}
    session-challenge-threshold: ${PUBLIC_SESSION_CHALLENGE_THRESHOLD:3}
    max-datasets: ${PUBLIC_MAX_DATASETS:5}
    max-file-size-mb: ${PUBLIC_MAX_FILE_SIZE_MB:20}
    max-total-storage-mb: ${PUBLIC_MAX_TOTAL_STORAGE_MB:100}
    max-rows: ${PUBLIC_MAX_ROWS:100000}
    max-columns: ${PUBLIC_MAX_COLUMNS:200}
    max-clean-tasks: ${PUBLIC_MAX_CLEAN_TASKS:50}
    max-charts: ${PUBLIC_MAX_CHARTS:100}
    max-exports: ${PUBLIC_MAX_EXPORTS:20}
    export-max-rows: ${PUBLIC_EXPORT_MAX_ROWS:10000}
    ai-enabled: ${PUBLIC_AI_ENABLED:false}
    ai-max-calls-per-day: ${PUBLIC_AI_MAX_CALLS_PER_DAY:20}
    ai-max-calls-per-ip-per-day: ${PUBLIC_AI_MAX_CALLS_PER_IP_PER_DAY:60}
    ai-global-daily-calls: ${PUBLIC_AI_GLOBAL_DAILY_CALLS:500}
    ai-global-daily-token-budget: ${PUBLIC_AI_GLOBAL_DAILY_TOKEN_BUDGET:200000}
    ai-max-input-chars: ${PUBLIC_AI_MAX_INPUT_CHARS:4000}
    ai-max-output-tokens: ${PUBLIC_AI_MAX_OUTPUT_TOKENS:1024}
    ai-max-concurrency: ${PUBLIC_AI_MAX_CONCURRENCY:1}
    ai-timeout-ms: ${PUBLIC_AI_TIMEOUT_MS:30000}
    ai-breaker-enabled: ${PUBLIC_AI_BREAKER_ENABLED:true}
    ai-challenge-enabled: ${PUBLIC_AI_CHALLENGE_ENABLED:true}
    ai-challenge-difficulty: ${PUBLIC_AI_CHALLENGE_DIFFICULTY:18}
    ai-challenge-ttl-seconds: ${PUBLIC_AI_CHALLENGE_TTL_SECONDS:120}
    demo-data-enabled: ${PUBLIC_DEMO_DATA_ENABLED:true}
    cleanup-enabled: ${PUBLIC_CLEANUP_ENABLED:true}
    cleanup-interval-minutes: ${PUBLIC_CLEANUP_INTERVAL_MINUTES:60}
    cleanup-grace-minutes: ${PUBLIC_CLEANUP_GRACE_MINUTES:60}
```

新增 `application-local.yml`（仅 `local` Profile 激活，不进入生产 Compose）：

```yaml
app:
  public:
    cookie-secure: false
    local-insecure-cookie: true
    allowed-origins: http://localhost:5173,http://127.0.0.1:8080
    ip-hash-pepper: local-dev-only-pepper-change-me-0123456789
```

### 2.1 启动校验（public 模式）

`PublicModeStartupValidator` 在启动时执行，任一失败即抛异常终止：

1. `allowed-origins` 非空，且不含 `*`；非 local 下必须是 `https://`。
2. `ip-hash-pepper` 长度 >= 32。
3. `cookie-secure=false` 且 `local-insecure-cookie=false` → 拒绝启动。
4. `local-insecure-cookie=true` → 记录 WARN「仅限本地开发」。
5. `session-ttl-hours` 不在 1–168 → 拒绝启动。
6. 日志只打印 `mode`、TTL 与布尔开关，不打印 pepper、origin 之外的敏感值。

## 3. 数据模型（V7，纯增量）

新增 `db/migration/V7__public_workspace.sql`，全部幂等；回滚 `db/rollback/R7__public_workspace.sql`。

### 3.1 新表

```sql
anon_session(
  id BIGINT PK AUTO_INCREMENT,
  token_hash CHAR(64) NOT NULL,
  created_at DATETIME, last_seen_at DATETIME,
  expires_at DATETIME NOT NULL,
  ip_hash CHAR(64) NULL,
  revoked TINYINT NOT NULL DEFAULT 0,
  cleanup_state VARCHAR(16) NOT NULL DEFAULT 'active',  -- active/purging/failed/purged
  cleanup_attempts INT NOT NULL DEFAULT 0,
  purge_started_at DATETIME NULL,
  purged_at DATETIME NULL,
  last_cleanup_error VARCHAR(200) NULL,
  UNIQUE KEY uk_token_hash(token_hash),
  KEY idx_expires_at(expires_at),
  KEY idx_cleanup_state(cleanup_state),
  KEY idx_ip_hash(ip_hash)
)

cleanup_item(
  id BIGINT PK AUTO_INCREMENT,
  anon_session_id BIGINT NOT NULL,
  resource_type VARCHAR(32) NOT NULL,   -- dataset_table/backup_table/dataset_meta/clean_rule/clean_log/chart/export/ai_log/usage/challenge
  resource_key VARCHAR(128) NOT NULL,   -- 精确表名或主键
  state VARCHAR(16) NOT NULL DEFAULT 'pending', -- pending/done/failed
  error_type VARCHAR(100) NULL,
  updated_at DATETIME,
  UNIQUE KEY uk_session_resource(anon_session_id, resource_type, resource_key),
  KEY idx_state(state)
)

ai_challenge(
  id BIGINT PK AUTO_INCREMENT,
  challenge_hash CHAR(64) NOT NULL,     -- SHA-256(secret)，不存明文
  anon_session_id BIGINT NOT NULL,
  ip_hash CHAR(64) NOT NULL,
  expires_at DATETIME NOT NULL,
  used_at DATETIME NULL,
  created_at DATETIME,
  UNIQUE KEY uk_challenge_hash(challenge_hash),
  KEY idx_session(anon_session_id),
  KEY idx_expires(expires_at)
)

chart_view(
  id BIGINT PK, anon_session_id BIGINT NOT NULL, dataset_id BIGINT NOT NULL,
  chart_type VARCHAR(20), chart_config JSON, created_at DATETIME,
  KEY idx_anon(anon_session_id), KEY idx_created(created_at)
)

export_record(
  id BIGINT PK, anon_session_id BIGINT NOT NULL, dataset_id BIGINT NOT NULL,
  export_type VARCHAR(20) NOT NULL DEFAULT 'csv', row_count INT,
  created_at DATETIME, KEY idx_anon(anon_session_id), KEY idx_created(created_at)
)

anon_usage_daily(
  id BIGINT PK AUTO_INCREMENT,
  scope_type VARCHAR(10) NOT NULL,      -- session/ip/global
  scope_key CHAR(64) NOT NULL,          -- 会话ID字符串 / IP 哈希 / 'global'
  metric VARCHAR(30) NOT NULL,          -- ai_call/ai_token/session_create
  usage_date DATE NOT NULL,
  counter BIGINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_scope_metric_date(scope_type, scope_key, metric, usage_date)
)

cleanup_log(
  id BIGINT PK, started_at DATETIME, finished_at DATETIME,
  sessions_cleaned INT, datasets_removed INT, tables_dropped INT,
  rows_removed INT, failures INT, orphan_tables_reported INT,
  detail VARCHAR(2000)
)
```

### 3.2 既有表加列（可空，不动历史数据）

| 表 | 新增列 | 索引 |
|---|---|---|
| `dataset` | `owner_type VARCHAR(16) NOT NULL DEFAULT 'private'`、`anon_session_id BIGINT NULL` | `idx_anon_session`、`idx_owner_type` |
| `clean_rule` | `anon_session_id BIGINT NULL` | `idx_anon_session` |
| `clean_log` | `anon_session_id BIGINT NULL` | `idx_anon_session` |
| `ai_generation_log` | `anon_session_id BIGINT NULL` | `idx_anon_session` |

历史行 `owner_type='private'`，公开模式查询永不命中。

### 3.3 演示数据

`DemoDataService`（public + `demo-data-enabled=true`）启动时检查 `dataset.name='官方演示数据'`；不存在则读取 classpath `demo/sales_sample.csv`（约 200 行合成：dept/region/month/revenue/orders/satisfaction），复用 `DatasetService` 的列推断与建表逻辑，写入 `owner_type='demo'`、`anon_session_id=NULL`、表名 `demo_sales_sample`。

## 4. 匿名会话与 CSRF

### 4.1 签发

- 受控会话启动：唯一创建入口 `POST /api/public/session`（幂等）；未建立会话时业务 API 返回 401 + `X-Session-Required: 1`，`AnonymousSessionFilter` 不再隐式签发。
- Cookie `__Host-dc_anon`：32 字节 `SecureRandom` Base64URL，服务端只存 SHA-256；`ResponseCookie` 设置 `HttpOnly`、`Secure`、`SameSite=Strict`、`Path=/`、`Max-Age=TTL`，不设置 `Domain`。
- 生产 public 模式启动时强校验 Cookie 名称/属性，不合规拒绝启动；本地 HTTP 调试只能通过独立 local Profile 覆盖（非 `__Host-`、非 Secure）。
- 请求属性 `ANON_SESSION_ID`；`RequestContext` 保存 `sessionId`、`ipHash`、`mode`，`finally` 清理。
- `last_seen_at` 节流：与上次相差 > 5 分钟才更新。

### 4.2 会话创建限流

签发前检查：

1. 单 IP 活跃会话数 ≤ `max-active-sessions-per-ip`；
2. 单 IP 每自然日新建次数 ≤ `session-create-per-ip-per-day`（`anon_usage_daily` metric=`session_create`）；
3. 全站活跃会话数 ≤ `max-active-sessions`；
4. 当日创建次数达到 `session-challenge-threshold` 后，必须携带一次性 `X-Session-Challenge`（先 `POST /api/public/session/challenge` 领取）。

任一超限返回 `429 {"code":429,"message":"当前访问人数较多，请稍后再试"}`，不签发 Cookie、不产生半成品会话；达到阈值返回 `428` 且带 `X-Session-Challenge-Required: 1`。拒绝原因以 `session_reject_<reason>` 写入脱敏计数，不记录 Cookie/Token/原始 IP。

### 4.3 Origin 强校验（P0）

- `PublicOriginGuardFilter` 在 public 模式对**所有非 GET 请求**（POST/PUT/DELETE/PATCH）校验 `Origin`：
  - 缺失 → 403；
  - 与 `PUBLIC_ALLOWED_ORIGIN` 白名单逐项精确匹配（scheme+host+port）→ 否则 403。
- 不使用请求 `Host` 作为可信基准，防止 Host Header 伪造。
- 导出改为 `POST /api/export/dataset/{id}`（浏览器对 POST 一定发送 Origin）。
- 公开模式禁用 SSE `GET /api/ai/generate/stream`（EventSource 同源 GET 不保证 Origin），只允许 `POST /api/ai/generate` 与 `POST /api/ai/generate/flux`。
- `X-Confirm: yes` 仅作为前端二次确认标记，**不作为安全控制**；安全依赖 Origin 白名单 + SameSite + HttpOnly。

### 4.4 不可恢复性

删除 Cookie 或换浏览器后生成新会话，旧数据不可访问；TTL 到期后清理任务删除数据。前端固定提示「临时工作区不可恢复」。

## 5. 资源归属与授权

### 5.1 统一可见条件（P1）

`OwnershipService` 统一封装，禁止各控制器自行拼条件：

```java
// public 模式读可见性：官方演示 OR 当前会话
wrapper.and(w -> w.eq(Dataset::getOwnerType, "demo")
        .or(x -> x.eq(Dataset::getOwnerType, "anon")
                   .eq(Dataset::getAnonSessionId, currentSessionId)));
// 任何详情/规则/日志/统计/图表/导出都必须复用：
Dataset requireDataset(Long id);   // 不满足可见性统一 404
void requireRule(Long ruleId);
void requireCleanLog(Long logId);
void requireDemoReadOnly(Dataset d); // 演示数据写操作 403
```

演示数据规则（P2）：

| 操作 | 演示数据 |
|---|---|
| 列表、详情、预览、列、统计 | 允许 |
| 生成临时图表 | 允许（写入 `chart_view` 并计入图表配额） |
| AI 分析（开启时） | 允许（仅统计摘要） |
| 创建/修改/删除清洗规则、执行清洗、回滚 | 禁止 403 |
| 删除数据集 | 禁止 403 |
| 导出 CSV | 禁止 403 |

### 5.2 实际接口清单与授权（与现有代码对齐，P1）

| 实际端点 | public 规则 |
|---|---|
| `GET /api/dataset` | 仅 demo + 本会话 |
| `GET /api/dataset/{id}` | 归属校验 |
| `POST /api/dataset/upload` | Origin + 配额 |
| `GET /api/dataset/{id}/preview` | 归属校验 |
| `GET /api/dataset/{id}/columns` | 归属校验 |
| `GET /api/dataset/{id}/statistics` | 归属校验 |
| `DELETE /api/dataset/{id}` | Origin + X-Confirm + 归属 + demo 禁止 |
| `GET /api/clean/rule?datasetId=` | 数据集归属 |
| `POST /api/clean/rule` | Origin + 数据集归属 + demo 禁止 |
| `PUT /api/clean/rule/{id}`、`DELETE /api/clean/rule/{id}` | Origin + 规则归属 + demo 禁止 |
| `POST /api/clean/execute`、`POST /api/clean/preview` | Origin + 数据集归属 + demo 禁止（execute 另需 X-Confirm 与配额） |
| `POST /api/clean/rollback/{logId}` | Origin + X-Confirm + 日志归属 + demo 禁止 |
| `GET /api/clean/log?datasetId=` | 数据集归属 |
| `POST /api/analysis/chart` | Origin + 数据集归属 + 图表配额 + demo 允许 |
| `GET /api/analysis/statistics/{datasetId}` | 数据集归属 |
| `POST /api/export/dataset/{id}` | Origin + X-Confirm + 归属 + demo 禁止 + 导出配额 |
| `POST /api/ai/generate`、`POST /api/ai/generate/flux` | Origin + AI 开关 + 挑战 + 配额 |
| `GET /api/ai/generate/stream` | public 404 |
| `/api/ai/config/**`、`/api/ai/template/**`、`/api/ai/history*`、`/api/auth/**`、`/api/admin/**` | public 403/404 |
| `GET /api/public/config` | 公开只读，无内部地址 |
| `GET /api/public/ai/challenge` | AI 开启时可用 |
| `GET /api/health` | 保留，Nginx 仅私网 |

所有不属于当前会话的资源统一返回 404，不泄露存在性。

### 5.3 私有模式

保持第二阶段行为；`/api/public/**` 仅 `config` 放行；`/api/admin/**`、`/api/export/**` 需 JWT。

## 6. 配额与清理

### 6.1 配额

`PublicQuotaService`：

| 维度 | 计算 |
|---|---|
| 数据集数/总存储 | `COUNT/SUM(dataset WHERE anon_session_id=? AND owner_type='anon')` |
| 单文件/行/列 | 上传前与解析后比较 |
| 清洗任务 | `COUNT(clean_log WHERE anon_session_id=?)` |
| 图表 | `COUNT(chart_view WHERE anon_session_id=?)` |
| 导出 | `COUNT(export_record WHERE anon_session_id=?)` |
| AI | 第 7 节 |

超限 `BusinessException(429, "<指标>已达上限（<值>）…")`；文件超限 400。

### 6.2 清理任务（P1 修订）

`PublicCleanupService.purgeExpired()`，`@Scheduled` + `POST /api/admin/maintenance/cleanup`（private 手动）。

**只按元数据精确表名删除**：

1. 候选会话：`expires_at < now - grace` 或 `revoked=1`，`cleanup_state IN ('active','failed')`；以及 `cleanup_state='purging' AND purge_started_at < now-10min`（中断恢复）。
2. 认领会话：`UPDATE ... SET cleanup_state='purging', purge_started_at=NOW(), cleanup_attempts=cleanup_attempts+1 WHERE id=? AND cleanup_state IN ('active','failed','purging')`。
3. 生成/补齐 `cleanup_item`：
   - `dataset_table`：`dataset.table_name` 精确值；
   - `backup_table`：该会话数据集对应 `clean_log.backup_table` 精确值；
   - 元数据项：`dataset_meta`、`clean_rule`、`clean_log`、`chart`、`export`、`ai_log`、`usage`、`challenge`。
   每个表名先 `SqlGuard.requireTableName` 校验，且必须来自数据库元数据，**禁止按前缀/正则扫描任意表**。
4. 逐项处理 `pending/failed`：
   - 表项：`DROP TABLE IF EXISTS`（幂等），成功后 `done`；
   - 元数据项：按主键/会话删除，成功后 `done`；
   - 单项失败：`failed` + `error_type`，继续其他项，不中断整轮。
5. 全部项 `done` 后，删除该会话剩余元数据行，`cleanup_state='purged'`、`purged_at=NOW()`；会话行保留为墓碑，7 天后由下一轮删除。
6. 孤儿表：仅通过 `INFORMATION_SCHEMA.TABLES` 匹配 `^data_[a-z0-9_]+(_backup_[0-9]+)?$` 且 `CREATE_TIME < cutoff`、无任何元数据引用的表，**只统计数量写入 `cleanup_log`，绝不自动 DROP**。
7. 写 `cleanup_log`：会话数、数据集数、删表数、删行数、失败数、孤儿表数量、失败异常类型；不写 token/IP/原始数据。
8. `GET_LOCK('dataclean_cleanup', 0)` 防并发；拿不到锁跳过本轮。

故障收敛（验收要求）：
- 删表成功但元数据残留 → 重跑 `DROP IF EXISTS` + 删除元数据；
- 元数据已删但表残留 → `cleanup_item` 保留表名，重跑仍能删表；
- 进程中断 → `purging` 超时后重新认领；
- 任何场景都不触碰 `owner_type='private'` 数据。

## 7. 公开 AI 保护

1. `ai-enabled=false`：生成接口 403。
2. 开启后：
   - 固定默认配置，忽略 `configId`；不返回供应商地址/模型/Key/历史。
   - 模板白名单：`dataset_summary`、`field_relation`、`outlier_analysis`、`clean_suggestion`；拒绝 `customPrompt`、`variables`、`extraContext`。
   - 仅发送字段名/类型/空值/唯一值/聚合统计；无原始明细。复用并强化 `AiService.buildPrompt` 脱敏截断。
   - 输入 ≤ `ai-max-input-chars`；`maxTokens=min(配置, ai-max-output-tokens)`；`timeout=min(配置, ai-timeout-ms)`。
   - **PoW 挑战（P0）**：
     - `GET /api/public/ai/challenge` 生成 32 字节 secret，存 `SHA-256(secret)`、`anon_session_id`、`ip_hash`、`expires_at`；
     - 返回 `challengeId.secret HMAC 签名` 的 token；
     - 验证时必须同时满足：HMAC 有效、挑战属于当前会话、IP 摘要一致、未过期；
     - 成功后原子核销：`UPDATE ai_challenge SET used_at=NOW() WHERE id=? AND used_at IS NULL`，影响行数必须为 1；重复使用返回 429。
   - **配额（P0）**：会话每日调用、IP 每日调用、全站每日调用、全站每日 token 预算四层校验；`anon_usage_daily` 记录 `scope_type=session/ip/global`。
   - 估 token = `(输入字符 + 输出字符)/4`；超过全站预算且 `ai-breaker-enabled=true` → 熔断至次日，返回 429「今日公开 AI 额度已用完」。
   - 并发 `Semaphore`（单实例；多实例需外置限流，列入已知限制）。
   - 供应商 4xx/5xx、超时、限流 → `BusinessException(503, "AI 服务暂不可用，请稍后再试")`。
3. `GlobalExceptionHandler` 增加 `503 -> SERVICE_UNAVAILABLE`。
4. `ai_generation_log` 写 `anon_session_id`，继续脱敏截断。

## 8. 网关与部署

### 8.1 Nginx（P1 修订）

- 模板：`deploy/nginx/default.conf.tpl`；自定义 `deploy/nginx/docker-entrypoint.d/10-dataclean-envsubst.sh`：
  - 仅 `envsubst '${NGINX_CLIENT_MAX_BODY_SIZE}'`（HTTPS 版再显式加 `${TLS_CERT_FILE} ${TLS_KEY_FILE}`）；
  - 输出到 `/etc/nginx/conf.d/default.conf`；
  - 不使用官方 `.template` 自动替换，避免 `$uri`、`$host`、`$remote_addr` 被误渲染。
- 限流：`api` 10r/s、`upload` 3r/m、`ai` 5r/m；`ai_conn` 5；分级挂载。
- `client_max_body_size ${NGINX_CLIENT_MAX_BODY_SIZE};`
- 安全头：`nosniff`、`X-Frame-Options: DENY`、`Referrer-Policy`、`Permissions-Policy`、CSP。
- 脱敏日志：`log_format` 仅记录 `$request_method $uri`，不含 query/Cookie/Authorization。
- `/api/health` 私网限制保留；`server_tokens off`。
- HTTPS：`deploy/nginx/https/default.conf.tpl`（80 全量 301、TLS1.2/1.3、HSTS）、`docker-compose.https.yml`（443、证书只读挂载）。

### 8.2 Compose / 环境

- `docker-compose.yml` 透传 `APP_MODE` 与全部 `PUBLIC_*`（不含 Cookie Secure 覆盖项）；Nginx 透传 `NGINX_CLIENT_MAX_BODY_SIZE`；mysql/backend 仍不发布端口，`db_net` internal。
- `.env.example` 增加变量说明；`.env.public.example` 提供公开部署示例。
- `PUBLIC_COOKIE_SECURE` 不再出现在常规 `.env`/Compose；仅 `application-local.yml` 覆盖。
- 安全组只开 80/443 写入 `deploy/README.md`。

## 9. 前端

- `stores/appMode.js`：`GET /api/public/config`，失败回退 private。
- Router：public 跳过登录守卫；`/ai-config` 重定向；private 不变。
- `Layout.vue`：公开提示条「公开临时工作区 · 数据将在 {ttl} 小时后自动清理 · 请勿上传个人隐私或保密数据」；隐藏 AI 配置/退出登录，显示匿名会话。
- `request.js`：`withCredentials: true`；public 不跳登录；429 提示；导出改为 POST blob。
- `Dataset.vue`：删除二次确认 + `X-Confirm`。
- `Clean.vue`：执行/回滚二次确认 + `X-Confirm`；隐藏自定义表达式。
- `Analysis.vue`：导出按钮（POST blob，二次确认）。
- `AiPanel.vue`：public 且关闭时不渲染；开启时取挑战、解 PoW、带 `X-AI-Challenge`；隐藏配置选择。

## 10. 修改文件清单

### 新增（后端）

```
config/PublicModeProperties.java
config/PublicModeStartupValidator.java
config/PublicModeConfig.java
filter/AnonymousSessionFilter.java
filter/PublicOriginGuardFilter.java
util/RequestContext.java
service/AnonymousSessionService.java
service/OwnershipService.java
service/PublicQuotaService.java
service/PublicCleanupService.java
service/DemoDataService.java
service/HumanChallengeService.java
controller/PublicController.java
controller/ExportController.java
controller/AdminMaintenanceController.java
entity/AnonSession.java / AnonUsageDaily.java / ChartView.java / ExportRecord.java / CleanupLog.java / CleanupItem.java / AiChallenge.java
mapper/AnonSessionMapper.java / AnonUsageMapper.java / ChartViewMapper.java / ExportRecordMapper.java / CleanupLogMapper.java / CleanupItemMapper.java / AiChallengeMapper.java
dto/PublicConfigVO.java
resources/demo/sales_sample.csv
resources/application-local.yml
db/migration/V7__public_workspace.sql
db/rollback/R7__public_workspace.sql
specs/phase3-public-demo/{spec,tasks,checklist}.md
```

### 修改（后端）

```
SecurityConfig.java / JwtAuthFilter.java / application.yml
DatasetService.java / DatasetController.java
CleanService.java
AnalysisService.java
AiService.java / AiController.java
GlobalExceptionHandler.java
DatasetMapper.java（按需聚合）
```

### 前端、部署、文档

```
frontend/src/stores/appMode.js（新增）
frontend/src/router/index.js
frontend/src/utils/request.js
frontend/src/components/layout/Layout.vue
frontend/src/views/Dataset.vue / Clean.vue / Analysis.vue
frontend/src/components/ai/AiPanel.vue
frontend/Dockerfile
deploy/nginx/default.conf.tpl（替代 default.conf）
deploy/nginx/https/default.conf.tpl（新增）
deploy/nginx/docker-entrypoint.d/10-dataclean-envsubst.sh（新增）
docker-compose.yml / docker-compose.https.yml（新增）
.env.example / .env.public.example（新增）
deploy/README.md / db/README.md
scripts/phase3-isolation-check.ps1（新增）
PHASE3_DELIVERY.md
```

## 11. 验收与测试

| 编号 | 场景 | 期望 |
|---|---|---|
| A1 | 双匿名会话隔离 | 互不可见 |
| A2 | 改 dataset/rule/log/chart/export ID | 全部 404，响应不含资源名 |
| A3 | 访问 AI 配置/历史/认证/管理 | 403/404 |
| A4 | 删除 Cookie / 过期 / 清理 | 旧数据不可恢复；表与记录全清 |
| A5 | 各维度配额 | 429/400，提示明确 |
| A6 | AI | 默认 403；自定义 Prompt 拒绝；超限 429；供应商异常 503；挑战绑定会话/IP/单次 |
| A7 | 网关 | 仅 80/443；health 私网；安全头/限流 |
| A8 | 恶意上传 | 伪造扩展名、炸弹、超行列、二进制 CSV 拒绝 |
| A9 | 敏感信息 | 无 SQL/堆栈/路径/密钥/会话 ID |
| A10 | 同 IP 批量建会话 | 触发创建限流/活跃上限，429，不签发 Cookie |
| A11 | 清理故障注入 | 元数据残留/表残留/进程中断三种场景重跑收敛，私有数据零改动 |
| A12 | Origin 缺失/伪造 | public 非 GET 无 Origin 或跨源 Origin → 403 |

自动化：单元测试（会话、归属、配额、挑战单次核销、清理状态机、AI 公开分支）、`mvn test`、`vite build`、`scan-secrets`、迁移演练、双会话脚本。

## 12. 回滚

1. 应用：`APP_MODE=private` 重新部署。
2. 数据：`R7` 删除 7 张新表与新增列（先备份；仅确认无公开数据需保留时执行）。
3. Nginx：恢复 HTTP 模板，不启用 HTTPS 覆盖文件。
4. 清理：`PUBLIC_CLEANUP_ENABLED=false`。
5. 回滚后验证 `/api/health`、登录页、历史数据完整。

## 13. 已知限制

1. 并发与熔断基于单实例内存；多副本需 Redis/网关限流。
2. 无 Docker/证书，HTTPS 仅配置与步骤，未本地实证。
3. 公开模式生产必须 HTTPS；本地 HTTP 只能通过 `local` Profile 关闭 Secure Cookie。
4. 演示数据为合成样本。
5. 导出流式生成不落盘；仅记录元数据。
6. 孤儿表只报告不自动删除，需运维按报告人工确认。
7. `V5/V6` 回滚仍具破坏性（沿用第二阶段说明）。

## 14. 审批项

- `APP_MODE` 默认 `private`，公开部署显式 `public`。
- TTL 默认 24h、硬上限 168h。
- 公开 AI 默认关闭；开启时 4 模板 + 绑定会话/IP 的单次 PoW + 全站预算熔断。
- 演示数据由合成 CSV 启动生成，只读且禁止导出/清洗。
- 公开模式要求固定 `PUBLIC_ALLOWED_ORIGIN` 与独立 `PUBLIC_IP_HASH_PEPPER`，生产非 Secure Cookie 拒绝启动。
