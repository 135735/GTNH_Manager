@echo off
rem 构建入口：绕过执行策略调用 build.ps1（本机默认禁止运行 .ps1）
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
