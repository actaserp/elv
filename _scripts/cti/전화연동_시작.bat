@echo off
REM ─────────────────────────────────────────────────────────────
REM  KT 전화 연동 에이전트 — 검은 창 없이 백그라운드로 실행합니다.
REM  로그는 같은 폴더의 kt_agent.log 에 쌓입니다.
REM
REM  자동 시작 등록
REM    1) 윈도우키 + R  →  shell:startup  →  엔터
REM    2) 열린 폴더에 이 파일의 바로가기를 넣으세요
REM ─────────────────────────────────────────────────────────────
cd /d "C:\Users\Park Jinwook\Desktop\이재욱\elv\통화매니저 API"
start "" "C:\Windows\SysWOW64\wscript.exe" //nologo "%~dp0kt_agent.vbs"
