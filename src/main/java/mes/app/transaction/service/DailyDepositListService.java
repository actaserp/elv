package mes.app.transaction.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 일별입금현황 — 파워빌더 '일별입금현황'(w_tb_da026_02w).
 * 한 달을 골라 거래처별로 1~31일 수금액을 늘어놓는다.
 *
 * 수금액 합산 컬럼이 월별입금현황(12개)과 다르다. 이 화면은 선수금(sunamt)이 빠진 11개다.
 * 파워빌더 원본이 그렇게 되어 있어 그대로 옮겼다. (경기 기준 sunamt 는 전부 0 이라 실제 차이는 없다)
 */
@Slf4j
@Service
public class DailyDepositListService {

    @Autowired
    SqlRunner sqlRunner;

    /** 일별입금현황의 수금액 — 선수금 제외 11개 */
    private static final String RCV_AMT_11 =
            "ISNULL(a.hamt,0) + ISNULL(a.eamt,0) + ISNULL(a.samt,0) + ISNULL(a.bamt,0)"
          + " + ISNULL(a.damt,0) + ISNULL(a.gamt,0) + ISNULL(a.jamt,0) + ISNULL(a.jmar,0)"
          + " + ISNULL(a.csamt,0) + ISNULL(a.cmar,0) + ISNULL(a.dcamt,0)";

    public String getCustcd(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        Map<String, Object> row = sqlRunner.getRow(
                "SELECT custcd FROM tb_xa012 WHERE spjangcd = :spjangcd", param);
        if (row == null || row.get("custcd") == null) return null;
        return String.valueOf(row.get("custcd")).trim();
    }

    /**
     * @param mon   조회 월 (YYYYMM)
     * @param cltcd 거래처코드. 빈 값이면 전체
     * @param spcd  거래처그룹. 빈 값이면 전체
     */
    public List<Map<String, Object>> getDailyDepositList(String mon, String cltcd, String spjangcd, String spcd) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        StringBuilder days = new StringBuilder();
        for (int d = 1; d <= 31; d++) {
            days.append(String.format(
                    "       SUM(CASE WHEN RIGHT(a.rcvdate, 2) = '%02d' THEN %s ELSE 0 END) AS day%d,%n",
                    d, RCV_AMT_11, d));
        }

        String sql = "SELECT a.cltcd AS cltcd,\n"
                + "       ISNULL(b.cltnm, '') AS cltname,\n"
                + days
                + "       SUM(" + RCV_AMT_11 + ") AS total_sum\n"
                + "  FROM TB_DA026 a WITH(NOLOCK)\n"
                + "  LEFT OUTER JOIN TB_XCLIENT b WITH(NOLOCK) ON a.custcd = b.custcd AND a.cltcd = b.cltcd\n"
                + " WHERE a.custcd = :custcd\n"
                + "   AND a.spjangcd = :spjangcd\n"
                + "   AND LEFT(a.rcvdate, 6) = :mon\n"
                + "   AND (:spcd = '' OR b.spcd = :spcd)\n"
                + "   AND (:cltcd = '' OR a.cltcd = :cltcd)\n"
                + " GROUP BY a.cltcd, b.cltnm\n"
                + " ORDER BY b.cltnm\n";

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("custcd", custcd);
        p.addValue("spjangcd", spjangcd);
        p.addValue("mon", mon == null ? "" : mon.replaceAll("-", ""));
        p.addValue("cltcd", cltcd == null ? "" : cltcd.trim());
        p.addValue("spcd", spcd == null ? "" : spcd.trim());

        return sqlRunner.getRows(sql, p);
    }
}
