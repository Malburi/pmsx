# pmsx-collector.exe 단일 실행파일 빌드 (Windows, PyInstaller)
#   사용: powershell -ExecutionPolicy Bypass -File build.ps1 [-Python .venv\Scripts\python.exe]
#   결과: dist\pmsx-collector.exe
#   - 브라우저는 포함하지 않는다(고객 PC의 Chrome/Edge 사용: capture.browserChannel / capture.browserPath).
#   - Playwright 드라이버(node)는 포함된다.
param(
    [string]$Python = ".venv\Scripts\python.exe"
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not (Test-Path $Python)) { throw "Python 실행파일을 찾을 수 없습니다: $Python (먼저 venv 를 만들고 requirements-dev.txt 를 설치하세요)" }

# 브라우저를 번들하지 않도록 (PLAYWRIGHT_BROWSERS_PATH=0 이면 hook 이 브라우저를 포함함)
Remove-Item Env:PLAYWRIGHT_BROWSERS_PATH -ErrorAction SilentlyContinue

& $Python -m PyInstaller --noconfirm --clean --onefile --console `
    --name pmsx-collector `
    --distpath dist --workpath build --specpath build `
    --add-data "$PSScriptRoot\pmsx_collector\schema;pmsx_collector\schema" `
    --collect-submodules sqlglot.dialects `
    --hidden-import tree_sitter_java --hidden-import tree_sitter_javascript --hidden-import tree_sitter_typescript `
    --hidden-import tree_sitter_c_sharp `
    --hidden-import pmsx_collector.parsers.websquare --hidden-import pmsx_collector.parsers.xplatform `
    --hidden-import pmsx_collector.parsers.winforms --hidden-import pmsx_collector.parsers.aspnet `
    --hidden-import pmsx_collector.parsers.dotnet --hidden-import pmsx_collector.desktop_capture `
    --collect-submodules pywinauto --hidden-import comtypes.client --hidden-import win32gui --hidden-import win32ui `
    --hidden-import win32process `
    --hidden-import pmsx_collector.wireframe --hidden-import pmsx_collector.parsers.harness --hidden-import PIL.PngImagePlugin `
    --hidden-import psycopg_binary --hidden-import pmsx_collector.capture `
    --hidden-import pmsx_collector.scaffold --hidden-import pmsx_collector.doctor `
    --hidden-import pmsx_collector.upload --hidden-import pmsx_collector.incremental `
    --hidden-import pmsx_collector.parsers.struts1 --hidden-import pmsx_collector.parsers.queryxml --hidden-import pmsx_collector.textio `
    --hidden-import pmsx_collector.render.jsp_static --hidden-import pmsx_collector.render.static_capture `
    --hidden-import pmsx_collector.render.scriptlet_branches --hidden-import playwright.async_api --hidden-import PIL.JpegImagePlugin `
    --exclude-module pytest --exclude-module tkinter --exclude-module matplotlib `
    packaging\entry.py
if ($LASTEXITCODE -ne 0) { throw "PyInstaller 빌드 실패 ($LASTEXITCODE)" }

$exe = Get-Item dist\pmsx-collector.exe
"{0}  {1:N1} MB" -f $exe.FullName, ($exe.Length / 1MB)
& $exe.FullName --help | Select-Object -First 3
