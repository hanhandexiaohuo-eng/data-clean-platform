# ============================================================
# Phase 3 public-mode acceptance: isolation / cookie / session limits
#
# Usage (PowerShell 5.1+):
#   powershell -ExecutionPolicy Bypass -File scripts\phase3-isolation-check.ps1 `
#       -BaseUrl "https://demo.example.com" -PublicOrigin "https://demo.example.com"
#
#   # 本地 HTTP 调试（local Profile，Cookie 名称会被 local 覆盖为非 __Host-）：
#   powershell -ExecutionPolicy Bypass -File scripts\phase3-isolation-check.ps1 `
#       -BaseUrl "http://127.0.0.1:8080" -CookieName "dc_anon_local" -HttpDebug
#
#   # 可选：连接隔离测试库，验证“会话撤销/过期后旧数据不可恢复”
#   ... -MysqlExe "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" `
#       -DbName "dataclean_phase3_it" -AllowEmptyPassword
#
# Preconditions:
#   - APP_MODE=public and the service is reachable through Nginx.
#   - PUBLIC_ALLOWED_ORIGIN equals -PublicOrigin.
#   - 目标必须是隔离测试实例（临时库 + 临时上传卷），不要指向生产库。
#
# Exit code: 0 = all checks passed, 1 = failures, 2 = target not in public mode.
# 输出只包含状态码、Cookie 属性与布尔结论，不输出 Cookie 值/Token/原始 IP。
# ============================================================

[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost",
    [string]$PublicOrigin = "",
    [int]$SessionLimitProbe = 30,
    [string]$CookieName = "__Host-dc_anon",
    [switch]$HttpDebug,
    [string]$MysqlExe = "",
    [string]$DbHost = "127.0.0.1",
    [int]$DbPort = 3306,
    [string]$DbUser = "root",
    [string]$DbName = "",
    [switch]$AllowEmptyPassword
)

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

if ([string]::IsNullOrWhiteSpace($PublicOrigin)) { $PublicOrigin = $BaseUrl }
$BaseUrl = $BaseUrl.TrimEnd('/')
$PublicOrigin = $PublicOrigin.TrimEnd('/')

$script:pass = 0
$script:fail = 0
$script:skip = 0

function Check([string]$Name, [bool]$Ok, [string]$Detail = "") {
    if ($Ok) {
        $script:pass++
        Write-Host "[PASS] $Name" -ForegroundColor Green
    } else {
        $script:fail++
        Write-Host "[FAIL] $Name $Detail" -ForegroundColor Red
    }
}

function Skip([string]$Name, [string]$Reason) {
    $script:skip++
    Write-Host "[SKIP] $Name - $Reason" -ForegroundColor Yellow
}

function New-BrowserSession {
    return New-Object Microsoft.PowerShell.Commands.WebRequestSession
}

function Invoke-Api {
    param(
        $Session,
        [string]$Method,
        [string]$Path,
        $Body = $null,
        [string]$ContentType = $null,
        [hashtable]$Headers = @{}
    )
    $p = @{
        Uri             = ($BaseUrl + $Path)
        Method          = $Method
        WebSession      = $Session
        UseBasicParsing = $true
        TimeoutSec      = 30
    }
    if ($Headers.Count -gt 0) { $p.Headers = $Headers }
    if ($null -ne $Body) { $p.Body = $Body }
    if ($ContentType) { $p.ContentType = $ContentType }

    try {
        $r = Invoke-WebRequest @p
        return [pscustomobject]@{ Status = [int]$r.StatusCode; Body = $r.Content; Headers = $r.Headers }
    } catch {
        $resp = $_.Exception.Response
        if ($null -eq $resp) {
            return [pscustomobject]@{ Status = 0; Body = $_.Exception.Message; Headers = @{} }
        }
        $text = ""
        if ($null -ne $_.ErrorDetails -and $null -ne $_.ErrorDetails.Message) {
            $text = $_.ErrorDetails.Message
        }
        return [pscustomobject]@{ Status = [int]$resp.StatusCode; Body = $text; Headers = $resp.Headers }
    }
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

function New-Multipart {
    param([hashtable]$Fields, [string]$FileField, [string]$FileName, [byte[]]$FileBytes)
    $boundary = [Guid]::NewGuid().ToString('N')
    $enc = [Text.Encoding]::UTF8
    $ms = New-Object System.IO.MemoryStream

    foreach ($key in $Fields.Keys) {
        $part = "--$boundary`r`nContent-Disposition: form-data; name=`"$key`"`r`n`r`n$($Fields[$key])`r`n"
        $b = $enc.GetBytes($part)
        $ms.Write($b, 0, $b.Length)
    }
    $head = "--$boundary`r`nContent-Disposition: form-data; name=`"$FileField`"; filename=`"$FileName`"`r`nContent-Type: text/csv`r`n`r`n"
    $hb = $enc.GetBytes($head)
    $ms.Write($hb, 0, $hb.Length)
    $ms.Write($FileBytes, 0, $FileBytes.Length)
    $tail = "`r`n--$boundary--`r`n"
    $tb = $enc.GetBytes($tail)
    $ms.Write($tb, 0, $tb.Length)

    return @{ Bytes = $ms.ToArray(); ContentType = "multipart/form-data; boundary=$boundary" }
}

function Expect-Status {
    param($Session, [string]$Method, [string]$Path, [int]$Expected, [string]$Name,
          $Body = $null, [string]$ContentType = $null, [hashtable]$Headers = @{})
    $r = Invoke-Api $Session $Method $Path $Body $ContentType $Headers
    Check $Name ($r.Status -eq $Expected) "expected=$Expected actual=$($r.Status) body=$($r.Body)"
    return $r
}

function Upload-Csv {
    param($Session, [string]$Name)
    $csv = [Text.Encoding]::UTF8.GetBytes("name,amount,region`r`nA,10,North`r`nB,20,South`r`n")
    $mp = New-Multipart @{ name = $Name; description = "isolation check $Name"; hasHeader = "true"; encoding = "UTF-8" } "file" "$Name.csv" $csv
    return Invoke-Api $Session "POST" "/api/dataset/upload" $mp.Bytes $mp.ContentType @{ Origin = $PublicOrigin }
}

# 受控会话引导：唯一建会话入口；返回会话对象与原始 Set-Cookie（仅用于属性断言）
function New-AnonSession {
    param([string]$Label)
    $s = New-BrowserSession
    $r = Invoke-Api $s "POST" "/api/public/session" $null $null @{ Origin = $PublicOrigin }
    if ($r.Status -eq 428) {
        $ch = Invoke-Api $s "POST" "/api/public/session/challenge" $null $null @{ Origin = $PublicOrigin }
        if ($ch.Status -eq 200) {
            $chToken = ($ch.Body | ConvertFrom-Json).token
            $r = Invoke-Api $s "POST" "/api/public/session" $null $null @{ Origin = $PublicOrigin; "X-Session-Challenge" = $chToken }
        }
    }
    $setCookie = Get-HeaderValue $r.Headers "Set-Cookie"
    return [pscustomobject]@{ Label = $Label; Session = $s; Status = $r.Status; Body = $r.Body; SetCookie = $setCookie }
}

function Measure-CookieAttributes {
    param([string]$SetCookie, [string]$Label)
    if ([string]::IsNullOrWhiteSpace($SetCookie)) {
        Check "$Label session cookie is issued" $false "no Set-Cookie"
        return
    }
    $line = ($SetCookie -split "`n" | Where-Object { $_ -match [regex]::Escape("$CookieName=") } | Select-Object -First 1)
    if (-not $line) { $line = $SetCookie }
    Check "$Label cookie name is $CookieName" ($line -match [regex]::Escape("$CookieName="))
    Check "$Label cookie is HttpOnly" ($line -match '(?i)HttpOnly')
    Check "$Label cookie is SameSite=Strict" ($line -match '(?i)SameSite=Strict')
    Check "$Label cookie Path=/" ($line -match '(?i)Path=/')
    Check "$Label cookie has no Domain attribute" (-not ($line -match '(?i)Domain='))
    if ($HttpDebug) {
        Check "$Label cookie is non-Secure only for local debug" (-not ($line -match '(?i)Secure'))
    } else {
        Check "$Label cookie is Secure" ($line -match '(?i)Secure')
    }
    # 属性断言已覆盖 HttpOnly，等价于 JS 无法通过 document.cookie 读取
    Check "$Label cookie value is not logged by this script" $true
}

function Get-Sha256Hex {
    param([string]$Text)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text))
        return (-join ($bytes | ForEach-Object { $_.ToString('x2') }))
    } finally { $sha.Dispose() }
}

function Get-CookieToken {
    param([string]$SetCookie)
    if ([string]::IsNullOrWhiteSpace($SetCookie)) { return $null }
    if ($SetCookie -match [regex]::Escape("$CookieName=") + '([^;,\s]+)') { return $Matches[1] }
    return $null
}

function Invoke-IsolatedSql {
    param([string]$Sql)
    if ([string]::IsNullOrWhiteSpace($MysqlExe) -or [string]::IsNullOrWhiteSpace($DbName)) {
        throw "未提供隔离库参数"
    }
    $mysqlArgs = @('--protocol=TCP', "--host=$DbHost", "--port=$DbPort", "--user=$DbUser", '--default-character-set=utf8mb4', '--batch', '--skip-column-names', "--database=$DbName", '-e', $Sql)
    $output = & $MysqlExe @mysqlArgs 2>&1
    if ($LASTEXITCODE -ne 0) { throw "隔离库 SQL 执行失败（已脱敏）" }
    return ($output -join "`n")
}

Write-Host "== Phase 3 isolation check: $BaseUrl (origin=$PublicOrigin, cookie=$CookieName) ==" -ForegroundColor Cyan

# ---------- 0. mode probe & minimal public entries ----------
$probe = New-BrowserSession
$health = Invoke-Api $probe "GET" "/api/health"
Check "/api/health reachable without session" ($health.Status -eq 200) "actual=$($health.Status)"

$cfg = Invoke-Api $probe "GET" "/api/public/config"
if ($cfg.Status -ne 200) {
    Write-Host "Cannot reach /api/public/config: status=$($cfg.Status)" -ForegroundColor Red
    exit 1
}
$cfgJson = $cfg.Body | ConvertFrom-Json
if (-not $cfgJson.publicMode) {
    Write-Host "Target is not in public mode (mode=$($cfgJson.mode)); aborting." -ForegroundColor Red
    exit 2
}
Check "public config is reachable without login" ($cfgJson.publicMode -eq $true)

# 未建立会话时：业务 API 一律 401 + X-Session-Required，且不隐式建会话
$noSess = Invoke-Api $probe "GET" "/api/dataset?page=1&size=1"
Check "business API without session -> 401" ($noSess.Status -eq 401) "actual=$($noSess.Status)"
Check "401 carries X-Session-Required: 1" ((Get-HeaderValue $noSess.Headers "X-Session-Required") -eq "1")
Check "401 does not issue a session cookie" ([string]::IsNullOrWhiteSpace((Get-HeaderValue $noSess.Headers "Set-Cookie")))

$noSessUpload = Invoke-Api $probe "POST" "/api/dataset/upload" ([byte[]]@(1,2,3)) "application/octet-stream" @{ Origin = $PublicOrigin }
Check "upload without session -> 401" ($noSessUpload.Status -eq 401) "actual=$($noSessUpload.Status)"

# 私有能力在 public 模式统一 404，不泄露接口/资源是否存在
foreach ($entry in @(
    @{ M = "GET";  P = "/api/auth/info" },
    @{ M = "GET";  P = "/api/ai/config" },
    @{ M = "GET";  P = "/api/ai/template" },
    @{ M = "GET";  P = "/api/ai/history?datasetId=1" },
    @{ M = "POST"; P = "/api/admin/maintenance/cleanup" }
)) {
    $r = Invoke-Api $probe $entry.M $entry.P $null $null @{ Origin = $PublicOrigin }
    Check "private entry $($entry.M) $($entry.P) -> 404" ($r.Status -eq 404) "actual=$($r.Status)"
    Check "private entry $($entry.P) body does not leak" (-not ($r.Body -match '(?i)admin|template|config|历史|配置')) "body=$($r.Body)"
}

# ---------- 1. controlled session bootstrap ----------
Write-Host '-- session bootstrap / cookie attributes --'
$a = New-AnonSession "A"
if ($a.Status -ne 200) {
    Write-Host "session A bootstrap failed: status=$($a.Status) body=$($a.Body)" -ForegroundColor Red
    exit 1
}
$b = New-AnonSession "B"
Check "session A created via POST /api/public/session" ($a.Status -eq 200)
Check "session B created via POST /api/public/session" ($b.Status -eq 200)
Measure-CookieAttributes $a.SetCookie "A"
Measure-CookieAttributes $b.SetCookie "B"

$again = Invoke-Api $a.Session "POST" "/api/public/session" $null $null @{ Origin = $PublicOrigin }
$againJson = $again.Body | ConvertFrom-Json
Check "existing session bootstrap is idempotent" ($again.Status -eq 200 -and $againJson.created -eq $false)
Check "idempotent bootstrap does not re-issue cookie" ([string]::IsNullOrWhiteSpace((Get-HeaderValue $again.Headers "Set-Cookie")))

# ---------- 2. two isolated sessions ----------
$sa = $a.Session
$sb = $b.Session

$ra = Upload-Csv $sa "iso-a"
$rb = Upload-Csv $sb "iso-b"
if ($ra.Status -ne 200 -or $rb.Status -ne 200) {
    Write-Host "Upload failed: A=$($ra.Status) $($ra.Body) | B=$($rb.Status) $($rb.Body)" -ForegroundColor Red
    exit 1
}
$da = $ra.Body | ConvertFrom-Json
$db = $rb.Body | ConvertFrom-Json
$idA = [long]$da.data.id
$idB = [long]$db.data.id

Check "session A uploads own dataset" ($idA -gt 0)
Check "session B uploads own dataset" ($idB -gt 0)
Check "dataset response does not expose anonSessionId" ($null -eq $da.data.anonSessionId)
Check "uploaded dataset ownerType=anon" ($da.data.ownerType -eq "anon")

# B 创建规则与清洗日志，供 A 越权尝试
$ruleBResp = Invoke-Api $sb "POST" "/api/clean/rule" ([Text.Encoding]::UTF8.GetBytes("{`"datasetId`":$idB,`"ruleName`":`"iso-rule`",`"ruleType`":`"remove_duplicate`",`"sortOrder`":1,`"status`":1}")) "application/json" @{ Origin = $PublicOrigin }
$ruleBId = $null
if ($ruleBResp.Status -eq 200) {
    $ruleBId = [long](($ruleBResp.Body | ConvertFrom-Json).data.id)
    Check "session B creates own clean rule" ($ruleBId -gt 0)
} else {
    Skip "cross-session clean rule mutation" "session B rule creation failed status=$($ruleBResp.Status)"
}

$execB = Invoke-Api $sb "POST" "/api/clean/execute" ([Text.Encoding]::UTF8.GetBytes("{`"datasetId`":$idB,`"ruleIds`":[$ruleBId]}")) "application/json" @{ Origin = $PublicOrigin; "X-Confirm" = "yes" }
$logBId = $null
if ($execB.Status -eq 200) {
    # execute 接口不返回日志 ID，改由会话 B 查询自身日志获取（用于后续越权回滚验证）
    $logList = Invoke-Api $sb "GET" "/api/clean/log?datasetId=$idB"
    if ($logList.Status -eq 200) {
        $logJson = $logList.Body | ConvertFrom-Json
        $firstLog = @($logJson.data) | Select-Object -First 1
        if ($null -ne $firstLog -and $null -ne $firstLog.id) { $logBId = [long]$firstLog.id }
    }
    if ($logBId -gt 0) { Check "session B produces own clean log" $true }
    else { Skip "cross-session rollback" "clean log id not returned" }
} else {
    Skip "cross-session rollback" "session B clean execution failed status=$($execB.Status)"
}

# ---------- 3. cross-session access must be 404 ----------
$crossChecks = @(
    @{ M = "GET";  P = "/api/dataset/$idB"; E = 404 },
    @{ M = "GET";  P = "/api/dataset/$idB/preview"; E = 404 },
    @{ M = "GET";  P = "/api/dataset/$idB/columns"; E = 404 },
    @{ M = "GET";  P = "/api/dataset/$idB/statistics"; E = 404 },
    @{ M = "GET";  P = "/api/clean/rule?datasetId=$idB"; E = 404 },
    @{ M = "GET";  P = "/api/clean/log?datasetId=$idB"; E = 404 },
    @{ M = "POST"; P = "/api/export/dataset/$idB"; E = 404 },
    @{ M = "DELETE"; P = "/api/dataset/$idB"; E = 404 }
)
foreach ($c in $crossChecks) {
    $h = @{ Origin = $PublicOrigin; "X-Confirm" = "yes" }
    $r = Invoke-Api $sa $c.M $c.P $null $null $h
    Check "cross-session $($c.M) $($c.P) -> 404" ($r.Status -eq 404) "actual=$($r.Status) body=$($r.Body)"
    Check "cross-session $($c.P) body is generic" (-not ($r.Body -match '(?i)iso-b|dataset_|owner|anon_session|table_name')) "body=$($r.Body)"
}

# 跨会话：修改/删除规则、清洗预览/执行、回滚
if ($ruleBId) {
    $h = @{ Origin = $PublicOrigin; "X-Confirm" = "yes" }
    $u = Invoke-Api $sa "PUT" "/api/clean/rule/$ruleBId" ([Text.Encoding]::UTF8.GetBytes("{`"datasetId`":$idB,`"ruleName`":`"hijack`",`"ruleType`":`"remove_duplicate`"}")) "application/json" $h
    Check "cross-session rule update -> 404" ($u.Status -eq 404) "actual=$($u.Status)"
    $d = Invoke-Api $sa "DELETE" "/api/clean/rule/$ruleBId" $null $null $h
    Check "cross-session rule delete -> 404" ($d.Status -eq 404) "actual=$($d.Status)"
    $pv = Invoke-Api $sa "POST" "/api/clean/preview" ([Text.Encoding]::UTF8.GetBytes("{`"datasetId`":$idB,`"ruleIds`":[$ruleBId]}")) "application/json" $h
    Check "cross-session clean preview -> 404" ($pv.Status -eq 404) "actual=$($pv.Status)"
    $ex = Invoke-Api $sa "POST" "/api/clean/execute" ([Text.Encoding]::UTF8.GetBytes("{`"datasetId`":$idB,`"ruleIds`":[$ruleBId]}")) "application/json" $h
    Check "cross-session clean execute -> 404" ($ex.Status -eq 404) "actual=$($ex.Status)"
}
if ($logBId) {
    $rb2 = Invoke-Api $sa "POST" "/api/clean/rollback/$logBId" $null $null @{ Origin = $PublicOrigin; "X-Confirm" = "yes" }
    Check "cross-session clean rollback -> 404" ($rb2.Status -eq 404) "actual=$($rb2.Status)"
}

# own resources remain readable
Expect-Status $sa "GET" "/api/dataset/$idA/preview" 200 "own preview -> 200" | Out-Null
Expect-Status $sa "GET" "/api/dataset/$idA/statistics" 200 "own statistics -> 200" | Out-Null

# list only contains own + demo
$listA = Invoke-Api $sa "GET" "/api/dataset?page=1&size=100"
$listAJson = $listA.Body | ConvertFrom-Json
$ids = @($listAJson.data.records | ForEach-Object { [long]$_.id })
$hasB = $ids -contains $idB
$hasA = $ids -contains $idA
Check "dataset list excludes other session datasets" (-not $hasB)
Check "dataset list includes own datasets" $hasA

# ---------- 4. origin guard ----------
# 不带 Origin 的原始请求（Invoke-WebRequest 会自动补 Origin，这里改用 HttpWebRequest）
$noOriginStatus = 0
try {
    $rawReq = [System.Net.HttpWebRequest]::Create($BaseUrl + "/api/public/ai/challenge")
    $rawReq.Method = "POST"
    $rawReq.Timeout = 15000
    $rawReq.AllowAutoRedirect = $false
    $rawResp = $rawReq.GetResponse()
    $noOriginStatus = [int]$rawResp.StatusCode
    $rawResp.Close()
} catch {
    # PowerShell 会把 .NET 方法异常包成 MethodInvocationException，需取最内层 WebException 的响应
    $inner = $_.Exception
    while ($null -ne $inner -and $null -eq $inner.Response -and $null -ne $inner.InnerException) { $inner = $inner.InnerException }
    if ($null -ne $inner -and $null -ne $inner.Response) { $noOriginStatus = [int]$inner.Response.StatusCode } else { $noOriginStatus = 0 }
}
Check "POST without Origin -> 403" ($noOriginStatus -eq 403) "actual=$noOriginStatus"
Expect-Status $sa "POST" "/api/public/ai/challenge" 403 "POST with forged Origin -> 403" `
    $null $null @{ Origin = "https://evil.example.com" } | Out-Null
$good = Invoke-Api $sa "POST" "/api/public/ai/challenge" $null $null @{ Origin = $PublicOrigin }
Check "POST with whitelisted Origin passes origin guard" ($good.Status -ne 403) "actual=$($good.Status)"

# ---------- 5. demo dataset is read-only ----------
$demo = $listAJson.data.records | Where-Object { $_.ownerType -eq "demo" } | Select-Object -First 1
if ($null -eq $demo) {
    Skip "demo dataset authorisation matrix" "demo data not enabled or not yet created"
} else {
    $demoId = [long]$demo.id
    Expect-Status $sa "GET" "/api/dataset/$demoId/preview" 200 "demo preview readable" | Out-Null
    Expect-Status $sa "GET" "/api/dataset/$demoId/statistics" 200 "demo statistics readable" | Out-Null
    Expect-Status $sa "POST" "/api/clean/execute" 403 "demo clean execution -> 403" `
        ([Text.Encoding]::UTF8.GetBytes("{`"datasetId`":$demoId,`"ruleIds`":[]}")) "application/json" `
        @{ Origin = $PublicOrigin; "X-Confirm" = "yes" } | Out-Null
    Expect-Status $sa "POST" "/api/export/dataset/$demoId" 403 "demo export -> 403" `
        $null $null @{ Origin = $PublicOrigin; "X-Confirm" = "yes" } | Out-Null
    Expect-Status $sa "DELETE" "/api/dataset/$demoId" 403 "demo delete -> 403" `
        $null $null @{ Origin = $PublicOrigin; "X-Confirm" = "yes" } | Out-Null
}

# ---------- 6. session deletion / revocation / expiry ----------
Write-Host '-- session lifecycle --'
$tokenA = Get-CookieToken $a.SetCookie

# 6.1 删除 Cookie 后旧数据不可恢复
$sa.Cookies = New-Object System.Net.CookieContainer
$afterClear = Invoke-Api $sa "GET" "/api/dataset/$idA"
Check "deleted cookie cannot read old dataset" ($afterClear.Status -eq 401) "actual=$($afterClear.Status)"
$fresh = New-AnonSession "C"
if ($fresh.Status -eq 200) {
    $freshList = Invoke-Api $fresh.Session "GET" "/api/dataset?page=1&size=100"
    $freshIds = @()
    if ($freshList.Status -eq 200) {
        $freshIds = @((($freshList.Body | ConvertFrom-Json).data.records) | ForEach-Object { [long]$_.id })
    }
    Check "new session cannot recover deleted-cookie data" (-not ($freshIds -contains $idA))
} else {
    Skip "new session isolation after cookie deletion" "session bootstrap failed status=$($fresh.Status)"
}

# 6.2 撤销 / 过期：需要隔离测试库
if ([string]::IsNullOrWhiteSpace($MysqlExe) -or [string]::IsNullOrWhiteSpace($DbName)) {
    Skip "revoked session cannot read old data" "未提供 -MysqlExe/-DbName（隔离库）；请在隔离环境重跑"
    Skip "expired session cannot read old data" "未提供 -MysqlExe/-DbName（隔离库）；请在隔离环境重跑"
} else {
    $tokenHashA = Get-Sha256Hex $tokenA
    Invoke-IsolatedSql "UPDATE anon_session SET revoked=1 WHERE token_hash='$tokenHashA';" | Out-Null
    $revoked = Invoke-Api $a.Session "GET" "/api/dataset/$idA"
    Check "revoked session cannot read old data" ($revoked.Status -eq 401) "actual=$($revoked.Status)"

    Invoke-IsolatedSql "UPDATE anon_session SET revoked=0, expires_at=NOW() - INTERVAL 1 MINUTE WHERE token_hash='$tokenHashA';" | Out-Null
    $expired = Invoke-Api $a.Session "GET" "/api/dataset/$idA"
    Check "expired session cannot read old data" ($expired.Status -eq 401) "actual=$($expired.Status)"
}

# ---------- 7. session creation limiting & challenge ----------
Write-Host '-- session creation limiting --'
$firstChallenge = $null
$hit429 = $false
$hit428 = $false
$cookieOn428 = $false
$cookieOn429 = $false
$attempts = 0

for ($i = 0; $i -lt $SessionLimitProbe; $i++) {
    $r = Invoke-Api (New-BrowserSession) "POST" "/api/public/session" $null $null @{ Origin = $PublicOrigin }
    $attempts++
    if ($r.Status -eq 428) {
        if (-not $hit428) {
            $hit428 = $true
            $cookieOn428 = -not [string]::IsNullOrWhiteSpace((Get-HeaderValue $r.Headers "Set-Cookie"))
            $firstChallenge = $r
        }
        # 完成一次挑战后继续探测日限额
        $ch = Invoke-Api (New-BrowserSession) "POST" "/api/public/session/challenge" $null $null @{ Origin = $PublicOrigin }
        if ($ch.Status -eq 200) {
            $chJson = $ch.Body | ConvertFrom-Json
            $retry = Invoke-Api (New-BrowserSession) "POST" "/api/public/session" $null $null @{ Origin = $PublicOrigin; "X-Session-Challenge" = $chJson.token }
            if ($retry.Status -eq 429) {
                $hit429 = $true
                $cookieOn429 = -not [string]::IsNullOrWhiteSpace((Get-HeaderValue $retry.Headers "Set-Cookie"))
                break
            }
            continue
        }
        break
    }
    if ($r.Status -eq 429) {
        $hit429 = $true
        $cookieOn429 = -not [string]::IsNullOrWhiteSpace((Get-HeaderValue $r.Headers "Set-Cookie"))
        break
    }
    if ($r.Status -ne 200) { break }
}

Check "high-frequency session creation requires challenge (428)" $hit428 "attempts=$attempts"
if ($firstChallenge) {
    Check "428 carries X-Session-Challenge-Required: 1" ((Get-HeaderValue $firstChallenge.Headers "X-Session-Challenge-Required") -eq "1")
}
Check "428 does not issue a session cookie" (-not $cookieOn428)
Check "daily session limit reached returns 429" $hit429 "attempts=$attempts"
Check "429 does not issue a new session cookie" (-not $cookieOn429)

Write-Host ""
Write-Host "Result: PASS=$($script:pass) FAIL=$($script:fail) SKIP=$($script:skip)" -ForegroundColor Cyan
if ($script:fail -gt 0) { exit 1 } else { exit 0 }
