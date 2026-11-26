# DataClean 界面与功能模块升级：模块映射与缺口清单

> 阶段：升级前置盘点（只读梳理）
> 技术栈：Spring Boot + MyBatis-Plus + MySQL + Vue 3 + Element Plus + ECharts
> 双模式：public（匿名临时工作区）/ private（登录 + 权限）

## 1. 现状资产清单

### 1.1 前端路由（`frontend/src/router/index.js`）

| 路径 | 页面 | 说明 |
| --- | --- | --- |
| `/login` | Login.vue | 仅私有模式 |
| `/dataset` | Dataset.vue | 数据集列表 + 上传（默认首页） |
| `/preview/:id` | Preview.vue | 数据预览 |
| `/clean/:id` | Clean.vue | 清洗规则与执行 |
| `/analysis/:id` | Analysis.vue | 单数据集可视化分析 |
| `/jobs` | Jobs.vue | 任务中心（仅 3 类任务） |
| `/dashboard` | Dashboard.vue | 看板列表（空态仅一句提示） |
| `/dashboard/:id` | DashboardDetail.vue | 看板详情（卡片 + 排序） |
| `/ai-config` | AiConfig.vue | 私有模式 AI 配置 |
| `/ai-governance` | AiGovernance.vue | 私有模式 AI 治理 |

- 路由守卫：公开模式跳过登录，并拦截 `/login`、`/ai-config`、`/ai-governance`。
- 组件：`components/layout/Layout.vue`（固定 220px 侧栏，无响应式）、`components/ai/AiPanel.vue`。
- API 封装：`api/{auth,dataset,clean,job,analysis,dashboard,ai,governance,publicSession}.js`。
- 运行模式：`stores/appMode.js` 读取 `/api/public/config`。

### 1.2 后端接口

| 控制器 | 关键接口 | 公开模式 |
| --- | --- | --- |
| DatasetController | `GET/POST /api/dataset`、`/upload`、`/upload-async`、`/{id}/preview`、`/{id}/columns`、`/{id}/statistics`、`DELETE /{id}` | 可用，归属校验 |
| VersionController | `GET /api/dataset/{id}/versions`、restore、delete | 可用，归属校验 |
| CleanController | 规则 CRUD、`/execute`、`/preview`、`/rollback`、`/log` | 可用，归属校验 |
| JobController | 列表、详情、cancel、retry | 可用，归属过滤 |
| AnalysisController | `/recommend`、`/chart`、`/statistics/{id}` | 可用 |
| ChartViewController | 保存图表 CRUD、`/generate`、`/status` | 可用 |
| DashboardController | 看板 CRUD、items、order | 可用 |
| ExportController | `POST /api/export/dataset/{id}`、`/export/chart/{id}` | 可用，需 `X-Confirm: yes` |
| AiController | `/generate`（公开走 PublicAiService + 人机挑战）、stream/flux（公开 404）、config/template/history（公开 denyAll） | 受限 |
| AiGovernanceController | providers/models/scenes/calls/usage/circuits | 公开 404 |
| PublicController | `GET /api/public/config`（裸 JSON）、`POST /api/public/ai/challenge`、维护清理（公开 404） | 公开可读 |
| PublicSessionController | `POST /api/public/session`、`/challenge` | 公开唯一建会话入口 |
| HealthController | `/api/health` | 公开可读 |

- 安全：`SecurityConfig` 公开模式 `denyAll` 身份/配置/治理/审计；`PublicOriginGuardFilter` 写操作 Origin 校验 + `X-Confirm: yes`；`AnonymousSessionFilter` 注入匿名会话；`OwnershipService` 做数据集归属校验。
- 数据模型：`dataset`（ownerType=private/demo/anon，anonSessionId 不返回前端）、`dataset_column`、`dataset_version`、`dataset_version_rule`、`async_job`、`clean_rule/log`、`stats_cache`、`saved_chart`、`dashboard/item`、`export_record`、`audit_log`、`ai_*`、`anon_session/usage`、`backup_table_record`。
- 迁移：V1–V10（增量 + 回滚脚本）。

### 1.3 已具备的能力（可直接复用）

- 服务端下推统计：`AnalysisService.getStatistics` 一次聚合返回每列 `nullCount/nullRate/uniqueCount/min/max/sum/avg/std/q1/median/q3`，大数据集标记 `approximate/sampleRows`，带版本化缓存。
- 图表推荐：`GET /api/analysis/recommend?datasetId=`。
- 保存图表视图：字段映射/聚合/过滤/排序/TopN/标题/版本 + 生成时版本变更 409 确认。
- 看板 item 排序：`PUT /api/dashboard/{id}/order`。
- 任务：导入（含异步）、清洗、版本恢复，含进度/阶段/失败摘要/重试/取消。
- 导出：数据集 CSV、图表 CSV，均二次确认 + 公开配额。
- AI：公开受控生成（字段/统计/脱敏样本）、配额、熔断、审计；私有供应商/模型/场景路由/调用审计。
- 演示数据：启动时创建 `ownerType=demo` 只读数据集。

## 2. 目标导航 → 现有能力映射

| 目标模块 | 现有承载 | 复用 | 缺口 |
| --- | --- | --- | --- |
| 数据工作台 | 无（首页直接是数据集） | dataset/jobs/dashboard/statistics 聚合 | 全部新建：概览、质量摘要、最近活动、下一步推荐 |
| 数据集管理 | Dataset.vue | 列表/上传/预览/字段/统计/删除/版本/导出 | 卡片+表格双视图、搜索排序筛选、质量摘要、归属与清理时间、步骤式导入、演示只读标识、删除影响面 |
| 数据质量中心 | 无 | `/{id}/statistics`、`/{id}/columns`、清洗规则 API | 全新建：质量总览、字段详情、问题优先级、分布、规则草稿；重复行率/类型不一致当前无采集 |
| 任务中心 | Jobs.vue | 列表/cancel/retry | 筛选、耗时、阶段、错误安全说明；STATS/CHART/EXPORT/AI 类任务后端未记录 |
| 数据看板 | Dashboard.vue / DashboardDetail.vue | 看板/图表/推荐/排序/CSV | 空态模板与推荐入口、口径/版本/抽样标注、PNG 导出、拖拽栅格 |
| AI 助手 | AiPanel.vue + `/api/ai/generate` | 生成、配额、熔断、脱敏 | 独立页面、场景入口、额度展示接口、草稿转规则 |
| 帮助与示例 | 无 | demo 数据集、清洗/看板模板数据 | 全新建 |
| 系统与运行状态 | 无（actuator 8081） | `/api/health`、清理日志、AI 治理 | 全新建，且仅私有；公开必须 404 |

## 3. 缺口明细与实现策略

### 3.1 阻塞性缺陷（必须先修）

1. **公开模式误判为私有模式**：`stores/appMode.js` 用带业务拦截器的 `request` 读取 `/api/public/config`，而该接口返回裸 JSON（无 `code` 包装），拦截器判为失败 → `publicMode=false` → 守卫跳 `/login`。
   → 改为独立 axios 实例读取裸 JSON（运行副本已验证）。
2. **公开提示条无倒计时/容量/额度**：`Layout.vue` 只展示 TTL 文案。
   → 前端以会话首次建立时间 + `sessionTtlHours` 计算倒计时；容量/额度取 `/api/public/config` 上限值，用量按当前会话资源实时计数；无法获取的用量显示“待接入”，不伪造。

### 3.2 需要新增前端页面（不新增库表）

| 页面 | 数据来源 | 空/禁用策略 |
| --- | --- | --- |
| Workbench.vue | `GET /api/dataset`、`/api/job`、`/api/dashboard`、`/api/dataset/{id}/statistics` | 无数据→演示数据/导入引导；统计失败→“统计未完成” |
| Quality.vue | `/{id}/columns` + `/{id}/statistics` + `/api/clean/rule` | 重复率/类型不一致→“未采集”；空字段→空态 |
| AiAssistant.vue | `/api/ai/generate`（public 带挑战）、`/api/public/config` | AI 关闭/配额不足/超时/熔断→明确提示；草稿需用户确认 |
| Help.vue | demo 数据集、静态模板 | 纯前端内容，不含内部接口 |
| SystemStatus.vue | `/api/health`、治理接口（私有） | 公开模式路由 404/不渲染 |

- 质量指标口径：
  - 空值率 = Σ nullCount / (行数 × 列数)；
  - 唯一值比例 = uniqueCount / nonNullCount；
  - 异常值（数值列 IQR）= 基于 q1/q3 的越界行估算，标注“估算”；
  - 字段评分 = 完整性 40% + 唯一性 30% + 数值稳定性 30%，缺失项降权；
  - 重复行率、类型不一致率：当前统计接口未提供 → 展示“未采集（待接入）”，不估算、不占位为 0。

### 3.3 需要新增的后端能力（按优先级）

| 优先级 | 能力 | 方案 |
| --- | --- | --- |
| P0 | 公开额度只读状态 | 扩展 `GET /api/public/config`（在会话上下文中返回 `aiRemaining`、`datasetUsed/max`、`exportUsed/max`），仅当存在匿名会话时返回；不暴露会话 ID |
| P1 | 私有系统状态摘要 | 新增 `GET /api/admin/status/summary`（仅私有，公开 404），只返回健康、容量、清理、失败统计、AI 预算/熔断摘要 |
| P2 | 重复行率/类型不一致 | 需要新增聚合 SQL 或离线画像任务；本期不实现，质量中心明确标注“未采集” |
| P2 | 统一任务类型（统计/图表/导出/AI） | 现有 `async_job` 无对应记录，本期任务中心按类型筛选时显示“该类任务尚未接入” |

### 3.4 数据模型结论

- 本期不需要新增表；V11 仅在 P0 需要持久化额度快照时才引入，且必须为纯增量 + 可重复执行。
- `ownerType` 已能区分 demo / anon / private，数据集卡片与删除确认可直接使用。
- `dataset.anonSessionId` 已 `@JsonIgnore`，前端不得也不需要读取。

## 4. 交付顺序

1. 修复 `appMode`（P0）。
2. 全局设计系统 + 响应式 Layout（8 导航 + 公开提示条 + 顶栏）。
3. 工作台 → 数据集 → 质量中心 → 任务中心 → 看板 → AI 助手 → 帮助 → 私有系统状态。
4. 前端构建、后端测试、迁移演练、双会话隔离、图表性能、AI 配额/熔断/脱敏、敏感信息扫描。
5. 输出预览原型、修改清单、API 说明、测试结果、部署与回滚说明、已知限制。
