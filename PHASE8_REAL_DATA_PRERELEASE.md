# 第八阶段交付：真实数据能力补齐与预发验证

> 阶段目标：不新增纯展示页面，补齐此前标记为“未接入”的真实数据能力，并在隔离环境完成可重复验证。
> 约束：未删除真实数据、未切换生产流量、未执行真实生产库迁移；性能与故障测试均使用隔离库、合成数据、临时上传卷与预发环境。

## 1. Java 与构建运行基线

| 项 | 结论 |
| --- | --- |
| 唯一支持版本 | **JDK 17（LTS）**，本地实测 `17.0.21.1` |
| 编译目标 | `maven-compiler-plugin release=17` |
| 构建镜像 | `maven:3.9-eclipse-temurin-17` |
| 运行镜像 | `eclipse-temurin:17-jre-alpine` |
| 预发实际运行 | `openjdk version "17.0.21.1"`（2026-11-23 重建镜像并滚动重启 backend） |
| 测试兼容 | 已移除 `-J--add-opens=jdk.compiler/...` 等 JDK 24 实验参数与 `<fork>true</fork>`；标准 `mvn clean test` 直接通过 |
| CI 门禁 | `scripts/ci-gate.ps1`：先校验 JDK 必须为 17（否则立即失败并提示原因），再依次执行 `mvn clean test`、`npm run build`、`scan-secrets.mjs`，任一失败即退出码 1 |

不支持的版本及原因：

- **JDK 21**：与固定基线不一致；Mockito/Byte Buddy 在 21 上需要额外 `--add-opens`，会把环境差异带进 CI。
- **JDK 24+**：Byte Buddy 对 24 的类文件格式支持依赖 `-Dnet.bytebuddy.experimental=true`，属于实验性开关，不能作为长期方案；同时 JDK 24 非 LTS。
- **JDK 8/11**：低于 Spring Boot 3.3 的最低要求（17）。

验证证据（本地、干净构建）：

```
mvn clean test   → Tests run: 152, Failures: 0, Errors: 0, Skipped: 0（JDK 17）
npm run build    → 成功（vite 5.2.8）
node scripts/scan-secrets.mjs → 未发现疑似明文凭据（exit=0）
ci-gate.ps1      → JDK 26 环境被拒绝（exit=1，提示仅支持 17）；JDK 17 环境全部门禁通过
                   （mvn clean test 152 通过 → npm run build 成功 → 敏感信息扫描通过 → 退出码 0）
```

> 本地注意：当前开发机把项目放在 Desktop，Java 进程在该目录创建 `target` 会被系统拒绝（`Cannot create resource output directory`）。门禁与干净构建请先把源码复制到可写目录（本次在 `temp_20261026_01\gate-check` 中执行，`node_modules` 用目录联接复用），这不是项目本身的问题；CI/预发的工作目录可写，不受影响。

## 2. 数据质量真实统计

### 2.1 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/quality/{datasetId}?versionId=` | 读取报告；无记录返回 `status=NOT_COLLECTED` |
| POST | `/api/quality/{datasetId}/compute?versionId=&columns=&sampleLimit=&refresh=` | 触发统计（默认当前版本、最多 20 字段、抽样 ≤10、缓存 10 分钟） |

### 2.2 实现要点

- 全部下推 MySQL 聚合，**不把明细拉入应用内存**：
  - 重复：`GROUP BY 字段组合 HAVING COUNT(*)>1` 的外层 `SUM/COUNT/MAX`，抽样仅 `LIMIT n`。
  - 类型不一致：对数值/日期/布尔/文本列用 `SUM(CASE WHEN ... REGEXP ...)` 一次性统计非空、空串、去重、格式命中数。
- 状态机：`NOT_COLLECTED → COMPUTING → DONE / FAILED`；`FAILED` 保留 `error_summary` 供前端重试提示。
- 输出：`duplicate{status, duplicateRows, duplicateRate, duplicateGroups, maxGroupSize, scopeColumns, samples, notice}`、`typeMismatch{checkedColumns, mismatchColumnCount, columns[]}`、`summary{score, duplicateRate, duplicateRows, mismatchColumnCount, totalMismatchRows}`、`draftRules`（`TYPE_CONVERT` / `REMOVE_DUPLICATE`，`requiresConfirm=true`）、`queryCount`、`computedAt/expiresAt`。
- 日期校验使用带月/日取值范围的表达式，`2026/13/45` 会被判为格式异常而不是正常日期。
- 归属：公开模式按匿名会话、私有模式按用户校验；跨会话统一 404。
- 仅生成清洗规则草稿，不自动改数据。

### 2.3 前端

`/quality` 页：新增“开始质量统计 / 重新统计”（二次确认）、四态提示（未采集/计算中/完成/失败）、重复率与类型不一致真实值、字段级“格式异常/评分/建议转换”，缺失统计时保持“未采集”，不再用 0 或模拟值伪装完成。

## 3. 任务中心完整接入

- 统一任务类型：`IMPORT`、`CLEAN`、`RESTORE`、`QUALITY_STATS`、`CHART`、`EXPORT`、`AI`。
- 记录字段：类型、状态、阶段、进度、数据集与数据版本、创建/开始/结束时间、耗时、失败摘要、归属主体。
- 状态机：`PENDING → RUNNING → SUCCEEDED / FAILED`，另含 `CANCEL_REQUESTED / CANCELLED / RECOVERABLE`。
- 重试：仅 `IMPORT/CLEAN/RESTORE` 等有 Worker 的类型允许重试；其余返回 400「该任务类型不支持重试，请在原页面重新发起」，避免“假排队”。
- 敏感信息：失败摘要仅使用业务可读文案（如「任务执行失败，请稍后重试」「公开演示环境暂未开放 AI 分析」），不写 SQL、内部路径、堆栈、Cookie/Token/密钥或原始数据行。
- 跨会话查询任务统一 404。
- 前端 `/tasks`：筛选、统计卡、空态、详情、重试均走真实接口；类型标签覆盖新增类型。

## 4. 图表、看板与导出

- 图表生成：`POST /api/analysis/chart`，登记 `CHART` 任务；大数据集由服务端 `GROUP BY + TopN + LIMIT` 聚合，只返回聚合点。
- 图表视图：`POST/GET/PUT/DELETE /api/chart-view`，保存数据版本、字段映射、聚合、筛选、排序、TopN、抽样/截断状态、生成时间；字段失效或版本变化返回 409 并在前端提示“需刷新/重新配置”。
- 看板：`/api/dashboard` 创建/重命名、`/items` 增删、`/order` 拖拽排序、栅格坐标（`gridX/gridY/gridW/gridH`）+ `sortOrder`；公开模式归属匿名会话并随 TTL 清理，私有模式按权限保存。
- 受控导出：
  - CSV：`POST /api/export/dataset/{id}`、`POST /api/export/chart/{chartViewId}`，保留公式注入防护，公开模式遵守会话归属/次数/行数上限。
  - PNG：`POST /api/export/chart/{chartViewId}/png?width=&height=`，服务端 Java2D 渲染，无额外前端依赖。
  - 导出均登记 `EXPORT` 任务，可在任务中心追踪。
- 本轮修复：导出内部生成图表数据时不再额外登记 `CHART` 任务，避免在 `EXPORT` 任务运行期间触发“同一会话活跃任务数”配额导致 429。

## 5. AI 调用任务

- `/api/ai/generate` 在完成归属校验后登记 `AI` 任务并写脱敏审计（仅场景/模式等元数据）。
- 公开模式默认关闭；本地与预发均为 `AI=off`，请求返回 403 安全文案。
- 错误映射：429 → `QUOTA_EXCEEDED`，503 → `AI_CIRCUIT_OPEN`，408 → `AI_TIMEOUT`，其他 → `AI_CALL_FAILED`；前端只展示排队/处理中/完成/失败/配额不足/熔断等安全状态，不展示供应商、密钥、Prompt 或完整响应。
- AI 清洗建议只产生可编辑草稿，需用户二次确认后才创建清洗任务。
- 429/503/熔断恢复：本地未配置供应商，未做真实供应商故障注入 → **未验证**；仅验证关闭态与错误码映射路径。

## 6. 数据库迁移

新增（纯增量）：

- `db/migration/V11__data_quality_report.sql`：`data_quality_report`（dataset_id、version_id、status、columns_key、payload JSON、computed_at、expires_at、error_summary、owner_type、anon_session_id、时间戳；唯一键 `(dataset_id, version_id)`，status/expires 索引）。
- `db/rollback/R11__data_quality_report.sql`：`DROP TABLE IF EXISTS data_quality_report`。

迁移演练（隔离 MySQL 8.4.6，端口 13307，真实执行 `deploy/migrate/run-migrations.sh`）：

| 场景 | 结果 |
| --- | --- |
| 空库全量 V1→V11 | 通过（11 个版本成功） |
| 立刻重复执行 | 通过（新执行 0、跳过 11） |
| 存量库（仅 V1/V2 + 业务行） | 从 V3 递增执行到 V11，原有 `dataset` 行保留 |
| 截断 V11 制造失败 | 失败并记录 `success=0`，服务不脏写 |
| 修复后重试 | 通过，`success=1`，表创建成功 |
| 执行 R11 回滚 | 表删除；再次执行 V11 可恢复 |

## 7. 性能与预发验证

### 7.1 合成数据

`scripts/perf/generate-synthetic-csv.mjs`：10 万行、7 列；每 97 行整行复制（重复）、每 113 行金额为 `N/A`、每 157 行日期非法、每 47 行布尔列为 `unknown`。

### 7.2 本地（JDK 17，单机 MySQL，隔离库）

| 项 | 结果 |
| --- | --- |
| 100,000 行异步导入 | 7.0 s（`IMPORT` 任务 SUCCEEDED，100000 行） |
| 质量统计（7 字段，强制刷新 5 次） | 4–6 s，P50 ≈ 5 s，P95 ≈ 6 s，报告 `queryCount=13` |
| 图表聚合（region × SUM，TopN 20） | 340 ms，返回 5 个聚合点 |
| 看板加载 | P50 105 ms，P95 176 ms |
| 任务中心列表 | 99 ms |
| CSV 导出（上限 10,000 行） | ≈1 s，568 KB |
| PNG 导出（1200×720） | 867 ms，29.6 KB |
| 峰值内存 | 后端以 `-Xmx512m` 运行完成上述全部场景，无 OOM |

### 7.3 预发（Docker + Nginx + 自签 HTTPS，2 vCPU / 1.9 GB）

| 项 | 结果 |
| --- | --- |
| 100,000 行导入 | 提交 14:08:58.285 → 末批 14:09:04.534，约 **6.5–7 s**；`IMPORT` SUCCEEDED，100000 行 |
| 质量统计（5 次强制刷新） | 4836 / 5380 / 8009 / 7245 / 5577 ms（P50 5380，P95 8009） |
| 图表聚合 | 687 ms，5 个聚合点 |
| 看板加载 | 39 ms |
| CSV 导出 | 3412 ms，568,785 B |
| PNG 导出 | 5642 ms，25,610 B（PNG 魔数校验通过） |
| 任务中心 | 754 ms（含多类型任务：CHART/EXPORT/IMPORT/QUALITY_STATS） |
| 匿名会话隔离 | 会话 B 访问 A 的 dataset/quality/job/chart-view/dashboard/export（含 PNG）全部 **404** |
| 真实 IP 限流 | 突发 80 次 `POST /api/public/session`：**75 次 429、5 次 200** |
| HTTPS Cookie | `__Host-dc_anon; Secure; HttpOnly; SameSite=Strict` |
| 端口隔离 | 外网仅 80/443 可达；3306/8080/8081/6379 均不可达 |
| AI 关闭态 | 403，安全文案；任务中心记录 `AI` 任务失败但不泄露内部信息 |
| 上传扫描 | **未完成**：ClamAV 因 2 GB 内存不足被 OOM 杀死（`OOMKilled=true`，restart=10，`Out of memory: Killed process clamd`）。EICAR 上传得到 503「文件安全检测暂不可用」，即 **fail-closed 已验证**；真实病毒检出 **未验证**。为稳定主机已临时 `docker stop` 并 `--restart=no` ClamAV，`scanner-1` 因此 unhealthy。恢复方式见 10.1。 |
| TTL 清理 | 配置与启动校验通过（TTL 24h、清理=on）；本次窗口未到清理周期，**容器内未观察到实际清理动作**；该链路由 7.5 本机等价复验补齐（约 40 s 完成 purge 并删除数据集） |
| 前端预发渲染 | 前端镜像已用本阶段源码重建并部署（HTTP 200）；自签证书导致自动化浏览器拒绝加载，逐页渲染 **未验证**（改用本地 `http://localhost:5174` 等价验证，见下） |

### 7.4 前端渲染（本地真实接口）

浏览器逐页验证（`http://localhost:5174`，代理真实后端）：

- 工作台：正常渲染，导航完整；
- 任务中心：真实空态、统计卡与筛选渲染正常，无错误横幅；
- 质量中心：数据集选择器、质量总览、字段明细正常，未采集状态显示正确。

### 7.5 本机等价复验（无 Docker：MySQL 8.4 + JDK 17 jar + Nginx 1.27.4 + 自签 TLS）

受限于本机无 Docker / WSL，采用等价拓扑复验：单机 MySQL 8.4、JDK 17 运行打包产物（`-Xmx512m`，`127.0.0.1:18080`，管理口 `127.0.0.1:18081`）、便携版 Nginx 1.27.4（`127.0.0.1:8443`，自签 `CN=localhost, SAN=dns:localhost,ip:127.0.0.1`）。Nginx 配置由 `deploy/nginx/default.https.conf.template` 本地化而来（仅调整监听端口、Host 白名单、上游地址与静态目录），限流区、脱敏 location、代理头与安全响应头保持一致。`public` 模式：Cookie=`__Host-dc_anon`、TTL=1h、清理=on（间隔 1 min、宽限 0 min）、扫描 provider=none。

| 项 | 结果 |
| --- | --- |
| HTTPS Cookie | `POST /api/public/session` → 200，`Set-Cookie: __Host-dc_anon=...; Path=/; Max-Age=3600; Secure; HttpOnly; SameSite=Strict` |
| 真实 IP 限流（api_zone 30r/s，burst 60） | 300 个并发 `GET /api/public/config`：**68×200 + 232×429** |
| 上传限流（upload_zone 10r/m，burst 3） | 连续 5 次 `POST /api/dataset/upload`：第 5 次 **429**（前 4 次穿过限流层，由后端处理） |
| 跨会话隔离 | 会话 A 上传得到 `dataset id=14`；会话 B 访问 → **404**（`{"code":404,"message":"资源不存在"}`），会话 A → 200 |
| TTL 清理 | 注入会话 A `expires_at = NOW() - 5 min` 后：约 40 s 内状态 `active → purged` 并删除墓碑行，`dataset id=14` 记录清除（count=0）；会话 A 再访问 → **401**，会话 B 仍 200 |
| 端口隔离 | `netstat`：18080/18081/8443 均仅监听 `127.0.0.1`；LAN IP（192.168.x / 172.30.x）直连三端口全部连接拒绝 |
| 敏感路径脱敏 | `/actuator/health`、`/api/admin/users`、`/api/health` 经 Nginx 均 **404** |
| 安全响应头 | HSTS、X-Content-Type-Options、X-Frame-Options、Referrer-Policy、Permissions-Policy、CSP 均存在（见 `curl -kI`） |

本机等价复验**未覆盖**：公网可信证书链（自签仅本地）、ClamAV 真实病毒检出、AI 供应商 429/503/熔断。

## 8. 测试与安全检查

| 检查 | 状态 |
| --- | --- |
| `mvn clean test`（JDK 17） | 已实际验证：152 通过 |
| `npm run build` | 已实际验证：通过 |
| 迁移演练（6 场景） | 已实际验证 |
| 敏感信息扫描 `scripts/scan-secrets.mjs` | 已实际验证：exit=0，未发现疑似明文凭据（跳过仅本地联调用的 `backend/.env`，该文件已被 `.gitignore` 排除） |
| CI 门禁 `scripts/ci-gate.ps1` | 已实际验证：JDK 26 环境被拒绝（exit=1 并提示原因）；JDK 17 环境执行 `mvn clean test` + `npm run build` + 敏感信息扫描 |
| 双会话隔离（本地 + 预发 + 本机等价复验） | 已实际验证：统一 404 |
| 预发真实 IP 限流 | 已实际验证：429（容器预发 + 本机 Nginx 等价复验） |
| 预发端口隔离 / HTTPS Cookie | 已实际验证（容器预发 + 本机 Nginx 等价复验） |
| 任务失败摘要脱敏 | 静态检查 + 实际失败任务响应核对（无 SQL/路径/堆栈/密钥） |
| 私有接口在公开模式不可达 | 已实际验证：`/api/ai/config`、`/api/auth/login`、`/api/admin/status`、`/api/ai/governance` 均 404 |
| 公式注入防护 | 既有单测覆盖（`ExportCsvGuardTest`），本阶段未改动防护逻辑 |
| 真实供应商 429/503/熔断 | **未验证**（未配置供应商） |
| 上传病毒实际检出 | **未验证**（ClamAV 内存不足） |
| TTL 实际清理动作 | 已实际验证：本机等价复验注入过期时间后约 40 s 完成 purge 并删除数据集（见 7.5） |
| 预发页面逐页渲染 | **未验证**（自签证书限制；本地等价验证通过） |

## 9. 已接入 / 未接入

已接入：

- 真实重复率与类型不一致统计（含抽样、草稿规则、状态与缓存）；
- 任务中心覆盖统计/图表/导出/AI；
- 图表任务、图表视图、看板自由编排（重命名/拖拽/栅格/PNG）；
- CSV 与 PNG 受控导出、导出任务追踪；
- 预发 JDK 17 镜像、V11 迁移、HTTPS Cookie、限流、端口隔离、跨会话 404；
- TTL 到期清理（本机等价复验已实际观察到 purge 与数据集删除，见 7.5）。

未接入 / 未验证：

- 真实 AI 供应商调用与 429/503/熔断恢复；
- 预发 ClamAV 病毒检出（主机内存不足）；
- 公网可信证书链下的 HTTPS 验证（本机为自签）；
- 私有大屏共享/协作等非本阶段范围能力。

## 10. 风险与后续优化

1. **预发内存不足（P0）**：1.9 GB 主机无法同时承载 MySQL + 后端 + ClamAV（clamd RSS ≈ 648 MB）。建议升配到 4 GB 或把扫描拆到独立主机；当前扫描 fail-closed，会影响上传可用性。恢复扫描服务：升配后执行 `docker update --restart=unless-stopped dataclean-clamav-1 && docker start dataclean-clamav-1`，再确认 `scanner-1` 恢复 healthy。
2. 质量统计为同步请求（当前 100k×7 列约 5–8 s）。列数增多时建议改为 `QUALITY_STATS` Worker 异步执行，前端轮询任务状态。
3. 重复率口径为“落在重复组内的全部行（含首次出现）”，需在 UI 明示，避免与“多余行数”混淆。
4. 前端预发 HTTPS 自动化验证缺失，建议为预发域名配置受信证书后补做。
5. 质量报告缓存 10 分钟，数据版本新增后需按 `versionId` 重新统计，避免跨版本误读。

## 11. 回滚说明

- 应用：重新部署上一版镜像即可；本阶段接口均为增量。
- 数据：`R11__data_quality_report.sql` 仅删除统计缓存表，不影响数据集、版本、任务、图表、看板。
- 配置：`PUBLIC_AI_ENABLED=false` 始终为默认；回滚无需改配置。

## 12. 三轮自查

1. **数据正确性与版本一致性**：统计按 `versionId` 归属；历史版本使用该版本物理表；字段别名与物理列双重校验；日期范围校验修正；报告 `stale` 标记与 409 提示；导出/图表绑定数据版本。
2. **跨会话/私有隔离**：dataset/quality/job/chart-view/dashboard/export 全部经 `OwnershipService`，本地与预发双会话实测统一 404；导出与 PNG 同样覆盖。
3. **大数据与泄露风险**：统计/图表/TopN/抽样均下推 SQL，未全量入内存；`-Xmx512m` 完成 10 万行全链路；失败摘要与 AI 错误均为安全文案；公开模式私有接口 404；未触碰生产库与生产流量。

## 13. 关键文件

后端：`backend/pom.xml`、`backend/Dockerfile`、`JobService`、`JobWorker`、`StreamingImportService`、`AnalysisService`、`SavedChartService`、`ExportService`、`ChartPngRenderer`、`DataQualityService`、`QualityController`、`AiController`、`ExportController`、`DatasetMapper`、`DataQualityReport`（实体/Mapper）、`V11/R11`。

前端：`api/quality.js`、`views/Quality.vue`、`views/Jobs.vue`、`views/DashboardDetail.vue`、`views/Dashboard.vue`、`views/Help.vue`、`api/analysis.js`。

脚本：`scripts/perf/generate-synthetic-csv.mjs`、`scripts/scan-secrets.mjs`、`scripts/ci-gate.ps1`。
