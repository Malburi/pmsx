# 오프라인(폐쇄망) 설치용 wheelhouse 생성 — 인터넷 되는 PC에서 실행해 폴더째 반입
#   사용: powershell -ExecutionPolicy Bypass -File build_wheelhouse.ps1 [-Platform win_amd64] [-PythonVersion 3.13] [-Dev]
#   반입 후 설치(폐쇄망):
#     python -m venv .venv
#     .venv\Scripts\python -m pip install --no-index --find-links wheelhouse -r requirements.txt
#   다른 OS/버전용: -Platform manylinux2014_x86_64 -PythonVersion 3.11
param(
    [string]$Platform = "",
    [string]$PythonVersion = "",
    [string]$Python = ".venv\Scripts\python.exe",
    [switch]$Dev
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
if (-not (Test-Path $Python)) { $Python = "python" }

$req = if ($Dev) { "requirements-dev.txt" } else { "requirements.txt" }
$args = @("-m", "pip", "download", "-r", $req, "-d", "wheelhouse", "--only-binary=:all:")
if ($Platform)      { $args += @("--platform", $Platform) }
if ($PythonVersion) { $args += @("--python-version", $PythonVersion) }

& $Python @args
if ($LASTEXITCODE -ne 0) { throw "pip download 실패 ($LASTEXITCODE)" }
Copy-Item requirements.txt, requirements-dev.txt wheelhouse\ -Force
$n = (Get-ChildItem wheelhouse -Filter *.whl).Count
"wheelhouse: $n 개 wheel  ($((Resolve-Path wheelhouse).Path))"
