using System;
using System.Collections.Specialized;
using System.Net;
using System.Text;
using System.Threading;
using KTOpenAPI;

namespace ActasCti
{
    /// <summary>
    /// KT 통화매니저 연동 본체.
    ///
    /// KTOpenAPI.dll 은 32bit COM 이라 서버(리눅스)에서 직접 못 붙는다.
    /// 이 프로그램이 전화 받는 PC 에서 COM 을 물고 있다가,
    /// 전화가 오면 elv 서버로 넘기고 서버가 담당자 화면에 띄운다.
    ///
    /// 규격서: 통화매니저 API 매뉴얼 v1.35
    /// </summary>
    public class KtAgent
    {
        const int MODE_EVENT_V2CID = 0x1000;   // 규격서 9.1.1 — 기본 꺼져 있어 켜야 한다
        const int POLL_MS      = 3000;         // 화면에서 누른 [연결]/[해제] 가져오기
        const int HEARTBEAT_MS = 30000;        // "살아 있다" 신고
        const int EVENT_TIMEOUT_MS = 1500;   // 알림이 늦으면 안 된다. 짧게만 기다린다

        readonly Config cfg;
        KTPCBizXClass kt;
        Timer pollTimer, beatTimer;

        public bool LoggedIn { get; private set; }

        /// <summary>상태가 바뀌면 알린다 (트레이 아이콘이 받는다)</summary>
        public event Action<string, bool> StateChanged;   // (메시지, 연결됨)
        public event Action<string> Logged;

        /// <summary>전화가 왔다 — (발신번호, 수신상태, 고객명, 등록된 회선인가)</summary>
        public event Action<string, string, string, bool> CallReceived;

        /// <summary>
        /// 서버가 이 PC 의 회선번호를 모른다(true) / 다시 알아봤다(false).
        /// 전화는 울리는데 화면에만 안 뜨는 상태라, 트레이가 눈에 띄게 알린다.
        /// </summary>
        public event Action<bool> LineUnknown;

        /// <summary>
        /// 다른 PC 가 같은 계정을 물고 있다(301·1000).
        /// 끊고 가져올지는 사람이 정해야 하므로 트레이가 물어본다.
        /// </summary>
        public event Action DuplicateLogin;

        public KtAgent(Config config) { cfg = config; }

        // ── 시작 / 종료 ──────────────────────────────────────────

        public void Start()
        {
            Log("프로그램을 시작합니다.");

            kt = new KTPCBizXClass();
            Log("COM 객체 생성 완료");

            try { kt.SetApiMode(MODE_EVENT_V2CID, 1); }
            catch (Exception e) { Log("SetApiMode 실패(무시 가능): " + e.Message); }

            kt.EventLogin         += OnLogin;
            kt.EventConnect       += OnConnect;
            kt.EventChangePasswd  += OnChangePasswd;   // 규격서 3.8.3
            kt.EventV2CID   += OnCallV2;
            kt.EventCID     += OnCallOld;   // SetApiMode 가 안 먹었을 때 대비

            pollTimer = new Timer(_ => Poll(),      null, POLL_MS,      POLL_MS);
            beatTimer = new Timer(_ => Heartbeat(), null, HEARTBEAT_MS, HEARTBEAT_MS);

            if (cfg.AutoLogin) Login();
            else Notify("화면에서 [연결] 을 누르면 로그인합니다.", false);
        }

        public void Stop()
        {
            if (pollTimer != null) pollTimer.Dispose();
            if (beatTimer != null) beatTimer.Dispose();

            // LoggedIn 은 EventLogin 200 이 와야 켜진다. 그런데 Login() 이 200 을
            // 돌려준 시점에 KT 쪽에는 이미 세션이 선다 — 결과 이벤트가 안 오는 PC 에서는
            // 로그아웃을 한 번도 안 보내게 되어, 다음 [연결] 이 자기 세션에 막혀 1000 이 난다.
            // 그러니 '요청을 보낸 적이 있으면' 끝낼 때 로그아웃을 시도한다.
            if (LoggedIn || loginRequested) Logout(true);
            Log("프로그램을 종료합니다.");
        }

        // ── 로그인 / 로그아웃 ────────────────────────────────────

        public void Login()
        {
            if (LoggedIn) { Log("이미 로그인돼 있습니다."); return; }
            if (!cfg.IsReady) { Notify("설정이 비어 있습니다. [설정] 에서 계정을 넣어주세요.", false); return; }

            Notify("연결하는 중…", false);
            Log(string.Format("로그인 요청 — server={0} id={1}", cfg.KtServer, cfg.KtLoginId));

            int rc;
            try { rc = kt.Login(cfg.KtServer, cfg.KtAuthKey, cfg.KtLoginId, cfg.KtLoginPw); }
            catch (Exception e) { Notify("로그인 중 오류: " + e.Message, false); return; }

            Log(string.Format("Login() 반환값 = {0}  ({1})", rc, Codes.LoginMsg(rc)));
            if (rc != 200)
            {
                Notify(Codes.LoginAdvice(rc), false);

                // 301·1000 — 다른 PC 가 이 계정을 물고 있다.
                // 랜선이 뽑히거나 강제 종료된 PC 는 로그아웃을 못 보내서 KT 쪽에
                // 세션이 한참 남는다. 그 PC 를 되살릴 수 없으면 기다리는 길밖에 없는데,
                // 규격서 3.3.2 LoginKickOut 으로 끊고 가져올 수 있다.
                // 남의 연결을 끊는 일이라 묻고 나서 한다.
                if (rc == 301 || rc == 1000)
                {
                    var dup = DuplicateLogin;
                    if (dup != null) dup();
                }

                // 403 임시비밀번호 · 409 유효성 · 410/411 기간만료 — 바꾸거나 연장해야 열린다.
                // 그냥 두면 전화는 울리는데 화면에만 안 뜨는 상태로 하루가 간다.
                if (rc == 403 || rc == 409 || rc == 410 || rc == 411)
                {
                    var h = PasswordWarning;
                    if (h != null) h(0, true);
                }
                return;
            }
            // 여기서부터 KT 쪽에는 세션이 서 있다. 결과가 오든 안 오든 끝낼 때 정리해야 한다.
            loginRequested = true;
            Log("요청 성공. 인증 결과(EventLogin)를 기다립니다…");
        }

        /// <summary>Login() 이 200 을 받았다 — 결과 이벤트는 아직 안 왔을 수 있다.</summary>
        bool loginRequested;

        /// <summary>
        /// 규격서 3.3.2 — 다른 PC 가 물고 있는 로그인을 끊고 이 PC 로 가져온다.
        /// 인자가 없다. 직전 Login() 에 넣은 계정을 그대로 쓴다.
        /// 성공하면 결과는 EventLogin 으로 온다.
        /// </summary>
        public void KickOut()
        {
            int rc;
            try { rc = kt.LoginKickOut(); }
            catch (Exception e)
            {
                Log("LoginKickOut 오류: " + e.Message);
                Notify("다른 PC 의 연결을 끊지 못했습니다: " + e.Message, false);
                return;
            }

            Log(string.Format("LoginKickOut() 반환값 = {0}", rc));

            if (rc == 200) { Notify("다른 PC 의 연결을 끊는 중…", false); return; }

            Notify("다른 PC 의 연결을 끊지 못했습니다. (코드 " + rc + ")", false);
        }

        // ── KT 자체 창 / 진단 ────────────────────────────────────

        /// <summary>
        /// 규격서 3.4.2 — KT 가 만든 환경설정 창을 띄운다.
        /// 수신 장치·녹취·발신번호 같은 건 KT 쪽 설정이라 우리가 흉내 낼 수 없다.
        /// 0x9010 이 첫 화면이고, 0x9021~0x9133 으로 특정 탭을 바로 열 수도 있다.
        /// </summary>
        public void ShowConfig()
        {
            try { kt.ShowConfig(0x9010, 0); }
            catch (Exception e)
            {
                Log("환경설정 창 오류: " + e.Message);
                Notify("환경설정 창을 열지 못했습니다: " + e.Message, false);
            }
        }

        /// <summary>
        /// 지금 상태를 통째로 로그에 적는다.
        ///
        /// "전화가 안 떠요" 라는 연락을 받았을 때 물어볼 수 있는 건 한정돼 있다.
        /// 로그인은 됐는지, SDK 가 어느 경로를 보는지 — 그 값들을 모르면
        /// 가설만 세우다 끝난다(실제로 그랬다). 눌러서 받아보면 바로 갈린다.
        /// </summary>
        public void Diagnose()
        {
            Log("───── 진단 정보 ─────");
            Log("  프로그램   = " + Version());
            Log("  회선번호   = " + cfg.KtLine());
            Log("  KT 아이디  = " + cfg.KtLoginId);
            Log("  KT 서버    = " + cfg.KtServer + " (1=상용, 0=개발)");
            Log("  elv 주소   = " + cfg.ElvUrl);
            Log("  자동로그인 = " + cfg.AutoLogin);
            Log("  LoggedIn   = " + LoggedIn + "   (EventLogin 200 을 받았는가)");

            Probe("GetApiVer ", () => kt.GetApiVer());
            Probe("GetApiPath", () => kt.GetApiPath());

            // AUTH/NAME 이 채워져 있으면 SDK 는 로그인된 것이다.
            // LoggedIn=false 인데 이게 나오면 이벤트만 안 들어온 것 — 둘은 전혀 다른 고장이다.
            foreach (var one in new[] { "AUTH|NAME", "AUTH|TEAM", "AUTH|RIGHT",
                                        "AUTH|SEQNO", "AUTH|MAIN_NUM", "CONF|SMS_SIGN" })
            {
                var p = one.Split('|');
                Probe("GetEnv " + one.Replace("|", "/"), () => kt.GetEnv(p[0], p[1]));
            }

            Log("─────────────────────");
            Notify("진단 정보를 로그에 적었습니다.", LoggedIn);
        }

        /// <summary>한 항목이 터져도 나머지는 찍히게 한다 — 로그인 전에는 던지는 것들이 있다.</summary>
        void Probe(string label, Func<string> read)
        {
            string value;
            try { value = read(); }
            catch (Exception e) { value = "(오류: " + e.Message + ")"; }
            Log("  " + label + " = " + (string.IsNullOrEmpty(value) ? "(빈값)" : value));
        }

        static string Version()
        {
            try
            {
                var v = System.Reflection.Assembly.GetExecutingAssembly().GetName().Version;
                return v.Major + "." + v.Minor + "." + v.Build;
            }
            catch { return "?"; }
        }

        public void Logout() { Logout(false); }

        /// <param name="force">
        /// 로그인 결과를 못 받았어도 KT 쪽 세션을 정리하려고 보낼 때 true.
        /// </param>
        public void Logout(bool force)
        {
            if (!LoggedIn && !force) { Log("이미 해제돼 있습니다."); return; }

            try { kt.Logout(); } catch (Exception e) { Log("Logout 오류: " + e.Message); }

            LoggedIn       = false;
            loginRequested = false;
            Post("agent-offline", null);
            Notify("연결을 해제했습니다.", false);
        }

        // ── KT 이벤트 ────────────────────────────────────────────

        /// <summary>규격서 3.8.1 — 200 이면 로그인 성공</summary>
        void OnLogin(int result)
        {
            Log(string.Format("[EventLogin] {0}  ({1})", result, Codes.LoginEventMsg(result)));

            if (result == 200)
            {
                LoggedIn = true;
                Post("agent-online", null);
                Notify("전화 연결됨", true);
                CheckPasswordAge();   // 만료가 가까우면 여기서 알린다
                return;
            }

            // 200 이 아니면 로그인 상태가 아니다. 다만 프로그램을 끝내지는 않는다 —
            // [해제] 를 눌러도 300(로그아웃)이 오는데, 끝내면 다시 [연결] 을 못 한다.
            LoggedIn = false;
            Post("agent-offline", null);

            if (result == 401) Notify("인증키가 차단됐습니다. 담당자에게 문의해주세요.", false);
            else               Notify("연결이 해제됐습니다.", false);
        }

        /// <summary>규격서 3.8.2 — 1 일 때만 전화 수신이 가능하다</summary>
        void OnConnect(int result)
        {
            if (result == 1)      Log("[EventConnect] 1 — 전화 수신 대기 중");
            else if (result == 2) Log("[EventConnect] 2 — 접속 성공(동기화 중)");
            else                  Log("[EventConnect] 0 — 네트웍 끊김. 전화 수신 불가 (재접속 시도)");
        }

        /// <summary>규격서 4.4.2 — 전화 수신 통지</summary>
        void OnCallV2(string caller, string callee, string result, string dbId, KTDHash data)
        {
            SendCall(caller, callee, result, dbId);
        }

        /// <summary>구형 이벤트 (SetApiMode 가 안 먹었을 때)</summary>
        void OnCallOld(string caller, string callee, string result, string clSeq, string miSeq)
        {
            SendCall(caller, callee, result, clSeq);
        }

        void SendCall(string caller, string callee, string result, string dbId)
        {
            Log(string.Format("[전화] caller={0} callee={1} result={2} dbid={3}",
                              caller, callee, result, dbId));

            var p = new NameValueCollection();
            p["line"]   = cfg.KtLine();
            p["caller"] = caller  ?? "";
            p["callee"] = callee  ?? "";
            p["result"] = result  ?? "";
            p["dbId"]   = dbId    ?? "";

            // 서버 응답에 고객명이 실려 온다. 화면을 띄워두지 않는 곳에서는
            // 이 알림이 전화를 아는 유일한 길이라, 번호만 띄우면 쓸모가 적다.
            //
            // 다만 서버를 기다리다 알림이 늦으면 안 된다. 짧게만 기다리고,
            // 응답이 없으면 번호만이라도 띄운다 — 서버가 죽어도 전화는 알려야 한다.
            string name   = null;
            bool   known  = true;      // 등록된 회선인가

            try
            {
                var res = Http("POST", cfg.BaseUrl() + "event", p, EVENT_TIMEOUT_MS);
                name = JsonString(res ?? "", "callnm");
            }
            catch (WebException we)
            {
                // 404 = 서버가 이 회선을 모른다. 전화가 와도 화면에 뜨지 않는 상태다.
                var http = we.Response as HttpWebResponse;
                if (http != null && (int)http.StatusCode == 404) known = false;

                Log("event 전송 실패: " + we.Message);
            }
            catch (Exception e) { Log("event 전송 실패: " + e.Message); }

            var ch = CallReceived;
            if (ch != null) ch(caller ?? "", result ?? "", name ?? "", known);
        }

        // ── 화면 ↔ 에이전트 ──────────────────────────────────────

        /// <summary>화면에서 누른 [연결]/[해제] 를 가져온다</summary>
        void Poll()
        {
            try
            {
                var url = cfg.BaseUrl() + "agent-poll?line=" + Uri.EscapeDataString(cfg.KtLine());
                var res = Http("GET", url, null);
                if (res == null) return;

                if (res.Contains("\"connect\"")) { Log("화면에서 [연결] 을 눌렀습니다."); Login(); }
                else if (res.Contains("\"disconnect\"")) { Log("화면에서 [해제] 를 눌렀습니다."); Logout(); }
                else if (res.Contains("\"sms\"")) SendSms(res);
            }
            catch { /* 통신 실패는 다음 주기에 다시 시도한다 */ }
        }

        /// <summary>
        /// 서버에 "나 살아 있다" 를 알린다. 화면의 연결 표시가 이것으로 켜진다.
        ///
        /// 서버가 이 회선을 모르면(404) 전화가 울려도 화면에는 뜨지 않는다.
        /// 예전에는 로그에만 30초마다 같은 줄이 쌓였는데, 그 파일을 열어보는
        /// 사람은 없다. 상태가 바뀌는 순간 한 번만 바깥으로 알린다.
        /// </summary>
        bool lineUnknown;

        void Heartbeat()
        {
            if (!LoggedIn) return;
            if (cfg.KtLine().Length == 0) return;

            var form = new NameValueCollection();
            form["line"] = cfg.KtLine();

            try
            {
                Http("POST", cfg.BaseUrl() + "agent-online", form);
                SetLineUnknown(false);
            }
            catch (WebException we)
            {
                var http = we.Response as HttpWebResponse;

                if (http != null && (int)http.StatusCode == 404) SetLineUnknown(true);
                else Log("agent-online 전송 실패: " + we.Message);
            }
            catch (Exception e) { Log("agent-online 전송 실패: " + e.Message); }
        }

        void SetLineUnknown(bool unknown)
        {
            if (unknown == lineUnknown) return;   // 바뀔 때만 — 30초마다 떠들면 알림이 못 쓰게 된다
            lineUnknown = unknown;

            Log(unknown
                ? "이 회선이 서버에 등록돼 있지 않습니다: " + cfg.KtLine() + " — 전화가 화면에 뜨지 않습니다."
                : "회선이 확인되었습니다: " + cfg.KtLine());

            var ch = LineUnknown;
            if (ch != null) ch(unknown);
        }

        // ── 비밀번호 ─────────────────────────────────────────────
        //
        // KT 계정 비밀번호는 90일마다 바꿔야 한다. 그냥 두면 어느 날 아침
        // 로그인이 410/411 로 막히고, 전화는 울리는데 화면에만 안 뜬다.
        //
        // 규격서에 연장 함수(3.7.4 PasswdExpiredExtend)가 있어서 비밀번호를
        // 바꾸지 않고 90일을 미룰 수 있다. 바꾸려면 3.7.3 PasswdChange.

        /// <summary>비밀번호를 그대로 두고 만료를 90일 미룬다</summary>
        public int ExtendPassword()
        {
            int rc;
            try { rc = kt.PasswdExpiredExtend(); }
            catch (Exception e) { Log("[비밀번호] 연장 중 오류: " + e.Message); return -1; }

            Log(string.Format("[비밀번호] PasswdExpiredExtend 반환 = {0}  ({1})",
                              rc, Codes.PasswdExtendMsg(rc)));

            if (rc == 200) cfg.PwMarkChanged();
            return rc;
        }

        /// <summary>비밀번호를 바꾼다. 성공하면 설정에도 새 값을 넣는다</summary>
        public int ChangePassword(string oldPw, string newPw)
        {
            int rc;
            try { rc = kt.PasswdChange(oldPw, newPw); }
            catch (Exception e) { Log("[비밀번호] 변경 중 오류: " + e.Message); return -1; }

            Log(string.Format("[비밀번호] PasswdChange 반환 = {0}  ({1})",
                              rc, Codes.PasswdChangeMsg(rc)));

            if (rc == 200)
            {
                cfg.KtLoginPw = newPw;
                cfg.PwMarkChanged();   // Save 까지 한다
            }
            return rc;
        }

        /// <summary>
        /// 규격서 3.8.3 — 사용자가 KT 쪽 창에서 비밀번호를 바꿨을 때 바뀐 값이 온다.
        /// 받아서 설정에 넣어 둬야 한다. 안 그러면 다음 로그인부터 옛 비번으로 시도해 막힌다.
        /// </summary>
        void OnChangePasswd(string passwd)
        {
            if (string.IsNullOrEmpty(passwd)) return;

            Log("[비밀번호] 바뀐 것을 받았습니다. 설정에 넣습니다.");
            cfg.KtLoginPw = passwd;
            cfg.PwMarkChanged();

            Notify("비밀번호가 바뀌어 설정에 저장했습니다.", LoggedIn);
        }

        /// <summary>만료가 가까우면 트레이에서 알린다. 날짜는 우리가 센다(규격서에 조회 함수가 없다)</summary>
        void CheckPasswordAge()
        {
            // 처음 설치해서 기록이 없으면 오늘을 기준으로 삼는다.
            // 실제 변경일과 다를 수 있지만, 없는 것보다는 낫고 다음 변경 때 맞춰진다.
            if (cfg.PwAgeDays() < 0)
            {
                cfg.PwMarkChanged();
                Log("[비밀번호] 기준일이 없어 오늘로 잡았습니다.");
                return;
            }

            Log(string.Format("[비밀번호] 마지막 변경·연장 후 {0}일 (만료까지 {1}일)",
                              cfg.PwAgeDays(), cfg.PwDaysLeft()));

            if (!cfg.PwShouldWarn()) return;

            var h = PasswordWarning;
            if (h != null) h(cfg.PwDaysLeft(), false);
        }

        /// <summary>(남은 날, 이미 만료됐나) — 트레이가 받아 창을 띄운다</summary>
        public event Action<int, bool> PasswordWarning;

        // ── 문자 보내기 ──────────────────────────────────────────
        //
        // 규격서 5.1.1 — 수신번호를 하나씩 등록(최대 32)한 뒤 SendSMS 를 부르고,
        // 끝나면 반드시 지운다. 안 지우면 다음 문자가 이 사람들에게도 간다.
        //
        // 발신번호는 청약한 그 회선이어야 한다(4005 발신번호 오류).
        // 계정이 '0269595020@kt.com' 이라 앞부분이 곧 회선번호다.
        void SendSms(string json)
        {
            if (!LoggedIn)
            {
                SmsResult(false, "전화가 연결돼 있지 않습니다.");
                return;
            }

            var to = JsonArray(json, "to");
            var message = JsonString(json, "message");

            if (to.Count == 0)        { SmsResult(false, "받는 사람이 없습니다.");  return; }
            if (message.Length == 0)  { SmsResult(false, "내용이 비어 있습니다."); return; }

            var caller = cfg.KtLoginId;
            int at = caller.IndexOf('@');
            if (at > 0) caller = caller.Substring(0, at);

            Log(string.Format("[문자] 보냅니다 — 발신={0} 수신={1}명 길이={2}자",
                              caller, to.Count, message.Length));

            try
            {
                kt.RemoveAllRecvPhone();   // 앞서 남은 번호가 있으면 치운다
                foreach (var one in to) kt.SetRecvPhone(one);

                int rc = kt.SendSMS(caller, "", message);
                Log(string.Format("[문자] SendSMS 반환 = {0}  ({1})", rc, Codes.SmsMsg(rc)));

                kt.RemoveAllRecvPhone();

                if (rc == 200) SmsResult(true, "");
                else           SmsResult(false, Codes.SmsMsg(rc));
            }
            catch (Exception e)
            {
                Log("[문자] 오류: " + e);
                try { kt.RemoveAllRecvPhone(); } catch { }
                SmsResult(false, e.Message);
            }
        }

        void SmsResult(bool ok, string message)
        {
            var p = new NameValueCollection();
            p["line"] = cfg.KtLine();
            p["ok"]       = ok ? "1" : "0";
            p["message"]  = message ?? "";
            Post("sms-result", p);
        }

        // ── 아주 작은 JSON 읽기 ──────────────────────────────────
        //
        // 값 두 개만 꺼내면 돼서 직렬화 라이브러리를 들이지 않았다.
        // 서버가 보내는 모양이 정해져 있다: {"command":"sms","sms":{"to":[...],"message":"..."}}

        static string JsonString(string json, string key)
        {
            int i = json.IndexOf("\"" + key + "\"", StringComparison.Ordinal);
            if (i < 0) return "";
            i = json.IndexOf('"', json.IndexOf(':', i) + 1);
            if (i < 0) return "";

            var sb = new StringBuilder();
            for (int j = i + 1; j < json.Length; j++)
            {
                char c = json[j];
                if (c == '\\' && j + 1 < json.Length)
                {
                    char n = json[++j];
                    if      (n == 'n') sb.Append('\n');
                    else if (n == 'r') sb.Append('\r');
                    else if (n == 't') sb.Append('\t');
                    else if (n == 'u' && j + 4 < json.Length)
                    {
                        sb.Append((char)Convert.ToInt32(json.Substring(j + 1, 4), 16));
                        j += 4;
                    }
                    else sb.Append(n);
                    continue;
                }
                if (c == '"') break;
                sb.Append(c);
            }
            return sb.ToString();
        }

        static System.Collections.Generic.List<string> JsonArray(string json, string key)
        {
            var list = new System.Collections.Generic.List<string>();

            int i = json.IndexOf("\"" + key + "\"", StringComparison.Ordinal);
            if (i < 0) return list;
            int open = json.IndexOf('[', i);
            int close = open < 0 ? -1 : json.IndexOf(']', open);
            if (open < 0 || close < 0) return list;

            foreach (var part in json.Substring(open + 1, close - open - 1).Split(','))
            {
                var one = part.Trim().Trim('"').Trim();
                if (one.Length > 0) list.Add(one);
            }
            return list;
        }

        void Post(string path, NameValueCollection form)
        {
            if (cfg.KtLine().Length == 0) return;

            if (form == null)
            {
                form = new NameValueCollection();
                form["line"] = cfg.KtLine();
            }
            try { Http("POST", cfg.BaseUrl() + path, form); }
            catch (Exception e) { Log(path + " 전송 실패: " + e.Message); }
        }

        string Http(string method, string url, NameValueCollection form)
        {
            return Http(method, url, form, 5000);
        }

        string Http(string method, string url, NameValueCollection form, int timeoutMs)
        {
            var req = (HttpWebRequest)WebRequest.Create(url);
            req.Method  = method;
            req.Timeout = timeoutMs;
            req.Headers["X-Cti-Secret"] = cfg.ElvSecret;

            if (method == "POST")
            {
                var sb = new StringBuilder();
                foreach (string k in form.Keys)
                {
                    if (sb.Length > 0) sb.Append('&');
                    sb.Append(Uri.EscapeDataString(k)).Append('=')
                      .Append(Uri.EscapeDataString(form[k] ?? ""));
                }
                var body = Encoding.UTF8.GetBytes(sb.ToString());
                req.ContentType   = "application/x-www-form-urlencoded";
                req.ContentLength = body.Length;
                using (var s = req.GetRequestStream()) s.Write(body, 0, body.Length);
            }

            using (var res = (HttpWebResponse)req.GetResponse())
            using (var r = new System.IO.StreamReader(res.GetResponseStream()))
                return r.ReadToEnd();
        }

        // ── 알림 ─────────────────────────────────────────────────

        void Notify(string message, bool connected)
        {
            Log(message);
            var h = StateChanged;
            if (h != null) h(message, connected);
        }

        void Log(string message)
        {
            Logger.Write(message);
            var h = Logged;
            if (h != null) h(message);
        }
    }
}
