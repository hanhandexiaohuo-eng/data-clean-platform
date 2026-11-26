# 第六阶段任务清单

> 执行顺序：数据层 → 后端可视化 → 后端 AI 治理 → 前端 → 测试与演练 → 文档与自查。
> 所有阶段均使用隔离数据库、临时上传卷与合成数据；不触碰真实生产数据。

## T1 数据库迁移与回滚

- [x] 新增 `db/migration/V10__visualization_ai_governance.sql`：
  - `saved_chart`、`dashboard`、`dashboard_item`；
  - `ai_provider`、`ai_model`、`ai_scene_route`、`ai_call_log`、`ai_quota`、`ai_usage`、`ai_circuit_state`；
  - 幂等 DDL 与索引。
- [x] 新增 `db/rollback/R10__visualization_ai_governance.sql`：仅删除新增表，头部标注破坏性操作。
- [x] 更新 `db/README.md`：V10/R10 说明、演练步骤、备份要求。
- 验证：空库 V1–V10；存量库 V9→V10；重复执行；失败重试。（隔离实例 13307 已全部通过，另加 R10 回滚/再升级）

## T2 后端可视化：推荐、配置校验、图表数据

- [x] `dto/ChartRecommendationVO.java`；排序使用 `ChartRequestDTO.SortDTO` 内部类（未单独建 `dto/SortDTO.java`）。
- [x] `service/ChartRecommendService.java`：六类推荐。
- [x] 配置校验合并进 `AnalysisService.validateExtendedConfig`（未单独建 `ChartConfigValidator`），覆盖字段/聚合/排序/TopN/时间粒度/分桶/模式。
- [x] 扩展 `ChartRequestDTO`：`title/colorTheme/mode/timeGranularity/topN/bucketCount/sort`。
- [x] 扩展 `ChartDataVO`：版本、行数、聚合、截断/抽样/空态、生成时间。
- [x] 修改 `AnalysisService.generateChartData`：时间粒度、TopN、分布分桶、版本信息、空态。
- [x] 修改 `AnalysisController`：`GET /api/analysis/recommend`。
- 验证：`AnalysisChartLimitTest`、`ChartRecommendServiceTest` 已通过（`ChartConfigValidatorTest` 由前者等价覆盖）。

## T3 后端保存图表与看板

- [x] 实体/Mapper：`SavedChart`、`Dashboard`、`DashboardItem`。
- [x] DTO/VO：`SavedChartDTO/VO`、`ChartViewStatusVO`、`DashboardDTO/VO`、`DashboardItemDTO/VO`。
- [x] `service/SavedChartService.java`：保存、更新、删除、可见性、版本状态、重新生成（409）。
- [x] `service/DashboardService.java`：CRUD、项增删、重排、栅格校验、图表可见性。
- [x] `controller/ChartViewController.java`、`controller/DashboardController.java`（删除类操作要求 `X-Confirm`）。
- [x] `PublicCleanupService` 增加 `saved_chart/dashboard/dashboard_item` 清理。
- [x] 审计：`CHART_VIEW_*`、`DASHBOARD_*`。
- 验证：`SavedChartServiceIsolationTest`、`DashboardServiceIsolationTest` 已通过（跨会话 404、stale/字段失效 409）。

## T4 后端导出

- [x] `ExportService.exportChartCsv(chartViewId)`：服务端重算、版本校验、行数上限、公式注入防护。
- [x] `ExportController` 新增 `POST /api/export/chart/{chartViewId}`（`X-Confirm: yes`）。
- [x] 复用 `PublicQuotaService.checkExport/recordExport`（新增 exportType 重载）。
- 验证：跨会话 404（`requireVisible`）、stale 409（`generate` 内建）、CSV 公式注入（`ExportCsvGuardTest` 已通过）；配额限制复用既有逻辑。

## T5 后端 AI 治理数据层

- [x] 实体/Mapper：`AiProvider`、`AiModel`、`AiSceneRoute`、`AiCallLog`、`AiQuota`、`AiUsage`、`AiCircuitState`。
- [x] `enum AiScene` 与模板键映射。
- [x] `util/PrincipalHasher.java`（HMAC-SHA256）。
- [x] DTO/VO：供应商（无密钥回读）、模型、场景路由、调用日志查询/视图、配额/使用、熔断。
- 验证：`PrincipalHasherTest` 已通过；密钥字段 `@JsonIgnore`/`@ToString.Exclude` 已核对。

## T6 后端 AI 治理服务

- [x] `service/AiSceneRouter.java`：路由解析 + legacy 回退。
- [x] `service/AiQuotaService.java`：私有/公开配额、用量累加、429。
- [x] `service/AiCircuitBreakerService.java`：失败计数、OPEN/HALF_OPEN/CLOSED、持久化、503。
- [x] `service/AiCallAuditService.java`：脱敏审计落库、过滤查询。
- [x] `service/AiPromptContextBuilder.java`：最小化上下文、PII 脱敏、截断。
- [x] `service/AiGovernanceService.java`：主备调用、并发信号量、重试、降级、成本估算。
- [x] `controller/AiGovernanceController.java`：管理接口（仅私有）。
- [x] 修改 `AiService`、`PublicAiService`：接入治理层；公开禁用 `CLEAN_SUGGESTION`。
- [x] 修改 `SecurityConfig`：公开模式拒绝 AI 配置/治理/历史接口。
- [x] 审计：`AI_PROVIDER_SAVE/DELETE`、`AI_MODEL_SAVE`、`AI_SCENE_ROUTE_SAVE`、`AI_CIRCUIT_RESET`。
- 验证：`AiGovernanceDegradationTest`、`AiQuotaServiceTest`、`AiCircuitBreakerServiceTest`、`AiCallAuditServiceTest` 已通过；`PublicAiBoundaryTest`、`AiGovernanceAccessTest` 未单独建类，公开边界以 `SecurityConfig` 静态核对 + 降级链路测试覆盖。

## T7 前端可视化

- [x] `utils/echarts.js` 按需注册；`Analysis.vue` 改用该模块。
- [x] 推荐卡片、状态条、保存弹窗以组件内区块实现于 `Analysis.vue`（未拆出 `ChartRecommend.vue`/`ChartStatusBar.vue`/`SaveChartDialog.vue`）。
- [x] `Analysis.vue`：推荐、自由配置、状态条、保存、导出、stale 提示、实例销毁/resize。
- [x] `api/analysis.js` 扩展推荐/保存图表/导出。
- 验证：`vite build` 通过；图表实例在 `onUnmounted` 统一 dispose。

## T8 前端看板与 AI 治理

- [x] `views/Dashboard.vue`、`views/DashboardDetail.vue`：创建/重命名/删除、12 列栅格、上移/下移、增删卡片、stale 标记。
- [x] `api/dashboard.js`。
- [x] `views/AiGovernance.vue`：供应商/模型/场景路由/调用审计/配额/熔断；二次确认；无密钥回显。
- [x] `api/governance.js`（对应任务中的 `api/aiGovernance.js`）。
- [x] `router/index.js`、`Layout.vue`：新增入口与公开模式守卫。
- [x] 治理入口已放入侧边菜单（`Layout.vue`），`AiConfig.vue` 未单独加入口。
- 验证：`vite build` 通过；公开模式访问 `/ai-governance` 由路由守卫拦截（静态核对）。

## T9 集成测试、迁移演练与性能基线

- [x] 启动隔离 MySQL 与后端可写副本，执行 V10 四场景迁移（另加 R10 回滚/再升级）。
- [x] 双匿名会话隔离：保存图表/看板跨会话 404（单元级已实际验证）；导出/任务复用既有 P5 结论。
- [ ] 合成 20 万行数据，采集推荐、柱状、分布、热力接口 P50/P95 与返回点数（未执行，见 `PHASE6_DELIVERY.md` 第九节）。
- [ ] 前端渲染耗时采集（未执行）。
- [x] AI 失败降级：本地假供应商验证主备、429、503（`AiGovernanceDegradationTest`）；熔断恢复由 `AiCircuitBreakerServiceTest` 覆盖。
- [x] `node scripts/scan-secrets.mjs` 扫描源码与构建产物：未发现明文凭据。
- [x] `mvn test`（`JAVA_HOME=D:\JDK21`，152 通过）、`vite build`（通过）。

## T10 文档与三轮自查

- [x] 编写 `PHASE6_DELIVERY.md`：文件清单、迁移、接口、看板说明、AI 配置与密钥管理、部署/回滚/故障恢复、性能基线、三态验收矩阵。
- [x] 更新 `db/README.md`、`deploy/README.md`。
- [x] 第一轮自查：图表与看板数据隔离（跨会话、公开 TTL、私有边界）。
- [x] 第二轮自查：AI 密钥、Prompt、响应、审计日志泄露。
- [x] 第三轮自查：AI 成本、熔断、降级、公开模式滥用。
- [x] 修复发现的问题后重新跑测试与扫描（修复：字段失效与版本无关时也应 409；更新旧断言）。
- [x] 隔离 MySQL 与后端进程已停止；临时文件仅为本阶段测试数据。
