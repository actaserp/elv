package mes.app.transaction.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 비용항목별 월별현황 — 파워빌더 '지급항목별원장'(w_tb_ca642w_11).
 *
 * 이름과 달리 지급이 아니라 매입액(TB_CA640.mijamt)을 비용항목으로 묶어 1~12월로 펼친 화면이다.
 * 비용항목 대분류는 TB_CA647 이고, 매입의 비용항목코드(artcd) 앞 두 자리가 대분류코드(gartcd)다.
 *
 * 파워빌더 화면의 '거래처' 입력칸은 실제로는 비용항목명(gartnm)을 앞자리 일치로 찾는다.
 * (파라미터 이름만 as_mijcltcd 로 남아 있다) 여기서는 칸 이름을 비용항목으로 바꿔 달았다.
 *
 * 상세 탭(비용항목상세)은 파워빌더 원본을 못 받아 같은 형태로 세부 항목(TB_CA648) 단위로 만들었다.
 */
@Slf4j
@Service
public class MonthlyExpenseItemService {

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

    /** 월 컬럼 12개 + 합계 + 평균(자료가 있는 달 수로 나눔) */
    private static String monthColumns() {
        StringBuilder sb = new StringBuilder();
        for (int m = 1; m <= 12; m++) {
            sb.append(String.format(
                    "       SUM(CASE WHEN SUBSTRING(a.mijdate, 5, 2) = '%02d' THEN ISNULL(a.mijamt,0) ELSE 0 END) AS mon%d,%n",
                    m, m));
        }
        sb.append("       SUM(ISNULL(a.mijamt,0)) AS total_sum,\n");
        sb.append("       SUM(ISNULL(a.mijamt,0)) / NULLIF(COUNT(DISTINCT SUBSTRING(a.mijdate, 5, 2)), 0) AS avg_amt\n");
        return sb.toString();
    }

    private MapSqlParameterSource param(String spjangcd, String custcd, String year, String artnm) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("custcd", custcd);
        p.addValue("spjangcd", spjangcd);
        p.addValue("year", year == null ? "" : year.trim());
        p.addValue("artnm", artnm == null ? "" : artnm.trim());
        return p;
    }

    /** 비용항목별 (대분류) — 파워빌더 w_tb_ca642w_11 */
    public List<Map<String, Object>> getByCategory(String year, String artnm, String spjangcd) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        String sql = "SELECT c.gartcd AS code,\n"
                + "       c.gartnm AS artnm,\n"
                + monthColumns()
                + "  FROM TB_CA640 a WITH(NOLOCK)\n"
                + "  JOIN TB_CA647 c WITH(NOLOCK)\n"
                + "    ON a.custcd = c.custcd AND a.spjangcd = c.spjangcd\n"
                + "   AND LEFT(a.artcd, 2) = c.gartcd\n"
                + " WHERE a.custcd = :custcd\n"
                + "   AND a.spjangcd = :spjangcd\n"
                + "   AND LEFT(a.mijdate, 4) = :year\n"
                + "   AND (:artnm = '' OR c.gartnm LIKE :artnm + '%')\n"
                + " GROUP BY c.gartcd, c.gartnm\n"
                + " ORDER BY c.gartcd\n";

        return sqlRunner.getRows(sql, param(spjangcd, custcd, year, artnm));
    }

    /** 비용항목상세 (세부 항목) — 파워빌더 원본 미확보. 대분류 탭과 같은 형태다. */
    public List<Map<String, Object>> getByItem(String year, String artnm, String spjangcd) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        String sql = "SELECT a.artcd AS code,\n"
                + "       MAX(ISNULL(d.artnm, '')) AS artnm,\n"
                + "       MAX(ISNULL(c.gartnm, '')) AS gartnm,\n"
                + monthColumns()
                + "  FROM TB_CA640 a WITH(NOLOCK)\n"
                + "  LEFT JOIN TB_CA648 d WITH(NOLOCK)\n"
                + "    ON a.custcd = d.custcd AND a.spjangcd = d.spjangcd AND a.artcd = d.artcd\n"
                + "  LEFT JOIN TB_CA647 c WITH(NOLOCK)\n"
                + "    ON a.custcd = c.custcd AND a.spjangcd = c.spjangcd AND LEFT(a.artcd, 2) = c.gartcd\n"
                + " WHERE a.custcd = :custcd\n"
                + "   AND a.spjangcd = :spjangcd\n"
                + "   AND LEFT(a.mijdate, 4) = :year\n"
                + "   AND ISNULL(a.artcd, '') <> ''\n"
                + "   AND (:artnm = '' OR ISNULL(c.gartnm,'') LIKE :artnm + '%' OR ISNULL(d.artnm,'') LIKE :artnm + '%')\n"
                + " GROUP BY a.artcd\n"
                + " ORDER BY a.artcd\n";

        return sqlRunner.getRows(sql, param(spjangcd, custcd, year, artnm));
    }
}
