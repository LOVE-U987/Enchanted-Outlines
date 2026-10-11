@echo off
rem ============================================================
rem  Enchanted Outlines · VERIFY 自动化验证系统 一键入口
rem
rem    verify.bat                  编译门禁 + 铁律扫描 + 语言键一致性
rem    verify.bat --no-compile     跳过 gradle（秒级）
rem    verify.bat --only rules     只跑铁律扫描
rem    verify.bat --json           CI / 工具消费
rem    verify.bat --list           列出全部检查项
rem
rem  退出码：0 = 通过（可能含告警）  1 = 存在铁律违规  2 = 环境问题
rem ============================================================
setlocal enabledelayedexpansion
chcp 65001 >nul

set "PY="
where python >nul 2>nul
if !ERRORLEVEL! equ 0 set "PY=python"
if not defined PY (
    where py >nul 2>nul
    if !ERRORLEVEL! equ 0 set "PY=py -3"
)

if not defined PY (
    echo [VERIFY] 未找到 Python 3.8+，请安装 Python 并加入 PATH。
    exit /b 2
)

%PY% "%~dp0tools\verify.py" %*
exit /b !ERRORLEVEL!
