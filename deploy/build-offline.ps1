# ─────────────────────────────────────────────────────────────────────
#  PMS-X 오프라인 설치 패키지 빌드 (Windows PowerShell 5.1+)  — build-offline.sh 와 같은 결과
#
#  사용법 (pmsx\ 에서):  powershell -ExecutionPolicy Bypass -File deploy\build-offline.ps1 [-SkipWeb] [-Jdk <JDK21 경로>]
# ─────────────────────────────────────────────────────────────────────
param(
    [switch]$SkipWeb,
    [string]$Jdk = $env:PMSX_JDK21
)
$ErrorActionPreference = 'Stop'

$Deploy = $PSScriptRoot
$Root = Split-Path $Deploy -Parent
$Web = Join-Path $Root 'pmsx-web'
$Server = Join-Path $Root 'pmsx-server'
$Out = Join-Path $Deploy 'out'

$Version = (Select-String -Path (Join-Path $Server 'build.gradle') -Pattern "^version\s*=\s*'(.+)'").Matches[0].Groups[1].Value
$Pkg = "pmsx-$Version"
$Stage = Join-Path $Out $Pkg
Write-Host "▶ PMS-X $Version 오프라인 패키지 빌드"

if (-not $Jdk) {
    $cand = Get-ChildItem (Join-Path $env:USERPROFILE '.gradle\jdks') -Directory -Filter '*21*' -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin\jlink.exe') } | Select-Object -First 1
    if ($cand) { $Jdk = $cand.FullName } elseif ($env:JAVA_HOME) { $Jdk = $env:JAVA_HOME }
}
$Jlink = Join-Path $Jdk 'bin\jlink.exe'
if (-not (Test-Path $Jlink)) { throw "JDK 21 의 jlink 를 찾지 못했습니다. -Jdk <경로> 를 지정하세요." }
Write-Host "  JDK: $Jdk"

New-Item -ItemType Directory -Force $Out | Out-Null
if (Test-Path $Stage) { Remove-Item -Recurse -Force $Stage }
$Zip = Join-Path $Out "$Pkg-offline.zip"
if (Test-Path $Zip) { Remove-Item -Force $Zip }

# 1) 웹 빌드
$WebDist = Join-Path $Out 'web-dist'
if ($SkipWeb) {
    if (-not (Test-Path (Join-Path $WebDist 'index.html'))) { throw "-SkipWeb 인데 $WebDist 가 없습니다." }
    Write-Host "▶ [1/5] 웹 빌드 생략"
} else {
    Write-Host "▶ [1/5] 웹 빌드"
    Push-Location $Web
    try {
        if (-not (Test-Path 'node_modules')) { npm ci --no-audit --no-fund; if ($LASTEXITCODE) { throw 'npm ci 실패' } }
        if (Test-Path $WebDist) { Remove-Item -Recurse -Force $WebDist }
        npm run build -- --outDir "$WebDist" --emptyOutDir
        if ($LASTEXITCODE) { throw '웹 빌드 실패' }
    } finally { Pop-Location }
}

# 2) 외부 URL 검사
Write-Host "▶ [2/5] 웹 번들 외부 URL 검사"
$block = 'fonts\.googleapis\.com|fonts\.gstatic\.com|cdn\.jsdelivr\.net|unpkg\.com|cdnjs\.cloudflare\.com|code\.jquery\.com|ajax\.googleapis\.com|cdn\.tailwindcss\.com|use\.fontawesome\.com|api\.iconify\.design|esm\.sh|googletagmanager\.com|google-analytics\.com|sentry\.io'
$hits = Get-ChildItem -Recurse -File $WebDist, (Join-Path $Server 'src\main\resources') -Include *.js, *.css, *.html, *.json, *.yml, *.svg |
    Select-String -Pattern "https?://($block)[^`"' )]*" -AllMatches | ForEach-Object { $_.Matches.Value } | Sort-Object -Unique
if ($hits) { $hits | ForEach-Object { Write-Host "   $_" }; throw '외부 CDN/웹폰트 호출 URL 이 있습니다 (폐쇄망 불가).' }
Write-Host "✅ 외부 호출 차단 대상 URL 없음"

# 3) 서버 jar
Write-Host "▶ [3/5] 서버 jar (웹 포함) + 라이선스 도구"
Push-Location $Server
try {
    .\gradlew.bat --quiet clean bootJar licenseToolJar "-PwebDist=$WebDist"
    if ($LASTEXITCODE) { throw 'gradle 빌드 실패' }
} finally { Pop-Location }

# 4) JRE
Write-Host "▶ [4/5] jlink 로 JRE 21 생성"
$modules = 'java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.transaction.xa,java.xml,java.xml.crypto,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported,jdk.zipfs,jdk.management,jdk.naming.dns,jdk.localedata,jdk.charsets'
& $Jlink --add-modules $modules --include-locales=en,ko --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output (Join-Path $Stage 'jre')
if ($LASTEXITCODE) { throw 'jlink 실패' }

# 5) 구성
Write-Host "▶ [5/5] 패키지 구성"
Copy-Item (Join-Path $Server 'build\libs\pmsx-server.jar') $Stage
New-Item -ItemType Directory -Force (Join-Path $Stage 'license-tool\dev-keys') | Out-Null
Copy-Item (Join-Path $Server 'build\libs\pmsx-license-tool.jar') (Join-Path $Stage 'license-tool')
Copy-Item -Recurse -Force (Join-Path $Deploy 'template\*') $Stage
Copy-Item (Join-Path $Deploy 'license-tool\README.md') (Join-Path $Stage 'license-tool')
foreach ($f in 'public.key', 'dev-license.key', 'README.md') {   # 개인키는 넣지 않는다
    Copy-Item (Join-Path $Deploy "license-tool\dev-keys\$f") (Join-Path $Stage 'license-tool\dev-keys')
}
foreach ($d in 'run', 'logs', 'data') { New-Item -ItemType Directory -Force (Join-Path $Stage $d) | Out-Null }
# 시연용 As-Is 수집 결과 (bin/start-demo 가 적재) — samples/<샘플>/out 의 최신 zip
$DemoDir = Join-Path $Stage 'demo'
New-Item -ItemType Directory -Force $DemoDir | Out-Null
foreach ($s in 'hr-legacy', 'hr-nexacro', 'hr-spa') {
    $z = Get-ChildItem (Join-Path $Root "samples\$s\out") -Filter 'pmsx-*.zip' -File -ErrorAction SilentlyContinue |
        Sort-Object Name | Select-Object -Last 1
    if ($z) { Copy-Item $z.FullName $DemoDir; Write-Host "  데모 As-Is: $($z.Name)" } else { Write-Host "  (데모 As-Is 없음: samples\$s\out)" }
}
"PMS-X $Version`nbuilt: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" | Out-File -Encoding utf8 (Join-Path $Stage 'VERSION.txt')

# Compress-Archive(PS 5.1)는 경로 구분자를 '' 로 써서 Linux 에서 풀 때 문제가 되므로 JDK jar 도구로 묶는다
& (Join-Path $Jdk 'bin\jar.exe') --create --no-manifest --file $Zip -C $Out $Pkg
if ($LASTEXITCODE) { throw 'zip 생성 실패' }
$size = [math]::Round((Get-Item $Zip).Length / 1MB, 1)
Write-Host "✅ 완료: $Zip ($size MB)"
