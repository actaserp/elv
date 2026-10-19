; ─────────────────────────────────────────────────────────────
;  ACTAS 전화연동 설치 프로그램
;
;  빌드: build-installer.bat  (ISCC.exe 가 이 파일을 읽는다)
;
;  하는 일
;    - KT SDK 와 프로그램을 한 폴더에 푼다 (흩어 두면 1700 오류)
;    - KTOpenAPI.dll 을 regsvr32 로 등록한다 (관리자 권한)
;    - 시작프로그램에 등록한다 (빠뜨리면 다음날 전화가 안 온다)
;    - 서버 설정(인증키·주소·연결키)을 미리 넣어 둔다
;
;  사업체가 입력하는 건 KT 아이디·비밀번호 둘뿐이다.
; ─────────────────────────────────────────────────────────────

#define AppName      "ACTAS 전화연동"
#define AppVersion   "2.3.0"
#define Publisher    "ACTAS"
#define ExeName      "ACTAS전화연동.exe"

; 배포할 파일이 모여 있는 폴더. build-installer.bat 가 채워 준다.
#ifndef PayloadDir
  #define PayloadDir "payload"
#endif

; ── 사업체 공통 설정 — 여기만 고치면 전 사업체에 반영된다 ──
#ifndef ElvUrl
  #define ElvUrl "https://actas-ai.co.kr/elv/api/AS/cti/event"
#endif
#ifndef KtAuthKey
  #define KtAuthKey ""
#endif
#ifndef ElvSecret
  #define ElvSecret ""
#endif

[Setup]
AppId={{8F3C1D92-4A77-4E5B-9C31-2B6E8A1D47F0}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#Publisher}
DefaultDirName={autopf}\ACTAS\전화연동
DefaultGroupName=ACTAS
DisableProgramGroupPage=yes
OutputDir=..\..\..\_build\installer
OutputBaseFilename=ACTAS전화연동_설치_{#AppVersion}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern

; KT COM 이 32bit 다. 64bit 폴더에 깔면 안 된다.
ArchitecturesInstallIn64BitMode=

; regsvr32 와 Program Files 쓰기 때문에 관리자 권한이 필요하다
PrivilegesRequired=admin

UninstallDisplayName={#AppName}
UninstallDisplayIcon={app}\{#ExeName}

; 돌고 있는 프로그램이 KT DLL 을 잡고 있으면 덮어쓰기가 실패한다.
; 그대로 [무시] 하고 넘어가면 새 exe 와 옛 DLL 이 섞여 설치되고,
; 그 조합은 실행 중에야 엉뚱한 오류로 드러나 원인을 찾기 어렵다.
; 이 이름은 Program.cs 의 Mutex 와 같아야 한다 — 바꾸면 여기도 같이 바꿀 것.
AppMutex=ACTAS_전화연동_single
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "korean"; MessagesFile: "compiler:Languages\Korean.isl"

[Files]
; KT SDK 와 프로그램 전부. 하나라도 빠지면 PC 에 따라 1602 오류가 난다.
Source: "{#PayloadDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs

[Icons]
Name: "{group}\{#AppName}";          Filename: "{app}\{#ExeName}"
Name: "{autodesktop}\{#AppName}";    Filename: "{app}\{#ExeName}"; Tasks: desktopicon
; 시작프로그램 — PC 를 켜면 저절로 뜬다
Name: "{userstartup}\{#AppName}";    Filename: "{app}\{#ExeName}"; Tasks: autostart

[Tasks]
Name: "autostart";   Description: "PC 를 켤 때 자동으로 실행 (권장)"; GroupDescription: "추가 작업:"
Name: "desktopicon"; Description: "바탕화면에 바로가기 만들기";        GroupDescription: "추가 작업:"; Flags: unchecked

[Run]
Filename: "{app}\{#ExeName}"; Description: "지금 전화연동 실행"; Flags: nowait postinstall skipifsilent

[UninstallRun]
; 지울 때 COM 등록을 풀어 준다. 안 풀면 레지스트리에 없는 경로가 남는다.
Filename: "{sys}\regsvr32.exe"; Parameters: "/u /s ""{app}\KTOpenAPI.dll"""; Flags: runhidden; RunOnceId: "UnregKtDll"

[Code]
var
  PageKt: TInputQueryWizardPage;

procedure InitializeWizard;
begin
  PageKt := CreateInputQueryPage(wpSelectTasks,
    'KT 통화매니저 계정',
    '회선청약 때 받으신 계정을 넣어주세요.',
    '전화번호@kt.com 형식입니다. 모르시면 담당자에게 문의해주세요.' + #13#10 +
    '나머지 설정은 이미 들어 있어 입력하지 않으셔도 됩니다.');

  PageKt.Add('아이디',   False);
  PageKt.Add('비밀번호', True);
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  if CurPageID = PageKt.ID then
  begin
    if Trim(PageKt.Values[0]) = '' then
    begin
      MsgBox('KT 아이디를 넣어주세요.', mbError, MB_OK);
      Result := False;
    end
    else if Pos('@', PageKt.Values[0]) = 0 then
    begin
      MsgBox('전화번호@kt.com 형식이어야 합니다.', mbError, MB_OK);
      Result := False;
    end
    else if Trim(PageKt.Values[1]) = '' then
    begin
      MsgBox('KT 비밀번호를 넣어주세요.', mbError, MB_OK);
      Result := False;
    end;
  end;
end;

{ 설정 파일을 미리 만들어 둔다.
  비밀번호는 프로그램이 처음 뜰 때 DPAPI 로 다시 저장한다 —
  설치 프로그램에서는 암호화할 수단이 없어 평문으로 적고, 프로그램이 곧 덮어쓴다. }
procedure WriteSettings;
var
  Dir, Path, S: String;
begin
  Dir  := ExpandConstant('{localappdata}\ACTAS\전화연동');
  Path := Dir + '\settings.conf';
  ForceDirectories(Dir);

  S := '# ACTAS 전화연동 설정 — 설치 프로그램이 만들었습니다.' + #13#10 +
       '# 프로그램의 [설정] 에서 고치세요. 비밀번호는 처음 실행할 때 암호화됩니다.' + #13#10 +
       'KT_SERVER    = 1' + #13#10 +
       'KT_AUTHKEY   = ' + '{#KtAuthKey}' + #13#10 +
       'KT_LOGIN_ID  = ' + Trim(PageKt.Values[0]) + #13#10 +
       'KT_LOGIN_PW  = ' + PageKt.Values[1] + #13#10 +
       'ELV_URL      = ' + '{#ElvUrl}' + #13#10 +
       'ELV_SECRET   = ' + '{#ElvSecret}' + #13#10 +
       'AUTO_LOGIN   = true' + #13#10 +
       'WINDOW_TITLE = ' + #13#10 +
       'DEBUG_TABS   = 0' + #13#10;

  SaveStringToFile(Path, S, False);
end;

{ KT COM 등록.
  KT 안내대로 관리자 권한으로 시도하되, 실패해도 설치를 멈추지 않는다.
  권한·백신 때문에 막히는 PC 가 있어서, 그때는 손으로 할 수 있게 알려만 준다. }
procedure RegisterKtDll;
var
  Code: Integer;
  Dll:  String;
begin
  Dll := ExpandConstant('{app}\KTOpenAPI.dll');

  if not FileExists(Dll) then
  begin
    MsgBox('KTOpenAPI.dll 을 찾을 수 없습니다.' + #13#10 +
           '설치 파일이 온전하지 않습니다. 담당자에게 문의해주세요.', mbError, MB_OK);
    Exit;
  end;

  if Exec(ExpandConstant('{sys}\regsvr32.exe'), '/s "' + Dll + '"',
          '', SW_HIDE, ewWaitUntilTerminated, Code) and (Code = 0) then
    Exit;

  MsgBox('전화 장치 등록에 실패했습니다. (코드 ' + IntToStr(Code) + ')' + #13#10 + #13#10 +
         '보안 설정 때문일 수 있습니다. 아래를 직접 해주세요.' + #13#10 + #13#10 +
         '1. 시작 → cmd 를 마우스 오른쪽 → 관리자 권한으로 실행' + #13#10 +
         '2. 다음을 붙여넣고 엔터' + #13#10 + #13#10 +
         '   regsvr32 "' + Dll + '"' + #13#10 + #13#10 +
         '그래도 안 되면 담당자에게 문의해주세요.', mbError, MB_OK);
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
  begin
    RegisterKtDll;
    WriteSettings;
  end;
end;
