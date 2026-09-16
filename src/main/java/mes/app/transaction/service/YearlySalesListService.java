package mes.app.transaction.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 년도별 매출현황 — 파워빌더 두 화면을 한 화면에 담았다.
 *   요약(getSummary) : '년도별 매출현황'(w_tb_da023_02w) — 4개년 매출액과 전년 대비 증가율
 *   월별(getMonthly) : '월별 매출현황'(w_tb_da023_03w) — 최근 4년 × 1~12월 매출액, 부서 조건
 *
 * 두 화면 모두 행이 거래처가 아니라 연도다. (거래처별로 보는 화면은 월별매출현황이 따로 있다)
 */
@Slf4j
@Service
public class YearlySalesListService {

    @Autowired
    SqlRunner sqlRunner;

    public String getCustcd(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        Map<String, Object> row = sqlRunner.getRow(
                "SELECT custcd FROM tb_xa012 WHERE spjangcd = :spjangcd", param);
        if (row == null || row.get("custcd") == null) return null;
        return String.valueOf(row.get("custcd")).trim();
    }

    /** 최근 4년 × 12개월 매출액 (파워빌더 w_tb_da023_03w) */
    public List<Map<String, Object>> getMonthly(String year, String divicd, String spjangcd) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        StringBuilder months = new StringBuilder();
        for (int m = 1; m <= 12; m++) {
            months.append(String.format(
                    "       SUM(CASE WHEN SUBSTRING(misdate, 5, 2) = '%02d' THEN misamt ELSE 0 END) AS mon%d,%n",
                    m, m));
        }

        // 평균은 파워빌더 화면의 '평균' 칸이다. 자료가 있는 달로만 나눈다(해가 진행 중이면 그 달까지).
        String sql = "SELECT LEFT(misdate, 4) + '년' AS misyear,\n"
                + months
                + "       SUM(misamt) AS total_sum,\n"
                + "       SUM(misamt) / NULLIF(COUNT(DISTINCT SUBSTRING(misdate, 5, 2)), 0) AS avg_amt\n"
                + "  FROM TB_DA023 WITH(NOLOCK)\n"
                + " WHERE custcd = :custcd\n"
                + "   AND spjangcd = :spjangcd\n"
                + "   AND LEFT(misdate, 4) BETWEEN CONVERT(varchar(4), CONVERT(numeric, :year) - 3) AND :year\n"
                + "   AND (:divicd = '' OR divicd = :divicd)\n"
                + " GROUP BY LEFT(misdate, 4)\n"
                + " ORDER BY LEFT(misdate, 4)\n";

        return sqlRunner.getRows(sql, param(spjangcd, custcd, year, divicd));
    }

    /**
     * 4개년 매출액과 증가율 (파워빌더 w_tb_da023_02w).
     * 증가율은 (당해 − 전년) / 당해 이고, 음수면 0 으로 둔다(파워빌더와 동일).
     */
    public List<Map<String, Object>> getSummary(String year, String spjangcd) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        String yearAmt = """
                WITH y AS (
                    SELECT LEFT(misdate, 4) AS yy, SUM(ISNULL(misamt, 0)) AS amt
                      FROM TB_DA023 WITH(NOLOCK)
                     WHERE custcd = :custcd AND spjangcd = :spjangcd
                     GROUP BY LEFT(misdate, 4)
                ),
                v AS (
                    SELECT
                        ISNULL((SELECT amt FROM y WHERE yy = CONVERT(varchar(4), CONVERT(numeric, :year))), 0)     AS g1,
                        ISNULL((SELECT amt FROM y WHERE yy = CONVERT(varchar(4), CONVERT(numeric, :year) - 1)), 0) AS g2,
                        ISNULL((SELECT amt FROM y WHERE yy = CONVERT(varchar(4), CONVERT(numeric, :year) - 2)), 0) AS g3,
                        ISNULL((SELECT amt FROM y WHERE yy = CONVERT(varchar(4), CONVERT(numeric, :year) - 3)), 0) AS g4,
                        ISNULL((SELECT amt FROM y WHERE yy = CONVERT(varchar(4), CONVERT(numeric, :year) - 4)), 0) AS g5
                )
                SELECT '금액' AS gubun, g1 AS gubun1, g2 AS gubun2, g3 AS gubun3, g4 AS gubun4 FROM v
                UNION ALL
                SELECT '증가율',
                       CASE WHEN g1 = 0 OR (g1 - g2) <= 0 THEN 0 ELSE (g1 - g2) / g1 END,
                       CASE WHEN g2 = 0 OR (g2 - g3) <= 0 THEN 0 ELSE (g2 - g3) / g2 END,
                       CASE WHEN g3 = 0 OR (g3 - g4) <= 0 THEN 0 ELSE (g3 - g4) / g3 END,
                       CASE WHEN g4 = 0 OR (g4 - g5) <= 0 THEN 0 ELSE (g4 - g5) / g4 END
                  FROM v
                """;

        return sqlRunner.getRows(yearAmt, param(spjangcd, custcd, year, ""));
    }

    private MapSqlParameterSource param(String spjangcd, String custcd, String year, String divicd) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("custcd", custcd);
        p.addValue("spjangcd", spjangcd);
        p.addValue("year", year == null ? "" : year.trim());
        p.addValue("divicd", divicd == null ? "" : divicd.trim());
        return p;
    }
}
