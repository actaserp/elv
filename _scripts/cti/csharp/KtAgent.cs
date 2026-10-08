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

        readonly Config cfg;
        KTPCBizXClass kt;
        Timer pollTimer, beatTimer;

        public bool LoggedIn { get; private set; }

        /// <summary>상태가 바뀌면 알린다 (트레이 아이콘이 받는다)</summary>
        public event Action<string, bool> StateChanged;   // (메시지, 연결됨)
        public event Action<string> Logged;

        /// <summary>전화가 왔다 — 트레이가 윈도우 알림을 띄운다 (발신번호, 수신상태)</summary>
        public event Action<string, string> CallReceived;

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

            if (LoggedIn) Logout();
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

                // 403 임시비밀번호 · 409 유효성 · 410/411 기간만료 — 바꾸거나 연장해야 열린다.
                // 그냥 두면 전화는 울리는데 화면에만 안 뜨는 상태로 하루가 간다.
                if (rc == 403 || rc == 409 || rc == 410 || rc == 411)
                {
                    var h = PasswordWarning;
                    if (h != null) h(0, true);
                }
                return;
            }
            Log("요청 성공. 인증 결과(EventLogin)를 기다립니다…");
        }

        public void Logout()
        {
            if (!LoggedIn) { Log("이미 해제돼 있습니다."); return; }
            try { kt.Logout(); } catch (Exception e) { Log("Logout 오류: " + e.Message); }

            LoggedIn = false;
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

            // 윈도우 알림은 여기서 띄운다. 브라우저를 거치지 않아 더 빠르고,
            // 화면을 닫아두었거나 브라우저가 꺼져 있어도 뜬다.
            var ch = CallReceived;
            if (ch != null) ch(caller ?? "", result ?? "");

            var p = new NameValueCollection();
            p["username"] = cfg.ElvUsername;
            p["caller"]   = caller  ?? "";
            p["callee"]   = callee  ?? "";
            p["result"]   = result  ?? "";
            p["dbId"]     = dbId    ?? "";
            Post("event", p);
        }

        // ── 화면 ↔ 에이전트 ──────────────────────────────────────

        /// <summary>화면에서 누른 [연결]/[해제] 를 가져온다</summary>
        void Poll()
        {
            try
            {
                var url = cfg.BaseUrl() + "agent-poll?username=" + Uri.EscapeDataString(cfg.ElvUsername);
                var res = Http("GET", url, null);
                if (res == null) return;

                if (res.Contains("\"connect\"")) { Log("화면에서 [연결] 을 눌렀습니다."); Login(); }
                else if (res.Contains("\"disconnect\"")) { Log("화면에서 [해제] 를 눌렀습니다."); Logout(); }
                else if (res.Contains("\"sms\"")) SendSms(res);
            }
            catch { /* 통신 실패는 다음 주기에 다시 시도한다 */ }
        }

        void Heartbeat()
        {
            if (LoggedIn) Post("agent-online", null);
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
            p["username"] = cfg.ElvUsername;
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
            if (string.IsNullOrEmpty(cfg.ElvUsername)) return;

            if (form == null)
            {
                form = new NameValueCollection();
                form["username"] = cfg.ElvUsername;
            }
            try { Http("POST", cfg.BaseUrl() + path, form); }
            catch (Exception e) { Log(path + " 전송 실패: " + e.Message); }
        }

        string Http(string method, string url, NameValueCollection form)
        {
            var req = (HttpWebRequest)WebRequest.Create(url);
            req.Method  = method;
            req.Timeout = 5000;
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
