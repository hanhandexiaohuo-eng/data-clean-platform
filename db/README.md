# 数据库迁移目录说明

本目录是生产环境唯一的数据库升级入口。**不再依赖人工执行 `sql/init.sql`**（该文件已改为废弃说明）。

```
db/
├── migration/                  # 版本化迁移（按版本号顺序执行）
│   ├── V1__baseline_core_schema.sql
│   ├── V2__user_table.sql
│   ├── V3__ai_key_cipher.sql
│   ├── V4__query_indexes.sql
│   ├── V5__async_task.sql
│   ├── V6__audit_fields.sql
│   ├── V7__public_workspace.sql
│   ├── V8__public_hardening.sql
│   └── V9__data_version_async.sql
├── rollback/                   # 高风险迁移的回滚脚本/说明
│   ├── R3__ai_key_cipher.sql
│   ├── R4__query_indexes.sql
│   ├── R5__async_task.sql
│   ├── R6__audit_fields.sql
│   ├── R7__public_workspace.sql
│   ├── R8__public_hardening.sql
│   └── R9__data_version_async_rollback.sql
└── README.md
```

执行器：`deploy/migrate/run-migrations.sh`（由 Compose 的 `migrate` 服务调用，成功后退出）。

## 一、迁移规则

1. 文件名必须为 `V<数字>__<字母数字下划线>.sql`，数字递增，不可跳号复用。
2. **已发布的迁移文件禁止修改**：执行器会校验 SHA-256，内容变化会直接中止。
3. 所有迁移必须**纯增量**：
   - 使用 `CREATE TABLE IF NOT EXISTS`；
   - 新增列/索引前通过 `INFORMATION_SCHEMA` 判断；
   - 禁止 `DROP TABLE` 真实业务表、`TRUNCATE`、无条件 `ALTER ... DROP COLUMN`、无条件重建表。
4. 单个迁移尽量只做一件事，便于定位失败与回滚。
5. 迁移中不得写入密钥、Token、真实连接串或原始敏感数据。
6. 新增迁移后，先在隔离临时库演练，再进入生产。

## 二、执行机制（`schema_migration`）

| 列 | 说明 |
|---|---|
| `version` | 迁移版本号（整数，主键） |
| `description` / `script` | 描述与文件名 |
| `checksum` | 文件 SHA-256；存量基线为 `BASELINE` |
| `success` | 0=失败，1=成功 |
| `execution_ms` / `installed_on` / `updated_on` | 耗时与时间戳 |

执行流程：

1. 等待 MySQL 可用并校验目标库存在；
2. 通过 `GET_LOCK` 获取命名锁，保证同一时间仅一个迁移实例；
3. 创建 `schema_migration`（若不存在）；
4. 识别存量库基线（见第三节）；
5. 按版本号顺序执行未成功版本；
6. 成功后写入/更新记录；失败写入 `success=0` 并中止，应用不会启动；
7. 已成功版本直接跳过；哈希不一致则中止。

> MySQL 的 DDL 会隐式提交，无法用事务包裹整份迁移。为满足“失败不留半完成状态”，
> 所有迁移均写成**幂等增量**：中途失败后修复原因直接重跑即可补齐，不会重复建表/加列。

## 三、存量库基线策略

存量库没有任何迁移历史时，执行器按已存在的表自动建立基线，只执行基线之后的版本：

| 检测结果 | 基线 | 后续执行 |
|---|---|---|
| 存在 `dataset` 且存在 `user` | V1–V2 视为已执行 | V3–V9 |
| 存在 `dataset`，无 `user` | V1 视为已执行 | V2–V9 |
| 空库 / 无核心表 | 无基线 | V1–V9 |

基线只写入 `schema_migration` 记录，**不会执行任何 DDL、不会改动已有表**。
若某个存量环境曾手工执行过 V3 的 DDL，V3 脚本本身幂等，重复执行安全。

## 四、版本清单

| 版本 | 内容 | 类型 | 回滚方式 |
|---|---|---|---|
| V1 | 8 张核心表 + 内置 Prompt + 默认 AI 配置 | 基线建表 | 不支持原地回滚，恢复备份 |
| V2 | `user` 表 | 基线建表 | 不支持原地回滚，恢复备份 |
| V3 | `ai_config.api_key_cipher` / `api_key_mask`，放宽明文列为可空 | 新增列 | `R3`（确认无密文）或恢复备份 |
| V4 | 4 个查询索引 | 新增索引 | `R4`（仅删索引，无数据影响） |
| V5 | `async_task` 异步任务表（预留，未接入业务） | 新增表 | `R5`（删除表，破坏性，先备份） |
| V6 | `dataset.created_by/updated_by`、`clean_rule.created_by`、`clean_log.operator_id` | 新增可空列 | `R6`（删除审计列，破坏性，先备份） |
| V7 | 公开模式 7 张新表（`anon_session`、`cleanup_item`、`ai_challenge`、`chart_view`、`export_record`、`anon_usage_daily`、`cleanup_log`）+ `dataset/clean_rule/clean_log/ai_generation_log` 会话归属列与索引 | 新增表/列/索引 | `R7`（删除第三阶段对象，破坏性，先备份） |
| V8 | 会话创建挑战：`ai_challenge.purpose`（默认 `ai`）、`anon_session_id` 允许 NULL、`idx_purpose_expires` 索引 | 纯增量列/索引 | `R8`（删除列/索引；不回退 NULL 约束，执行前确认无 NULL 记录） |
| V9 | `dataset.current_version_id` + `dataset_version`、`dataset_version_rule`、`async_job`、`backup_table`、`stats_cache`、`audit_log` 6 张新表；为存量数据集回填 version 1 记录 | 纯增量表/列 + 元数据回填 | `R9`（删除新增表与版本指针列，破坏性，先备份；历史版本物理表变孤儿，需人工确认） |
| V10 | 可视化与 AI 治理 10 张新表：`saved_chart`、`dashboard`、`dashboard_item`、`ai_provider`、`ai_model`、`ai_scene_route`、`ai_call_log`、`ai_quota`、`ai_usage`、`ai_circuit_state`；幂等 DDL + 索引 | 纯增量新增表 | `R10`（仅 `DROP TABLE IF EXISTS` 本阶段 10 张表，不触碰既有对象；破坏性，先备份） |

### V10 演练记录（2026-11-21，隔离实例 127.0.0.1:13307）

| 场景 | 结果 |
|---|---|
| 空库 V1→V10 | 成功，新增表 10/10，总表 33 |
| 存量库 V1→V9 + 存量行 → V10 | 成功，23→33，存量数据保留 |
| 重复执行 V10 | 退出码 0，结构不变 |
| 截断 60% 执行 → 完整重试 | 首次中断（5/10），重试后 10/10 |
| 追加非法 SQL → 完整重试 | 首次失败，重试后 10/10 |
| R10 回滚 → 再执行 V10 | 回滚后 0/10、总表 23；再升级 10/10、总表 33 |

演练未连接生产库，未修改既有表数据；完整日志见部署环境中的演练记录。

V1 对应历史 `sql/init.sql` 的表结构；V2 对应 `sql/migration_v3_user.sql`；
V3 对应 `sql/migration_v4_ai_key_cipher.sql`。历史文件仅保留追溯，不再作为执行入口。

## 五、迁移前备份建议

执行升级/迁移前至少完成：

- [ ] `mysqldump --single-transaction --routines --triggers` 备份整库；
- [ ] 备份上传目录与当前部署版本信息；
- [ ] 确认 `AI_SECRET_KEY` 可用且与数据库备份分开保存；
- [ ] 确认磁盘空间充足；
- [ ] 在隔离临时库演练新增迁移与回滚脚本。

执行器不会自动备份、不会删除/覆盖数据库，备份完全由运维人员确认执行。

## 六、回滚原则

1. **首选恢复备份**：任何结构性回滚都不如恢复迁移前备份可靠。
2. 纯新增列/索引可按 `db/rollback/` 脚本原地回滚；执行前确认无业务数据依赖。
3. V5/V6 回滚会丢数据，必须先备份并确认业务可接受。
4. V1/V2 回滚意味着删除核心业务表，禁止原地执行，只能恢复备份。
5. 回滚后需同步回退应用版本，并重新验证 `/api/health`。
6. 原地回滚脚本不会修改 `schema_migration` 历史。若回滚后需要重新应用该版本，
   必须先确认结构已恢复，再删除对应版本记录（或直接恢复备份），否则执行器会按历史跳过。

## 七、新增迁移步骤

```bash
# 1. 新建 V9__your_change.sql（纯增量、幂等）
# 2. 如需回滚，新增 db/rollback/R9__your_change.sql 或写明恢复步骤
# 3. 在临时库演练
docker compose run --rm migrate
# 4. 校验版本与结构
docker compose exec mysql sh -c \
  'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" -D "$MYSQL_DATABASE" -e "SELECT version,description,success,installed_on FROM schema_migration ORDER BY version"'
```
