using System;
using System.Collections.Generic;
using System.Web.Script.Serialization;
using KTOpenAPI;

namespace ActasCti
{
    /// <summary>
    /// 화면이 부른 KT 기능을 실제로 실행하는 곳.
    ///
    /// 브라우저는 이 PC 의 COM 을 직접 못 부른다. 그래서 화면이 서버에 "이 기능을
    /// 이런 인자로 불러달라" 고 적어두면, 에이전트가 가져와 여기서 실행하고
    /// 결과를 JSON 으로 돌려준다.
    ///
    /// 기능을 늘릴 때는 Run() 의 switch 에 한 줄 추가하면 된다.
    /// 규격서 7장(주소록) · 8장(팩스) · 5장(문자)을 이 방식으로 붙여 나간다.
    /// </summary>
    static class KtCalls
    {
        static readonly JavaScriptSerializer Json = new JavaScriptSerializer();

        /// <summary>
        /// fn 이름으로 갈라 실행한다. 결과는 JSON 문자열.
        /// 던지지 않는다 — 어떤 오류든 화면에 문장으로 전해져야 한다.
        /// </summary>
        public static Result Run(KTPCBizXClass kt, string fn, string argsJson)
        {
            Dictionary<string, object> a;
            try   { a = Parse(argsJson); }
            catch (Exception e) { return Result.Fail("인자를 읽지 못했습니다: " + e.Message); }

            try
            {
                switch (fn)
                {
                    // ── 주소록 (규격서 7장) ──────────────────────
                    case "address.groupList": return AddressGroupList(kt, a);
                    case "address.dataList":  return AddressDataList(kt, a);
                    case "address.groupNew":  return AddressGroupNew(kt, a);
                    case "address.groupEdit": return AddressGroupEdit(kt, a);
                    case "address.groupDel":  return AddressGroupDel(kt, a);
                    case "address.dataNew":   return AddressDataNew(kt, a);
                    case "address.dataEdit":  return AddressDataEdit(kt, a);
                    case "address.dataDel":   return AddressDataDel(kt, a);
                    case "address.resync":    return Simple(kt.ReSyncAddress(Short(a, "type", 2)));

                    // ── 문자 (규격서 5장) ────────────────────────
                    case "sms.send":          return SmsSend(kt, a);
                    case "sms.reserve":       return SmsReserve(kt, a);
                    case "sms.reserveEdit":   return SmsReserveEdit(kt, a);
                    case "sms.reserveCancel": return SmsReserveCancel(kt, a);
                    case "sms.list":          return SmsList(kt, a);
                    case "sms.del":           return SmsDel(kt, a);
                    case "sms.reserveGet":    return SmsReserveGet(kt, a);
                    case "sms.count":         return Result.Ok(
                                                  new Dictionary<string, object> {
                                                      { "count", kt.GetSmsCount(Short(a, "kind", 1)) } });

                    // ── 팩스 (규격서 8장) ────────────────────────
                    case "fax.send":      return FaxSend(kt, a, false);
                    case "fax.reserve":   return FaxSend(kt, a, true);
                    case "fax.sendList":  return FaxSendList(kt, a);
                    case "fax.recvList":  return FaxRecvList(kt, a);
                    case "fax.cancel":    return Simple(kt.FaxCancel(Int(a, "jobId", 0), Int(a, "subJobId", 0)));
                    case "fax.resend":    return Simple(kt.FaxReSend(Int(a, "jobId", 0), Int(a, "subJobId", 0)));
                    case "fax.enabled":   return Result.Ok(
                                              new Dictionary<string, object> { { "enabled", kt.FaxIsEnable() } });

                    default:
                        return Result.Fail("모르는 기능입니다: " + fn);
                }
            }
            catch (Exception e)
            {
                Logger.Write("[기능] " + fn + " 실행 중 오류: " + e);
                return Result.Fail(e.Message);
            }
        }

        // ── 주소록 ───────────────────────────────────────────
        //
        // cAgType / cAdType — 2·3·6 세 종류가 있고, 신규 등록은 2 와 6 만 된다(7.1.3).
        // 화면에서 넘겨주고, 안 넘기면 2 를 쓴다.

        static Result AddressGroupList(KTPCBizXClass kt, Dictionary<string, object> a)
        {
            var raw = kt.GetAddressGroupList(Short(a, "type", 2), Str(a, "pdbid"));

            var rows = new List<object>();
            int seen = 0;
            foreach (var one in Each(raw))
            {
                seen++;
                var g = one as KTDAddressGroup;
                if (g == null) continue;
                rows.Add(new Dictionary<string, object> {
                    { "dbid",  g.DBID  }, { "name", g.Name },
                    { "pdbid", g.PDBID }, { "type", g.Type },
                });
            }
            Describe("groupList", raw, seen, rows.Count);
            return Result.Ok(rows);
        }

        static Result AddressDataList(KTPCBizXClass kt, Dictionary<string, object> a)
        {
            var raw = kt.GetAddressDataList(Short(a, "type", 2), Str(a, "pdbid"));

            var rows = new List<object>();
            int seen = 0;
            foreach (var one in Each(raw))
            {
                seen++;
                var d = one as KTDAddressData;
                if (d == null) continue;
                rows.Add(ToMap(d));
            }
            Describe("dataList", raw, seen, rows.Count);
            return Result.Ok(rows);
        }

        static Result AddressGroupNew(KTPCBizXClass kt, Dictionary<string, object> a)
        {
            string name = Str(a, "name");
            if (name.Length == 0) return Result.Fail("그룹 이름을 넣어주세요.");

            // 7.1.3 — 성공하면 만들어진 DBID, 실패하면 "0"
            string dbid = kt.NewAddressGroup(Short(a, "type", 2), name, Str(a, "pdbid"));
            if (string.IsNullOrEmpty(dbid) || dbid == "0") return Result.Fail("그룹을 만들지 못했습니다.");

            return Result.Ok(new Dictionary<string, object> { { "dbid", dbid } });
        }

        static Result AddressGroupEdit(KTPCBizXClass kt, Dictionary<string, object> a)
        {
            string dbid = Str(a, "dbid");
            if (dbid.Length == 0) return Result.Fail("고칠 그룹을 고르지 않았습니다.");

            return Simple(kt.EditAddressGroup(Short(a, "type", 2), Str(a, "name"), Str(a, "pdbid"), dbid));
        }

        static Result AddressGroupDel(KTPCBizXClass kt, Dictionary<string, object> a)
        {
            string dbid = Str(a, "dbid");
            if (dbid.Length == 0) return Result.Fail("지울 그룹을 고르지 않았습니다.");

            return Simple(kt.DelAddressGroup(Short(a, "type", 2), dbid));
        }

        static Result AddressDataNew(KTPCBizXClass kt, Dictionary<string, object> a)
        {
            if (Str(a, "name").Length == 0) return Result.Fail("이름을 넣어주세요.");

            string dbid = kt.NewAddressData(
                Short(a, "type", 2), Str(a, "pdbid"),
                Str(a, "name"),      Str(a, "mobile"),  Str(a, "office"),   Str(a, "home"),
                Str(a, "fax"),       Str(a, "company"), Str(a, "team"),     Str(a, "title"),
                Str(a, "business"),  Str(a, "zipcode"), Str(a, "address"),  Str(a, "email"),
                Str(a, "memo"),      Str(a, "birthday"), Short(a, "birthtype", 0),
                Str(a, "ngname"),    Str(a, "metchance"), Str(a, "keyman"), Str(a, "favorite"));

            if (string.IsNullOrEmpty(dbid) || dbid == "0") return Result.Fail("등록하지 못했습니다.");
            return Result.Ok(new Dictionary<string, object> { { "dbid", dbid } });
        }

        static Result AddressDataEdit(KTPCBizXClass kt, Dictionary<string, object> a)
        {
            string dbid = Str(a, "dbid");
            if (dbid.Length == 0) return Result.Fail("고칠 항목을 고르지 않았습니다.");

            return Simple(kt.EditAddressData(
                Short(a, "type", 2), Str(a, "pdbid"), dbid,
                Str(a, "name"),      Str(a, "mobile"),  Str(a, "office"),   Str(a, "home"),
                Str(a, "fax"),       Str(a, "company"), Str(a, "team"),     Str(a, "title"),
                Str(a, "business"),  Str(a, "zipcode"), Str(a, "address"),  Str(a, "email"),
                Str(a, "memo"),      Str(a, "birthday"), Short(a, "birthtype", 0),
                Str(a, "ngname"),    Str(a, "metchance"), Str(a, "keyman"), Str(a, "favorite")));
        }

        static Result AddressDataDel(KTPCBizXClass kt, Dictionary<string, object> a)
        {
            string dbid = Str(a, "dbid");
            if (dbid.Length == 0) return Result.Fail("지울 항목을 고르지 않았습니다.");

            return Simple(kt.DelAddressData(Short(a, "type", 2), Str(a, "pdbid"), dbid));
        }

        // ── 문자 ─────────────────────────────────────────────
        //
        // 수신번호는 인자가 아니라 SetRecvPhone 으로 하나씩 쌓아두고 보낸다(규격서 5.1.1).
        // 쌓인 번호는 보낸 뒤에도 남으므로 앞뒤로 반드시 비운다 —
        // 안 비우면 다음 문자가 엉뚱한 사람에게도 간다.

        /// <summary>한 번에 보낼 수 있는 최대 인원 (규격서 5.1.1)</summary>
        const int MAX_RECV = 32;

        /// <summary>단문 한도. 넘으면 장문(MMS)으로 보내야 한다.</summary>
        const int SMS_BYTES = 80;

        static Result SmsSend(KTPCBizXClass kt, Dictionary<string, object> a)
        {
            var to = List(a, "to");
            string message = Str(a, "message");

            if (to.Count == 0)       return Result.Fail("받는 사람을 넣어주세요.");
            if (message.Length == 0) return Result.Fail("내용을 넣어주세요.");
            if (to.Count > MAX_RECV)
                return Result.Fail("한 번에 " + MAX_RECV + "명까지입니다. (지금 " + to.Count + "명)");

            // 길이는 EUC-KR 바이트로 센다 — KT 가 그 기준으로 자른다
            int bytes = System.Text.Encoding.GetEncoding("euc-kr").GetByteCount(message);
            bool useMms = Str(a, "kind") == "mms" || bytes > SMS_BYTES;

            if (!useMms && bytes > SMS_BYTES)
                return Result.Fail("내용이 깁니다. " + SMS_BYTES + "바이트까지입니다. (지금 " + bytes + ")");

            string caller = Caller(a);
            string display = Str(a, "display");

            Logger.Write(string.Format("[문자] {0} 발송 — 수신={1}명, {2}바이트",
                                       useMms ? "장문" : "단문", to.Count, bytes));

            try
            {
                kt.RemoveAllRecvPhone();
                foreach (var one in to) kt.SetRecvPhone(one);

                int rc = useMms ? kt.SendMMS(caller, display, message)
                                : kt.SendSMS(caller, display, message);

                Logger.Write(string.Format("[문자] 반환 = {0}  ({1})", rc, Codes.SmsMsg(rc)));

                if (rc != 200) return Result.Fail(Codes.SmsMsg(rc));

                return Result.Ok(new Dictionary<string, object> {
                    { "sent",  to.Count }, { "bytes", bytes },
                    { "kind",  useMms ? "mms" : "sms" },
                });
            }
            finally
            {
                // 성공이든 실패든 반드시 비운다
                try { kt.RemoveAllRecvPhone(); } catch { }
            }
        }

        // ── 문자 예약 ────────────────────────────────────────
        //
        // 보내는 방법은 즉시 발송과 같다. 날짜만 YYYYMMDDhhmm 으로 더 준다(규격서 5.2.1).
        // 돌려주는 값이 다르다 — 실패하면 "0", 성공하면 seqno 다. 그 seqno 로 뒤에
        // 고치거나 취소한다.

        static Result SmsReserve(KTPCBizXClass kt, Dictionary<string, object> a)
        {
            var to = List(a, "to");
            string message  = Str(a, "message");
            string sendDate = Digits(Str(a, "sendDate"));

            if (to.Count == 0)         return Result.Fail("받는 사람을 넣어주세요.");
            if (message.Length == 0)   return Result.Fail("내용을 넣어주세요.");
            if (to.Count > MAX_RECV)   return Result.Fail("한 번에 " + MAX_RECV + "명까지입니다.");
            if (sendDate.Length != 12) return Result.Fail("보낼 시각을 정해주세요.");

            int bytes = System.Text.Encoding.GetEncoding("euc-kr").GetByteCount(message);
            bool useMms = bytes > SMS_BYTES;

            Logger.Write(string.Format("[문자] 예약 {0} — 수신={1}명, {2}, {3}바이트",
                                       useMms ? "장문" : "단문", to.Count, sendDate, bytes));
            try
            {
                kt.RemoveAllRecvPhone();
                foreach (var one in to) kt.SetRecvPhone(one);

                string caller  = Caller(a);
                string display = Str(a, "display");

                if (useMms)
                {
                    int rc = kt.SendReserveMMS(caller, display, message, sendDate);
                    if (rc != 200) return Result.Fail(Codes.SmsMsg(rc));
                    return Result.Ok(new Dictionary<string, object> { { "sent", to.Count } });
                }

                string seq = kt.SendReserveSMS(caller, display, message, sendDate);
                Logger.Write(string.Format("[문자] SendReserveSMS 반환 = '{0}'  (발신={1}, 시각={2})",
                                           seq, caller, sendDate));

                if (string.IsNullOrEmpty(seq) || seq == "0")
                    return Result.Fail("예약하지 못했습니다. 발신번호와 보낼 시각을 확인해주세요. "
                                     + "(너무 가까운 시각은 거절될 수 있습니다)");

                // 목록에서는 이 번호를 다시 얻을 길이 없다. 지금 적어둬야 나중에 고치고 취소한다.
                RememberSeq(seq, sendDate, to, message);

                return Result.Ok(new Dictionary<string, object> {
                    { "seqno", seq }, { "sent", to.Count },
                });
            }
            finally { try { kt.RemoveAllRecvPhone(); } catch { } }
        }

        static Result SmsReserveEdit(KTPCBizXClass kt, Dictionary<string, object> a)
        {
            string seq      = Str(a, "seqno");
            string message  = Str(a, "message");
            string sendDate = Digits(Str(a, "sendDate"));

            if (seq.Length == 0)       return Result.Fail("고칠 예약을 고르지 않았습니다.");
            if (message.Length == 0)   return Result.Fail("내용을 넣어주세요.");
            if (sendDate.Length != 12) return Result.Fail("보낼 시각을 정해주세요.");

            Logger.Write("[문자] 예약수정 요청 — seqno='" + seq + "', 시각=" + sendDate);

            var r = Simple(kt.EditReserveSMS(Caller(a), seq, message, sendDate));

            // 적어둔 열쇠가 '시각+번호' 인데 수정하면 그 시각이 바뀐다.
            // 같이 고쳐두지 않으면 바로 다음 조회부터 이 예약을 못 찾는다.
            if (r.Success) MoveSeq(seq, sendDate, message);

            return r;
        }

        static Result SmsReserveCancel(KTPCBizXClass kt, Dictionary<string, object> a)
        {
            string seq = Str(a, "seqno");
            if (seq.Length == 0) return Result.Fail("취소할 예약을 고르지 않았습니다.");

            // 엉뚱한 번호로 불러도 KT 가 200 을 돌려주는 것을 봤다(취소했는데 문자가 나갔다).
            // 무엇을 보냈는지 남겨야 나중에 맞춰볼 수 있다.
            Logger.Write("[문자] 예약취소 요청 — seqno='" + seq + "'");
            return Simple(kt.CancelReserveSMS(Caller(a), seq));
        }

        /// <summary>
        /// 문자 목록 (규격서 5.3.3).
        /// sKind — 1:전체, 2:발신, 3:수신, 5:실패, 6:예약전송 (4 는 없다)
        /// </summary>
        static Result SmsList(KTPCBizXClass kt, Dictionary<string, object> a)
        {
            short kind  = Short(a, "kind",  1);
            int   start = Short(a, "start", 0);
            int   count = Short(a, "count", 200);

            var raw = kt.GetSmsList(kind, start, count);

            var rows = new List<object>();
            int seen = 0;
            foreach (var one in Each(raw))
            {
                seen++;
                var s = one as KTDSmsData;
                if (s == null) continue;

                rows.Add(ToMap(s));
            }
            Describe("smsList", raw, seen, rows.Count);

            // KT 가 안 주는 예약번호를, 예약할 때 적어둔 것에서 되붙인다
            var seqs = LoadSeqs();
            foreach (Dictionary<string, object> r in rows)
            {
                if (Convert.ToString(r["result"]) != "900") continue;

                string when = Digits(Convert.ToString(r["reserve"]));
                if (when.Length > 12) when = when.Substring(0, 12);

                Seq found;
                if (!seqs.TryGetValue(when + "|" + Convert.ToString(r["callee"]), out found)) continue;

                r["group"] = found.No;

                // KT 목록은 고친 시각을 반영하지 않고 처음 시각을 계속 준다.
                // 화면에 그대로 보여주면 사람이 '수정이 안 됐다' 고 읽는다. 아는 값으로 바꿔 준다.
                if (found.SendDate.Length == 12 && found.SendDate != when)
                    r["reserve"] = found.SendDate;

                if (!string.IsNullOrEmpty(found.Message))
                    r["message"] = found.Message;
            }
            PruneSeqs();

            return Result.Ok(rows);
        }

        // ── 팩스 ─────────────────────────────────────────────
        //
        // 받는 번호는 문자와 같은 방식이다 — FaxAddNumber 로 쌓고 보낸 뒤 비운다.
        // 다른 점은 보낼 것이 '이 PC 에 있는 파일' 이라는 것. 화면이 고른 파일은
        // 서버에 올라와 있으므로, 토큰으로 내려받아 임시파일로 떨군 뒤 그 경로를 넘긴다.
        //
        // 규격서 8.1 — 변환하려면 그 문서의 프로그램(한글·엑셀 등)과 CSD Writer 가
        // 이 PC 에 깔려 있어야 한다. PDF 가 가장 안전하다.

        static Result FaxSend(KTPCBizXClass kt, Dictionary<string, object> a, bool reserve)
        {
            if (kt.FaxIsEnable() == 0)
            {
                // 꺼져 있으면 켜 본다(8.1). 서비스 자체가 없으면 그래도 안 된다.
                try { kt.FaxEnable(1); } catch { }
                if (kt.FaxIsEnable() == 0)
                    return Result.Fail("이 계정에서는 팩스를 쓸 수 없습니다. KT 에 문의해주세요.");
            }

            var numbers = List(a, "numbers");
            string subject = Str(a, "subject");
            string token   = Str(a, "token");

            if (numbers.Count == 0) return Result.Fail("받는 팩스번호를 넣어주세요.");
            if (token.Length == 0)  return Result.Fail("보낼 파일이 없습니다.");

            string path = null;
            try
            {
                path = Download(token);
                if (path == null) return Result.Fail("보낼 파일을 받아오지 못했습니다. 다시 올려주세요.");

                kt.FaxClearNumber();
                foreach (var one in numbers) kt.FaxAddNumber("", one);

                Logger.Write(string.Format("[팩스] {0} — 수신={1}곳, 파일={2}",
                                           reserve ? "예약" : "발송", numbers.Count, path));

                short sendType = Short(a, "sendType", 1);
                object raw = reserve
                        ? kt.FaxReserve(sendType, subject, path, Digits(Str(a, "reserveTime")))
                        : kt.FaxSend(sendType, subject, path);

                var r = raw as KTDFaxSendResult;
                if (r == null) return Result.Fail("팩스 서버가 응답하지 않았습니다.");

                Logger.Write(string.Format("[팩스] 결과 = {0}, JobID = {1}, 쪽수 = {2}",
                                           r.Result, r.JobID, r.PageCount));

                if (r.Result != 200) return Result.Fail(Codes.FaxMsg(r.Result));

                return Result.Ok(new Dictionary<string, object> {
                    { "jobId", r.JobID }, { "msgId", r.MsgID },
                    { "pages", r.PageCount }, { "sent", numbers.Count },
                });
            }
            finally
            {
                try { kt.FaxClearNumber(); } catch { }
                if (path != null) { try { System.IO.File.Delete(path); } catch { } }
            }
        }

        static Result FaxSendList(KTPCBizXClass kt, Dictionary<string, object> a)
        {
            var raw = kt.FaxSendList(Short(a, "lines", 100), Short(a, "page", 1),
                                     Digits(Str(a, "from")), Digits(Str(a, "to")));

            var rows = new List<object>();
            int seen = 0;
            foreach (var one in Each(raw))
            {
                seen++;
                var f = one as KTDFaxSendInfo;
                if (f == null) continue;

                rows.Add(new Dictionary<string, object> {
                    { "jobId",   f.JobID },     { "subject",  f.Subject },
                    { "endTime", f.sEndTime },  { "faxNumber", f.FaxNumber },
                    { "feeNumber", f.FeeNumber },
                    { "result",  f.Result },    { "total",    f.TotalCount },
                });
            }
            Describe("faxSendList", raw, seen, rows.Count);
            return Result.Ok(rows);
        }

        static Result FaxRecvList(KTPCBizXClass kt, Dictionary<string, object> a)
        {
            var raw = kt.FaxRecvList(Short(a, "lines", 100), Short(a, "page", 1),
                                     Digits(Str(a, "from")), Digits(Str(a, "to")));

            var rows = new List<object>();
            int seen = 0;
            foreach (var one in Each(raw))
            {
                seen++;
                var f = one as KTDFaxRecvInfo;
                if (f == null) continue;

                rows.Add(new Dictionary<string, object> {
                    { "msgId",  f.MsgID },        { "from",  f.SendFaxNumer },
                    { "to",     f.RecvFaxNumer }, { "time",  f.RecvTime },
                    { "pages",  f.PageCount },    { "read",  f.AlreadyView },
                    { "total",  f.TotalCount },
                });
            }
            Describe("faxRecvList", raw, seen, rows.Count);
            return Result.Ok(rows);
        }

        /// <summary>서버가 보관 중인 파일을 임시 폴더로 내려받는다. 경로를 돌려준다.</summary>
        static string Download(string token)
        {
            var cfg = Config.Load();

            string url = cfg.BaseUrl() + "fax-file?line=" + Uri.EscapeDataString(cfg.KtLine())
                       + "&token=" + Uri.EscapeDataString(token);

            string path = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "elvfax_" + token);

            try
            {
                var req = (System.Net.HttpWebRequest)System.Net.WebRequest.Create(url);
                req.Method  = "GET";
                req.Timeout = 30000;
                req.Headers.Add("X-Cti-Secret", cfg.ElvSecret);

                using (var res = req.GetResponse())
                using (var src = res.GetResponseStream())
                using (var dst = System.IO.File.Create(path))
                    src.CopyTo(dst);

                return path;
            }
            catch (Exception e)
            {
                Logger.Write("[팩스] 파일 내려받기 실패: " + e.Message);
                return null;
            }
        }

        static int Int(Dictionary<string, object> a, string key, int fallback)
        {
            object v;
            if (!a.TryGetValue(key, out v) || v == null) return fallback;

            int n;
            return int.TryParse(v.ToString(), out n) ? n : fallback;
        }

        /// <summary>
        /// 예약 한 건을 다시 읽는다 (규격서 5.2.5).
        ///
        /// 목록(GetSmsList)이 Group 을 안 채워주는 걸 확인했다. 고치거나 취소하려면
        /// sSmsGroupSeqNo 가 있어야 하므로, 고른 건만 이걸로 다시 읽어 본다.
        /// </summary>
        static Result SmsReserveGet(KTPCBizXClass kt, Dictionary<string, object> a)
        {
            string dbid = Str(a, "dbid");
            if (dbid.Length == 0) return Result.Fail("예약을 고르지 않았습니다.");

            var raw = kt.GetReserveSMS(dbid);

            foreach (var one in Each(raw))
            {
                var s = one as KTDSmsData;
                if (s != null) return Result.Ok(ToMap(s));
            }

            Describe("reserveGet", raw, 0, 0);
            return Result.Fail("예약 내용을 읽지 못했습니다.");
        }

        /// <summary>KTDSmsData 를 화면이 쓰는 꼴로. 열쇠가 될 만한 값은 모두 넘긴다.</summary>
        static Dictionary<string, object> ToMap(KTDSmsData s)
        {
            return new Dictionary<string, object> {
                { "dbid",    s.DBID },    { "logicId", s.LogicID },
                { "group",   s.Group },   { "caller",  s.Caller },
                { "callee",  s.Callee },  { "message", s.Message },
                { "date",    s.Date },    { "result",  s.Result },
                { "reserve", s.Reserve }, { "type",    s.Type },
                { "kind",    s.Kind },    { "status",  s.Status },
            };
        }

        static Result SmsDel(KTPCBizXClass kt, Dictionary<string, object> a)
        {
            string dbid = Str(a, "dbid");
            if (dbid.Length == 0) return Result.Fail("지울 문자를 고르지 않았습니다.");

            return Simple(kt.DelSms(dbid));
        }

        /// <summary>
        /// 발신번호. 화면이 비워두면 이 PC 의 회선번호를 쓴다.
        ///
        /// 빈 문자열을 넘기면 KT 가 거절한다 — 예약 발송이 이유 없이 "0" 으로 실패하던 게
        /// 이것이었다. 전화 수신 알림 쪽 코드는 처음부터 회선번호를 넣고 있었다.
        /// </summary>
        static string Caller(Dictionary<string, object> a)
        {
            string given = Digits(Str(a, "caller"));
            if (given.Length > 0) return given;

            try { return Config.Load().KtLine(); }
            catch { return ""; }
        }

        // ── 예약번호 보관 ────────────────────────────────────
        //
        // 예약을 고치거나 취소하려면 sSmsGroupSeqNo 가 있어야 한다. 그런데 목록
        // (GetSmsList)은 그 값을 안 준다 — Group 은 비어 있고, LogicID 는 엉뚱하며,
        // DBID 는 예약번호보다 1 크다(받는 사람이 여럿이면 더 벌어질 것이다).
        //
        // 번호를 주는 곳은 SendReserveSMS 의 반환값 한 군데뿐이다. 그래서 예약할 때
        // 받아서 적어두고, 목록을 읽을 때 되붙인다. 열쇠는 '보낼 시각 + 받는 번호' —
        // 같은 사람에게 같은 분(分)에 두 번 예약하지 않는 한 겹치지 않는다.
        //
        // 서버 DB 를 건드리지 않으려고 이 PC 에 파일로 둔다. 예약은 그 PC 에서 건 것만
        // 다루면 되고, 어차피 에이전트는 사업체당 한 대다.

        static string SeqStore()
        {
            return System.IO.Path.Combine(Logger.Folder, "..", "reserve.txt");
        }

        // 한 줄 꼴 — 예약번호 \t 지금시각 \t 받는번호 \t 처음시각 \t 지금내용
        //
        // '처음시각' 을 따로 둔 이유: KT 목록은 수정해도 처음 시각을 계속 보여준다.
        // 지금시각만 들고 있으면 한 번 고친 예약을 목록에서 다시 못 찾는다.
        //
        // 내용도 같은 이유로 적어둔다. KT 가 옛 내용을 주는데 그대로 보여주면
        // '수정이 안 됐다' 고 읽게 된다. 실제로 나가는 건 고친 쪽이다.

        static void RememberSeq(string seqno, string sendDate, List<string> callees, string message)
        {
            if (string.IsNullOrEmpty(seqno) || seqno == "0") return;

            try
            {
                var lines = new List<string>();
                foreach (var one in callees)
                    lines.Add(seqno + "\t" + sendDate + "\t" + one + "\t" + sendDate + "\t" + Flat(message));

                System.IO.File.AppendAllLines(SeqStore(), lines, System.Text.Encoding.UTF8);
            }
            catch (Exception e) { Logger.Write("[문자] 예약번호를 적어두지 못했습니다: " + e.Message); }
        }

        /// <summary>한 줄에 담으려고 줄바꿈·탭을 치환한다. 되돌릴 때 Unflat 을 쓴다.</summary>
        static string Flat(string s)
        {
            return (s ?? "").Replace("\\", "\\\\").Replace("\t", "\\t")
                            .Replace("\r", "").Replace("\n", "\\n");
        }

        static string Unflat(string s)
        {
            return (s ?? "").Replace("\\n", "\n").Replace("\\t", "\t").Replace("\\\\", "\\");
        }

        /// <summary>적어둔 한 줄</summary>
        class Seq
        {
            public string No;         // 예약번호
            public string SendDate;   // 지금 잡혀 있는 시각 (고치면 바뀐다)
            public string Message;    // 지금 잡혀 있는 내용 (고치면 바뀐다)
        }

        /// <summary>예약을 고쳤다 — 적어둔 줄의 시각과 내용도 따라 바꾼다.</summary>
        static void MoveSeq(string seqno, string newDate, string newMessage)
        {
            try
            {
                if (!System.IO.File.Exists(SeqStore())) return;

                var outp = new List<string>();
                foreach (var line in System.IO.File.ReadAllLines(SeqStore(), System.Text.Encoding.UTF8))
                {
                    var p = line.Split('\t');

                    // 처음시각은 그대로 둔다 — 목록에서 이 예약을 찾는 열쇠다
                    if (p.Length >= 3 && p[0] == seqno)
                        outp.Add(p[0] + "\t" + newDate + "\t" + p[2] + "\t"
                               + (p.Length >= 4 ? p[3] : p[1]) + "\t" + Flat(newMessage));
                    else
                        outp.Add(line);
                }

                System.IO.File.WriteAllLines(SeqStore(), outp, System.Text.Encoding.UTF8);
            }
            catch (Exception e) { Logger.Write("[문자] 예약번호 시각을 고치지 못했습니다: " + e.Message); }
        }

        /// <summary>
        /// '시각+받는번호' → 적어둔 내용. 처음시각과 지금시각 양쪽으로 찾을 수 있게 넣어둔다 —
        /// KT 목록은 처음시각을 주고, 사람이 보는 건 지금시각이다.
        /// </summary>
        static Dictionary<string, Seq> LoadSeqs()
        {
            var map = new Dictionary<string, Seq>();
            try
            {
                if (!System.IO.File.Exists(SeqStore())) return map;

                foreach (var line in System.IO.File.ReadAllLines(SeqStore(), System.Text.Encoding.UTF8))
                {
                    var p = line.Split('\t');
                    if (p.Length < 3) continue;

                    var s    = new Seq { No = p[0], SendDate = p[1],
                                         Message = p.Length >= 5 ? Unflat(p[4]) : null };
                    var orig = (p.Length >= 4 && p[3].Length > 0) ? p[3] : p[1];

                    map[orig + "|" + p[2]] = s;
                    map[p[1] + "|" + p[2]] = s;
                }
            }
            catch (Exception e) { Logger.Write("[문자] 예약번호를 읽지 못했습니다: " + e.Message); }
            return map;
        }

        /// <summary>
        /// 파일이 한없이 길어지지 않게 치운다. 보낼 시각이 하루 넘게 지난 줄만 버린다.
        ///
        /// 예전에는 '목록에 없으면 버린다' 로 짰는데, 열쇠가 한 번만 어긋나도
        /// 살아 있는 예약번호가 영영 사라졌다. 지우는 쪽이 틀리면 되돌릴 수 없으니
        /// 조금 남기는 쪽으로 기운다.
        /// </summary>
        static void PruneSeqs()
        {
            try
            {
                if (!System.IO.File.Exists(SeqStore())) return;

                string limit = DateTime.Now.AddDays(-1).ToString("yyyyMMddHHmm");

                var keep = new List<string>();
                foreach (var line in System.IO.File.ReadAllLines(SeqStore(), System.Text.Encoding.UTF8))
                {
                    var p = line.Split('\t');
                    if (p.Length < 3) continue;
                    if (string.Compare(p[1], limit, StringComparison.Ordinal) >= 0) keep.Add(line);
                }

                if (keep.Count != System.IO.File.ReadAllLines(SeqStore()).Length)
                    System.IO.File.WriteAllLines(SeqStore(), keep, System.Text.Encoding.UTF8);
            }
            catch { /* 정리 실패가 조회를 막으면 안 된다 */ }
        }

        /// <summary>숫자만 남긴다 — 화면이 2026-10-19 14:30 처럼 보내와도 받는다.</summary>
        static string Digits(string s)
        {
            var sb = new System.Text.StringBuilder();
            foreach (var ch in s ?? "") if (char.IsDigit(ch)) sb.Append(ch);
            return sb.ToString();
        }

        static Dictionary<string, object> ToMap(KTDAddressData d)
        {
            return new Dictionary<string, object> {
                { "dbid",    d.DBID },      { "name",     d.Name },
                { "mobile",  d.MobileNum }, { "office",   d.OfficeNum },
                { "home",    d.HomeNum },   { "fax",      d.FaxNum },
                { "company", d.CompanyName }, { "team",   d.Department },
                { "title",   d.Position },  { "business", d.Business },
                { "zipcode", d.ZipCode },   { "address",  d.Address },
                { "email",   d.Email },     { "memo",     d.Memo },
                { "birthday",d.BirthDay },  { "birthtype",d.BirthType },
                { "metchance", d.MetChance }, { "keyman", d.Keyman },
                { "favorite",  d.Favorite }, { "ngname", d.NgName },
            };
        }

        // ── 거들기 ───────────────────────────────────────────

        /// <summary>규격서의 공통 규약 — 200 이 성공이다.</summary>
        static Result Simple(int rc)
        {
            if (rc == 200) return Result.Ok(null);
            return Result.Fail("KT 가 거절했습니다. (코드 " + rc + ")");
        }

        /// <summary>
        /// 결과가 비었을 때 "KT 가 안 준 것" 인지 "우리가 못 푼 것" 인지 로그로 갈라준다.
        /// 돌아온 것의 실제 타입까지 남긴다 — 규격서에 COM Object 라고만 적혀 있어서
        /// 배열인지 단건인지 안 해보고는 알 수 없다.
        /// </summary>
        static void Describe(string what, object raw, int seen, int taken)
        {
            string type = raw == null ? "null" : raw.GetType().FullName;
            Logger.Write(string.Format("[기능] {0} 원본={1}, 훑음={2}, 담음={3}", what, type, seen, taken));
        }

        /// <summary>COM 이 돌려준 목록을 훑는다. 배열일 수도, 하나일 수도, 비어 있을 수도 있다.</summary>
        static IEnumerable<object> Each(object raw)
        {
            if (raw == null) yield break;

            var list = raw as System.Collections.IEnumerable;
            if (list != null && !(raw is string))
            {
                foreach (var one in list) yield return one;
                yield break;
            }
            yield return raw;
        }

        static Dictionary<string, object> Parse(string json)
        {
            if (string.IsNullOrEmpty(json)) return new Dictionary<string, object>();
            return Json.Deserialize<Dictionary<string, object>>(json)
                   ?? new Dictionary<string, object>();
        }

        static string Str(Dictionary<string, object> a, string key)
        {
            object v;
            if (!a.TryGetValue(key, out v) || v == null) return "";
            return v.ToString();
        }

        /// <summary>
        /// 여럿을 받는 인자. 화면이 배열로 줄 수도, 쉼표로 이어 줄 수도 있어 둘 다 받는다.
        /// 번호는 숫자만 남기고 중복은 지운다 — 같은 사람에게 두 번 가면 안 된다.
        /// </summary>
        static List<string> List(Dictionary<string, object> a, string key)
        {
            var raw = new List<string>();

            object v;
            if (a.TryGetValue(key, out v) && v != null)
            {
                var arr = v as System.Collections.IEnumerable;
                if (arr != null && !(v is string)) foreach (var one in arr) raw.Add(Convert.ToString(one));
                else                               raw.AddRange(v.ToString().Split(',', ';', '\n'));
            }

            var outp = new List<string>();
            foreach (var one in raw)
            {
                var digits = new System.Text.StringBuilder();
                foreach (var ch in one ?? "") if (char.IsDigit(ch)) digits.Append(ch);

                string d = digits.ToString();
                if (d.Length > 0 && !outp.Contains(d)) outp.Add(d);
            }
            return outp;
        }

        static short Short(Dictionary<string, object> a, string key, short fallback)
        {
            object v;
            if (!a.TryGetValue(key, out v) || v == null) return fallback;

            short n;
            return short.TryParse(v.ToString(), out n) ? n : fallback;
        }

        /// <summary>화면에 돌려줄 한 건. data 는 JSON 문자열로 넘어간다.</summary>
        public class Result
        {
            public bool   Success;
            public string Message = "";
            public string Data    = "";

            public static Result Ok(object payload)
            {
                return new Result { Success = true, Data = payload == null ? "" : Json.Serialize(payload) };
            }

            public static Result Fail(string why)
            {
                return new Result { Success = false, Message = why };
            }
        }
    }
}
