# R1 修复报告：P1 核心缺陷（SEC2-01 / SEC2-02）

- 报告日期：2026-11-25
- 对应审计：`docs/SECOND_AUDIT_REPORT_2026-11-25.md`（本轮未修改、未覆盖）
- 修复范围：仅 SEC2-01（HTTP 409 业务异常被降级为 200）与 SEC2-02（过期图表携带正确 `X-Confirm-Version` 仍必然 409）
- 运行环境：本地 `https://localhost:8443`（Nginx 1.27.4 → 后端 18080/18081，profile=`pre`，`APP_MODE=public`）
- 版本控制：该项目目录**不是 Git 仓库**（无 `.git`），因此无分支/提交记录可比对；本轮未重置、未清理任何用户数据，修改前已逐个文件核对现有内容后再编辑。

---

## 1. 缺陷原始根因

### 1.1 SEC2-01：409 业务异常被降级为 HTTP 200

**根因（后端）**：`GlobalExceptionHandler.handleBusinessException` 使用 `switch (e.getCode())` 映射 HTTP 状态，但分支中缺少 `409`，命中 `default -> HttpStatus.OK`，于是 `BusinessException(409, ...)` 实际返回 HTTP 200 + `{"code":409,...}` 的 JSON。

由于导出接口 `POST /api/export/chart/{id}`、`POST /api/export/chart/{id}/png` 在图表 stale 时由 `ExportService` 调用 `SavedChartService.generateForExport(id)` 抛出 `BusinessException(409, ...)`，前端以 `responseType: 'blob'` 请求，HTTP 200 会被 axios 视为成功，于是：

1. 前端把 JSON 错误体当作文件内容，`downloadBlob` 用 `text/csv`（CSV）/`image/png`（PNG）重新包装成 Blob；
2. 浏览器落盘一个内容为 `{"code":409,...}` 的伪 CSV / 伪 PNG；
3. 页面提示“CSV 已下载 / PNG 导出完成”。

**根因（前端）**：`src/utils/request.js` 的响应拦截器只按 HTTP 状态与业务 `code` 判断，而 Blob 分支既没有检查 `Content-Type`，也没有解析错误 JSON，属于第二条缺陷链。

### 1.2 SEC2-02：确认正确版本仍必然 409

**根因**：`ChartViewController.generate` 已解析 `X-Confirm-Version` 并做了前置校验，但通过校验后调用的是 `savedChartService.generate(id)`——**未把确认版本传入 Service**。Service 内部 `stale` 分支只有“抛 409”一条路径，没有“确认 → 重绑当前版本 → 重新生成”的实现，因此：

- 用户带 `X-Confirm-Version = currentVersionId` 时，Controller 放行；
- Service 再次判定 stale（`saved_chart.dataset_version_id != dataset.current_version_id`）后无条件抛 409；
- 图表永远停留在 stale 状态，看板也无法恢复。

即“前端传了确认参数但后端没有消费它”，是逻辑缺失而非前端参数错误。

---

## 2. 修改文件与具体位置

| 文件 | 位置 | 修改内容 |
| --- | --- | --- |
| `backend/src/main/java/com/dataclean/exception/GlobalExceptionHandler.java` | L21-49 | 补齐 `case 409 -> HttpStatus.CONFLICT`，并显式保留 400/401/403/404/429/500/503；`default` 由 `OK` 改为 `INTERNAL_SERVER_ERROR` |
| `backend/src/main/java/com/dataclean/service/SavedChartService.java` | L147-236 | 新增 `generate(id, confirmedVersionId)`；`generateForExport(id)` 走“无确认”路径；stale 分支实现确认重绑 + 条件更新 + 版本复核 |
| `backend/src/main/java/com/dataclean/controller/ChartViewController.java` | L64-94 | `POST /{id}/generate` 保留前置校验，返回 409 时附带 `ChartViewStatusVO`；通过后调用 `generate(id, confirmVersion)` |
| `frontend/src/utils/request.js` | L11-27、L44-58、L94-103 | 新增 Blob JSON 识别工具；成功/失败两条链路都识别 `application/json` Blob，解析 `message` 并 reject |
| `frontend/src/views/DashboardDetail.vue` | L170-189、L304-318 | 刷新遇到二次 409 时同步最新状态；PNG 导出失败展示后端 message，不再提示成功 |
| `frontend/src/views/Analysis.vue` | 未改动（L622-669 已具备 409 确认重试与错误提示） | 复核确认与后端新逻辑对齐，无需修改 |

未触碰：数据库结构、数据上传、清洗规则与执行、数据质量、版本恢复、看板数据模型、其他模块。

---

## 3. 修改前后关键逻辑

### 3.1 后端状态码映射（SEC2-01）

修改前：

```java
HttpStatus status = switch (e.getCode()) {
    case 400 -> HttpStatus.BAD_REQUEST;
    case 401 -> HttpStatus.UNAUTHORIZED;
    case 403 -> HttpStatus.FORBIDDEN;
    case 404 -> HttpStatus.NOT_FOUND;
    case 429 -> HttpStatus.TOO_MANY_REQUESTS;
    case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
    default -> HttpStatus.OK;   // 409 落入此处，被降级为 200
};
```

修改后（新增 409、500，`default` 兜底为 500）：

```java
case 404 -> HttpStatus.NOT_FOUND;
case 409 -> HttpStatus.CONFLICT;
case 429 -> HttpStatus.TOO_MANY_REQUESTS;
case 500 -> HttpStatus.INTERNAL_SERVER_ERROR;
case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
default -> HttpStatus.INTERNAL_SERVER_ERROR;
```

统一响应体仍为 `Result.fail(code, message)`，格式未变。另检索全仓 `ResponseEntity.ok(Result.fail...)` / `HttpStatus.OK.body(Result.fail...)` 等降级写法，**无其他命中**。

### 3.2 版本确认刷新（SEC2-02）

Controller（前置校验，快速失败并返回状态数据）：

```java
if (hasMissingFields) return conflict("字段已变更，需要重新配置", status);
if (Boolean.TRUE.equals(status.getStale())) {
    if (confirmVersion == null) return conflict("数据已更新，需要确认是否使用最新版本", status);
    if (!Objects.equals(confirmVersion, status.getCurrentVersionId()))
        return conflict("数据版本已再次变化，请重新确认", status);
}
return ResponseEntity.ok(Result.success(savedChartService.generate(id, confirmVersion)));
```

Service（真正消费确认版本、条件重绑、双重版本复核）：

```java
Long currentVersionId = dataset.getCurrentVersionId();
boolean stale = !Objects.equals(chart.getDatasetVersionId(), currentVersionId);
if (stale) {
    if (confirmedVersionId == null) throw new BusinessException(409, "数据已更新，需要确认是否使用最新版本");
    if (!Objects.equals(confirmedVersionId, currentVersionId)) throw new BusinessException(409, "数据版本已再次变化，请重新确认");

    LambdaUpdateWrapper<SavedChart> bind = new LambdaUpdateWrapper<>();
    bind.eq(SavedChart::getId, id)
        .set(SavedChart::getDatasetVersionId, currentVersionId)
        .set(SavedChart::getUpdatedAt, LocalDateTime.now());
    // 仅当仍绑定旧版本时才更新，避免并发刷新互相覆盖
    if (previousVersionId == null) bind.isNull(SavedChart::getDatasetVersionId);
    else bind.eq(SavedChart::getDatasetVersionId, previousVersionId);

    int updated = savedChartMapper.update(null, bind);
    if (updated == 0) { /* 读回判断是否已被并发刷新到目标版本，否则 409 */ }
    // 重绑后复核 dataset.current_version_id，若已再次变化则回滚绑定并 409
    ...
}
ChartDataVO data = analysisService.generateChartData(request, recordJob);
// 生成后再次复核版本，防止结果绑定到已过期版本
if (!Objects.equals(afterGenerate.getCurrentVersionId(), currentVersionId))
    throw new BusinessException(409, "刷新过程中数据版本发生变化，请重新确认");
```

对应场景：

- **A 一致**：`stale=false`，直接生成，不要求确认。
- **B 过期未确认**：409，不写库、不改 `dataset_version_id`。
- **C 过期且确认正确**：条件更新重绑到当前版本 → 生成 → 成功后 `stale` 自然消除（`saved == current`）。
- **D 确认版本 ≠ 当前**：409「数据版本已再次变化，请重新确认」，不写库。
- **E 刷新期间版本再变化**：条件更新（`id + 旧版本`）防覆盖；重绑后与生成后各复核一次 `current_version_id`，不一致则回滚本次绑定 / 丢弃结果并 409。

`generateForExport` 保持“无确认”语义：stale 时导出返回 409，要求用户先确认刷新，符合既有产品约束。

### 3.3 前端 Blob 错误识别（SEC2-01）

```js
const isJsonBlob = (blob) => {
  const type = (blob.type || '').toLowerCase()
  return type.includes('application/json') || type.includes('+json')
}
```

- 成功拦截器：`responseType==='blob'` 且为 JSON Blob → `readBlobJson` 解析 `message`，构造 `Error`（带 `response/code/isBlobError`）并 reject，**不进下载流程**。
- 失败拦截器：状态码非 2xx 且为 JSON Blob → 覆盖 `error.message`、`error.code`、`error.response.data` 后 reject，交由页面展示。
- 判定依据是 **响应头 Content-Type + 内容可解析性**，不只看 HTTP 状态码，兼容 axios 把错误体包装成 Blob 的行为。
- 正常 `text/csv`、`image/png` 响应不受影响，仍返回原始 response 供下载。

---

## 4. 构建结果

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| 后端 | `mvn -B -DskipTests package`（Maven 3.9.9 + JDK 17.0.21） | **BUILD SUCCESS**，编译 183 个源文件，耗时 1 分 21 秒，产出 `data-clean-platform-1.0.0.jar` |
| 前端 | `node node_modules/vite/bin/vite.js build`（Vite 5） | **成功**，`✓ built in 22.15s`，产出 `dist/`（含 `request-4-juh2ZM.js` 等新产物） |

构建环境说明（不影响结论）：

1. 后端源码位于含中文的桌面路径，Maven 在该路径下 `maven-resources-plugin` 无法创建 `target/classes`（路径编码问题），且 `mvnw.cmd` 未设置 `maven.multiModuleProjectDirectory`。解决办法：将 `backend/` 原样复制到 ASCII 路径 `Documents/Trae-CN/temp_20261026_01/build-r1/backend`，使用本机已缓存的 Maven 3.9.9 发行版 + JDK 17 构建，再把 jar 回填到运行目录。**源码副本与工程源码一致，未做任何构建期改写。**
2. 本机 `npm` 安装损坏（`npm-prefix.js` 缺失），因此直接用 `node node_modules/vite/bin/vite.js build`，等价于 `npm run build`。

产物部署：

- 新 jar → `local-run/backend/app.jar`（旧 jar 备份为 `app.jar.bak-r1`）；
- 新 `dist/` → `local-run/frontend/dist/`（Nginx root，L143/L156）；
- 后端以原参数重启：`java -Xmx512m -jar app.jar --server.address=127.0.0.1 --management.server.address=127.0.0.1`，cwd=`pre-verify`，环境变量取自 `pre-verify/.env`（profile=`pre`，端口 18080/18081）；
- 健康检查：`/actuator/health`（18081）= 200；`https://localhost:8443/` = 200。

---

## 5. API 回归结果（独立匿名会话 + 独立数据集）

测试脚本：`r1-test/r1-api-test.mjs`，原始结果：`r1-test/result.json`、`r1-test/run.log`。
本轮运行上下文：`datasetId=30`、`chartId=14`、`dashId=8`、版本 `34 → 35 → 36`。全部数据均属于新建测试匿名会话，未读写任何既有用户数据集。

**总计：23 项，通过 23，失败 0，跳过 0。**

| 编号 | 用例 | 结果 | 证据 |
| --- | --- | --- | --- |
| S0 | 建立匿名会话 | PASS | `__Host-dc_anon` 正常下发 |
| S1 | 上传独立测试数据集 | PASS | `datasetId=30` |
| S2 | 读取初始版本 | PASS | `v1=34` |
| S3 | 保存图表并绑定 v1 | PASS | `saved=34, stale=false` |
| A | 正常 CSV 导出 | PASS | 200，`text/csv;charset=UTF-8`，内容 `x_val,y_val…Beijing,400.0…` |
| B | 正常 PNG 导出 | PASS | 200，`image/png`，长度 13754，PNG 魔数 `89504E47` 正确 |
| 6 | 图表未过期时刷新 | PASS | 200，`dataVersionId=34` |
| S4 | 清洗产生新版本 | PASS | `v1=34 → v2=35` |
| C1 | 过期图表 CSV 导出 | PASS | 409，`application/json`，`数据已更新，需要确认是否使用最新版本` |
| C2 | 过期图表 PNG 导出 | PASS | 409，`application/json`，同上（未产生伪文件） |
| 7 | 过期 + 未确认刷新 | PASS | 409；`saved=34` 未变，`cur=35` |
| 8 | 过期 + 确认版本错误 | PASS | 409；`saved=34` 未变 |
| 9 | 过期 + 确认版本正确 | PASS | 200，`dataVersionId=35`，随后 `saved=35, stale=false` |
| 10 | 刷新后 CSV 导出恢复 | PASS | 200，`text/csv` |
| 11 | 创建看板并添加图表 | PASS | `dashId=8, itemId=9, itemStale=false` |
| S5 | 产生第二个新版本 | PASS | `v2=35 → v3=36` |
| 12 | 数据集更新后看板条目 stale | PASS | `itemStale=true, saved=35, cur=36` |
| 13 | 再次更新后旧确认版本被拒绝 | PASS | 409，`数据版本已再次变化，请重新确认`，`saved=35` 未变 |
| 14 | 确认最新版本刷新 + 看板恢复 | PASS | 200，`dataVersionId=36`，`itemStale=false, saved=36` |
| D1 | 400 语义 | PASS | 400，`请确认后再执行该操作` |
| D2 | 404 语义 | PASS | 404，`资源不存在` |
| D3 | 401 语义 | PASS | 无会话访问 `/api/dataset` → 401 |
| D4 | 403 语义 | PASS | 演示数据集清洗（预览）→ 403，`官方演示数据为只读…` |

> 说明：迭代过程中曾出现两次“测试脚本自身断言错误”（CSV 断言误匹配列名、清洗执行漏带 `X-Confirm`），均已修正脚本后重跑，**不是产品缺陷**；最终一轮为准。

---

## 6. 图表版本关联验证

| 阶段 | `saved_chart.dataset_version_id` | `dataset.current_version_id` | `stale` |
| --- | --- | --- | --- |
| 保存后 | 34 | 34 | false |
| 清洗产生 v35 后 | 34 | 35 | true |
| 未确认刷新（409） | 34 | 35 | true（保持不变） |
| 错误确认刷新（409） | 34 | 35 | true（保持不变） |
| 正确确认刷新 | 35 | 35 | false |
| 再次清洗产生 v36 | 35 | 36 | true |
| 旧确认（v35）刷新 | 35 | 36 | true（409，关联未变） |
| 新确认（v36）刷新 | 36 | 36 | false |

结论：仅在“确认版本 == 当前版本”时才发生重绑；409 失败路径均不修改版本关联；重绑后 `stale` 正确清除。

---

## 7. 看板联动验证

- 看板条目通过 `GET /api/dashboard/{id}` 返回 `stale/savedVersionId/currentVersionId`，与图表状态一致；
- 数据集更新后条目变 `stale=true`（saved=35, cur=36）；
- 以 `X-Confirm-Version=36` 刷新图表后，看板条目 `stale=false, saved=36`，`DashboardDetail.vue` 的 `refreshItem` 成功后重绘并自动清除过期提示；
- 刷新失败（二次 409）时，`refreshItem` 会把最新 `status` 写回条目状态，界面仍显示“数据已更新，需要刷新”，不会伪造成功。

---

## 8. 未验证项（如实标注）

1. **场景 E 的真实并发窗口**：代码已用条件更新 + 两次版本复核实现，但回归测试为串行调用，**未构造“生成过程中数据集恰好产生新版本”的并发竞争**，该分支未在运行时被触发（仅静态审查 + 逻辑推演）。建议 R2 用并发压测或故障注入覆盖。
2. **HTTP 429（限流）与 500（服务端异常）**：本轮未构造可复现请求，未做运行时验证；仅确认映射代码正确（429→TOO_MANY_REQUESTS，500/default→INTERNAL_SERVER_ERROR）。
3. **前端浏览器端到端下载行为**：已确认 `request.js` 改动被正确编译进产物（`dist/assets/request-4-juh2ZM.js` 含 JSON Blob 判定与 `isBlobError`），但**未在浏览器中实际点击过期图表导出按钮**验证“无文件落盘 + 显示后端 message”。三处页面（Analysis / DashboardDetail / Dataset）的 catch 均已展示 `e.message`，属代码级验证。
4. **多客户端并发刷新同一图表**：条件更新分支在单客户端顺序测试中未被触发（`updated==0` 路径未覆盖）。
5. **Maven 官方 wrapper**：`mvnw.cmd` 在本机不设置 `maven.multiModuleProjectDirectory`，直接调用失败；本轮改用本机缓存的 Maven 3.9.9 构建。该脚本问题属构建工具链问题，**未修改**（超出本轮范围），记录待办。

---

## 9. 遗留风险

- **Blob 判定依赖 Content-Type**：若未来某错误响应以 `application/octet-stream` 返回 JSON，前端不会识别为错误（当前后端异常统一 JSON，风险低）。可考虑增加“文本内容以 `{` 开头且可解析”的兜底判定，但本轮未加，避免误伤正常文件。
- **导出 CSV 列名**为 `x_val/y_val`（后端既有实现），与页面展示列名不同，属既有行为，未在本轮修改。
- **`ChartViewController` 与 `SavedChartService` 存在重复 stale 校验**：Controller 负责快速失败与状态回传，Service 负责最终一致性，属有意冗余；后续重构可合并，但当前有利于并发安全。
- **测试数据残留**：本轮在多个测试匿名会话下创建了测试数据集（id 27-30）、图表（10-14）、看板（6-8）。均为匿名会话私有数据，受公开模式清理策略（`PUBLIC_CLEANUP_ENABLED=true`，1 分钟间隔）自动回收；未触碰任何既有用户数据。
- **新增演示探针图表**：为验证 403，曾在演示数据集 8 上创建自身图表（id=13，只读数据集本身未被修改）。

---

## 10. 结论与 R2 建议

- **SEC2-01 已从根因修复**：409 不再降级为 200；导出接口在 stale 时返回 HTTP 409 + JSON 业务提示；前端按 Content-Type 识别 JSON 错误并展示 message，不生成伪文件、不提示成功。
- **SEC2-02 已从根因修复**：Service 真正消费 `X-Confirm-Version`，按场景 A–E 处理；确认正确即重绑版本、更新数据、清除 stale，看板随之恢复；版本再次变化时旧确认被拒绝。
- 未发现新增缺陷；原有 400/401/403/404/409 语义与正常导出、创建、查询、看板功能均通过回归。
- 建议进入 R2，并在 R2 补充：并发版本竞争测试（场景 E、多客户端刷新）、429/500 状态码实测、浏览器端到端导出验证。

---

## 附：测试记录文件

- 测试脚本：`C:\Users\zengyun\Documents\Trae-CN\temp_20261026_01\r1-test\r1-api-test.mjs`
- 结构化结果：`docs/R1_TEST_RESULTS_2026-11-25.json`（原始 `r1-test/result.json`）
- 运行日志：`docs/R1_TEST_RUN_2026-11-25.log`（原始 `r1-test/run.log`）
- 后端构建日志：`C:\Users\zengyun\Documents\Trae-CN\temp_20261026_01\build-r1-backend.log`
- 前端构建日志：`C:\Users\zengyun\Documents\Trae-CN\temp_20261026_01\build-r1-frontend.log`
- 运行期日志：`C:\Users\zengyun\Documents\Trae-CN\temp_20261026_01\pre-verify\backend-r1.out.log`
