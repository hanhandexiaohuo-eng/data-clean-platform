# DataClean CI 门禁（本地/CI 通用）
#
# 校验内容：
#   1. JDK 必须为 17（唯一支持基线，其他版本直接失败）；
#   2. 后端 `mvn clean test` 全部通过；
#   3. 前端 `npm run build` 生产构建通过；
#   4. 敏感信息扫描 `scripts/scan-secrets.mjs` 无疑似明文凭据。
#
# 使用方式（项目根目录或任意目录）：
#   powershell -ExecutionPolicy Bypass -File .\scripts\ci-gate.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\ci-gate.ps1 -MvnExe "C:\path\mvn.cmd" -NodeExe "C:\path\node.exe"
#
# 说明：脚本不读写生产数据，仅执行构建、单元测试与静态扫描。

param(
    [string]$MvnExe = 'mvn',
    [string]$NodeExe = 'node',
    [string]$NpmCli = '',
    [string]$MavenSettings = ''
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$backendDir = Join-Path $projectRoot 'backend'
$frontendDir = Join-Path $projectRoot 'frontend'
$scanScript = Join-Path $projectRoot 'scripts\scan-secrets.mjs'

function Fail {
    param([string]$Message)
    Write-Host "[ci-gate] 失败：$Message" -ForegroundColor Red
    exit 1
}

function Invoke-Step {
    param([string]$Label, [scriptblock]$Action)
    Write-Host "[ci-gate] $Label ..." -ForegroundColor Cyan
    # 原生命令的 stderr（如 mvn/npm 警告）在 Stop 模式下会被当成终止性错误，这里降级并以退出码判定
    $previousEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Action } finally { $ErrorActionPreference = $previousEap }
    if ($LASTEXITCODE -ne 0) { Fail "$Label（exit=$LASTEXITCODE）" }
}

# ---------- 1. Java 基线 ----------
Write-Host '[ci-gate] 校验 JDK 基线 ...' -ForegroundColor Cyan
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Fail '未找到 java 命令，请先安装 JDK 17 并加入 PATH'
}
# `java -version` 输出到 stderr，直接重定向会被当成错误记录，这里用进程读取输出
function Get-JavaVersionText {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = 'java'
    $psi.Arguments = '-version'
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardError = $true
    $psi.RedirectStandardOutput = $true
    $proc = [System.Diagnostics.Process]::Start($psi)
    $text = $proc.StandardError.ReadToEnd() + $proc.StandardOutput.ReadToEnd()
    $proc.WaitForExit()
    return $text
}
$javaVersion = Get-JavaVersionText
if ($javaVersion -notmatch 'version "17\.') {
    Write-Host ($javaVersion -replace '\s+', ' ').Trim()
    Fail '当前 JDK 不是 17。本项目唯一支持 JDK 17（LTS）；JDK 21 需要额外 --add-opens，JDK 24+ 依赖 Byte Buddy 实验开关，均不受支持。'
}
Write-Host '[ci-gate] JDK 17 校验通过' -ForegroundColor Green

# ---------- 2. 后端构建与单测 ----------
$mvnArgs = @('-B', 'clean', 'test')
if ($MavenSettings) { $mvnArgs = @('-B', '-s', $MavenSettings) + $mvnArgs }
Invoke-Step '后端 mvn clean test' {
    Push-Location $backendDir
    try { & $MvnExe @mvnArgs } finally { Pop-Location }
}

# ---------- 3. 前端生产构建 ----------
if (-not $NpmCli) { $NpmCli = Join-Path (Split-Path -Parent $NodeExe) 'node_modules\npm\bin\npm-cli.js' }
if (-not (Test-Path $NpmCli)) { Fail "未找到 npm-cli.js：$NpmCli，请用 -NpmCli 指定路径" }
Invoke-Step '前端 npm run build' {
    Push-Location $frontendDir
    try { & $NodeExe $NpmCli 'run' 'build' } finally { Pop-Location }
}

# ---------- 4. 敏感信息扫描 ----------
# scan-secrets 以当前工作目录为扫描根，这里固定到项目根，避免从其他目录调用时扫错范围
Invoke-Step '敏感信息扫描' {
    Push-Location $projectRoot
    try { & $NodeExe $scanScript } finally { Pop-Location }
}

Write-Host '[ci-gate] 全部门禁通过' -ForegroundColor Green
