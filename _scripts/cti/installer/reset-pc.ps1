# ─────────────────────────────────────────────────────────────
#  ACTAS 전화연동 — PC 초기화
#
#  설치했던 흔적을 지워 '처음 설치하는 PC' 상태로 되돌린다.
#  수동으로 폴더를 복사해 쓰던 PC, 설치 프로그램으로 깐 PC 둘 다 된다.
#
#  쓰는 법: 파워셸을 관리자 권한으로 열고
#      powershell -ExecutionPolicy Bypass -File reset-pc.ps1
#
#  COM 등록 해제에 관리자 권한이 필요하다. 아니면 레지스트리에 찌꺼기가 남는다.
# ─────────────────────────────────────────────────────────────

$ErrorActionPreference = 'Continue'
$CLSID = '{16AB1B2A-A22E-4FAC-92CB-84102DF5CE3D}'

function Say($m) { Write-Host $m }

# 관리자 권한인가
$admin = ([Security.Principal.WindowsPrincipal] `
          [Security.Principal.WindowsIdentity]::GetCurrent()
         ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $admin) {
    Say ''
    Say '  [중단] 관리자 권한이 필요합니다.'
    Say '  파워셸 아이콘을 오른쪽 클릭 → 관리자 권한으로 실행 후 다시 해주세요.'
    Say ''
    pause; exit 1
}

Say ''
Say '  ACTAS 전화연동 — PC 초기화'
Say '  ----------------------------------------'

# ── 1. 실행 중인 것 종료 ──
# DLL 을 물고 있으면 지워지지 않는다. 먼저 끊어야 한다.
$procs = Get-Process -Name 'ACTAS전화연동' -ErrorAction SilentlyContinue
if ($procs) {
    foreach ($p in $procs) {
        try { Stop-Process -Id $p.Id -Force; Say "  종료: PID $($p.Id)" }
        catch { Say "  종료 실패: PID $($p.Id)" }
    }
    Start-Sleep -Seconds 3
} else { Say '  실행 중인 프로그램 없음' }

# 옛 VBScript 판을 쓰던 PC 대비
Get-Process -Name 'wscript','cscript' -ErrorAction SilentlyContinue | ForEach-Object {
    try { Stop-Process -Id $_.Id -Force; Say "  종료: $($_.ProcessName) PID $($_.Id)" } catch { }
}

# ── 2. COM 등록 해제 ──
# regsvr32 는 등록 당시 경로를 레지스트리에 적어 둔다. 거기서 읽어 그대로 푼다.
$found = $null
foreach ($h in @("HKLM:\SOFTWARE\Classes\CLSID\$CLSID\InprocServer32",
                 "HKLM:\SOFTWARE\WOW6432Node\Classes\CLSID\$CLSID\InprocServer32",
                 "HKCU:\SOFTWARE\Classes\CLSID\$CLSID\InprocServer32")) {
    if (Test-Path $h) {
        $found = (Get-ItemProperty $h).'(default)'
        break
    }
}

if ($found) {
    Say "  등록된 경로: $found"
    if (Test-Path $found) {
        $null = Start-Process regsvr32.exe -ArgumentList ('/u /s "' + $found + '"') -Wait -PassThru
        Say '  COM 등록 해제 시도'
    } else {
        Say '  (그 경로에 파일이 없습니다 — 레지스트리만 남은 상태)'
    }
} else { Say '  COM 등록 없음' }

# ── 3. 설치 프로그램으로 깔았으면 제거 ──
$removed = $false
foreach ($u in @('HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
                 'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*')) {
    Get-ItemProperty $u -ErrorAction SilentlyContinue |
      Where-Object { $_.DisplayName -like '*전화연동*' } |
      ForEach-Object {
        Say "  제거 실행: $($_.DisplayName) $($_.DisplayVersion)"
        $exe = $_.UninstallString -replace '"',''
        if (Test-Path $exe) {
            Start-Process $exe -ArgumentList '/VERYSILENT','/NORESTART' -Wait
            $removed = $true
        }
      }
}
if (-not $removed) { Say '  설치 프로그램으로 깐 흔적 없음' }

# ── 4. 시작프로그램 ──
foreach ($d in @([Environment]::GetFolderPath('Startup'),
                 [Environment]::GetFolderPath('CommonStartup'))) {
    Get-ChildItem $d -ErrorAction SilentlyContinue |
      Where-Object { $_.Name -like '*전화연동*' } |
      ForEach-Object { Remove-Item $_.FullName -Force; Say "  삭제: $($_.Name)" }
}

# ── 5. 설정과 로그 ──
# 비밀번호는 DPAPI 로 묶여 있어 다른 PC 에서 못 푼다. 지워도 된다.
$data = "$env:LOCALAPPDATA\ACTAS"
if (Test-Path $data) {
    Remove-Item $data -Recurse -Force
    Say '  설정·로그 삭제'
} else { Say '  설정 없음' }

# ── 6. 남은 폴더 ──
# 어디에 풀었는지는 PC 마다 달라 자동으로 지우지 않는다. 찾아서 알려만 준다.
Say ''
if ($found) {
    $dir = Split-Path $found
    if (Test-Path $dir) {
        Say '  아직 남은 폴더가 있습니다. 확인 후 지워주세요:'
        Say ''
        Say "      Remove-Item '$dir' -Recurse -Force"
        Say ''
    }
}

Say '  ----------------------------------------'
Say '  [완료] 이제 설치 프로그램으로 새로 깔 수 있습니다.'
Say ''
pause
