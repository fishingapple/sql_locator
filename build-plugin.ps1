# Slow SQL Locator IDEA Plugin Builder

$ErrorActionPreference = "Stop"

Write-Host "========================================"
Write-Host " Slow SQL Locator IDEA Plugin Builder"
Write-Host "========================================"
Write-Host ""

$ProjectRoot = $PSScriptRoot
$CoreSrc = Join-Path $ProjectRoot "core\src\main\java"
$PluginSrc = Join-Path $ProjectRoot "idea-plugin\src\main\java"
$PluginRes = Join-Path $ProjectRoot "idea-plugin\src\main\resources"
$BuildDir = Join-Path $ProjectRoot "build"
$ClassesDir = Join-Path $BuildDir "classes"
$PluginDir = Join-Path $BuildDir "slow-sql-locator"

# 解析 javac：优先 PATH，其次 JAVA_HOME，最后 ~/.jdks 下的 JDK
$Javac = "javac"
if (-not (Get-Command javac -ErrorAction SilentlyContinue)) {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\javac.exe")) {
        $Javac = "$env:JAVA_HOME\bin\javac.exe"
    } else {
        $cand = Get-ChildItem "$env:USERPROFILE\.jdks\*\bin\javac.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($cand) { $Javac = $cand.FullName }
    }
}
Write-Host "Using javac: $Javac"

# jar 与 javac 同目录
$JarTool = "jar"
if ($Javac -ne "javac") { $JarTool = Join-Path (Split-Path $Javac -Parent) "jar.exe" }

# 关键：产出 Java 8 字节码（v52），保证 JBR 8/11/17/21 均可加载
$ReleaseArgs = @("--release", "8", "-encoding", "UTF-8")

Write-Host "[1/5] Cleaning build directory..."
if (Test-Path $BuildDir) { Remove-Item -Recurse -Force $BuildDir }
New-Item -ItemType Directory -Force -Path $ClassesDir | Out-Null
New-Item -ItemType Directory -Force -Path "$PluginDir\lib" | Out-Null
New-Item -ItemType Directory -Force -Path "$PluginDir\META-INF" | Out-Null

Write-Host "[2/5] Compiling core module..."
$CoreFiles = Get-ChildItem -Path "$CoreSrc\com\loc" -Filter "*.java" -File
$CorePaths = $CoreFiles | ForEach-Object { $_.FullName }
& $Javac @ReleaseArgs -d $ClassesDir $CorePaths
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: Core module compilation failed"
    exit 1
}

Write-Host "[3/5] Compiling plugin module..."
$IDEAHome = $env:IDEA_HOME
if (-not $IDEAHome -and (Test-Path (Join-Path $ProjectRoot "idea-sdk\lib"))) {
    # 未设置 IDEA_HOME 时，回退到仓库内置的 idea-sdk
    $IDEAHome = Join-Path $ProjectRoot "idea-sdk"
    Write-Host "IDEA_HOME not set, fallback to bundled idea-sdk"
}
if ($IDEAHome) {
    $IDEALib = Join-Path $IDEAHome "lib"
    $PluginFiles = Get-ChildItem -Path "$PluginSrc\com\loc" -Filter "*.java" -File
    $PluginPaths = $PluginFiles | ForEach-Object { $_.FullName }
    $ClassPath = "$ClassesDir;$IDEALib\*"
    & $Javac @ReleaseArgs -cp $ClassPath -d $ClassesDir $PluginPaths
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ERROR: Plugin compilation failed"
        exit 1
    }
} else {
    Write-Host "ERROR: No IntelliJ SDK found (set IDEA_HOME or keep idea-sdk folder)"
    exit 1
}

Write-Host "[4/5] Packaging core JAR..."
Push-Location $ClassesDir
$JarName = "$PluginDir\lib\sql-locator-core.jar"
& $JarTool cf $JarName com
Pop-Location

Write-Host "[5/5] Copying plugin descriptor..."
Copy-Item "$PluginRes\META-INF\plugin.xml" "$PluginDir\META-INF\plugin.xml" -Force

Write-Host ""
Write-Host "========================================"
Write-Host "  Build Complete!"
Write-Host "========================================"
Write-Host ""
Write-Host "Plugin location: $PluginDir"
Write-Host ""
Write-Host "Installation:"
Write-Host "  1. Open IntelliJ IDEA"
Write-Host "  2. File > Settings > Plugins"
Write-Host "  3. Gear icon > Install Plugin from Disk..."
Write-Host "  4. Select: $BuildDir\slow-sql-locator.zip"
Write-Host ""
Write-Host "Creating plugin ZIP..."
# 注意：不能用 Compress-Archive —— 它生成的 zip 用反斜杠作路径分隔符，
# IDEA 插件安装器会报 corrupted archive (no file entries)。必须用 jar 打包。
Push-Location $BuildDir
& $JarTool cMf "$BuildDir\slow-sql-locator.zip" slow-sql-locator
Pop-Location

Write-Host "Done: $BuildDir\slow-sql-locator.zip"
Write-Host "========================================"
