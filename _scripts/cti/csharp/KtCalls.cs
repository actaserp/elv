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
            foreach (var one in Each(raw))
            {
                var g = one as KTDAddressGroup;
                if (g == null) continue;
                rows.Add(new Dictionary<string, object> {
                    { "dbid",  g.DBID  }, { "name", g.Name },
                    { "pdbid", g.PDBID }, { "type", g.Type },
                });
            }
            return Result.Ok(rows);
        }

        static Result AddressDataList(KTPCBizXClass kt, Dictionary<string, object> a)
        {
            var raw = kt.GetAddressDataList(Short(a, "type", 2), Str(a, "pdbid"));

            var rows = new List<object>();
            foreach (var one in Each(raw))
            {
                var d = one as KTDAddressData;
                if (d == null) continue;
                rows.Add(ToMap(d));
            }
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
