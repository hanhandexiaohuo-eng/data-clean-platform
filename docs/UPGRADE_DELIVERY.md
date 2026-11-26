# 界面与功能模块升级 · 交付说明

更新日期：2026-11-23

## 1. 本次改动文件

前端

- `frontend/src/stores/appMode.js`：修复 `/api/public/config` 裸 JSON 被拦截器误判导致公开模式失效；新增工作区计时辅助。
- `frontend/src/styles/app.css`（新增）：设计令牌与通用组件样式（卡片、指标、空态、列表、响应式）。
- `frontend/src/main.js`：引入全局样式。
- `frontend/src/components/layout/Layout.vue`：8 项导航（系统页仅私有）、窄屏抽屉、顶栏面包屑/模式/刷新、公开提示条倒计时。
- `frontend/src/router/index.js`：新信息架构与公开模式路由守卫。
- `frontend/src/views/Workbench.vue`（新增）：工作区概览、质量概览、最近活动、下一步推荐。
- `frontend/src/views/Quality.vue`（新增）：数据集/版本选择、质量总览、字段明细、清洗草稿入口。
- `frontend/src/views/AiAssistant.vue`（新增）：受控 AI 场景、额度展示、安全提示；私有模式引导至 AI 配置/治理。
- `frontend/src/views/Help.vue`（新增）：5 个演示场景、四步流程、公开工作区限制与 FAQ。
- `frontend/src/views/SystemStatus.vue`（新增）：私有模式运行摘要，缺失指标显示“未采集”。
- `frontend/src/views/Dataset.vue`：卡片/表格双视图、搜索排序分页、步骤导入、演示数据只读、删除影响确认。
- `frontend/src/views/Jobs.vue`：筛选、进度/阶段/耗时、脱敏失败摘要、取消/重试确认、轮询。
- `frontend/src/views/Dashboard.vue`：空态模板、推荐图表入口、创建空白看板。
- `frontend/src/api/ai.js`：新增 `getPublicAiQuota`。

后端

- `backend/.../service/StreamingImportService.java`：异步导入文本列类型推断由固定 `VARCHAR(500)` 改为 ≤255 用 `VARCHAR(255)`、更长的用 `TEXT`，修复超长单元格导致整批导入失败。
- `backend/.../controller/PublicController.java`：新增 `GET /api/public/ai/quota`，仅公开模式、仅返回当前匿名会话的 AI 用量与剩余额度。

数据库迁移：无新增（未变更表结构）。

## 2. 路由

| 路由 | 页面 | 公开模式 |
| --- | --- | --- |
| `/workbench`（默认） | 数据工作台 | 可用 |
| `/datasets`（兼容 `/dataset`） | 数据集管理 | 可用 |
| `/preview/:id` `/clean/:id` `/analysis/:id` | 预览 / 清洗 / 分析 | 可用 |
| `/quality` | 数据质量中心 | 可用 |
| `/tasks`（兼容 `/jobs`） | 任务中心 | 可用（仅本会话） |
| `/dashboards`（兼容 `/dashboard`）`/dashboard/:id` | 数据看板 | 可用 |
| `/ai` | AI 助手 | 可用（受配置与配额控制） |
| `/help` | 帮助与示例 | 可用 |
| `/system` | 系统与运行状态 | 拦截 → `/workbench` |
| `/ai-config` `/ai-governance` `/login` | 配置/治理/登录 | 拦截 → `/workbench` |

## 3. 接口

- 新增 `GET /api/public/ai/quota`：公开模式返回 `{enabled, used, limit, remaining}`；私有模式 404。只读，不返回 IP、全局配额或供应商信息。
- 复用既有接口：`/api/dataset`、`/api/dataset/{id}/statistics`、`/api/dataset/{id}/versions`、`/api/job`、`/api/dashboard`、`/api/analysis/*`、`/api/ai/generate`、`/api/public/session`、`/api/public/config`、`/api/public/ai/challenge`。

## 4. 验证结果

- 前端生产构建：`npm run build` 通过（33s）。
- 后端测试：152 个用例全部通过。（第八阶段已统一为 JDK 17 基线，标准 `mvn clean test` 直接通过，不再需要 JDK 24 与 Byte Buddy 实验开关。）
- 浏览器验证（公开模式，桌面）：工作台、数据集、质量、任务、看板、AI、帮助均通过；无功能性 JS 错误。
- 公开模式边界：`/system` 访问被重定向到 `/workbench`，侧栏不出现系统菜单；帮助页源文件扫描无内部地址/密钥/运维命令。
- 会话隔离：新建匿名会话仅能看到演示数据，看不到其他会话的数据集与任务。
- 移动端：599px 视口下侧栏收起为抽屉、遮罩正常、无横向滚动、提示条换行正常（精确 390px 设备模拟受浏览器工具限制）。
- 导入修复：同一份 29,971 行、含 954 字符单元格的 CSV 异步导入成功。

## 5. 已知限制（页面已如实标注，未伪造）

- 重复率、类型不一致：后端无对应统计，显示“未采集”。
- 统计 / 图表 / 导出 / AI 任务：无任务记录，任务中心标注“未接入”。
- 看板自由编排、模板图表、从已有图表添加：仍在看板详情页/未接入，入口为创建空白看板或跳转分析页。
- 历史版本统计：统计接口按当前版本返回，切换版本时提示“该版本统计未接入”。
- 公开 AI：本地 `PUBLIC_AI_ENABLED=false`，页面为禁用态；启用后需一次性挑战令牌，额度按服务端计数。
- Mockito 测试依赖（第八阶段已修复）：统一 JDK 17，标准 `mvn clean test` 通过，无需 `-Dnet.bytebuddy.experimental=true`。

## 6. 本地联调

- 后端：`local-run/backend` 下 `java -jar app.jar --spring.profiles.active=local --app.public.cookie-secure=false --app.public.local-insecure-cookie=true`（8080，actuator 8081）。
- 前端：`local-run/frontend` 下 `npm run dev`（5173，代理 `/api` 到 8080）。
- 本机 npm 的 `npm.ps1` 存在路径拼接问题，改用 `node D:\Node\node_modules\npm\bin\npm-cli.js run <script>`。

## 7. 第八阶段更新（真实数据能力补齐，2026-11-23）

第八阶段已把上文“未接入”项中的以下能力补齐，并以 JDK 17 统一本地/测试/Docker/预发基线：

- 重复率 / 类型不一致真实统计（数据库聚合、状态四态、缓存、规则草稿，质量中心已用真实接口）；
- 统计、图表、导出、AI 调用统一登记任务中心（重试仅限有 Worker 的类型，失败摘要脱敏）；
- 图表视图保存/读取、看板重命名与拖拽排序、PNG 图表导出、CSV/PNG 导出任务追踪；
- 纯增量迁移 `V11__data_quality_report.sql` 与回滚 `R11__data_quality_report.sql`。
- JDK 17 唯一基线（开发/测试/CI/Docker/预发一致），并提供 `scripts/ci-gate.ps1` 门禁：校验 JDK 17 → `mvn clean test` → `npm run build` → 敏感信息扫描；JDK 21/24+ 不支持的原因见 PHASE8 文档。

仍未完成：真实 AI 供应商 429/503/熔断验证、预发 ClamAV 病毒检出（主机内存不足）、TTL 到期实际清理观察。

详见根目录 `PHASE8_REAL_DATA_PRERELEASE.md`（含性能基线、预发验证结果、风险与回滚说明）。
