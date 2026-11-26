# 第二阶段交付：数据库迁移与容器化部署基线

> 项目：`data-clean-platform`
> 阶段目标：建立可重复部署、可恢复、端口收敛的运行基础。
> 明确不做：数据清洗/导入/回滚执行、改写现有数据集、删除真实数据、取消登录页、开放公网、访问令牌 Cookie、自签名生产 HTTPS。

## 一、结论

| 项目 | 状态 |
|---|---|
| 版本化迁移体系（等效 Flyway 迁移容器） | 完成 |
| 迁移幂等性 / 存量基线 / 失败可重试 / 校验和防篡改 | 完成并实测 |
| 回滚脚本与安全回退说明 | 完成（R3–R6，V1/V2 仅可恢复备份） |
| Docker Compose 运行基线（mysql/backend/nginx/migrate） | 完成（**未在本机实际 `docker compose up`，见风险 R-1**） |
| Nginx 同源反代、安全头、隐藏版本号、健康检查隔离 | 完成 |
| `.env` 注入、`.dockerignore`、示例变量模板 | 完成 |
| 日志滚动、生产错误不泄露堆栈/路径 | 完成并实测 |
| 最小 `/api/health` | 完成并实测 |
| 后端构建与测试 / 前端构建 / 敏感信息扫描 | 通过 |

## 二、交付文件清单

### 新增

| 文件 | 作用 |
|---|---|
| `docker-compose.yml` | 生产编排：mysql / migrate / backend / nginx，命名网络与命名卷 |
| `.env.example` | 生产环境变量模板（含说明、必填标记、格式、占位符） |
| `.dockerignore` | 排除 `.env`、真实配置、密钥、上传、日志、备份、`node_modules`、构建产物 |
| `backend/Dockerfile` | 多阶段构建（Maven 3.9 + JDK21 → JRE21 Alpine），非 root `app(10001)` |
| `frontend/Dockerfile` | 多阶段构建（Node 22 → Nginx 1.27），静态前端 + 反代配置 |
| `deploy/migrate/Dockerfile` | 迁移执行器镜像（`mysql:8.4` 客户端 + 脚本，非 root） |
| `deploy/migrate/run-migrations.sh` | 迁移执行器：等待数据库、`GET_LOCK` 串行化、基线识别、SHA-256 校验、失败记录 |
| `deploy/nginx/default.conf` | 同源反代、安全头、缓存策略、健康检查隔离、限流/HTTPS 预留 |
| `deploy/README.md` | 部署、升级、备份、恢复、回滚、日志运维说明 |
| `db/migration/V1__baseline_core_schema.sql` | 核心 8 表 + 内置 Prompt 模板 |
| `db/migration/V2__user_table.sql` | 用户表 |
| `db/migration/V3__ai_key_cipher.sql` | AI Key 密文/掩码字段 |
| `db/migration/V4__query_indexes.sql` | 4 个查询索引 |
| `db/migration/V5__async_task.sql` | 异步任务表（预留） |
| `db/migration/V6__audit_fields.sql` | 审计预留字段（created_by/updated_by/operator_id） |
| `db/rollback/R3..R6__*.sql` | 对应高风险迁移的回滚脚本/说明 |
| `db/README.md` | 迁移规则、版本清单、基线策略、备份检查、回滚原则 |
| `scripts/rehearse-migrations.ps1` | 隔离临时库的迁移演练（幂等、基线、回滚、业务数据保留） |
| `backend/.../controller/HealthController.java` | 最小健康检查 |
| `backend/.../test/.../HealthControllerTest.java` | 健康检查 3 个用例 |
| `backend/src/main/resources/logback-spring.xml` | 控制台 + 按天/大小滚动、容量上限、保留周期 |

### 修改

| 文件 | 变更 |
|---|---|
| `backend/src/main/resources/application.yml` | 端口/优雅停机/错误响应收紧/CORS/日志；修正 `include-exception` 为布尔值 |
| `backend/src/main/resources/application.yml.example` | 同步脱敏模板 |
| `backend/.../config/SecurityConfig.java` | 放行 `/api/health`，其余维持认证 |
| `backend/.../config/CorsConfig.java` | 改为 `CorsConfigurationSource`，默认不放行来源、`allowCredentials=false` |
| `.gitignore` | 忽略 `backups/` `data/` `certs/` `*.dump`，并保留迁移 SQL |
| `README.md` | 启动方式改为 Docker Compose + 版本化迁移 |
| `sql/init.sql` | 标记废弃，不再包含可执行 DROP/建表语句 |
| `scripts/scan-secrets.mjs` | 扩展扫描范围（`.sh`/`.ps1`/Dockerfile），修正变量引用误报 |

## 三、迁移体系要点

- 版本文件 `db/migration/V<数字>__<描述>.sql`，按数字升序执行；已发布文件禁止修改，变更须新建更高版本。
- 历史表 `schema_migration`：版本、描述、SHA-256 校验和、耗时、`success`。
- 新库：从 V1 顺序执行。
- 存量库首次接入：自动识别基线（仅核心表 → 基线 1；核心表 + 用户表 → 基线 2），标记为已执行但不重放建表。
- 重复启动：已成功版本跳过；文件内容变化触发校验和失败并中止。
- 失败：写入 `success=0` 后退出，阻断后端启动；修复后重跑会重试该版本（迁移均为幂等增量）。
- 并发：`GET_LOCK` 串行化，超时（`MIGRATION_LOCK_TIMEOUT`，默认 60s）即失败。
- 全部迁移纯增量：`CREATE TABLE IF NOT EXISTS`、`INFORMATION_SCHEMA` 判断后加列/索引、`INSERT IGNORE`；无 DROP TABLE、无 TRUNCATE、无 DELETE 业务数据、无无条件重建。
- 迁移前必须完成备份检查（见 `deploy/README.md` 第五节）；本阶段不自动执行任何备份或清理。

回滚矩阵：

| 迁移 | 回滚方式 | 说明 |
|---|---|---|
| V3 / V6（新增可空列） | `db/rollback/R3` / `R6` 原地回滚 | 回滚前确认无数据依赖新增列 |
| V4（索引） | `R4` 幂等 DROP INDEX | 不涉及数据 |
| V5（新表） | `R5` DROP TABLE | **破坏性**，会删除任务记录，必须先备份 |
| V1 / V2（基线建表） | 不支持原地回滚 | 只能恢复迁移前备份 |

> 原地回滚不会改写 `schema_migration` 历史；回滚后如重跑同一版本，需先删除对应历史记录或直接恢复备份，详见 `db/README.md`。

## 四、测试与验收结果

| 检查 | 命令/方式 | 结果 |
|---|---|---|
| 后端编译打包 | `mvn -DskipTests package` | 通过 |
| 后端测试 | `mvn test` | **58 个用例，0 失败 / 0 错误**（10 个测试类） |
| 健康检查实测 | 打包 jar + 临时 MySQL 8.4（3307） | `GET /api/health` → `200 {"status":"UP","checks":{"database":"UP"}}` |
| 未认证访问 | `GET /api/datasets` | `403`（未放行） |
| 错误响应 | 非法 JSON POST `/api/auth/login` | `400 {"code":400,"message":"请求体格式不正确"}`，无堆栈/路径 |
| 前端构建 | `vite build` | 通过，`dist/` 产物正常 |
| 迁移演练（空库） | `scripts/rehearse-migrations.ps1`（临时库） | 通过：V1–V6 全量、二次执行全部跳过、标记数据保留、模板数=5 |
| 迁移演练（存量库） | 同上 | 通过：识别基线=2，仅执行 V3–V6，重复执行幂等 |
| 回滚演练 | 同上 | 通过：R4/R5/R6 可执行 |
| 执行器实测 | Git Bash 调 `run-migrations.sh` | 通过：跳过已执行、校验和篡改中止、`success=0` 重试、锁竞争超时 |
| Compose 不变量 | PyYAML 静态断言 | 通过（见第五节） |
| 敏感信息扫描 | `node scripts/scan-secrets.mjs` | 通过：未发现疑似明文凭据 |
| `docker compose build/up` | — | **未执行：本机未安装 Docker/WSL** |

## 五、三轮自查

### 第 1 轮：配置与敏感信息

- `.env.example` 覆盖数据库、JWT、AI 主密钥、上传、日志、CORS、初始管理员、端口；每项均标注必填性、格式与占位符；`AI_SECRET_KEY` 明确要求 Base64 32 字节。
- 项目根目录不存在 `.env`；`.dockerignore` 排除 `.env*`（保留 `.env.example`）、`application.yml`、密钥文件、`node_modules`、`target`/`dist`、上传/日志/备份。
- 后端镜像不 COPY 真实配置，使用 `application.yml.example` 作为骨架，全部值由环境变量注入；Compose 不使用 `env_file`，MySQL root 密码不进入后端容器。
- 密码/令牌只经环境变量注入；日志中无密码、Cookie、JWT、AI Key、完整 SQL（MyBatis 已切换 `NoLoggingImpl`）。
- 生产错误响应 `include-message/stacktrace/binding-errors=never`、`include-exception=false`；实测 400 响应仅含业务提示。
- `scripts/scan-secrets.mjs` 全量扫描通过。
- 结论：**通过**。

### 第 2 轮：迁移幂等性与回滚性

- 空库：V1–V6 全量执行成功；第二次执行 6 个版本全部跳过。
- 存量库：识别基线 2，仅执行 V3–V6；重复执行无变化。
- 业务数据：演练前后标记数据行数与内容保持不变。
- 失败路径：篡改已执行文件 → 校验和中止；人为制造失败 → `success=0` 且不阻塞后续修复重试。
- 并发：锁占用时按超时失败并释放锁，不残留会话。
- 回滚：R4（索引）、R5（表）、R6（列）脚本实测可执行；R3 提供密文行数检查后的人工确认步骤。
- 结论：**通过**（V1/V2 不支持原地回滚，已在文档标注只能恢复备份）。

### 第 3 轮：容器网络与端口暴露

静态断言（`docker-compose.yml`）：

- `mysql`：无 `ports`，仅 `db_net`，命名卷 `dataclean_mysql_data`；
- `backend`：无 `ports`，`db_net + app_net`，非 root 镜像，依赖 `mysql healthy` 与 `migrate completed`；
- `nginx`：唯一发布端口 `${HTTP_PORT:-80}:80`，仅 `app_net`，依赖 `backend healthy`；
- `migrate`：仅 `db_net`、无端口、`restart: "no"`、`read_only`；
- `db_net` 为 `internal: true`（数据库不可出网），三个命名卷；
- 三个常驻服务均配置健康检查；镜像均固定大版本，无 `latest`。

实测（非容器）：

- 健康接口仅返回 `status` 与 `checks.database`；
- 未认证接口 403；错误响应不泄露内部信息。

结论：**配置层面通过；容器运行层面的端口不可达需在具备 Docker 的环境复核**（风险 R-1）。

## 六、首次部署 / 升级 / 备份 / 恢复 / 回滚

详见 [`deploy/README.md`](deploy/README.md)，要点：

```bash
cp .env.example .env      # 填写真实值；不得提交
docker compose config --quiet
docker compose build
docker compose up -d      # 单命令：mysql healthy → migrate 成功退出 → backend healthy → nginx healthy
docker compose ps
```

- 升级：先备份 → `docker compose build` → `docker compose up -d`（migrate 只执行未完成增量）→ 查看 `migrate`/`backend` 日志。
- 备份：`mysqldump --single-transaction` 导出到 `backups/`；上传目录单独打包；备份与 `AI_SECRET_KEY` 分开存放。
- 恢复：停写 → 优先在临时库演练 → 导入备份 → 恢复服务。
- 回滚：优先恢复迁移前备份；纯新增列/索引用 `db/rollback/`；**禁止**未备份执行 `docker compose down -v`。

## 七、未完成风险项

| 编号 | 风险 | 等级 | 说明 / 后续处理 |
|---|---|---|---|
| R-1 | 本机无 Docker/WSL，未实际执行 `docker compose build/up`、`nginx -t`、镜像内非 root 与健康检查实测 | 中 | 需在具备 Docker 的环境按 `deploy/README.md` 第二节验收：三服务 healthy、宿主机不可直连 8080/3306、容器重建后数据仍在 |
| R-2 | `/api/ai/generate/stream` 仍为 `permitAll` | 中 | EventSource 无法携带自定义头，属既有实现；需在后继访问控制阶段收敛（短期建议改为一次性签名 URL 或 Cookie 会话） |
| R-3 | `/api/health` 的 Nginx 来源限制在 Docker Desktop / 前置 NAT 场景下，客户端源地址可能被改写为网关地址 | 低 | 响应不含敏感信息；生产建议置于可信 LB 后并按真实来源重配，或仅容器内探测 |
| R-4 | Spring Boot 自动装配的 in-memory 用户会在启动日志打印随机密码 | 低 | 未启用 HTTP Basic/Form 登录，不能用于认证；后续可通过排除 `UserDetailsServiceAutoConfiguration` 消除日志噪音 |
| R-5 | MySQL 应用账号在容器初始化时获得该库全部权限（含 DDL），供 migrate 使用 | 低 | 迁移需要 DDL；如需最小权限，后续可拆分「迁移账号」与「运行账号」 |
| R-6 | V5 回滚会删除 `async_task` 表 | 低 | 已在脚本与文档中标注破坏性，必须先备份 |
| R-7 | 未启用限流、请求体之外的 WAF/防刷、HTTPS | 按计划 | 属后续阶段；Nginx 已预留 `limit_req` 与 443 配置位置 |
| R-8 | 旧文件 `sql/migration_v3_user.sql`、`sql/migration_v4_ai_key_cipher.sql`、`sql/rollback_v4_ai_key_cipher.sql`、`scripts/rehearse-migration.ps1` 保留 | 低 | 仅作追溯，不再被执行；`sql/init.sql` 已废弃 |
| R-9 | 无自动备份调度 | 按计划 | 本阶段明确不自动执行备份/清理 |

## 八、敏感信息声明

本阶段所有新增/修改文件仅使用占位符（如 `CHANGE_ME_*`），未写入任何真实密码、Token、数据库连接串或 API Key；测试使用的临时数据库、临时账号与临时密钥均已在验证后销毁，且不进入仓库。
