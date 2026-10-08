using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;

namespace ActasCti
{
    /// <summary>
    /// 설정 보관. 비밀번호는 윈도우 DPAPI 로 암호화해 저장한다 —
    /// 그 PC, 그 윈도우 계정에서만 풀린다. 파일을 복사해 가도 다른 곳에서는 못 읽는다.
    /// (VBScript 때는 kt_agent.ini 에 평문이라 사업체 배포가 불가능했다)
    /// </summary>
    public class Config
    {
        public int    KtServer   = 1;      // 0 = 개발, 1 = 상용
        public string KtAuthKey  = "";     // CP 인증키 40자리
        public string KtLoginId  = "";     // 전화번호@kt.com
        public string KtLoginPw  = "";     // 메모리에서는 평문, 파일에는 암호화
        public string ElvUsername = "";    // 알림을 받을 elv 로그인 계정
        public string ElvUrl     = "https://mes.actascld.co.kr/elv/api/AS/cti/event";
        public string ElvSecret  = "";
        public bool   AutoLogin  = false;  // PC 를 켤 때 바로 로그인할지
        public string WindowTitle = "";    // 브라우저 탭 제목 (비우면 주소·제품명으로 찾는다)
        public bool   DebugTabs  = false;  // 탭 이름을 로그에 남긴다 (문제 추적용, 기본 꺼짐)

        /// <summary>
        /// KT 비밀번호를 마지막으로 바꾸거나 기간을 연장한 날 (yyyyMMdd).
        /// 규격서에 만료 잔여일을 묻는 함수가 없어서 우리가 세는 수밖에 없다.
        /// </summary>
        public string PwChanged = "";

        /// <summary>알림에서 [나중에] 를 누른 날 (yyyyMMdd). 그날은 다시 묻지 않는다.</summary>
        public string PwSnoozed = "";

        static string Path_
        {
            get
            {
                return System.IO.Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    "ACTAS", "전화연동", "settings.conf");
            }
        }

        public static Config Load()
        {
            var c = new Config();
            if (!File.Exists(Path_)) return c;

            foreach (var raw in File.ReadAllLines(Path_, Encoding.UTF8))
            {
                var line = raw.Trim();
                if (line.Length == 0 || line.StartsWith("#")) continue;

                int i = line.IndexOf('=');
                if (i <= 0) continue;

                var key = line.Substring(0, i).Trim();
                var val = line.Substring(i + 1).Trim();

                switch (key)
                {
                    case "KT_SERVER":    int.TryParse(val, out c.KtServer); break;
                    case "KT_AUTHKEY":   c.KtAuthKey   = val; break;
                    case "KT_LOGIN_ID":  c.KtLoginId   = val; break;
                    case "KT_LOGIN_PW":  c.KtLoginPw   = Decrypt(val); break;
                    case "ELV_USERNAME": c.ElvUsername = val; break;
                    case "ELV_URL":      c.ElvUrl      = val; break;
                    case "WINDOW_TITLE": c.WindowTitle = val; break;
                    case "DEBUG_TABS":   c.DebugTabs   = (val == "1" || val.ToLower() == "true"); break;
                    case "PW_CHANGED":   c.PwChanged   = val; break;
                    case "PW_SNOOZED":   c.PwSnoozed   = val; break;
                    case "ELV_SECRET":   c.ElvSecret   = val; break;
                    case "AUTO_LOGIN":   c.AutoLogin   = val.Equals("true", StringComparison.OrdinalIgnoreCase); break;
                }
            }
            return c;
        }

        public void Save()
        {
            Directory.CreateDirectory(System.IO.Path.GetDirectoryName(Path_));

            var sb = new StringBuilder();
            sb.AppendLine("# ACTAS 전화연동 설정 — 직접 고치지 마시고 프로그램의 [설정] 을 쓰세요.");
            sb.AppendLine("# 비밀번호는 이 PC 에서만 풀리도록 암호화돼 있습니다.");
            sb.AppendLine("KT_SERVER    = " + KtServer);
            sb.AppendLine("KT_AUTHKEY   = " + KtAuthKey);
            sb.AppendLine("KT_LOGIN_ID  = " + KtLoginId);
            sb.AppendLine("KT_LOGIN_PW  = " + Encrypt(KtLoginPw));
            sb.AppendLine("ELV_USERNAME = " + ElvUsername);
            sb.AppendLine("ELV_URL      = " + ElvUrl);
            sb.AppendLine("WINDOW_TITLE = " + WindowTitle);
            sb.AppendLine("DEBUG_TABS   = " + (DebugTabs ? "1" : "0"));
            sb.AppendLine("PW_CHANGED   = " + PwChanged);
            sb.AppendLine("PW_SNOOZED   = " + PwSnoozed);
            sb.AppendLine("ELV_SECRET   = " + ElvSecret);
            sb.AppendLine("AUTO_LOGIN   = " + (AutoLogin ? "true" : "false"));

            File.WriteAllText(Path_, sb.ToString(), Encoding.UTF8);
        }

        // ── 비밀번호 만료 ────────────────────────────────────────
        //
        // KT 계정 비밀번호는 3개월(90일)마다 바꿔야 한다 (규격서 3.2 로그인).
        // 만료되면 로그인이 410/411 로 막히고, 전화는 울리는데 화면에만 안 뜬다.
        //
        // 잔여일을 묻는 함수가 규격서에 없다. GetMemberInfo 는 아이디와 이름뿐이고
        // 그마저 전체 관리자만 쓸 수 있다. 그래서 날짜를 우리가 적어두고 센다.

        const int ExpireDays = 90;
        const int WarnDays   = 75;   // 15일 남으면 알린다

        public static string Today { get { return DateTime.Now.ToString("yyyyMMdd"); } }

        /// <summary>마지막 변경·연장일로부터 지난 날. 기록이 없으면 -1</summary>
        public int PwAgeDays()
        {
            DateTime at;
            if (!DateTime.TryParseExact(PwChanged, "yyyyMMdd", null,
                    System.Globalization.DateTimeStyles.None, out at)) return -1;

            return (int)(DateTime.Now.Date - at.Date).TotalDays;
        }

        /// <summary>만료까지 남은 날. 기록이 없으면 -1</summary>
        public int PwDaysLeft()
        {
            int age = PwAgeDays();
            return age < 0 ? -1 : ExpireDays - age;
        }

        /// <summary>지금 알려야 하는가 — 15일 안쪽이고, 오늘 [나중에] 를 누르지 않았을 때</summary>
        public bool PwShouldWarn()
        {
            int age = PwAgeDays();
            if (age < WarnDays) return false;
            return PwSnoozed != Today;
        }

        /// <summary>바꾸거나 연장했다 — 오늘로 기록하고 미루기는 지운다</summary>
        public void PwMarkChanged()
        {
            PwChanged = Today;
            PwSnoozed = "";
            Save();
        }

        /// <summary>설정이 다 채워졌는가</summary>
        public bool IsReady
        {
            get
            {
                return KtAuthKey.Length > 0 && KtLoginId.Length > 0 &&
                       KtLoginPw.Length > 0 && ElvUsername.Length > 0 && ElvSecret.Length > 0;
            }
        }

        // ── DPAPI ────────────────────────────────────────────────
        // CurrentUser 범위 — 같은 PC 의 다른 윈도우 계정도 못 푼다.

        static readonly byte[] Salt = Encoding.UTF8.GetBytes("ACTAS-CTI-2026");

        static string Encrypt(string plain)
        {
            if (string.IsNullOrEmpty(plain)) return "";
            try
            {
                var data = ProtectedData.Protect(
                    Encoding.UTF8.GetBytes(plain), Salt, DataProtectionScope.CurrentUser);
                return Convert.ToBase64String(data);
            }
            catch { return ""; }
        }

        static string Decrypt(string cipher)
        {
            if (string.IsNullOrEmpty(cipher)) return "";
            try
            {
                var data = ProtectedData.Unprotect(
                    Convert.FromBase64String(cipher), Salt, DataProtectionScope.CurrentUser);
                return Encoding.UTF8.GetString(data);
            }
            catch
            {
                // 다른 PC·다른 계정에서 복사해 온 설정이면 여기로 온다. 다시 입력받아야 한다.
                return "";
            }
        }

        /// <summary>ELV_URL 에서 마지막 경로만 떼어 같은 위치의 다른 엔드포인트 주소를 만든다</summary>
        public string BaseUrl()
        {
            int p = ElvUrl.LastIndexOf('/');
            return p > 0 ? ElvUrl.Substring(0, p + 1) : ElvUrl;
        }

        /// <summary>
        /// 화면 주소 — ELV_URL 에서 /api/ 앞까지 (예: https://actas-ai.co.kr/elv/).
        /// 알림을 눌렀는데 브라우저가 아예 안 떠 있을 때 이 주소를 연다.
        /// </summary>
        public string SiteUrl()
        {
            int p = ElvUrl.IndexOf("/api/", StringComparison.OrdinalIgnoreCase);
            return p > 0 ? ElvUrl.Substring(0, p + 1) : ElvUrl;
        }

        /// <summary>
        /// 브라우저 탭을 알아보는 단서들. 알림을 눌렀을 때 이 중 하나라도
        /// 탭 이름에 들어 있으면 우리 화면으로 본다.
        ///
        /// 탭 이름은 페이지 제목인데, elv 제목은 사업체마다 다르고(LOGO_TITLE)
        /// 비어 있으면 브라우저가 주소를 대신 보여준다. 그래서 하나만 봐서는 안 된다.
        /// </summary>
        public string[] TabTokens()
        {
            var list = new System.Collections.Generic.List<string>();

            // ① 설정에 적어 둔 제목 (사업체 제목이 특이할 때 재빌드 없이 맞춘다)
            if (!string.IsNullOrEmpty(WindowTitle)) list.Add(WindowTitle);

            // ② 주소 — 제목이 비어 있으면 브라우저가 이걸 보여준다
            //    (예: actas-ai.co.kr/elv)
            var s = SiteUrl();
            int scheme = s.IndexOf("://", StringComparison.Ordinal);
            if (scheme > 0) s = s.Substring(scheme + 3);
            s = s.TrimEnd('/');
            if (s.Length > 0) list.Add(s);

            // ③ 제품명 — 제목이 설정돼 있으면 대개 여기에 들어 있다
            list.Add("ACTAS");

            return list.ToArray();
        }
    }
}
