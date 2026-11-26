# R4 安全与工程化收尾报告（2026-11-25 批次）

> 范围：仅处理影响实际使用与安全性的问题（XSS、重复提交、死代码、看板 N+1、Nginx 429 响应格式）。
> 原则：不重构、不改表结构、不重置数据库、不影响既有业务语义；R1、R2、R3 成果未改动。
> 环境：`https://localhost:8443`（Nginx 1.27.4）→ `127.0.0.1:18080`（Spring Boot 3.3.5 / MySQL 8.4）。

---

## 5.1 XSS 风险

### 审计结果（v-html 全部 4 处）

| 位置 | 渲染内容 | 数据来源 | 原实现 |
|---|---|---|---|
| [AiPanel.vue#L24](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/frontend/src/components/ai/AiPanel.vue#L24) | `renderedContent` | AI 生成结果（Markdown） | `marked.parse()` 直接输出 |
| [Analysis.vue#L301](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/frontend/src/views/Analysis.vue#L301) | `renderedAiContent` | AI 生成结果 | `marked.parse()` 直接输出 |
| [Clean.vue#L187](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/frontend/src/views/Clean.vue#L187) | `renderedAiContent` | AI 生成结果 | `marked.parse()` 直接输出 |
| [Preview.vue#L45](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/frontend/src/views/Preview.vue#L45) | `renderedAiContent` | AI 生成结果 | `marked.parse()` 直接输出 |

结论：4 处均为 **AI 返回内容**。`marked`（v12）默认放行原始 HTML，模型输出中的 `<script>`、`<img onerror>`、`<a href="javascript:">` 会被 `v-html` 原样插入 DOM 并执行，属于真实可利用的存储型/反射型 XSS 路径。普通业务文本（数据表格、标题等）均使用 Vue 插值，不存在同类风险。

### 修复

- 新增 [markdown.js](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/frontend/src/utils/markdown.js)：统一 `marked.parse()` → **DOMPurify 3.1.6** 白名单净化；禁用 `style/form/input/iframe/object/embed` 标签与 `style` 属性，其余按 HTML profile 白名单保留。
- DOMPurify 以 vendored ESM 形式放置于 [dompurify.es.mjs](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/frontend/src/vendor/dompurify.es.mjs)（项目 npm 环境不可用，无法在线安装依赖；构建产物已确认包含 `markdown-*.js` chunk 56.94 kB）。
- 4 个组件统一改为 `renderMarkdown(...)`，移除各自对 `marked` 的直接依赖（`marked.parse` 现仅存在于 `markdown.js` 内部）。

### 验证（真实库 + 与生产相同的净化配置）

在部署根目录放置临时同源测试页，加载 DOMPurify 3.1.6 并对以下恶意载荷执行与生产一致的 `sanitize`：

```
输入：<b>safe</b><img src=x onerror="window.__xss=1"><script>window.__xss=2</script>
      <a href="javascript:alert(1)" onclick="window.__xss=3">link</a>
      <p style="color:red">styled</p><iframe src="https://example.com"></iframe>
```

| 指标 | 结果 |
|---|---|
| 净化输出 | `CLEAN=<b>safe</b><img src="x"><a>link</a><p>styled</p>` |
| 脚本/事件执行 | `EXECUTED=none`（`window.__xss` 从未被赋值） |
| 被移除项 | `<script>`、`<iframe>`、`onerror`、`onclick`、`javascript:` href、`style` 属性 |
| 正常内容 | `<b>`、`<a>`、`<p>`、`<img src>` 保留，Markdown 正常展示不受影响 |

测试文件已在验证后删除，未留在部署目录。

> 说明：AI 功能在预发布环境为关闭状态（`PUBLIC_AI_ENABLED=false`），因此「真实模型输出渲染」未端到端实测；本条验证针对渲染管线本身（相同库、相同配置、相同载荷）。

## 5.2 前端防重复提交

| 操作 | 修改前 | 修改后 |
|---|---|---|
| 清洗执行 | 已有 `executing` 锁 | 保留；**后端新增同数据集活动任务 409** |
| 数据集上传 | 已有 `importing` 守卫 | 保留 |
| 图表 CSV 导出 | 无锁，可重复请求 | 新增 `csvExporting`，按钮 `:loading` |
| 图表 PNG 导出（本地） | 无锁 | 新增 `pngExporting` 守卫与 `:loading` |
| 数据集 CSV 导出 | 无锁，确认框可叠加 | 新增 `exportingId`，按钮 `:loading`，请求期间拒绝重复进入 |
| 看板 PNG 导出 | 无锁 | 新增 `exportingIds`，按卡片 `:loading` |
| 图表刷新 | 已有 `chartLoading` | 保留 |
| 看板宽度调整 / 排序 | 无锁，并发可乱序 | 新增 `layoutBusy`，操作进行中禁用并拒绝重入 |

### 后端防重复（不依赖前端按钮）

在 [CleanService.java#L155-L157](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/backend/src/main/java/com/dataclean/service/CleanService.java#L155-L157) 中，创建清洗任务前检查同一数据集是否已有 `PENDING/RUNNING/CANCEL_REQUESTED` 的清洗任务：

```java
if (jobService.countActiveByDataset(dataset.getId(), JobService.TYPE_CLEAN) > 0) {
    throw new BusinessException(409, "该数据集已有进行中的清洗任务，请等待完成或取消后再试");
}
```

`countActiveByDataset` 位于 [JobService.java](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/backend/src/main/java/com/dataclean/service/JobService.java)（按 `dataset_id + job_type + 活动状态` 计数）。

### 实测（独立匿名会话，3000 行数据集）

```
dataset 200 90
rule 200 22
first execute: 200 {"data":{"executedRules":["fill missing val"],"jobId":143,...}}
immediate second execute: 409 {"code":409,"message":"该数据集已有进行中的清洗任务，请等待完成或取消后再试"}
job finished: SUCCEEDED
cleanup 200
```

失败后任务结束即可重新提交（`SUCCEEDED` 后不再命中 409），符合「失败后可重试」要求。

> 遗留：活动任务检查与任务插入之间存在极短 check-then-insert 竞态窗口，极端并发下仍可能创建两个任务。当前规模影响小，已记入 BACKLOG（可加唯一索引或分布式锁，但涉及表结构，本轮不做）。

## 5.3 清理死代码

- 确认 `rollbackClean` 前端无页面调用、无模块依赖、无测试依赖（全仓检索仅剩后端 `CleanController`/`CleanService`）。
- 已删除 [clean.js](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/frontend/src/api/clean.js) 中的 `rollbackClean` 封装。
- 后端接口保留：其实现为显式拒绝（[CleanService.java#L259](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/backend/src/main/java/com/dataclean/service/CleanService.java#L259)：`旧备份回滚已停用，请在版本列表中选择历史版本进行恢复`），属于「已停用但保留审计语义」；删除后端路由需同步调整测试与文档，超出本轮最小改动范围，记入 BACKLOG。
- 同步确认：前端 `marked` 直接调用已全部收敛到 `markdown.js`，无其他无效调用。

## 5.4 看板详情 N+1 查询

### 实测（MySQL `performance_schema.global_status.Questions` 差值，暖机后单次 `GET /api/dashboard/{id}`）

| 场景 | 修复前 SQL 数 | 修复后 SQL 数 |
|---|---|---|
| 1 项看板 | 15 | 14 |
| 5 项看板 | 35 | 14 |
| 每增加 1 项 | **+5** | **0** |
| 固定开销 | ~10 | ~14 |

结论：修复前确为真实 N+1（每项固定 5 次查询：图表、数据集、保存版本、当前版本、列元数据）。修复后项目数与查询数无关。

### 修复（最小改动，保持原有语义）

[DashboardService.java#L262-L345](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/backend/src/main/java/com/dataclean/service/DashboardService.java#L262-L345) 新增批量装配 `toItemVOs`：图表、数据集、版本、列各批量查询一次，再在内存组装；可见性过滤、stale 判定、missingFields、失效项标记逻辑完全复用原规则。

配套改动：
- [SavedChartService.java](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/backend/src/main/java/com/dataclean/service/SavedChartService.java)：新增 `isVisibleChart`、`buildStatus(chart, dataset, saved, current, columns)` 重载（原单条 `buildStatus` 保留并复用新重载）。
- [OwnershipService.java](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/backend/src/main/java/com/dataclean/service/OwnershipService.java)：`isDatasetVisible` 开放供批量过滤复用。
- `DashboardServiceIsolationTest` 已同步新增 4 个 Mock，测试代码编译通过。

回归验证：R2 的看板容错（失效项、可移除、正常项不受影响）逻辑未变，其测试将在 R5 复跑。

## 5.5 Nginx 429 响应

### 修复前

限流（`api_zone` 30r/s、`upload_zone` 10r/m、`ai_zone` 6r/m）触发时返回 Nginx 默认 **HTML 错误页**，前端按 JSON 解析会失败，提示不一致（R3 测试期间已实际遇到）。

### 修复（[nginx.conf](file:///C:/Users/zengyun/Documents/Trae-CN/temp_20261026_01/pre-verify/nginx-1.27.4/conf/nginx.conf)）

```nginx
error_page 429 = @rate_limited;

location @rate_limited {
    internal;
    default_type application/json;
    add_header Retry-After 1 always;
    return 429 '{"code":429,"message":"请求过于频繁，请稍后重试"}';
}
```

`nginx -t` 通过，已实际启动生效。

### 实测

```
#1 status=401 content-type=application/json;charset=UTF-8  (业务响应，非限流)
...
#5 status=429 content-type=application/json body={"code":429,"message":"请求过于频繁，请稍后重试"}
#6 status=429 content-type=application/json body={"code":429,"message":"请求过于频繁，请稍后重试"}
static / status=200 content-type=text/html; charset=utf-8
```

结论：API 429 已统一为 JSON，错误结构与后端 `Result` 一致（`code/message`）；静态资源不受影响。

## 构建与部署

| 项目 | 结果 |
|---|---|
| 后端 | `mvn -B -o -DskipTests package` → BUILD SUCCESS，产物 `app.jar`（已部署到 `local-run/backend/app.jar`，18080/18081 正常启动） |
| 前端 | `vite build` → built in 23.08s（含 `markdown-*.js` 56.94 kB），已同步至 Nginx 根目录 `local-run/frontend/dist` |
| Nginx | 配置校验通过并启动，8443 正常监听 |
| 数据库 | 未改表结构、未重置、未删除用户数据 |

## 构建期修正（真实编译错误）

1. `AsyncJob` 字段为 `jobType`（非 `type`），已修正查询条件。
2. `SavedChartService` 旧调用点仍传 `Dataset`，已改为传列 Map。
3. `DashboardService` 的 `addItem/updateItem` 仍调用被删除的 `toItemVO(item, chart)`，已补单项装配方法（复用批量逻辑）。
4. `DashboardServiceIsolationTest` 构造器参数不匹配，已补 4 个 Mapper Mock。

## 未验证 / 遗留

1. 浏览器真实点击导出（CSV/PNG）与重复点击行为未在本轮用浏览器实操验证，R5 补测。
2. 清洗重复提交的极短并发竞态窗口未压测（记入 BACKLOG）。
3. AI 关闭状态下，真实模型输出的端到端 XSS 未实测（渲染管线已验证）。

## 结论

R4 五项必要收尾均已落地并取得实测证据：XSS 渲染管线净化（真实载荷验证）、前后端双重防重复（409 实测）、死代码清理、看板 N+1 消除（35→14，每项增量为 0）、429 JSON 化。**可进入 R5 全模块验收。**
