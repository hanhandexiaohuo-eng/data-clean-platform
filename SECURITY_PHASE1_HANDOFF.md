# 数据清洗平台 · 第一阶段安全底线改造（进度记录）

- 记录日期：2026-11-19
- 范围：仅安全底线改造，未部署、未执行任何真实清洗/回滚/导入/删除
- 明确不做（后续阶段）：免登录、Cookie 访问凭证、HTTPS、Nginx、Docker Compose

## 一、完成内容与验证结果

| 项目 | 命令 | 结果 |
|---|---|---|
| 后端编译与测试 | `mvn test`（JAVA_HOME=D:\JDK21） | BUILD SUCCESS，Tests run: 55, Failures: 0, Errors: 0 |
| 前端构建 | `node .\node_modules\vite\bin\vite.js build` | 成功（2042 modules） |
| 敏感信息扫描 | `node .\scripts\scan-secrets.mjs` | 未发现疑似明文凭据 |
| 迁移演练（真实 MySQL 8.4） | `scripts/rehearse-migration.ps1`（临时库） | 通过：两次执行均成功、列结构正确、回滚 DDL 可用、临时库已清理 |

新增测试（55 个）：`AiServiceKeyTest`(5)、`AnalysisServiceSecurityTest`(7)、`CleanServiceRuleValidationTest`(6)、`AiPiiMaskerTest`(7)、`AiSecretCipherTest`(6)、`FileParserUtilTest`(10)、`SqlGuardTest`(10)、`DatasetMapperBindingTest`(2，H2 真实 MyBatis 参数绑定)、`AiKeyMigrationRunnerTest`(2，H2 迁移与幂等)。

## 二、关键改动

1. 凭据外置
   - `backend/src/main/resources/application.yml`：全部环境变量；`log-impl: NoLoggingImpl`；`server.error.include-*=never`；移除 `allowMultiQueries`。
   - 新增脱敏模板 `application.yml.example`、项目根 `.env.example`。
   - `JwtUtil` 去除内置默认密钥并校验长度；`DataInitializer` 去除 admin/123456，改环境变量。
   - `GlobalExceptionHandler` 统一错误码，响应不含 SQL/堆栈/路径。
2. AI 密钥
   - 新增 `AiSecretCipher`（AES-256-GCM，`v1:Base64(iv||ciphertext)`，主密钥仅来自 `AI_SECRET_KEY`）。
   - 新增 `AiConfigVO`（仅 `keyConfigured` + `apiKeyMask`），Controller 全部改返回 VO。
   - `AiService.saveConfig` 只写密文与掩码；编辑留空不覆盖；调用前服务端解密。
   - `AiKeyMigrationRunner` 启动迁移存量明文：先写密文、再清明文，并有「有密文即清明文」兜底。
3. 动态 SQL
   - 新增 `SqlGuard`：标识符/表名/聚合(SUM,AVG,COUNT,MAX,MIN)/图表类型/过滤操作符/排序方向/列类型白名单，非法统一 400。
   - `AnalysisService` 全流程白名单 + 参数绑定；`limit` 默认 100、上限 5000。
   - `CleanService` 规则类型与 targetType 白名单、`targetType` 走受控枚举、自定义表达式过滤默认下线（`CLEAN_CUSTOM_FILTER_ENABLED=false`），开启时走受限 DSL + 参数绑定。
   - `SqlBuilderUtil`、`DynamicTableService`、`DatasetService` 所有表名/列名经 `SqlGuard`；预览分页 `size∈[20,200]`、页码上限 100 万防 offset 溢出。
4. 上传与数据
   - `FileParserUtil`：扩展名+文件头双校验、100MB/50 万行/1000 列/20 工作表上限、`ZipSecureFile`、文件名净化、`escapeCsvFormula`。
   - 前端 `Analysis.vue` 导出 CSV 对 `= + - @ Tab CR` 前缀加 `'`。
   - 上传目录不在静态资源映射内（`WebMvcConfig` 仅映射 classpath:/static）。
5. AI 数据边界
   - `AiPiiMasker` 默认脱敏手机号/邮箱/身份证/银行卡/Token；Prompt 统一脱敏并截断（`AI_MAX_PROMPT_CHARS`）。
   - 生成日志 `prompt/response/errorMsg/inputSummary` 脱敏并截断 2000 字符。
   - `OkHttpUtil` 不再记录 Authorization、供应商响应原文、模型响应原文。

## 三、数据库迁移与回滚

1. 备份：`mysqldump ... ai_config > ai_config_backup_<时间戳>.sql`
2. 执行 `sql/migration_v4_ai_key_cipher.sql`（纯增量、可重复执行：仅加 `api_key_cipher`/`api_key_mask`、放宽 `api_key` 为可空）
3. 配置 `AI_SECRET_KEY` 后启动应用，自动完成存量明文 → 密文迁移并清空明文字段
4. 回滚：优先恢复备份；原地回滚见 `sql/rollback_v4_ai_key_cipher.sql`（`api_key` 保持可空；旧版本需重新填写 Key）

## 四、环境变量清单（仅变量名）

`DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`JWT_SECRET`、`JWT_EXPIRATION`、`AI_SECRET_KEY`、`AI_MAX_PROMPT_CHARS`、`ADMIN_USERNAME`、`ADMIN_PASSWORD`、`UPLOAD_PATH`、`UPLOAD_MAX_FILE_SIZE`、`UPLOAD_MAX_REQUEST_SIZE`、`UPLOAD_MAX_ROWS`、`UPLOAD_MAX_COLUMNS`、`UPLOAD_MAX_EXCEL_SHEETS`、`CLEAN_CUSTOM_FILTER_ENABLED`、`LOG_LEVEL`

说明：AI 供应商 Key 不走环境变量，只经页面提交后加密入库，接口只读掩码。

## 五、验证命令（明天可直接复跑）

```powershell
# 后端测试
$env:JAVA_HOME='D:\JDK21'
& 'C:\Users\zengyun\.m2\wrapper\dists\apache-maven-3.9.6-bin\3311e1d4\apache-maven-3.9.6\bin\mvn.cmd' -B -f 'C:\Users\zengyun\Desktop\Kimi_Agent_数据导入可视化平台\data-clean-platform\backend\pom.xml' test

# 前端构建
node .\node_modules\vite\bin\vite.js build   # cwd=frontend

# 敏感信息扫描
node .\scripts\scan-secrets.mjs              # cwd=项目根
```

## 六、残余风险 / 后续待办

1. ~~迁移脚本未在真实 MySQL 演练~~ 已完成（2026-11-20）：`scripts/rehearse-migration.ps1` 在 MySQL 8.4 的临时库 `dataclean_migration_rehearsal` 中验证「增量迁移 → 重复执行 → 列结构 → 回滚 → 清理」，全程未触碰真实库 `dataclean`；真实库迁移脚本需在部署时执行。
2. 已补充真实 MyBatis（H2 MySQL 兼容模式）集成测试，验证 `<script>${sql}</script>` 参数绑定与注入型取值不改变 SQL 语义；H2 为 test 依赖，不进入生产包。
3. 已移除未使用的 `DatasetMapper.selectColumnValues`，减少动态 SQL 面。
4. 第二阶段访问边界已确认：**轻量访问令牌 + 网络层收紧**（保留登录页、去掉账号体系，单一访问令牌 + 反向代理/安全组限制来源 IP）；HTTPS、Nginx、Docker Compose 同属第二阶段。
