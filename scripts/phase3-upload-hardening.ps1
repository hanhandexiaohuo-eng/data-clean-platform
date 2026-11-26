<#
.SYNOPSIS
    第三阶段公开上传加固验证（隔离环境）。

.DESCRIPTION
    覆盖：伪造扩展名、二进制伪 CSV、超大文件、超行数、超列数、压缩炸弹、
    异常 Excel、上传目录不可静态访问、原始文件名不落盘、CSV 导出公式注入转义。

    仅使用临时上传卷与匿名会话，不接触私有数据。
    输出不含 Cookie 值、Token、原始 IP 或数据库连接串。

.PARAMETER BaseUrl
    被测后端地址（默认 http://127.0.0.1:8081）。

.PARAMETER Origin
    允许来源，需与后端 allowed-origins 一致。

.PARAMETER WorkDir
    测试文件目录（默认隔离工作目录下 upload-cases）。

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\phase3-upload-hardening.ps1 `
        -BaseUrl 'http://127.0.0.1:8081' -Origin 'http://127.0.0.1:8081'
#>
param(
    [string]$BaseUrl = "http://127.0.0.1:8081",
    [string]$Origin = "http://127.0.0.1:8081",
    [string]$WorkDir = "C:\Users\zengyun\Documents\Trae-CN\temp_20261026_01\phase3-it\upload-cases",
    [string]$UploadRoot = "C:\Users\zengyun\Documents\Trae-CN\temp_20261026_01\phase3-it\uploads"
)

$ErrorActionPreference = "Continue"
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$script:Pass = 0
$script:Fail = 0
$script:Skip = 0

function Check([string]$name, [bool]$cond, [string]$detail = "") {
    if ($cond) {
        $script:Pass++
        Write-Host ("[PASS] {0} {1}" -f $name, $detail)
    } else {
        $script:Fail++
        Write-Host ("[FAIL] {0} {1}" -f $name, $detail)
    }
}

function Skip([string]$name, [string]$detail = "") {
    $script:Skip++
    Write-Host ("[SKIP] {0} {1}" -f $name, $detail)
}

New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
$jar = Join-Path $WorkDir "cookies.txt"
if (Test-Path $jar) { Remove-Item $jar -Force }

$curl = "curl.exe"

function Curl-Code([string[]]$curlArgs) {
    $out = & $curl @curlArgs 2>$null
    return ($out | Select-Object -Last 1)
}

function Invoke-Session {
    $resp = Join-Path $WorkDir "session.json"
    $code = Curl-Code @('-s', '-o', $resp, '-w', '%{http_code}', '-b', $jar, '-c', $jar,
        '-H', "Origin: $Origin", '-X', 'POST', "$BaseUrl/api/public/session")
    if ($code -eq '428') {
        $ch = Join-Path $WorkDir "challenge.json"
        $chCode = Curl-Code @('-s', '-o', $ch, '-w', '%{http_code}', '-b', $jar, '-c', $jar,
            '-H', "Origin: $Origin", '-X', 'POST', "$BaseUrl/api/public/session/challenge")
        if ($chCode -ne '200') { return $false }
        $token = (Get-Content $ch -Raw | ConvertFrom-Json).data.token
        $code = Curl-Code @('-s', '-o', $resp, '-w', '%{http_code}', '-b', $jar, '-c', $jar,
            '-H', "Origin: $Origin", '-H', "X-Session-Challenge: $token", '-X', 'POST', "$BaseUrl/api/public/session")
    }
    return ($code -eq '200')
}

function Upload-File([string]$path, [string]$name, [string]$filename) {
    $resp = Join-Path $WorkDir "upload-$([IO.Path]::GetFileNameWithoutExtension($filename)).json"
    $code = Curl-Code @('-s', '--max-time', '180', '-o', $resp, '-w', '%{http_code}',
        '-b', $jar, '-c', $jar, '-H', "Origin: $Origin",
        '-F', "name=$name", '-F', "hasHeader=true", '-F', "encoding=UTF-8",
        '-F', "file=@$path;filename=$filename;type=application/octet-stream",
        "$BaseUrl/api/dataset/upload")
    $body = ""
    if (Test-Path $resp) { $body = Get-Content $resp -Raw }
    return [pscustomobject]@{ Code = [int]$code; Body = $body }
}

# ---------- 生成测试文件 ----------
$goodCsv = Join-Path $WorkDir "good.csv"
Set-Content -LiteralPath $goodCsv -Encoding UTF8 -Value @"
name,amount,region
=1+1,2,east
+3+4,5,west
@SUM(A1),6,north
-7+8,9,south
"@

$forgedCsv = Join-Path $WorkDir "forged.csv"
[IO.File]::WriteAllBytes($forgedCsv, [byte[]](0x50, 0x4B, 0x03, 0x04) + [Text.Encoding]::ASCII.GetBytes("fake-xlsx-payload"))

$binaryCsv = Join-Path $WorkDir "binary.csv"
$bin = [Text.Encoding]::ASCII.GetBytes("a,b`n1,") + [byte[]](0x00, 0x01, 0x02, 0x03)
[IO.File]::WriteAllBytes($binaryCsv, $bin)

$hugeCsv = Join-Path $WorkDir "huge.csv"
$line = ("x" * 1023) + "`n"
$sw = [IO.File]::Create($hugeCsv)
$bytes = [Text.Encoding]::ASCII.GetBytes($line)
for ($i = 0; $i -lt 21000; $i++) { $sw.Write($bytes, 0, $bytes.Length) }
$sw.Close()

$manyRows = Join-Path $WorkDir "manyrows.csv"
$sb = New-Object Text.StringBuilder
[void]$sb.AppendLine("c1,c2")
for ($i = 0; $i -lt 100001; $i++) { [void]$sb.AppendLine("1,2") }
[IO.File]::WriteAllText($manyRows, $sb.ToString(), (New-Object Text.UTF8Encoding($false)))

$manyCols = Join-Path $WorkDir "manycols.csv"
$hdr = (1..201 | ForEach-Object { "c$_" }) -join ","
$v1 = (1..201 | ForEach-Object { "1" }) -join ","
[IO.File]::WriteAllText($manyCols, "$hdr`n$v1`n", (New-Object Text.UTF8Encoding($false)))

$bombXlsx = Join-Path $WorkDir "bomb.xlsx"
if (Test-Path $bombXlsx) { Remove-Item $bombXlsx -Force }
$fs = [IO.File]::Create($bombXlsx)
$zip = New-Object IO.Compression.ZipArchive($fs, [IO.Compression.ZipArchiveMode]::Create)
$entry = $zip.CreateEntry("xl/worksheets/sheet1.xml", [IO.Compression.CompressionLevel]::Optimal)
$es = $entry.Open()
$chunk = New-Object byte[] 1048576
for ($i = 0; $i -lt 1MB; $i++) { $chunk[$i] = 0x41 }
for ($i = 0; $i -lt 260; $i++) { $es.Write($chunk, 0, $chunk.Length) }
$es.Close(); $zip.Dispose(); $fs.Close()

$brokenXlsx = Join-Path $WorkDir "broken.xlsx"
[IO.File]::WriteAllBytes($brokenXlsx, [byte[]](0x50, 0x4B, 0x03, 0x04) + [Text.Encoding]::ASCII.GetBytes("not-a-real-zip-structure"))

Write-Host "== Phase 3 upload hardening: $BaseUrl =="

$sessionOk = Invoke-Session
if (-not $sessionOk) {
    Write-Host "FATAL: cannot establish anonymous session (check per-IP daily limit or Origin whitelist)"
    exit 1
}
Check "anonymous session established" $true

# ---------- 恶意/异常文件拒绝 ----------
$r = Upload-File $forgedCsv "forged-ext" "forged.csv"
Check "forged extension (zip magic in .csv) rejected" ($r.Code -eq 400) "actual=$($r.Code)"

$r = Upload-File $binaryCsv "binary-csv" "binary.csv"
Check "binary content disguised as csv rejected" ($r.Code -eq 400) "actual=$($r.Code)"

$r = Upload-File $hugeCsv "huge-file" "huge.csv"
Check "oversized file rejected" ($r.Code -eq 400) "actual=$($r.Code)"

$r = Upload-File $manyRows "many-rows" "manyrows.csv"
Check "row limit exceeded rejected" ($r.Code -eq 400) "actual=$($r.Code)"

$r = Upload-File $manyCols "many-cols" "manycols.csv"
Check "column limit exceeded rejected" ($r.Code -eq 400) "actual=$($r.Code)"

$r = Upload-File $bombXlsx "zip-bomb" "bomb.xlsx"
Check "compression bomb rejected" ($r.Code -eq 400) "actual=$($r.Code)"

$r = Upload-File $brokenXlsx "broken-excel" "broken.xlsx"
Check "malformed excel rejected" ($r.Code -eq 400) "actual=$($r.Code)"

# 失败响应不得回显内部路径/SQL/堆栈
$leak = ($r.Body -match '(?i)sql|stack|C:\\\\|uploads|h2|mysql')
Check "rejection body has no internal details" (-not $leak)

# ---------- 正常文件与导出公式转义 ----------
$pdf = Join-Path $WorkDir "upload-good.json"
$code = Curl-Code @('-s', '--max-time', '60', '-o', $pdf, '-w', '%{http_code}',
    '-b', $jar, '-c', $jar, '-H', "Origin: $Origin",
    '-F', 'name=formula-check', '-F', 'hasHeader=true', '-F', 'encoding=UTF-8',
    "-F", "file=@$goodCsv;filename=good.csv;type=text/csv",
    "$BaseUrl/api/dataset/upload")
$datasetJson = Get-Content $pdf -Raw | ConvertFrom-Json
$datasetId = $null
if ($code -eq '200' -and $null -ne $datasetJson.data) { $datasetId = [long]$datasetJson.data.id }
Check "benign csv upload accepted" ($null -ne $datasetId) "status=$code"

if ($null -ne $datasetId) {
    $csvOut = Join-Path $WorkDir "export.csv"
    $ecode = Curl-Code @('-s', '--max-time', '60', '-o', $csvOut, '-w', '%{http_code}',
        '-b', $jar, '-c', $jar, '-H', "Origin: $Origin", '-H', 'X-Confirm: yes',
        '-X', 'POST', "$BaseUrl/api/export/dataset/$datasetId")
    Check "csv export succeeded" ($ecode -eq '200') "actual=$ecode"
    if ($ecode -eq '200') {
        $text = Get-Content $csvOut -Raw
        Check "export escapes '=' formula prefix" ($text -match "'=1\+1")
        Check "export escapes '+' formula prefix" ($text -match "'\+3\+4")
        Check "export escapes '@' formula prefix" ($text -match "'@SUM")
        Check "export escapes '-' formula prefix" ($text -match "'-7\+8")
    }
}

# ---------- 上传目录隔离 ----------
$names = @("good.csv", "forged.csv", "binary.csv", "huge.csv", "manyrows.csv", "manycols.csv", "bomb.xlsx", "broken.xlsx")
$found = @()
if (Test-Path $UploadRoot) {
    $files = Get-ChildItem -LiteralPath $UploadRoot -Recurse -File -ErrorAction SilentlyContinue
    foreach ($n in $names) {
        $hit = $files | Where-Object { $_.Name -eq $n }
        if ($hit) { $found += $n }
    }
}
Check "original upload filenames are not persisted" ($found.Count -eq 0) ("found=" + ($found -join ','))
$badTemp = @()
if (Test-Path $UploadRoot) {
    $badTemp = Get-ChildItem -LiteralPath $UploadRoot -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike 'upload_*.tmp' }
}
Check "upload volume only holds container temp files" ($badTemp.Count -eq 0) ("others=" + $badTemp.Count)

foreach ($p in @("/uploads/", "/uploads/good.csv", "/uploads/tmp/")) {
    $code = Curl-Code @('-s', '-o', (Join-Path $WorkDir "static.out"), '-w', '%{http_code}', "$BaseUrl$p")
    $body = ""
    if (Test-Path (Join-Path $WorkDir "static.out")) { $body = Get-Content (Join-Path $WorkDir "static.out") -Raw }
    $isCsv = $body -match '(?m)^name,amount,region'
    Check "upload path not served as static file $p" ((-not $isCsv) -and ($code -ne '200') -and ($code -ne '206'))
}

Write-Host ""
Write-Host ("Result: PASS={0} FAIL={1} SKIP={2}" -f $script:Pass, $script:Fail, $script:Skip)
if ($script:Fail -gt 0) { exit 1 }
