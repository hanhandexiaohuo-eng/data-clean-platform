# DataClean —— 数据导入、清洗、质量与可视化分析平台

一个可本地部署的数据处理平台：把 CSV / XLSX 文件导入 MySQL，自动识别字段类型，配置并执行清洗规则，按版本管理数据，生成质量报告、图表与看板，并支持 CSV / PNG 导出。平台同时支持**私有模式**（账号登录）与**公开演示模式**（匿名会话、配额与清理）。

> 本文档描述的是仓库中**已实现**的能力。未实现或未验证的内容在文末「已知限制」中单独列出，不做夸大。

---

## 1. 解决的问题

| 问题 | 平台的处理方式 |
|---|---|
| 上传的表格字段类型混乱、首列带 BOM | 流式解析后抽样推断类型，剥离 UTF-8 BOM，非法/空文件拒绝导入 |
| 清洗后无法回溯，改错只能重来 | 每次清洗生成独立物理表与新版本，`dataset.current_version_id` 指向当前版本，可恢复历史版本 |
| 图表基于旧数据却无提示 | 图表绑定数据版本，数据变化后标记 `stale`，刷新必须显式确认版本，否则 409 |
| 删除数据集/图表后看板整体打不开 | 删除时清理下游关联；看板对孤儿项降级为「失效卡片」而非整体 404 |
| 文本字段求和静默返回 0 | 非数值字段禁止 SUM/AVG，返回 400 并提示改用 COUNT |
| 0 行数据被评为 100 分 | 空数据集不给出评分，质量等级为 `EMPTY` |
| AI 输出直接 `v-html` 渲染 | Markdown 经 DOMPurify 白名单净化后再渲染 |
| 公开演示被刷接口 / 占满资源 | 匿名会话配额、上传与 API 限流、人机挑战、过期会话清理 |

---

## 2. 核心功能

- **数据导入**：CSV / XLSX（`.xls` 需另存为 `.xlsx`），支持有/无表头、编码选择、大文件异步导入、上传限流与文件扫描接口。
- **数据预览与列元数据**：分页预览、列类型/空值数/唯一值数/最值/均值/标准差。
- **数据质量**：类型一致性、空值率、重复行、日期合法性（含闰年校验）、异常值样例；结果按 `dataset_id + version_id` 缓存。
- **清洗**：缺失值处理、去重、类型转换、列名标准化、异常值处理、自定义过滤（默认关闭）；支持预览与执行，执行落库为异步任务。
- **版本管理**：导入/清洗/恢复产生版本；支持版本列表、恢复（异步任务）、删除；规则快照随版本保存。
- **任务中心**：持久化异步任务（导入/清洗/恢复），支持进度、取消、重试、失败摘要。
- **可视化分析**：柱/线/面积/饼/散点/热力图，SUM/AVG/COUNT/MAX/MIN 聚合，图表推荐，保存图表视图。
- **看板**：多图表栅格布局、排序、宽度调整；图表失效时展示异常态并可移除。
- **导出**：数据集 CSV（带 UTF-8 BOM，Excel 可直接打开）、图表 PNG。
- **AI 辅助**：数据集摘要、字段关系、异常值、图表洞察、清洗建议；支持 SSE 流式输出；AI 配置 / 模板 / 供应商 / 模型 / 场景路由 / 配额 / 熔断 / 调用审计（治理页仅私有模式）。
- **用户与隔离**：私有模式 JWT 登录；公开模式匿名会话（`__Host-dc_anon` Cookie），数据按会话隔离。
- **运维**：健康检查、Actuator + Prometheus 指标（独立管理端口）、审计日志、公开会话定时清理。

---

## 3. 技术栈（与 `pom.xml` / `package.json` 一致）

### 后端
- Java 17（唯一支持基线）、Spring Boot **3.3.5**
- Spring Web / WebFlux（SSE 流式输出）、Spring Security、Bean Validation
- MyBatis-Plus **3.5.7**、Druid 1.2.22、MySQL **8.4**（`mysql-connector-j`）
- Apache POI **5.2.5**（XLSX SAX 解析）、OpenCSV **5.9**
- OkHttp **4.12.0**（AI API）、Jackson、Guava、Commons Lang3、Lombok
- JWT：jjwt **0.12.6**
- Actuator + micrometer-registry-prometheus
- 测试：JUnit 5 / Spring Boot Test / Reactor Test / H2（仅测试）

### 前端
- Vue **3.4**（Composition API）、Vite **5.2**、Vue Router **4.3**、Pinia **2.1**
- Element Plus **2.6**、ECharts **5.5** + vue-echarts **6.7**
- axios **1.6**、marked **12**（Markdown）、DOMPurify **3.1.6**（vendored ESM）、SortableJS、Sass

---

## 4. 系统架构

```
浏览器 (Vue3 SPA)
      │ HTTPS
      ▼
Nginx（静态资源 + /api 反向代理 + 限流 + 429 JSON）
      │
      ▼
Spring Boot 后端（REST API / SSE）
  ├─ Controller 层：dataset / clean / version / job / analysis / chart-view
  │                 dashboard / export / quality / ai / auth / public
  ├─ Service 层：导入解析、清洗执行、版本切换、质量计算、图表/看板、导出、AI
  ├─ Job 层：持久化异步任务 Worker（导入 / 清洗 / 恢复）
  └─ Mapper 层：MyBatis-Plus
      │
      ├── MySQL 8.4（元数据表 + 每个数据集/版本的动态物理表）
      └── AI Provider（OpenAI 兼容接口，可选）
```

- 详细架构图与模块边界：[docs/ARCHITECTURE_2026-11-26.md](docs/ARCHITECTURE_2026-11-26.md)
- 数据库 ER 图：[docs/DATABASE_ER_2026-11-26.md](docs/DATABASE_ER_2026-11-26.md)
- 六条核心业务流程图：[docs/BUSINESS_FLOWS_2026-11-26.md](docs/BUSINESS_FLOWS_2026-11-26.md)
- 技术难点与方案：[docs/PROJECT_TECHNICAL_SUMMARY.md](docs/PROJECT_TECHNICAL_SUMMARY.md)

---

## 5. 功能模块与路由

| 模块 | 前端路由 | 主要接口 |
|---|---|---|
| 登录 | `/login` | `POST /api/auth/login`、`POST /api/auth/register`、`GET /api/auth/info` |
| 数据工作台 | `/workbench` | 聚合各模块数据 |
| 数据集管理 | `/datasets` | `GET/POST/DELETE /api/dataset`、`POST /api/dataset/upload`、`POST /api/dataset/upload-async` |
| 数据预览 | `/preview/:id` | `GET /api/dataset/{id}/preview`、`/columns`、`/statistics` |
| 数据清洗 | `/clean/:id` | `GET/POST/PUT/DELETE /api/clean/rule`、`POST /api/clean/preview`、`/execute`、`GET /api/clean/log` |
| 数据质量中心 | `/quality` | `GET /api/quality/{datasetId}`、`POST /api/quality/{datasetId}/compute` |
| 任务中心 | `/tasks` | `GET /api/job`、`/api/job/{id}`、`POST /api/job/{id}/cancel`、`/retry` |
| 数据集版本 | 清洗页/接口 | `GET /api/dataset/{id}/versions`、`POST .../{versionId}/restore?confirm=true`、`DELETE .../{versionId}` |
| 可视化分析 | `/analysis/:id` | `POST /api/analysis/chart`、`GET /api/analysis/recommend`、`GET /api/analysis/statistics/{id}` |
| 保存图表 | 分析页 | `GET/POST /api/chart-view`、`POST /api/chart-view/{id}/generate` |
| 数据看板 | `/dashboards`、`/dashboard/:id` | `GET/POST /api/dashboard`、`/api/dashboard/{id}/items`、`/order` |
| 导出 | 各页面 | `POST /api/export/dataset/{id}`、`POST /api/export/chart/{id}/png` |
| AI 助手 | `/ai` | `POST /api/ai/generate`、`GET /api/ai/generate/stream` |
| AI 配置 / 治理（私有） | `/ai-config`、`/ai-governance` | `/api/ai/config`、`/api/ai/template`、`/api/ai/governance/*` |
| 系统状态（私有） | `/system` | `GET /api/health`、管理端口 `/actuator/*` |
| 帮助 | `/help` | 静态说明 |

公开模式下 `/login`、`/system`、`/ai-config`、`/ai-governance` 在前端路由层被重定向到工作台，后端同时对管理接口做权限校验。

---

## 6. 数据库设计

数据库 `dataclean`，共 35 张元数据表（另有按数据集/版本动态创建的 `data_*` 物理表）。**未使用数据库外键**，关联完整性由应用层事务与删除策略保证。

核心关系：

```
dataset 1 ── N dataset_column
dataset 1 ── N dataset_version 1 ── N dataset_version_rule
dataset 1 ── N clean_rule / clean_log / data_quality_report / stats_cache
dataset 1 ── N saved_chart 1 ── N dashboard_item N ── 1 dashboard
dataset 1 ── N async_job（导入/清洗/恢复）
anon_session 1 ── N dataset / saved_chart / dashboard / async_job（公开模式隔离）
dataset.current_version_id ──> dataset_version.id（当前版本指针）
```

- `dataset_version`：`version_no` 唯一（`dataset_id + version_no`），`status` 为 `BUILDING/ACTIVE/ARCHIVED/FAILED`，`checksum` 用于恢复前校验。
- `saved_chart.dataset_version_id`：保存时绑定的数据版本，是 `stale` 判定的依据。
- `data_quality_report`：`dataset_id + version_id` 唯一，版本切换后自然失效重算。
- `async_job`：持久化任务状态（`PENDING/RUNNING/SUCCEEDED/FAILED/CANCEL_REQUESTED/CANCELLED/RECOVERABLE`）。

完整字段与迁移脚本见 `db/migration/V1`–`V11`，回滚脚本见 `db/rollback/`。

---

## 7. 本地运行

### 7.1 环境要求
- JDK 17
- Maven 3.9+
- MySQL 8.4（或 8.0+）
- Node.js 18+（建议 20+）

### 7.2 初始化数据库

```bash
# 按版本顺序执行 db/migration/V1 ... V11
# 详细规则、存量库基线策略与回滚方式见 db/README.md
mysql -u root -p -e "CREATE DATABASE dataclean DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

### 7.3 启动后端

```bash
cd backend
cp src/main/resources/application.yml.example src/main/resources/application.yml
# 编辑 application.yml 或使用环境变量：DB_URL / DB_USERNAME / DB_PASSWORD / JWT_SECRET
mvn spring-boot:run
# 默认 http://localhost:8080
```

`application.yml`、`.env` 含密码与密钥，已被 `.gitignore` 排除，仓库只保留脱敏模板。

### 7.4 启动前端

```bash
cd frontend
npm install
npm run dev
# 默认 http://localhost:5173，Vite 代理 /api 到后端
```

### 7.5 可选：公开演示模式

```bash
APP_MODE=public
PUBLIC_ALLOWED_ORIGIN=https://your-domain
PUBLIC_IP_HASH_PEPPER=<>=32 位随机串>
```

公开模式启动时会校验 Cookie、来源白名单、扫描器等安全配置，不满足则拒绝启动。

---

## 8. 部署

仓库提供 Docker Compose 基线（`docker-compose.yml`）与可选覆盖文件：

```bash
cp .env.example .env        # 填写 MySQL、JWT、AI 等真实值
docker compose run --rm migrate   # 执行版本化迁移
docker compose up -d --build
```

- 端口边界：MySQL 仅在 `db_net` 内网；后端不映射宿主机端口；Nginx 是唯一入口。
- HTTPS / 监控 / 文件扫描覆盖配置：`deploy/docker-compose.https.yml`、`deploy/docker-compose.monitoring.yml`、`deploy/docker-compose.scan.yml`。
- 详细说明见 [deploy/README.md](deploy/README.md)。

---

## 9. 关键配置

| 配置项 | 环境变量 | 说明 |
|---|---|---|
| 运行模式 | `APP_MODE` | `private`（默认）/ `public` |
| 数据库 | `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` | Druid 连接池 |
| JWT | `JWT_SECRET`、`JWT_EXPIRATION` | secret ≥32 字符 |
| 上传 | `UPLOAD_PATH`、`UPLOAD_MAX_ROWS`、`UPLOAD_MAX_COLUMNS` | 默认最多 50 万行 / 1000 列 |
| 异步任务 | `JOBS_ENABLED`、`JOBS_WORKER_THREADS`、`JOBS_VERSION_KEEP` | 线程数默认 2，保留版本默认 5 |
| 公开配额 | `PUBLIC_MAX_DATASETS`、`PUBLIC_MAX_ROWS`、`PUBLIC_SESSION_TTL_HOURS` 等 | 见 `application.yml.example` |
| AI | `AI_SECRET_KEY`（AES-256-GCM）、`PUBLIC_AI_ENABLED` | 未配置密钥时 AI 不可用 |
| 管理端口 | `MANAGEMENT_PORT` | 默认 8081，仅内网 |

---

## 10. 测试与验收

- 后端单元/集成测试：`backend/src/test/java`（29 个测试源文件，含 Mapper H2 隔离测试、服务隔离测试）。
- 执行：`cd backend && mvn test`
- 预发布接口验收：`docs/FINAL_ACCEPTANCE_REPORT_2026-11-25.md`，累计 86 项检查（18 模块 + 链路 A–F + 高风险场景 + XLSX），全部 PASS；6 项因环境限制明确标记为「未验证」。
- 专项修复报告：
  - `docs/R2_FIX_REPORT_2026-11-25.md`（关联完整性）
  - `docs/R3_FIX_REPORT_2026-11-25.md`（数据准确性）
  - `docs/R4_FIX_REPORT_2026-11-25.md`（XSS / 防重复提交 / N+1 / 429）
  - `docs/R1_FIX_REPORT_2026-11-25.md`（错误码与版本确认）
- 仓库未包含页面截图；启动前端后可直接查看各页面。

---

## 11. 目录结构

```
data-clean-platform/
├── backend/                     # Spring Boot 后端
│   └── src/main/java/com/dataclean/
│       ├── controller/          # REST 控制器（15 个）
│       ├── service/             # 业务服务（含 JobWorker / 版本 / 清洗 / 质量 / 导出）
│       ├── mapper/  entity/  dto/  enums/
│       ├── config/              # 安全、公开模式、任务、监控、上传配置
│       ├── filter/  handler/  exception/  util/
│       └── metrics/
├── frontend/                    # Vue3 + Vite SPA
│   └── src/{views,components,api,router,stores,utils,vendor}/
├── db/migration/                # V1–V11 版本化迁移
├── db/rollback/                 # 对应回滚脚本
├── deploy/                      # Nginx / 监控 / 扫描器 / 迁移镜像
├── docs/                        # 审计、修复、验收与设计文档
├── docker-compose.yml
└── .env.example
```

---

## 12. 已知限制与技术债

完整清单见 [docs/BACKLOG.md](docs/BACKLOG.md)，主要包括：

1. 数据库无外键；历史孤儿质量报表 / 规则快照 / 物理表未自动清理（仅审计 SQL）。
2. 并发清洗、并发质量计算使用 JVM 内锁，仅适用于单实例；多实例需改为数据库唯一约束或分布式锁。
3. 浏览器端真实点击导出、失效看板卡片交互未做自动化验证（自签证书限制），需人工验证。
4. 未做 10 万行以上压力测试；现有最大验证规模为 2 万行。
5. 后端保留 `POST /api/clean/rollback/{logId}` 路由并显式拒绝，历史回滚语义由版本恢复替代。

---

## 13. 安全说明

- AI 模块只发送数据集元信息与统计摘要，**不发送原始数据行**。
- AI 密钥使用 AES-256-GCM 加密存储，接口只返回掩码。
- 所有破坏性接口要求 `X-Confirm: yes`（或版本恢复 `confirm=true`）。
- 上传目录不可静态访问；临时文件定期清理；公开模式默认要求文件扫描实现。
- 审计日志记录操作类型、资源、结果与脱敏摘要，不记录原始数据、凭据或 SQL。
