@echo off
rem ──────────────────────────────────────────────────────────────
rem  ACTAS 전화연동 설치 프로그램 만들기
rem
rem  1) KT SDK + 프로그램을 payload 폴더에 모은다
rem  2) Inno Setup 으로 setup.exe 를 만든다
rem
rem  인증키와 연결키는 여기 적지 않는다. secrets.bat 에 두고 읽는다
rem  (secrets.bat 은 gitignore 돼 있다).
rem ──────────────────────────────────────────────────────────────
setlocal enabledelayedexpansion
chcp 65001 > nul
cd /d "%~dp0"

echo.
echo  ACTAS 전화연동 설치 프로그램 만들기
echo  ----------------------------------------

rem ── 인증키·연결키 읽기 ──
if not exist "secrets.bat" (
  echo.
  echo  [실패] secrets.bat 이 없습니다.
  echo.
  echo  secrets.bat.sample 을 secrets.bat 으로 복사하고
  echo  인증키와 연결키를 채워주세요.
  echo.
  pause
  exit /b 1
)
call "%~dp0secrets.bat"

if "%KT_AUTHKEY%"=="" ( echo  [실패] secrets.bat 에 KT_AUTHKEY 가 비어 있습니다. & pause & exit /b 1 )
if "%ELV_SECRET%"=="" ( echo  [실패] secrets.bat 에 ELV_SECRET 이 비어 있습니다. & pause & exit /b 1 )
if "%ELV_URL%"==""    ( set "ELV_URL=https://actas-ai.co.kr/elv/api/AS/cti/event" )

rem ── 모아 올 곳 ──
set "SDK=%~dp0..\..\..\..\..\이재욱\elv\통화매니저 API"
set "BUILD=%~dp0..\csharp\bin\Release"

if not exist "%SDK%\KTOpenAPI.dll" (
  echo.
  echo  [실패] KT SDK 를 찾지 못했습니다.
  echo         %SDK%
  echo.
  echo  SDK 폴더 위치가 바뀌었으면 이 파일의 SDK 경로를 고쳐주세요.
  echo.
  pause
  exit /b 1
)
if not exist "%BUILD%\ACTAS전화연동.exe" (
  echo.
  echo  [실패] 프로그램이 빌드돼 있지 않습니다.
  echo         _scripts\cti\csharp\build.bat 을 먼저 실행해주세요.
  echo.
  pause
  exit /b 1
)

rem ── payload 모으기 ──
echo  파일을 모읍니다...
if exist "payload" rmdir /s /q "payload"
mkdir "payload"

rem KT SDK — dll/ocx 전부. 하나라도 빠지면 PC 에 따라 1602 오류가 난다.
copy /y "%SDK%\*.dll" "payload\" > nul
copy /y "%SDK%\*.ocx" "payload\" > nul

rem 오픈소스 고지 — MIT·Apache 2.0 이 배포본 포함을 요구한다. 빼면 안 된다.
copy /y "%SDK%\oss_notice.html" "payload\" > nul

rem 우리가 만든 것 (SDK 폴더의 옛 사본을 덮어쓴다)
copy /y "%BUILD%\ACTAS전화연동.exe"      "payload\" > nul
copy /y "%BUILD%\Interop.KTOpenAPI.dll"  "payload\" > nul

rem 실행하며 생기는 파일은 넣지 않는다 — 그 PC 의 상태와 로그인 흔적이다
del /q "payload\*.dat" 2> nul
del /q "payload\*.bip" 2> nul

for /f %%c in ('dir /b "payload" ^| find /c /v ""') do set FILES=%%c
echo  payload: !FILES! 개

if not exist "payload\KTOpenAPI.dll" ( echo  [실패] KTOpenAPI.dll 이 빠졌습니다. & pause & exit /b 1 )
if not exist "payload\oss_notice.html" ( echo  [실패] oss_notice.html 이 빠졌습니다. & pause & exit /b 1 )

rem ── Inno Setup ──
set "ISCC=%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe"
if not exist "%ISCC%" set "ISCC=%ProgramFiles%\Inno Setup 6\ISCC.exe"
if not exist "%ISCC%" (
  echo.
  echo  [실패] Inno Setup 6 을 찾지 못했습니다.
  echo         https://jrsoftware.org/isdl.php 에서 설치해주세요.
  echo.
  pause
  exit /b 1
)

echo  설치 프로그램을 만듭니다...
"%ISCC%" /Qp ^
  "/DKtAuthKey=%KT_AUTHKEY%" ^
  "/DElvSecret=%ELV_SECRET%" ^
  "/DElvUrl=%ELV_URL%" ^
  "ACTAS전화연동.iss"

if errorlevel 1 (
  echo.
  echo  [실패] 빌드 중 오류가 있습니다. 위 메시지를 확인해주세요.
  echo.
  pause
  exit /b 1
)

echo.
echo  ----------------------------------------
echo  [완료] _build\installer\ 를 확인해주세요.
echo.
echo  사업체에는 이 setup 파일 하나만 보내면 됩니다.
echo.
pause
