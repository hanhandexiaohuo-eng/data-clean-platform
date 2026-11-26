# DataClean AI Key 迁移演练脚本（隔离临时库，不触碰真实数据）
#
# 用途：在本地 MySQL 的临时库 dataclean_migration_rehearsal 中演练
#       sql/migration_v4_ai_key_cipher.sql 的增量迁移、重复执行与回滚。
#
# 使用方式（在项目根目录执行）：
#   powershell -ExecutionPolicy Bypass -File .\scripts\rehearse-migration.ps1
#
# 说明：
#   - 仅在临时库中建表、插入示例数据、执行迁移与回滚，不会连接 dataclean 真实库；
#   - 密码通过 Read-Host 输入并只写入当前进程的 MYSQL_PWD，不落盘、不回显；
#   - 结束时删除临时库 dataclean_migration_rehearsal。

param(
    [string]$MysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe',
    [string]$DbHost = '127.0.0.1',
    [int]$DbPort = 3306,
    [string]$DbUser = 'root'
)

$ErrorActionPreference = 'Stop'
$RehearsalDb = 'dataclean_migration_rehearsal'
if ($RehearsalDb -ne 'dataclean_migration_rehearsal') { throw '临时库名称被修改，已中止' }
if (-not (Test-Path $MysqlExe)) { throw "未找到 mysql.exe：$MysqlExe，可用 -MysqlExe 指定路径" }

if (-not $env:MYSQL_PWD) {
    $secure = Read-Host "请输入 MySQL 账号 $DbUser 的密码（仅用于本次进程）" -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { $env:MYSQL_PWD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$migrationFile = Join-Path $projectRoot 'sql\migration_v4_ai_key_cipher.sql'
if (-not (Test-Path $migrationFile)) { throw "未找到迁移脚本：$migrationFile" }

function Invoke-Sql {
    param([string]$Sql, [string]$Database = '')
    $mysqlArgs = @('--protocol=TCP', "-h$DbHost", "-P$DbPort", "-u$DbUser", '--batch')
    if ($Database) { $mysqlArgs += "--database=$Database" }
    $output = $Sql | & $MysqlExe @mysqlArgs 2>&1
    if ($LASTEXITCODE -ne 0) { throw "SQL 执行失败：$output" }
    return ($output -join "`n")
}

try {
    Write-Host '[1/6] 创建临时库...'
    Invoke-Sql "DROP DATABASE IF EXISTS ``$RehearsalDb``; CREATE DATABASE ``$RehearsalDb`` DEFAULT CHARACTER SET utf8mb4;" | Out-Null

    Write-Host '[2/6] 创建迁移前的旧版 ai_config（仅 api_key 明文字段）...'
    Invoke-Sql @"
CREATE TABLE ``$RehearsalDb``.``ai_config`` (
    ``id`` BIGINT NOT NULL AUTO_INCREMENT,
    ``name`` VARCHAR(100) NOT NULL,
    ``api_url`` VARCHAR(500) NOT NULL,
    ``api_key`` VARCHAR(500) NOT NULL,
    ``model_name`` VARCHAR(100) NOT NULL,
    ``temperature`` DECIMAL(3,2) NOT NULL DEFAULT 0.70,
    ``max_tokens`` INT NOT NULL DEFAULT 2048,
    ``timeout`` INT NOT NULL DEFAULT 30000,
    ``is_default`` TINYINT NOT NULL DEFAULT 0,
    ``status`` TINYINT NOT NULL DEFAULT 1,
    ``created_at`` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ``updated_at`` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (``id``)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO ``$RehearsalDb``.``ai_config`` (``name``,``api_url``,``api_key``,``model_name``)
VALUES ('rehearsal-a','https://example.com/a','placeholder-key-aaaa','test-model'),
       ('rehearsal-b','https://example.com/b','placeholder-key-bbbb','test-model');
"@ | Out-Null

    Write-Host '[3/6] 第一次执行迁移...'
    $migrationSql = Get-Content -Raw -Encoding UTF8 $migrationFile
    Invoke-Sql $migrationSql $RehearsalDb | Out-Null

    Write-Host '[4/6] 第二次执行迁移（验证可重复执行）...'
    Invoke-Sql $migrationSql $RehearsalDb | Out-Null

    Write-Host '[5/6] 校验列结构...'
    $columns = Invoke-Sql "SHOW COLUMNS FROM ``$RehearsalDb``.``ai_config``;" 
    $columns

    $hasCipher = $columns -match 'api_key_cipher'
    $hasMask = $columns -match 'api_key_mask'
    if (-not $hasCipher -or -not $hasMask) { throw '迁移后缺少 api_key_cipher 或 api_key_mask 列' }

    Write-Host '[6/6] 演练原地回滚（当前无密文，可安全删除新增列）并清理临时库...'
    Invoke-Sql @"
ALTER TABLE ``$RehearsalDb``.``ai_config`` DROP COLUMN ``api_key_mask``;
ALTER TABLE ``$RehearsalDb``.``ai_config`` DROP COLUMN ``api_key_cipher``;
ALTER TABLE ``$RehearsalDb``.``ai_config`` MODIFY COLUMN ``api_key`` VARCHAR(500) NULL;
"@ | Out-Null

    Invoke-Sql "DROP DATABASE IF EXISTS ``$RehearsalDb``;" | Out-Null
    Write-Host '演练通过：迁移可重复执行、列结构正确、回滚 DDL 可用。' -ForegroundColor Green
}
catch {
    Write-Host "演练失败：$($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
