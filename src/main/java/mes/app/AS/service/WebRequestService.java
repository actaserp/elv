package mes.app.AS.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class WebRequestService {

    @Autowired
    SqlRunner sqlRunner; // 사업체DB (@Primary = tenantSqlRunner)

    @Autowired
    NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    // ── 카운트 (금일수신/고장접수/콜백/당일처리) ─────────────
    public Map<String, Object> getCount(String spjangcd) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("today", java.time.LocalDate.now().toString().replace("-", ""));

        String sql = """
                SELECT
                    COUNT(*)                                                 AS callcount,
                    SUM(CASE WHEN resultck IS NULL THEN 1 ELSE 0 END)       AS rececnt,
                    0                                                        AS callback,
                    SUM(CASE WHEN resultck = '1'   THEN 1 ELSE 0 END)       AS compcnt
                FROM TB_E401
                WHERE spjangcd = :spjangcd
                  AND recedate = :today
                """;

        return sqlRunner.getRow(sql, param);
    }

    // ── 고장접수현황 카드 리스트 (TB_E401) ───────────────────
    public List<Map<String, Object>> getList(
            String spjangcd, String fromDate, String toDate, String actnm) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("fromDate", fromDate);
        param.addValue("toDate",   toDate);

        String sql = """
                SELECT
                    e.recedate,
                    e.recenum,
                    e.recetime,
                    e.hitchdate,
                    e.hitchhour,
                    e.actcd,
                    e.actnm,
                    e.equpcd,
                    e.equpnm,
                    e.reperid,
                    j2.pernm     AS repernm,
                    e.perid,
                    j.pernm,
                    e.contcd,
                    c.contnm,
                    e.contents,
                    e.remark,
                    e.resultck   AS status
                FROM TB_E401 e
                LEFT JOIN TB_JA001 j  ON j.perid    = 'p' + e.perid
                                     AND j.spjangcd  = e.spjangcd
                LEFT JOIN TB_JA001 j2 ON j2.perid   = 'p' + e.reperid
                                     AND j2.spjangcd = e.spjangcd
                LEFT JOIN TB_E010  c  ON c.contcd   = e.contcd
                WHERE e.spjangcd = :spjangcd
                  AND e.recedate BETWEEN :fromDate AND :toDate
                """;

        if (actnm != null && !actnm.isBlank()) {
            sql += " AND e.actnm LIKE :actnm";
            param.addValue("actnm", "%" + actnm.trim() + "%");
        }

        sql += " ORDER BY e.recedate DESC, e.recenum DESC";

        return sqlRunner.getRows(sql, param);
    }

    // ── 고장접수 저장 (TB_E401 INSERT / UPDATE) ───────────────
    public void save(String spjangcd, String custcd,
                     String recedate, String recenum, String recetime,
                     String hitchdate, String hitchhour,
                     String actcd, String actnm,
                     String equpcd, String equpnm,
                     String reperid, String perid,
                     String contcd, String contents, String remark) {

        String today = java.time.LocalDate.now().toString().replace("-", "");

        if (recenum == null || recenum.isBlank()) {
            // 신규 INSERT - recenum 채번
            recenum = getNextRecenum(spjangcd, recedate);

            MapSqlParameterSource param = new MapSqlParameterSource();
            param.addValue("custcd",    custcd);
            param.addValue("spjangcd",  spjangcd);
            param.addValue("recedate",  recedate);
            param.addValue("recenum",   recenum);
            param.addValue("recetime",  recetime);
            param.addValue("hitchdate", hitchdate);
            param.addValue("hitchhour", hitchhour);
            param.addValue("actcd",     actcd);
            param.addValue("actnm",     actnm);
            param.addValue("equpcd",    equpcd);
            param.addValue("equpnm",    equpnm);
            param.addValue("reperid",   reperid);
            param.addValue("perid",     perid);
            param.addValue("contcd",    contcd);
            param.addValue("contents",  contents);
            param.addValue("remark",    remark);
            param.addValue("inperid",   perid);
            param.addValue("indate",    today);

            String sql = """
                    INSERT INTO TB_E401
                        (custcd, spjangcd, recedate, recenum, recetime,
                         hitchdate, hitchhour,
                         actcd, actnm, equpcd, equpnm,
                         reperid, perid,
                         contcd, contents, remark,
                         inperid, indate)
                    VALUES
                        (:custcd, :spjangcd, :recedate, :recenum, :recetime,
                         :hitchdate, :hitchhour,
                         :actcd, :actnm, :equpcd, :equpnm,
                         :reperid, :perid,
                         :contcd, :contents, :remark,
                         :inperid, :indate)
                    """;

            namedParameterJdbcTemplate.update(sql, param);

        } else {
            // 수정 UPDATE
            MapSqlParameterSource param = new MapSqlParameterSource();
            param.addValue("spjangcd",  spjangcd);
            param.addValue("recedate",  recedate);
            param.addValue("recenum",   recenum);
            param.addValue("recetime",  recetime);
            param.addValue("hitchdate", hitchdate);
            param.addValue("hitchhour", hitchhour);
            param.addValue("actcd",     actcd);
            param.addValue("actnm",     actnm);
            param.addValue("equpcd",    equpcd);
            param.addValue("equpnm",    equpnm);
            param.addValue("reperid",   reperid);
            param.addValue("perid",     perid);
            param.addValue("contcd",    contcd);
            param.addValue("contents",  contents);
            param.addValue("remark",    remark);

            String sql = """
                    UPDATE TB_E401 SET
                        recetime  = :recetime,
                        hitchdate = :hitchdate,
                        hitchhour = :hitchhour,
                        actcd     = :actcd,
                        actnm     = :actnm,
                        equpcd    = :equpcd,
                        equpnm    = :equpnm,
                        reperid   = :reperid,
                        perid     = :perid,
                        contcd    = :contcd,
                        contents  = :contents,
                        remark    = :remark
                    WHERE spjangcd = :spjangcd
                      AND recedate = :recedate
                      AND recenum  = :recenum
                    """;

            namedParameterJdbcTemplate.update(sql, param);
        }
    }

    // ── 고장접수 삭제 (TB_E401 DELETE) ───────────────────────
    public void delete(String spjangcd, String recedate, String recenum) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("recedate", recedate);
        param.addValue("recenum",  recenum);

        namedParameterJdbcTemplate.update("""
                DELETE FROM TB_E401
                WHERE spjangcd = :spjangcd
                  AND recedate = :recedate
                  AND recenum  = :recenum
                """, param);
    }

    // ── 문자전송내역 조회 (TB_E401_SMS) ─────────────────────
    public List<Map<String, Object>> getSmsHistory(
            String spjangcd, String recedate, String recenum) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("recedate", recedate);
        param.addValue("recenum",  recenum);

        String sql = """
                SELECT
                    s.result,
                    s.recedate,
                    CONVERT(varchar(6), s.receipdate, 108) AS recetime,
                    s.pernm,
                    s.sms_tel,
                    s.flag,
                    s.sms_text
                FROM TB_E401_SMS s
                WHERE s.spjangcd = :spjangcd
                  AND s.recedate = :recedate
                  AND s.recenum  = :recenum
                ORDER BY s.receipdate DESC
                """;

        return sqlRunner.getRows(sql, param);
    }

    // ── 통화메모 목록 조회 (TB_CALLMAIN) ────────────────────
    public List<Map<String, Object>> getMemoList(
            String spjangcd, String srchDate, String callnm) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("srchDate", srchDate);
        param.addValue("callnm",   callnm != null ? callnm : "%");

        String sql = """
                SELECT
                    seq,
                    calldate,
                    calltime,
                    callnm,
                    callnum,
                    callmemo,
                    callbackflag,
                    callbacktime,
                    callbackmemo,
                    callendmemo
                FROM TB_CALLMAIN
                WHERE calldate = :srchDate
                  AND callnm   LIKE :callnm
                ORDER BY calldate DESC, calltime DESC
                """;

        return sqlRunner.getRows(sql, param);
    }

    // ── 통화메모 저장 (TB_CALLMAIN INSERT / UPDATE) ──────────
    public void saveMemo(String spjangcd, String seq,
                         String calldate, String calltime,
                         String callnm, String callnum,
                         String callbackflag, String callbacktime, String callbackmemo,
                         String callmemo, String callendmemo, String pernm) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("seq",          seq);
        param.addValue("calldate",     calldate);
        param.addValue("calltime",     calltime);
        param.addValue("callnm",       callnm);
        param.addValue("callnum",      callnum);
        param.addValue("callbackflag", callbackflag);
        param.addValue("callbacktime", callbacktime);
        param.addValue("callbackmemo", callbackmemo);
        param.addValue("callmemo",     callmemo);
        param.addValue("callendmemo",  callendmemo);
        param.addValue("pernm",        pernm);
        param.addValue("regdate",      java.time.LocalDate.now().toString().replace("-", ""));

        if (seq == null || seq.isBlank()) {
            // 신규 INSERT - seq 채번 (yyyymm + 순번)
            String newSeq = getNextCallSeq(calldate.substring(0, 6));
            param.addValue("seq", newSeq);

            namedParameterJdbcTemplate.update("""
                    INSERT INTO TB_CALLMAIN
                        (seq, calldate, calltime, callnm, callnum,
                         callbackflag, callbacktime, callbackmemo,
                         callmemo, callendmemo, pernm, regdate)
                    VALUES
                        (:seq, :calldate, :calltime, :callnm, :callnum,
                         :callbackflag, :callbacktime, :callbackmemo,
                         :callmemo, :callendmemo, :pernm, :regdate)
                    """, param);
        } else {
            namedParameterJdbcTemplate.update("""
                    UPDATE TB_CALLMAIN SET
                        calldate     = :calldate,
                        calltime     = :calltime,
                        callnm       = :callnm,
                        callnum      = :callnum,
                        callbackflag = :callbackflag,
                        callbacktime = :callbacktime,
                        callbackmemo = :callbackmemo,
                        callmemo     = :callmemo,
                        callendmemo  = :callendmemo
                    WHERE seq = :seq
                    """, param);
        }
    }

    // ── 통화메모 삭제 (TB_CALLMAIN DELETE) ───────────────────
    public void deleteMemo(String spjangcd, String seq) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("seq", seq);

        namedParameterJdbcTemplate.update("""
                DELETE FROM TB_CALLMAIN
                WHERE seq = :seq
                """, param);
    }

    // ── seq 채번 (yyyymm 기준 MAX + 1) ───────────────────────
    private String getNextCallSeq(String yyyymm) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("yyyymm", yyyymm);

        String sql = """
                SELECT ISNULL(MAX(CAST(seq AS BIGINT)), 0) + 1
                FROM TB_CALLMAIN
                WHERE LEFT(seq, 6) = :yyyymm
                """;

        Long next = namedParameterJdbcTemplate.queryForObject(sql, param, Long.class);
        if (next == null) next = Long.parseLong(yyyymm + "0001");
        return String.valueOf(next);
    }

    // ── 팝업: 현장 검색 (TB_E601) ────────────────────────────
    public List<Map<String, Object>> popupActnm(String spjangcd, String actnm) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("actnm", (actnm != null && !actnm.isBlank()) ? "%" + actnm + "%" : "%");

        String sql = """
                SELECT actcd, actnm
                FROM TB_E601
                WHERE spjangcd = :spjangcd
                  AND actnm    LIKE :actnm
                ORDER BY actnm ASC
                """;

        return sqlRunner.getRows(sql, param);
    }

    // ── 팝업: 호기 검색 (TB_E611) ────────────────────────────
    public List<Map<String, Object>> popupEqupnm(String spjangcd, String actcd) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("actcd",    actcd);

        String sql = """
                SELECT equpcd, equpnm
                FROM TB_E611 WITH(NOLOCK)
                WHERE spjangcd = :spjangcd
                  AND actcd    = :actcd
                ORDER BY equpcd ASC
                """;

        return sqlRunner.getRows(sql, param);
    }

    // ── 팝업: 사원 검색 (TB_JA001 - 접수자/통보자 공통) ──────
    public List<Map<String, Object>> popupPernm(String spjangcd, String pernm) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("pernm", (pernm != null && !pernm.isBlank() && !pernm.equals("%"))
                ? "%" + pernm + "%" : "%");

        String sql = """
                SELECT perid, pernm, handphone
                FROM TB_JA001
                WHERE spjangcd = :spjangcd
                  AND pernm    LIKE :pernm
                ORDER BY pernm ASC
                """;

        return sqlRunner.getRows(sql, param);
    }

    // ── 팝업: 고장내용 검색 (TB_E010) ────────────────────────
    public List<Map<String, Object>> popupContnm(String contnm) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("contnm", (contnm != null && !contnm.isBlank() && !contnm.equals("%"))
                ? "%" + contnm + "%" : "%");

        String sql = """
                SELECT contcd, contnm
                FROM TB_E010
                WHERE contnm LIKE :contnm
                ORDER BY contcd ASC
                """;

        return sqlRunner.getRows(sql, param);
    }

    // ── PushID 조회 (TB_JA001) ────────────────────────────────
    public String getPushId(String spjangcd, String pernm) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("pernm",    pernm);

        String sql = """
                SELECT TOP 1 pushid
                FROM TB_JA001
                WHERE spjangcd = :spjangcd
                  AND pernm    = :pernm
                """;

        Map<String, Object> row = sqlRunner.getRow(sql, param);
        return (row != null) ? (String) row.get("pushid") : null;
    }

    // ── recenum 채번 ──────────────────────────────────────────
    private String getNextRecenum(String spjangcd, String recedate) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("recedate", recedate);

        String sql = """
                SELECT ISNULL(MAX(CAST(recenum AS INT)), 0) + 1
                FROM TB_E401
                WHERE spjangcd = :spjangcd
                  AND recedate = :recedate
                """;

        Integer next = namedParameterJdbcTemplate.queryForObject(sql, param, Integer.class);
        if (next == null) next = 1;
        return String.format("%03d", next);
    }
}
