# 数据清洗与可视化系统 第二轮深度质量审计报告

- 审计日期：2026-11-25
- 审计对象：`data-clean-platform`（backend / frontend / deploy / sql）
- 审计性质：只读审计。本轮**未修改任何业务源码、未修改数据库结构、未覆盖任何历史文档**。
- 审计方式：源码静态审计 + 真实接口调用 + 真实数据库核对 + 独立测试会话/独立测试数据集。
- 服务入口：`https://localhost:8443`（Nginx 1.27.4 → 后端 `127.0.0.1:18080`）

---

## 1. 审计范围

| 层次 | 范围 |
| --- | --- |
| 后端模块 | 数据集上传/列表/详情/预览/列/统计、数据质量、数据清洗（规则/预览/执行/日志）、任务中心、数据分析、图表视图、看板、版本管理、数据导出、匿名会话与权限、全局异常处理、异步任务执行器 |
| 前端页面 | 工作台、数据集列表/详情、数据预览、数据质量、清洗规则/预览/执行、任务中心、数据分析、看板列表/详情、版本、导出相关交互 |
| 数据库 | `dataset`、`dataset_version`、`dataset_column`、`clean_rule`、`clean_log`、`data_quality_report`、`saved_chart`、`dashboard`、`dashboard_item`、`async_job`、`anon_session`、`backup_table` 等 |
| 部署 | Nginx 反向代理、限流、错误页、安全响应头；后端 `.env` 公开模式配置 |
| 安全 | 匿名会话认证、跨会话数据隔离、越权访问、SQL 注入、XSS、异常信息泄露、文件安全 |

本轮**不包含**：代码修复、数据库变更、生产配置调整、压力测试工具级并发压测。

---

## 2. 项目运行环境

| 项 | 实际值 |
| --- | --- |
| 后端 | `local-run/backend/app.jar`，JDK 17.0.21，监听 `127.0.0.1:18080`（管理端口 18081），工作目录 `temp_20261026_01/pre-verify`（读取 `.env`） |
| 反向代理 | Nginx 1.27.4，`https://localhost:8443`，自签证书，前端 dist 静态托管 |
| 数据库 | MySQL 8.4，库名 `dataclean` |
| 运行模式 | 公开（匿名）模式：`__Host-dc_anon` Cookie，TTL 1h，每分钟清理过期会话 |
| 公开配额（实测/配置） | 单会话 5 个数据集、单文件 20MB、单会话总存储 100MB、单数据集 10 万行、单会话同时 1 个活跃任务、每日任务数上限 |
| 测试账号 | 本轮新建**独立匿名会话 E**（21:36 创建），与历史会话 A/B 完全隔离 |
| 测试数据集 | 17（仅表头）、18（重复列名）、19（中文表头/特殊字符/非法日期）、20（大量空值）、21/22（删除场景）、24（1 万行）、25（10 万行）、26（20.8MB 异步导入）；17/18/20/21/22 为测试自身创建并删除，未触碰 demo 数据集 8 与历史会话数据 |
| 观测事实 | 历史会话 A/B 的 Cookie 已过期（TTL 1h），其数据对本轮会话不可见，符合设计 |

> 证据目录（本轮中间证据文件）：`C:\Users\zengyun\Documents\Trae-CN\temp_20261026_01\audit2`

---

## 3. 实际检查文件清单

### 3.1 后端（逐文件阅读）

| 文件 | 关注点 |
| --- | --- |
| `backend/.../exception/GlobalExceptionHandler.java` | 业务异常 HTTP 状态映射（409 缺失） |
| `backend/.../controller/DatasetController.java` | 上传/列表/详情/预览/列/统计/删除 |
| `backend/.../controller/QualityController.java` | 质量查询/计算 |
| `backend/.../controller/CleanController.java` | 规则 CRUD、预览、执行、日志、rollback |
| `backend/.../controller/AnalysisController.java` | 推荐、统计、图表数据 |
| `backend/.../controller/ChartViewController.java` | 图表 CRUD、status、generate（`X-Confirm-Version`） |
| `backend/.../controller/DashboardController.java` | 看板 CRUD、加项、排序 |
| `backend/.../controller/VersionController.java` | 版本列表、恢复 |
| `backend/.../controller/JobController.java` | 任务列表/详情/取消/重试 |
| `backend/.../controller/PublicSessionController.java` | 匿名会话建立 |
| `backend/.../service/DatasetService.java` | 解析入库、异步导入阈值、删除逻辑 |
| `backend/.../service/CleanService.java` | 规则校验、预览临时表、执行建任务 |
| `backend/.../service/VersionsService.java`（VersionService） | 版本激活、恢复 |
| `backend/.../service/SavedChartService.java` | 图表创建/更新/删除、stale 判定、generate |
| `backend/.../service/DashboardService.java` | 看板详情装配（N+1） |
| `backend/.../service/DataQualityService.java` | 质量指标计算、版本关联、评分 |
| `backend/.../service/AnalysisService.java` | 图表聚合 SQL、口径 |
| `backend/.../service/JobService.java` | 配额、状态机、启动恢复 |
| `backend/.../service/JobWorker.java` | 任务领取与执行 |
| `backend/.../service/DynamicTableService.java` | 动态表 DDL/DML |
| `backend/.../service/OwnershipService.java` | 归属校验、demo 只读 |
| `backend/.../service/PublicQuotaService.java` | 文件/数据集/行数/存储配额 |
| `backend/.../util/FileParserUtil.java` | CSV 解析、BOM、列名清洗、行/列上限 |
| `backend/.../util/SqlBuilderUtil.java` | 建表/插入 SQL 拼装与转义 |
| `backend/.../mapper/AsyncJobMapper.java` | 任务领取原子性 |
| `backend/.../mapper/DatasetMapper.java` | 统计/存储汇总 |
| `backend/.../config/SecurityConfig.java` | 公开/私有端点边界 |
| `backend/.../config/JobProperties.java`、`PublicModeProperties.java` | 阈值默认值 |
| `backend/.../filter/PublicOriginGuardFilter.java` | Origin 校验 |
| `backend/.../dto/SavedChartDTO.java`、`CleanRuleDTO.java`、`CleanExecuteDTO.java`、`DashboardDTO.java`、`DashboardItemDTO.java` | 入参约束 |
| `backend/src/main/resources/application.yml` | 上传/公开配额配置 |
| `backend/.../config/PublicModeConfig.java` | 配置装配 |

### 3.2 前端（逐文件阅读）

- `frontend/src/utils/request.js`
- `frontend/src/api/clean.js`
- `frontend/src/api/analysis.js`
- `frontend/src/views/Analysis.vue`
- `frontend/src/views/DashboardDetail.vue`
- 全量视图扫描：`AiAssistant/AiConfig/AiGovernance/Analysis/Clean/Dashboard/DashboardDetail/Dataset/Help/Jobs/Login/Preview/Quality/SystemStatus/Workbench.vue`

### 3.3 数据库与部署

- `information_schema` 表/索引/外键查询（实测：**全库无外键约束**）
- `SHOW CREATE TABLE data_quality_report`（唯一键 `(dataset_id, version_id)`）
- `sql/init.sql`、`migration_v3_user.sql`、`migration_v4_ai_key_cipher.sql`
- `pre-verify/nginx-1.27.4/conf/nginx.conf`（限流、错误页、安全头）

---

## 4. 核心业务调用链（按实际代码）

1. **匿名会话建立**
   `POST /api/public/session` → `PublicSessionController` → 生成会话并下发 `__Host-dc_anon`（HttpOnly/Secure）→ 后续请求经 `SessionContextFilter` 注入 `sessionId`。

2. **数据集上传（同步）**
   `POST /api/dataset/upload` → `DatasetController.upload` → `PublicQuotaService.checkUpload`（大小/数量/存储）→ `FileParserUtil.parse`（BOM/分隔符/行×列上限）→ `DatasetService.persistDataset`（写 `dataset`/`dataset_column`）→ `DynamicTableService.createTable` + 批量 `INSERT` → `checkRowsAndColumns` → 返回 `datasetId`。

3. **数据集上传（异步，≥5MB）**
   `DatasetController.upload` → `shouldImportAsync` → 临时文件落盘 + `dataset(status=0)` 占位 + `async_job(PENDING)` → `JobWorker` 领取（`markRunning` 条件更新）→ 解析入库 → 更新 dataset(status=1) 与任务状态。

4. **清洗执行**
   `POST /api/clean/execute` → `CleanController` → `CleanService.executeClean`（校验规则、`JobService.checkQuota`、创建 `CLEAN` 任务）→ `JobWorker.runClean`：读取 `dataset.currentVersionId` 对应表 → `max(version_no)+1` 建新版本记录 → `CREATE TABLE ..._vN` → 逐条规则 `INSERT ... SELECT` → 更新 `dataset_version(ACTIVE)` 与 `dataset.currentVersionId`/`tableName` → 旧版本置 `ARCHIVED`。**DDL/DML 不在同一数据库事务内**（MySQL DDL 隐式提交）。

5. **图表生成/导出**
   `POST /api/chart-view/{id}/generate` → `ChartViewController`（可带 `X-Confirm-Version` 预校验）→ `SavedChartService.generate`：读取图表配置 → `buildStatus` 判定 stale/missingFields → 通过则 `AnalysisService.generateChartData` 使用**当前** `dataset.tableName` 聚合查询。
   `POST /api/export/chart/{id}`、`/png` → `ExportController` → `ExportService` → 同样调用 `SavedChartService.generate`；异常交给 `GlobalExceptionHandler`。

6. **版本恢复**
   `POST /api/dataset/{id}/versions/{versionId}/restore` → `VersionController` → 创建 `RESTORE` 任务 → `JobWorker.runRestore` → 将目标版本置 `ACTIVE`、其余置 `ARCHIVED`、回写 `dataset.currentVersionId` 与 `tableName`（不新建版本）。

7. **质量计算**
   `POST /api/quality/{datasetId}/compute` → `DataQualityService.compute` → 按 `dataset_version_id` 做聚合统计 → 写 `data_quality_report`（唯一键 `dataset_id+version_id`）→ 查询时按“当前版本”取报告；旧版本报告保留但不再作为当前结果。

---

## 5. 模块异常测试结果

结论口径：**接口状态码 + 响应体 + 数据库落地结果**三方核对。

| 模块 | 用例 | 实际结果 | 判定 |
| --- | --- | --- | --- |
| 数据集 | 上传空文件（0B） | 400 `上传文件不能为空` | 通过 |
| 数据集 | 仅表头 CSV | 200，`rowCount=0`、`status=1`（就绪） | 疑似问题（SEC2-08） |
| 数据集 | 重复列名 CSV `a,a,b` | 200，自动去重为 `a` / `a_2` / `b`，无异常入库 | 通过（静默改名，见备注） |
| 数据集 | UTF-8 BOM 中文表头 | 200，但首列被改为 `_金额_元_` | 缺陷（SEC2-05） |
| 数据集 | 非法 CSV（引号未闭合） | 400 `CSV解析失败...` | 通过 |
| 数据集 | 不支持格式 `.txt` | 400 `不支持的文件格式` | 通过 |
| 数据集 | 大量空值 CSV | 200，空串处理为 NULL/空值参与质量统计 | 通过 |
| 数据集 | 20.8MB 文件（>5MB 阈值） | 200 `文件较大，已提交后台导入`，异步任务成功 | 通过 |
| 数据集 | 查询不存在 ID / 非数字 ID | 404 `数据集不存在` / 400 `请求参数格式不正确` | 通过 |
| 数据集 | 删除不存在 ID | 404 | 通过 |
| 数据集 | 删除 demo 数据集 | 403（demo 只读） | 通过 |
| 数据质量 | 0 行数据集计算 | 200，`score=100.0`、各列 `CONSISTENT` | 缺陷（SEC2-08） |
| 数据质量 | 非法日期 `2026-02-30` | 200，`expectedType=DATE`、`mismatchCount=0` | 缺陷（SEC2-07） |
| 数据质量 | 同一版本重复计算 | 返回缓存结果（`computedAt` 不变），不重复计算 | 通过 |
| 数据质量 | 新版本后查询 | 当前版本无报告时返回未计算，旧版本报告不冒充当前结果 | 通过 |
| 数据质量 | 不存在数据集计算 | 404 | 通过 |
| 数据清洗 | 空 `ruleIds` 预览 | 400 `没有有效的清洗规则` | 通过（预期行为） |
| 数据清洗 | 不支持的 `ruleType` | 400 `不支持的清洗规则类型` | 通过 |
| 数据清洗 | 非法 `handleType` / `targetType` | 400 明确提示 | 通过 |
| 数据清洗 | 目标列不存在 | 规则创建 **200 成功**；预览才 400 通用错误 | 缺陷（SEC2-11） |
| 数据清洗 | 合法规则预览/执行 | 200；执行生成新版本，`job=SUCCEEDED` | 通过 |
| 数据清洗 | 重复提交清洗 | 429 `任务数量已达上限`（公开模式单会话 1 个活跃任务） | 通过 |
| 数据分析 | 非法图表类型 | 400 `不支持的图表类型` | 通过 |
| 数据分析 | 不存在字段 | 400 `字段不存在` | 通过 |
| 数据分析 | 缺少 X/Y 轴的图表配置 | **图表创建 200 成功**，生成时才 400 `必须指定 X 轴和 Y 轴` | 缺陷（SEC2-10） |
| 数据分析 | Y 轴为文本列（SUM 文本） | 200 成功，返回全 0 数据 | 缺陷（SEC2-06） |
| 图表视图 | 查询不存在图表 | 404 | 通过 |
| 图表视图 | 删除后访问详情 | 404 | 通过 |
| 看板 | 添加不存在图表 | 400 `图表不存在` | 通过 |
| 看板 | 重复添加同一图表 | 400 拒绝（并有唯一约束兜底） | 通过 |
| 看板 | 删除图表后看看板 | **整块看板 404 `图表不存在`** | 缺陷（SEC2-04） |
| 看板 | 删除数据集后看看板 | **整块看板 404 `数据集不存在`** | 缺陷（SEC2-03） |
| 看板 | 非法排序项 ID | 400 | 通过 |
| 任务中心 | 任务失败状态 | `FAILED` + `errorCode/errorSummary` 正确 | 通过 |
| 任务中心 | 重复任务/配额 | 429 | 通过 |
| 任务中心 | 取消已结束任务 | 400 `任务已结束` | 通过 |
| 任务中心 | 服务重启残留 RUNNING | 启动时统一降级为 `RECOVERABLE`（源码 L238-242） | 通过（静态） |
| 导出 | 数据集 CSV | 200，`text/csv`，带 BOM，行数/内容与库一致 | 通过 |
| 导出 | 图表 CSV / PNG（最新） | 200，CSV 内容真实；PNG 12KB 级、魔数正确 | 通过 |
| 导出 | 过期图表 CSV/PNG | **200 + `application/json` + `{"code":409,...}`（55B）** | 缺陷（SEC2-01） |
| 导出 | 不存在图表 | 404 | 通过 |
| 导出 | 无权限图表（他会话） | 404 | 通过 |

---

## 6. 数据一致性测试结果

| 链路 | 检查项 | 实测证据 | 结论 |
| --- | --- | --- | --- |
| 数据集 ↔ 版本 | 清洗后生成新版本并切换 | 数据集 19 清洗 job 37 `SUCCEEDED`，`outputVersion=18`，`dataset.currentVersionId=18` | 一致 |
| 数据集 ↔ 版本 | 恢复历史版本后 `currentVersionId` | 恢复 v16：job 42 `SUCCEEDED`，`currentVersionId=16`，v16=ACTIVE、v18=ARCHIVED，无新增版本 | 一致 |
| 数据集 ↔ 版本 | 并发恢复不同版本 | 并行请求 v16 / v18：一成功一被配额拒绝；最终仅 1 个 ACTIVE，未出现脏读 | 一致（单实例） |
| 数据集 ↔ 图表 | 图表绑定版本 | `saved_chart.dataset_version_id` 创建时=当前版本；clean 后仍为旧版本 | 一致 |
| 数据集 ↔ 图表 | 更新后旧图表 stale | 清洗生成 v18 后，图表 5 判定 stale，导出/生成返回 409（业务码） | 判定正确，但**用户无法刷新**（SEC2-02） |
| 数据集 ↔ 图表 | 刷新图表后使用当前版本 | `X-Confirm-Version=18`（恰好等于当前版本）仍返回 409 | **缺陷（SEC2-02）** |
| 数据集 ↔ 质量 | 报告关联版本 | `data_quality_report` 唯一键 `(dataset_id, version_id)`；当前版本无报告不返回旧报告 | 一致 |
| 数据集 ↔ 质量 | 重复计算覆盖 | 同版本第二次计算返回缓存，未覆盖历史 | 一致 |
| 数据集 ↔ 清洗任务 | 任务与数据集/版本 | `async_job.dataset_id / input_version_id / output_version_id` 正确写入 | 一致 |
| 数据集 ↔ 清洗任务 | 清洗失败是否污染原数据 | 空配置图表任务 35 `FAILED`，未产生版本；原数据集可继续访问 | 一致（未构造增量清洗中途失败用例，见第 15 节） |
| 看板 ↔ 图表 | 引用存在性 | 详情装配逐项校验图表与数据集 | 存在性校验有，但**级联缺失** |
| 看板 ↔ 图表 | 图表删除后关联 | `dashboard_item` 残留，`GET /api/dashboard/4` → 404 | **缺陷（SEC2-04）** |
| 看板 ↔ 图表 | 数据集删除后关联 | `dashboard_item`/`saved_chart` 残留，`GET /api/dashboard/5` → 404 | **缺陷（SEC2-03）** |
| 全库 | 孤儿数据实测 | `orphan_charts=1`、`orphan_items=2`、`orphan_quality=2`（历史遗留 dataset 13/16）、`orphan_rule=0` | 缺陷证据 |
| 全库 | 外键约束 | `information_schema.KEY_COLUMN_USAGE` 无任何外键 | 设计限制（应用层保证，见风险） |

---

## 7. 并发与事务审计结果

| 场景 | 方式 | 结果 | 结论 |
| --- | --- | --- | --- |
| 同一会话并发提交同一清洗任务 | 两个 curl 同时 POST `/api/clean/execute` | 一个 200（job 可创建），一个 429 `任务数量已达上限`；未产生重复任务 | 实际执行通过；但 `checkQuota` 为“先 count 再 insert”，**静态存在 TOCTOU**，本轮未复现 |
| 并发恢复同一数据集不同版本 | 并行 POST restore v16/v18 | 一成功一 429；最终版本状态唯一 ACTIVE | 实际执行通过 |
| 任务被多个 worker 重复领取 | `AsyncJobMapper.markRunning` 条件更新（`WHERE status IN (...)`） | 原子更新，最多一个执行者 | 通过（静态） |
| 服务崩溃残留 RUNNING | `JobService` 启动恢复 | 启动时将 RUNNING 降级为 RECOVERABLE | 通过（静态） |
| 同一数据集并发清洗（私有模式） | 未执行 | 私有模式 `privateMaxActive=4`，存在同数据集并发清洗可能；版本号 `(dataset_id, version_no)` 唯一约束会拦截其一，动态表名 `_vN` 可能冲突 | **未验证（需私有账号）** |
| 清洗期间并发导出 | 未执行 | 100k 清洗约 4s，窗口极短；静态上导出读 `dataset.tableName`（切换前旧表/切换后新表），不会读半成品表 | **未验证（时间窗口无法稳定命中）** |
| 清洗中途异常的事务原子性 | 静态 | 建版本行、DDL、批量 INSERT 不在同一事务；MySQL DDL 隐式提交，理论上存在“版本行回滚但表已建/表已建但数据半写入”的窗口 | **潜在风险（未构造出实际失败用例）** |
| 并发质量计算 | 未执行 | 唯一键可防重复行；最后一个写入者覆盖 | **未验证** |
| 丢失更新（dataset.currentVersionId） | 静态 | 更新前无 `SELECT ... FOR UPDATE`/乐观锁版本号；依赖单会话 1 活跃任务串行化 | 设计限制 |

**结论**：公开模式下，单会话任务串行化 + 条件更新 + 唯一约束基本能避免重复执行与版本覆盖；但事务边界与私有模式并发仍缺少数据库级保护，本轮**不宣称并发全部通过**。

---

## 8. 前端交互审计结果

| 页面/能力 | 检查点 | 结果 |
| --- | --- | --- |
| 全局 `request.js` | 统一错误提示、401 处理、Blob 透传 | 非 2xx 统一 `ElMessage.error`；Blob 分支原样返回，由调用方判断 |
| 数据集列表/详情 | loading、失败提示、删除二次确认 | `v-loading` 覆盖，13 处 catch，4 处 confirm | 通过（静态） |
| 上传 | 重复点击 | 上传按钮有 loading 态 | 通过（静态） |
| 质量页 | 加载/错误/重算 | 5 处 catch，loading | 通过（静态） |
| 清洗页 | 预览/执行/规则 | 9 处 catch，loading；执行有确认 | 通过（静态） |
| 任务中心 | 轮询、取消、重试、错误展示 | 4 处 catch，拦截器统一提示；`loadError` 展示 | 通过（静态） |
| 数据分析-导出 CSV | **Blob 失败识别** | `Analysis.vue` L659-669 `downloadBlob` 固定 `text/csv` 且不检查 `Content-Type`；L623-634 收到 200+JSON 仍提示“CSV 已下载” | **缺陷（SEC2-01）** |
| 数据分析-刷新过期图表 | `X-Confirm-Version` | `analysis.js` L62 会带版本头；但后端始终 409，前端提示“刷新失败：数据已更新，需要刷新”，无法完成刷新 | **缺陷（SEC2-02）** |
| 看板详情-刷新/导出 PNG | Blob 失败识别 | `DashboardDetail.vue` L299-315 直接 `Blob(...'image/png')` 下载，无 JSON 识别；同样误报成功 | **缺陷（SEC2-01）** |
| 看板详情 | 图表被删/数据集被删 | 详情整页 404，仅空白错误 | **缺陷（SEC2-03/04）** |
| 导出按钮 | 防重复点击 | 导出按钮无 loading/禁用（Analysis L623、DashboardDetail L299） | 低风险（SEC2-14） |
| 页面切换 | 旧请求污染 | 组件内无请求取消（AbortController）；快速切换数据集时存在旧响应覆盖新状态的可能 | 潜在风险（未实测复现） |
| AI 内容渲染 | `v-html` | Analysis/Clean/Preview 使用 `marked.parse` 后 `v-html`，**未引入 DOMPurify**；AI 当前关闭 | 潜在 XSS（SEC2-15） |
| 控制台报错 | 未做浏览器交互回归 | 仅静态与接口层验证 | 未验证（见第 15 节） |

---

## 9. 安全审计结果

| 用例 | 请求 | 结果 |
| --- | --- | --- |
| 未登录访问受保护接口 | 无 Cookie `GET /api/dataset` | 401 `匿名会话未建立` |
| 伪造会话 Cookie | 随机 `__Host-dc_anon` | 401 |
| 会话过期 | 历史 Cookie（c.txt） | 401，无法访问历史数据 |
| 跨会话读数据集 | E 会话读数据集 16 | 404 |
| 跨会话读图表/看板/质量/任务 | chart 3 / dashboard 3 / quality 16 / job 29 | 全部 404 |
| demo 数据只读 | 删除数据集 8 | 403 |
| 管理/运维接口暴露 | Nginx `/api/admin/`、`/api/ops/`、`/actuator/` | 404（Nginx 直接拦截） |
| 健康检查暴露 | Nginx `/api/health` | 404（显式关闭，设计如此） |
| 内部认证接口暴露 | `/api/auth/me`、`/api/ai/governance/*`（公开模式） | 404 |
| SQL 注入（字段名） | 图表 `xAxis="日期\` OR 1=1 --"` | 400 `字段不存在`，拼接前有列白名单校验 |
| SQL 注入（规则） | `ruleType` 白名单、`targetColumn` 标识符校验 | 400 拒绝 |
| 路径穿越 | 上传不带原始文件落盘；下载按 ID 查库生成，文件名固定 | 未发现可利用点 |
| 恶意文件上传 | `.txt` 拒绝；仅 CSV 解析；无脚本执行 | 通过 |
| 异常堆栈泄露 | 数据库异常/系统异常统一 500 + 通用文案 | 通过（静态+接口） |
| XSS | 数据集名等文本由 Vue 转义；AI 输出走 `v-html` | 存储型风险低；AI 渲染为潜在 XSS |
| Token 泄露 | 匿名 Cookie HttpOnly + Secure + `__Host-` 前缀；响应头无敏感配置 | 通过 |

---

## 10. 性能与资源审计结果

### 10.1 实测（单实例，本机，含网络与 TLS 开销）

| 操作 | 1,000 行 | 10,000 行 | 100,000 行 |
| --- | --- | --- | --- |
| 上传（同步） | 未单测 | 2,970 ms（服务端 2,800 ms） | 25,200 ms（3.2MB CSV，低于 5MB 异步阈值，同步阻塞） |
| 数据集预览（20 行/页） | — | 96 ms | 未单测（分页 LIMIT） |
| 质量计算 | — | 407 ms | 2,982 ms |
| 图表创建 | — | 165 ms | 106 ms |
| 图表生成（聚合） | — | 197 ms | 247 ms |
| CSV 导出 | — | 618 ms | 834 ms（导出上限 10,000 行，`x-export-truncated`） |
| 20.8MB 文件异步导入受理 | — | — | 555 ms 返回 202/200 受理，后台完成 |

### 10.2 静态检查

| 项 | 结论 |
| --- | --- |
| 列表分页 | 数据集/任务/清洗记录均分页；`preview size>1000` 返回 400（有上限） |
| N+1 查询 | `DashboardService.toVO` 对每个 item 依次 `requireVisible` + `buildStatus`（各含查询），看板项多时 N+1 明显（SEC2-16） |
| 全表读取 | 质量统计走 SQL 聚合；图表走 `GROUP BY` 聚合；导出有 10,000 行截断 |
| 大文件内存 | CSV 解析一次性读入 `List<Map>`，20MB/10 万行占用较高；未做流式入库 |
| 未关闭流 | 使用 try-with-resources / Spring 管理，未发现泄漏 |
| 线程池 | 异步任务池可配置，单实例；任务串行度受配额限制 |
| 前端大数据渲染 | 表格分页 + 虚拟滚动未启用；10 万行不做全量渲染（有分页），风险可控 |
| 限流 | Nginx `api_zone` burst=60；上传单独 `upload_zone` 10r/m burst=3，超限返回 Nginx HTML 429（非 JSON） |
| 错误页 | `proxy_intercept_errors on` + `error_page 404` 将后端 404 业务文案统一替换为 `资源不存在`，排障信息丢失（设计取舍） |

---

## 11. 第一轮缺陷复核结果

### 11.1 P1：HTTP 409 被降级为 HTTP 200 —— **仍未修复，已实际复现（SEC2-01）**

- 源码：`GlobalExceptionHandler.java` L38-46，`switch` 仅映射 400/401/403/404/429/503，`default -> HttpStatus.OK`。
- 实测（会话 E，数据集 19 清洗产生 v18 后）：
  - `POST /api/export/chart/5` → `HTTP/1.1 200`、`Content-Type: application/json;charset=UTF-8`、body `{"code":409,"message":"数据已更新，需要刷新"}`，文件 55 字节。
  - `POST /api/export/chart/5/png` → 同上，55 字节，无 PNG 魔数。
  - 同时 `async_job` 38/39/41 状态 `FAILED`、`error_summary=数据已更新，需要刷新`。
- 前端：`Analysis.vue` L659-669 与 `DashboardDetail.vue` L299-315 直接按 CSV/PNG 下载并提示成功。
- 结论：**缺陷成立，且影响面比第一轮描述更大**（会产生失败任务 + 伪文件 + 错误成功提示）。

### 11.2 P3：`rollbackClean` 死代码 —— **仍存在（SEC2-09）**

- `frontend/src/api/clean.js` L50-54 仍导出 `rollbackClean`；全前端检索无调用方。
- 实测 `POST /api/clean/rollback/1`：因记录不可见返回 404；后端该能力已明确停用（提示改用版本恢复）。
- 结论：可安全清理，本轮仅建议，不删除。

### 11.3 预期行为：空规则预览 400 —— **确认正确**

- `POST /api/clean/preview {"datasetId":19,"ruleIds":[]}` → 400 `没有有效的清洗规则`。
- 后端在构造预览前显式校验；前端不会提交空规则（按钮受选择状态约束）。
- 结论：非缺陷。

---

## 12. 新发现的问题（缺陷卡）

> 编号规则：`SEC2-xx`。P0=阻断上线；P1=核心功能不可用/数据可信度受损；P2=功能/数据一致性缺陷；P3=体验/维护性/潜在风险。

### SEC2-01 HTTP 409 业务异常降级为 200，前端下载伪 CSV/PNG

| 项 | 内容 |
| --- | --- |
| 所属模块 | 全局异常处理 / 数据导出 / 数据分析 / 看板 |
| 严重程度 | **P1** |
| 问题描述 | `BusinessException(409)` 走 `default -> HttpStatus.OK`，响应 200 + JSON；前端 Blob 下载不校验 `Content-Type`，把 JSON 存成 `.csv/.png` 并提示成功 |
| 源码位置 | `GlobalExceptionHandler.java` L38-46；`Analysis.vue` L623-634、L659-669；`DashboardDetail.vue` L299-315；`ExportService`/`SavedChartService.generate` L169-171 |
| 复现步骤 | 1) 创建图表并保存；2) 对数据集执行清洗生成新版本；3) `POST /api/export/chart/{id}` 与 `/png`（带 `X-Confirm: yes`）；4) 观察状态码/Content-Type/文件 |
| 预期结果 | HTTP 409（或 412），`application/json`，前端识别失败并提示“请刷新图表” |
| 实际结果 | HTTP 200，`application/json`，body `{"code":409,...}`，55B 文件被当作 CSV/PNG 下载；同时生成 FAILED 导出任务 |
| 影响范围 | 所有过期图表的 CSV/PNG 导出；用户得到无法打开的文件且误以为成功 |
| 修复建议 | 异常映射补齐 `case 409 -> HttpStatus.CONFLICT`（并补 422/500 等）；前端 Blob 下载前判断 `content-type` 是否 `application/json`，是则解析错误并提示；导出接口优先返回 409 |
| 是否实际复现 | 是（含响应头、文件大小、任务失败记录） |

### SEC2-02 过期图表“刷新”入口 100% 失败，图表永久停留在旧版本

| 项 | 内容 |
| --- | --- |
| 所属模块 | 图表视图 / 看板详情 |
| 严重程度 | **P1** |
| 问题描述 | `ChartViewController.generate` 接收 `X-Confirm-Version` 仅用于前置校验，随后调用 `savedChartService.generate(id)`；该方法在 `stale=true` 时**无条件**抛 409，且没有任何“确认后重绑当前版本”的分支 |
| 源码位置 | `ChartViewController.java` L71-84；`SavedChartService.java` L146-174（L169-171 抛错）、L127-128（仅 update 时重绑） |
| 复现步骤 | 1) 图表 5 绑定 v16；2) 清洗数据集 19 生成 v18；3) `POST /api/chart-view/5/generate` 带 `X-Confirm-Version: 18`（等于当前版本）；4) 前端点击“生成/刷新” |
| 预期结果 | 确认版本一致后允许生成，并将 `dataset_version_id` 更新为 18 |
| 实际结果 | 409 `数据已更新，需要刷新`；前端看板“刷新”同样失败；导出被永久阻断 |
| 影响范围 | 清洗/恢复后的所有已保存图表与看板项；唯一绕行是到分析页“载入→保存”重新绑定 |
| 修复建议 | 生成接口在 `confirmVersion == currentVersionId` 时执行重绑（`setDatasetVersionId` + 审计）后再生成；或在 service 增加 `refresh` 参数；前端对 409 提供“确认使用新版本”动作 |
| 是否实际复现 | 是 |

### SEC2-03 删除数据集不清理关联对象，导致看板整页 404 与孤儿数据累积

| 项 | 内容 |
| --- | --- |
| 所属模块 | 数据集 / 看板 / 质量 / 清洗 |
| 严重程度 | **P2** |
| 问题描述 | `deleteDataset` 只清理 dataset、dataset_version、async_job、统计缓存、dataset_column；未处理 `saved_chart`、`dashboard_item`、`clean_rule`、`clean_log`、`data_quality_report`、`backup_table`。全库无外键，孤儿永久残留 |
| 源码位置 | `DatasetService.java` L377-430 |
| 复现步骤 | 1) 新建数据集 22；2) 建图表 6 并加入看板 5；3) `DELETE /api/dataset/22`（带 `X-Confirm: yes`）；4) `GET /api/dashboard/5`；5) 查库孤儿 |
| 预期结果 | 关联图表/看板项/规则/质量报告被级联删除或标记失效，看板可加载并提示“图表已删除” |
| 实际结果 | 看板 5 → 404 `数据集不存在`；看板列表仍显示 `itemCount=1`；`orphan_charts=1`、`orphan_items=1`、历史 `orphan_quality=2` |
| 影响范围 | 删除数据集即“炸掉”引用它的看板；长期运行后孤儿表/行持续增长 |
| 修复建议 | 删除事务内级联清理（或软删除 + 看板装配跳过失效项）；增加定时孤儿巡检；数据库层补 FK 或至少清理脚本 |
| 是否实际复现 | 是 |

### SEC2-04 删除图表不清理看板项，看板随后不可访问

| 项 | 内容 |
| --- | --- |
| 所属模块 | 图表视图 / 看板 |
| 严重程度 | **P2** |
| 问题描述 | `SavedChartService.delete` 仅删 `saved_chart` + 审计，不删 `dashboard_item`；`DashboardService.toVO` 遇失效图表直接抛 404，导致整个看板详情不可用 |
| 源码位置 | `SavedChartService.java` L138-144；`DashboardService.java` L223-260（L234-235 逐项 `requireVisible`） |
| 复现步骤 | 1) 看板 4 添加图表 4、5；2) `DELETE /api/chart-view/4`；3) `GET /api/dashboard/4` |
| 预期结果 | 删除图表时同步移除看板项，或看板跳过失效项并提示 |
| 实际结果 | `GET /api/dashboard/4` → 404 `图表不存在`；看板 4 永久不可用 |
| 影响范围 | 所有含已删图表的看板 |
| 修复建议 | 删除图表时同事务删除 `dashboard_item`（或软删）；装配时对失效项降级展示而不是整体 404 |
| 是否实际复现 | 是 |

### SEC2-05 UTF-8 BOM 未剥离，首列表头被改名为 `_xxx`

| 项 | 内容 |
| --- | --- |
| 所属模块 | 数据集上传 / 数据分析 / 清洗 |
| 严重程度 | **P2** |
| 问题描述 | 以 `InputStreamReader(UTF-8)` 直接交给 CSV 解析，未去 BOM；BOM 字符进入首列表头，`sanitizeColumnName` 将其转为 `_`，列名/别名变成 `_金额_元_` |
| 源码位置 | `FileParserUtil.java` L113（读取）、L135/L220（清洗列名）、L412-428（sanitize）；无 BOM 处理分支 |
| 复现步骤 | 1) 用 Excel 导出 UTF-8 with BOM 的 CSV（表头 `金额(元),日期,备注`）；2) 上传；3) 查看列与预览 |
| 预期结果 | 列名为 `金额_元_`（`(`/`)` 被替换），与 BOM 无关 |
| 实际结果 | 列名为 `_金额_元_`；无 BOM 的同一文件为 `region/product/...` 正常 |
| 影响范围 | Excel 导出的 CSV（极常见）；字段名不符预期，规则/图表选择与文档说明易出错 |
| 修复建议 | 读取首行前去除 `\uFEFF`（`parse` 入口或 `sanitizeColumnName` 先 `strip BOM`）；补充 BOM 单测 |
| 是否实际复现 | 是（DB `dataset_column` 对比：19 为 `_金额_元_`，24 为 `region`） |

### SEC2-06 Y 轴为文本列时静默返回全 0 图表

| 项 | 内容 |
| --- | --- |
| 所属模块 | 数据分析 / 图表视图 |
| 严重程度 | **P2** |
| 问题描述 | 创建图表只校验字段存在，不校验聚合指标列类型；文本列参与 `SUM` 得到 0，接口 200 返回“正常”图表 |
| 源码位置 | `SavedChartService.normalizeConfig`（仅列存在性）；`AnalysisService.generateChartData` 聚合 SQL |
| 复现步骤 | 1) 在数据集 19 创建 `bar` 图表，`xAxis=日期`、`yAxis=备注`（VARCHAR）；2) `POST /api/chart-view/7/generate` |
| 预期结果 | 400 `指标字段必须为数值类型` |
| 实际结果 | 200，柱状图全为 0（日期 2 项：0.0 / 0.0） |
| 影响范围 | 用户可能把“全 0”当成真实业务结论 |
| 修复建议 | 创建/更新时校验 yAxis 声明类型属于数值；生成时对非数值列拒绝并提示 |
| 是否实际复现 | 是 |

### SEC2-07 日期合法性问题未被识别（2026-02-30 判为一致）

| 项 | 内容 |
| --- | --- |
| 所属模块 | 数据质量 |
| 严重程度 | **P2** |
| 问题描述 | 日期一致性仅用正则匹配格式 `yyyy-MM-dd`，不校验日历合法性；`2026-02-30` 被计为合法 |
| 源码位置 | `DataQualityService.java` L52（`DATE_REGEX`）、L261、L372 |
| 复现步骤 | 1) 上传含 `2026-02-30` 的数据集 19；2) `POST /api/quality/19/compute` |
| 预期结果 | `日期` 列 `mismatchCount=1`，质量分下降或标注异常 |
| 实际结果 | `expectedType=DATE`、`matchedCount=2`、`mismatchCount=0`、`CONSISTENT` |
| 影响范围 | 质量评分虚高，非法日期流入下游 |
| 修复建议 | 用 `STR_TO_DATE` 或 Java 解析做真实日期校验（含闰年/月末） |
| 是否实际复现 | 是 |

### SEC2-08 空数据集被接受且质量评分为 100

| 项 | 内容 |
| --- | --- |
| 所属模块 | 数据集 / 数据质量 |
| 严重程度 | **P2** |
| 问题描述 | 仅表头 CSV 上传成功（`rowCount=0`、`status=1`）；质量计算评分为 100（分母 0 时不计扣分） |
| 源码位置 | `DatasetService.persistDataset`（无“必须至少 1 行”校验）；`DataQualityService.java` L162-170、L184 |
| 复现步骤 | 1) 上传内容为 `a,b,c` 的 CSV；2) `POST /api/quality/17/compute` |
| 预期结果 | 上传 400 `文件没有数据行`；质量计算返回“无数据可评估”而非 100 分 |
| 实际结果 | 上传 200 就绪；质量 200，`score=100.0`，各列 `CONSISTENT` |
| 影响范围 | 空数据集可被用于图表/看板，产生空结果与误导性质量结论 |
| 修复建议 | 上传阶段拒绝 0 行；质量计算对 0 行返回 `NOT_APPLICABLE` |
| 是否实际复现 | 是 |

### SEC2-09 `rollbackClean` 死代码

| 项 | 内容 |
| --- | --- |
| 所属模块 | 前端 API 封装 / 清洗 |
| 严重程度 | **P3** |
| 问题描述 | 后端已停用旧备份回滚，前端仍导出 `rollbackClean`，无调用方 |
| 源码位置 | `frontend/src/api/clean.js` L50-54；后端 `POST /api/clean/rollback/{logId}` |
| 复现步骤 | 全局检索 `rollbackClean`；调用该接口 |
| 预期结果 | 无该封装 |
| 实际结果 | 封装仍存在；接口返回 404/400 停用提示 |
| 影响范围 | 维护成本、误用风险 |
| 修复建议 | 删除导出与相关注释；保留后端兼容一段时间 |
| 是否实际复现 | 是（静态 + 接口） |

### SEC2-10 缺少必填轴的图表可在创建阶段保存成功

| 项 | 内容 |
| --- | --- |
| 所属模块 | 图表视图 |
| 严重程度 | **P3** |
| 问题描述 | `chartConfig={}` 的 `bar` 图创建 200 成功（图表 id=4），到生成时才 400 |
| 源码位置 | `SavedChartService.normalizeConfig`（不校验 bar/line 必须有 xAxis/yAxis） |
| 复现步骤 | `POST /api/chart-view` 提交空 `chartConfig` |
| 预期结果 | 创建即 400 |
| 实际结果 | 创建成功；`generate` 返回 400，并产生 FAILED 任务 35 |
| 影响范围 | 无效图表进入列表/看板 |
| 修复建议 | 创建/更新时按图表类型强制校验必填轴 |
| 是否实际复现 | 是 |

### SEC2-11 清洗规则允许引用不存在的列，错误延迟到预览且提示泛化

| 项 | 内容 |
| --- | --- |
| 所属模块 | 数据清洗 |
| 严重程度 | **P3** |
| 问题描述 | `createRule` 只校验标识符格式，不校验列是否属于该数据集；保存成功，预览返回通用失败信息 |
| 源码位置 | `CleanService.createRule` L81 起；预览异常包装处 |
| 复现步骤 | 创建 `missing_value_handle`，`targetColumn=不存在`；再预览 |
| 预期结果 | 创建即 400 `字段不存在` |
| 实际结果 | 创建 200（规则 id=4）；预览 400 通用错误 |
| 影响范围 | 无效规则堆积，排障成本高 |
| 修复建议 | 创建时校验 `dataset_column` 存在且类型匹配；预览错误透出具体列名 |
| 是否实际复现 | 是 |

### SEC2-12 异步导入仅按字节数分流，稠密小文件同步阻塞约 25 秒

| 项 | 内容 |
| --- | --- |
| 所属模块 | 数据集上传 / 性能 |
| 严重程度 | **P3** |
| 问题描述 | `asyncImportBytes` 默认 5MB；10 万行、3.2MB 的 CSV 走同步路径，请求阻塞 25.2s |
| 源码位置 | `DatasetService.shouldImportAsync` L169-172；`JobProperties.asyncImportBytes` L23 |
| 复现步骤 | 上传 10 万行 CSV（3.2MB），记录耗时 |
| 预期结果 | 按行数/解析成本分流，或统一异步受理 |
| 实际结果 | 同步 25.2s；20.8MB 文件反而 555ms 受理（异步） |
| 影响范围 | 弱网/网关超时（Nginx `proxy_read_timeout 120s` 未触发，但体验差） |
| 修复建议 | 分流条件加入行数阈值或统一异步；上传接口快速受理 |
| 是否实际复现 | 是 |

### SEC2-13 Nginx 上传限流 429 返回 HTML 页面

| 项 | 内容 |
| --- | --- |
| 所属模块 | 部署 / 上传 |
| 严重程度 | **P3** |
| 问题描述 | `upload_zone` 10r/m burst=3，超限返回 Nginx 默认 HTML 429，前端统一解析 JSON 失败，只能给出泛化错误 |
| 源码位置 | `nginx.conf` 上传 location（`limit_req zone=upload_zone burst=3`），无 `error_page 429` JSON |
| 复现步骤 | 连续快速上传 4 个文件 |
| 预期结果 | 429 + JSON `{"code":429,...}` |
| 实际结果 | 429 Nginx HTML |
| 影响范围 | 批量上传时提示不友好 |
| 修复建议 | 为 429 增加 JSON `error_page`；或前端识别 HTML 429 |
| 是否实际复现 | 是 |

### SEC2-14 导出按钮缺少防重复点击

| 项 | 内容 |
| --- | --- |
| 所属模块 | 前端 / 导出 |
| 严重程度 | **P3** |
| 问题描述 | CSV/PNG 导出按钮无 loading/禁用，连续点击触发多次请求与多次下载 |
| 源码位置 | `Analysis.vue` L623；`DashboardDetail.vue` L299 |
| 复现步骤 | 快速连续点击导出 |
| 预期结果 | 请求期间禁用按钮 |
| 实际结果 | 可重复触发 |
| 影响范围 | 重复导出任务、重复下载 |
| 修复建议 | 增加 `exporting` 状态并禁用按钮 |
| 是否实际复现 | 静态确认（未做浏览器点击回归） |

### SEC2-15 AI 内容经 `marked` 后直接 `v-html`，缺少 HTML 净化

| 项 | 内容 |
| --- | --- |
| 所属模块 | 前端 / 安全 |
| 严重程度 | **P3（潜在）** |
| 问题描述 | `marked.parse` 输出直接 `v-html`，未引入 DOMPurify；若 AI 开启且返回恶意 HTML，可形成 XSS |
| 源码位置 | `Analysis.vue`、`Clean.vue`、`Preview.vue` 的 `v-html` 渲染；全项目检索无 `DOMPurify` |
| 复现步骤 | 需开启 AI 并构造含 `<img onerror>` 的模型输出（当前 AI 关闭，未执行） |
| 预期结果 | 渲染前净化 |
| 实际结果 | 未净化 |
| 影响范围 | AI 功能启用后的会话内 XSS |
| 修复建议 | 引入 `DOMPurify.sanitize(marked.parse(x))`；同时收紧 CSP（当前 `script-src 'self'` 已降低危害） |
| 是否实际复现 | 否（条件不满足，仅静态风险） |

### SEC2-16 看板详情逐项查询（N+1）

| 项 | 内容 |
| --- | --- |
| 所属模块 | 看板 / 性能 |
| 严重程度 | **P3** |
| 问题描述 | 装配每个 item 时 `requireVisible` + `buildStatus` 各自查询图表/数据集/列，项数多时查询数线性增长 |
| 源码位置 | `DashboardService.toVO` L223-260（L234-235 循环） |
| 复现步骤 | 构造含 N 个图表的看板并请求详情（本轮看板仅 2 项，未做大 N 压测） |
| 预期结果 | 批量查询（IN）后内存装配 |
| 实际结果 | 每项多次查询 |
| 影响范围 | 大看板首屏变慢 |
| 修复建议 | 批量加载 chart/dataset/version 并缓存 status |
| 是否实际复现 | 否（静态确认，未做 N 大值实测） |

---

## 13. 缺陷优先级分类

| 编号 | 模块 | 级别 | 标题 | 实际复现 |
| --- | --- | --- | --- | --- |
| SEC2-01 | 异常处理/导出/前端 | P1 | 409 降级 200，伪 CSV/PNG | 是 |
| SEC2-02 | 图表/看板 | P1 | 过期图表刷新必然失败 | 是 |
| SEC2-03 | 数据集/看板 | P2 | 删数据集遗留孤儿并炸看板 | 是 |
| SEC2-04 | 图表/看板 | P2 | 删图表遗留看板项并炸看板 | 是 |
| SEC2-05 | 上传/分析 | P2 | BOM 导致首列改名 | 是 |
| SEC2-06 | 分析/图表 | P2 | 文本指标静默全 0 | 是 |
| SEC2-07 | 质量 | P2 | 非法日期判为一致 | 是 |
| SEC2-08 | 数据集/质量 | P2 | 空数据集就绪且 100 分 | 是 |
| SEC2-09 | 前端 API | P3 | rollbackClean 死代码 | 是 |
| SEC2-10 | 图表 | P3 | 缺轴图表可保存 | 是 |
| SEC2-11 | 清洗 | P3 | 不存在列规则可保存 | 是 |
| SEC2-12 | 上传/性能 | P3 | 稠密小文件同步 25s | 是 |
| SEC2-13 | 部署 | P3 | 429 返回 HTML | 是 |
| SEC2-14 | 前端导出 | P3 | 无防重复点击 | 静态 |
| SEC2-15 | 前端安全 | P3（潜在） | v-html 未净化 | 否 |
| SEC2-16 | 看板/性能 | P3 | 详情 N+1 | 静态 |

P0：**无**。

---

## 14. 修复建议（按优先级）

1. **SEC2-01（先做，改动小、收益大）**
   - `GlobalExceptionHandler`：补 `case 409 -> HttpStatus.CONFLICT`、`case 500 -> INTERNAL_SERVER_ERROR`，并统一异常码枚举。
   - 前端：`downloadBlob`/`exportPng` 读取 `content-type`，命中 `application/json` 时 `text()` 解析并 `ElMessage.error`，禁止落盘。
2. **SEC2-02**
   - `SavedChartService.generate` 增加 `confirmVersion` 参数：`stale && equals(current)` 时重绑版本、写审计、再生成；控制器把已校验的头传下去。
   - 前端 409 时提供“使用最新版本并刷新”确认动作。
3. **SEC2-03 / SEC2-04（数据一致性）**
   - `deleteDataset`、`SavedChartService.delete` 在事务内级联删除 `dashboard_item`；`deleteDataset` 同步清理 `saved_chart`、`clean_rule`、`clean_log`、`data_quality_report`、`backup_table` 与动态表。
   - 装配侧对失效引用降级为占位项并提示，而不是整页 404。
   - 增加孤儿巡检任务（每日）。
4. **SEC2-05**：解析前剥离 `\uFEFF`；补 BOM 单测。
5. **SEC2-06 / SEC2-07 / SEC2-08（数据可信度）**
   - 指标列类型白名单；日期真实解析校验；0 行数据集拒绝入库、质量返回 N/A。
6. **SEC2-10 / SEC2-11**：把校验前移到创建阶段（必填轴、列存在与类型）。
7. **SEC2-09**：删除 `rollbackClean` 导出（本轮仅建议）。
8. **SEC2-12 ~ SEC2-16**：异步分流按行数、429 JSON 化、导出按钮 loading、DOMPurify、看板批量装配。

> 以上修复均需单独立项评审；本轮未改动任何源码。

---

## 15. 未验证项目及原因

| 项目 | 原因 |
| --- | --- |
| 私有账号模式下的同数据集并发清洗/并发质量计算 | 当前部署为公开匿名模式，无私有测试账号；不擅自创建/改配置 |
| 清洗中途异常的事务回滚（半成品表/半写入数据） | 未构造出稳定触发点；需故障注入或改代码，本轮禁止 |
| 清洗期间并发导出 | 10 万行清洗约 4 秒，时间窗口无法稳定命中；未做延迟注入 |
| 取消“运行中”任务 | 任务执行过快，取消请求到达时已结束；仅验证了“已结束任务取消返回 400” |
| xlsx 上传与解析 | 仅测试 CSV；未验证 xlsx 分支 |
| 超过 100MB / 超过 10 万行的拒绝路径 | 受公开配额与上传限制影响，未构造超大样本；仅做静态确认 |
| 大看板（N≥20）渲染与详情耗时 | 未构造大规模看板 |
| 浏览器真实交互回归（点击、控制台、页面切换竞态） | 本轮以 API + 源码审计为主；`https` 自签证书环境未做浏览器自动化回归 |
| XSS 实际利用 | AI 功能关闭，无法构造恶意模型输出 |
| 1,000 行档位与 100,000 行以上规模 | 1,000 行未单独测试；10 万行为公开模式上限 |
| 数据库连接池/线程池极限 | 未做容量压测 |

---

## 16. 最终系统质量评估

- **功能完整性**：核心链路（上传→质量→清洗→版本→图表→看板→导出）均真实可用，数据口径与版本切换基本正确；质量/图表的“版本绑定”设计是亮点。
- **数据一致性**：写路径（版本生成、恢复）一致；**删除路径是明显短板**，孤儿数据已在数据库中被实测确认，并会直接导致看板不可用。
- **异常与错误呈现**：整体参数校验与错误文案质量较好，但 409 映射缺失破坏了“错误必须可识别”的底线，且前端 Blob 下载放大了危害。
- **安全**：匿名会话隔离、越权防护、SQL 注入防护、管理接口隐藏均实测有效；主要剩余风险是 AI 渲染未净化的潜在 XSS。
- **性能**：10 万行以内响应可接受；上传同步阻塞与看板 N+1 是主要优化点。
- **可上线判断**：
  - **不建议在当前状态下直接上线**。SEC2-01、SEC2-02 会让用户在“导出/刷新”这一高频路径上得到错误文件或永久失败；SEC2-03/04 会在删除操作后破坏看板。
  - 若必须灰度：至少先修复 SEC2-01 + SEC2-02（改动局部、风险低），并对删除数据集/图表做前端二次提示“将同时移除看板引用”。
- **结论可信度声明**：本报告所有“已复现”项均附实际请求/响应/数据库证据；仅静态确认或条件不具备的项已明确标注，未计入通过率。

---

### 附：本轮关键实测证据摘要

```
# 过期图表导出（SEC2-01）
HTTP/1.1 200
Content-Type: application/json;charset=UTF-8
{"code":409,"message":"数据已更新，需要刷新"}   # 55 bytes，被当作 .csv/.png 下载

# 刷新已确认版本仍失败（SEC2-02）
POST /api/chart-view/5/generate  X-Confirm-Version: 18   (dataset.currentVersionId=18)
HTTP 409 {"code":409,"message":"数据已更新，需要刷新"}

# 删除数据集后看板（SEC2-03）
GET /api/dashboard/5 -> 404 {"code":404,"message":"数据集不存在"}
saved_chart.id=6 残留；dashboard_item.id=6 残留

# 删除图表后看板（SEC2-04）
GET /api/dashboard/4 -> 404 {"code":404,"message":"图表不存在"}

# BOM 列名（SEC2-05）
dataset 19: _金额_元_ | 日期 | 备注
dataset 24: region | product | amount | qty | date | remark

# 非法日期（SEC2-07）
2026-02-30 -> expectedType=DATE, matchedCount=2, mismatchCount=0

# 空数据集质量（SEC2-08）
rowCount=0, status=1, score=100.0, 各列 CONSISTENT

# 文本指标（SEC2-06）
bar(日期 x 备注) -> 200，全 0 序列

# 性能（10 万行）
上传 25,200ms；质量 2,982ms；图表生成 247ms；导出 834ms（截断 10,000 行）
```

- 报告生成时间：2026-11-25
- 报告文件：`docs/SECOND_AUDIT_REPORT_2026-11-25.md`（新建，未覆盖任何历史文档）
