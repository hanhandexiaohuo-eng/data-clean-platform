# ============================================================
# Phase 3 cleanup fault-injection acceptance
#
# Simulates the three required failure modes against a running public instance:
#   A) dynamic table already dropped, dataset metadata remains
#   B) dataset metadata already deleted, physical table remains (orphan)
#   C) process interrupted mid-purge (cleanup_state=purging, stale claim)
# and verifies that a later cleanup round converges without touching private data.
#
# Preconditions:
#   - APP_MODE=public, service reachable through Nginx ( -BaseUrl / -PublicOrigin ).
#   - PUBLIC_CLEANUP_ENABLED=true.
#   - Set PUBLIC_CLEANUP_INTERVAL_MINUTES=1 on the test instance so the scheduled
#     cleanup runs frequently; keep PUBLIC_CLEANUP_GRACE_MINUTES=60 (default) so the
#     script can force expiry by SQL.
#   - A maintenance window is recommended: do NOT run on production data.
#   - MySQL client available; password supplied via -DbPassword or prompt.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\phase3-cleanup-faults.ps1 `
#       -BaseUrl "http://localhost:8080" -PublicOrigin "http://localhost:5173" `
#       -DbUser root -DbName dataclean
#
# Exit code: 0 = all checks passed, 1 = failures, 2 = prerequisites missing.
# ============================================================

[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost",
    [string]$PublicOrigin = "",
    [string]$MysqlExe = "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe",
    [string]$DbHost = "127.0.0.1",
    [int]$DbPort = 3306,
    [string]$DbUser = "root",
    [string]$DbName = "dataclean",
    [string]$DbPassword = $env:DB_PASSWORD,
    [switch]$AllowEmptyPassword,
    [ValidateSet("A", "B", "C", "All")]
    [string]$Scenario = "All",
    [int]$WaitSeconds = 300
)

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
if ([string]::IsNullOrWhiteSpace($PublicOrigin)) { $PublicOrigin = $BaseUrl }
$BaseUrl = $BaseUrl.TrimEnd('/')
$PublicOrigin = $PublicOrigin.TrimEnd('/')

if (-not (Test-Path $MysqlExe)) {
    Write-Host "mysql client not found: $MysqlExe" -ForegroundColor Red
    exit 2
}

if ([string]::IsNullOrWhiteSpace($DbPassword)) {
    if (-not $AllowEmptyPassword) {
        $sec = Read-Host "MySQL password for $DbUser" -AsSecureString
        $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
        try { $DbPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
    }
}

$script:pass = 0
$script:fail = 0

function Check([string]$Name, [bool]$Ok, [string]$Detail = "") {
    if ($Ok) {
        $script:pass++
        Write-Host "[PASS] $Name" -ForegroundColor Green
    } else {
        $script:fail++
        Write-Host "[FAIL] $Name $Detail" -ForegroundColor Red
    }
}

function Invoke-Sql([string]$Sql) {
    $env:MYSQL_PWD = $DbPassword
    try {
        $out = & $MysqlExe --protocol=TCP --host=$DbHost --port=$DbPort --user=$DbUser -D $DbName `
            --default-character-set=utf8mb4 -N -B -e $Sql 2>&1
        if ($LASTEXITCODE -ne 0) { throw "mysql failed: $out" }
        return @($out)
    } finally {
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    }
}

function Scalar-Sql([string]$Sql) {
    $rows = Invoke-Sql $Sql
    if ($null -eq $rows) { return "" }
    # PowerShell 会把单元素数组展开成字符串；直接索引字符串会取到第一个字符
    if ($rows -is [array]) {
        if ($rows.Count -eq 0) { return "" }
        return "$($rows[0])".Trim()
    }
    return "$rows".Trim()
}

function New-BrowserSession {
    return New-Object Microsoft.PowerShell.Commands.WebRequestSession
}

function Invoke-Api {
    param($Session, [string]$Method, [string]$Path, $Body = $null,
          [string]$ContentType = $null, [hashtable]$Headers = @{})
    $p = @{
        Uri = ($BaseUrl + $Path); Method = $Method; WebSession = $Session
        UseBasicParsing = $true; TimeoutSec = 30
    }
    if ($Headers.Count -gt 0) { $p.Headers = $Headers }
    if ($null -ne $Body) { $p.Body = $Body }
    if ($ContentType) { $p.ContentType = $ContentType }
    try {
        $r = Invoke-WebRequest @p
        return [pscustomobject]@{ Status = [int]$r.StatusCode; Body = $r.Content; Headers = $r.Headers }
    } catch {
        $resp = $_.Exception.Response
        if ($null -eq $resp) { return [pscustomobject]@{ Status = 0; Body = $_.Exception.Message; Headers = @{} } }
        $body = ""
        if ($null -ne $_.ErrorDetails -and $null -ne $_.ErrorDetails.Message) {
            $body = $_.ErrorDetails.Message
        }
        return [pscustomobject]@{ Status = [int]$resp.StatusCode; Body = $body; Headers = $resp.Headers }
    }
}

function New-Multipart {
    param([hashtable]$Fields, [string]$FileField, [string]$FileName, [byte[]]$FileBytes)
    $boundary = [Guid]::NewGuid().ToString('N')
    $enc = [Text.Encoding]::UTF8
    $ms = New-Object System.IO.MemoryStream
    foreach ($key in $Fields.Keys) {
        $part = "--$boundary`r`nContent-Disposition: form-data; name=`"$key`"`r`n`r`n$($Fields[$key])`r`n"
        $b = $enc.GetBytes($part); $ms.Write($b, 0, $b.Length)
    }
    $head = "--$boundary`r`nContent-Disposition: form-data; name=`"$FileField`"; filename=`"$FileName`"`r`nContent-Type: text/csv`r`n`r`n"
    $hb = $enc.GetBytes($head); $ms.Write($hb, 0, $hb.Length)
    $ms.Write($FileBytes, 0, $FileBytes.Length)
    $tail = "`r`n--$boundary--`r`n"
    $tb = $enc.GetBytes($tail); $ms.Write($tb, 0, $tb.Length)
    return @{ Bytes = $ms.ToArray(); ContentType = "multipart/form-data; boundary=$boundary" }
}

function Get-HeaderValue {
    param($Headers, [string]$Name)
    if ($null -eq $Headers) { return $null }
    try {
        $v = $Headers[$Name]
        if ($null -eq $v) { return $null }
        if ($v -is [array]) { return ($v -join ",") }
        return "$v"
    } catch { return $null }
}

function Get-Sha256Hex {
    param([string]$Text)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text))
        return (-join ($bytes | ForEach-Object { $_.ToString('x2') }))
    } finally { $sha.Dispose() }
}

function New-TestDataset([string]$Name) {
    $s = New-BrowserSession
    # 受控会话引导：唯一建会话入口，必要时完成一次性挑战
    $sess = Invoke-Api $s "POST" "/api/public/session" $null $null @{ Origin = $PublicOrigin }
    if ($sess.Status -eq 428) {
        $ch = Invoke-Api $s "POST" "/api/public/session/challenge" $null $null @{ Origin = $PublicOrigin }
        if ($ch.Status -ne 200) { throw "session challenge failed: status=$($ch.Status)" }
        $chJson = $ch.Body | ConvertFrom-Json
        $sess = Invoke-Api $s "POST" "/api/public/session" $null $null @{ Origin = $PublicOrigin; "X-Session-Challenge" = $chJson.token }
    }
    if ($sess.Status -ne 200) { throw "session bootstrap failed: status=$($sess.Status)" }

    $setCookie = Get-HeaderValue $sess.Headers "Set-Cookie"
    if ([string]::IsNullOrWhiteSpace($setCookie) -or $setCookie -notmatch '=([^;,\s]+)') {
        throw "session cookie missing"
    }
    $tokenHash = Get-Sha256Hex $Matches[1]
    $sessionId = [long](Scalar-Sql "SELECT id FROM anon_session WHERE token_hash = '$tokenHash' LIMIT 1")

    $csv = [Text.Encoding]::UTF8.GetBytes("name,amount`r`nA,1`r`nB,2`r`n")
    $mp = New-Multipart @{ name = $Name; description = "cleanup fault test"; hasHeader = "true"; encoding = "UTF-8" } "file" "$Name.csv" $csv
    $r = Invoke-Api $s "POST" "/api/dataset/upload" $mp.Bytes $mp.ContentType @{ Origin = $PublicOrigin }
    if ($r.Status -ne 200) { throw "upload failed: status=$($r.Status) body=$($r.Body)" }
    $json = $r.Body | ConvertFrom-Json
    return [pscustomobject]@{
        Session   = $s
        DatasetId = [long]$json.data.id
        TableName = [string]$json.data.tableName
        SessionId = $sessionId
    }
}

function Expire-Session([long]$SessionId) {
    Invoke-Sql "UPDATE anon_session SET revoked = 1, expires_at = DATE_SUB(NOW(), INTERVAL 2 HOUR) WHERE id = $SessionId" | Out-Null
}

function Wait-SessionGone([long]$SessionId, [int]$TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $count = [long](Scalar-Sql "SELECT COUNT(*) FROM anon_session WHERE id = $SessionId")
        if ($count -eq 0) { return $true }
        Start-Sleep -Seconds 5
    }
    return $false
}

function Table-Exists([string]$TableName) {
    $safe = $TableName.Replace("'", "''")
    $count = [long](Scalar-Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = '$safe'")
    return ($count -gt 0)
}

function Get-PrivateSnapshot {
    $ids = Scalar-Sql "SELECT COALESCE(GROUP_CONCAT(id ORDER BY id), '') FROM dataset WHERE owner_type = 'private'"
    $tables = Scalar-Sql "SELECT COALESCE(GROUP_CONCAT(table_name ORDER BY table_name), '') FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN (SELECT table_name FROM dataset WHERE owner_type = 'private')"
    $demoCount = [long](Scalar-Sql "SELECT COUNT(*) FROM dataset WHERE owner_type = 'demo'")
    return [pscustomobject]@{ Ids = $ids; Tables = $tables; DemoCount = $demoCount }
}

function Assert-PrivateUnchanged($Before, $After, [string]$Prefix) {
    Check "$Prefix private dataset ids unchanged" ($Before.Ids -eq $After.Ids) "before=$($Before.Ids) after=$($After.Ids)"
    Check "$Prefix private physical tables unchanged" ($Before.Tables -eq $After.Tables) "before=$($Before.Tables) after=$($After.Tables)"
    Check "$Prefix demo dataset count unchanged" ($Before.DemoCount -eq $After.DemoCount) "before=$($Before.DemoCount) after=$($After.DemoCount)"
}

Write-Host "== Phase 3 cleanup fault injection: $BaseUrl ==" -ForegroundColor Cyan

# ---------- prerequisite: public mode ----------
$cfg = Invoke-Api (New-BrowserSession) "GET" "/api/public/config"
if ($cfg.Status -ne 200) { Write-Host "cannot reach /api/public/config" -ForegroundColor Red; exit 2 }
$cfgJson = $cfg.Body | ConvertFrom-Json
if (-not $cfgJson.publicMode) { Write-Host "target is not in public mode" -ForegroundColor Red; exit 2 }

$privateBefore = Get-PrivateSnapshot

$scenarios = @()
if ($Scenario -eq "All") { $scenarios = @("A", "B", "C") } else { $scenarios = @($Scenario) }

foreach ($sc in $scenarios) {
    Write-Host "`n-- Scenario $sc --" -ForegroundColor Cyan
    $ctx = New-TestDataset ("fault-" + $sc.ToLower() + "-" + (Get-Date -Format "HHmmss"))
    Write-Host "session=$($ctx.SessionId) dataset=$($ctx.DatasetId) table=$($ctx.TableName)"
    $safeTable = $ctx.TableName.Replace("'", "''")

    switch ($sc) {
        "A" {
            # table already dropped, metadata still present
            Invoke-Sql "DROP TABLE IF EXISTS ``$safeTable``" | Out-Null
            Check "A precondition: physical table removed" (-not (Table-Exists $ctx.TableName))
            Expire-Session $ctx.SessionId
            $gone = Wait-SessionGone $ctx.SessionId $WaitSeconds
            Check "A session converged and was purged" $gone
            $meta = [long](Scalar-Sql "SELECT COUNT(*) FROM dataset WHERE id = $($ctx.DatasetId)")
            Check "A dataset metadata removed" ($meta -eq 0) "remaining=$meta"
        }
        "B" {
            # metadata already deleted, physical table remains
            Invoke-Sql "DELETE FROM dataset_column WHERE dataset_id = $($ctx.DatasetId)" | Out-Null
            Invoke-Sql "DELETE FROM dataset WHERE id = $($ctx.DatasetId)" | Out-Null
            Check "B precondition: dataset metadata removed" ([long](Scalar-Sql "SELECT COUNT(*) FROM dataset WHERE id = $($ctx.DatasetId)") -eq 0)
            Expire-Session $ctx.SessionId
            $gone = Wait-SessionGone $ctx.SessionId $WaitSeconds
            Check "B session converged and was purged" $gone
            Check "B orphan table is retained (never guessed/dropped)" (Table-Exists $ctx.TableName)
            Invoke-Sql "DROP TABLE IF EXISTS ``$safeTable``" | Out-Null
            Write-Host "  orphan table cleaned manually after verification: $($ctx.TableName)"
        }
        "C" {
            # interrupted purge: stale purging state + one pending item
            Expire-Session $ctx.SessionId
            Invoke-Sql "UPDATE anon_session SET cleanup_state = 'purging', purge_started_at = DATE_SUB(NOW(), INTERVAL 20 MINUTE) WHERE id = $($ctx.SessionId)" | Out-Null
            Invoke-Sql "INSERT IGNORE INTO cleanup_item(anon_session_id, resource_type, resource_key, state, updated_at) VALUES($($ctx.SessionId), 'dataset_table', '$safeTable', 'pending', NOW())" | Out-Null
            $gone = Wait-SessionGone $ctx.SessionId $WaitSeconds
            Check "C stale purging session was reclaimed and purged" $gone
            $meta = [long](Scalar-Sql "SELECT COUNT(*) FROM dataset WHERE id = $($ctx.DatasetId)")
            Check "C dataset metadata removed after resume" ($meta -eq 0) "remaining=$meta"
            Check "C dynamic table dropped after resume" (-not (Table-Exists $ctx.TableName))
        }
    }

    $after = Get-PrivateSnapshot
    Assert-PrivateUnchanged $privateBefore $after ("[$sc]")
}

Write-Host ""
Write-Host "Result: PASS=$($script:pass) FAIL=$($script:fail)" -ForegroundColor Cyan
if ($script:fail -gt 0) { exit 1 } else { exit 0 }
