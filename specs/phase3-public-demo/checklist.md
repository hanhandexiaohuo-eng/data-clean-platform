# 第三阶段验收清单（修订 v3，含上线前安全加固）

标记说明：
- `[x] 已实测`：在隔离数据库（MySQL 13306）、临时上传卷与本机后端实例上实际执行并保留脱敏结果。
- `[~] 仅静态检查`：仅通过代码/配置审阅确认，未在真实网关或生产拓扑运行。
- `[ ] 未验证`：受当前环境限制（无 Docker / Nginx / OpenSSL）未执行。

## 一、模式与隔离

- [x] 已实测：`APP_MODE=public` 免登录可访问数据集/预览/清洗/导出，公开会话只能看到 demo 与自身数据。
- [~] 仅静态检查：`APP_MODE=private` 保留登录页，行为与第二阶段一致（`mvn test` 通过，无端到端回归）。
- [x] 已实测：公开会话无法读取私有历史数据、AI 配置、AI 历史、管理/运维接口（统一 404）。
- [x] 已实测：公开用户只看到演示数据与本人会话数据（跨会话列表不出现他人资源名）。
- [~] 仅静态检查：私有模式下公开端点仅 `/api/public/config` 可用，其余拒绝。

## 二、越权与存在性

- [x] 已实测：跨会话 dataset 详情/预览/列/统计 → 404。
- [x] 已实测：跨会话清洗规则读取/更新/删除 → 404。
- [x] 已实测：跨会话清洗日志回滚 → 404。
- [x] 已实测：跨会话导出与删除 → 404。
- [x] 已实测：错误响应统一 `{"code":404,"message":"资源不存在"}`，不含资源名、表名、所有者、内部 ID。
- [x] 已实测：演示数据可读/预览/统计；清洗、修改、删除、导出均 403。

## 三、Cookie、Origin 与会话

- [~] 仅静态检查：生产 `Set-Cookie: __Host-dc_anon=...; Path=/; HttpOnly; Secure; SameSite=Strict`（无 Domain）。无 HTTPS 环境，未抓取真实响应头；启动校验与单元/配置检查已覆盖。
- [x] 已实测：local Profile 下 Cookie 为 `dc_anon_local`，`HttpOnly; SameSite=Strict; Path=/`、无 Domain、非 Secure（仅本机调试）。
- [x] 已实测：删除 Cookie 后旧数据不可恢复，业务接口返回 401 + `X-Session-Required: 1`。
- [x] 已实测：撤销/过期会话访问业务接口 → 401，旧资源不可恢复。
- [x] 已实测：数据库仅存 token 的 SHA-256 摘要，无明文 token；日志不打印 Cookie/Token。
- [~] 仅静态检查：Cookie 不写入 localStorage/sessionStorage（前端代码审阅）。
- [x] 已实测：public 下非 GET 缺少 Origin → 403；伪造/跨源 Origin → 403；白名单精确匹配，不接受 `*`。
- [x] 已实测：生产 public 模式 Cookie 名称不合规（`dc_anon`）拒绝启动，进程退出码非 0。
- [x] 已实测：生产 public 模式 `cookie-secure=false` 拒绝启动，日志给出明确原因。
- [x] 已实测：local 覆盖位于 `backend/local/application-local.yml`，生产 jar 内不存在任何 `application-local.yml`。

## 四、会话创建防滥用

- [x] 已实测：单 IP 每自然日创建上限（`PUBLIC_SESSION_CREATE_PER_IP_PER_DAY`）超限 → 429，不签发 Cookie。
- [x] 已实测：当日达到阈值后返回 428 + `X-Session-Challenge-Required: 1`；领取一次性令牌后可成功创建。
- [x] 已实测：单 IP 活跃会话上限超限 → 429，脱敏计数 `session_reject_ip_active`。
- [x] 已实测：全站活跃会话上限超限 → 429，脱敏计数 `session_reject_global_active`，无半成品会话。
- [x] 已实测：未建立会话时仅 `/api/health`、`/api/public/config`、会话入口/挑战、静态资源可访问，其余业务 API 401。
- [x] 已实测：拒绝统计只落 `anon_usage_daily` 指标与 HMAC IP 摘要，不记录 Cookie/Token/原始 IP。

## 五、清理与容量

- [x] 已实测：到期会话的文件、动态表、备份表、清洗记录、图表、导出记录、AI 记录、挑战记录、会话数据全部清理。
- [x] 已实测：清理只按数据库元数据中的精确表名执行，不按前缀/正则扫描。
- [x] 已实测：物理表已删/元数据残留、元数据已删/物理表残留、purging 进程中断三种场景重跑均收敛（`PASS=18 FAIL=0`）。
- [x] 已实测：清理任务可重复执行，不触碰 `owner_type='private'` 数据。
- [x] 已实测：`cleanup_log` 仅有脱敏统计，无 token/IP/原始数据。
- [x] 已实测：无法证明归属的孤儿表只报告数量，不自动删除。
- [x] 已实测：单文件大小、行数、列数、上传总存储配额生效；数据集/清洗/图表/导出配额为代码级校验（未逐一压测）。

## 六、AI

- [~] 仅静态检查：默认关闭 403、内置模板白名单、字段/聚合脱敏、日预算/熔断、供应商错误统一 503。
- [~] 仅静态检查：挑战绑定会话 + IP 摘要 + 过期且单次核销；公开用户无法访问 AI 配置/Key/历史。
- [ ] 未验证：真实 AI 供应商调用与全站 token 预算（隔离环境未配置供应商，`PUBLIC_AI_ENABLED=false`）。

## 七、网关与部署

- [~] 仅静态检查：Compose 仅发布 80（HTTPS 覆盖再发布 443）；`backend`/`mysql` 仅 `expose`，无宿主机端口。
- [~] 仅静态检查：Nginx 唯一入口、隐藏版本、安全头、分级限流、请求体上限。
- [~] 仅静态检查：`/api/health` 与 `/api/admin/` 在 Nginx 返回 404；迁移为独立一次性容器，无公网 HTTP 入口。
- [~] 仅静态检查：Nginx 模板 envsubst 仅替换白名单变量，`$uri/$host/$remote_addr` 保留。
- [~] 仅静态检查：HTTPS 覆盖配置含 80→443 跳转、HSTS、TLS1.2/1.3。
- [ ] 未验证：Nginx 实际启动、TLS 握手、HSTS/安全响应头抓取、真实公网端口收敛（环境无 Docker/Nginx/OpenSSL）。

## 八、安全与质量

- [x] 已实测：错误响应无 SQL/堆栈/路径/密钥/数据库信息/会话 ID。
- [x] 已实测：伪造扩展名、二进制伪 CSV、超大文件、超行数、超列数、压缩炸弹、异常 Excel 均被 400 拒绝且不泄露内部信息。
- [x] 已实测：上传目录不可静态访问；原始文件名不落盘；上传临时文件请求结束即删除（`tmp` 计数归零）。
- [x] 已实测：CSV 导出对 `= + - @` 公式注入字符加 `'` 前缀转义。
- [~] 仅静态检查：恶意文件扫描接口已预留，当前 `none` 空实现，上线前必须接入真实扫描服务。
- [x] 已实测：`mvn test`（72 通过）、`vite build`、`scan-secrets`、迁移演练（含 V8/R8）全部通过。
- [x] 已实测：交付文档含修改清单、迁移、清理说明、容量配置、部署、回滚、测试结果、已知限制。

## 九、第四阶段增补（生产发布前验证）

> 详细验收矩阵见 [`PHASE4_DELIVERY.md`](../../PHASE4_DELIVERY.md)。

- [x] 已实际验证：真实 Nginx 1.27.4 + 自签名证书，80→443 跳转、TLS1.2/1.3、HSTS/CSP、Cookie 属性透传、非法 Host 444、内部路径 404、502/404 脱敏、413/429、缓存与日志脱敏（`PASS=42 FAIL=0`）。
- [x] 已实际验证：HTTP 恶意文件扫描接入，正常 CSV 通过、风险/超时/5xx/不可用全部拒绝（`PASS=22 FAIL=0`）。
- [x] 已实际验证：伪造 `X-Real-IP` 无法绕过单 IP 活跃上限；指标覆盖会话/上传/AI/清理/容量。
- [x] 已实际验证：管理端口仅内网，公网 `/actuator/` 返回 404。
- [x] 已实测：`mvn test`（85 通过）、前端构建、`scan-secrets`、Compose 静态校验（`PASS=29`）。
- [ ] 未验证（环境限制）：`docker compose up`、容器网络隔离、公网端口扫描、真实域名/证书、真实扫描产品、AI 开启后配额/熔断、Prometheus 实际抓取与告警触发。
