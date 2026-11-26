# 第六阶段交付说明：可视化体验升级与 AI 调用治理

> 交付日期：2026-11-21
> 前置状态：第一至第五阶段已交付；第四阶段生产发布门禁**仍未解除**，本阶段未切换任何真实生产流量。
> 全部验证只在**隔离 MySQL（127.0.0.1:13307，MySQL 8.4）**、**临时上传目录**与**可写构建副本**中完成；
> 未连接、未修改真实生产库，未执行任何真实清洗、未删除任何真实数据。

---

## 一、交付范围

| 章节 | 要求 | 落地情况 |
|---|---|---|
| 一、图表能力 | 趋势/分类对比/占比/分布/相关性/热力六类推荐；自由配置；图表解释；ECharts 按需引入；实例销毁与 resize；单次 ≤ 2000 点 | 已实现 |
| 二、保存图表与看板 | 保存完整配置与数据版本；公开模式仅本会话且随 TTL 清理；看板 12 列栅格、增删/排序/重命名；版本变化提示刷新；字段失效可恢复 | 已实现 |
| 三、导出 | 安全 PNG；受限 CSV；公式注入防护；公开模式行数/次数/会话/TTL 限制；归属校验 | 已实现 |
| 四、AI 治理 | 供应商/模型目录/场景路由/主备/配额/熔断/审计；5 个场景；每场景可配参数；治理页仅私有 | 已实现 |
| 五、AI 安全 | 仅后端代理；Key 只写不读、掩码返回；修改二次确认并审计；PII 脱敏与截断；输出不自动执行 | 已实现 |
| 六、配额与熔断 | 私有/公开分场景配额；超限 429 且不调用供应商；超时/429/连续失败/预算触发熔断并切备用或降级；冷却与受控探测；审计脱敏 | 已实现 |
| 七、迁移与验证 | V10 纯增量；空库/存量/重复/失败重试；跨会话 404；测试/构建/扫描/降级 | 见第八节 |
| 八、交付 | 文件清单、迁移、接口、看板、AI 配置与密钥、部署/回滚/恢复、验收报告、三轮自查 | 本文档 |

---

## 二、修改文件清单

### 后端（`backend/src/main/java/com/dataclean/`）

**新增**

| 文件 | 作用 |
|---|---|
| `entity/SavedChart.java` | 保存图表（类型、配置 JSON、数据版本、归属、公开会话） |
| `entity/Dashboard.java`、`entity/DashboardItem.java` | 看板与卡片（12 列栅格坐标、尺寸、排序） |
| `entity/AiProvider.java` | 供应商（加密 Key、掩码、状态），Key 字段 `@JsonIgnore`/`@ToString.Exclude` |
| `entity/AiModel.java` | 模型目录（温度、最大输出、超时、重试、并发、单价） |
| `entity/AiSceneRoute.java` | 场景路由（主/备模型与参数覆盖） |
| `entity/AiCallLog.java` | 调用审计（仅元数据，结果常量 SUCCESS/FAILED/DEGRADED/REJECTED） |
| `entity/AiQuota.java`、`entity/AiUsage.java` | 配额与用量（private / public_session / public_ip / public_global） |
| `entity/AiCircuitState.java` | 熔断状态（CLOSED/OPEN/HALF_OPEN、冷却截止、探测名额） |
| `mapper/SavedChartMapper.java`、`mapper/DashboardMapper.java`、`mapper/DashboardItemMapper.java` | 保存图表/看板持久化 |
| `mapper/AiProviderMapper.java`、`mapper/AiModelMapper.java`、`mapper/AiSceneRouteMapper.java` | 治理目录持久化 |
| `mapper/AiCallLogMapper.java`、`mapper/AiQuotaMapper.java`、`mapper/AiUsageMapper.java`、`mapper/AiCircuitStateMapper.java` | 审计/配额/用量/熔断持久化 |
| `dto/ChartRequestDTO.java`、`dto/ChartDataVO.java`、`dto/ChartRecommendationVO.java` | 图表请求/数据/推荐 |
| `dto/SavedChartDTO.java`、`dto/SavedChartVO.java`、`dto/ChartViewStatusVO.java` | 保存图表传输对象与状态（stale/missingFields） |
| `dto/DashboardDTO.java`、`dto/DashboardVO.java`、`dto/DashboardItemDTO.java`、`dto/DashboardItemVO.java` | 看板传输对象 |
| `dto/AiProviderVO.java`、`dto/AiProviderDTO.java`、`dto/AiCallPageVO.java`、`dto/AiCircuitVO.java`、`dto/AiUsageSummaryVO.java` | AI 治理管理端 VO/DTO |
| `enums/AiScene.java` | 场景枚举（DATASET_SUMMARY/FIELD_RELATION/OUTLIER_ANALYSIS/CHART_INSIGHT/CLEAN_SUGGESTION） |
| `service/ChartRecommendService.java` | 六类图表推荐（≤6 条，含理由与置信度） |
| `service/SavedChartService.java` | 保存图表 CRUD/状态/生成，跨会话隔离与版本/字段失效 409 |
| `service/DashboardService.java` | 看板 CRUD、卡片增删改、重排，12 列栅格校验 |
| `service/AiSceneRouter.java` | 场景路由解析与 legacy 回退，主备 Target 与参数覆盖 |
| `service/AiCircuitBreakerService.java` | 熔断（阈值 3、冷却 60s、受控探测、重启恢复） |
| `service/AiQuotaService.java` | 配额检查/计数/用量汇总 |
| `service/AiCallAuditService.java` | 调用审计（仅元数据，主体哈希） |
| `service/AiPromptContextBuilder.java` | 上下文最小化与 PII 脱敏（私有 ≤3 行样本，公开仅统计） |
| `service/AiGovernanceService.java` | 统一调用入口：路由、配额、并发、重试、熔断、降级、审计 |
| `service/AiGovernanceAdminService.java` | 管理端：供应商/模型/场景/审计查询/用量/熔断（公开 404） |
| `util/PrincipalHasher.java` | 主体 HMAC-SHA256 摘要（`app.ai.audit-pepper`） |
| `controller/ChartViewController.java` | `/api/chart-view`（含 `X-Confirm`、`X-Confirm-Version`） |
| `controller/DashboardController.java` | `/api/dashboard`（含 `X-Confirm`） |
| `controller/AiGovernanceController.java` | `/api/ai/governance`（公开模式 denyAll） |

**修改**

| 文件 | 变更 |
|---|---|
| `service/AnalysisService.java` | 扩展校验（模式/时间粒度/Top N/分桶/排序）、分布模式、数据点上限与元信息解释 |
| `controller/AnalysisController.java` | 新增 `GET /api/analysis/recommend` |
| `service/ExportService.java` | 新增 `exportChartCsv`（CSV 注入防护、行数上限、配额记账） |
| `controller/ExportController.java` | 新增 `POST /api/export/chart/{id}`（需确认头） |
| `service/PublicQuotaService.java` | 新增导出类型重载 `recordExport(datasetId, exportType, rowCount)` |
| `service/AiService.java` | 治理开启时统一走 `AiGovernanceService`，SSE 改为一次性下发 |
| `service/PublicAiService.java` | 公开调用接入治理、最小化上下文、429 区分 |
| `dto/AiGenerateDTO.java` | 新增 `scene` |
| `service/PublicCleanupService.java` | 会话 TTL 清理覆盖保存图表/看板/看板卡片 |
| `config/SecurityConfig.java` | 公开模式 denyAll `/api/ai/governance` 及其子路径 |
| `config/AiKeyMigrationRunner.java` | 兼容治理新增配置（不触碰既有密钥） |

### 前端（`frontend/src/`）

| 文件 | 变更 |
|---|---|
| `utils/echarts.js` | 按需注册图表与组件，`createChart` 自动 resize 与 dispose |
| `api/analysis.js` | 推荐、图表生成、保存图表 CRUD/状态/生成、CSV 导出 |
| `api/dashboard.js`、`api/governance.js` | 看板与治理接口封装（写操作带确认头） |
| `views/Analysis.vue` | 推荐卡片、七种图表、主题/粒度/Top N/分桶/排序、数据解释、空态、PNG/CSV、AI 洞察、已保存图表管理 |
| `views/Dashboard.vue`、`views/DashboardDetail.vue` | 看板列表与 12 列栅格编辑、刷新提示、增删卡片、重排 |
| `views/AiGovernance.vue` | 供应商/模型/场景/审计/用量/熔断六个页签 |
| `router/index.js`、`components/layout/Layout.vue` | 新路由、公开模式隐藏治理入口 |

### 数据库

| 文件 | 说明 |
|---|---|
| `db/migration/V10__visualization_ai_governance.sql` | 10 张新表，幂等 DDL + 索引，纯增量 |
| `db/rollback/R10__visualization_ai_governance.sql` | 仅 `DROP TABLE IF EXISTS`，不触碰既有表 |
| `specs/phase6-visualization-ai-governance/{spec.md,tasks.md}` | 规格与任务清单（已勾选） |

---

## 三、功能与接口说明

### 1. 图表

- 类型：`bar`、`line`、`area`、`pie`、`scatter`、`heatmap`，另有 `mode=distribution` 的数值分桶直方图（对外 `chartType=bar`）。
- 自由配置：X/Y 轴、聚合（含 COUNT/SUM/AVG/MIN/MAX）、过滤、排序、Top N、时间粒度（AUTO/DAY/WEEK/MONTH/YEAR）、颜色主题、标题。
- 解释信息随响应返回：数据集、聚合、扫描行数、数据版本号、是否截断/抽样、生成时间与提示语；空结果返回空态标记。
- 数据量控制：分类图默认 Top N 50（上限 500），饼图默认 12，时间序列按 200–2000 点自适应，散点 2000，热力 500，任何请求硬上限 2000 点。
- 推荐接口按字段类型给出最多 6 条（趋势/对比/占比/分布/相关性/热力），含理由与置信度。

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/analysis/chart` | 生成图表数据（含解释元信息） |
| `GET` | `/api/analysis/recommend?datasetId=` | 图表推荐 |
| `GET` | `/api/analysis/statistics?datasetId=` | 字段统计 |
| `GET/POST/PUT/DELETE` | `/api/chart-view`、`/api/chart-view/{id}` | 保存图表 CRUD（删除需 `X-Confirm: yes`） |
| `GET` | `/api/chart-view/{id}/status` | 版本/字段状态 |
| `POST` | `/api/chart-view/{id}/generate` | 重新生成；`X-Confirm-Version` 确认当前版本 |

### 2. 看板

- 12 列栅格：`gridX` 0–11、`gridW` 2–12、`x + w ≤ 12`、`gridH` 2–12；小屏（≤1100px）卡片强制整行。
- 支持创建/重命名/删除、添加/移除卡片、上移/下移重排；删除与移除需确认头。
- 版本变化时前端提示「数据已更新，需要刷新」并可一键刷新，绝不静默套用旧配置；字段失效提示重新配置。

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET/POST` | `/api/dashboard` | 列表/创建 |
| `GET/PUT/DELETE` | `/api/dashboard/{id}` | 详情/重命名/删除 |
| `POST` | `/api/dashboard/{id}/items` | 添加卡片 |
| `PUT/DELETE` | `/api/dashboard/{id}/items/{itemId}` | 更新/移除卡片 |
| `PUT` | `/api/dashboard/{id}/order` | 重排卡片 |

### 3. 导出

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/export/dataset/{id}` | 既有受限 CSV 导出 |
| `POST` | `/api/export/chart/{chartViewId}` | 已保存图表受限 CSV（`X-Confirm: yes`，返回 `X-Export-Rows`/`X-Export-Truncated`） |

- PNG 仅前端本地生成，不上传、不落盘。
- CSV：UTF-8 BOM、RFC4180 转义、`= + - @ \t \r` 前缀加单引号防公式注入；公开模式按会话行数/次数限制，私有上限 100000 行；始终校验归属。

### 4. AI 治理

- 场景：`DATASET_SUMMARY`、`FIELD_RELATION`、`OUTLIER_ANALYSIS`、`CHART_INSIGHT`（公开可用）、`CLEAN_SUGGESTION`（仅私有）。
- 每场景可配主/备模型、温度、最大输出、超时、重试、并发；未配置时回退既有 `ai_config`。
- 调用链：路由 → 熔断检查 → 多 scope 配额 → 并发许可 → 计数 → 主模型（含重试）→ 失败切备用 → 审计；全部失败 503，配额超限 429 且不发起供应商调用。
- 熔断：连续失败 3 次打开，429/预算耗尽立即打开，冷却 60s 后单探测；成功即恢复。
- 审计只写场景、供应商名、模型码、主体哈希、数据集/版本/图表 ID、耗时、token、成本、结果、错误类型、配额域；不写 Key、Prompt、响应、Cookie、Token 与原始敏感数据。

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET/POST/PUT/DELETE` | `/api/ai/governance/providers` | 供应商（写操作需确认头；Key 只写不读） |
| `GET/POST/PUT/DELETE` | `/api/ai/governance/models` | 模型目录 |
| `GET/PUT` | `/api/ai/governance/scenes` | 场景路由 |
| `GET` | `/api/ai/governance/calls` | 审计分页筛选（from/to/scene/modelCode/result/errorType） |
| `GET` | `/api/ai/governance/usage?date=` | 当日用量汇总 |
| `GET`、`POST /{id}/reset` | `/api/ai/governance/circuits` | 熔断查看与重置 |

公开模式：上述接口全部 404（`SecurityConfig` denyAll），前端路由守卫同时拦截 `/ai-governance`。

---

## 四、数据库迁移（V10 / R10）

- V10 新建 10 张表并建立必要索引，全部 `CREATE TABLE IF NOT EXISTS` + `CREATE INDEX` 幂等写法，纯增量，不修改既有表结构与数据。
- R10 只 `DROP TABLE IF EXISTS` 这 10 张表，不触碰 `dataset`、`dataset_column`、`dataset_version` 等既有对象。
- 演练在隔离实例 `127.0.0.1:13307`（数据目录 `temp_20261026_01/p6-it/mysql-data`）完成，日志见 `temp_20261026_01/p6-it/migration-rehearsal.log`。

| 场景 | 操作 | 结果 |
|---|---|---|
| 空库 | V1→V10 顺序执行 | 成功，新增表 10/10，总表 33 |
| 存量库 | V1→V9 + 插入存量 `dataset` 行，再执行 V10 | 成功，表数 23→33，新增 10/10，存量行保留 |
| 重复执行 | 再次执行 V10 | 退出码 0，表数不变，无报错 |
| 失败重试 | 执行按字节截断 60% 的 V10 | 报错中断，仅建成 5/10；随后执行完整 V10 → 10/10 |
| 错误脚本 | 完整 V10 追加非法 SQL | 报错退出；再次执行完整 V10 → 10/10 |
| 回滚 | 执行 R10 | 新增表 0/10，总表 23（既有表保留） |
| 再升级 | 回滚后重跑 V10 | 10/10，总表 33 |

> 说明：`scripts/rehearse-migrations.ps1` 等既有脚本用于 P5 的 13306 实例；本阶段使用独立端口与数据目录，互不影响。

---

## 五、AI 配置与密钥管理

1. **配置入口**：私有模式「AI 治理」页面或 `/api/ai/governance/*`；供应商 URL 仅允许 `http/https`。
2. **密钥写入**：新增供应商必须填写 Key；编辑时留空表示保持原值。Key 经 `AiSecretCipher` 加密存储，接口只返回 `apiKeyMask` 与 `keyConfigured`，任何接口都不回传明文。
3. **密钥轮换**：直接编辑供应商填入新 Key 即可，旧密文被覆盖；`AI_SECRET_KEY` 变更需先按既有密钥迁移流程处理。
4. **审计**：供应商保存审计仅记录名称与 `keyChanged`，不含任何密钥片段。
5. **主体哈希**：审计主体使用 `PrincipalHasher`（HMAC-SHA256 + `app.ai.audit-pepper`）。未配置 pepper 时使用固定盐并输出 WARN，**生产必须显式配置**。
6. **数据最小化**：私有场景仅携带 ≤3 行样本（≤20 列，敏感列 `[redacted]`）；公开场景仅携带统计摘要；最终统一经 `AiPiiMasker` 脱敏并按 `app.ai.max-prompt-chars`（默认 20000）截断。
7. **输出边界**：AI 输出仅作展示/建议，不自动执行清洗、SQL、文件或配置变更；清洗建议只生成草稿，需用户确认后再创建规则。

新增环境变量：

| 变量 | 默认 | 说明 |
|---|---|---|
| `AI_GOVERNANCE_ENABLED` | `true` | 关闭后回退旧版 `ai_config` 直连逻辑（不建议长期关闭） |
| `AI_AUDIT_PEPPER` | 空 | 审计主体哈希盐，生产必填；为空时使用固定盐并告警 |
| `APP_AI_MAX_PROMPT_CHARS` | `20000` | 送入模型的 Prompt 最大字符数，超出截断 |

---

## 六、部署、回滚与故障恢复

### 部署顺序

1. 备份整库与上传卷，记录当前版本；
2. 配置 `AI_SECRET_KEY`（既有）与 `AI_AUDIT_PEPPER`（新增）；
3. 执行 `db/migration/V10__visualization_ai_governance.sql`（或容器 `migrate` 服务）；
4. 部署后端与前端，检查 `/api/health`；
5. 私有模式进入「AI 治理」配置供应商、模型、场景路由，做一次试调用；
6. 前端冒烟：推荐 → 生成 → 保存 → 看板添加 → 导出 CSV/PNG → 治理页审计出现记录。

### 回滚

- 首选恢复部署前备份；
- 如需原地回滚：先下线新前端入口，执行 `db/rollback/R10__visualization_ai_governance.sql`，再回退应用版本；
- R10 只删本阶段新增表，既有数据集、版本、清洗记录不受影响；
- 回滚后 `schema_migration` 历史不会自动修改，需要重新升级时按 `db/README.md` 的说明处理，或直接恢复备份。

### 故障恢复

| 现象 | 处理 |
|---|---|
| AI 调用返回 503「暂无可用模型」 | 检查场景路由是否配置、主备模型是否启用；查看熔断页是否 OPEN，必要时重置 |
| 大量 429 | 配额或并发触顶；私有调整 `ai_quota`，公开收紧会话/IP/全局限额；超限不会调用供应商 |
| 熔断开后长时间不可用 | 冷却默认 60s，冷却后自动单探测；若探测仍失败会再次打开 |
| 图表「数据已更新，需要刷新」 | 正常保护；确认后以当前版本重新生成，字段缺失需重新配置 |
| 图表为空 | 检查过滤条件与字段类型；响应 `empty=true` 表示查询结果为空而非错误 |
| 看板卡片不显示 | 检查卡片宽度/坐标是否合法、保存图表是否已被删除或字段失效 |
| 导出被拒绝 | 公开模式行数/次数限制或归属不符；私有确认 `X-Confirm: yes` 是否正确携带 |
| CSV 出现前置单引号 | 属公式注入防护的正常行为（`=+-@` 开头单元格） |

---

## 七、验证与验收矩阵

> 三态定义：**已实际验证**＝本轮真实执行并取得结果；**仅静态检查**＝代码/配置审阅；**未验证**＝未执行。

| 项目 | 方式 | 结果 |
|---|---|---|
| 后端编译（178 源文件） | `mvn test-compile` | 已实际验证：BUILD SUCCESS |
| 后端全量测试 | `mvn test` | 已实际验证：**152 个用例全部通过**（0 失败/0 错误） |
| 图表推荐覆盖 | `ChartRecommendServiceTest` | 已实际验证：六类推荐、上限、空态、DAY/MONTH |
| 图表限额与扩展校验 | `AnalysisChartLimitTest` | 已实际验证：Top N、分桶、粒度、模式、2000 点上限 |
| 跨会话隔离 | `SavedChartServiceIsolationTest`、`DashboardServiceIsolationTest` | 已实际验证：统一 404 |
| 版本/字段失效 | 同上 | 已实际验证：409 且不静默套用；字段缺失报出列名 |
| AI 降级 | `AiGovernanceDegradationTest`（本地 HTTP 供应商） | 已实际验证：主备切换 DEGRADED、全失败 503、配额 429 不调用、无路由 503 审计 |
| 熔断 | `AiCircuitBreakerServiceTest` | 已实际验证：阈值 3、429/预算立即打开、单探测、成功恢复、重置 |
| 配额 | `AiQuotaServiceTest` | 已实际验证：私有默认、次数/Token/预算耗尽、公开未配置放行 |
| 审计脱敏 | `AiCallAuditServiceTest` | 已实际验证：仅元数据、主体哈希、写入失败不影响主流程 |
| 主体哈希 | `PrincipalHasherTest` | 已实际验证：稳定、64 位十六进制、不同盐不同结果、空主体归并 |
| CSV 注入防护 | `ExportCsvGuardTest` | 已实际验证：`= + - @ \t` 前缀、引号/逗号/换行转义 |
| V10/R10 迁移 | 隔离 MySQL 13307，五场景 | 已实际验证（见第四节） |
| 公开模式治理接口拒绝 | `SecurityConfig` + 路由守卫 | 仅静态检查 |
| 公开模式 AI 配额边界 | `PublicAiService` 调用链 | 仅静态检查（单元级路径未单独覆盖） |
| 前端生产构建 | `vite build`（隔离副本） | 已实际验证：2053 模块，约 39s，产物含 Analysis/Dashboard/DashboardDetail/AiGovernance |
| 敏感信息扫描 | `node scripts/scan-secrets.mjs` | 已实际验证：未发现疑似明文凭据 |
| 日志敏感字段扫描 | 源码日志语句全量检索 | 已实际验证：仅输出计数/状态/长度，无 Prompt/响应/Key |
| 性能基线 P50/P95 | 需完整实例 + 数据集压测 | **未验证**（见第九节） |
| 浏览器端交互回归 | 真实浏览器 | **未验证**（未启动联调环境） |

---

## 八、三轮自查

### 第一轮：安全与归属

- [x] 保存图表/看板所有读写均经过 `requireVisible`，跨会话统一 404；
- [x] 删除、移除卡片、供应商写操作均要求 `X-Confirm: yes`；
- [x] 生成图表前二次校验 SQL 白名单，不接受前端任意字段；
- [x] AI Key 仅写不读，VO 仅含掩码；实体排除序列化与 `toString`；
- [x] 审计与日志不含明文 Key/Prompt/响应/Cookie/Token；
- [x] 公开模式治理接口 denyAll，前端隐藏入口。

### 第二轮：一致性与边界

- [x] 图表点位上下一处计算：`resolveEffectiveLimit` 硬上限 2000，饼图 12，热力 500；
- [x] `distribution` 在请求层映射为 `bar + mode=distribution`，白名单不会因新模式被绕过；
- [x] 版本失效与字段失效均在服务层与控制器双重拦截，前端提供确认/重配路径；
- [x] 熔断状态持久化并在重启后恢复，冷却后单探测，避免雪崩与并发穿透；
- [x] 配额在调用前计数、用量在成功后累加，429 不产生供应商请求。

### 第三轮：交付完整性

- [x] V10 与 R10 成对存在，迁移五场景通过；
- [x] 152 个测试通过，前端构建通过，扫描通过；
- [x] 接口、看板、AI 配置、部署/回滚/恢复手册已写入本文档；
- [x] `tasks.md` 已勾选；
- [ ] 性能基线与浏览器回归尚未执行（如实标注，不作为已完成项）。

---

## 九、已知限制与后续建议

1. **性能基线未执行**：本轮无完整联调实例与 10 万行以上数据集，P50/P95 未取得真实数据。建议在预发布环境按以下步骤补测：导入 10 万行合成数据集 → 预热 3 次 → 对 `/api/analysis/chart`（趋势/分类/散点/热力）各采样 30 次，记录 P50/P95 与响应点数；对 `/api/chart-view/{id}/generate` 与 `/api/export/chart/{id}` 同样采样。
2. **浏览器交互未回归**：推荐、看板拖拽式布局、治理页表单与公开模式隐藏仅完成构建与静态检查，建议在预发布环境按第六节冒烟清单走查。
3. **前端产物体积**：`Analysis` chunk 约 636 kB（ECharts 按需仍较大），如首屏压力明显可进一步拆分图表页路由懒加载与算子模块。
4. **私有模式归属**：当前私有模式无用户体系，保存图表与看板以 `ownerType=private` 隔离；接入登录后建议迁移为 `userId` 维度。
5. **公开模式 AI 配额**：依赖配置 `ai_quota` 的 public_session/public_ip/public_global 三层；未配置时放行，生产上线前必须显式配置。

---

## 十、硬约束确认

- 未删除任何真实数据，未修改既有数据集内容；
- 未自动执行任何清洗/SQL 文件或配置变更，AI 建议仅生成草稿；
- 测试仅使用隔离库、临时目录与合成数据；
- 第四阶段生产门禁未解除，未切换真实生产流量。
