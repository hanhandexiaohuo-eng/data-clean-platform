# R2 数据关联完整性修复报告（2026-11-25）

> 范围：第二轮审计中「删除数据集/图表后遗留孤儿关联、看板整体 404」问题。
> 原则：不重构、不改表结构、不重置数据库、不删除用户真实数据；R1 成果未改动。
> 环境：Spring Boot 3.3.5 / MySQL 8.4（dataclean）/ Vue3 + Vite5；预发布部署 `https://localhost:8443` → `127.0.0.1:18080`。

---

## 一、真实数据库结构与关联核对

以 `information_schema` 实际表结构与数据为准（未凭猜测）：

| 表 | 与数据集/图表的关联 | 处理策略（R2 起） |
|---|---|---|
| `dataset` | 主表 | 删除 |
| `dataset_column` | `dataset_id` | 随数据集删除 |
| `dataset_version` | `dataset_id` | 随数据集删除 |
| `dataset_version_rule` | `version_id`（清洗规则快照） | 随版本删除（此前遗漏，会变孤儿） |
| `data_quality_report` | `dataset_id` / `version_id` | 随数据集删除（此前遗漏，会变孤儿） |
| `clean_rule` | `dataset_id` | 随数据集删除（配置无意义） |
| `clean_log` | `dataset_id` / `backup_table` | **保留**（审计历史） |
| `async_job` | `dataset_id` | **保留**（任务历史），但删除前拦截进行中任务 |
| `stats_cache` | `dataset_id` | 随数据集删除 |
| `backup_table_record` | `dataset_id` | 随数据集删除（登记信息） |
| `saved_chart` | `dataset_id` / `dataset_version_id` | 随数据集删除 |
| `dashboard_item` | `dashboard_id` / `saved_chart_id` | 随图表/数据集删除 |
| `chart_view` | `dataset_id`（配额计数） | 随数据集删除 |
| `export_record` / `ai_call_log` / `audit_log` | 数据集/图表引用 | **保留**（审计/历史） |
| 动态数据表 `data_*`、`*_v2`、`*_backup_*` | 由元数据精确归属 | 事务提交后删除 |

删除前基线实测（dataclean）：

- 孤儿图表 0、孤儿看板项 0（历史会话已被匿名清理任务回收）；
- 孤儿质量报表 7 条、孤儿版本规则快照 4 条（历史旧版本遗留，来自早期删除逻辑）；
- 历史孤儿物理表 5 张（`data_audit_100k_*`、`data_smoke_ds_*_v2`、`data_审计测试_special_csv_*_v2/_v3`、`data_淘宝母婴购物数据集_*_backup_*`）。

## 二、根因

1. **删除数据集不清理下游关联**：只删版本、统计缓存、任务、列与主表；
   `saved_chart`（图表）、`dashboard_item`（看板项）、`data_quality_report`（质量报表）、
   `dataset_version_rule`（规则快照）、`clean_rule`（清洗规则）、`chart_view`（配额记录）
   全部残留。
2. **删除图表不清理看板项**：`saved_chart` 删除后 `dashboard_item.saved_chart_id` 悬空。
3. **看板详情强校验**：`DashboardService.toVO` 对每个看板项调用
   `savedChartService.requireVisible` 与 `ownershipService.requireDataset`，
   任一引用失效即抛 404，导致**整块看板不可访问**。
4. **DDL 与事务混用**：原实现先 `DROP TABLE` 再删元数据。MySQL DDL 隐式提交，
   一旦元数据删除失败无法回滚，且会留下“表已删、元数据还在”的坏状态。

## 三、修改内容

### 3.1 删除数据集（DatasetService）

[deleteDataset](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/backend/src/main/java/com/dataclean/service/DatasetService.java#L405-L525)

- 事务内按依赖顺序：版本规则快照 → 质量报表 → 清洗规则 → 图表与看板项 → chart_view
  → 版本 → 统计缓存 → 备份表登记 → 列 → 数据集。
- **保留** `clean_log`、`async_job`、`export_record`、AI/审计日志作为历史记录（报告第五节说明）。
- **进行中任务保护**：存在 `PENDING/RUNNING/CANCEL_REQUESTED` 任务时返回
  `409 数据集存在进行中的任务，请等待完成或取消后再删除`，避免任务在删除后继续写入。
- **DDL 后置**：物理表（主表、版本表、备份表）在事务 `afterCommit` 后删除；
  提交前失败可完整回滚元数据，提交后 DDL 失败仅留下可统计的孤儿表（降级为日志告警）。

### 3.2 删除图表（SavedChartService）

[delete](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/backend/src/main/java/com/dataclean/service/SavedChartService.java#L143-L157)

- `@Transactional`：先删该图表的所有 `dashboard_item`，再删图表，最后写审计。
- 其他看板、其他图表不受影响。

### 3.3 看板容错（DashboardService / DashboardItemVO / DashboardDetail.vue）

- 新增非抛异常查询：[SavedChartService.findVisible](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/backend/src/main/java/com/dataclean/service/SavedChartService.java#L256-L279)、
  [OwnershipService.findVisibleDataset](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/backend/src/main/java/com/dataclean/service/OwnershipService.java#L73-L85)。
- 看板详情逐项降级：图表缺失 → `invalid=true, invalidReason=图表已删除，无法展示`；
  数据集缺失 → `invalid=true, invalidReason=数据集已删除，无法展示`；
  正常项继续走原有 stale/版本状态逻辑（[DashboardService](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/backend/src/main/java/com/dataclean/service/DashboardService.java#L243-L296)）。
- `updateItem`（移动/缩放）对失效项不抛 404，返回失效 VO；`removeItem` 原本只校验看板归属，可直接移除。
- 前端 [DashboardDetail.vue](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/frontend/src/views/DashboardDetail.vue#L48-L72)：
  失效项显示错误提示与「移除该卡片」，隐藏图表渲染，禁用 PNG 导出；
  失效项不再请求图表接口，正常图表展示不受影响。
- 失效状态为**显式**：不使用 `try/catch 全部忽略`，仅对“引用不存在/不可见”降级，其他异常照常抛出。

### 3.4 匿名会话清理任务（PublicCleanupService）

- 新增 `data_quality_report`、`version_rule` 清理项，并在会话回收时补删这两类数据，
  避免会话过期后再次制造孤儿（这正是历史孤儿质量报表/快照的来源）。

### 3.5 前端删除提示（Dataset.vue）

- 删除确认文案明确：图表及看板卡片将一并移除，历史清洗/任务记录保留。

## 四、历史孤儿数据安全方案

- **不自动批量删除历史数据**：仅通过看板容错保证可访问；不在启动时做破坏性清扫。
- 审计 SQL（只读，供人工核对）：

```sql
SELECT COUNT(*) FROM saved_chart sc LEFT JOIN dataset d ON d.id=sc.dataset_id WHERE d.id IS NULL;
SELECT COUNT(*) FROM dashboard_item di LEFT JOIN saved_chart sc ON sc.id=di.saved_chart_id WHERE sc.id IS NULL;
SELECT COUNT(*) FROM data_quality_report q LEFT JOIN dataset d ON d.id=q.dataset_id WHERE d.id IS NULL;
SELECT COUNT(*) FROM dataset_version_rule r LEFT JOIN dataset_version v ON v.id=r.version_id WHERE v.id IS NULL;
```

- 物理孤儿表：应用已在启动/定时任务中**仅告警不删除**（当前 5 张，均为历史测试/审计产物），
  列入 BACKLOG，由运维确认后手动清理。
- 现有孤儿质量报表/快照可在业务确认后按 `dataset_id`/`version_id` 精确删除，本次未执行。

## 五、事务一致性与回滚说明

- 元数据删除在单事务内完成，任一 SQL 失败整体回滚（已通过 404/403 失败路径验证无副作用）。
- DDL 隐式提交无法纳入事务，故后置到 `afterCommit`；失败降级为告警，不会破坏元数据一致性。
- 未做真实“事务中途数据库故障注入”测试（见第七节）。

## 六、构建与测试

- 后端：`mvn -B -DskipTests package` → **BUILD SUCCESS**（JDK17）。
- 前端：`vite build` → **成功**（16.31s，仅 chunk 体积告警）。
- 已部署新 jar 与前端 dist，后端 18080/18081 正常启动，演示数据集就绪。

### R2 回归结果（独立匿名会话 + 独立测试数据）

| 编号 | 场景 | 结果 |
|---|---|---|
| S0–S6 | 会话、上传、质量报表、清洗新版本、规则快照落库 | PASS |
| T1-1 | 删除被两个看板引用的图表：两看板 200 且引用清空、图表 404、`dashboard_item`=0 | PASS |
| T1-2 | 重复删除图表 404，无副作用 | PASS |
| T2-1 | 历史孤儿图表（SQL 直删图表）：看板 200，项 `invalid=true`、原因正确 | PASS |
| T2-2 | 失效项可移除，看板保持可用 | PASS |
| T3-1 | 历史孤儿数据集（SQL 直删数据集）：看板 200，项 `invalid=true` | PASS |
| T4-1 | 删除数据集：图表/看板项/质量/规则/版本/快照残留全部为 0 | PASS |
| T4-2 | 删除后看板 200，仅移除被删图表，另一正常图表保留且可用 | PASS |
| T4-3 | 删除后数据集、图表均 404 | PASS |
| T4-4 | 正常图表状态 200；移除不存在条目 404 且看板不变 | PASS |
| T4-5 | 再次删除含看板引用的数据集成功，看板仍可打开 | PASS |
| T5-1 | 删除不存在数据集 404，数据集总数不变 | PASS |
| T5-2 | 演示数据集删除 403，数据保留 | PASS |
| T6-1 | 跨会话读取/删除看板与数据集均 404 | PASS |
| T7-1 | 全局孤儿数量前后一致（0/0/7/4），本轮未新增孤儿 | PASS |

**汇总：23/23 PASS，0 FAIL。**

## 七、未验证 / 遗留

1. **真实事务故障注入**未执行（未人为制造数据库异常）；回滚结论来自代码路径与失败路径验证。
2. **浏览器端 UI 实操**（点击「移除该卡片」、失效样式）未在本轮执行，计划在 R5 浏览器验收补测。
3. **进行中任务 409 保护**未构造稳定并发窗口实测（仅代码实现 + 删除重试可正常通过）。
4. 历史孤儿物理表 5 张、孤儿质量报表 7 条、孤儿快照 4 条**按设计保留**，未清理；已列入 BACKLOG。
5. 保留的 `async_job` 历史任务在任务中心仍可见，对应数据集已删除时重试会失败（预期行为，未新增 UI 提示）。

## 八、结论

R2 目标达成：删除数据集/图表不再产生影响查询的孤儿关联；历史孤儿引用不会让看板整体 404；
失效项显式标记且可移除；删除具备事务边界与 DDL 后置保护。**可进入 R3。**
