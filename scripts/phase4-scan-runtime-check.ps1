# ============================================================
# 第四阶段：扫描链路与运行指标实测（隔离后端 + 隔离 MySQL + 模拟扫描服务）
# ------------------------------------------------------------
# 前置：后端已以 public 模式启动，且：
#   UPLOAD_SCAN_PROVIDER=http / UPLOAD_SCAN_URL=http://127.0.0.1:18090/scan
#   PUBLIC_MAX_ACTIVE_SESSIONS_PER_IP=3 / PUBLIC_SESSION_CREATE_PER_IP_PER_DAY>=20
#   PUBLIC_SESSION_CHALLENGE_ENABLED=false / PUBLIC_AI_ENABLED=false
#
# 覆盖：Cookie 属性、扫描通过/检出/超时/异常/不可用、异常响应脱敏、
#       伪造代理头失效、AI 默认关闭、429 与上传指标。
# ============================================================
param(
    [string]$BaseUrl = "http://127.0.0.1:19080",
    [string]$MgmtUrl = "http://127.0.0.1:19081",
    [string]$Origin = "https://localhost:18443",
    [int]$ScannerPort = 18090
)

$ErrorActionPreference = "Stop"
$Repo = Split-Path -Parent $PSScriptRoot
$Work = Join-Path $env:TEMP "dc-phase4-runtime"
New-Item -ItemType Directory -Force -Path $Work | Out-Null
$ScannerMode = Join-Path $Work "scan-mode.txt"
Set-Content -LiteralPath $ScannerMode -Value "clean" -Encoding ASCII

$script:pass = 0
$script:fail = 0
function Check([string]$Name, $Ok, [string]$Detail = "") {
    # 兼容 -match 返回数组的情况：非空数组视为真，空数组/null 视为假
    $isOk = $false
    if ($Ok -is [array]) { $isOk = ($Ok.Count -gt 0) }
    elseif ($null -ne $Ok) { $isOk = [bool]$Ok }
    if ($isOk) { $script:pass++; Write-Output "[PASS] $Name" }
    else { $script:fail++; Write-Output "[FAIL] $Name | $Detail" }
}

function Invoke-CurlCli([string[]]$CurlArgs) {
    $out = & curl.exe @CurlArgs 2>$null
    return [string]($out | Select-Object -Last 1)
}

# ---------- 测试文件 ----------
$goodCsv = Join-Path $Work "good.csv"
[IO.File]::WriteAllText($goodCsv, "name,amount`nA,1`nB,2`n", (New-Object Text.UTF8Encoding($false)))
$eicarCsv = Join-Path $Work "eicar.csv"
[IO.File]::WriteAllText($eicarCsv, "name`nEICAR-STANDARD-ANTIVIRUS-TEST-FILE`n", (New-Object Text.UTF8Encoding($false)))

# ---------- 扫描服务 ----------
$scanner = Start-Process -FilePath "python" -ArgumentList @((Join-Path $Repo "scripts\tools\phase4_mock_scanner.py"), "$ScannerPort", $ScannerMode) -WindowStyle Hidden -PassThru
Start-Sleep -Seconds 2
$modeCode = Invoke-CurlCli @("-s", "-o", "NUL", "-w", "%{http_code}", "-X", "POST", "http://127.0.0.1:$ScannerPort/__mode/clean")
Check "模拟扫描服务可用" ($modeCode -eq "200") "code=$modeCode"

function Set-ScanMode([string]$Mode) {
    Invoke-CurlCli @("-s", "-o", "NUL", "-w", "%{http_code}", "http://127.0.0.1:$ScannerPort/__mode/$Mode") | Out-Null
    Start-Sleep -Milliseconds 200
}

# ---------- 会话 ----------
function New-Session([string]$Jar, [string]$Name, [string]$ForgedIp = "") {
    $hdr = Join-Path $Work "$Name.hdr"
    $curlArgs = @("-s", "-D", $hdr, "-o", "NUL", "-w", "%{http_code}", "-c", $Jar,
        "-H", "Origin: $Origin", "-X", "POST", "$BaseUrl/api/public/session")
    if ($ForgedIp) { $curlArgs += @("-H", "X-Real-IP: $ForgedIp") }
    return Invoke-CurlCli $curlArgs
}

$jarA = Join-Path $Work "a.jar"
$hdrA = Join-Path $Work "a.hdr"
$code = New-Session $jarA "a"
$cookieText = [string]$(if (Test-Path $hdrA) { Get-Content $hdrA -Raw } else { "" })
# 生产 Cookie 为 Secure，curl 在 http 下不会自动回传；验收时显式带上 Cookie 头（不输出其值）
$cookieValue = ""
if ($cookieText -match "__Host-dc_anon=([^;\s]+)") { $cookieValue = $Matches[1] }
$cookieHeader = if ($cookieValue) { "Cookie: __Host-dc_anon=$cookieValue" } else { "Cookie: __Host-dc_anon=" }
Check "匿名会话创建成功" ($code -eq "200") "code=$code"
Check "成功提取会话 Cookie 值" ($cookieValue.Length -gt 0) "length=$($cookieValue.Length)"
Check "Cookie 名称 __Host-dc_anon" ($cookieText -match "__Host-dc_anon=") ""
Check "Cookie 具备 Secure/HttpOnly/Strict/Path=/" ([bool](
    ($cookieText -match "(?i);\s*Secure") -and ($cookieText -match "(?i);\s*HttpOnly") -and
    ($cookieText -match "(?i)SameSite=Strict") -and ($cookieText -match "(?i);\s*Path=/")
)) ""
Check "Cookie 不含 Domain" (-not ($cookieText -match "(?im)^Set-Cookie:.*Domain=")) ""

function Upload([string]$Path, [string]$Name) {
    $resp = Join-Path $Work "up-$Name.json"
    $code = Invoke-CurlCli @("-s", "--max-time", "60", "-o", $resp, "-w", "%{http_code}",
        "-H", $cookieHeader, "-H", "Origin: $Origin",
        "-F", "name=p4-$Name", "-F", "hasHeader=true", "-F", "encoding=UTF-8",
        "-F", "file=@$Path;filename=$Name.csv;type=text/csv",
        "$BaseUrl/api/dataset/upload")
    $body = [string]$(if (Test-Path $resp) { Get-Content $resp -Raw } else { "" })
    return [pscustomobject]@{ Code = $code; Body = $body }
}

# ---------- 扫描通过 ----------
$ok = Upload $goodCsv "good"
Check "正常 CSV 扫描通过并导入成功" ($ok.Code -eq "200") ("code=" + $ok.Code + " body=" + $ok.Body.Substring(0, [Math]::Min(120, $ok.Body.Length)))

# ---------- 检出风险 ----------
Set-ScanMode "malicious"
$bad = Upload $goodCsv "bad"
Check "恶意文件被拒绝(400)" ($bad.Code -eq "400") "code=$($bad.Code)"
Check "拒绝响应不泄露扫描细节" (-not ($bad.Body -match "scan|scanner|127\.0\.0\.1|EICAR")) $bad.Body.Substring(0, [Math]::Min(120, $bad.Body.Length))

Set-ScanMode "clean"
$eicar = Upload $eicarCsv "eicar"
Check "EICAR 样本被拒绝(400)" ($eicar.Code -eq "400") "code=$($eicar.Code)"

# ---------- 超时 ----------
Set-ScanMode "timeout"
$slow = Upload $goodCsv "slow"
Check "扫描超时被拒绝(503)" ($slow.Code -eq "503") "code=$($slow.Code)"

# ---------- 扫描服务异常 ----------
Set-ScanMode "error"
$err = Upload $goodCsv "err"
Check "扫描服务 5xx 被拒绝(503)" ($err.Code -eq "503") "code=$($err.Code)"
Check "异常响应不泄露内部信息" (-not ($err.Body -match "scanner-internal|127\.0\.0\.1|scanner")) $err.Body.Substring(0, [Math]::Min(120, $err.Body.Length))

# ---------- 扫描服务不可用 ----------
Stop-Process -Id $scanner.Id -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1
$down = Upload $goodCsv "down"
Check "扫描服务不可用被拒绝(503)" ($down.Code -eq "503") "code=$($down.Code)"

# 恢复扫描服务，便于后续复跑
$scanner = Start-Process -FilePath "python" -ArgumentList @((Join-Path $Repo "scripts\tools\phase4_mock_scanner.py"), "$ScannerPort", $ScannerMode) -WindowStyle Hidden -PassThru
Start-Sleep -Seconds 2
Set-ScanMode "clean"

# ---------- AI 默认关闭 ----------
$aiBody = Join-Path $Work "ai.json"
$aiReq = Join-Path $Work "ai-request.json"
# PowerShell 会剥掉原生参数中的双引号，JSON 体必须通过文件传入
[IO.File]::WriteAllText($aiReq,
    '{"templateKey":"summary","contextType":"dataset","datasetId":1,"customPrompt":"hello"}',
    (New-Object Text.UTF8Encoding($false)))
$aiCode = Invoke-CurlCli @("-s", "-o", $aiBody, "-w", "%{http_code}", "-H", $cookieHeader,
    "-H", "Origin: $Origin", "-H", "Content-Type: application/json",
    "-d", "@$aiReq",
    "$BaseUrl/api/ai/generate")
Check "AI 默认关闭返回 403" ($aiCode -eq "403") "code=$aiCode"

# ---------- 伪造代理头失效（按 IP 上限） ----------
$jarB = Join-Path $Work "b.jar"
$jarC = Join-Path $Work "c.jar"
$jarD = Join-Path $Work "d.jar"
$c1 = New-Session $jarB "b" "8.8.8.8"
$c2 = New-Session $jarC "c" "9.9.9.9"
$c3 = New-Session $jarD "d" "7.7.7.7"
Check "伪造 X-Real-IP 无法绕过单 IP 活跃上限(429)" ($c3 -eq "429") ("codes=" + ($c1, $c2, $c3 -join ","))

# ---------- 指标 ----------
try { $health = (Invoke-WebRequest -Uri "$MgmtUrl/actuator/health" -UseBasicParsing -TimeoutSec 5).StatusCode } catch { $health = 0 }
Check "内网管理端口 health 可用" ($health -eq 200) "code=$health"
try { $prom = (Invoke-WebRequest -Uri "$MgmtUrl/actuator/prometheus" -UseBasicParsing -TimeoutSec 5).Content } catch { $prom = "" }
Check "指标包含上传结果计数" ($prom -match 'dc_upload_total\{.*result="success"') ""
Check "指标包含扫描失败计数" ($prom -match 'dc_upload_total\{.*result="scan_failed"') ""
Check "指标包含 429 计数" ($prom -match 'dc_http_responses_total\{.*status="429"') ""
Check "指标包含会话拒绝原因" ($prom -match 'dc_session_rejected_total\{.*reason="ip_active"') ""
Check "指标包含活跃会话/容量" (($prom -match "dc_sessions_active") -and ($prom -match "dc_upload_volume_bytes")) ""

if ($scanner -and -not $scanner.HasExited) { Stop-Process -Id $scanner.Id -Force -ErrorAction SilentlyContinue }

Write-Output "TOTAL=$($script:pass + $script:fail) PASS=$($script:pass) FAIL=$($script:fail)"
if ($script:fail -gt 0) { exit 1 }
exit 0
