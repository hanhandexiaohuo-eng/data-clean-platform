# BACKLOG —— 非阻塞技术债与已知限制（2026-11-26）

> 原则：以下事项**不影响当前单实例预发布使用**，因此未在 R2–R5 中处理；任何涉及删数据、改表结构、扩容的事项，执行前必须先出迁移与回滚方案并经确认。
> 状态标记：`待处理` / `需人工验证` / `设计取舍`。

---

## A. 历史孤儿数据（需确认后处理）

**现象**（R2 基线实测，`dataclean`）：

| 类型 | 数量 | 说明 |
|---|---|---|
| 孤儿质量报表 | 7 条 | `data_quality_report.dataset_id` 已不存在 |
| 孤儿规则快照 | 4 条 | `dataset_version_rule.version_id` 已不存在 |
| 历史孤儿物理表 | 5 张 | 早期删除逻辑遗留，未被任何 `dataset_version` 引用 |

已知疑似孤儿物理表（以实际查询为准，不要按名字直接删）：

- `data_audit_100k_1795614439846`
- `data_smoke_ds_1795607756054_v2`
- `data_审计测试_special_csv_1795614024257_v2`
- `data_审计测试_special_csv_1795614024257_v3`
- `data_淘宝母婴购物数据集_1781487227857_backup_1781487640961`

**风险**：直接 `DROP TABLE` 不可回滚；清理元数据可能影响历史审计解释。

**建议流程**：

1. 先用只读 SQL 生成清单（见下），人工核对；
2. 备份：`mysqldump` 相关表 + `SHOW CREATE TABLE` 保存 DDL；
3. 先在预发布执行、验证看板/质量/任务页面无异常；
4. 生产执行前准备回滚（备份恢复脚本）；
5. 只清理确认无引用的物理表；元数据孤儿建议保留，避免破坏历史审计。

**只读审计 SQL**：

```sql
-- 孤儿质量报表
SELECT r.id, r.dataset_id, r.version_id
FROM data_quality_report r
LEFT JOIN dataset d ON d.id = r.dataset_id
WHERE d.id IS NULL;

-- 孤儿规则快照
SELECT vr.id, vr.version_id
FROM dataset_version_rule vr
LEFT JOIN dataset_version v ON v.id = vr.version_id
WHERE v.id IS NULL;

-- 疑似孤儿物理表（需与 dataset_version.table_name 比对）
SELECT t.table_name
FROM information_schema.tables t
LEFT JOIN dataset_version v ON v.table_name = t.table_name
WHERE t.table_schema = DATABASE()
  AND t.table_name LIKE 'data\_%'
  AND t.table_name NOT LIKE '%\_v%'
  AND v.id IS NULL;
```

**优先级**：低（仅影响存储与报表整洁，不影响功能）。
**状态**：待处理（需用户确认）。

---

## B. 多实例部署的并发控制

**现状**：并发清洗、并发质量计算使用 `ConcurrentHashMap<Long, Object>` + `synchronized` 的 JVM 内锁。

**风险**：多实例/多副本部署时锁不跨进程，仍可能出现重复任务或唯一键冲突。

**建议**（按成本从低到高）：

1. 在 `async_job` 上增加「同一 dataset + job_type + 活动状态」的数据库唯一约束（需迁移 + 回滚）；
2. `data_quality_report` 已存在 `uk_dqr_dataset_version`，把插入改为 `INSERT ... ON DUPLICATE KEY UPDATE` 或捕获 `DuplicateKeyException` 后重查；
3. 引入 Redis/数据库分布式锁（新增依赖）；
4. 清洗执行改为数据库乐观锁（版本号/CAS）。

**优先级**：中（扩容前必须处理）。
**状态**：待处理。

---

## C. 浏览器端人工验证项

| 项目 | 原因 | 验证方式 |
|---|---|---|
| CSV / PNG 真实点击导出 | 自动化浏览器无法接受自签证书（`ERR_CERT_AUTHORITY_INVALID`） | 手动打开 `https://localhost:8443`，接受证书后逐页点击导出，核对文件名、内容与 Content-Type |
| 看板失效卡片交互 | 同上，DOM 不可达 | 手动制造孤儿引用（删除图表后查看看板），确认红色失效提示与「移除该卡片」可用 |
| 私有模式并发清洗 | 预发布为 public 模式，切换需重启并配置管理员 | 在私有实例复跑 R5 风险脚本 |
| 清洗中途进程级故障 | 未做 kill -9 / 断库演练 | 破坏性演练环境执行，确认版本不切换、任务可恢复 |
| 500 响应体格式 | 修复后并发场景不再产生 500，未构造其他 500 | 临时触发内部异常，确认 `{code,message}` 且不泄露堆栈 |

**优先级**：中。
**状态**：需人工验证。

---

## D. 性能与容量

1. **10 万行以上压测未做**：已导入 10 万行数据集（`data_audit_100k_1795614439846`），但未做完整「导入 → 清洗 → 质量 → 图表 → 导出」压力测试；R5 高风险最大 2 万行。
2. **Nginx 限流为单实例内存限流**：多 Nginx 或多实例时总额度会被放大；如需全局配额，需在应用层或 Redis 实现。
3. **`stats_cache` / 质量报表无主动淘汰任务**：仅按版本与 `expires_at` 失效，长期运行会累积历史版本报表。
4. **动态表无分区/归档策略**：版本保留由 `JOBS_VERSION_KEEP` 与 `JOBS_VERSION_MAX_AGE_DAYS` 控制，但物理表清理依赖任务；异常中断可能留下未登记表。

**优先级**：中。
**状态**：待处理。

---

## E. 数据模型与语义取舍

| 项 | 说明 | 建议 |
|---|---|---|
| 无数据库外键 | 所有关联为逻辑关联，靠应用层事务保证 | 保持现状（动态表与批量导入场景下外键成本高），但需在删除路径持续维护级联清单 |
| `async_task` 预留表 | V5 创建，当前业务使用 `async_job`，`async_task` 无写入 | 保留或在下一次大版本迁移中删除（需确认无外部依赖） |
| `clean_log` 在数据集删除后保留 | 审计需要，但 `backup_table` 可能已被清理 | 文档明确「日志保留、备份表不保证存在」；如需长期回溯，应保留备份表或导出日志 |
| 旧版本质量报表保留 | 版本切换后不删除，仅查询不使用 | 可增加按 `version_id` 的归档清理任务 |
| `chart_view` 与 `saved_chart` 并存 | `chart_view` 用于匿名配额计数，`saved_chart` 为正式图表 | 保持现状；新增图表能力时避免双写不一致 |

**优先级**：低。
**状态**：设计取舍。

---

## F. 已保留但不再推荐使用的入口

| 入口 | 现状 | 说明 |
|---|---|---|
| `POST /api/clean/rollback/{logId}` | 后端保留路由，显式拒绝/不可用 | 回滚语义已由「版本恢复」替代；前端封装 `rollbackClean` 已删除（R4） |
| 旧 `.xls` 上传 | 返回 400，提示另存 `.xlsx`/CSV | 避免 HSSF 内存与格式兼容问题 |
| `backend/src/main/resources/static/index.html` | 单文件静态原型，不请求后端 | 仅作历史记录，不代表平台功能 |

**优先级**：低。
**状态**：设计取舍。

---

## G. 时间与格式

1. 后端 Jackson 固定 `Asia/Shanghai`，MySQL 连接串 `serverTimezone=Asia/Shanghai`；若部署到其他时区，需确认容器 `TZ` 与数据库时区一致。
2. 日期列以字符串抽样推断，混合格式（`2026-01-01` 与 `2026/01/01`）会判为不一致；属预期行为，但前端需给出可读提示。
3. XLSX 单元格若含 BOM 或前后空格，解析时统一 `trim` 并剥离 `\uFEFF`；仍建议在真实业务文件上抽样验证。
4. 导出 CSV 带 BOM，若下游系统不接受 BOM，需要在导出参数中增加开关（当前无此配置）。

**优先级**：低。
**状态**：待处理 / 需人工验证。

---

## H. 文档与测试资产

1. 预发布测试脚本位于本地临时目录，未纳入仓库；如需回归，应整理为 `scripts/` 下的可重复脚本（需脱敏环境变量）。
2. 仓库未包含页面截图；如需交付材料，应在真实环境手动截图，不使用生成图代替。
3. `docs/` 中 R1–R5 报告为历史记录，**不得覆盖**；新增文档使用独立文件名。

**优先级**：低。
**状态**：待处理。
