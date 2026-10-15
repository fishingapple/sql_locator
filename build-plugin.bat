@echo off
chcp 65001 >nul
echo ========================================
echo  Slow SQL Locator IDEA Plugin Builder
echo ========================================
echo.

set PROJECT_ROOT=%~dp0
set CORE_SRC=%PROJECT_ROOT%core\src\main\java
set PLUGIN_SRC=%PROJECT_ROOT%idea-plugin\src\main\java
set PLUGIN_RES=%PROJECT_ROOT%idea-plugin\src\main\resources
set BUILD_DIR=%PROJECT_ROOT%build
set CLASSES_DIR=%BUILD_DIR%\classes
set PLUGIN_DIR=%BUILD_DIR%\slow-sql-locator

echo [1/5] 清理构建目录...
if exist "%BUILD_DIR%" rmdir /s /q "%BUILD_DIR%"
mkdir "%CLASSES_DIR%"
mkdir "%PLUGIN_DIR%\lib"
mkdir "%PLUGIN_DIR%\META-INF"

echo [2/5] 编译核心模块（Java 8 字节码）...
javac --release 8 -encoding UTF-8 -d "%CLASSES_DIR%" %CORE_SRC%\com\loc\*.java
if errorlevel 1 (
    echo 错误: 核心模块编译失败
    pause
    exit /b 1
)

echo [3/5] 编译插件模块...
REM 未设置 IDEA_HOME 时回退到仓库内置 idea-sdk
if not defined IDEA_HOME (
    if exist "%PROJECT_ROOT%idea-sdk\lib" set IDEA_HOME=%PROJECT_ROOT%idea-sdk
)
if defined IDEA_HOME (
    set IDEA_LIB=%IDEA_HOME%\lib
    javac --release 8 -encoding UTF-8 -cp "%CLASSES_DIR%;%IDEA_LIB%\*" -d "%CLASSES_DIR%" %PLUGIN_SRC%\com\loc\LocateSqlAction.java
    if errorlevel 1 (
        echo 错误: 插件模块编译失败
        pause
        exit /b 1
    )
) else (
    echo 错误: 未找到 IntelliJ SDK，请设置 IDEA_HOME 或保留 idea-sdk 目录
    pause
    exit /b 1
)

echo [4/5] 打包核心 JAR...
cd /d "%CLASSES_DIR%"
jar cf "%PLUGIN_DIR%\lib\sql-locator-core.jar" com

echo [5/5] 复制插件描述文件...
copy "%PLUGIN_RES%\META-INF\plugin.xml" "%PLUGIN_DIR%\META-INF\plugin.xml"

echo.
echo ========================================
echo  构建完成!
echo ========================================
echo  插件位置: %BUILD_DIR%\slow-sql-locator
echo.
echo  安装方法:
echo  1. 打开 IntelliJ IDEA
echo  2. File ^> Settings ^> Plugins
echo  3. 点击齿轮图标 ^> Install Plugin from Disk...
echo  4. 选择: %BUILD_DIR%\slow-sql-locator.zip
echo.
echo  创建插件 ZIP 包...
REM 注意: 不能用 Compress-Archive，它生成的 zip 用反斜杠分隔符，IDEA 会报 corrupted archive
cd /d "%BUILD_DIR%"
jar cMf "%BUILD_DIR%\slow-sql-locator.zip" slow-sql-locator
echo  完成: %BUILD_DIR%\slow-sql-locator.zip
echo ========================================
pause
