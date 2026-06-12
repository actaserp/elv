package mes.app.dashboard.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class DashCompService {

    @Autowired
    SqlRunner sqlRunner;

    // ── 계약현황 카운트 ───────────────────────────────────────
    public Map<String, Object> getContractCount(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        String today   = java.time.LocalDate.now().toString().replace("-", "");
        String after30 = java.time.LocalDate.now().plusDays(30).toString().replace("-", "");
        param.addValue("today",   today);
        param.addValue("after30", after30);

        String sql = """
                SELECT
                    COUNT(*)                                                              AS totalCnt,
                    SUM(CASE WHEN actgubun = '01' THEN 1 ELSE 0 END)                     AS contractCnt,
                    SUM(CASE WHEN actgubun != '01' THEN 1 ELSE 0 END)                    AS cancelCnt,
                    SUM(CASE WHEN actgubun = '01'
                              AND enddate IS NOT NULL AND enddate != ''
                              AND enddate < :today    THEN 1 ELSE 0 END)                 AS expiredCnt,
                    SUM(CASE WHEN actgubun = '01'
                              AND enddate IS NOT NULL AND enddate != ''
                              AND enddate BETWEEN :today AND :after30 THEN 1 ELSE 0 END) AS soonCnt
                FROM TB_E601
                WHERE spjangcd = :spjangcd
                """;
        return sqlRunner.getRow(sql, param);
    }

    // ── 계약현황 목록 ─────────────────────────────────────────
    public List<Map<String, Object>> getContractList(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        String today   = java.time.LocalDate.now().toString().replace("-", "");
        String after30 = java.time.LocalDate.now().plusDays(30).toString().replace("-", "");
        param.addValue("today",   today);
        param.addValue("after30", after30);

        String sql = """
                SELECT TOP 30
                    e.actcd,
                    e.actnm,
                    e.tel,
                    e.stdate,
                    e.enddate,
                    e.actpernm,
                    CASE WHEN e.actgubun = '01' THEN '계약중' ELSE '해지' END AS actgubunNm,
                    CASE
                        WHEN e.actgubun = '01' AND e.enddate IS NOT NULL AND e.enddate != ''
                             AND e.enddate < :today    THEN '만료'
                        WHEN e.actgubun = '01' AND e.enddate IS NOT NULL AND e.enddate != ''
                             AND e.enddate <= :after30 THEN '만료임박'
                        WHEN e.actgubun = '01'         THEN '정상'
                        ELSE '해지'
                    END AS contractStatusNm,
                    (SELECT COUNT(*) FROM TB_E611 eq
                     WHERE eq.actcd = e.actcd AND eq.spjangcd = e.spjangcd) AS equpCnt
                FROM TB_E601 e
                WHERE e.spjangcd = :spjangcd
                ORDER BY
                    CASE WHEN e.actgubun != '01' THEN 1 ELSE 0 END ASC,
                    e.enddate ASC
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
                        WHEN e.enddate < :today    THEN '만료'
                        WHEN e.enddate <= :after30 THEN '만료임박'
                        ELSE '정상'
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
                    CASE WHEN e.actgubun = '01' THEN '계약중' ELSE '해지' END AS actgubunNm,
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
