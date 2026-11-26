# 第三阶段任务拆解（修订 v2）

> 依赖：`spec.md` v2 确认后执行。验证环境：`JAVA_HOME=D:\JDK21`、Maven 3.9.6（缓存）、`D:\Node\node.exe`。

## T1 模式开关、配置与启动校验

- [ ] `application.yml` 增加 `app.mode` 与 `app.public.*`（含 allowed-origins、ip-hash-pepper、会话/全局配额）。
- [ ] 新增 `config/PublicModeProperties.java`（TTL 1–168、MB 换算）。
- [ ] 新增 `config/PublicModeStartupValidator.java`：
  - public 下 origin 必填且非 `*`、生产必须 https；
  - pepper >= 32；
  - `cookie-secure=false` 且非 local → 拒绝启动；
  - 仅打印 mode/TTL/布尔开关。
- [ ] 新增 `config/PublicModeConfig.java`（`@EnableScheduling`）。
- [ ] 新增 `resources/application-local.yml`（仅 local Profile：非 Secure Cookie + localhost origin）。
- [ ] `.env.example` / `.env.public.example`：不暴露 Cookie Secure 覆盖项。
- 验证：`mvn -q -DskipTests compile`；public 缺 origin/pepper 启动失败；local Profile 可启动并 WARN。

## T2 V7 迁移与实体/Mapper

- [ ] `V7__public_workspace.sql`：7 张新表（anon_session 含 cleanup_state、cleanup_item、ai_challenge、chart_view、export_record、anon_usage_daily、cleanup_log）+ 4 张表会话列与索引；全部 INFORMATION_SCHEMA 幂等。
- [ ] `R7__public_workspace.sql`。
- [ ] 实体与 Mapper：AnonSession、AnonUsageDaily、ChartView、ExportRecord、CleanupLog、CleanupItem、AiChallenge 及对应 Mapper。
- [ ] `Dataset`/`CleanRule`/`CleanLog`/`AiGenerationLog` 加 `ownerType`/`anonSessionId`。
- [ ] `db/README.md` 补 V7/R7。
- 验证：迁移演练空库、存量库、重复执行、校验和。

## T3 匿名会话、创建限流与 Origin 强校验

- [ ] `RequestContext`（sessionId、ipHash、mode）。
- [ ] `AnonymousSessionService`：32 字节 token、SHA-256 摘要、`ResponseCookie(__Host-dc_anon, HttpOnly, Secure, SameSite=Strict, Path=/, 无 Domain)`、`last_seen_at` 节流；`ipHash` 使用 **独立 `PUBLIC_IP_HASH_PEPPER`**（HMAC-SHA256，域分隔 `ip:`）。
- [ ] 会话创建限流：单 IP 活跃数、单 IP 每小时创建数（`anon_usage_daily` metric=session_create）、全站活跃上限；超限 429 且不签发 Cookie。
- [ ] `AnonymousSessionFilter`：排除 health/config；无效/过期先限流再重签。
- [ ] `PublicOriginGuardFilter`：
  - public 下所有 POST/PUT/DELETE/PATCH 必须携带 `Origin`，且与 `PUBLIC_ALLOWED_ORIGIN` 精确匹配；缺失/跨源 403；
  - 不以 Host 为基准；
  - `X-Confirm: yes` 仅校验破坏性操作的确认标记（说明为非安全控制）。
- [ ] `JwtAuthFilter` public 跳过；public 禁用 `GET /api/ai/generate/stream`。
- [ ] `SecurityConfig` 双链：public permit 数据集/清洗/分析/导出/公开配置/AI POST，deny auth/config/template/history/admin；private 沿用并限制 `/api/public/**`。
- 验证：过滤器单测（Cookie 属性、缺 Origin 403、跨源 403、创建限流 429）。

## T4 统一归属校验与配额

- [ ] `OwnershipService`：`visibleDatasetWrapper()`（demo OR 本会话 anon）、`requireDataset/requireRule/requireCleanLog/requireChart`、`requireDemoReadOnly`；跨会话一律 404。
- [ ] `PublicQuotaService`：数据集/存储/文件/行列/清洗/图表/导出；AI 四层计量；超限 429。
- [ ] `DatasetService`：列表/详情/预览/列/统计统一走可见条件；上传写 owner_type/anon_session_id；demo 禁止删除；新增 demo 创建与导出行读取。
- [ ] `CleanService`：规则、执行、回滚、日志全部归属校验；demo 禁止写；清洗配额；写 `clean_log.anon_session_id`。
- [ ] `AnalysisService`：统计与图表归属；图表配额；写 `chart_view`（demo 允许生成临时图表）。
- 验证：单测跨会话 404、demo 写操作 403、各配额 429。

## T5 清理状态机与演示数据

- [ ] `PublicCleanupService`：
  - 认领会话 → 生成 `cleanup_item`（仅元数据中的精确表名：`dataset.table_name`、`clean_log.backup_table`）；
  - 逐项 `DROP TABLE IF EXISTS` / 按主键删除，单项失败记录 `error_type` 不中断；
  - 全部完成后删除剩余元数据并标记 `purged`；墓碑 7 天后删除；
  - `purging` 超 10 分钟可重新认领；
  - 孤儿表只统计进 `cleanup_log`，绝不自动 DROP；
  - `GET_LOCK` 防并发。
- [ ] `AdminMaintenanceController`：private 手动触发，返回脱敏统计。
- [ ] `DemoDataService` + `demo/sales_sample.csv`：demo 只读、不可清洗/删除/导出、可统计与图表。
- 验证：故障注入三种场景（表删/元数据留、元数据删/表留、进程中断）重跑收敛；私有数据行数不变。

## T6 公开 AI 保护

- [ ] `HumanChallengeService`：
  - 生成 32 字节 secret，存 SHA-256 + session + ip_hash + expires；
  - token = `id.secret.HMAC`；
  - 验证 HMAC、会话、IP、过期；
  - 原子核销 `UPDATE ... SET used_at=NOW() WHERE id=? AND used_at IS NULL`，影响 1 行；重复使用 429。
- [ ] `PublicController`：`GET /api/public/ai/challenge`（仅 AI 开启）。
- [ ] `AiService` public 分支：默认配置、4 模板白名单、拒绝 customPrompt/variables/extraContext、输入/输出/超时上限、会话+IP+全站调用与全站 token 预算、熔断、并发信号量、503 降级、写 `ai_generation_log.anon_session_id`。
- [ ] `AiController`：config/template/history public 404；禁用 SSE。
- [ ] `GlobalExceptionHandler` 增加 503。
- 验证：单测覆盖关闭 403、白名单、重复挑战 429、超预算 429、供应商异常 503。

## T7 导出

- [ ] `ExportController`：`POST /api/export/dataset/{id}`：
  - Origin + X-Confirm + 归属 + demo 禁止 + 导出配额；
  - `text/csv;charset=UTF-8` + BOM，公式注入转义；
  - 最多 `export-max-rows`，超限 `X-Export-Truncated`；
  - 写 `export_record`；文件名 `dataset-<id>.csv`。
- 验证：越权 404、demo 403、公式转义、配额 429。

## T8 前端

- [ ] `stores/appMode.js`（public/private、ttl、aiEnabled、quotas）。
- [ ] `main.js` 挂载前初始化。
- [ ] Router：public 跳过登录、`/ai-config` 重定向；private 不变。
- [ ] `request.js`：withCredentials、public 不跳登录、429/403 提示、`withConfirm`、POST blob 导出。
- [ ] `Layout.vue` 提示条与菜单裁剪。
- [ ] `Dataset.vue` / `Clean.vue` / `Analysis.vue` 二次确认与导出。
- [ ] `AiPanel.vue`：关闭不渲染、解 PoW、带 `X-AI-Challenge`、无配置选择。
- 验证：`vite build`；浏览器检查提示条、菜单、确认框。

## T9 网关与部署

- [ ] `deploy/nginx/default.conf.tpl` + 自定义 `docker-entrypoint.d/10-dataclean-envsubst.sh`（显式变量白名单，避免误替换 `$uri/$host/$remote_addr`）。
- [ ] 分级限流（api/upload/ai + ai_conn）、请求体限制、安全头、脱敏日志、health 私网。
- [ ] `deploy/nginx/https/default.conf.tpl` + `docker-compose.https.yml`（443、证书挂载、HSTS、80→443）。
- [ ] `frontend/Dockerfile` 复制模板与 entrypoint 脚本。
- [ ] `docker-compose.yml` 透传 `APP_MODE`/`PUBLIC_*`/`NGINX_CLIENT_MAX_BODY_SIZE`；端口仅 nginx。
- [ ] `.env.example`、`.env.public.example`、`deploy/README.md`（部署、HTTPS、安全组、备份/恢复/回滚）。
- 验证：静态检查“仅 nginx ports / db_net internal / 无 latest / envsubst 白名单”。

## T10 测试、自查与交付

- [ ] 单测：会话与创建限流、归属 404、配额 429、挑战绑定与单次核销、清理状态机与故障注入、AI 公开分支、Origin 校验。
- [ ] 执行 `mvn test`、`vite build`、`node scripts/scan-secrets.mjs`、迁移演练。
- [ ] `scripts/phase3-isolation-check.ps1`：双 Cookie jar 越权断言（含 Origin 头）。
- [ ] 三轮自查：跨会话隔离、敏感信息泄露、清理与资源/AI 成本（含同 IP 批量建会话、三种清理故障）。
- [ ] `PHASE3_DELIVERY.md`：文件清单、迁移、清理说明、容量配置、部署/回滚、测试结果、已知限制。

## 完成标准

- A1–A12 全部有测试或手工证据。
- public 默认零历史数据、零配置；private 与第二阶段一致。
- 真实数据零删除、零迁入公开空间。
- Origin、PoW 单次核销、全站 AI 预算、独立 pepper、元数据精确清理、Secure Cookie 启动校验全部落地。
