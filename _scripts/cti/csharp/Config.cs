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
            sb.AppendLine("ELV_SECRET   = " + ElvSecret);
            sb.AppendLine("AUTO_LOGIN   = " + (AutoLogin ? "true" : "false"));

            File.WriteAllText(Path_, sb.ToString(), Encoding.UTF8);
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
    }
}
