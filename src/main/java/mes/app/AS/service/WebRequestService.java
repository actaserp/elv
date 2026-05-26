package mes.app.AS.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class WebRequestService {

    @Autowired
    @Qualifier("mainSqlRunner")
    SqlRunner sqlRunner;

    @Autowired
    SqlRunner tenantSqlRunner; // 사업체DB (RoutingDataSource, @Primary)

    // ── 사용자 정보 조회 (custcd + spjangcd) ─────────────────
    private Map<String, Object> getUserInfo(String username) {

        // 1. 본사DB에서 personid 조회
        MapSqlParameterSource mainParam = new MapSqlParameterSource();
        mainParam.addValue("username", username);

        String mainSql = """
                SELECT personid
                FROM auth_user
                WHERE username = :username
                """;

        List<Map<String, Object>> mainRows = sqlRunner.getRows(mainSql, mainParam);
        if (mainRows.isEmpty()) return null;

        Object personid = mainRows.get(0).get("personid");
        if (personid == null) return null;

        // 2. 사업체DB에서 person.id → TB_JA001 조인으로 custcd, spjangcd 조회
        MapSqlParameterSource tenantParam = new MapSqlParameterSource();
        tenantParam.addValue("personid", personid);

        String tenantSql = """
                SELECT TOP 1 j.custcd, j.spjangcd
                FROM person p
                JOIN TB_JA001 j ON j.perid = p.Code
                WHERE p.id = :personid
                """;

        List<Map<String, Object>> tenantRows = tenantSqlRunner.getRows(tenantSql, tenantParam);
        return tenantRows.isEmpty() ? null : tenantRows.get(0);
    }

    // ── 요약 카운트 ───────────────────────────────────────────
    public Map<String, Object> getSummary(String username) {
        Map<String, Object> userInfo = getUserInfo(username);
        if (userInfo == null) return null;

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", userInfo.get("spjangcd"));

        String sql = """
                SELECT
                    COUNT(*)                                             AS cntRecv,
                    SUM(CASE WHEN divicd IS NOT NULL THEN 1 ELSE 0 END) AS cntRece,
                    0                                                    AS cntCallback,
                    SUM(CASE WHEN resultck = '1'    THEN 1 ELSE 0 END)  AS cntToday
                FROM TB_E401
                WHERE spjangcd = :spjangcd
                  AND recedate = CONVERT(VARCHAR(8), GETDATE(), 112)
                """;

        return tenantSqlRunner.getRow(sql, param);
    }

    // ── 고장접수 목록 조회 (TB_E401) ─────────────────────────
    public List<Map<String, Object>> getRepairList(
            String username, String fromDate, String toDate, String actnm) {

        Map<String, Object> userInfo = getUserInfo(username);
        if (userInfo == null) return List.of();

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", userInfo.get("spjangcd"));
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
                    e.contents,
                    e.remark,
                    e.reperid,
                    e.perid,
                    j.pernm,
                    e.divicd,
                    jc.divinm
                FROM TB_E401 e
                LEFT JOIN TB_JA001 j  ON j.perid    = 'p' + e.perid
                                     AND j.spjangcd  = e.spjangcd
                LEFT JOIN TB_JC002 jc ON j.divicd   = jc.divicd
                WHERE e.spjangcd = :spjangcd
                  AND e.recedate BETWEEN :fromDate AND :toDate
                """;

        if (actnm != null && !actnm.isBlank()) {
            sql += " AND e.actnm LIKE :actnm";
            param.addValue("actnm", "%" + actnm.trim() + "%");
        }

        sql += " ORDER BY e.recedate DESC, e.recenum DESC";

        return tenantSqlRunner.getRows(sql, param);
    }

    // ── 현장 목록 조회 (TB_E601) ─────────────────────────────
    public List<Map<String, Object>> getActList(String username) {
        Map<String, Object> userInfo = getUserInfo(username);
        if (userInfo == null) return List.of();

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", userInfo.get("spjangcd"));

        String sql = """
                SELECT actcd, actnm
                FROM TB_E601
                WHERE spjangcd = :spjangcd
                ORDER BY actnm ASC
                """;

        return tenantSqlRunner.getRows(sql, param);
    }

    // ── 호기 목록 조회 (TB_E611) ─────────────────────────────
    public List<Map<String, Object>> getEqupList(String username, String actcd) {
        Map<String, Object> userInfo = getUserInfo(username);
        if (userInfo == null) return List.of();

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", userInfo.get("spjangcd"));
        param.addValue("actcd",    actcd);

        String sql = """
                SELECT equpcd, equpnm
                FROM TB_E611 WITH(NOLOCK)
                WHERE spjangcd = :spjangcd
                  AND actcd    = :actcd
                ORDER BY equpcd ASC
                """;

        return tenantSqlRunner.getRows(sql, param);
    }

    // ── 고장접수 저장 (TB_E401 INSERT / UPDATE) ───────────────
    public void saveRepair(
            String username,
            String recedate,
            String recenum,
            String recetime,
            String hitchdate,
            String hitchhour,
            String actcd,
            String actnm,
            String equpcd,
            String equpnm,
            String contents,
            String remark,
            String reperid,
            String perid) {

        Map<String, Object> userInfo = getUserInfo(username);
        if (userInfo == null) throw new RuntimeException("사용자 정보를 찾을 수 없습니다.");

        String custcd   = (String) userInfo.get("custcd");
        String spjangcd = (String) userInfo.get("spjangcd");

        if (recenum == null || recenum.isBlank()) {
            // 신규 INSERT
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
            param.addValue("contents",  contents);
            param.addValue("remark",    remark);
            param.addValue("reperid",   reperid);
            param.addValue("perid",     perid);
            param.addValue("inperid",   perid);
            param.addValue("indate",    recedate);

            String sql = """
                    INSERT INTO TB_E401
                        (custcd, spjangcd, recedate, recenum, recetime,
                         hitchdate, hitchhour,
                         actcd, actnm, equpcd, equpnm,
                         contents, remark, reperid,
                         perid, inperid, indate)
                    VALUES
                        (:custcd, :spjangcd, :recedate, :recenum, :recetime,
                         :hitchdate, :hitchhour,
                         :actcd, :actnm, :equpcd, :equpnm,
                         :contents, :remark, :reperid,
                         :perid, :inperid, :indate)
                    """;

            tenantSqlRunner.execute(sql, param);

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
            param.addValue("contents",  contents);
            param.addValue("remark",    remark);
            param.addValue("reperid",   reperid);

            String sql = """
                    UPDATE TB_E401 SET
                        recetime    = :recetime,
                        hitchdate   = :hitchdate,
                        hitchhour   = :hitchhour,
                        actcd       = :actcd,
                        actnm       = :actnm,
                        equpcd      = :equpcd,
                        equpnm      = :equpnm,
                        contents    = :contents,
                        remark      = :remark,
                        reperid     = :reperid,
                        update_time = GETDATE()
                    WHERE spjangcd = :spjangcd
                      AND recedate = :recedate
                      AND recenum  = :recenum
                    """;

            tenantSqlRunner.execute(sql, param);
        }
    }

    // ── 고장접수 삭제 (TB_E401 DELETE) ───────────────────────
    public void deleteRepair(String username, String recedate, String recenum) {
        Map<String, Object> userInfo = getUserInfo(username);
        if (userInfo == null) throw new RuntimeException("사용자 정보를 찾을 수 없습니다.");

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", userInfo.get("spjangcd"));
        param.addValue("recedate", recedate);
        param.addValue("recenum",  recenum);

        String sql = """
                DELETE FROM TB_E401
                WHERE spjangcd = :spjangcd
                  AND recedate = :recedate
                  AND recenum  = :recenum
                """;

        tenantSqlRunner.execute(sql, param);
    }

    // ── recenum 채번 (001 ~ 999) ──────────────────────────────
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

        Map<String, Object> row = tenantSqlRunner.getRow(sql, param);
        int next = row != null ? ((Number) row.values().iterator().next()).intValue() : 1;
        return String.format("%03d", next);
    }
}
