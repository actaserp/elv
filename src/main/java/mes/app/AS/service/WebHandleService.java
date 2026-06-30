package mes.app.AS.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class WebHandleService {

    @Autowired
    SqlRunner sqlRunner;

    @Autowired
    NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    // ── 고장접수 목록 조회 (TB_E401) ─────────────────────────
    public List<Map<String, Object>> getRequestList(
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
                    e.actcd,
                    e.actnm,
                    e.equpcd,
                    e.equpnm,
                    e.contcd,
                    ct.contnm,
                    e.contents,
                    e.remark,
                    e.resultck
                FROM TB_E401 e
                LEFT JOIN TB_E010 ct ON ct.contcd  = e.contcd
                                    AND ct.spjangcd = e.spjangcd
                WHERE e.spjangcd = :spjangcd
                  AND e.recedate BETWEEN :fromDate AND :toDate
                """;

        if (actnm != null && !actnm.isBlank()) {
            sql += " AND e.actnm LIKE :actnm";
            param.addValue("actnm", "%" + actnm.trim() + "%");
        }

        sql += " ORDER BY e.recedate DESC, e.recenum DESC";
        return this.sqlRunner.getRows(sql, param);
    }

    // ── 고장처리 단건 조회 (recedate+recenum) ─────────────────
    public Map<String, Object> getCompByReceive(String spjangcd, String recedate, String recenum) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("recedate", recedate);
        param.addValue("recenum",  recenum);

        String sql = """
                SELECT
                    e.compdate, e.compnum, e.comptime,
                    e.recedate, e.recenum, e.recetime,
                    e.arrivdate, e.arrivtime,
                    e.actcd, e.actnm, e.equpcd, e.equpnm,
                    e.gregicd, e.contremark,
                    e.regicd,
                    eg.reginm,
                    e.remocd,
                    em.remonm,
                    e.faccd,
                    f19.facnm,
                    e.remoremark,
                    e.resucd,
                    es.resunm,
                    e.resuremark,
                    e.resultcd,
                    er.resultnm,
                    e.customer,
                    e.remark,
                    e.actperid,
                    ap.pernm AS actpernm,
                    e.filesvnm,
                    e.filepath
                FROM TB_E411 e
                LEFT JOIN TB_JA001 ap ON ap.perid    = e.actperid
                                     AND ap.spjangcd = e.spjangcd
                LEFT JOIN TB_E014 eg  ON eg.regicd   = e.regicd
                LEFT JOIN TB_E011 em  ON em.remocd   = e.remocd
                LEFT JOIN TB_E019 f19 ON f19.faccd   = e.faccd
                LEFT JOIN TB_E012 es  ON es.resucd   = e.resucd
                LEFT JOIN TB_E015 er  ON er.resultcd = e.resultcd
                WHERE e.spjangcd = :spjangcd
                  AND e.recedate = :recedate
                  AND e.recenum  = :recenum
                """;

        List<Map<String, Object>> rows = this.sqlRunner.getRows(sql, param);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── 고장처리 목록 조회 (TB_E411) ─────────────────────────
    public List<Map<String, Object>> getCompList(
            String spjangcd, String fromDate, String toDate, String actnm) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("fromDate", fromDate);
        param.addValue("toDate",   toDate);

        String sql = """
                SELECT
                    e.compdate,
                    e.compnum,
                    e.comptime,
                    e.recedate,
                    e.recenum,
                    e.actcd,
                    e.actnm,
                    e.equpcd,
                    e.equpnm,
                    e.contremark,
                    e.remoremark,
                    e.resuremark,
                    e.customer,
                    e.remark,
                    e.actperid,
                    ap.pernm AS actpernm
                FROM TB_E411 e
                LEFT JOIN TB_JA001 ap ON ap.perid    = e.actperid
                                     AND ap.spjangcd = e.spjangcd
                WHERE e.spjangcd = :spjangcd
                  AND e.recedate BETWEEN :fromDate AND :toDate
                """;

        if (actnm != null && !actnm.isBlank()) {
            sql += " AND e.actnm LIKE :actnm";
            param.addValue("actnm", "%" + actnm.trim() + "%");
        }

        sql += " ORDER BY e.recedate DESC, e.recenum DESC";
        return this.sqlRunner.getRows(sql, param);
    }

    // ── 고장처리결과 등록 (TB_E411 INSERT) ───────────────────
    public void saveComp(
            String custcd, String spjangcd, String compdate, String comptime,
            String recedate, String recenum, String recetime,
            String arrivdate, String arrivtime,
            String actcd, String actnm, String equpcd, String equpnm,
            String contremark, String gregicd, String regicd,
            String remocd, String faccd, String remoremark,
            String resucd, String resuremark, String resultcd,
            String remark, String customer, String perid,
            String filesvnm, String filepath) {

        String compnum = getNextCompnum(spjangcd, compdate);

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd",     custcd);
        param.addValue("spjangcd",   spjangcd);
        param.addValue("compdate",   compdate);
        param.addValue("compnum",    compnum);
        param.addValue("comptime",   comptime);
        param.addValue("recedate",   recedate);
        param.addValue("recenum",    recenum);
        param.addValue("recetime",   recetime);
        param.addValue("arrivdate",  arrivdate);
        param.addValue("arrivtime",  arrivtime);
        param.addValue("actcd",      actcd);
        param.addValue("actnm",      actnm);
        param.addValue("equpcd",     equpcd);
        param.addValue("equpnm",     equpnm);
        param.addValue("contremark", contremark);
        param.addValue("gregicd",    gregicd);
        param.addValue("regicd",     regicd);
        param.addValue("remocd",     remocd);
        param.addValue("faccd",      faccd);
        param.addValue("remoremark", remoremark);
        param.addValue("resucd",     resucd);
        param.addValue("resuremark", resuremark);
        param.addValue("resultcd",   resultcd);
        param.addValue("remark",     remark);
        param.addValue("customer",   customer);
        param.addValue("result",     "1");
        param.addValue("actperid",   perid != null ? perid : "");
        param.addValue("perid",      perid != null ? perid : "");
        param.addValue("inperid",    perid != null ? perid : "");
        param.addValue("indate",     compdate);
        param.addValue("filesvnm",   filesvnm != null ? filesvnm : "");
        param.addValue("filepath",   filepath  != null ? filepath  : "");

        namedParameterJdbcTemplate.update("""
                INSERT INTO TB_E411
                    (custcd, spjangcd, compdate, compnum, comptime,
                     recedate, recenum, recetime, arrivdate, arrivtime,
                     actcd, actnm, equpcd, equpnm,
                     contremark, gregicd, regicd,
                     remocd, faccd, remoremark,
                     resucd, resuremark, resultcd,
                     remark, customer, result,
                     actperid, perid, inperid, indate,
                     filesvnm, filepath)
                VALUES
                    (:custcd, :spjangcd, :compdate, :compnum, :comptime,
                     :recedate, :recenum, :recetime, :arrivdate, :arrivtime,
                     :actcd, :actnm, :equpcd, :equpnm,
                     :contremark, :gregicd, :regicd,
                     :remocd, :faccd, :remoremark,
                     :resucd, :resuremark, :resultcd,
                     :remark, :customer, :result,
                     :actperid, :perid, :inperid, :indate,
                     :filesvnm, :filepath)
                """, param);

        // ── TB_E401 처리완료 상태 업데이트 ──────────────────
        if (recedate != null && !recedate.isBlank() && recenum != null && !recenum.isBlank()) {
            MapSqlParameterSource updateParam = new MapSqlParameterSource();
            updateParam.addValue("spjangcd", spjangcd);
            updateParam.addValue("recedate",  recedate);
            updateParam.addValue("recenum",   recenum);
            namedParameterJdbcTemplate.update("""
                    UPDATE TB_E401 SET resultck = '1'
                    WHERE spjangcd = :spjangcd
                      AND recedate = :recedate
                      AND recenum  = :recenum
                    """, updateParam);
        }

        // ── TB_E037 HEAD MERGE + TB_E038 업무일지 자동 등록 ──
        MapSqlParameterSource headParam = new MapSqlParameterSource();
        headParam.addValue("custcd",   custcd);
        headParam.addValue("spjangcd", spjangcd);
        headParam.addValue("rptdate",  compdate);
        headParam.addValue("perid",    perid);  // actperid = 처리자 기준

        namedParameterJdbcTemplate.update("""
                MERGE INTO TB_E037 AS target
                USING (SELECT :custcd AS custcd, :spjangcd AS spjangcd,
                              :rptdate AS rptdate, :perid AS perid) AS source
                ON (    target.custcd   = source.custcd
                    AND target.spjangcd = source.spjangcd
                    AND target.rptdate  = source.rptdate
                    AND target.perid    = source.perid)
                WHEN NOT MATCHED THEN
                    INSERT (custcd, spjangcd, rptdate, perid)
                    VALUES (:custcd, :spjangcd, :rptdate, :perid);
                """, headParam);

        Integer nextRpt = namedParameterJdbcTemplate.queryForObject("""
                SELECT ISNULL(MAX(CAST(rptnum AS INT)), 0) + 1
                FROM TB_E038
                WHERE custcd   = :custcd
                  AND spjangcd = :spjangcd
                  AND rptdate  = :rptdate
                  AND perid    = :perid
                """, headParam, Integer.class);
        String rptnum = String.format("%03d", nextRpt != null ? nextRpt : 1);

        MapSqlParameterSource detailParam = new MapSqlParameterSource();
        detailParam.addValue("custcd",   custcd);
        detailParam.addValue("spjangcd", spjangcd);
        detailParam.addValue("rptdate",  compdate);
        detailParam.addValue("perid",    perid);
        detailParam.addValue("rptnum",   rptnum);
        detailParam.addValue("actcd",    actcd);
        detailParam.addValue("actnm",    actnm);
        detailParam.addValue("equpcd",   equpcd != null ? equpcd : "");
        detailParam.addValue("wkcd",     "");
        detailParam.addValue("frtime",   comptime != null ? comptime : "");
        detailParam.addValue("totime",   comptime != null ? comptime : "");
        detailParam.addValue("remark",   customer != null ? customer : "");
        detailParam.addValue("filesvnm", filesvnm != null ? filesvnm : "");
        detailParam.addValue("filepath", filepath  != null ? filepath  : "");

        namedParameterJdbcTemplate.update("""
                INSERT INTO TB_E038
                    (custcd, spjangcd, rptdate, perid, rptnum,
                     actcd, actnm, equpcd, wkcd, frtime, totime, remark,
                     filesvnm, filepath)
                VALUES
                    (:custcd, :spjangcd, :rptdate, :perid, :rptnum,
                     :actcd, :actnm, :equpcd, :wkcd, :frtime, :totime, :remark,
                     :filesvnm, :filepath)
                """, detailParam);
    }

    // ── 고장처리결과 수정 (TB_E411 UPDATE) ───────────────────
    public void updateComp(
            String spjangcd, String compdate, String compnum, String comptime,
            String recedate, String recenum, String recetime,
            String arrivdate, String arrivtime,
            String actcd, String actnm, String equpcd, String equpnm,
            String contremark, String gregicd, String regicd,
            String remocd, String faccd, String remoremark,
            String resucd, String resuremark, String resultcd,
            String remark, String customer, String perid) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd",   spjangcd);
        param.addValue("compdate",   compdate);
        param.addValue("compnum",    compnum);
        param.addValue("comptime",   comptime);
        param.addValue("recedate",   recedate);
        param.addValue("recenum",    recenum);
        param.addValue("recetime",   recetime);
        param.addValue("arrivdate",  arrivdate);
        param.addValue("arrivtime",  arrivtime);
        param.addValue("actcd",      actcd);
        param.addValue("actnm",      actnm);
        param.addValue("equpcd",     equpcd);
        param.addValue("equpnm",     equpnm);
        param.addValue("contremark", contremark);
        param.addValue("gregicd",    gregicd);
        param.addValue("regicd",     regicd);
        param.addValue("remocd",     remocd);
        param.addValue("faccd",      faccd);
        param.addValue("remoremark", remoremark);
        param.addValue("resucd",     resucd);
        param.addValue("resuremark", resuremark);
        param.addValue("resultcd",   resultcd);
        param.addValue("remark",     remark);
        param.addValue("customer",   customer);
        param.addValue("actperid",   perid);

        namedParameterJdbcTemplate.update("""
                UPDATE TB_E411 SET
                    comptime   = :comptime,
                    recedate   = :recedate,
                    recenum    = :recenum,
                    recetime   = :recetime,
                    arrivdate  = :arrivdate,
                    arrivtime  = :arrivtime,
                    actcd      = :actcd,
                    actnm      = :actnm,
                    equpcd     = :equpcd,
                    equpnm     = :equpnm,
                    contremark = :contremark,
                    gregicd    = :gregicd,
                    regicd     = :regicd,
                    remocd     = :remocd,
                    faccd      = :faccd,
                    remoremark = :remoremark,
                    resucd     = :resucd,
                    resuremark = :resuremark,
                    resultcd   = :resultcd,
                    remark     = :remark,
                    customer   = :customer,
                    actperid   = :actperid
                WHERE spjangcd = :spjangcd
                  AND compdate = :compdate
                  AND compnum  = :compnum
                """, param);
    }

    // ── 고장처리결과 삭제 (TB_E411 DELETE) ───────────────────
    public void deleteComp(String spjangcd, String compdate, String compnum) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("compdate", compdate);
        param.addValue("compnum",  compnum);

        // 삭제 전 recedate, recenum 조회 (TB_E401 상태 복원용)
        Map<String, Object> row = namedParameterJdbcTemplate.queryForList("""
                SELECT recedate, recenum FROM TB_E411
                WHERE spjangcd = :spjangcd
                  AND compdate = :compdate
                  AND compnum  = :compnum
                """, param).stream().findFirst().orElse(null);

        namedParameterJdbcTemplate.update("""
                DELETE FROM TB_E411
                WHERE spjangcd = :spjangcd
                  AND compdate = :compdate
                  AND compnum  = :compnum
                """, param);

        // TB_E401 resultck 처리전으로 복원
        if (row != null) {
            String recedate = row.get("recedate") != null ? row.get("recedate").toString() : null;
            String recenum  = row.get("recenum")  != null ? row.get("recenum").toString()  : null;
            if (recedate != null && recenum != null) {
                MapSqlParameterSource updateParam = new MapSqlParameterSource();
                updateParam.addValue("spjangcd", spjangcd);
                updateParam.addValue("recedate",  recedate);
                updateParam.addValue("recenum",   recenum);
                namedParameterJdbcTemplate.update("""
                        UPDATE TB_E401
                        SET resultck = NULL
                        WHERE spjangcd = :spjangcd
                          AND recedate = :recedate
                          AND recenum  = :recenum
                        """, updateParam);
            }
        }
    }

    // ── 팝업: 현장 검색 (TB_E601) ────────────────────────────
    public List<Map<String, Object>> popupActnm(String spjangcd, String actnm) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT actcd, actnm
                FROM TB_E601
                WHERE spjangcd = :spjangcd
                """;

        if (actnm != null && !actnm.isBlank()) {
            sql += " AND actnm LIKE :actnm";
            param.addValue("actnm", "%" + actnm.trim() + "%");
        }

        sql += " ORDER BY actnm ASC";
        return this.sqlRunner.getRows(sql, param);
    }

    // ── 팝업: 호기 검색 (TB_E611) ────────────────────────────
    public List<Map<String, Object>> popupEqupnm(String spjangcd, String actcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("actcd",    actcd);

        String sql = """
                SELECT equpcd, equpnm, actcd
                FROM TB_E611
                WHERE spjangcd = :spjangcd
                  AND actcd    = :actcd
                ORDER BY equpcd ASC
                """;

        return this.sqlRunner.getRows(sql, param);
    }

    // ── 팝업: 사원 검색 (TB_JA001, 처리자) ───────────────────
    public List<Map<String, Object>> popupPernm(String spjangcd, String pernm) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT j.perid, j.pernm, jc.divinm
                FROM TB_JA001 j
                LEFT JOIN TB_JC002 jc ON j.divicd   = jc.divicd
                                     AND j.spjangcd  = jc.spjangcd
                WHERE j.spjangcd = :spjangcd
                  AND j.retiredate IS NULL
                """;

        if (pernm != null && !pernm.isBlank()) {
            sql += " AND j.pernm LIKE :pernm";
            param.addValue("pernm", "%" + pernm.trim() + "%");
        }

        sql += " ORDER BY j.pernm ASC";
        return this.sqlRunner.getRows(sql, param);
    }

    // ── compnum 채번 ──────────────────────────────────────────
    private String getNextCompnum(String spjangcd, String compdate) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("compdate", compdate);

        String sql = """
                SELECT ISNULL(MAX(CAST(compnum AS INT)), 0) + 1
                FROM TB_E411
                WHERE spjangcd = :spjangcd
                  AND compdate = :compdate
                """;

        Integer next = namedParameterJdbcTemplate.queryForObject(sql, param, Integer.class);
        if (next == null) next = 1;
        return String.format("%03d", next);
    }
}
