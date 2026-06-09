package mes.app.mobile.Service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class MaintenanceRepairService {

    @Autowired
    SqlRunner sqlRunner;

    @Autowired
    NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    // ── 사용자 정보 조회 ───────────────────────────────────────
    public Map<String, Object> getUserInfo(String username) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("username", username);

        String sql = """
                SELECT TOP 1
                    a.username,
                    a.first_name,
                    p.id,
                    an.restnum,
                    t.sttime
                FROM auth_user a
                LEFT JOIN tb_pb209 an ON an.perid = a.personid
                LEFT JOIN person p ON p.id = a.personid
                LEFT JOIN tb_pbcont t ON t.flag = RIGHT('0' + CAST(p.PersonGroup_id AS VARCHAR), 2)
                WHERE a.username = :username
                ORDER BY an.todate DESC
                """;

        return this.sqlRunner.getRow(sql, param);
    }

    // ── 고장접수 목록 조회 (TB_E401) ─────────────────────────
    public List<Map<String, Object>> getRepairList(
            String fromDate, String toDate, String actnm, String spjangcd, String perid) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("fromDate", fromDate);
        param.addValue("toDate",   toDate);
        param.addValue("perid",    perid);

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
                LEFT JOIN TB_E010 ct ON ct.contcd   = e.contcd
                                    AND ct.spjangcd  = e.spjangcd
                WHERE e.spjangcd = :spjangcd
                  AND e.recedate BETWEEN :fromDate AND :toDate
                  AND e.reperid  = :perid
                """;

        if (actnm != null && !actnm.isBlank()) {
            sql += " AND e.actnm LIKE :actnm";
            param.addValue("actnm", "%" + actnm.trim() + "%");
        }

        sql += " ORDER BY e.recedate DESC, e.recenum DESC";
        return this.sqlRunner.getRows(sql, param);
    }

    // ── 고장처리결과 목록 조회 (TB_E411) ─────────────────────
    public List<Map<String, Object>> getCompList(
            String fromDate, String toDate, String actnm, String resultck, String spjangcd) {

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
                    e.recetime,
                    e.arrivdate,
                    e.arrivtime,
                    e.actcd,
                    e.actnm,
                    e.equpcd,
                    e.equpnm,
                    e.gregicd,
                    e.contremark,
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
                    e.perid,
                    e.actperid,
                    j.pernm,
                    ap.pernm    AS actpernm,
                    jc.divinm,
                    a.resultck
                FROM TB_E411 e
                LEFT JOIN TB_JA001 j   ON j.perid    = 'p' + e.perid
                                      AND j.spjangcd  = e.spjangcd
                LEFT JOIN TB_JA001 ap  ON ap.perid    = e.actperid
                                      AND ap.spjangcd = e.spjangcd
                LEFT JOIN TB_JC002 jc  ON j.divicd   = jc.divicd
                LEFT JOIN TB_E019 f19  ON f19.faccd   = e.faccd
                LEFT JOIN TB_E014 eg   ON eg.regicd   = e.regicd
                LEFT JOIN TB_E011 em   ON em.remocd   = e.remocd
                LEFT JOIN TB_E012 es   ON es.resucd   = e.resucd
                LEFT JOIN TB_E015 er   ON er.resultcd = e.resultcd
                LEFT JOIN TB_E401  a   ON a.recedate  = e.recedate
                                      AND a.recenum   = e.recenum
                                      AND a.spjangcd  = e.spjangcd
                WHERE e.spjangcd = :spjangcd
                  AND e.compdate BETWEEN :fromDate AND :toDate
                """;

        if (actnm != null && !actnm.isBlank()) {
            sql += " AND e.actnm LIKE :actnm";
            param.addValue("actnm", "%" + actnm.trim() + "%");
        }

        if (resultck != null && !resultck.isBlank()) {
            if (resultck.equals("1")) {
                sql += " AND a.resultck = '1'";
            } else if (resultck.equals("null")) {
                sql += " AND (a.resultck IS NULL OR a.resultck <> '1')";
            }
        }

        sql += " ORDER BY e.compdate DESC, e.compnum DESC";
        return this.sqlRunner.getRows(sql, param);
    }

    // ── 현장 목록 조회 (TB_E601) ─────────────────────────────
    public List<Map<String, Object>> getSiteList(String spjangcd, String keyword) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT actcd, actnm
                FROM TB_E601
                WHERE spjangcd = :spjangcd
                """;

        if (keyword != null && !keyword.trim().isEmpty()) {
            sql += " AND actnm LIKE :keyword";
            param.addValue("keyword", "%" + keyword.trim() + "%");
        }

        sql += " ORDER BY actnm ASC";
        return this.sqlRunner.getRows(sql, param);
    }

    // ── 호기 목록 조회 (TB_E611) ─────────────────────────────
    public List<Map<String, Object>> getEqupList(String spjangcd, String actcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("actcd",    actcd);

        String sql = """
                SELECT equpcd, equpnm, actcd
                FROM TB_E611 WITH(NOLOCK)
                WHERE spjangcd = :spjangcd
                  AND actcd    = :actcd
                ORDER BY equpcd ASC
                """;

        return this.sqlRunner.getRows(sql, param);
    }

    // ── 고장처리결과 등록 (TB_E411 INSERT) ───────────────────
    public void saveComp(
            String custcd,
            String spjangcd,
            String compdate,
            String comptime,
            String recedate,
            String recenum,
            String recetime,
            String arrivdate,
            String arrivtime,
            String actcd,
            String actnm,
            String equpcd,
            String equpnm,
            String contremark,
            String gregicd,
            String remoremark,
            String regicd,
            String resuremark,
            String remocd,
            String resultcd,
            String faccd,
            String customer,
            String resucd,
            String remark,
            String actperid,
            String perid,
            String filesvnm,
            String filepath) {

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
        param.addValue("remoremark", remoremark);
        param.addValue("regicd",     regicd);
        param.addValue("resuremark", resuremark);
        param.addValue("remocd",     remocd);
        param.addValue("resultcd",   resultcd);
        param.addValue("faccd",      faccd);
        param.addValue("customer",   customer);
        param.addValue("resucd",     resucd);
        param.addValue("remark",     remark);
        param.addValue("actperid",   actperid);
        param.addValue("result",     "1");
        param.addValue("perid",      perid);
        param.addValue("inperid",    perid);
        param.addValue("indate",     compdate);
        param.addValue("filesvnm",   filesvnm != null ? filesvnm : "");
        param.addValue("filepath",   filepath  != null ? filepath  : "");

        namedParameterJdbcTemplate.update("""
                INSERT INTO TB_E411
                    (custcd, spjangcd, compdate, compnum, comptime,
                     recedate, recenum, recetime,
                     arrivdate, arrivtime,
                     actcd, actnm, equpcd, equpnm,
                     contremark, gregicd,
                     remoremark, regicd,
                     resuremark, remocd,
                     resultcd, faccd,
                     customer, resucd,
                     remark, actperid, result,
                     perid, inperid, indate,
                     filesvnm, filepath)
                VALUES
                    (:custcd, :spjangcd, :compdate, :compnum, :comptime,
                     :recedate, :recenum, :recetime,
                     :arrivdate, :arrivtime,
                     :actcd, :actnm, :equpcd, :equpnm,
                     :contremark, :gregicd,
                     :remoremark, :regicd,
                     :resuremark, :remocd,
                     :resultcd, :faccd,
                     :customer, :resucd,
                     :remark, :actperid, :result,
                     :perid, :inperid, :indate,
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
        headParam.addValue("perid",    actperid);

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
        detailParam.addValue("perid",    actperid);
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

    // ── 고장부위 조회 (TB_E013) ──────────────────────────────
    public List<Map<String, Object>> getGreginmList(String keyword) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("keyword", "%" + (keyword != null ? keyword : "") + "%");
        return this.sqlRunner.getRows("""
                SELECT gregicd, greginm FROM TB_E013
                WHERE ISNULL(greginm,'') LIKE :keyword AND useyn = '1'
                ORDER BY greginm
                """, param);
    }

    // ── 고장부위상세 조회 (TB_E014) ──────────────────────────
    public List<Map<String, Object>> getReginmList(String gregicd, String keyword) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("gregicd", gregicd);
        param.addValue("keyword", "%" + (keyword != null ? keyword : "") + "%");
        return this.sqlRunner.getRows("""
                SELECT a.regicd, a.reginm, a.gregicd FROM TB_E014 a
                LEFT JOIN TB_E013 b ON a.gregicd = b.gregicd
                WHERE ISNULL(b.gregicd,'') = :gregicd
                  AND ISNULL(a.reginm,'') LIKE :keyword
                ORDER BY a.reginm
                """, param);
    }

    // ── 고장요인 조회 (TB_E011) ──────────────────────────────
    public List<Map<String, Object>> getRemonmList(String keyword) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("keyword", "%" + (keyword != null ? keyword : "") + "%");
        return this.sqlRunner.getRows("""
                SELECT remocd, remonm FROM TB_E011
                WHERE ISNULL(remonm,'') LIKE :keyword AND useyn = '1'
                ORDER BY remonm
                """, param);
    }

    // ── 고장원인 조회 (TB_E019) ──────────────────────────────
    public List<Map<String, Object>> getFacnmList(String keyword) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("keyword", "%" + (keyword != null ? keyword : "") + "%");
        return this.sqlRunner.getRows("""
                SELECT faccd, facnm FROM TB_E019
                WHERE ISNULL(facnm,'') LIKE :keyword AND useyn = '1'
                ORDER BY faccd
                """, param);
    }

    // ── 처리내용 조회 (TB_E012) ──────────────────────────────
    public List<Map<String, Object>> getResunmList(String keyword) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("keyword", "%" + (keyword != null ? keyword : "") + "%");
        return this.sqlRunner.getRows("""
                SELECT resucd, resunm FROM TB_E012
                WHERE ISNULL(resunm,'') LIKE :keyword AND useyn = '1'
                ORDER BY resunm
                """, param);
    }

    // ── 처리결과 조회 (TB_E015) ──────────────────────────────
    public List<Map<String, Object>> getResultnmList(String keyword) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("keyword", "%" + (keyword != null ? keyword : "") + "%");
        return this.sqlRunner.getRows("""
                SELECT resultcd, resultnm FROM TB_E015
                WHERE ISNULL(resultnm,'') LIKE :keyword AND useyn = '1'
                ORDER BY resultcd
                """, param);
    }

    // ── 고장처리결과 수정 (TB_E411 UPDATE) ───────────────────
    public void updateComp(
            String spjangcd, String compdate, String compnum, String comptime,
            String recedate, String recetime, String arrivdate, String arrivtime,
            String actcd, String actnm, String equpcd, String equpnm,
            String contremark, String gregicd, String remoremark, String regicd,
            String resuremark, String remocd, String resultcd, String faccd,
            String customer, String resucd, String remark, String actperid) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd",   spjangcd);
        param.addValue("compdate",   compdate);
        param.addValue("compnum",    compnum);
        param.addValue("comptime",   comptime);
        param.addValue("recedate",   recedate);
        param.addValue("recetime",   recetime);
        param.addValue("arrivdate",  arrivdate);
        param.addValue("arrivtime",  arrivtime);
        param.addValue("actcd",      actcd);
        param.addValue("actnm",      actnm);
        param.addValue("equpcd",     equpcd);
        param.addValue("equpnm",     equpnm);
        param.addValue("contremark", contremark);
        param.addValue("gregicd",    gregicd);
        param.addValue("remoremark", remoremark);
        param.addValue("regicd",     regicd);
        param.addValue("resuremark", resuremark);
        param.addValue("remocd",     remocd);
        param.addValue("resultcd",   resultcd);
        param.addValue("faccd",      faccd);
        param.addValue("customer",   customer);
        param.addValue("resucd",     resucd);
        param.addValue("remark",     remark);
        param.addValue("actperid",   actperid);

        namedParameterJdbcTemplate.update("""
                UPDATE TB_E411 SET
                    comptime   = :comptime,
                    recedate   = :recedate,
                    recetime   = :recetime,
                    arrivdate  = :arrivdate,
                    arrivtime  = :arrivtime,
                    actcd      = :actcd,
                    actnm      = :actnm,
                    equpcd     = :equpcd,
                    equpnm     = :equpnm,
                    contremark = :contremark,
                    gregicd    = :gregicd,
                    remoremark = :remoremark,
                    regicd     = :regicd,
                    resuremark = :resuremark,
                    remocd     = :remocd,
                    resultcd   = :resultcd,
                    faccd      = :faccd,
                    customer   = :customer,
                    resucd     = :resucd,
                    remark     = :remark,
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

    // ── compnum 채번 (001 ~ 999) ──────────────────────────────
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
