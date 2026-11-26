# 第五阶段交付说明：数据正确性、版本化清洗与异步任务

> 交付日期：2026-11-20
> 前置状态：第一至第四阶段代码已交付；第四阶段生产发布门禁**尚未解除**。
> 本阶段全部验证只在**隔离 MySQL（127.0.0.1:13306）**、**临时上传目录**与**隔离后端（127.0.0.1:18080）**中完成，未连接、未修改任何真实生产库与生产上传卷，未执行任何真实清洗/回滚。

---

## 一、交付范围

| 章节 | 要求 | 落地情况 |
|---|---|---|
| 一、数据正确性 | 字段标准化白名单、表/列/类型/聚合/排序全白名单、失败不静默、演示只读、公开数据仅本会话可处理 | 已实现 |
| 二、版本与可恢复清洗 | 版本模型、物理隔离、回滚切版本、任务/版本/表关联、保留策略、孤儿表只上报 | 已实现 |
| 三、异步导入与清洗 | 持久化任务、状态机、进度、重启恢复、取消检查点、前端任务中心、公私配额 | 已实现 |
| 四、大文件与统计性能 | CSV/Excel 流式、超阈值异步、统计单次扫描、近似分位数、图表限点、版本化缓存 | 已实现 |
| 五、接口/审计/安全 | 全接口归属校验、跨会话 404、二次确认、审计脱敏、错误不外泄 | 已实现 |
| 六、迁移与回滚 | 纯增量 V9、空库/存量/重复/失败重试验证、R9 回滚脚本与演练 | 已实现 |
| 七、验收 | 10 万行导入、取消/重试/恢复、规则成功/失败/取消、跨会话、统计 P50/P95、构建与扫描 | 见第八节矩阵 |
| 八、交付 | 文件清单、迁移、状态机、版本模型、保留策略、部署/回滚/恢复手册、三态结果、三轮自查 | 本文档 |

---

## 二、修改文件清单

### 后端（`backend/src/main/java/com/dataclean/`）

**新增**

| 文件 | 作用 |
|---|---|
| `job/JobStatus.java` | 任务状态常量（PENDING/RUNNING/SUCCEEDED/FAILED/CANCEL_REQUESTED/CANCELLED/RECOVERABLE） |
| `job/JobCancelledException.java` | 取消信号异常，用于在安全检查点中断任务 |
| `config/JobProperties.java` | `app.jobs.*` 配置绑定（线程数、配额、保留策略等） |
| `entity/AsyncJob.java` | 异步任务实体 |
| `entity/DatasetVersion.java` | 数据集版本实体（版本号/输入输出/规则快照/校验摘要/状态） |
| `entity/DatasetVersionRule.java` | 版本规则快照实体 |
| `entity/StatsCacheEntry.java` | 统计缓存实体（按数据集 + 版本） |
| `entity/BackupTableRecord.java` | 备份表登记实体（兼容旧备份语义，新流程不再生成备份表） |
| `entity/AuditLog.java` | 审计日志实体 |
| `mapper/AsyncJobMapper.java` | 任务领取/进度/完成/取消/重试/恢复/配额计数 |
| `mapper/DatasetVersionMapper.java` | 版本 CRUD 与状态查询 |
| `mapper/DatasetVersionRuleMapper.java` | 规则快照 CRUD |
| `mapper/StatsCacheMapper.java` | 统计缓存 CRUD（按数据集+版本失效） |
| `mapper/BackupTableRecordMapper.java` | 备份表登记 CRUD |
| `mapper/AuditLogMapper.java` | 审计写入与查询 |
| `service/JobService.java` | 任务创建（配额）、查询、取消、重试、进度、检查点、终态、启动恢复、payload 读写 |
| `service/JobWorker.java` | 固定线程池轮询执行 IMPORT/CLEAN/RESTORE；取消/失败清理；重启恢复 |
| `service/VersionService.java` | 初始版本、BUILDING、激活切指针、列元数据刷新、校验和、版本列表/删除、保留策略、孤儿表上报 |
| `service/StreamingImportService.java` | CSV/Excel 流式解析 + 批量写入 + 进度 + 取消检查点 + 行列/压缩比限制 |
| `controller/JobController.java` | `/api/job` 任务中心接口 |
| `controller/VersionController.java` | `/api/dataset/{id}/versions` 版本列表/恢复/删除 |
| `dto/AsyncImportVO.java` | 异步导入响应（jobId/datasetId/async） |

**修改**

| 文件 | 变更 |
|---|---|
| `service/DatasetService.java` | 异步导入判定与提交、占位数据集、临时文件、同步导入建初始版本、列表过滤、删除级联版本/缓存/任务、统计委托 |
| `service/CleanService.java` | 预览走临时克隆表、正式清洗转异步任务、规则白名单校验、未知规则 400、参数化 UPDATE、遗留临时表清理、旧备份回滚停用 |
| `service/AnalysisService.java` | 移除进程内列缓存、统计单条聚合 + 版本化缓存 + 大数据抽样、分位数算法、图表限点与提示 |
| `service/PublicCleanupService.java` | 会话清理覆盖版本表/版本元数据/统计缓存/任务/审计/备份登记；归属证明；孤儿只上报 |
| `service/AuditService.java` | 任务/版本操作审计动作 |
| `config/PublicModeConfig.java` | 注册任务与版本相关配额/限制 |
| `controller/DatasetController.java` | 上传支持异步返回、`/upload-async`、统计改走 AnalysisService |
| `entity/Dataset.java` | `currentVersionId` 字段 |
| `mapper/DatasetMapper.java` | 孤儿表统计排除版本表/备份表 |
| `dto/ChartDataVO.java` | `notice`（限点/Top N 提示） |
| `dto/CleanResultVO.java` | `jobId`、`currentVersionId` |
| `resources/application.yml` / `application.yml.example` | 新增 `app.upload.max-*`、`app.jobs.*` 配置段 |

### 测试（`backend/src/test/java/com/dataclean/`）

| 文件 | 说明 |
|---|---|
| `service/JobServiceLifecycleTest.java` | 新增 10 例：跨会话 404、缺任务 404、终态不可取消、成功不可重试、重试耗尽 429、失败重试重置 PENDING、检查点取消/超时 408/任务丢失 |
| `service/AnalysisServiceStatsTest.java` | 新增：单条聚合 SQL、缓存命中、10 万+ 行抽样与 `approximate` 标识 |
| `service/CleanServiceRuleValidationTest.java` | 新增：非法类型元数据 400 且不执行 DDL、合法类型原样回填 |
| `service/AnalysisServiceSecurityTest.java` | 构造器适配新依赖 |

### 数据库

| 文件 | 说明 |
|---|---|
| `db/migration/V9__data_version_async.sql` | 纯增量：`dataset.current_version_id` + 6 张新表 + 存量回填 version 1 |
| `db/rollback/R9__data_version_async_rollback.sql` | 破坏性回滚脚本（含备份要求、取证 SQL、幂等删列、回滚后校验） |
| `db/README.md` | 迁移清单、基线判定表、V9/R9 说明 |

### 前端（`frontend/src/`）

| 文件 | 说明 |
|---|---|
| `api/job.js` | 新增：任务列表/详情/按数据集/取消/重试 |
| `api/dataset.js` | 新增：版本列表/恢复/删除 |
| `views/Jobs.vue` | 新增：任务中心（状态、进度、阶段、失败原因、取消/重试、3 秒轮询） |
| `router/index.js` | `/jobs` 路由 |
| `components/layout/Layout.vue` | 「任务中心」菜单 |
| `views/Dataset.vue` | 状态 0/2 标签、异步上传提示、处理中自动刷新、版本历史对话框（恢复/删除二次确认） |
| `views/Clean.vue` | 清洗任务面板（进度/阶段/错误/取消/重试）、轮询、移除旧备份回滚 |

### 运维文档

`deploy/README.md` 新增第十一节（配置、任务与版本、故障恢复手册、备份与 R9 回滚）。

---

## 三、迁移与回滚

### 1. V9 迁移（纯增量）

- 新表：`dataset_version`、`dataset_version_rule`、`async_job`、`backup_table`、`stats_cache`、`audit_log`。
- 新列：`dataset.current_version_id`。
- 存量回填：仅对 `table_name` 非空的数据集插入 `version_no=1 / ACTIVE / BACKFILL` 记录（幂等 `INSERT ... SELECT ... WHERE NOT EXISTS`）。

### 2. 迁移演练结果（均已实际操作）

| 场景 | 操作 | 结果 |
|---|---|---|
| 空库 | 新库 `dc_p5_it` 依序执行 V1→V9 | 全部成功，6 张新表 + `current_version_id` 存在 |
| 重复执行 | 再次执行 V9 | 成功（幂等） |
| 失败重试 | 删除 `dataset_version_rule`、`stats_cache` 后重跑 V9 | 成功，6 张表恢复齐备 |
| 存量库 | `dc_p5_up` 执行 V1–V8 + 插入 `data_legacy_1` 数据集后执行 V9 | 回填 `1 / 1 / ACTIVE / BACKFILL / data_legacy_1` |
| 回滚演练 | 新库 `dc_p5_r9` V1→V9，执行 R9，再重跑 V9 | 回滚前 6 表/1 列 → 回滚后 0 表/0 列 → 重跑 V9 后 6 表/1 列 |

### 3. R9 回滚（破坏性，必须先备份）

1. 停止任务执行器：`JOBS_ENABLED=false` 并重启后端，确认无 `PENDING/RUNNING/CANCEL_REQUESTED`。
2. 全量备份 + 导出取证：`audit_log`、`dataset_version`、`async_job`。
3. 执行 `db/rollback/R9__data_version_async_rollback.sql`（脚本内含备份提示、取证 SQL、活动任务检查、幂等删列与回滚后校验）。
4. 历史版本物理表**不会被删除**，将变为孤儿表；`dataset.table_name` 始终指向当前生效表，旧版应用读取不受影响。
5. 需要重新升级时重跑 V9：仅按 `dataset.table_name` 回填 version 1，历史版本链不可恢复。

---

## 四、任务状态机

```
                 create
                   │
                   ▼
              ┌─────────┐  claim(markRunning)  ┌─────────┐
              │ PENDING │ ───────────────────► │ RUNNING │
              └─────────┘                      └─────────┘
                   │                            │   │   │
          cancelPending(未执行)                 │   │   └──► FAILED（错误码 + 安全摘要）
                   ▼                            │   │
              ┌───────────┐      checkpoint     │   └──► SUCCEEDED（输出版本/校验摘要）
              │ CANCELLED │ ◄───────────────── │
              └───────────┘   (CANCEL_REQUESTED)│
                                                │  进程重启（recoverRunning）
                                                ▼
                                          RECOVERABLE（可重试）
```

- 取消：`POST /api/job/{id}/cancel` 置 `CANCEL_REQUESTED`；Worker 在批次/工作表检查点抛 `JobCancelledException`，清理半成品后置 `CANCELLED`。
- 重启：启动时把遗留 `RUNNING` 置 `RECOVERABLE`（`error_code=PROCESS_RESTART`），`CANCEL_REQUESTED` 直接置 `CANCELLED`。
- 重试：仅 `FAILED/RECOVERABLE/CANCELLED` 可重试，超过 `public/private-max-retries` 返回 429。
- 权限：任务查询/取消/重试均按数据集归属校验，公开模式按匿名会话过滤，跨会话统一 404。
- 超时：超过 `JOBS_MAX_RUNTIME_SECONDS` 在检查点抛 408 并转可重试。

---

## 五、版本模型与保留策略

### 1. 模型

`dataset_version` 记录：数据集 ID、`version_no`、输入版本 ID、输出物理表、规则快照哈希、发起/完成时间、操作者（私有用户/匿名会话）、影响行/列数、状态（BUILDING/ACTIVE/ARCHIVED）、错误摘要、校验摘要；`dataset_version_rule` 保存每条规则的完整快照。

- 每次导入/清洗生成独立物理表 `data_<name>_<ts>_v<n>`，不覆盖旧表。
- 清洗：`CREATE TABLE new LIKE src` → `INSERT ... SELECT` → 逐规则应用（带检查点）→ 计算行数/校验和 → `activate`（旧 ACTIVE→ARCHIVED，新版本 ACTIVE，更新 `dataset.table_name/current_version_id/row_count`，刷新列元数据，删除统计缓存）。
- 恢复：只切换 `current_version_id` 与表指针，要求目标版本非 BUILDING、物理表存在、校验摘要一致。
- 取消/失败：删除本次 BUILDING 版本表、版本行、规则快照（按 versionId + 按 `job_id` 兜底），**绝不触碰 ACTIVE**。

### 2. 保留策略

| 归属 | 策略 |
|---|---|
| 公开（匿名会话） | 版本随会话 TTL 清理，清理范围含版本表、版本元数据、规则快照、统计缓存、任务、审计、备份登记 |
| 私有 | `JOBS_VERSION_KEEP`（默认 5）+ `JOBS_VERSION_MAX_AGE_DAYS`（默认 30），每日 03:30 定时清理，至少保留 1 个可恢复版本 |
| 手动删除 | `DELETE /versions/{id}?confirm=true`；ACTIVE/BUILDING 不可删，版本总数 ≤2 时拒绝 |
| 孤儿表 | 扫描 `data_*` 且无 `dataset`/`dataset_version`/`backup_table` 归属，**只上报**（`dc_orphan_tables`），不自动删除 |

---

## 六、部署步骤

1. **数据库**：执行 `db/migration/V9__data_version_async.sql`（按 `db/README.md` 基线判定选择起点），确认 6 张新表与 `dataset.current_version_id`。
2. **配置**：按需设置 `JOBS_*`、`UPLOAD_MAX_ROWS`、`APP_MODE`、`CORS_ALLOWED_ORIGINS`（公开模式还需 `PUBLIC_ALLOWED_ORIGIN`，必须 https）。默认值可直接启动。
3. **后端**：`mvn clean package`（JDK 21）→ 启动 jar；确认日志出现 `任务执行器已启动`、`演示数据集就绪`。
4. **前端**：`npm run build`（本机 npm 异常时用 `node node_modules/vite/bin/vite.js build`）→ 发布 `dist`。
5. **冒烟**：上传小 CSV（同步）、上传大 CSV（异步并见任务中心）、执行一次清洗、查看版本列表、恢复旧版本。
6. **回退**：应用回滚后按 R9 处理元数据；生产环境执行前必须完成备份与审计导出。

---

## 七、故障恢复手册

详见 `deploy/README.md` 第十一节第 3 小节，摘要：

| 现象 | 处理 |
|---|---|
| 重启后任务 `RECOVERABLE` | 直接重试；临时文件已过 TTL 的导入任务需重新上传 |
| 任务长时间 `RUNNING` | 等待超时（默认 30 分钟）自动转可重试；检查 Worker 线程与数据库连接 |
| 清洗 `FAILED` 但数据未变 | 属预期：半成品已清理，当前版本不变；重试耗尽后新建清洗任务 |
| 取消未立即生效 | 在安全检查点生效，最多等待一个批次；取消后无残留表/版本 |
| 恢复版本失败 | 检查版本非 BUILDING、物理表存在、校验摘要一致（不一致返回 400） |
| 统计 `approximate=true` | 行数 >10 万时基于 5 万行抽样；需精确值请缩小数据集 |
| 维护窗口 | `JOBS_ENABLED=false` 重启；PENDING 任务保留，恢复后继续执行 |

---

## 八、实测结果矩阵

> 三态严格区分：**已实际验证** = 在隔离环境实际执行并观察到结果；**仅静态检查** = 只阅读代码/配置推断；**未验证** = 未执行。

### 1. 导入与取消

| 项目 | 结果 | 证据 |
|---|---|---|
| 10 万行 CSV 异步导入 | 已实际验证 | `orders_100k.csv`（6.3MB，7 列）→ job 1 `SUCCEEDED`，耗时 8869ms；数据集 `rowCount=100000`、`columnCount=7`、`status=1`；版本 1 `IMPORT`，校验和 `100000:2988276222` |
| 取消导入 | 已实际验证 | job 6 取消于已处理 4200 行；最终 `CANCELLED`；数据集 `status=2`、版本数 0；库内无 `data_orders_cancel2%` 残留表 |
| 同步小文件导入 | 已实际验证 | `small_100.csv`（100 行）同步返回数据集 5，`rowCount=100`，版本 4 `IMPORT` |
| 超 10 万行上限 | 仅静态检查 | 代码在超过 `maxRows` 时抛 400；未构造超限文件实测 |
| Excel 流式读取（工作表数/行数/单元格/压缩比） | 仅静态检查 | 代码路径与静态块已实现；未做运行期大 Excel 实测 |

### 2. 清洗、版本与恢复

| 项目 | 结果 | 证据 |
|---|---|---|
| 清洗生成新版本 | 已实际验证 | job 3 `SUCCEEDED` → 版本 3 ACTIVE，校验和与版本 1 一致（列标准化为幂等），`clean_log.backup_table=''` |
| 恢复历史版本 | 已实际验证 | job 4 `SUCCEEDED` → `currentVersionId=1`，版本 1 ACTIVE、版本 3 ARCHIVED，校验和 `100000:2988276222` 一致 |
| 清洗失败不污染数据 | 已实际验证 | job 7/job 10 `FAILED(BadSqlGrammarException→INTERNAL)`；数据集仍指向版本 4；无 BUILDING 版本、无残留表、无孤儿规则快照 |
| 失败重试与上限 | 已实际验证 | 重试 2 次均重新 `PENDING→RUNNING→FAILED`，第 3 次返回 429；`retry_count` 递增 |
| 非法字段类型（DDL 注入） | 已实际验证 | `targetType="VARCHAR(10); DROP TABLE ..."` → 400，未执行任何 DDL |
| 版本删除保护 | 已实际验证 | 删除 ACTIVE 版本 400；仅剩 2 个时删除第 3 个成功后，再删至仅 1 个返回 400；删除后物理表同步 DROP |
| 服务重启恢复 | 已实际验证 | 模拟 RUNNING → 重启后 `RECOVERABLE(PROCESS_RESTART)`；模拟 CANCEL_REQUESTED → 重启后 `CANCELLED` |
| 私有版本定时保留 | 仅静态检查 | `@Scheduled` 与 `JOBS_VERSION_KEEP/MAX_AGE_DAYS` 已实现；未在私有模式运行满周期 |

### 3. 权限与隔离

| 项目 | 结果 | 证据 |
|---|---|---|
| 跨会话读取数据集/统计/版本 | 已实际验证 | 会话 B 访问会话 A 的 dataset 2、statistics、versions → 404 |
| 跨会话任务查询/取消/重试 | 已实际验证 | B 访问 job 1、cancel、retry → 404；B 的任务列表为空 |
| 演示数据只读 | 已实际验证 | A 对演示数据集创建规则、执行清洗 → 403 |
| 破坏性操作二次确认 | 已实际验证 | 清洗执行缺少 `X-Confirm` → 400；版本恢复缺少 `confirm` → 400 |
| 审计脱敏 | 已实际验证 | `audit_log` 仅记录动作/资源/结果，`detail` 为空，无 SQL/Cookie/Token |
| 错误信息不泄露 | 已实际验证 | 任务失败仅返回 `INTERNAL` + "任务执行失败，请稍后重试"；400 消息不含表名/路径/堆栈 |

### 4. 统计与图表

| 项目 | 结果 | 证据 |
|---|---|---|
| 统计单次扫描 | 已实际验证 | 10 万行 7 列首次计算 2.554s（单条聚合 + 9 条分位数查询） |
| 版本化缓存命中 | 已实际验证 | 20 次热请求 P50 10.6ms / P95 29.7ms；版本切换后缓存失效并重算 |
| 大数据抽样标识 | 已实际验证（单元测试） | `AnalysisServiceStatsTest`：>10 万行使用 `LIMIT 50000` 子查询并返回 `approximate=true` |
| 图表限点与提示 | 已实际验证 | `limit=5000` → 400（超上限 2000）；按 id 分组 `limit=2000` → 2000 点 + `notice` 提示 |
| 统计优化前后对比 | SQL 层基线（非旧应用进程） | 见下节 |

#### 统计优化前后对比（隔离库，同一客户端，含 0.2s 客户端开销）

| 方式 | 查询数 | P50 | P95 | 说明 |
|---|---|---|---|---|
| 旧逐列模式（SQL 层复现） | 7 次聚合 + 3 次整列拉取 = 10 | 5.946s | 6.010s | 每列全表扫描；分位数需整列进 JVM（旧实现） |
| 新单次聚合（SQL 层复现，冷） | 1 次聚合 + 9 次分位数 = 10 | 4.160s | 4.420s | 仅 1 次全表扫描，分位数在库内 `ORDER BY + LIMIT 1 OFFSET` |
| 新应用冷请求 | 13（含元数据/缓存读写） | 2.554s（单次） | — | HTTP 实测 |
| 新应用缓存命中 | 2（元数据 + 缓存读取） | 10.6ms | 29.7ms | 20 次实测 |

内存：旧实现将 3 个数值列（约 30 万值）整体拉入 JVM 排序；新实现不在应用侧物化整列，>10 万行改用 5 万行子查询。**内存峰值为静态分析结论，未做堆采样实测。**

### 5. 构建、测试与扫描

| 项目 | 结果 | 证据 |
|---|---|---|
| 后端单元/集成测试 | 已实际验证 | `mvn test`：**99 tests, 0 failures, 0 errors**（JDK 21） |
| 前端构建 | 已实际验证 | `vite build` 成功（19.34s；仅 chunk >500kB 提示，属既有告警） |
| 敏感信息扫描 | 已实际验证 | `node scripts/scan-secrets.mjs` 通过，无命中 |
| V9 迁移四场景 | 已实际验证 | 见第三节 |
| R9 回滚与再升级 | 已实际验证 | 6 表/1 列 → 0/0 → 6/1 |

---

## 九、三轮自查

### 第一轮：数据正确性（发现并修复 3 个真实缺陷）

1. **清洗成功却被标记 FAILED**：`clean_log.backup_table` 为 NOT NULL，版本化清洗写入 `null` 触发约束异常，导致版本已激活而任务失败。实测复现（job 2）。修复：写空串表示"无备份表"，并把激活后的日志/保留策略写入包在 try/catch 中，失败只告警不回退已提交的版本切换。
2. **取消导入残留空表与永久"处理中"数据集**：取消分支只清版本未清导入表。实测复现（job 5 后 `data_orders_cancel_*` 仍存在、数据集 status=0）。修复：取消时调用 `dropImportTable`（删表 + 数据集置 `status=2`）。复测通过。
3. **失败清洗遗留规则快照**：按 `job_id` 兜底删除 BUILDING 版本时未删 `dataset_version_rule`。实测发现 3 条孤儿快照。修复：`cleanupPartial` 两条分支都调用 `deleteVersionRules`。复测孤儿数为 0。

### 第二轮：权限与安全

- 逐项验证跨会话数据集/统计/版本/任务/取消/重试均为 404；公开任务列表按会话过滤。
- 演示数据集读写均 403；公开模式仅能处理所属匿名会话数据。
- 字段标准化：非法 `targetType` 400 且无 DDL 执行；合法类型按白名单生成 DDL。
- 审计仅记录动作/资源/结果，不含原始数据、SQL、Cookie、Token；任务失败返回通用错误。
- 敏感信息扫描通过。

### 第三轮：一致性与运维

- 版本删除保护（ACTIVE/BUILDING/至少保留 2 个）实测通过；删除同步 DROP 物理表。
- 服务重启恢复（RUNNING→RECOVERABLE、CANCEL_REQUESTED→CANCELLED）实测通过。
- 统计缓存随版本切换失效并重算。
- R9 回滚与再升级在独立库演练通过；回滚脚本含备份强制提示与取证 SQL。
- 孤儿表只上报不删除；公开清理覆盖版本/任务/统计/审计/备份登记（静态检查 + 部分运行期验证）。

---

## 十、已知限制与后续建议

1. **第四阶段发布门禁未解除**：本阶段成果只能在隔离环境使用，禁止直接切换真实生产流量。
2. Excel 流式导入仅做代码级验证，建议上线前用 10 万行级 xlsx 补一次压测。
3. 私有版本定时保留与私有任务配额未在私有模式做满周期运行验证。
4. 统计"优化前"基线为 SQL 层复现，非旧应用进程实测；如需精确对比应在发布分支保留旧实现做 A/B。
5. 清洗预览使用临时克隆表，预览行数受实现限制；大表预览耗时随数据量增长。
6. 取消导入后重试会因临时文件已删除而失败（返回明确错误），产品侧如需"取消后可重试"应保留临时文件或要求重新上传。

---

## 十一、隔离验证环境说明（仅本机临时环境）

| 项 | 值 |
|---|---|
| MySQL | 独立实例 `127.0.0.1:13306`，数据目录与临时目录均在 `temp_20261026_01\phase3-it` 下 |
| 数据库 | `dc_p5_it`（主验证）、`dc_p5_up`（存量升级）、`dc_p5_r9`（回滚演练） |
| 后端 | `127.0.0.1:18080`，jar 由源码副本 `temp_20261026_01\p5-backend-run` 构建 |
| 上传目录 | `temp_20261026_01\p5-uploads`（非生产卷） |
| 验证数据 | `orders_100k.csv`、`small_100.csv`（均为临时生成） |

以上环境与数据均为隔离测试用途，与真实生产库、生产上传卷无任何连接。
