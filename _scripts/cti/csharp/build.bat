@echo off
rem ──────────────────────────────────────────────────────────────
rem  ACTAS 전화연동 빌드
rem
rem  이 파일을 두 번 눌러 실행하면 bin\Release\ 에 프로그램이 만들어진다.
rem  Visual Studio 에서 빌드한 것과 결과가 같다 (같은 MSBuild 를 쓴다).
rem ──────────────────────────────────────────────────────────────
setlocal
chcp 65001 > nul
cd /d "%~dp0"

echo.
echo  ACTAS 전화연동 빌드
echo  ----------------------------------------

rem Visual Studio 가 깔린 위치는 PC 마다 달라서 vswhere 로 찾는다
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%VSWHERE%" set "VSWHERE=%ProgramFiles%\Microsoft Visual Studio\Installer\vswhere.exe"

set "MSBUILD="
if exist "%VSWHERE%" (
  for /f "usebackq delims=" %%i in (`"%VSWHERE%" -latest -requires Microsoft.Component.MSBuild -find MSBuild\**\Bin\MSBuild.exe`) do set "MSBUILD=%%i"
)

if not defined MSBUILD (
  echo.
  echo  [실패] MSBuild 를 찾지 못했습니다.
  echo.
  echo  Visual Studio Community 를 설치하고, 설치 화면에서
  echo  ".NET 데스크톱 개발" 을 선택했는지 확인해주세요.
  echo.
  pause
  exit /b 1
)

echo  MSBuild: %MSBUILD%
echo.

"%MSBUILD%" "ACTAS전화연동.csproj" /p:Configuration=Release /p:Platform=x86 /v:minimal /nologo
if errorlevel 1 (
  echo.
  echo  [실패] 빌드 중 오류가 있습니다. 위 메시지를 확인해주세요.
  echo.
  pause
  exit /b 1
)

echo.
echo  ----------------------------------------
echo  [완료] bin\Release\ACTAS전화연동.exe
echo.
echo  사업체에 보낼 때는 bin\Release 폴더를 그대로 압축해주세요.
echo  (Interop.KTOpenAPI.dll 이 같이 있어야 실행됩니다)
echo.
pause
