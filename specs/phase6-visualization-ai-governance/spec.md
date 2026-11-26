# 第六阶段规格：可视化体验升级与 AI 调用治理

> 状态：待确认（Spec Mode）
> 目标仓库：`C:\Users\zengyun\Desktop\Kimi_Agent_数据导入可视化平台\data-clean-platform`
> 前置：第一至第五阶段已完成；第四阶段生产发布门禁未解除期间，只在隔离数据库、临时上传卷与测试环境开发验证，不切换真实生产流量。
> 硬约束：不删除真实数据、不修改既有数据集内容、不自动执行清洗规则、不自动覆盖用户数据；测试只用隔离库、临时上传卷与合成数据；任何界面/日志/接口响应/构建产物/文档均不得出现 AI API Key、供应商密钥、Cookie、Token 或原始敏感数据。

## 1. 范围与不变量

### 1.1 本阶段交付范围

1. 图表推荐、自由配置、图表解释、性能约束。
2. 保存图表视图、看板、数据版本失效提示、PNG/受限 CSV 导出。
3. AI 供应商、模型目录、场景路由、主备模型、配额、熔断、调用审计。
4. 公开模式与私有模式的数据隔离、权限边界与成本控制。
5. V10 纯增量迁移、R10 回滚、自动化测试、迁移演练、性能基线与交付文档。

### 1.2 不变量（任何实现不得违反）

1. 所有数据集/图表/看板/导出访问先经过 `OwnershipService`；跨匿名会话或跨私有工作区统一返回 404，响应不包含资源名、字段名、表名或存在性提示。
2. 所有进入 SQL 的表名、列名、聚合、排序、时间粒度、过滤操作符必须先经 `SqlGuard` 白名单；过滤值、分桶宽度等一律参数绑定。
3. 浏览器不直接请求模型供应商；所有 AI 调用只经后端代理。
4. API Key 只写不读：新增必须提供，编辑留空表示不变；任何查询只返回 `keyConfigured` 与掩码。
5. 送入模型的内容默认最小化并脱敏：字段名、类型、统计摘要、受限脱敏样本；不发送全量原始数据。
6. AI 输出只作为文本/建议返回，不自动生成并执行 SQL、清洗、文件或系统配置变更。
7. 公开模式不展示供应商、模型名称、配置、调用历史、成本或熔断状态；对应接口一律 404。
8. 图表单次返回数据点上限 2000；分类图 Top N 默认 50；分布图分桶默认 20、上限 50；超限必须标记 `truncated/sampled` 并给出提示。
9. 既有 `chart_view` 表继续作为公开模式图表生成配额台账，语义不变；本阶段新增 `saved_chart` 作为用户可保存的图表视图。

## 2. 数据库 V10（纯增量）

新增 `db/migration/V10__visualization_ai_governance.sql`，全部 `CREATE TABLE IF NOT EXISTS` / `INFORMATION_SCHEMA` 判断，可重复执行；回滚 `db/rollback/R10__visualization_ai_governance.sql`。

### 2.1 可视化表

```sql
saved_chart(
  id BIGINT PK AUTO_INCREMENT,
  dataset_id BIGINT NOT NULL,
  dataset_version_id BIGINT NULL,          -- 保存时的数据版本
  owner_type VARCHAR(16) NOT NULL,         -- private/demo/anon
  anon_session_id BIGINT NULL,
  user_id BIGINT NULL,
  title VARCHAR(200) NOT NULL,
  chart_type VARCHAR(20) NOT NULL,
  chart_config JSON NOT NULL,              -- 仅白名单键，见 3.2
  config_hash CHAR(64) NOT NULL DEFAULT '',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_sc_dataset(dataset_id),
  KEY idx_sc_owner_session(owner_type, anon_session_id),
  KEY idx_sc_user(user_id)
)

dashboard(
  id BIGINT PK AUTO_INCREMENT,
  owner_type VARCHAR(16) NOT NULL,
  anon_session_id BIGINT NULL,
  user_id BIGINT NULL,
  title VARCHAR(200) NOT NULL,
  description VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_db_owner_session(owner_type, anon_session_id),
  KEY idx_db_user(user_id)
)

dashboard_item(
  id BIGINT PK AUTO_INCREMENT,
  dashboard_id BIGINT NOT NULL,
  saved_chart_id BIGINT NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  grid_x INT NOT NULL DEFAULT 0,
  grid_y INT NOT NULL DEFAULT 0,
  grid_w INT NOT NULL DEFAULT 4,
  grid_h INT NOT NULL DEFAULT 4,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_di_dashboard_chart(dashboard_id, saved_chart_id),
  KEY idx_di_dashboard_sort(dashboard_id, sort_order)
)
```

### 2.2 AI 治理表

```sql
ai_provider(
  id BIGINT PK AUTO_INCREMENT,
  name VARCHAR(100) NOT NULL,
  api_url VARCHAR(500) NOT NULL,
  api_key_cipher VARCHAR(1000) NULL,       -- AES-256-GCM，禁止序列化
  api_key_mask VARCHAR(64) NULL,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_ap_name(name)
)

ai_model(
  id BIGINT PK AUTO_INCREMENT,
  provider_id BIGINT NOT NULL,
  model_code VARCHAR(120) NOT NULL,        -- 发送给供应商的模型标识
  display_name VARCHAR(120) NOT NULL,
  temperature DECIMAL(3,2) NOT NULL DEFAULT 0.70,
  max_output_tokens INT NOT NULL DEFAULT 1024,
  timeout_ms INT NOT NULL DEFAULT 30000,
  retry_count INT NOT NULL DEFAULT 1,      -- 0-2
  concurrency_limit INT NOT NULL DEFAULT 4,
  cost_per_1k_input DECIMAL(12,6) NOT NULL DEFAULT 0,
  cost_per_1k_output DECIMAL(12,6) NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_am_provider_code(provider_id, model_code)
)

ai_scene_route(
  id BIGINT PK AUTO_INCREMENT,
  scene VARCHAR(40) NOT NULL,              -- DATASET_SUMMARY/FIELD_RELATION/OUTLIER_ANALYSIS/CHART_INSIGHT/CLEAN_SUGGESTION
  primary_model_id BIGINT NOT NULL,
  fallback_model_id BIGINT NULL,
  temperature DECIMAL(3,2) NULL,           -- NULL 表示沿用模型默认
  max_output_tokens INT NULL,
  timeout_ms INT NULL,
  retry_count INT NULL,
  concurrency_limit INT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_asr_scene(scene)
)

ai_call_log(
  id BIGINT PK AUTO_INCREMENT,
  scene VARCHAR(40) NOT NULL,
  provider_name VARCHAR(100) NOT NULL,     -- 仅名称，无地址/密钥
  model_code VARCHAR(120) NOT NULL,
  principal_hash CHAR(64) NOT NULL,        -- 匿名会话/私有主体的不可逆摘要
  dataset_id BIGINT NULL,
  dataset_version_id BIGINT NULL,
  saved_chart_id BIGINT NULL,
  request_at DATETIME NOT NULL,
  latency_ms INT NOT NULL DEFAULT 0,
  input_tokens INT NOT NULL DEFAULT 0,
  output_tokens INT NOT NULL DEFAULT 0,
  cost DECIMAL(12,6) NOT NULL DEFAULT 0,
  result VARCHAR(16) NOT NULL,             -- SUCCESS/FAILED/DEGRADED/REJECTED
  error_type VARCHAR(40) NULL,
  degraded TINYINT NOT NULL DEFAULT 0,
  quota_scope VARCHAR(20) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_acl_time(request_at),
  KEY idx_acl_scene(scene),
  KEY idx_acl_model(model_code),
  KEY idx_acl_result(result)
)

ai_quota(
  id BIGINT PK AUTO_INCREMENT,
  scope_type VARCHAR(24) NOT NULL,         -- private/public_session/public_ip/public_global
  scene VARCHAR(40) NOT NULL DEFAULT '*',
  model_code VARCHAR(120) NOT NULL DEFAULT '*',
  period VARCHAR(8) NOT NULL DEFAULT 'DAY',
  max_calls BIGINT NULL,
  max_tokens BIGINT NULL,
  max_cost DECIMAL(12,6) NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_aq_scope(scene, model_code, period, scope_type)
)

ai_usage(
  id BIGINT PK AUTO_INCREMENT,
  scope_type VARCHAR(24) NOT NULL,
  scope_key_hash CHAR(64) NOT NULL,        -- 会话/IP/主体的不可逆摘要；global 固定 'global'
  scene VARCHAR(40) NOT NULL,
  model_code VARCHAR(120) NOT NULL,
  period_key VARCHAR(16) NOT NULL,         -- yyyy-MM-dd
  calls BIGINT NOT NULL DEFAULT 0,
  input_tokens BIGINT NOT NULL DEFAULT 0,
  output_tokens BIGINT NOT NULL DEFAULT 0,
  cost DECIMAL(12,6) NOT NULL DEFAULT 0,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_au_scope_period(scope_type, scope_key_hash, scene, model_code, period_key),
  KEY idx_au_period(period_key)
)

ai_circuit_state(
  id BIGINT PK AUTO_INCREMENT,
  circuit_key VARCHAR(120) NOT NULL,       -- model:<id> / provider:<id>
  state VARCHAR(16) NOT NULL DEFAULT 'CLOSED', -- CLOSED/OPEN/HALF_OPEN
  failure_count INT NOT NULL DEFAULT 0,
  opened_at DATETIME NULL,
  cooldown_until DATETIME NULL,
  last_error_type VARCHAR(40) NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_acs_key(circuit_key)
)
```

### 2.3 迁移安全

1. 不修改、不删除任何既有表和数据；`chart_view`、`ai_config`、`ai_generation_log` 保留。
2. 所有 DDL 均幂等；重复执行不报错。
3. 不向新表写入任何示例业务数据；`ai_provider`/`ai_model`/`ai_scene_route` 为空时自动回退到既有 `ai_config` 默认配置。
4. R10 只删除本阶段新增 9 张表，不触碰既有表；脚本头部标注“破坏性操作，先备份”。

## 3. 可视化分析体验

### 3.1 图表推荐

新增 `GET /api/analysis/recommend?datasetId={id}`。

实现：`service/ChartRecommendService.java`

1. 通过 `OwnershipService.requireDataset` 做归属校验，跨会话 404。
2. 读取 `dataset_column`，按类型分类：
   - 数值：`INT/BIGINT/DECIMAL/DOUBLE/FLOAT`；
   - 日期：`DATE/DATETIME/TIMESTAMP` 或列名含 `date/time/day/month/year`；
   - 分类：非数值非日期。
3. 生成至多 6 条推荐，覆盖以下类型：
   | 场景 | 条件 | 推荐配置 |
   |---|---|---|
   | 趋势 | 日期列 + 数值列 | `line`，X=日期，Y=数值，AGG=SUM，时间粒度 AUTO（行数≤1000 用 DAY，否则 MONTH） |
   | 分类对比 | 分类列唯一值 ≤ 50 + 数值列 | `bar`，X=分类，Y=数值，AGG=SUM，TopN=20 |
   | 占比 | 分类列唯一值 ≤ 12 | `pie`，X=分类，AGG=COUNT，TopN=12 |
   | 分布 | 数值列 | `bar` + `mode=distribution`，分桶 20 |
   | 相关性 | 两个数值列 | `scatter`，X/Y=数值列 |
   | 热力关系 | 两个分类列唯一值均 ≤ 20 | `heatmap`，X/Y=分类列 |
4. 每条返回 `RecommendationVO`：`chartType/xAxis/yAxis/groupBy/aggregation/timeGranularity/topN/mode/title/reason/confidence/config`。
5. 推荐失败不阻断页面：字段不足时返回空列表与 `notice`；用户始终可切到自由配置。

### 3.2 图表配置扩展

`ChartRequestDTO` 新增（全部可选，服务端白名单）：

```java
String title;              // 长度 ≤ 120，仅展示
String colorTheme;         // default/warm/cool/dark/pastel
String mode;               // normal/distribution
String timeGranularity;    // AUTO/DAY/WEEK/MONTH/YEAR
Integer topN;              // 1-500，分类图默认 50
Integer bucketCount;       // 5-50，分布图默认 20
SortDTO sort;              // {field: x|y, direction: ASC|DESC}
List<FilterConditionDTO> filters; // 既有
Integer limit;             // 1-2000，既有
```

校验统一走 `service/ChartConfigValidator.java`：

1. X/Y/分组/过滤/排序列必须存在于当前数据集列元数据；否则 400。
2. 聚合仍走 `SqlGuard.requireAggregation`；过滤操作符仍走 `SqlGuard.requireFilterOperator`。
3. `timeGranularity` 仅允许日期列；SQL 只使用固定映射：
   - DAY → `DATE_FORMAT(col,'%Y-%m-%d')`
   - WEEK → `DATE_FORMAT(col,'%x-%v')`
   - MONTH → `DATE_FORMAT(col,'%Y-%m')`
   - YEAR → `DATE_FORMAT(col,'%Y')`
4. `mode=distribution` 仅允许数值 X 列；服务端一次查询 `MIN/MAX` 后计算桶宽，SQL 为 `FLOOR((col - #{min}) / #{width})`，桶数 ≤ 50，禁止逐行下拉。
5. `topN` 超过 500 返回 400；`limit` 超过 2000 返回 400；未提供时按默认值收敛。
6. `sort.field` 只允许 `x`/`y`，方向只允许 `ASC/DESC`；排序字段不直接拼接用户字符串。

`AnalysisService.generateChartData` 调整：

1. 继续先做归属校验、配额、字段校验。
2. 按 `chartType` 生成数据，新增 `mode=distribution` 分支。
3. 分类图先按 `topN` 截取，再按排序规则输出。
4. 结果超过 `limit` 或命中分桶/抽样时设置 `truncated/sampled/notice`。
5. 返回前填充版本信息：`dataVersionId=dataset.currentVersionId`、`dataVersionNo=dataset_version.version_no`、`rowCount`、`aggregation`、`generatedAt`。

### 3.3 图表解释与状态

`ChartDataVO` 新增字段（保持既有字段兼容）：

```java
String chartType;
Long datasetId;
Long dataVersionId;
Integer dataVersionNo;
Long rowCount;
String aggregation;
Boolean truncated;
Boolean sampled;
Integer sampleSize;
Boolean empty;
LocalDateTime generatedAt;
```

前端必须展示：
1. 数据范围：`rowCount`、`dataVersionNo`、聚合方式。
2. 抽样/截断：`sampled`/`truncated` 与 `notice`，用醒目 tag。
3. 空态：`empty=true` 时显示 `el-empty`，文案说明“当前条件下无数据/字段不兼容/结果被截断”。
4. 生成、加载、失败、导出状态：按钮 loading、错误 `ElMessage`、导出成功/截断提示。

### 3.4 性能

1. `frontend/src/utils/echarts.js` 按需注册：
   - `BarChart/LineChart/PieChart/ScatterChart/HeatmapChart`
   - `GridComponent/TooltipComponent/TitleComponent/LegendComponent/DataZoomComponent/VisualMapComponent/ToolboxComponent`
   - `CanvasRenderer`、`LabelLayout`、`UniversalTransition`
   - 禁止 `import * as echarts from 'echarts'`。
2. 图表实例在 `onUnmounted`、重新渲染前 `dispose`；窗口 `resize` 事件正确绑定/解绑。
3. 统计接口继续服务端聚合 + 版本化缓存；图表接口禁止前端全量拉取。
4. 大数据集：分类图 TopN、分布图分桶、散点图限制点数；超过阈值返回 `notice`，不返回超量数据点。

## 4. 保存图表与看板

### 4.1 保存图表视图

实现：`service/SavedChartService.java`、`controller/ChartViewController.java`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/chart-view?datasetId=` | 当前可见图表列表 |
| GET | `/api/chart-view/{id}` | 详情 + 版本状态 |
| POST | `/api/chart-view` | 保存当前配置 |
| PUT | `/api/chart-view/{id}` | 更新标题/配置（重新绑定当前版本） |
| DELETE | `/api/chart-view/{id}` | 删除 |
| POST | `/api/chart-view/{id}/generate` | 按保存配置重新生成数据 |
| GET | `/api/chart-view/{id}/status` | 版本失效/字段失效状态 |

规则：
1. 保存内容：`dataset_id`、`dataset_version_id`、`chart_type`、`chart_config`、`title`、`owner_type`、`anon_session_id`/`user_id`。
2. 公开模式只保存到当前匿名会话，`owner_type='anon'`；TTL 到期随会话清理。
3. 私有模式 `owner_type='private'`，`user_id` 取自 JWT；查询只返回 `owner_type='private'` 且 `user_id` 匹配（或为空的历史行）的记录。
4. 所有详情/更新/删除/生成统一 `requireVisible`；跨会话/跨用户 404。
5. 版本失效：`saved_chart.dataset_version_id != dataset.current_version_id` 时 `stale=true`，返回 `currentVersionId/savedVersionId/missingFields`。
6. 重新生成：若 `stale=true` 且请求头 `X-Confirm-Version` 不等于当前版本 → 409，响应 `code=409, message="数据已更新，需要刷新"`，`data` 含版本信息；用户确认后重新校验字段，字段缺失返回 409 `FIELD_MISSING`，否则按当前版本生成，**不自动覆盖**已保存配置；用户可显式 `PUT` 更新。
7. 保存/更新/删除写 `AuditService`：`CHART_VIEW_SAVE/UPDATE/DELETE`，detail 只含图表 ID、类型、数据集 ID，不含配置原文与数据。

### 4.2 看板

实现：`service/DashboardService.java`、`controller/DashboardController.java`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/dashboard` | 当前可见看板列表（含项数） |
| GET | `/api/dashboard/{id}` | 看板详情 + 图表项 + 版本状态 |
| POST | `/api/dashboard` | 创建 |
| PUT | `/api/dashboard/{id}` | 重命名/描述 |
| DELETE | `/api/dashboard/{id}` | 删除（级联删除项） |
| POST | `/api/dashboard/{id}/items` | 添加图表卡片 |
| PUT | `/api/dashboard/{id}/items/{itemId}` | 更新位置/尺寸 |
| DELETE | `/api/dashboard/{id}/items/{itemId}` | 移除卡片 |
| PUT | `/api/dashboard/{id}/order` | 按 itemId 列表重排 |

规则：
1. 栅格 12 列；`x` 0–11、`y` ≥ 0、`w` 2–12、`h` 2–12，且 `x+w ≤ 12`；非法 400。
2. 添加的图表必须 `requireVisible` 且与看板同属当前会话/私有工作区；否则 404。
3. 同一看板同一图表只允许一个项；重复添加返回 400。
4. 看板详情返回每个项的 `savedChartId/title/chartType/stale/missingFields`，前端显示“数据已更新，需要刷新”。
5. 公开模式看板只属于当前匿名会话；私有模式按 `user_id` 隔离。
6. 看板/项增删改写审计，detail 不含图表配置与数据。

## 5. 导出

1. PNG：前端 `chartInstance.getDataURL({type:'png', pixelRatio:2, backgroundColor:'#fff'})`，不经过后端，不包含未展示数据。
2. 受限 CSV：新增 `POST /api/export/chart/{chartViewId}`，要求 `X-Confirm: yes`。
   - `SavedChartService.requireVisible`；
   - `stale=true` 且无当前版本确认 → 409；
   - 服务端按保存配置重新生成数据，不信任前端数据；
   - 行数上限：公开模式 `min(exportMaxRows, 2000)`，私有模式 2000；
   - 复用 `ExportService.csvCell` 公式注入防护（`= + - @ \t \r` 前缀加 `'`）；
   - 复用 `PublicQuotaService.checkExport/recordExport`，`export_type='chart'`；
   - 跨会话统一 404，不允许通过导出接口绕过归属校验。
3. 数据集 CSV 导出保持既有行为与限制。

## 6. AI 治理架构

### 6.1 场景

`enum AiScene`：
`DATASET_SUMMARY`、`FIELD_RELATION`、`OUTLIER_ANALYSIS`、`CHART_INSIGHT`、`CLEAN_SUGGESTION`。

模板键映射：`dataset_summary/field_relation/outlier_analysis/chart_insight/clean_suggestion`。

### 6.2 路由与主备

实现：`service/AiGovernanceService.java`、`service/AiSceneRouter.java`。

1. `resolve(scene)`：
   - 查 `ai_scene_route` + 主模型 + 备用模型 + 供应商；
   - 无路由时回退到既有 `ai_config` 默认配置（构造“legacy”路由），保证存量部署可用；
   - 路由禁用且无备用 → 503。
2. 参数优先级：场景路由 > 模型默认 > 全局默认。
3. 每次调用：
   - 先主模型；超时/供应商 429/连续失败 → 熔断；
   - 熔断打开或主模型失败 → 尝试备用模型；
   - 主备均不可用 → 503，不继续重试放大故障；
   - 重试次数 0–2，仅对可重试错误（超时、5xx）重试；供应商 429 不立即重试。
4. 并发上限：进程内 `Semaphore` 按模型；超限等待 2 秒后 429。
5. 公开模式使用同一治理路由，但对外不返回供应商/模型/成本信息。

### 6.3 管理接口（仅私有模式）

新增 `controller/AiGovernanceController.java`，前缀 `/api/ai/governance`：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/providers` | 供应商列表（仅掩码） |
| POST/PUT | `/providers` | 新增/修改（`X-Confirm: yes`，写审计） |
| DELETE | `/providers/{id}` | 删除（被模型引用时 400） |
| GET | `/models` | 模型列表 |
| POST/PUT | `/models` | 新增/修改 |
| DELETE | `/models/{id}` | 删除（被路由引用时 400） |
| GET | `/scenes` | 场景路由列表 |
| POST/PUT | `/scenes` | 新增/修改 |
| GET | `/calls` | 调用审计查询（分页 + 过滤） |
| GET | `/usage` | 配额/预算使用汇总 |
| GET | `/circuits` | 熔断状态 |
| POST | `/circuits/{id}/reset` | 受控重置（`X-Confirm: yes`，写审计） |

安全：
1. `SecurityConfig` 公开模式对 `/api/ai/governance/**`、`/api/ai/config/**`、`/api/ai/template/**`、`/api/ai/history/**` 一律 `denyAll` → 404。
2. `AiGovernanceController` 每个方法开头调用 `requirePrivate()`，双重防护。
3. 响应 DTO 不含 `api_key_cipher`、`api_key`、完整 Prompt、完整响应、Cookie、Token。

### 6.4 生成接口接入

1. `AiService.generate/generateStream/generateFlux` 私有模式改为调用 `AiGovernanceService.invoke(scene, prompt, context)`；保留既有 `ai_generation_log` 脱敏落库。
2. `PublicAiService.generate` 保留公开配额/挑战/模板白名单，然后调用治理层；公开允许场景仍为 `DATASET_SUMMARY/FIELD_RELATION/OUTLIER_ANALYSIS/CHART_INSIGHT`，`CLEAN_SUGGESTION` 公开 403。
3. `AiGenerateDTO` 新增可选 `scene`；未提供时由 `templateKey` 映射。

## 7. AI 密钥与调用安全

1. `AiProvider`：`apiKeyCipher`、`apiKey` 使用 `@JsonIgnore`/`@ToString.Exclude`；`AiProviderVO` 无密钥字段，仅 `keyConfigured/apiKeyMask`。
2. 保存供应商：
   - 新增必须提供 API Key；编辑留空保持原密钥；
   - 加密使用既有 `AiSecretCipher`（AES-256-GCM）；
   - 必须 `X-Confirm: yes`；
   - 审计 `AI_PROVIDER_SAVE`，detail 仅供应商 ID/名称/是否更换密钥。
3. 查询供应商只返回状态与掩码；异常信息不包含密文片段。
4. 数据最小化 `service/AiPromptContextBuilder.java`：
   - 私有：数据集名称、行/列数、字段名、类型、空值率、唯一值、min/max/avg/std、最多 3 行脱敏样本（每行最多 20 列）；
   - 公开：仅字段名、类型、统计摘要，不发送样本行；
   - 敏感列（手机号/邮箱/证件/银行卡/地址/账号/Token 关键字）整列样本替换为 `[redacted]`；
   - 所有文本经 `AiPiiMasker.maskText`；Prompt 截断到 `app.ai.max-prompt-chars`。
5. AI 输出不自动执行：不解析 SQL、不创建清洗规则、不改配置；`CLEAN_SUGGESTION` 仅返回建议文本。

## 8. 配额、熔断与审计

### 8.1 配额

实现：`service/AiQuotaService.java`。

1. 私有模式：
   - 默认每场景每日 200 次、200000 tokens、20 成本单位；
   - `ai_quota` 可按 `scope_type='private'` + `scene` + `model_code` 覆盖；
   - 超限 429，不调用供应商。
2. 公开模式：
   - 保留 `PublicModeProperties` 的会话/IP/全站调用量与 token 预算；
   - `ai_quota` 可追加 `public_session/public_ip/public_global` 限制；
   - 超限 429；全站预算耗尽 → 熔断并 503（沿用现有语义）。
3. `ai_usage` 按 `scope_type + scope_key_hash + scene + model + 日期` 累加；调用前先计入次数，防止并发绕过；返回后累加 token/成本。
4. 预算耗尽、连续失败、供应商 429、超时均进入熔断判定。

### 8.2 熔断

实现：`service/AiCircuitBreakerService.java`。

1. 熔断键：`model:<id>` 与 `provider:<id>`。
2. 连续失败 3 次或供应商 429/预算耗尽 → `OPEN`，`cooldown_until=now+60s`。
3. `OPEN` 期间：主模型直接走备用；主备均 `OPEN` → 503。
4. 冷却结束 → `HALF_OPEN`，只允许 1 个探测请求；成功 → `CLOSED`，失败 → 重新 `OPEN`。
5. 内存状态 + `ai_circuit_state` 持久化；重启后从数据库恢复 `OPEN/HALF_OPEN`。
6. 禁止紧凑循环重试；重试间隔 100–300ms，重试次数 ≤ 2。

### 8.3 调用审计

实现：`service/AiCallAuditService.java`。

`ai_call_log` 只记录：
场景、供应商名称、模型标识、主体不可逆摘要、数据集/版本标识、图表 ID、请求时间、耗时、输入/输出 token 估算、成本、结果、错误类型、是否降级、配额范围。

禁止记录：明文 Key、完整 Prompt、完整模型响应、Cookie、Token、原始敏感数据、供应商完整 URL（可只存名称）。

主体摘要：`util/PrincipalHasher.java`，HMAC-SHA256(subject, `app.ai.audit-pepper`)；公开模式 subject=`anon:<sessionId>`，私有模式 subject=`user:<userId>`；`global` 固定 `global`。pepper 为空时使用应用内固定盐并记录 WARN（不落库、不输出）。

### 8.4 审计查询

`GET /api/ai/governance/calls`：
参数 `from/to`（ISO 日期）、`scene`、`modelCode`、`result`、`errorType`、`page`（默认 1）、`size`（默认 20，最大 100）。
返回 `{total, items}`；`items` 仅包含 8.3 允许字段。
公开模式该接口 404。

## 9. 公开模式边界

1. 公开模式可见：推荐、图表生成、保存图表、看板、图表 CSV 导出（受配额/TTL）、公开 AI（开关开启时）。
2. 公开模式不可见：AI 配置、AI 治理、调用历史、成本、熔断状态、供应商/模型名称；接口一律 404。
3. 看板/图表只能引用当前匿名会话拥有的图表与可访问数据集（含演示数据只读）。
4. 公开看板/图表随会话 TTL 清理；`PublicCleanupService` 增加 `saved_chart`、`dashboard`、`dashboard_item` 资源类型。
5. 公开模式不允许指定 `configId`、`customPrompt`、`scene` 管理参数；AI 返回内容不包含模型名称。

## 10. 前端

### 10.1 新增/修改文件

新增：
```
frontend/src/utils/echarts.js
frontend/src/api/chartView.js
frontend/src/api/dashboard.js
frontend/src/api/aiGovernance.js
frontend/src/views/Dashboard.vue
frontend/src/views/DashboardDetail.vue
frontend/src/views/AiGovernance.vue
frontend/src/components/analysis/ChartRecommend.vue
frontend/src/components/analysis/SaveChartDialog.vue
frontend/src/components/analysis/ChartStatusBar.vue
```

修改：
```
frontend/src/views/Analysis.vue
frontend/src/views/AiConfig.vue
frontend/src/components/layout/Layout.vue
frontend/src/router/index.js
frontend/src/api/analysis.js
frontend/src/api/ai.js
```

### 10.2 交互要求

1. `Analysis.vue`：
   - 顶部“推荐图表”横向卡片；点击套用推荐配置，仍可自由修改；
   - 配置区新增排序、Top N、时间粒度、分桶数、过滤条件、标题、主题；
   - 图表上方状态条展示数据版本、行数、聚合、抽样/截断/空态；
   - “保存图表”“导出 PNG”“导出 CSV”“加入看板”；
   - 已保存图表版本失效时显示“数据已更新，需要刷新”，刷新前不静默套用旧配置；
   - 图表实例正确销毁与 resize。
2. `Dashboard.vue`/`DashboardDetail.vue`：
   - 创建/重命名/删除看板；
   - 12 列栅格、拖拽排序（基于 `sortablejs`，已在依赖中）、响应式；
   - 添加/移除图表卡片，卡片显示 stale 状态。
3. `AiGovernance.vue`（仅私有）：
   - 供应商（掩码 + 二次确认）、模型、场景路由、调用审计、配额/熔断；
   - 所有保存/删除/重置二次确认并带 `X-Confirm: yes`；
   - 不显示任何完整密钥或 Prompt/响应。
4. `Layout.vue`：新增“看板”菜单；私有模式新增“AI 治理”；公开模式隐藏 AI 治理与 AI 配置。
5. `router/index.js`：新增 `/dashboard`、`/dashboard/:id`、`/ai-governance`；公开模式守卫重定向 `/ai-governance` 与 `/ai-config` 到 `/dataset`。

## 11. 测试矩阵

### 11.1 后端自动化测试

新增：
```
service/ChartRecommendServiceTest.java
service/ChartConfigValidatorTest.java
service/SavedChartServiceTest.java
service/DashboardServiceTest.java
service/AnalysisChartLimitTest.java
service/AiGovernanceRoutingTest.java
service/AiQuotaServiceTest.java
service/AiCircuitBreakerServiceTest.java
service/AiCallAuditServiceTest.java
service/PublicAiBoundaryTest.java
util/PrincipalHasherTest.java
controller/AiGovernanceAccessTest.java
```

覆盖：

| 编号 | 场景 | 期望 |
|---|---|---|
| T1 | 保存图表、看板布局、版本失效 | 保存成功；版本变化 `stale=true`；未确认时 409；确认后按当前版本生成 |
| T2 | 跨会话读取/修改/导出图表或看板 | 全部 404 |
| T3 | 大数据量图表 | TopN/分桶/抽样生效，数据点 ≤ 2000，`notice/sampled/truncated` 正确 |
| T4 | API Key 不可回读 | `AiProviderVO`/`AiConfigVO` 无明文字段；编辑留空不变 |
| T5 | 公开模式 AI 配置/治理/历史不可达 | 404，响应无供应商/模型信息 |
| T6 | PII 脱敏 | 手机号/邮箱/证件/银行卡/Token/地址列被替换 |
| T7 | 模型主备切换 | 主模型失败 → 备用成功；`degraded=true` |
| T8 | 配额 429 | 超限不调用供应商，返回 429 |
| T9 | 熔断 503 | 连续失败/预算耗尽 → 503，冷却后半开探测 |
| T10 | 调用日志无敏感信息 | 无 Prompt/响应/Key/Cookie/Token/原始数据 |
| T11 | 图表配置非法字段 | 400，不执行 SQL |
| T12 | 看板栅格非法 | 400 |

### 11.2 集成与演练

1. `mvn test`（`JAVA_HOME=D:\JDK21`）。
2. `node node_modules/vite/bin/vite.js build`。
3. V10 迁移四场景：空库、存量库、重复执行、失败重试；R10 回滚后表结构恢复到 V9。
4. 隔离环境双匿名会话隔离：A 不能读取/修改/导出/删除 B 的图表、看板、AI 日志。
5. 性能基线：隔离库合成 20 万行数据，记录推荐/柱状图/分布图/热力图接口 P50/P95、返回点数、抽样策略；记录前端渲染耗时（可用浏览器 Performance 面板或 `performance.now()`）。
6. 敏感信息扫描：`node scripts/scan-secrets.mjs`，扫描构建产物与源码；不得命中真实密钥、Cookie、Token。
7. AI 失败降级：使用测试桩（假供应商地址）验证主备切换、429、503、熔断恢复。

## 12. 迁移演练与回滚

### 12.1 V10 演练

1. 备份目标库。
2. 空库：依次执行 V1–V10，校验 9 张新表与索引存在。
3. 存量库：在 V9 库上执行 V10，校验既有数据行数不变、无表被删除。
4. 重复执行：再次执行 V10，无错误。
5. 失败重试：模拟中途失败（单独执行部分语句）后重跑，最终结构一致。

### 12.2 R10 回滚

1. 仅当确认无需要保留的图表/看板/AI 治理数据时执行。
2. 先备份；脚本删除 `saved_chart/dashboard/dashboard_item/ai_provider/ai_model/ai_scene_route/ai_call_log/ai_quota/ai_usage/ai_circuit_state`。
3. 不删除 `chart_view`、`ai_config`、`ai_generation_log` 及任何既有表。
4. 应用回滚：设置 `AI_GOVERNANCE_ENABLED=false`，AI 调用回退到既有 `ai_config`；前端隐藏看板/AI 治理入口。

### 12.3 故障恢复

1. 图表接口失败：前端保留旧图并提示，不覆盖已保存配置。
2. 版本失效：提示刷新；字段缺失给出可恢复错误，不自动删除图表/看板。
3. AI 主备均不可用：503，记录审计，不影响数据浏览/清洗/导出。
4. 熔断误开：私有管理页可受控重置；公开模式只能等冷却窗口。
5. 迁移失败：停止应用，按 R10 回滚并恢复备份。

## 13. 交付物

1. 代码与迁移：V10、R10、后端/前端改动。
2. `PHASE6_DELIVERY.md`：
   - 修改文件清单；
   - 数据库迁移与回滚；
   - 接口说明；
   - 看板使用说明；
   - AI 配置与密钥管理说明；
   - 部署步骤、回滚步骤、故障恢复手册；
   - 图表性能基线（数据规模、接口耗时、前端渲染耗时、抽样/截断策略、P50/P95）；
   - AI 治理验收报告（已实际验证/仅静态检查/未验证三态）；
   - 三轮自查记录：数据隔离、密钥/Prompt/日志泄露、成本/熔断/公开滥用。
3. `db/README.md`、`deploy/README.md` 增量更新。

## 14. 验收标准

1. 图表推荐覆盖趋势、分类对比、占比、分布、相关性、热力关系，且可切换自由配置。
2. 图表配置字段全部服务端白名单校验；大数据集不返回超量数据点。
3. 保存图表/看板跨会话 404；版本变化明确提示；字段失效可恢复。
4. PNG 与受限 CSV 导出可用，CSV 公式注入防护有效，不能绕过归属校验。
5. AI 供应商/模型/场景路由/主备/配额/熔断/审计可用；429/503 语义正确。
6. 公开模式无法访问 AI 配置、治理、历史、成本；公开 AI 默认关闭。
7. API Key 不可回读；Prompt/响应/日志无敏感信息。
8. V10 四场景演练通过；`mvn test`、`vite build`、敏感信息扫描通过。

## 15. 审批项

1. 新增表名：`saved_chart`、`dashboard`、`dashboard_item`、`ai_provider`、`ai_model`、`ai_scene_route`、`ai_call_log`、`ai_quota`、`ai_usage`、`ai_circuit_state`。
2. 图表上限：单次 2000 点、TopN 默认 50、分布桶默认 20/上限 50、热力分类上限 20。
3. 公开 AI 默认关闭；公开只允许 4 个场景，`CLEAN_SUGGESTION` 仅私有。
4. 熔断：连续失败 3 次、冷却 60 秒、半开单探测；重试 ≤ 2。
5. 私有配额默认：每场景每日 200 次 / 200000 tokens / 20 成本单位，可由 `ai_quota` 覆盖。
6. PNG 由前端导出；服务端只提供受限 CSV。
7. 新增环境变量：`AI_GOVERNANCE_ENABLED`（默认 true）、`AI_AUDIT_PEPPER`（建议必填）、`AI_PRIVATE_MAX_CALLS_PER_DAY`、`AI_PRIVATE_MAX_TOKENS_PER_DAY`、`AI_PRIVATE_MAX_COST_PER_DAY`、`AI_CIRCUIT_FAILURE_THRESHOLD`、`AI_CIRCUIT_COOLDOWN_SECONDS`。
