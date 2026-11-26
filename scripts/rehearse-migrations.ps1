# DataClean 版本化迁移演练脚本（隔离临时库，不触碰真实数据）
#
# 覆盖：
#   1. 空库从 V1 全量初始化，重复执行幂等；
#   2. 存量库基线识别（V1–V2 视为已执行），只执行 V3–V8；
#   3. 迁移不删除既有业务数据（V7/V8 仅增量新增）；
#   4. R4/R5/R6/R7/R8 回滚脚本可用；
#   5. 失败重试：迁移记录丢失（DDL 已生效）后重跑可安全收敛；
#   6. 全程只操作临时库 dataclean_migration_rehearsal，结束后删除。
#
# 使用方式（项目根目录）：
#   powershell -ExecutionPolicy Bypass -File .\scripts\rehearse-migrations.ps1
#
# 密码通过 Read-Host 输入，仅写入当前进程 MYSQL_PWD，不落盘、不回显。

param(
    [string]$MysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe',
    [string]$DbHost = '127.0.0.1',
    [int]$DbPort = 3306,
    [string]$DbUser = 'root',
    [switch]$AllowEmptyPassword
)

$ErrorActionPreference = 'Stop'
$RehearsalDb = 'dataclean_migration_rehearsal'
if ($RehearsalDb -ne 'dataclean_migration_rehearsal') { throw '临时库名称被修改，已中止' }
if (-not (Test-Path $MysqlExe)) { throw "未找到 mysql.exe：$MysqlExe，可用 -MysqlExe 指定路径" }

$projectRoot = Split-Path -Parent $PSScriptRoot
$migrationDir = Join-Path $projectRoot 'db\migration'
$rollbackDir = Join-Path $projectRoot 'db\rollback'
if (-not (Test-Path $migrationDir)) { throw "未找到迁移目录：$migrationDir" }

if ($AllowEmptyPassword) {
    $env:MYSQL_PWD = ''
}
elseif ($null -eq $env:MYSQL_PWD) {
    $secure = Read-Host "请输入 MySQL 账号 $DbUser 的密码（仅用于本次进程）" -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { $env:MYSQL_PWD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

function Invoke-Sql {
    param([string]$Sql, [string]$Database = '')
    $mysqlArgs = @('--protocol=TCP', "--host=$DbHost", "--port=$DbPort", "--user=$DbUser", '--default-character-set=utf8mb4', '--batch', '--skip-column-names')
    if ($Database) { $mysqlArgs += "--database=$Database" }
    $output = $Sql | & $MysqlExe @mysqlArgs 2>&1
    if ($LASTEXITCODE -ne 0) { throw "SQL 执行失败：$output" }
    return ($output -join "`n")
}

function Invoke-SqlFile {
    param([string]$Path, [string]$Database)
    $sql = [IO.File]::ReadAllText($Path)
    Invoke-Sql $sql $Database | Out-Null
}

function Assert-Contains {
    param([string]$Text, [string]$Needle, [string]$Label)
    if ($Text -notmatch [regex]::Escape($Needle)) { throw "$Label 校验失败：缺少 $Needle" }
}

function Assert-NotContains {
    param([string]$Text, [string]$Needle, [string]$Label)
    if ($Text -match [regex]::Escape($Needle)) { throw "$Label 校验失败：仍存在 $Needle" }
}

function Reset-RehearsalDb {
    Invoke-Sql "DROP DATABASE IF EXISTS ``$RehearsalDb``; CREATE DATABASE ``$RehearsalDb`` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" | Out-Null
}

function New-SchemaMigrationTable {
    Invoke-Sql @'
CREATE TABLE IF NOT EXISTS `schema_migration` (
    `version` INT NOT NULL,
    `description` VARCHAR(200) NOT NULL,
    `script` VARCHAR(255) NOT NULL,
    `checksum` VARCHAR(64) NOT NULL,
    `success` TINYINT NOT NULL DEFAULT 0,
    `execution_ms` INT NOT NULL DEFAULT 0,
    `installed_on` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_on` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
'@ $RehearsalDb | Out-Null
}

function Invoke-Migration {
    param([string]$Path)
    $name = Split-Path $Path -Leaf
    if ($name -notmatch '^V([0-9]+)__([A-Za-z0-9_]+)\.sql$') { throw "非法迁移文件名：$name" }
    $version = [int]$Matches[1]
    $description = $Matches[2]
    $hash = (Get-FileHash -Algorithm SHA256 -Path $Path).Hash.ToLower()

    Invoke-SqlFile $Path $RehearsalDb
    Invoke-Sql "INSERT INTO schema_migration (version,description,script,checksum,success,execution_ms) VALUES ($version,'$description','$name','$hash',1,0) ON DUPLICATE KEY UPDATE description='$description',script='$name',checksum='$hash',success=1;" $RehearsalDb | Out-Null
}

function Write-SchemaMigrationBaseline {
    param([int[]]$Versions)
    New-SchemaMigrationTable
    foreach ($v in $Versions) {
        Invoke-Sql "INSERT INTO schema_migration (version,description,script,checksum,success,execution_ms) VALUES ($v,'legacy-baseline','BASELINE','BASELINE',1,0);" $RehearsalDb | Out-Null
    }
}

$migrationFiles = @(
    'V1__baseline_core_schema.sql',
    'V2__user_table.sql',
    'V3__ai_key_cipher.sql',
    'V4__query_indexes.sql',
    'V5__async_task.sql',
    'V6__audit_fields.sql',
    'V7__public_workspace.sql',
    'V8__public_hardening.sql'
)

try {
    # ================= 场景 A：空库全量初始化 + 重复执行 =================
    Write-Host '[A1] 创建临时库并从 V1 全量初始化...'
    Reset-RehearsalDb
    New-SchemaMigrationTable
    foreach ($name in $migrationFiles) {
        Invoke-Migration (Join-Path $migrationDir $name)
    }

    $appliedA = Invoke-Sql 'SELECT COUNT(*) FROM schema_migration WHERE success=1;' $RehearsalDb
    if ($appliedA -ne '8') { throw "空库迁移记录数量异常：$appliedA（预期 8）" }

    $tables = Invoke-Sql 'SHOW TABLES;' $RehearsalDb
    foreach ($t in @('dataset','dataset_column','clean_rule','clean_log','ai_config','ai_prompt_template','ai_generation_log','sys_config','user','async_task','anon_session','cleanup_item','ai_challenge','chart_view','export_record','anon_usage_daily','cleanup_log')) {
        Assert-Contains $tables $t '空库初始化表'
    }

    $aiColumns = Invoke-Sql 'SHOW COLUMNS FROM ai_config;' $RehearsalDb
    Assert-Contains $aiColumns 'api_key_cipher' 'V3 密文字段'
    Assert-Contains $aiColumns 'api_key_mask' 'V3 掩码字段'

    $datasetColumns = Invoke-Sql 'SHOW COLUMNS FROM dataset;' $RehearsalDb
    Assert-Contains $datasetColumns 'created_by' 'V6 审计字段'
    Assert-Contains $datasetColumns 'updated_by' 'V6 审计字段'
    Assert-Contains $datasetColumns 'owner_type' 'V7 归属字段'
    Assert-Contains $datasetColumns 'anon_session_id' 'V7 匿名会话字段'

    $cleanRuleColumns = Invoke-Sql 'SHOW COLUMNS FROM clean_rule;' $RehearsalDb
    Assert-Contains $cleanRuleColumns 'anon_session_id' 'V7 规则归属字段'

    $indexes = Invoke-Sql 'SHOW INDEX FROM dataset_column;' $RehearsalDb
    Assert-Contains $indexes 'idx_dataset_column_order' 'V4 索引'

    $challengeColumns = Invoke-Sql 'SHOW COLUMNS FROM ai_challenge;' $RehearsalDb
    Assert-Contains $challengeColumns 'purpose' 'V8 挑战用途字段'
    $challengeIndexes = Invoke-Sql 'SHOW INDEX FROM ai_challenge;' $RehearsalDb
    Assert-Contains $challengeIndexes 'idx_purpose_expires' 'V8 挑战索引'

    Write-Host '[A2] 写入业务标记数据，重复执行全部迁移...'
    Invoke-Sql "INSERT INTO dataset (name,file_name,file_size,file_type,table_name,row_count,column_count,status) VALUES ('rehearsal-a','a.csv',1,'csv','rehearsal_a',0,0,1);" $RehearsalDb | Out-Null
    foreach ($name in $migrationFiles) {
        Invoke-Migration (Join-Path $migrationDir $name)
    }

    $markerCount = Invoke-Sql "SELECT COUNT(*) FROM dataset WHERE table_name='rehearsal_a';" $RehearsalDb
    if ($markerCount -ne '1') { throw '重复执行迁移后业务标记数据丢失或重复' }

    $promptCount = Invoke-Sql 'SELECT COUNT(*) FROM ai_prompt_template;' $RehearsalDb
    if ($promptCount -ne '5') { throw "重复执行后内置模板数量异常：$promptCount（预期 5）" }

    Write-Host '[A3] 校验 V6 重复执行不新增列、不报错...'
    $auditCount = Invoke-Sql "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='$RehearsalDb' AND TABLE_NAME='dataset' AND COLUMN_NAME IN ('created_by','updated_by');" $RehearsalDb
    if ($auditCount -ne '2') { throw "审计字段数量异常：$auditCount" }

    Write-Host '[A4] 验证 R4/R5/R6 回滚脚本...'
    Invoke-SqlFile (Join-Path $rollbackDir 'R4__query_indexes.sql') $RehearsalDb
    $indexesAfter = Invoke-Sql 'SHOW INDEX FROM dataset_column;' $RehearsalDb
    Assert-NotContains $indexesAfter 'idx_dataset_column_order' 'R4 索引回滚'

    Invoke-SqlFile (Join-Path $rollbackDir 'R5__async_task.sql') $RehearsalDb
    $asyncTable = Invoke-Sql "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='$RehearsalDb' AND TABLE_NAME='async_task';" $RehearsalDb
    if ($asyncTable -ne '0') { throw 'R5 未删除 async_task' }

    Invoke-SqlFile (Join-Path $rollbackDir 'R6__audit_fields.sql') $RehearsalDb
    $auditAfter = Invoke-Sql 'SHOW COLUMNS FROM dataset;' $RehearsalDb
    Assert-NotContains $auditAfter 'created_by' 'R6 审计字段回滚'

    Write-Host '[A5] 验证 R7/R8 回滚脚本（仅临时库）...'
    Invoke-SqlFile (Join-Path $rollbackDir 'R8__public_hardening.sql') $RehearsalDb
    $purposeAfter = Invoke-Sql "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='$RehearsalDb' AND TABLE_NAME='ai_challenge' AND COLUMN_NAME='purpose';" $RehearsalDb
    if ($purposeAfter -ne '0') { throw 'R8 未删除 ai_challenge.purpose' }

    Invoke-SqlFile (Join-Path $rollbackDir 'R7__public_workspace.sql') $RehearsalDb
    $anonTableAfter = Invoke-Sql "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='$RehearsalDb' AND TABLE_NAME='anon_session';" $RehearsalDb
    if ($anonTableAfter -ne '0') { throw 'R7 未删除 anon_session' }
    $ownerAfter = Invoke-Sql "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='$RehearsalDb' AND TABLE_NAME='dataset' AND COLUMN_NAME='owner_type';" $RehearsalDb
    if ($ownerAfter -ne '0') { throw 'R7 未删除 dataset.owner_type' }

    # ================= 场景 B：存量库基线 + 仅执行增量 =================
    Write-Host '[B1] 模拟存量库（V1+V2 已存在，无迁移历史），写入业务数据...'
    Reset-RehearsalDb
    Invoke-SqlFile (Join-Path $migrationDir 'V1__baseline_core_schema.sql') $RehearsalDb
    Invoke-SqlFile (Join-Path $migrationDir 'V2__user_table.sql') $RehearsalDb
    Invoke-Sql "INSERT INTO dataset (name,file_name,file_size,file_type,table_name,row_count,column_count,status) VALUES ('rehearsal-b','b.csv',1,'csv','rehearsal_b',0,0,1);" $RehearsalDb | Out-Null
    Write-SchemaMigrationBaseline @(1, 2)

    Write-Host '[B2] 仅执行 V3–V8，并重复执行一次...'
    foreach ($name in @('V3__ai_key_cipher.sql','V4__query_indexes.sql','V5__async_task.sql','V6__audit_fields.sql','V7__public_workspace.sql','V8__public_hardening.sql')) {
        Invoke-Migration (Join-Path $migrationDir $name)
    }
    $appliedAfterFirst = Invoke-Sql 'SELECT COUNT(*) FROM schema_migration WHERE success=1;' $RehearsalDb
    if ($appliedAfterFirst -ne '8') { throw "基线后迁移记录数量异常：$appliedAfterFirst（预期 8）" }

    foreach ($name in @('V3__ai_key_cipher.sql','V4__query_indexes.sql','V5__async_task.sql','V6__audit_fields.sql','V7__public_workspace.sql','V8__public_hardening.sql')) {
        Invoke-Migration (Join-Path $migrationDir $name)
    }
    $appliedAfterSecond = Invoke-Sql 'SELECT COUNT(*) FROM schema_migration WHERE success=1;' $RehearsalDb
    if ($appliedAfterSecond -ne '8') { throw "重复执行后迁移记录数量异常：$appliedAfterSecond" }

    $markerB = Invoke-Sql "SELECT COUNT(*) FROM dataset WHERE table_name='rehearsal_b';" $RehearsalDb
    if ($markerB -ne '1') { throw '存量库增量迁移后业务数据丢失或重复' }

    $v3 = Invoke-Sql "SELECT COUNT(*) FROM schema_migration WHERE version=3 AND success=1;" $RehearsalDb
    if ($v3 -ne '1') { throw 'V3 增量迁移记录缺失' }

    Write-Host '[B3] 校验 V1/V2 未被重新执行（无重复模板）...'
    $promptCountB = Invoke-Sql 'SELECT COUNT(*) FROM ai_prompt_template;' $RehearsalDb
    if ($promptCountB -ne '5') { throw "存量库模板数量异常：$promptCountB（预期 5）" }

    # ================= 场景 C：失败/中断后重试 =================
    Write-Host '[C1] 模拟“DDL 已生效但迁移记录丢失”，重跑 V7/V8 必须安全收敛...'
    Invoke-Sql "DELETE FROM schema_migration WHERE version IN (7,8);" $RehearsalDb | Out-Null
    foreach ($name in @('V7__public_workspace.sql','V8__public_hardening.sql')) {
        Invoke-Migration (Join-Path $migrationDir $name)
    }
    $purposeCols = Invoke-Sql "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='$RehearsalDb' AND TABLE_NAME='ai_challenge' AND COLUMN_NAME='purpose';" $RehearsalDb
    if ($purposeCols -ne '1') { throw "重试后 purpose 列数量异常：$purposeCols" }
    $purposeIdx = Invoke-Sql "SELECT COUNT(DISTINCT INDEX_NAME) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA='$RehearsalDb' AND TABLE_NAME='ai_challenge' AND INDEX_NAME='idx_purpose_expires';" $RehearsalDb
    if ($purposeIdx -ne '1') { throw "重试后 idx_purpose_expires 索引数量异常：$purposeIdx" }

    Write-Host '[C2] 校验重试未破坏存量业务数据与既有列...'
    $markerC = Invoke-Sql "SELECT COUNT(*) FROM dataset WHERE table_name='rehearsal_b';" $RehearsalDb
    if ($markerC -ne '1') { throw '重试后业务标记数据丢失或重复' }
    $ownerCols = Invoke-Sql "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='$RehearsalDb' AND TABLE_NAME='dataset' AND COLUMN_NAME='owner_type';" $RehearsalDb
    if ($ownerCols -ne '1') { throw '重试后 dataset.owner_type 列异常' }

    Write-Host '[清理] 删除临时库...'
    Invoke-Sql "DROP DATABASE IF EXISTS ``$RehearsalDb``;" | Out-Null

    Write-Host '演练通过：空库初始化、重复执行幂等、存量基线增量、V7/V8 仅增量、业务数据保留、失败重试收敛、R4/R5/R6/R7/R8 回滚均正常。' -ForegroundColor Green
}
catch {
    Write-Host "演练失败：$($_.Exception.Message)" -ForegroundColor Red
    try { Invoke-Sql "DROP DATABASE IF EXISTS ``$RehearsalDb``;" | Out-Null } catch { }
    exit 1
}
