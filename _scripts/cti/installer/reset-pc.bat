@echo off
rem ──────────────────────────────────────────────────────────────
rem  ACTAS 전화연동 — PC 초기화 (두 번 눌러 실행)
rem
rem  관리자 권한이 필요해서, 아니면 스스로 다시 띄운다.
rem  reset-pc.ps1 과 같은 폴더에 두어야 한다.
rem ──────────────────────────────────────────────────────────────
cd /d "%~dp0"

rem 관리자 권한인지 확인하고, 아니면 권한을 올려 다시 실행
net session >nul 2>&1
if errorlevel 1 (
  echo  관리자 권한으로 다시 실행합니다...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

if not exist "%~dp0reset-pc.ps1" (
  echo.
  echo  [실패] reset-pc.ps1 이 같은 폴더에 없습니다.
  echo.
  pause
  exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0reset-pc.ps1"
