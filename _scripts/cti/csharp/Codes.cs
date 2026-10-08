using System;
using System.IO;
using System.Text;

namespace ActasCti
{
    /// <summary>통화매니저 API 매뉴얼 v1.35 의 반환값·상태값 해석</summary>
    public static class Codes
    {
        /// <summary>규격서 3.2.1 — Login() 반환값</summary>
        public static string LoginMsg(int c)
        {
            switch (c)
            {
                case 200:  return "서버로 요청 성공";
                case 301:  return "다른 위치에서 로그인 중";
                case 401:  return "미등록 아이디";
                case 402:  return "비밀번호 오류 5회 초과(잠김)";
                case 403:  return "임시비밀번호 로그인";
                case 405:  return "비밀번호 오류";
                case 407:  return "접속 IP 오류";
                case 408:  return "미등록 PC(MAC 제한)";
                case 410:
                case 411:  return "비밀번호 기간만료";
                case 500:  return "HTTPS/HTTP 요청 실패";
                case 1000: return "이미 로그인 중";
                case 1502: return "협정 만료일이 지남";
                case 1503: return "인증키 유효기간이 지남";
                case 1504: return "인증키 비활성";
                case 1505: return "인증키 타입 오류";
                case 1506: return "개발 서버인데 상용 인증키";
                case 1507: return "상용 서버인데 개발 인증키";
                case 1600: return "네트웍 오류";
                case 1700: return "API 환경 정보 없음(실행 경로)";
                case 1701: return "데이터 파일 초기화 오류";
                case 1702: return "PC 메모리 부족";
                default:   return "알 수 없는 값";
            }
        }

        /// <summary>사용자에게 그대로 보여줄 안내. 기술 용어 대신 할 일을 적는다.</summary>
        public static string LoginAdvice(int c)
        {
            switch (c)
            {
                case 301:  return "다른 PC 에서 같은 계정으로 연결돼 있습니다. 그쪽에서 해제한 뒤 다시 시도해주세요.";
                case 401:  return "등록되지 않은 아이디입니다. [설정] 에서 계정을 확인해주세요.";
                case 402:  return "비밀번호를 5번 틀려 계정이 잠겼습니다. 담당자에게 문의해주세요.";
                case 403:
                case 410:
                case 411:  return "비밀번호를 바꿔야 합니다. 안내 창에서 변경한 뒤 [설정] 에도 새 비밀번호를 넣어주세요.";
                case 405:  return "비밀번호가 맞지 않습니다. [설정] 에서 다시 넣어주세요.";
                case 407:  return "이 PC 의 인터넷 주소에서는 접속할 수 없습니다. 담당자에게 문의해주세요.";
                case 408:  return "등록되지 않은 PC 입니다. 담당자에게 문의해주세요.";
                case 1502:
                case 1503:
                case 1504: return "서비스 이용 기간이 끝났습니다. 담당자에게 문의해주세요.";
                case 1506:
                case 1507: return "서버 설정이 맞지 않습니다. 담당자에게 문의해주세요.";
                case 1600:
                case 500:  return "인터넷 연결을 확인해주세요.";
                case 1700:
                case 1701: return "프로그램 파일에 문제가 있습니다. 다시 설치해주세요.";
                default:   return "연결하지 못했습니다. (코드 " + c + ") 담당자에게 문의해주세요.";
            }
        }

        /// <summary>규격서 10.1 — 수신상태</summary>
        public static string CallState(string c)
        {
            switch (c)
            {
                case "200": return "통화 연결";
                case "201": return "수신중";
                case "202":
                case "203": return "부재중";
                case "204": return "부재중(수신차단)";
                case "291": return "통화 종료";
                case "401": return "결번";
                case "404": return "통화중";
                case "405": return "무응답";
                case "406": return "착신 연결 실패";
                case "407": return "발신자가 끊음";
                case "408": return "착신자가 끊음";
                default:    return "상태 " + c;
            }
        }

        /// <summary>벨이 울리는 중인가 (알림을 띄울 상태)</summary>
        public static bool IsRinging(string c) { return c == "201"; }

        /// <summary>못 받은 전화인가 (부재중으로 알려야 할 상태)</summary>
        public static bool IsMissed(string c)
        {
            return c == "202" || c == "203" || c == "204" || c == "407" || c == "408";
        }

        /// <summary>규격서 3.7.3 — PasswdChange 반환값</summary>
        public static string PasswdChangeMsg(int c)
        {
            switch (c)
            {
                case 200: return "비밀번호를 바꿨습니다";
                case 300: return "지금 비밀번호가 맞지 않습니다";
                case 409: return "새 비밀번호가 규칙에 맞지 않습니다";
                case 0:   return "변경하지 못했습니다";
                default:  return "알 수 없는 값 (" + c + ")";
            }
        }

        /// <summary>규격서 3.7.4 — PasswdExpiredExtend 반환값</summary>
        public static string PasswdExtendMsg(int c)
        {
            switch (c)
            {
                case 200: return "90일 연장했습니다";
                case 0:   return "연장하지 못했습니다";
                default:  return "알 수 없는 값 (" + c + ")";
            }
        }

        /// <summary>규격서 3.7.3 — 새 비밀번호 규칙. 창에 그대로 보여준다</summary>
        public const string PasswdRule =
            "8자 이상, 영문·숫자·특수문자를 각각 하나 이상 섞어야 합니다.\n" +
            "아이디와 같을 수 없고, 연속된 3글자(123, abc, aaa 등)는 쓸 수 없습니다.";

        /// <summary>규격서 5.1.1 — SendSMS 반환값</summary>
        public static string SmsMsg(int c)
        {
            switch (c)
            {
                case 200:  return "보냈습니다";
                case 0:    return "서버 요청 실패";
                case 2000: return "로그인 상태가 아닙니다";
                case 4001: return "받는 사람이 없습니다";
                case 4003: return "내용이 없습니다";
                case 4004: return "하루 발송 한도를 넘었습니다";
                case 4005: return "발신번호가 맞지 않습니다. 청약한 회선 번호여야 합니다";
                case 4006: return "내용이 깁니다 (최대 80바이트)";
                default:   return "알 수 없는 값 (" + c + ")";
            }
        }

        /// <summary>규격서 3.8.1 — EventLogin</summary>
        public static string LoginEventMsg(int c)
        {
            switch (c)
            {
                case 200: return "로그인 성공";
                case 300: return "로그아웃";
                case 400: return "강제 로그아웃";
                case 401: return "CP 강제 로그아웃(인증키 차단)";
                default:  return "기타";
            }
        }
    }

    /// <summary>
    /// 로그. 날짜별로 남기고 오래된 것은 지운다.
    /// 문제가 생기면 사업체가 이 파일을 보내주면 된다.
    /// </summary>
    public static class Logger
    {
        const int KeepDays = 14;

        static readonly object Lock = new object();

        public static string Folder
        {
            get
            {
                return Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    "ACTAS", "전화연동", "logs");
            }
        }

        public static void Write(string message)
        {
            try
            {
                lock (Lock)
                {
                    Directory.CreateDirectory(Folder);
                    var path = Path.Combine(Folder, DateTime.Now.ToString("yyyy-MM-dd") + ".log");
                    File.AppendAllText(path,
                        DateTime.Now.ToString("HH:mm:ss") + "  " + message + Environment.NewLine,
                        Encoding.UTF8);
                }
            }
            catch { /* 로그 실패가 프로그램을 멈추게 하면 안 된다 */ }
        }

        public static void Cleanup()
        {
            try
            {
                if (!Directory.Exists(Folder)) return;
                var limit = DateTime.Now.AddDays(-KeepDays);
                foreach (var f in Directory.GetFiles(Folder, "*.log"))
                    if (File.GetLastWriteTime(f) < limit) File.Delete(f);
            }
            catch { }
        }
    }
}
