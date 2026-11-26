# R3 数据处理准确性修复报告（2026-11-25）

> 范围：第二轮审计中「UTF-8 BOM 混入首列名 / 文本指标静默全 0 / 非法日期被判格式一致 / 0 行数据集 READY 且质量 100 分」四个数据准确性问题。
> 原则：不重构、不改表结构、不重置数据库、不删除用户真实数据；R1、R2 成果未改动。
> 环境：Spring Boot 3.3.5 / MySQL 8.4（dataclean）/ Vue3 + Vite5；预发布部署 `https://localhost:8443` → `127.0.0.1:18080`。

---

## 一、修复前基线与修复后实测对照

基线使用 R2 版本 jar（`app.jar.bak-r2`）在独立匿名会话中复现；修复后使用 R3 版本 jar（`app.jar`）在同口径独立匿名会话中验证。两次脚本均经 Nginx `https://localhost:8443` 访问，上传间隔 6.5s 规避限流。

| 问题 | 修复前实测（R2 jar） | 修复后实测（R3 jar） |
|---|---|---|
| UTF-8 BOM 首列名 | `["_金额_元_","城市"]` | `["金额_元_","城市","备注"]` |
| 文本指标（SUM） | `status=200`，`series=[{"name":"name","data":[0,0]}]`（静默全 0） | `status=400`，`字段 name 为文本类型，仅支持 COUNT 统计；求和/平均等指标请选择数值字段` |
| 非法日期 `2026-02-30`/`2025-02-29` | `status=CONSISTENT`，`expected=DATE`，`mismatch=0` | `status=ANOMALY`，`expected=DATE`，`mismatch=2`，异常样例正是这两个日期 |
| 0 行数据集质量 | `rowCount=0`，`score=100`，`level=undefined` | `rowCount=0`，`score` 缺失（null），`level=EMPTY`，`notice=数据集没有数据行，质量评分不可用` |

结论：四个问题均已从「修复前可复现的错误行为」变为「修复后符合预期的正确行为」。

## 二、逐项根因与修改

### 2.1 UTF-8 BOM 混入首列名

**根因**：CSV 解析按逗号切分首行时未剥离 UTF-8 BOM（`\uFEFF`），BOM 落入首列列名，随后列名清洗把 BOM 与括号替换为下划线，得到 `_金额_元_`。

**修改**（[FileParserUtil.java](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/backend/src/main/java/com/dataclean/util/FileParserUtil.java)）：

- [第 131 行](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/backend/src/main/java/com/dataclean/util/FileParserUtil.java#L131)：读到首行后对 `firstLine[0]` 统一 `stripBom`，同时覆盖「有表头」与「无表头首条数据」两种路径。
- [第 436 行](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/backend/src/main/java/com/dataclean/util/FileParserUtil.java#L436)：新增 `stripBom(String)`，仅移除开头的 `\uFEFF`，不影响正文其他字符。
- [第 416 行](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/backend/src/main/java/com/dataclean/util/FileParserUtil.java#L416)：`sanitizeColumnName` 开头追加 `replace("\uFEFF","")` 兜底，避免任何来源的 BOM 进入列名。
- 不改变原有列名去重与特殊字符替换逻辑；无 BOM 的普通 UTF-8 文件列名保持原样（B2 回归通过）。

### 2.2 文本指标静默全 0

**根因**：图表聚合直接对 Y 轴字段做 `SUM/AVG` 类 SQL 聚合，文本字段被 MySQL 隐式转成 0，接口照常返回 200 与全 0 序列，用户无法察觉类型错误。

**修改**（[AnalysisService.java](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/backend/src/main/java/com/dataclean/service/AnalysisService.java)）：

- [第 250 行](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/backend/src/main/java/com/dataclean/service/AnalysisService.java#L250)：在已扩展配置校验后调用 `validateYAxisAggregation`。
- [validateYAxisAggregation 第 283 行起](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/backend/src/main/java/com/dataclean/service/AnalysisService.java#L283-L305)：仅对 `bar/line/area/pie` 生效；聚合缺省时 pie 为 `COUNT`、其余为 `SUM`；当聚合不是 `COUNT` 且 Y 轴字段非数值类型时抛 `BusinessException(400, "字段 X 为文本类型，仅支持 COUNT 统计；求和/平均等指标请选择数值字段")`。
- 文本字段改为 `COUNT` 后正常返回计数（M3 `sum=3`）；数值字段 `SUM` 保持原行为（M4 `sum=60`）；饼图、折线同一规则生效（M5/M6）。

### 2.3 非法日期校验

**根因**：日期一致性判定只做格式正则匹配（`YYYY-MM-DD`），`2026-02-30`、`2025-02-29` 格式合法但日期不存在，被误判为 `DATE` 一致。

**修改**（[DataQualityService.java](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/backend/src/main/java/com/dataclean/service/DataQualityService.java)）：

- [dateValidExpr 第 409 行起](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/backend/src/main/java/com/dataclean/service/DataQualityService.java#L409)：新增合法性表达式，先取日期部分（兼容 `T`/空格分隔时间、`/` 分隔），再 `STR_TO_DATE(..., '%Y-%m-%d') IS NOT NULL` 判定真实存在（含闰年）。
- [第 268 行](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/backend/src/main/java/com/dataclean/service/DataQualityService.java#L268)：`date_like` 统计追加 `AND dateValidExpr(text)`。
- [第 379 行](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/backend/src/main/java/com/dataclean/service/DataQualityService.java#L379)：DATE 异常样例条件改为 `NOT (格式正则 AND dateValidExpr(...))`。
- MySQL 实测：`2026-02-30 / 2025-02-29 / 2026-04-31 / 2026-01-00` → 无效；`2024-02-29 / 2026-12-31 / 2026-2-3` → 有效。合法日期未被误拒（D3/D4），全为不存在日期时整体降级为 `TEXT` 而非 `DATE`（D5）。

### 2.4 空数据集质量评分

**根因**：质量评分以固定 100 为基准按空值/类型错误扣分，0 行时无任何扣分，直接输出 100；数据集状态仍为 READY，前端无空数据提示。

**修改**：

- [DataQualityService.java 第 161-172 行](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/backend/src/main/java/com/dataclean/service/DataQualityService.java#L161-L172)：仅当 `total > 0` 才计算并写入 `score`，否则 `score=null`。
- [第 186 行](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/backend/src/main/java/com/dataclean/service/DataQualityService.java#L186)：`summary.score` 在 `total==0` 或列 profile 为空时为 `null`。
- [第 187 行](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/backend/src/main/java/com/dataclean/service/DataQualityService.java#L187)：新增 `level`：`EMPTY`（无数据）/`ABNORMAL`（存在不一致）/`NORMAL`（正常）。
- [第 192 行](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/backend/src/main/java/com/dataclean/service/DataQualityService.java#L192)：`notice` 明确「数据集没有数据行，质量评分不可用」；[第 212 行](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/backend/src/main/java/com/dataclean/service/DataQualityService.java#L212) 顶层提示「数据集没有数据行，无法评估质量；请先导入有效数据」。
- 前端 [Quality.vue 第 213-214 行](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/frontend/src/views/Quality.vue#L213-L214)：评分显示 `summary.score != null ? score : '—'`，空数据显示「无数据不评分」；[第 593 行](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/frontend/src/views/Quality.vue#L593) 客户端估算增加 `rows>0` 前置与「空数据集」优先级。
- 前端 [Dataset.vue 第 81 行 / 第 176 行](file:///C:/Users/zengyun/Desktop/Kimi_Agent_数据导入可视化平台/data-clean-platform/frontend/src/views/Dataset.vue#L81)：列表与表格对 `status===1 && rowCount===0` 显示「空数据集」标签。
- 空数据集的 READY 状态未改动（状态机含义保持「上传成功可用」），改为在质量评分与 UI 层区分「空数据集 / 正常 / 异常」，避免改动既有状态语义。

## 三、构建与部署

- 后端：`mvn -B -DskipTests package` → **BUILD SUCCESS**（JDK17），产物 `app.jar`（R3）。
- 前端：`vite build` → **成功**，已部署到 Nginx 根目录 `frontend/dist`。
- 运行：后端 18080/18081 正常启动，演示数据集 id=8 就绪；当前部署的 `app.jar` 为 R3 版本。
- 说明：为提供「修复前/修复后」对照，测试期间曾临时切回 R2 jar 采集基线，采集完成后已恢复 R3 jar 并重启。

## 四、R3 回归结果（独立匿名会话 + 独立测试数据集，18 项）

| 编号 | 场景 | 结果 |
|---|---|---|
| S0 | 建立独立匿名会话 | PASS |
| B1 | 带 BOM 的 CSV 首列名不含 BOM/下划线前缀（`金额_元_`） | PASS |
| B2 | 无 BOM 文件列名保持原样（回归） | PASS |
| M1 | 文本字段 SUM 指标被明确拒绝（400） | PASS |
| M2 | 文本字段默认 SUM 不再静默返回全 0 | PASS |
| M3 | 文本字段 COUNT 正常且结果非零（`sum=3`） | PASS |
| M4 | 数值字段 SUM 保持正常（`sum=60`） | PASS |
| M5 | 饼图：文本 SUM 拒绝、COUNT 正常 | PASS |
| M6 | 折线图文本 AVG 同样被拒绝 | PASS |
| D1 | `2026-02-30`/`2025-02-29` 判定为日期不一致（`mismatch=2`） | PASS |
| D2 | 异常样例仅包含两个非法日期 | PASS |
| D3 | `2024-02-29` 全部合法（闰年） | PASS |
| D4 | `2026-12-31` 判定为合法日期 | PASS |
| D5 | 全为不存在日期时不误判为 DATE 一致（降级 TEXT） | PASS |
| E1 | 0 行数据集上传成功且行数为 0 | PASS |
| E2 | 0 行数据集评分不为 100（score 缺失、level=EMPTY） | PASS |
| E3 | 空数据集明确提示无数据 | PASS |
| E4 | 正常数据集评分仍为数值（`score=100`, `level=NORMAL`，回归） | PASS |

**汇总：18/18 PASS，0 FAIL。** 明细见 `r3-test/result.json`、`r3-test/r3-test.log`。

## 五、未验证 / 遗留

1. 前端「空数据集」标签与评分「—」显示已构建部署，但**浏览器端真实点击查看**未在本轮执行，计划在 R5 浏览器验收补测。
2. BOM 修复覆盖 CSV；**XLSX/Excel 导入路径**未单独做 BOM 场景测试（XLSX 本身无 BOM 概念，列为 R5 高风险补充）。
3. 日期合法性基于 MySQL `STR_TO_DATE('%Y-%m-%d')`；带时区偏移（如 `2026-02-30T00:00:00+08:00`）的极端写法按「取日期部分后判定」处理，未穷举所有时区格式。
4. 文本指标校验目前覆盖 `bar/line/area/pie`；其他图表类型沿用原逻辑，未新增限制。

## 六、结论

R3 四个数据准确性问题均已修复并通过独立会话回归（18/18）。修复前错误行为可稳定复现，修复后行为符合业务语义，且正常数据集、数值统计、合法日期、无 BOM 文件等原有功能未受影响。**可进入 R4。**
