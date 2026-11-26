# 核心业务流程图（2026-11-26）

> 覆盖 6 条主链路：上传解析、清洗与版本、质量计算、图表版本刷新、看板管理、导出。
> 图中接口、状态值与异常码均与当前代码一致；每一步的「失败分支」单列说明。

---

## 1. 上传解析流程

```mermaid
sequenceDiagram
    autonumber
    participant U as 浏览器
    participant N as Nginx
    participant C as DatasetController
    participant S as DatasetService
    participant SC as FileScanService
    participant I as StreamingImportService
    participant T as DynamicTableService
    participant DB as MySQL

    U->>N: POST /api/dataset/upload (file,name,hasHeader,encoding)
    N->>N: upload_zone 限流（10r/m, burst 3）
    N->>C: 转发（同源）
    C->>S: upload(...)
    S->>S: 会话/归属校验 + 公开模式配额（数量/大小/行数/列数）
    S->>SC: 扫描文件（none / http）
    SC-->>S: 通过 / 拒绝
    alt 小文件（≤ 20000 行且 ≤ 5MB）
        S->>I: 同步解析
    else 大文件
        S->>DB: INSERT async_job(IMPORT, PENDING)
        S-->>U: 返回 jobId + 占位数据集
        Note over DB: JobWorker 异步执行后续步骤
    end
    I->>I: 读取首行 → 剥离 BOM → 列名清洗/去重
    I->>I: 前 200 行抽样推断类型
    I->>T: 创建物理表 data_<name>_<ts>
    loop 每 chunk（默认 1000 行）
        I->>T: 批量 INSERT
        I->>DB: 更新任务 processed_rows / progress
    end
    I->>DB: INSERT dataset / dataset_column
    I->>DB: INSERT dataset_version v1 ACTIVE
    I->>DB: UPDATE dataset.current_version_id
    I->>DB: INSERT audit_log(UPLOAD)
    S-->>U: Result<dataset>
```

**失败分支**

| 场景 | 结果 |
|---|---|
| 文件为空 / 无法解析 / 损坏 XLSX | 400「Excel 解析失败，请检查文件格式」，不创建数据集 |
| `.xls` 旧格式 | 400，提示另存为 `.xlsx` 或 CSV |
| 超出行/列/大小限制 | 400，配额提示 |
| 扫描不通过 | 400/403，临时文件清理 |
| 上传过于频繁 | Nginx 429 JSON `{"code":429,"message":"请求过于频繁，请稍后重试"}` |
| 异步任务失败 | `async_job.status=FAILED`，`error_summary` 脱敏摘要，占位数据集标记失败 |

---

## 2. 清洗与版本流程

```mermaid
sequenceDiagram
    autonumber
    participant U as 浏览器
    participant CL as CleanController
    participant CS as CleanService
    participant JS as JobService
    participant W as JobWorker
    participant VS as VersionService
    participant DB as MySQL

    U->>CL: POST /api/clean/rule（保存规则）
    CL->>DB: INSERT/UPDATE clean_rule
    U->>CL: POST /api/clean/preview
    CL->>CS: 预览（不写业务表）
    CS-->>U: 预览结果 / 影响行数
    U->>CL: POST /api/clean/execute
    CL->>CS: executeClean(datasetId)
    CS->>CS: 按 dataset 加 JVM 锁（串行化检查与建任务）
    CS->>DB: 查询 PENDING/RUNNING/CANCEL_REQUESTED
    alt 已有进行中任务
        CS-->>U: 409「该数据集已有进行中的清洗任务」
    else 无进行中任务
        CS->>DB: INSERT async_job(CLEAN, PENDING, input_version_id)
        CS-->>U: { jobId }
    end
    W->>DB: 领取任务 → RUNNING
    W->>VS: 读取输入版本物理表
    W->>DB: 创建新表 data_<name>_<ts>_vN（BUILDING）
    loop 按 sort_order 执行启用规则
        W->>DB: 缺失值/去重/类型转换/标准化/异常值/自定义过滤
    end
    W->>VS: 计算 checksum（行数 + 表校验和）
    W->>DB: version → ACTIVE，写 dataset_version_rule 快照
    W->>DB: 切换 dataset.current_version_id
    W->>DB: 刷新 dataset_column，失效 stats_cache
    W->>DB: 旧版本 → ARCHIVED，任务 SUCCEEDED
    W->>DB: INSERT audit_log(CLEAN_START/SUCCESS)
```

**关键约束**

- 清洗只新增版本，不原地修改输入版本。
- 规则快照写入 `dataset_version_rule`；后续规则被删除也不影响历史版本解释。
- 失败时：新版本 `FAILED`，`current_version_id` 不变，任务记录 `error_code/error_summary`。
- 取消：写 `cancel_requested_at`，Worker 在安全点停止并置 `CANCELLED`。

---

## 3. 质量计算流程

```mermaid
flowchart TD
    A["GET /api/quality/{datasetId}"] --> B{"报表存在<br/>且 version_id 匹配<br/>且未过期"}
    B -->|是| C["直接返回缓存报表<br/>stale=false"]
    B -->|否| D["POST /api/quality/{datasetId}/compute"]
    D --> E["按 dataset 加计算锁"]
    E --> F["读取当前版本物理表"]
    F --> G{"row_count == 0 ?"}
    G -->|是| H["score=null, level=EMPTY<br/>notice=数据集没有数据行"]
    G -->|否| I["逐列统计<br/>空值率/唯一值/最值/均值/标准差"]
    I --> J["类型一致性<br/>数字/日期/文本"]
    J --> K["日期合法性校验<br/>STR_TO_DATE 含闰年"]
    K --> L["重复行 / 异常值样例"]
    L --> M["汇总评分与问题列表"]
    H --> N["UPSERT data_quality_report<br/>dataset_id + version_id 唯一"]
    M --> N
    N --> O["返回报表"]
    O --> P["版本切换后 version_id 不匹配<br/>自然重新计算"]
```

**修复后的准确性规则（R3）**

| 场景 | 行为 |
|---|---|
| 文本列做 SUM/AVG | 400，提示仅支持 COUNT |
| `2026-02-30`、`2025-02-29` | 判为非法日期，计入 DATE 异常样例 |
| 0 行数据集 | 不返回 100 分，`score` 缺省、`level=EMPTY` |
| CSV 首列 BOM | 列名剥离 BOM，不产生 `_金额_元_` 之类脏名 |

---

## 4. 图表版本刷新流程

```mermaid
sequenceDiagram
    autonumber
    participant U as 浏览器
    participant CV as ChartViewController
    participant SC as SavedChartService
    participant DB as MySQL
    participant AN as AnalysisService

    U->>CV: POST /api/chart-view/{id}/generate
    CV->>SC: generate(id, X-Confirm-Version)
    SC->>DB: 读取 saved_chart + dataset
    SC->>SC: 检查 missingFields（字段是否仍存在）
    alt 字段已删除
        SC-->>U: 409「字段已变更，请重新配置: ...」
    end
    SC->>SC: stale = chart.dataset_version_id != dataset.current_version_id
    alt stale 且未携带确认版本
        SC-->>U: 409「数据已更新，需要确认是否使用最新版本」
    else stale 且携带版本 ≠ 当前版本
        SC-->>U: 409「数据版本已再次变化，请重新确认」
    else 版本一致
        SC->>DB: 条件更新（仅当仍绑定旧版本）重绑到当前版本
        SC->>DB: 再次读取 dataset 当前版本
        alt 绑定后又产生新版本
            SC->>DB: 回滚本次绑定
            SC-->>U: 409「数据版本已再次变化，请重新确认」
        end
        SC->>AN: 按配置查询当前版本数据
        AN-->>SC: series / categories
        SC->>DB: INSERT audit_log(CHART_VIEW_REFRESH)
        SC-->>U: 图表数据
    end
```

**状态语义**

| 字段 | 含义 |
|---|---|
| `stale=true` | 图表绑定版本落后于数据集当前版本 |
| `missingFields` | 配置引用的字段在当前版本已不存在 |
| `invalid=true`（看板项） | 图表或数据集已被删除，卡片失效 |

---

## 5. 看板管理流程

```mermaid
flowchart TD
    A["POST /api/dashboard<br/>创建看板"] --> B["POST /api/dashboard/{id}/items<br/>添加图表"]
    B --> C{"图表可见<br/>且未重复挂载"}
    C -->|否| D["404 / 409"]
    C -->|是| E["INSERT dashboard_item<br/>默认 grid_w=4, grid_h=4"]
    E --> F["GET /api/dashboard/{id}"]
    F --> G["批量装配（一次查询）<br/>items → charts → datasets → versions → columns"]
    G --> H{"图表/数据集是否存在"}
    H -->|否| I["返回 invalid=true + 原因<br/>看板仍 200 可打开"]
    H -->|是| J["返回 stale / missingFields / 图表配置"]
    I --> K["用户可移除失效卡片"]
    J --> L["PUT /items/{itemId} 调整宽高<br/>PUT /order 排序"]
    L --> M["layoutBusy 防并发布局请求"]
    K --> N["DELETE /items/{itemId}"]
    N --> O["删除图表时级联删除 dashboard_item"]
```

**容错原则（R2）**

- 单项失效不导致看板整体 404；显式返回 `invalid` 与原因。
- 真实错误（数据库异常、权限异常）不被「失效」掩盖，仍按 500/403 返回。
- 看板详情使用批量装配，消除逐项查询的 N+1（实测 SQL 35 → 14）。

---

## 6. 导出流程

```mermaid
sequenceDiagram
    autonumber
    participant U as 浏览器
    participant EX as ExportController
    participant ES as ExportService
    participant OS as OwnershipService
    participant DB as MySQL

    rect rgb(238, 246, 255)
    Note over U,DB: CSV 导出
    U->>EX: POST /api/export/dataset/{id}<br/>X-Confirm: yes
    EX->>EX: 校验确认头
    EX->>OS: 校验数据集可见
    OS-->>EX: 可见 / 404
    EX->>ES: 导出 CSV
    ES->>DB: 读取当前版本数据（限制最大行数）
    ES->>DB: INSERT export_record（公开模式配额）
    ES->>DB: INSERT audit_log(EXPORT)
    ES-->>U: text/csv（UTF-8 BOM）
    end

    rect rgb(245, 240, 255)
    Note over U,DB: PNG 导出
    U->>EX: POST /api/export/chart/{chartViewId}/png<br/>X-Confirm: yes
    EX->>OS: 校验图表可见
    EX->>ES: 生成 PNG
    ES->>DB: 读取图表配置与当前版本数据
    ES->>ES: ChartPngRenderer 绘制
    ES->>DB: INSERT audit_log(EXPORT_PNG)
    ES-->>U: image/png
    end
```

**失败分支**

| 场景 | 结果 |
|---|---|
| 缺少 `X-Confirm: yes` | 400，明确提示需要确认 |
| 资源不可见 | 404（不泄露存在性） |
| 公开模式超出导出配额 | 429/403，配额提示 |
| 导出期间数据版本变化 | 图表导出走版本校验，冲突返回 409；CSV 读取当前版本快照 |
| 后端返回 JSON 错误 | 前端按 `Content-Type` 判定，不把 JSON 存成 CSV/PNG（R1 修复） |
| 并发点击导出 | 前端按钮 loading + 后端确认头/配额双重约束，不产生重复文件 |
