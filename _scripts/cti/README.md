# KT 전화연동 (CTI)

고장접수등록 화면(`AS/web_request.html`)에 전화가 뜨고, 발신번호로 고객을 찾아 주고,
문자를 보내는 기능 전체의 설명서. 인수인계용.

C# 에이전트만 따로 보려면 [csharp/README.md](csharp/README.md).

---

## 1. 왜 이런 구조인가

KT 통화매니저는 **32비트 윈도우 COM**(`KTOpenAPI.dll`)이다. REST 주소가 없다 —
규격서 151쪽 전체에 HTTP 엔드포인트가 한 줄도 없다. elv 서버는 리눅스라 이 DLL을 올릴 수 없다.

그래서 **전화 받는 PC가 COM을 물고 있고**, 전화가 오면 elv 서버로 넘기고, 서버가 담당자 화면에 띄운다.
문자도 같은 이유로 PC가 보낸다.

```
브라우저 화면  ←→  elv 서버(리눅스)  ←→  PC 에이전트(C#, 32bit)  ←→  KT
```

> 예전 `KtCallService` 가 `/api/v1/login` 같은 주소를 두드리고 있었는데 규격서에 없는 주소다.
> 지금은 동작하지 않는 채로 보관만 하고 있다(사용자 요청).

### 통신 경로

| 길 | 방향 | 하는 일 |
|---|---|---|
| `GET  /api/AS/cti/stream` | 서버 → 화면 | SSE. 전화 수신, 에이전트 생사, 문자 결과 |
| `POST /api/AS/cti/event` | 에이전트 → 서버 | 전화가 왔다 |
| `GET  /api/AS/cti/agent-poll` | 에이전트 → 서버 | 3초마다 명령을 가져감 |
| `POST /api/AS/cti/agent-online` `/agent-offline` | 에이전트 → 서버 | 30초마다 생사 신고 |
| `POST /api/AS/cti/connect` `/disconnect` | 화면 → 서버 | 명령을 적어둠 |
| `POST /api/AS/cti/sms` | 화면 → 서버 | 문자 명령을 적어둠 |
| `POST /api/AS/cti/sms-result` | 에이전트 → 서버 | 발송 결과 |
| `POST /api/AS/cti/mock` | 화면 → 서버 | 모의 수신 (개발용) |

브라우저는 PC 프로그램을 직접 못 부른다. 그래서 화면이 서버에 **명령을 적어두고**
에이전트가 3초 안에 가져가 실행한다. 명령 유효시간은 3분(`COMMAND_TTL_MS`) —
퇴근 전에 누른 [연결]이 다음날 부팅 때 되살아나 남의 세션을 밀어내는 것을 막는다.

### 세션 없는 경로는 세 곳에 등록해야 한다

`event` · `agent-online` · `agent-offline` · `agent-poll` · `sms-result` 는 세션이 없다.
`X-Cti-Secret` 헤더로만 가린다. 새 경로를 추가하면 **세 곳 모두** 넣어야 한다.

- `SecurityConfiguration.java` — CSRF 예외, permitAll (두 군데)
- `WebMvcConfig.java` — SpjangSecurityInterceptor 제외

시크릿은 `cti.agent.secret` → `C:\Temp\mes21\conf\server_elv.env` 의 `cti_agent_secret`.
**비어 있으면 에이전트 호출이 전부 503으로 막힌다**(기본 닫힘).

---

## 2. 기능별 구조

| 기능 | 주소 | 보는 테이블 |
|---|---|---|
| 전화 수신 카드 | SSE `/cti/stream` | — (에이전트가 보낸 값 그대로) |
| 발신번호 조회 | `/web_request/caller_info` | E601CALL · E611 · E601 · XCLIENT · CALLMAIN |
| 현장 수리내역 | `/web_request/site_history` | E401 + E411 + E010 + JA001 |
| 전화번호부 | `/web_request/phonebook_list` `_save` `_delete` | E601CALL + E601CALL_01 |
| 보수현장조회 | `/web_request/site_search` | E601 + E611 + JA001 |
| 콜백리스트 | `/web_request/callback_list` `callback_done` | CALLMAIN |
| 문자전송 | `/cti/sms` → 에이전트 | JA001 (받는 사람 번호만) |
| 사원 연락처 | `/web_request/person_tel` | JA001 |

전부 **사업체 MSSQL**. 본사 PostgreSQL이 아니다.

> `admin` 계정은 db_key가 `ZZ`라 등록된 사업체가 아니어서 조회가 전부 본사로 샌다.
> 테스트는 사업체 계정(`kyoungelv` 등)으로 해야 한다.

---

### 2.1 발신번호 조회 — `WebRequestService.findCallerInfo()`

다섯 군데를 **한 질의로** 묻는다. 우선순위(`pri`)를 붙여 `UNION ALL` 하고 `ORDER BY pri`.

| 순위 | 출처 | 보는 칸 | 왜 이 순서 |
|---|---|---|---|
| 1 | 전화번호부 | `E601CALL_01.tel`, `E601CALL.tel` | 사람이 직접 등록한 표라 가장 정확 |
| 2 | 승강기 비상통화 | `E611.emtelnum` | 갇힌 사람 전화 — 현장보다 급하다 |
| 3 | 현장 | `E601.tel`, `E601.hp` | actcd가 확정돼 고장접수로 이어진다 |
| 4 | 거래처 | `XCLIENT.telnum/hptelnum/opertel` | cltcd 확정 |
| 5 | 과거 통화이력 | `CALLMAIN.callnum` | 수기 입력이라 정확도가 낮다 |

**왜 한 질의인가** — 다섯 번 나눠 물었더니 첫 조회가 5~8초 걸렸다. 벨이 울리는 동안
번호만 뜨고 이름이 늦게 붙는다. 두 번째 전화가 즉시 뜨는 건 JS의 `callerMemo` 캐시 때문이다.

**칸 구성은 못 바꾼다** — `pri, source, actcd, actnm, callnm, equpnm, cltcd, cltnm, tel`.
UNION으로 묶이므로 하나라도 바꾸면 전부 바꿔야 한다. 각 분기는 `PHONEBOOK` / `EMERGENCY` /
`SITE` / `CLIENT` / `CALLLOG` 상수.

**번호 비교는 2중** — `IN (:cands)`(자바에서 `031-967-3884`, `(02)2664-6874` 형태 생성, 색인을 탈 수 있다)
\+ `REPLACE` 그물(괄호·공백 등 별난 표기용, 색인은 못 탄다).

**조인 키**

```sql
-- 전화번호부: 네 칸 전부 (custcd 빠뜨리면 안 된다, 아래 3.2 참고)
b.custcd = a.custcd AND b.spjangcd = a.spjangcd
AND b.actcd = a.actcd AND b.seq = a.seq

-- 비상통화: GROUP BY actcd 로 묶는다
E611 m → E601 e  ON spjangcd + actcd

-- 현장·거래처·통화이력: 단독 (XCLIENT·CALLMAIN 은 spjangcd 조건이 없다, 원본 그대로)
```

**`emtelnum`은 호기별이 아니라 현장당 하나다.** 도래울2단지는 호기 8개가 `031-967-3884`를 공유한다.
`TOP 1`로 호기를 집으면 "201동 비상통화"라는 거짓말이 된다 → `GROUP BY actcd`로 묶어
`cnt=1`일 때만 호기를 밝히고, 여럿이면 `8개 호기`로 표시.

**현장 분기의 고객명은 `ancltnm`** — `actnm`에는 `(실패)` `(분담)` `(통합)` 같은 관리용 표기가
섞여 있어 통화메모 고객명으로 부적절하다. `actnm`도 같이 내려보내 화면이 어느 현장인지 보여준다.

#### 모르면 모른다고 한다 — `mergeSite()`

UNION이 `TOP 20`줄을 받아오고 자바에서 합친다. **추가 질의는 없다.**

- **1순위 안에서 이름이 여럿이면** 아무거나 띄우지 않고 후보 목록을 카드에 보여준다(`ambiguous` + `candidates`).
  실측: `010-6358-4436` 한 번호에 등록이 6곳(고양일고등학교·당산한강아파트·광명e편한세상…).
  아무거나 띄우면 받는 사람이 **엉뚱한 고객 이름으로 인사한다.**
- 후보에는 순위 낮은 줄(현장 등)도 넣는다 → 현장이 붙은 후보를 고르면 `[현장 적용]`·`[내역보기]`가 살아난다.
- 구분(현장/거래처)만 다른 같은 이름은 **합치지 않고 그대로** 둔다(받는 사람에게는 정보다).
- **1순위에 현장이 없으면** 뒤쪽 줄에서 보충한다. 단 **현장이 여럿이면 붙이지 않는다** —
  관리업체 한 곳이 건물 여러 채를 맡으면 같은 번호가 현장 서넛에 걸린다.
  틀린 현장으로 고장접수가 들어가는 것보다 비는 편이 낫다.

모호 판정이 나면 `[CTI] 이름이 여럿이라 목록으로 넘깁니다` 로그에 pri/source/callnm이 전부 남는다.
**custcd 버그를 이 로그로 잡았다.**

---

### 2.2 현장 수리내역 — `getSiteHistory()`

카드의 `[내역보기]`. 그 현장의 고장접수 내역 최근 50건.

**접수(E401) 기준**으로 두고 처리(E411)를 LEFT JOIN 한다. 기존 `getCompList`는 처리 기준이라
**미처리 건이 빠진다** — 전화 응대에는 지금 처리 중인 건이 제일 급하다.
`resultck='1'`이면 `완료`, 아니면 `처리중`.

```sql
E401 a
  LEFT JOIN E010  ct ON contcd + spjangcd                        -- 고장구분 이름
  LEFT JOIN E411  e  ON spjangcd + recedate + recenum + actcd    -- 처리결과
  LEFT JOIN JA001 ap ON 'p' + e.perid                            -- 처리자
```

**TB_E401 컬럼 주의** — 고장내용은 `contents`다(`contremark`는 E411 쪽). `custnm`은 없다.

---

### 2.3 전화번호부 — `getPhoneBookList()` / `savePhoneBook()` / `deletePhoneBook()`

머리(`TB_E601CALL`)에 이름·비고·구분, 몸통(`TB_E601CALL_01`)에 번호.

**`actcd`는 묶음, `seq`는 그 안의 연락처.** 한 actcd에 이름이 여럿 들어간다
(`00000002`에 백마한양과 간석두진아파트가 각각 다른 seq).

**`actcd` 첫 자리 = `regflag`**

| regflag | 뜻 | actcd 범위 | 경기 건수 |
|---|---|---|---|
| 0 | 현장 | `0xxxxxxx` | 1,598 |
| 1 | 거래처 | `1xxxxxxx` | 1,374 |
| 2 | 직원 | `2xxxxxxx` | 111 |
| 3 | 일반 | `3xxxxxxx` | **0** |
| 4 | 비상통화 | — | **0** |

예외 없는 규칙이다. 신규 등록은 고른 구분의 번호대에서 채번한다(`nextPhoneBookActcd`).
`일반(3)`이 0건인 건 원본 화면이 쓰인 적이 없다는 뜻이다.

**기존 줄의 구분은 잠가 뒀다** — actcd가 열쇠이고 그 첫 자리가 구분이라, 나중에 구분만 바꾸면
규칙이 깨진다. 구분은 새로 넣을 때만 정한다.

**표시명은 `actmail`** (이메일이 아니라 이름으로 쓰인다).

#### 원본(aprjems)에서 고친 것 셋

| 원본의 문제 | 여기서 |
|---|---|
| 조회·수정·삭제에 `spjangcd`가 없다 | 전부 넣었다 — 없으면 남의 사업체 번호가 보이고 지워진다 |
| 삭제가 `where actcd not in (select actcd from _01)` 로 **홀로 남은 머리를 몽땅** 지운다 | 지울 줄만 지운다 |
| 조인이 `a.actcd = b.actcd` 하나뿐 | 네 칸 모두 — 아니면 엉뚱한 사람 이름이 붙는다 |

---

### 2.4 보수현장조회 — `searchSite()`

**규칙은 하나뿐이다.** 입력한 글자 그대로 각 칸에 `%글자%`.
현장명 · 관리주체 · 주소 · 주소2 · 전화번호 · 휴대전화 · 호기명. 최대 200건.

여기까지 오는 데 세 번 고쳤다 —

1. 숫자만 뽑아 전화번호와도 비교 → `201동`이 `201`로 전화 수백 건을 걸어
   정작 호기가 `TOP 200` 밖으로 밀려났다
2. 띄어쓰기로 쪼개 토큰 AND
3. 숫자만인 토큰은 호기명에서만

사용자 지적: **"번호는 그냥 텍스트의 일부일 뿐이야"**. 쪼개거나 특별취급하면
왜 이 결과가 나왔는지 설명이 안 된다. 대가로 `031-967-4959`는 저장된 그대로 쳐야 맞는다.

호기는 **대수만** 보여주고 현장 단위로 묶는다(26대짜리가 26줄로 쏟아지는 것 방지).
호기 고르기는 기존 호기 팝업 몫.

> 원본 `aprjems`에는 모달(`actactlistmodal`)만 있고 `searchwactlist_rtn()` **함수 정의가 없다.**
> 구현된 적이 없는 기능이라 베낄 원본 없이 설계했다.

---

### 2.5 콜백리스트 · 통화메모

`TB_CALLMAIN` 한 장. 예전 CTI 시대 테이블을 그대로 이어 쓴다(경기 109,761건).

```sql
ISNULL(callbackflag,'') LIKE '%1%'
AND LEN(callbackflag) > 0
AND LEN(callbacktime) > 0
```

콜백 처리를 누르면 `callbackflag`를 `'0'`으로 바꾼다. 지우지 않는다.
`seq` 채번은 `yyyyMM` + 4자리, 길이 10.

**배지 조회는 `getCount` 본 쿼리에서 떼어 뒀다** — 한 쿼리에 합쳐두면 `TB_CALLMAIN`이 없는
사업체에서 금일수신·고장접수·당일처리 배지까지 같이 죽는다(SqlRunner가 오류를 삼키고 null 반환).

---

### 2.6 문자전송

규격서 **5장 전체(90~107p)**가 문자다. 서버가 아니라 PC가 보낸다.

```
화면 POST /cti/sms  →  명령 큐(queueSms)
  →  에이전트 agent-poll 이 {"command":"sms","sms":{to:[],message:""}} 로 집어감
  →  SetRecvPhone × N  →  SendSMS  →  RemoveAllRecvPhone
  →  POST /sms-result  →  SSE "sms-result"  →  화면 팝업
```

- 단문 **80바이트**(한글 40자) · 수신 **32명** · 분당 10건
- 발신번호는 **청약한 그 회선**이어야 한다(4005 오류). 에이전트가 설정의 KT 계정에서 뽑는다
  (`0269595020@kt.com` → `0269595020`). 서버는 발신번호를 모른다.
- **`RemoveAllRecvPhone()`을 빠뜨리면 다음 문자가 같은 사람들에게 또 간다**
- 긴 글은 `SendMMS`, 예약발송은 `SendReserveSMS`
- 기본 수신자는 **통보자**(`perid`/`pernm`) → `/person_tel`로 `TB_JA001.handphone`

반환값은 `Codes.SmsMsg()` 가 사람 말로 바꾼다 —
200 성공 / 2000 로그인 안 됨 / 4001 수신자 없음 / 4003 내용 없음 / 4004 한도 초과 /
4005 발신번호 오류 / 4006 길이 초과.

문자 내용은 쉼표·따옴표·줄바꿈이 섞일 수 있어 **명령 문자열에 끼우지 않고 payload로 따로** 싣는다.

> **웹에서 바로 못 보내나?** 못 한다. 전부 COM이라 리눅스 서버에서 불가.
> 대안은 문자 전문 업체(알리고·솔라피 등) — HTTP API라 서버 직행, 80바이트 제한 없음,
> 전화 프로그램이 꺼져 있어도 나간다. 대신 **건당 요금과 계약**이 든다.
> 지금은 KT 회선에 딸려 있어 추가비용 0.

---

## 3. 테이블 지도

| 테이블 | 담는 것 | 열쇠 | 자주 쓰는 칸 |
|---|---|---|---|
| `TB_E601` | 현장 | spjangcd + actcd | actnm · **ancltnm** · tel · hp · address · perid |
| `TB_E611` | 호기 | + equpcd | equpnm · **emtelnum**(비상통화) |
| `TB_E601CALL` | 전화번호부 머리 | custcd+spjangcd+actcd+seq | **actmail**(이름) · **regflag** · tel · remark |
| `TB_E601CALL_01` | 전화번호부 몸통 | 같은 네 칸 | tel · indate |
| `TB_XCLIENT` | 거래처 | cltcd | cltnm · telnum · hptelnum · opertel |
| `TB_CALLMAIN` | 통화이력 · 메모 | seq | callnum · callnm · callmemo · callbackflag |
| `TB_E401` | 고장접수 | spjangcd+recedate+recenum+actcd | **contents** · **resultck** · equpnm · contcd |
| `TB_E411` | 고장처리 | 같은 네 칸 | compdate · resuremark · contremark · perid |
| `TB_JA001` | 사원 | spjangcd + **'p'+사번** | pernm · handphone |
| `TB_E010` | 고장구분 | contcd + spjangcd | contnm |

경기 DB의 `spjangcd`는 **`ZZ` 하나뿐**이다(E601 914건 / E401 10,704건).

### 조인할 때 지켜야 할 넷

1. **전화번호부는 네 칸 전부로 묶는다** — `custcd + spjangcd + actcd + seq`.
   custcd를 빼면 다른 custcd의 줄이 끌려와 **이름이 여럿으로 보인다.** 두 번 당했다.
2. **사원은 `'p'`를 붙여서 찾는다** — `TB_JA001.perid`는 `'p'+사번`,
   자식 테이블(E411·E037·E038)과 화면은 `'p'` 없이 들고 있다.
3. **전화번호부 `actcd`로 현장을 찾지 않는다** — 자체 번호 체계라 `TB_E601`과 맞지 않는다.
   직접매칭 0건, 8자리 자리맞춤 0건, `cltcd` 자리맞춤은 숫자만 겹치고 이름이 전혀 다르다
   (N-CITY오피스텔 ↔ (주)윤전자). 현장은 **전화번호로** 잇는다.
4. **`spjangcd`를 빠뜨리지 않는다** — 원본 aprjems 쿼리 여럿에 이 조건이 없다.
   그대로 베끼면 남의 사업체 자료가 보이고 지워진다.

### SqlRunner 함정

`getRows` / `getRow` 는 **조회 오류를 삼키고 `null`을 돌려준다.**
그대로 두면 쿼리가 터져도 "내역 없음"으로 보여서 한참 헤맨다.

- `getRows` : 오류면 `null`, 못 찾으면 **빈 목록** → 둘을 구분할 수 있다
- `getRow`  : 둘 다 `null` → 구분 못 한다

그래서 `site_history` · `phonebook_list` · `site_search` 는 컨트롤러에서 `null`이면
`success=false` + 메시지로 내려보낸다. 발신번호 조회는 이 성질을 이용해
`TB_E601CALL`이 없는 사업체면 그 표만 빼고 다시 묻는다.

---

## 4. 전화번호 저장 형식

경기 실측 기준으로 **섞여 있다**.

```
(02)2664-6874     괄호        ← 02 지역번호에 많다
031-962-9578      하이픈
01063584436       생짜
1877-9433         대표번호
--                숫자 없음   ← 이런 줄이 꽤 있다
(02)2658-0052~3   범위 표기   ← 미해결
```

`telCandidates()`가 하이픈·괄호 두 형식을 만들고 `REPLACE` 그물이 나머지를 잡는다.

**미해결**: `~`로 번호 둘을 묶은 표기는 숫자만 남기면 11자리가 되어 어느 번호와도 안 맞는다.
건수를 보고 처리 여부를 정하면 된다.

```sql
SELECT COUNT(*) FROM TB_E601CALL_01 WITH(NOLOCK)
 WHERE spjangcd = 'ZZ' AND tel LIKE '%~%'
```

---

## 5. C# 에이전트 — ACTAS 전화연동

자세한 건 [csharp/README.md](csharp/README.md). 요약만.

| 파일 | 하는 일 |
|---|---|
| `Program.cs` | 트레이 아이콘, 메뉴, 윈도우 풍선 알림, 알림 클릭 처리 |
| `KtAgent.cs` | KT COM 물기, 로그인, 전화 수신 이벤트, 문자 발송, 서버 통신 |
| `WindowFinder.cs` | 알림을 누르면 브라우저 창·탭을 찾아 올린다 |
| `Config.cs` | 설정 읽기·쓰기, 비밀번호 DPAPI 암호화, 탭 단서 만들기 |
| `Codes.cs` | KT 반환값을 사람 말로, 로그 기록 |
| `SettingsForm.cs` | 설정 창 |
| `AssemblyInfo.cs` | 버전 번호 |

고칠 일은 대부분 둘 중 하나 — **화면·알림은 `Program.cs`, KT·서버는 `KtAgent.cs`**.

**`PlatformTarget`은 `x86`으로 둔다.** 64비트로 빌드하면 컴파일은 되고 실행할 때 죽는다.

빌드는 `build.bat`(vswhere → MSBuild). 빌드 전에 트레이에서 **종료**해야 한다.
배포본은 `bin\Release` 폴더 **통째로** — `Interop.KTOpenAPI.dll`이 같이 있어야 한다.

### 알림 클릭 → 화면 복원

`WindowFinder.cs`가 3단계로 찾는다.

| 단계 | 방법 | 브라우저 종류 |
|---|---|---|
| ① | 창 제목이 맞는 창을 복원 | 상관없음 |
| ② | **접근성(UI Automation)으로 탭을 찾아 전환** | 상관없음 |
| ③ | 기본 브라우저로 주소를 연다 | 상관없음 |

탭을 UI Automation으로 다루므로 크롬·엣지·웨일·브레이브·파이어폭스에 같은 코드가 통한다.
**브라우저 이름 목록을 쓰지 않는다.**

처음엔 ②를 "브라우저 창이면 아무거나"로 짰다가 **Edge 창이 올라오고 elv는 Chrome에 있는** 실패를 겪었다.
창만 올리면 엉뚱한 탭이 나온다.

> UIA 주의: `FindAll(Descendants, TabItem)`은 페이지 내용까지 뒤져 몇 초 걸린다.
> **깊이 8 제한 + `ControlType.Document`는 안 내려감.** 크롬 계열은 접근성 트리를 지연 생성해
> 첫 조회가 빈손일 수 있어 400ms 후 1회 재시도. UI 멈춤 방지로 MTA 스레드에서 실행.

**탭을 알아보는 단서는 셋** (`Config.TabTokens()`) — 고정 문자열은 틀렸다.
`index.html`의 `<title>`은 `system_title` = SystemOption `LOGO_TITLE`(db_key별)에서 오고,
**비어 있으면 브라우저가 URL을 대신 보여준다**(코드에 기본값 없음).

1. `WINDOW_TITLE` 설정값 (적어둔 경우만)
2. 주소 (`actas-ai.co.kr/elv`)
3. `ACTAS`

운영 탭 제목은 `ACTAS EMMS` 로 확인됐다 → ③이 걸리므로 **운영에서 그대로 동작한다.**
다른 사업체가 `LOGO_TITLE`을 자기 회사명으로 넣으면 `WINDOW_TITLE` 설정이 필요할 수 있다.

---

## 6. 사업체 도입

### ① KT — 회선과 계정

**지능망고객센터 080-001-1588** 로 전화. 온라인 청약은 KT가 막아 두었다.
사업체의 **KT 유선전화** 회선에 통화매니저 API 부가서비스를 청약한다.

받는 것: `전화번호@kt.com` 계정 + 임시 비밀번호(회선번호 뒷자리).
첫 로그인에서 403이 뜨고 변경 창이 나온다. 바꾼 뒤 설정에도 새 비번을 넣는다.

**인증키는 우리 회사 것 하나로 전 사업체가 쓴다.** 인증키는 회선이 아니라 **앱**에 묶인다.

확인할 것 넷 —

- **KT 유선전화여야 한다** (타사 회선 불가)
- 번호당 서비스 하나 — 이미 통화매니저를 쓰고 있으면 충돌
- DID 회선이면 전자교환실 Prefix 작업 필요. 없으면 발신번호가 안 온다
- 전화기에 `*88` 착신전환이 걸려 있어도 발신번호가 안 온다

> 개발서버로 청약한 회선은 상용 전환 때 해지(100번) 후 재청약해야 한다.
> 처음부터 상용으로 신청하는 게 낫다.

### ② 서버 — 한 번만

`C:\Temp\mes21\conf\server_elv.env` 에 `cti_agent_secret` 를 넣는다.
사업체별로 나눌 필요 없이 한 값이면 된다. 이 값이 그대로 PC 설정의 `ELV_SECRET`.

### ③ 전화 받는 PC — PC마다

#### 옮길 파일 14개 (약 26MB)

전부 `이재욱\elv\통화매니저 API\` 에 있다. **확장자가 `.dll` `.ocx` `.exe` 인 것 전부 + `oss_notice.html`**.

| | 파일 |
|---|---|
| KT SDK 11개 | `KTOpenAPI.dll` `OllehApi.dll` `KTPConvDB.dll` `KTPImport.dll` `BTCrypt.dll` `KTPSock.dll` `KTPMemberTree.ocx` `BytoExcel.dll` `KTPData.dll` `KTPAddr.dll` `BytoSNTP.dll` |
| 우리 것 2개 | `ACTAS전화연동.exe` · `Interop.KTOpenAPI.dll` |
| 고지문 1개 | `oss_notice.html` |

**DLL을 전부 넣어야 한다.** 일부만 깔면 PC 환경에 따라 **1602(설치 오류)** 가 난다 (KT 안내).

`.dat` `.bip` `.lnk` 는 **가져가면 안 된다.** 실행하면서 생기는 그 PC의 상태 파일이고
그 계정의 로그인 흔적이 들어 있다. 새 PC에서 알아서 다시 생긴다.

`oss_notice.html` 은 KT가 만든 오픈소스 고지문이다(jsoncpp·SQLite·Crypto++·jQuery·Pretendard 등 8개).
MIT·Apache 2.0 이 **저작권 고지를 배포본에 포함할 것**을 요구하므로 **설치 폴더에 반드시 남겨야 한다.**
없어도 프로그램은 돌지만 라이선스 의무를 어기게 된다.

#### 설치

1. 아무 폴더에 **전부 같이** 푼다 (예: `C:\ACTAS\전화연동`)
   — KT DLL이 실행 경로 기준으로 `.dat`를 찾는다. 흩어 두면 1700 오류
2. **관리자 권한 cmd** 에서 `regsvr32 "C:\ACTAS\전화연동\KTOpenAPI.dll"`
   — KT도 "권한·PC보안 문제로 관리자 권한 수동 등록이 정상 절차"라고 안내한다
3. `ACTAS전화연동.exe` 실행 → 설정 창
4. KT 계정 · elv 아이디 · 인증키 · 주소 · 연결키 입력
5. **시작프로그램 폴더(`shell:startup`)에 바로가기** — 빠뜨리면 다음날 "전화가 안 와요" 연락이 온다

방화벽에서 `open.bizphone.co.kr` **9325~9339 아웃바운드**가 열려 있어야 한다.

- 코드 서명이 없어 첫 실행 때 SmartScreen 경고가 뜬다 (추가 정보 → 실행)
- 한 KT 계정에 한 PC만 된다(중복 로그인 301). 여러 명이 받으면 회선당 계정 둘이 필요할 수 있다
- **다른 PC에서 시험하려면 쓰던 PC의 프로그램을 먼저 종료해야 한다** — 같은 계정이라 서로 밀어낸다

### 설정 열 가지

`%LOCALAPPDATA%\ACTAS\전화연동\settings.conf`

| 항목 | 누가 정하나 | 설명 |
|---|---|---|
| `KT_LOGIN_ID` | 사업체 | `전화번호@kt.com`. **문자 발신번호도 여기서 뽑는다** |
| `KT_LOGIN_PW` | 사업체 | DPAPI 암호화. 다른 PC·다른 윈도우 계정에서는 안 풀린다 |
| `KT_AUTH_KEY` | 우리 | CP 인증키 40자리 |
| `ELV_USERNAME` | 사업체 | 전화를 띄울 elv 로그인 계정 |
| `ELV_URL` | 우리 | `https://actas-ai.co.kr/elv/api/AS/cti/event` |
| `ELV_SECRET` | 우리 | 서버의 `cti_agent_secret` 과 같은 값 |
| `AUTO_LOGIN` | 사업체 | PC를 켤 때 바로 연결할지 |
| `WINDOW_TITLE` | 필요할 때만 | 브라우저 탭 제목의 일부 |
| `DEBUG_TABS` | 문제 추적용 | 1이면 탭 이름을 로그에. **기본 꺼짐** |

`DEBUG_TABS`를 기본으로 끈 이유 — 사업체 직원 PC의 브라우저 탭 제목이 로그에 쌓이면 안 된다.

**`ELV_USERNAME`이 왜 필요한가** — 에이전트와 브라우저가 각각 따로 서버에 붙어서
서버는 둘이 같은 PC인지 알 수 없다. 사무실이 공인 IP를 공유하므로 IP로도 못 맞춘다.
없애려면 짝 맺기(6자리 코드) 흐름이 필요하고 반나절. 어차피 한 번 입력이라 지금은 아이디 방식.

### 도입 확인 목록

| | 확인할 것 | 안 되면 |
|---|---|---|
| 1 | 트레이 아이콘이 초록 | 로그의 `[EventLogin]` 줄 — 계정·비밀번호·인증키 |
| 2 | 화면 [전화] 칸이 `연결됨` | elv 아이디가 로그인 계정과 같은지, 연결키가 맞는지 |
| 3 | 실제로 전화를 걸어 카드가 뜬다 | 로그에 `[전화] ... result=201`이 있는지 — 없으면 KT 회선 문제 |
| 4 | 등록된 번호로 고객명이 뜬다 | 사업체 계정으로 로그인했는지 |
| 5 | 트레이 풍선 알림이 뜬다 | 윈도우 알림 설정에서 이 앱이 꺼져 있는지 |
| 6 | 알림을 누르면 화면이 올라온다 | `DEBUG_TABS=1` 로 탭 이름 확인 후 `WINDOW_TITLE` 설정 |
| 7 | 문자가 실제로 간다 | 로그의 `SendSMS` 반환값 — 4005면 발신번호 불일치 |
| 8 | PC를 껐다 켜도 자동 연결 | 시작프로그램 등록과 `AUTO_LOGIN` |

4번은 **실제 전화로 못 고른다** — 걸려오는 번호를 정할 수 없으므로 **모의 수신 패널**로 한다.
실행 옵션에 `-Dcti.mock.enabled=true`.

> 활성 프로파일이 `elv,ncp`라 `application-local.properties`는 읽히지 않는다.
> 모의 패널은 실행 옵션으로만 켤 수 있다.

---

## 7. 로그로 장애 가르기

**에이전트** — `%LOCALAPPDATA%\ACTAS\전화연동\logs\` (날짜별, 14일 보관).
트레이 우클릭 → 로그 보기. 첫 줄에 버전이 찍힌다.

| 로그 | 뜻 |
|---|---|
| `[EventLogin] 200` 이 없다 | KT 로그인 실패 — 그 줄의 안내 문구가 할 일을 알려준다 |
| `[EventConnect] 0` | 네트웍 끊김. 이 상태에서는 전화가 안 온다 |
| `[전화] ... result=201` 이 없다 | KT에서 전화 자체가 안 왔다 — 회선·착신 설정 |
| `event 전송 실패` | 서버 주소나 연결키가 틀렸다 |
| `[문자] SendSMS 반환 = 4005` | 발신번호가 청약 회선과 다르다 |
| `알림 클릭 — 열려 있는 탭 중에…` | 탭을 못 찾았다. `DEBUG_TABS=1` |

**서버** — `[CTI]` 로 시작한다.

| 로그 | 뜻 |
|---|---|
| `[CTI] 발신번호 조회가 ...ms` | 1초 초과. 색인을 봐야 한다 |
| `[CTI] 이름이 여럿이라 목록으로 넘깁니다` | pri/source/callnm이 전부 남는다 |
| `[CTI] 발신번호 조회 1차 실패` | `TB_E601CALL` 이 없거나 질의가 깨졌다 |

---

## 8. 남은 것

**도입이 늘면 먼저 아플 곳**

- **설치 프로그램과 자동 갱신이 없다.** 사람이 가서 깔고, 고칠 때마다 전부 다시 뿌려야 한다
- **비밀번호 3개월 만료** — KT 답이 없으면 사업체마다 3개월에 한 번씩 전화 연동이 멈춘다
- **코드 서명 인증서** 없으면 SmartScreen 경고 (연 20~40만원)

**작은 것들**

- 회선청약 트레이 메뉴 (`LineJoin` — 30분. 눌러보면 KT가 막았는지 반환값으로 나온다)
- `(02)2658-0052~3` 처럼 `~`로 묶인 번호는 조회가 안 된다
- `TB_E601CALL_01` 에 `(actcd, seq)` 중복 행이 있다 — 지금은 `TOP 1`이라 무해
- 비상통화 `callnm`에 `actnm`의 `(분담)`·`(실패)` 관리표기가 그대로 들어간다

**KT 회신 대기** (`openapi@byto.com`)

1. 한 서버(단일 IP)에서 다수 고객사 계정 동시 로그인 허용 여부
2. 비밀번호 만료 예외
3. SDK 재배포 허용 여부 (설치 프로그램에 DLL 포함)

**검증 안 된 것**

- 전화번호부 **신규·저장·삭제**와 구분 선택. 실제 데이터를 쓰는 부분이라 새 줄로만 시험할 것

---

## 9. 참고 자료

| | 위치 |
|---|---|
| 규격서 | `이재욱\elv\통화매니저 API\통화매니저 API 매뉴얼-v1.35-20260316.pdf` (151p) |
| SDK 일체 | 같은 폴더 (`KTOpenAPI.dll` 외) |
| 원본 쿼리 | `이재욱\elv\aprjems\...\mapper\<사업체>\App01*Mapper.xml` |
| 원본 화면 | `이재욱\elv\aprjems\...\templates\APP01\appKT.html` |
| C# 설명서 | [csharp/README.md](csharp/README.md) |
| 문의처 | `openapi@byto.com` (규격서) · 080-001-1588 (회선청약) |

PDF 추출: mingw64 `pdftotext -layout -enc UTF-8`

> **원본(aprjems)을 볼 때 주의** — elv의 예전 시스템은 둘이다.
> **PB = 파워빌더** 데스크톱 ERP, **aprjems** = 그 전에 만든 웹앱(Spring Boot + MyBatis).
> **CTI·통화메모·콜백은 파워빌더에 없고 aprjems에만 있다.**
> 그리고 aprjems의 CTI는 **업무 화면과 연결된 적이 없다** —
> `KtcallMain.html`은 KT 제공 샘플이고, `appKT.html`에서 그 창을 여는 코드는 주석 처리돼 있다.

---

## 10. 변경 이력 (2026-10-08 이후)

### 비밀번호 만료 대응 — v1.6.0

규격서에 답이 있었다. **3.7.4 `PasswdExpiredExtend()`** — 인자 없이 부르면 **90일 연장**(200 성공).
**3.7.3 `PasswdChange(old, new)`** — 변경(200 / 300 이전비번오류 / 409 유효성). 인터롭에 둘 다 있다.

**잔여일을 묻는 함수는 없다.** `GetMemberInfo()` 는 아이디·이름뿐이고 전체 관리자만 쓴다.
→ 설정에 `PW_CHANGED`(yyyyMMdd)를 적어두고 90일에서 뺀다. 기록이 없으면 첫 로그인 날을 기준으로 잡는다.

| 언제 | 무슨 일 |
|---|---|
| 로그인 성공 + 75일 경과 | 풍선 알림 + `PasswordForm` (남은 날 표시) |
| 로그인이 403 / 409 / 410 / 411 | 풍선 알림 + `PasswordForm` (이미 막힘으로 표시) |
| 트레이 메뉴 → **비밀번호** | 아무 때나 |

창에 **[90일 연장]** 과 **[비밀번호 변경]** 두 길. 연장이 실패하면 그 자리에서 변경으로 안내한다 —
**만료된 뒤에도 연장이 되는지 규격서에 없어서** 눌러봐야 안다. `[나중에]` 는 `PW_SNOOZED` 에
오늘 날짜를 적어 그날은 다시 묻지 않는다.

**덤**: 규격서 3.8.3 `EventChangePasswd(sPasswd)` 를 구독한다. KT 창에서 바꾸든 우리가 바꾸든
**바뀐 비밀번호가 이벤트로 와서 설정에 자동 저장된다.** 지금까지는 사용자가 설정창에 다시 넣어야 했고
빼먹으면 다음날 연결이 안 됐다.

> **미확인**: `PasswdExpiredExtend()` 의 반환값을 아직 실제로 못 봤다.
> 트레이 → 비밀번호 → [90일 연장] 을 눌러 로그를 보면 바로 나온다.
> 200 이면 연장 가능 확정, 0 이면 변경만 길이고 75일 알림이 필수가 된다.

### KT 회신 (2026-10-08, 유선)

메일이 아니라 전화로 왔다. 통화 메모 기준.

- **SDK 재배포: 절차나 허가 불필요.** 오히려 **"CP사가 최신버전으로 배포하는 것을 권장"**,
  "setup 으로 배포", "라이브러리 최신으로 깔리도록" → **설치 프로그램을 만들어도 된다.**
  담당자 수동설치만 가능하다던 전제가 사라졌다
- 비밀번호 만료건은 **매뉴얼에 포함돼 있다** = 위 연장·변경 함수를 쓰라는 뜻
- **중복 로그인 불가** 재확인 — 계정 1개 = PC 1대
- 설치 주의: **DLL을 전부 받아야 한다. 일부만 깔면 1602(설치 오류)**
- **`regsvr32` 는 관리자 권한 수동이 정상 절차** — 권한·PC보안 때문에 자동화가 막히는 경우가 있다
- 불확실: "비번 KPI 로 발급"(API 오타?), "SDK 라이브러리 오픈소스"(→ `oss_notice.html` 을 가리키는 듯)
- **미답**: 한 서버에서 서로 다른 계정 다수 동시 로그인. "중복 로그인 불가"는 같은 계정 얘기라 다른 질문이다

### 오픈소스 고지 — `oss_notice.html`

KT가 만든 고지문(`Copyright © 2016, KT corp` / `osscenter@kt.com`).
통화매니저 안에 쓰인 오픈소스 8개를 밝힌다.

| 구성요소 | 라이선스 |
|---|---|
| jsoncpp · Scrollator · jQuery 3.4.1 | MIT |
| ExplorerCanvas | Apache 2.0 |
| Crypto++ 5.6.1 | Boost Software License 1.0 |
| SQLite | Public Domain |
| json.js | JSON License |
| Pretendard (글꼴) | SIL Open Font License 1.1 |

**SDK 자체가 오픈소스라는 뜻은 아니다.** KT DLL 은 KT 저작물이고, 그 안의 부품 일부가 오픈소스다.

MIT·Apache 2.0 이 저작권 고지와 라이선스 전문을 배포본에 포함할 것을 요구하므로
**설치 프로그램에 `oss_notice.html` 을 넣고 설치 폴더에 남겨야 한다.**
트레이 메뉴에 `오픈소스 고지` 를 넣어 열게 하면 더 확실하다(10분).

### 시작프로그램이 옛 VBScript 를 가리키고 있던 문제

재부팅 후 트레이 아이콘이 없어서 보니, 시작프로그램에 **`전화연동_시작.bat.lnk`(VBScript용)** 가
그대로 있었다. C# 으로 넘어온 뒤 바꾼 적이 없었던 것. 그 VBScript 가 같은 KT 계정을 물면
C# 과 **중복 로그인(301)으로 서로 밀어낸다.**

→ 옛 바로가기를 지우고 `ACTAS 전화연동.lnk` 를 만들었다.
**사업체 도입 때도 똑같이 빠지기 쉬운 단계다.**

### 다음 할 일

| | 내용 |
|---|---|
| 지금 | NCP 배포 · `PasswdExpiredExtend()` 반환값 확인 |
| 다음 | **설치 프로그램**(Inno Setup, 2~3일) — KT 허락이 떨어졌다 |
| 작은 것 | 회선청약 트레이 메뉴(`LineJoin`, 30분) · `~` 로 묶인 번호 · 비상통화 이름의 관리표기 |
| 필요해지면 | 코드 서명 인증서(연 20~40만원) — 사업체가 스스로 받아 까는 방식으로 갈 때 |
| 넘어감 | 전화번호부 신규·저장·삭제 검증 |

---

## 11. 설치할 때 걸리는 함정 — COM 등록은 경로를 기억한다

`regsvr32` 는 **DLL 이 있던 그 경로를 레지스트리에 통째로 적는다.**

```
HKLM\SOFTWARE\WOW6432Node\Classes\CLSID\{16AB1B2A-A22E-4FAC-92CB-84102DF5CE3D}\InprocServer32
  →  C:\...\통화매니저 API\KTOpenAPI.dll
```

**폴더를 옮기거나 파일을 다시 풀었으면 반드시 다시 등록해야 한다.**
안 그러면 옛 경로를 보고 있어서 이런 오류가 난다.

| 오류 | 뜻 | 원인 |
|---|---|---|
| `0x80040154` | 클래스가 등록되지 않았습니다 | `regsvr32` 를 아예 안 했다 |
| `0x8007007E` | 지정된 모듈을 찾을 수 없습니다 | **등록은 됐는데 그 경로에 `OllehApi.dll` 등이 없다** |
| `0x80004005` | E_FAIL | 등록 경로는 맞는데 초기화 실패 (파일 일부 누락 등) |

지금 등록된 경로 확인:

```powershell
(Get-ItemProperty "HKLM:\SOFTWARE\WOW6432Node\Classes\CLSID\{16AB1B2A-A22E-4FAC-92CB-84102DF5CE3D}\InprocServer32").'(default)'
```

여기 나오는 경로와 **실제 파일이 있는 폴더가 같아야 한다.** 다르면 관리자 cmd 에서 다시 등록한다.

```
regsvr32 /u "옛경로\KTOpenAPI.dll"     (경로를 모르면 건너뛰어도 된다)
regsvr32 "지금폴더\KTOpenAPI.dll"
```

### 의존성은 두 개뿐이다 (dumpbin 확인)

`KTOpenAPI.dll` → `OllehApi.dll` + 윈도우 기본 DLL
`OllehApi.dll` → 윈도우 기본 DLL

**Visual C++ 재배포 패키지는 필요 없다.** 정적 링크돼 있다.
그래서 `0x8007007E` 가 나면 런타임이 아니라 **경로 문제**로 봐야 한다.

### `.dat` 파일은 가져가지 않아도 된다

규격서 2.2 가 참조 파일에 `KPD_API.dat` `KTA_API.dat` 를 적고 있어서 헷갈리지만,
의존성 목록에 없고 실행하면서 생긴다. 그 PC 의 상태 파일이므로 **옮기지 않는 쪽이 맞다.**

> 설치 프로그램을 만들면 설치 경로에서 자동 등록하므로 이 함정이 통째로 사라진다.
> KT 가 "setup 으로 배포하라"고 한 이유 중 하나일 것이다.
