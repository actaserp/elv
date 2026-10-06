# ACTAS 전화연동 (C# 트레이 프로그램)

전화 받는 PC 에 상주하면서, KT 통화매니저로 들어온 전화를 elv 서버로 넘긴다.
서버(리눅스)가 KT 를 직접 못 붙는 이유는 `KTOpenAPI.dll` 이 **32bit 윈도우 COM** 이기 때문이다.

```
전화 → KT 통화매니저 → [이 프로그램] → elv 서버 → 고장접수등록 화면
                            └→ 윈도우 알림 (트레이 풍선)
```

## 자바로 치면

| 여기 | 자바 |
|---|---|
| `ACTAS전화연동.csproj` | `pom.xml` |
| `build.bat` | `mvn package` |
| Visual Studio | IntelliJ |
| `Interop.KTOpenAPI.dll` | 외부 jar (KT SDK 에서 생성) |
| `bin\Release\` | `target/` |
| NuGet | Maven Central |

## 고치는 법

**방법 1 — Visual Studio** (코드 보면서 고칠 때)

`ACTAS전화연동.csproj` 를 두 번 누르면 프로젝트로 열린다.
`Ctrl+Shift+B` 가 빌드, `F5` 가 디버그 실행. IntelliJ 와 거의 같다.

**방법 2 — build.bat** (코드는 안 보고 빌드만 할 때)

두 번 눌러 실행하면 `bin\Release\` 에 만들어진다. Visual Studio 와 결과가 같다.

빌드하기 전에 **실행 중인 프로그램을 트레이에서 종료**해야 한다.
안 하면 exe 가 잠겨 있어 "다른 프로세스가 사용 중" 으로 실패한다.

## 파일별 역할

| 파일 | 하는 일 |
|---|---|
| `Program.cs` | 트레이 아이콘, 메뉴(연결/해제/설정/로그/종료), 윈도우 알림 |
| `KtAgent.cs` | KT COM 붙잡기, 로그인, 전화 수신 이벤트 → 서버 전송 |
| `Config.cs` | 설정 저장·읽기. 비밀번호는 DPAPI 로 이 PC 에서만 풀리게 암호화 |
| `Codes.cs` | KT 규격서의 반환값·상태값 해석 + 로그 기록 |
| `SettingsForm.cs` | 설정 창 |
| `AssemblyInfo.cs` | 버전 번호 |

고칠 일이 생기면 대부분 이 둘 중 하나다.

- **화면에 뜨는 내용·알림** → `Program.cs`
- **KT 쪽 동작·서버 전송** → `KtAgent.cs`

KT 가 내려주는 상태값(`201` 수신중, `202` 부재중 …)의 뜻은 `Codes.cs` 에 다 적어뒀다.
근거는 `통화매니저 API 매뉴얼 v1.35` 10.1 절.

## 고칠 때마다 할 일

1. 코드 수정
2. `AssemblyInfo.cs` 의 버전 올리기 — 기능 추가는 가운데(`1.1.0`), 버그 수정은 끝자리(`1.0.1`)
3. 빌드
4. 커밋

버전을 올려야 하는 이유: 사업체가 문제를 알려올 때 **어느 버전을 쓰는지** 알아야 한다.
프로그램이 시작할 때 로그 첫 줄에 버전이 남는다.

## 사업체 배포

`bin\Release` 폴더를 그대로 압축해서 보낸다. `Interop.KTOpenAPI.dll` 이 같이 있어야 실행된다.

받는 PC 에서 처음 한 번은 KT SDK 의 `KTOpenAPI.dll` 을 등록해야 한다 (관리자 권한 cmd).

```
regsvr32 "KTOpenAPI.dll 이 있는 경로\KTOpenAPI.dll"
```

> 지금은 사업체마다 사람이 받아서 깔아야 한다. 설치 프로그램과 자동 갱신은 아직 없다.
> 도입 사업체가 늘면 이게 제일 번거로워질 부분이다.

## 문제가 생기면 — 로그

트레이 아이콘 우클릭 → **로그 보기**. 또는

```
%LOCALAPPDATA%\ACTAS\전화연동\logs\
```

날짜별로 쌓이고 14일이 지나면 지워진다. 사업체에 이 파일을 보내달라고 하면 된다.

전화가 안 뜰 때 로그에서 볼 것:

| 로그 | 뜻 |
|---|---|
| `[EventLogin] 200` 이 없다 | KT 로그인 실패 — 그 줄의 안내 문구를 보면 된다 |
| `[EventConnect] 0` | 네트웍 끊김. 이 상태에서는 전화가 안 온다 |
| `[전화] caller=... result=201` 이 없다 | KT 에서 전화 자체가 안 왔다 (회선·착신 설정 문제) |
| `event 전송 실패` | 서버 주소나 연결키가 틀렸다 |

## KT 가 SDK 를 새로 주면

`Interop.KTOpenAPI.dll` 은 KT 의 `KTOpenAPI.dll` 을 .NET 이 읽을 수 있게 변환한 것이다.
SDK 가 바뀌면 다시 만든다. TlbImp 는 Visual Studio 와 같이 깔린다.

```
TlbImp.exe "KTOpenAPI.dll" /out:Interop.KTOpenAPI.dll /namespace:KTOpenAPI
```

메서드 이름이나 인자가 바뀌었으면 `KtAgent.cs` 가 컴파일 오류로 알려준다.

## 건드리면 안 되는 것

**`PlatformTarget` 은 `x86` 으로 둔다.** (`.csproj`)

KT COM 이 32bit 라서, 64bit 로 빌드하면 컴파일은 되지만 실행하는 순간
COM 객체를 못 만들고 죽는다. 빌드된 exe 가 32bit 인지 확인하려면
`bin\Release` 에서 프로그램을 띄워보면 바로 안다.
