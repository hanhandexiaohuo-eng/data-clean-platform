# ============================================================
# 第四阶段：真实 Nginx + HTTPS 本机验证
# ------------------------------------------------------------
# 用途：在无 Docker/Nginx 的验证机上，使用官方 Windows Nginx 发行包、
# 自签名证书与桩后端，实际加载 deploy/nginx/default.https.conf.template
# 渲染后的配置，验证跳转、TLS、HSTS、安全头、Cookie 透传、运行期变量、
# 非法 Host、限流、请求体上限、错误页脱敏与日志脱敏。
#
# 与生产差异：监听端口改为高位端口、上游为桩后端、证书为自签名；
# 容器网络隔离与真实公网端口扫描不在本脚本范围（需 Docker 环境）。
# ============================================================
param(
    [int]$HttpPort = 18081,
    [int]$HttpsPort = 18443,
    [int]$StubPort = 18080,
    [string]$NginxVersion = "1.27.4"
)

$ErrorActionPreference = "Stop"
$Repo = Split-Path -Parent $PSScriptRoot
$Work = Join-Path $env:TEMP "dc-phase4-nginx"
$Logs = Join-Path $Work "logs"
$Html = Join-Path $Work "html"
$ConfDir = Join-Path $Work "conf"
$CertDir = Join-Path $Work "certs"
$TempDir = Join-Path $Work "temp"

$script:pass = 0
$script:fail = 0
$script:skip = 0

function Check([string]$Name, [bool]$Ok, [string]$Detail = "") {
    if ($Ok) {
        $script:pass++
        Write-Output "[PASS] $Name"
    } else {
        $script:fail++
        Write-Output "[FAIL] $Name | $Detail"
    }
}

function Skip([string]$Name, [string]$Reason) {
    $script:skip++
    Write-Output "[SKIP] $Name | $Reason"
}

function To-NginxPath([string]$Path) {
    return $Path.Replace("\", "/")
}

New-Item -ItemType Directory -Force -Path $Work, $Logs, $Html, $ConfDir, $CertDir, $TempDir, (Join-Path $Html "assets") | Out-Null
Remove-Item (Join-Path $Logs "access.log"), (Join-Path $Logs "error.log") -ErrorAction SilentlyContinue

# ---------- 1. 准备 Nginx ----------
$ZipPath = Join-Path $Work "nginx-$NginxVersion.zip"
$NginxRoot = Join-Path $Work "nginx-$NginxVersion"
if (-not (Test-Path (Join-Path $NginxRoot "nginx.exe"))) {
    if (-not (Test-Path $ZipPath)) {
        Write-Output "downloading nginx $NginxVersion ..."
        Invoke-WebRequest -UseBasicParsing -Uri "https://nginx.org/download/nginx-$NginxVersion.zip" -OutFile $ZipPath
    }
    Expand-Archive -Path $ZipPath -DestinationPath $Work -Force
}
$NginxExe = Join-Path $NginxRoot "nginx.exe"
Check "nginx 发行包可用" (Test-Path $NginxExe) $NginxExe
if (-not (Test-Path $NginxExe)) { exit 1 }

Copy-Item (Join-Path $NginxRoot "conf\mime.types") (Join-Path $ConfDir "mime.types") -Force

# ---------- 2. 自签名证书 ----------
$JdkHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "D:\JDK21" }
$Keytool = Join-Path $JdkHome "bin\keytool.exe"
$Java = Join-Path $JdkHome "bin\java.exe"
$P12 = Join-Path $CertDir "cert.p12"
if (-not (Test-Path (Join-Path $CertDir "fullchain.pem"))) {
    $oldEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & $Keytool -genkeypair -alias dc -keyalg RSA -keysize 2048 -validity 7 `
        -dname "CN=localhost" -ext "SAN=dns:localhost,ip:127.0.0.1" `
        -storetype PKCS12 -keystore $P12 -storepass changeit -keypass changeit 2>$null | Out-Null
    & $Java (Join-Path $Repo "scripts\tools\PemCertExporter.java") $P12 "changeit" $CertDir 2>$null | Out-Null
    $ErrorActionPreference = $oldEap
}
Check "自签名证书生成" (Test-Path (Join-Path $CertDir "fullchain.pem")) ""

# ---------- 3. 渲染模板（仅白名单变量，保留运行期变量） ----------
$Template = [IO.File]::ReadAllText((Join-Path $Repo "deploy\nginx\default.https.conf.template"))
$Rendered = $Template
$Rendered = $Rendered.Replace('${NGINX_CLIENT_MAX_BODY_SIZE}', '25m')
$Rendered = $Rendered.Replace('${NGINX_SERVER_NAME}', 'localhost')
$Rendered = $Rendered.Replace('${NGINX_HTTPS_CERT}', (To-NginxPath (Join-Path $CertDir "fullchain.pem")))
$Rendered = $Rendered.Replace('${NGINX_HTTPS_KEY}', (To-NginxPath (Join-Path $CertDir "privkey.pem")))
$Rendered = $Rendered.Replace('/var/log/nginx/access.log', (To-NginxPath (Join-Path $Logs "access.log")))
$Rendered = $Rendered.Replace('/var/log/nginx/error.log', (To-NginxPath (Join-Path $Logs "error.log")))
$Rendered = $Rendered.Replace('http://backend:8080', "http://127.0.0.1:$StubPort")
$Rendered = $Rendered.Replace('root /usr/share/nginx/html;', ("root " + (To-NginxPath $Html) + ";"))
$Rendered = $Rendered.Replace('listen 80;', "listen $HttpPort;")
$Rendered = $Rendered.Replace('listen 443 ssl;', "listen $HttpsPort ssl;")

$Conf = @"
worker_processes 1;
error_log $(To-NginxPath (Join-Path $Logs "master-error.log")) crit;
pid $(To-NginxPath (Join-Path $Logs "nginx.pid"));
events { worker_connections 512; }
http {
    include $(To-NginxPath (Join-Path $ConfDir "mime.types"));
    default_type application/octet-stream;
    client_body_temp_path $(To-NginxPath (Join-Path $TempDir "client"));
    proxy_temp_path $(To-NginxPath (Join-Path $TempDir "proxy"));
    fastcgi_temp_path $(To-NginxPath (Join-Path $TempDir "fastcgi"));
    uwsgi_temp_path $(To-NginxPath (Join-Path $TempDir "uwsgi"));
    scgi_temp_path $(To-NginxPath (Join-Path $TempDir "scgi"));
$Rendered
}
"@
$ConfPath = Join-Path $ConfDir "nginx.conf"
[IO.File]::WriteAllText($ConfPath, $Conf, (New-Object System.Text.UTF8Encoding($false)))

Check '渲染后保留运行期变量 $uri' ($Rendered.Contains('$uri')) ""
Check '渲染后保留运行期变量 $host' ($Rendered.Contains('$host')) ""
Check '渲染后保留运行期变量 $remote_addr' ($Rendered.Contains('$remote_addr')) ""
Check '渲染后保留运行期变量 $proxy_add_x_forwarded_for' ($Rendered.Contains('$proxy_add_x_forwarded_for')) ""
Check "渲染后无未替换白名单占位符" (-not ($Rendered -match '\$\{NGINX_')) ""

# 静态资源样本
[IO.File]::WriteAllText((Join-Path $Html "index.html"), "<!doctype html><title>dc</title>", (New-Object System.Text.UTF8Encoding($false)))
[IO.File]::WriteAllText((Join-Path $Html "assets\app.js"), "console.log('dc');", (New-Object System.Text.UTF8Encoding($false)))

# ---------- 4. 启动桩后端与 Nginx ----------
$Stub = Start-Process -FilePath "python" -ArgumentList @((Join-Path $Repo "scripts\tools\phase4_stub_backend.py"), "$StubPort") -WindowStyle Hidden -PassThru
Start-Sleep -Seconds 2
$Prefix = (To-NginxPath $Work) + "/"
$tOut = Join-Path $Work "nginx-t.out.txt"
$tErr = Join-Path $Work "nginx-t.err.txt"
$testProc = Start-Process -FilePath $NginxExe -ArgumentList @("-p", $Prefix, "-c", "conf/nginx.conf", "-t") `
    -Wait -PassThru -NoNewWindow -RedirectStandardOutput $tOut -RedirectStandardError $tErr
$TestText = ((Get-Content $tOut -Raw -ErrorAction SilentlyContinue) + "`n" + (Get-Content $tErr -Raw -ErrorAction SilentlyContinue))
Check "nginx -t 配置语法通过" ($testProc.ExitCode -eq 0) $TestText.Trim()

$Nginx = Start-Process -FilePath $NginxExe -ArgumentList @("-p", $Prefix, "-c", "conf/nginx.conf") -WindowStyle Hidden -PassThru
Start-Sleep -Seconds 2

function Invoke-Curl([string[]]$CurlArgs) {
    $result = & curl.exe @CurlArgs 2>$null
    return $result
}

# ---------- 5. 协议与头部 ----------
$redirect = Invoke-Curl @("-s", "-o", "NUL", "-w", "%{http_code}|%{redirect_url}", "--resolve", "localhost:$HttpPort`:127.0.0.1", "http://localhost:$HttpPort/api/echo")
Check "HTTP 请求 301 跳转 HTTPS" ($redirect -like "301|https://localhost*") $redirect

$hdrFile = Join-Path $Work "hdr.txt"
$bodyFile = Join-Path $Work "body.txt"
$code = Invoke-Curl @("-sk", "-D", $hdrFile, "-o", $bodyFile, "-w", "%{http_code}", "--resolve", "localhost:$HttpsPort`:127.0.0.1", "https://localhost:$HttpsPort/api/echo")
$headers = Get-Content $hdrFile -Raw
Check "HTTPS 正常响应" ($code -eq "200") "code=$code"
Check "HSTS 已返回" ($headers -match "(?im)^Strict-Transport-Security:\s*max-age=31536000") ""
Check "CSP 已返回" ($headers -match "(?im)^Content-Security-Policy:.*default-src 'self'") ""
Check "X-Content-Type-Options 已返回" ($headers -match "(?im)^X-Content-Type-Options:\s*nosniff") ""
Check "未泄露 Nginx 版本" (-not ($headers -match "(?im)^Server:\s*nginx/[\d\.]+")) ""

$tls12 = Invoke-Curl @("-sk", "-o", "NUL", "-w", "%{http_code}", "--tlsv1.2", "--tls-max", "1.2", "--resolve", "localhost:$HttpsPort`:127.0.0.1", "https://localhost:$HttpsPort/api/echo")
Check "TLS 1.2 可用" ($tls12 -eq "200") "code=$tls12"
$tls13 = Invoke-Curl @("-sk", "-o", "NUL", "-w", "%{http_code}", "--tlsv1.3", "--resolve", "localhost:$HttpsPort`:127.0.0.1", "https://localhost:$HttpsPort/api/echo")
Check "TLS 1.3 可用" ($tls13 -eq "200") "code=$tls13"
$tls11 = Invoke-Curl @("-sk", "-o", "NUL", "-w", "%{http_code}", "--tls-max", "1.1", "--resolve", "localhost:$HttpsPort`:127.0.0.1", "https://localhost:$HttpsPort/api/echo")
Check "TLS 1.0/1.1 被拒绝" ($tls11 -ne "200") "code=$tls11"

# ---------- 6. Cookie 透传与运行期变量 ----------
$cookieHdr = Join-Path $Work "cookie.txt"
Invoke-Curl @("-sk", "-D", $cookieHdr, "-o", "NUL", "--resolve", "localhost:$HttpsPort`:127.0.0.1", "https://localhost:$HttpsPort/api/cookie") | Out-Null
$cookieText = Get-Content $cookieHdr -Raw
Check "Cookie 具备 Secure/HttpOnly/Strict/Path=/" (
    ($cookieText -match "__Host-dc_anon=") -and ($cookieText -match "(?i);\s*Secure") -and
    ($cookieText -match "(?i);\s*HttpOnly") -and ($cookieText -match "(?i)SameSite=Strict") -and
    ($cookieText -match "(?i);\s*Path=/")
) ""
Check "Cookie 不含 Domain" (-not ($cookieText -match "(?im)^Set-Cookie:.*Domain=")) ""

$echo = Invoke-Curl @("-sk", "--resolve", "localhost:$HttpsPort`:127.0.0.1", "https://localhost:$HttpsPort/api/echo?token=SUPERSECRET")
try { $echoJson = $echo | ConvertFrom-Json } catch { $echoJson = $null }
Check "Nginx 覆盖传递 X-Real-IP" ($echoJson -and $echoJson.x_real_ip -eq "127.0.0.1") ""
Check "X-Forwarded-For 非空" ($echoJson -and -not [string]::IsNullOrWhiteSpace($echoJson.x_forwarded_for)) ""
Check "X-Forwarded-Proto=https" ($echoJson -and $echoJson.x_forwarded_proto -eq "https") ""
Check "Host 正确传递" ($echoJson -and $echoJson.host -eq "localhost") ""

# ---------- 7. 非法 Host / 管理路径 / 错误页 ----------
$evil = Invoke-Curl @("-sk", "-o", "NUL", "-w", "%{http_code}", "--resolve", "localhost:$HttpsPort`:127.0.0.1", "-H", "Host: evil.example.com", "https://localhost:$HttpsPort/api/echo")
Check "非法 Host 被拒绝" ($evil -ne "200") "code=$evil"

foreach ($path in @("/api/health", "/api/admin/users", "/actuator/metrics", "/api/ops/diag", "/assets/missing.js")) {
    $c = Invoke-Curl @("-sk", "-o", "NUL", "-w", "%{http_code}", "--resolve", "localhost:$HttpsPort`:127.0.0.1", "https://localhost:$HttpsPort$path")
    Check "内部/缺失路径 $path 返回 404" ($c -eq "404") "code=$c"
}

$boomBody = Join-Path $Work "boom.txt"
$boomCode = Invoke-Curl @("-sk", "-o", $boomBody, "-w", "%{http_code}", "--resolve", "localhost:$HttpsPort`:127.0.0.1", "https://localhost:$HttpsPort/api/boom")
$boomText = Get-Content $boomBody -Raw
Check "上游 502 被脱敏为 502" ($boomCode -eq "502") "code=$boomCode"
Check "错误页不含上游内部信息" (-not ($boomText -match "upstream-secret")) ""
Check "错误页为 JSON" ($boomText -match '"code"\s*:\s*502') $boomText

$nfBody = Join-Path $Work "nf.txt"
Invoke-Curl @("-sk", "-o", $nfBody, "--resolve", "localhost:$HttpsPort`:127.0.0.1", "https://localhost:$HttpsPort/assets/missing.js") | Out-Null
$nfText = Get-Content $nfBody -Raw
Check "404 错误页为脱敏 JSON" ($nfText -match '"code"\s*:\s*404') $nfText
Check "404 错误页不含 nginx 字样" (-not ($nfText -match "(?i)nginx")) ""

# ---------- 8. 请求体限制与限流 ----------
$big = Join-Path $Work "big.bin"
$fs = [IO.File]::Create($big)
$fs.SetLength(30MB)
$fs.Close()
$bigCode = Invoke-Curl @("-sk", "-o", "NUL", "-w", "%{http_code}", "-X", "POST", "--data-binary", "@$big", "--resolve", "localhost:$HttpsPort`:127.0.0.1", "https://localhost:$HttpsPort/api/dataset/upload")
Check "超大请求体被拒绝(413)" ($bigCode -eq "413") "code=$bigCode"

$codes = @()
for ($i = 0; $i -lt 8; $i++) {
    $c = Invoke-Curl @("-sk", "-o", "NUL", "-w", "%{http_code}", "-X", "POST", "-d", "a=1", "--resolve", "localhost:$HttpsPort`:127.0.0.1", "https://localhost:$HttpsPort/api/dataset/upload")
    $codes += $c
}
Check "上传接口触发 429 限流" (($codes | Where-Object { $_ -eq "429" }).Count -ge 1) ($codes -join ",")

# ---------- 9. 静态缓存与日志脱敏 ----------
$assetHdr = Join-Path $Work "asset.txt"
$assetCode = Invoke-Curl @("-sk", "-D", $assetHdr, "-o", "NUL", "-w", "%{http_code}", "--resolve", "localhost:$HttpsPort`:127.0.0.1", "https://localhost:$HttpsPort/assets/app.js")
$assetText = Get-Content $assetHdr -Raw
Check "静态资源 200" ($assetCode -eq "200") "code=$assetCode"
Check "静态资源长缓存" ($assetText -match "(?i)Cache-Control:.*max-age=604800") ""
Check "静态资源保留 HSTS" ($assetText -match "(?im)^Strict-Transport-Security:") ""

Start-Sleep -Seconds 1
$accessLog = Join-Path $Logs "access.log"
$logText = if (Test-Path $accessLog) { Get-Content $accessLog -Raw } else { "" }
Check "访问日志未记录查询串敏感值" (-not ($logText -match "SUPERSECRET")) ""
Check "访问日志未记录来源 IP 明文" (-not ($logText -match "127\.0\.0\.1")) ""

# ---------- 10. 监听端口 ----------
Start-Sleep -Seconds 1
$nginxPids = (Get-Process nginx -ErrorAction SilentlyContinue).Id
$listenPorts = @()
if ($nginxPids) {
    $listenPorts = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $nginxPids -contains $_.OwningProcess } |
        Select-Object -ExpandProperty LocalPort -Unique
}
$unexpected = @($listenPorts | Where-Object { $_ -ne $HttpPort -and $_ -ne $HttpsPort })
Check "Nginx 仅监听测试的 HTTP/HTTPS 端口" ($unexpected.Count -eq 0) ("ports=" + ($listenPorts -join ","))

# ---------- 清理 ----------
try {
    Start-Process -FilePath $NginxExe -ArgumentList @("-p", $Prefix, "-c", "conf/nginx.conf", "-s", "stop") `
        -Wait -NoNewWindow -RedirectStandardOutput (Join-Path $Work "stop.out.txt") `
        -RedirectStandardError (Join-Path $Work "stop.err.txt") | Out-Null
} catch { }
if ($Nginx -and -not $Nginx.HasExited) { Stop-Process -Id $Nginx.Id -Force -ErrorAction SilentlyContinue }
if ($Stub -and -not $Stub.HasExited) { Stop-Process -Id $Stub.Id -Force -ErrorAction SilentlyContinue }
Get-Process nginx -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue

Write-Output "TOTAL=$($script:pass + $script:fail + $script:skip) PASS=$($script:pass) FAIL=$($script:fail) SKIP=$($script:skip)"
if ($script:fail -gt 0) { exit 1 }
exit 0
