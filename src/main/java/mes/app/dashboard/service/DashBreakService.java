package mes.app.dashboard.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class DashBreakService {

    @Autowired
    SqlRunner sqlRunner;

    // ── 고장접수현황 카운트 (금일/이번달) ────────────────────
    public Map<String, Object> getReceiveCount(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        String today   = java.time.LocalDate.now().toString().replace("-", "");
        String monthFr = today.substring(0, 6) + "01";
        param.addValue("today",   today);
        param.addValue("monthFr", monthFr);

        String sql = """
                SELECT
                    SUM(CASE WHEN recedate = :today    THEN 1 ELSE 0 END) AS todayCnt,
                    SUM(CASE WHEN recedate >= :monthFr THEN 1 ELSE 0 END) AS monthCnt,
                    SUM(CASE WHEN resultck IS NULL OR resultck <> '1' THEN 1 ELSE 0 END) AS pendingCnt,
                    SUM(CASE WHEN resultck = '1'       THEN 1 ELSE 0 END) AS doneCnt
                FROM TB_E401
                WHERE spjangcd = :spjangcd
                  AND recedate >= :monthFr
                """;
        return sqlRunner.getRow(sql, param);
    }

    // ── 고장접수 목록 (최근 20건) ─────────────────────────────
    public List<Map<String, Object>> getReceiveList(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        String monthFr = java.time.LocalDate.now().toString().replace("-", "").substring(0, 6) + "01";
        param.addValue("monthFr", monthFr);

        String sql = """
                SELECT TOP 20
                    e.recedate,
                    e.recenum,
                    e.recetime,
                    e.actnm,
                    e.equpnm,
                    c.contnm,
                    e.contents,
                    CASE WHEN e.resultck = '1' THEN '\ucc98\ub9ac\uc644\ub8cc' ELSE '\uc811\uc218\uc911' END AS statusNm
                FROM TB_E401 e
                LEFT JOIN TB_E010 c ON c.contcd = e.contcd
                WHERE e.spjangcd = :spjangcd
                  AND e.recedate >= :monthFr
                ORDER BY e.recedate DESC, e.recenum DESC
                """;
        return sqlRunner.getRows(sql, param);
    }

    // ── 고장처리현황 카운트 ───────────────────────────────────
    public Map<String, Object> getHandleCount(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        String monthFr = java.time.LocalDate.now().toString().replace("-", "").substring(0, 6) + "01";
        param.addValue("monthFr", monthFr);

        String sql = """
                SELECT
                    COUNT(*) AS totalCnt,
                    SUM(CASE WHEN h.resucd IS NULL OR h.resucd = '' THEN 1 ELSE 0 END) AS processingCnt,
                    SUM(CASE WHEN h.resucd IS NOT NULL AND h.resucd != '' THEN 1 ELSE 0 END) AS completeCnt
                FROM TB_E411 h
                WHERE h.spjangcd = :spjangcd
                  AND h.compdate >= :monthFr
                """;
        return sqlRunner.getRow(sql, param);
    }

    // ── 고장처리 목록 (최근 20건) ─────────────────────────────
    public List<Map<String, Object>> getHandleList(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        String monthFr = java.time.LocalDate.now().toString().replace("-", "").substring(0, 6) + "01";
        param.addValue("monthFr", monthFr);

        String sql = """
                SELECT TOP 20
                    h.compdate,
                    h.compnum,
                    h.actnm,
                    h.equpnm,
                    h.greginm,
                    h.actpernm,
                    CASE WHEN h.resucd IS NULL OR h.resucd = '' THEN '\ucc98\ub9ac\uc911' ELSE '\uc644\ub8cc' END AS statusNm
                FROM TB_E411 h
                WHERE h.spjangcd = :spjangcd
                  AND h.compdate >= :monthFr
                ORDER BY h.compdate DESC, h.compnum DESC
                """;
        return sqlRunner.getRows(sql, param);
    }

    // ── 유지보수만료현황 카운트 ───────────────────────────────
    public Map<String, Object> getExpireCount(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        String today   = java.time.LocalDate.now().toString().replace("-", "");
        String after30 = java.time.LocalDate.now().plusDays(30).toString().replace("-", "");
        param.addValue("today",   today);
        param.addValue("after30", after30);

        String sql = """
                SELECT
                    SUM(CASE WHEN enddate < :today THEN 1 ELSE 0 END) AS expiredCnt,
                    SUM(CASE WHEN enddate BETWEEN :today AND :after30 THEN 1 ELSE 0 END) AS soonCnt,
                    COUNT(*) AS totalCnt
                FROM TB_E601
                WHERE spjangcd = :spjangcd
                  AND actgubun = '01'
                  AND enddate IS NOT NULL
                  AND enddate != ''
                """;
        return sqlRunner.getRow(sql, param);
    }

    // ── 유지보수만료 목록 ─────────────────────────────────────
    public List<Map<String, Object>> getExpireList(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        String today   = java.time.LocalDate.now().toString().replace("-", "");
        String after30 = java.time.LocalDate.now().plusDays(30).toString().replace("-", "");
        param.addValue("today",   today);
        param.addValue("after30", after30);

        String sql = """
                SELECT
                    e.actcd,
                    e.actnm,
                    e.tel,
                    e.stdate,
                    e.enddate,
                    e.actpernm,
                    CASE
                        WHEN e.enddate < :today    THEN '\ub9cc\ub8cc'
                        WHEN e.enddate <= :after30 THEN '\ub9cc\ub8cc\uc784\ubc15'
                        ELSE '\uc815\uc0c1'
                    END AS expireStatusNm
                FROM TB_E601 e
                WHERE e.spjangcd = :spjangcd
                  AND e.actgubun = '01'
                  AND e.enddate IS NOT NULL
                  AND e.enddate != ''
                  AND e.enddate <= :after30
                ORDER BY e.enddate ASC
                """;
        return sqlRunner.getRows(sql, param);
    }

    // ── 관리대수현황 카운트 ───────────────────────────────────
    public Map<String, Object> getManageCount(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT
                    COUNT(*) AS totalSite,
                    SUM(CASE WHEN actgubun = '01' THEN 1 ELSE 0 END) AS contractSite,
                    SUM(CASE WHEN actgubun != '01' THEN 1 ELSE 0 END) AS cancelSite,
                    (SELECT COUNT(*) FROM TB_E611 WHERE spjangcd = :spjangcd) AS totalEqup,
                    (SELECT COUNT(*) FROM TB_E611 eq
                     INNER JOIN TB_E601 s ON s.actcd = eq.actcd AND s.spjangcd = eq.spjangcd
                     WHERE eq.spjangcd = :spjangcd AND s.actgubun = '01') AS contractEqup
                FROM TB_E601
                WHERE spjangcd = :spjangcd
                """;
        return sqlRunner.getRow(sql, param);
    }

    // ── 사업장별 관리대수 목록 ────────────────────────────────
    public List<Map<String, Object>> getManageList(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT TOP 20
                    e.actcd,
                    e.actnm,
                    e.tel,
                    e.actpernm,
                    CASE WHEN e.actgubun = '01' THEN '\uacc4\uc57d\uc911' ELSE '\ud574\uc9c0' END AS actgubunNm,
                    (SELECT COUNT(*) FROM TB_E611 eq
                     WHERE eq.actcd = e.actcd AND eq.spjangcd = e.spjangcd) AS equpCnt
                FROM TB_E601 e
                WHERE e.spjangcd = :spjangcd
                  AND e.actgubun = '01'
                ORDER BY equpCnt DESC, e.actnm ASC
                """;
        return sqlRunner.getRows(sql, param);
    }
}
