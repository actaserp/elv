package mes.app.transaction.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 월별매출현황
 *
 * 입금 탭은 파워빌더 '월별입금현황'(w_tb_da026_01w) 과 같다.
 * 거래처별로 그 해 1~12월 수금액을 늘어놓는다.
 *
 * 수금액은 TB_DA026 의 금액 컬럼 12개를 모두 더한 값이다.
 *   현금(hamt) 어음(eamt) 수표(samt) 예금(bamt) 카드(damt) 기타(gamt)
 *   지로(jamt) + 지로마진(jmar), 카드수금(csamt) + 카드마진(cmar), 할인(dcamt), 선수금(sunamt)
 * 매입 쪽(TB_CA642)은 7개뿐이라 수금이 더 복잡하다.
 *
 * 매출·미수 탭은 파워빌더 원본(월별 매출현황 w_tb_da023_03w, 월별미수현황 w_tb_da026_03w)을
 * 아직 못 받아서 입금 탭과 같은 모양으로 맞춰 만들었다. 원본을 받으면 대조해야 한다.
 *
 * 예전 코드는 sports 의 tb_salesment/tb_banktransit 를 읽었는데 사업체 DB 에는 그 테이블이 없다.
 */
@Slf4j
@Service
public class MonthlySalesListService {

    @Autowired
    SqlRunner sqlRunner;

    /**
     * 현장별 미수 탭의 수금 합산 범위.
     *
     * 파워빌더 원본은 9개 컬럼만 더해서 카드수금(csamt)·카드마진(cmar/cdmar)·선수금(sunamt)이 빠진다.
     * 경기 2026년 기준 그 네 컬럼이 251,562,134 원(대부분 카드수금)이라, 현장별 미수가 거래처별보다
     * 그만큼 크게 나온다. (거래처별 377,934,443 vs 현장별 611,441,540)
     *
     * 기본값은 파워빌더와 같은 9개다. 거래처별 탭과 숫자를 맞추려면 설정에서 아래 키를 true 로 두면 된다.
     *   elv.receivable.site-full-columns=true
     */
    @Value("${elv.receivable.site-full-columns:false}")
    private boolean siteFullColumns;

    /** 수금액 (파워빌더와 동일한 12개 컬럼 합) */
    private static final String RCV_AMT =
            "ISNULL(a.hamt,0) + ISNULL(a.eamt,0) + ISNULL(a.samt,0) + ISNULL(a.bamt,0)"
          + " + ISNULL(a.damt,0) + ISNULL(a.gamt,0) + ISNULL(a.jamt,0) + ISNULL(a.jmar,0)"
          + " + ISNULL(a.csamt,0) + ISNULL(a.cmar,0) + ISNULL(a.dcamt,0) + ISNULL(a.sunamt,0)";

    /** spjangcd 로 custcd 조회 (파워빌더의 as_custcd) */
    public String getCustcd(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        Map<String, Object> row = sqlRunner.getRow(
                "SELECT custcd FROM tb_xa012 WHERE spjangcd = :spjangcd", param);
        if (row == null || row.get("custcd") == null) return null;
        return String.valueOf(row.get("custcd")).trim();
    }

    private MapSqlParameterSource param(String spjangcd, String custcd, String year,
                                        String cltcd, String spcd) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("custcd", custcd);
        p.addValue("spjangcd", spjangcd);
        p.addValue("year", year == null ? "" : year.trim());
        p.addValue("cltcd", cltcd == null ? "" : cltcd.trim());
        p.addValue("spcd", spcd == null ? "" : spcd.trim());
        return p;
    }

    /** 월 컬럼 12개 (mon1 ~ mon12) */
    private static String monthColumns(String dateCol, String amtExpr) {
        StringBuilder sb = new StringBuilder();
        for (int m = 1; m <= 12; m++) {
            sb.append(String.format(
                    "       SUM(CASE WHEN SUBSTRING(%s, 5, 2) = '%02d' THEN %s ELSE 0 END) AS mon%d,%n",
                    dateCol, m, amtExpr, m));
        }
        return sb.toString();
    }

    /** 입금 탭 — 파워빌더 w_tb_da026_01w */
    public List<Map<String, Object>> getMonthDepositList(String year, String cltcd, String spjangcd, String spcd) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        String sql = "SELECT a.cltcd AS cltcd,\n"
                + "       ISNULL(b.cltnm, '') AS cltname,\n"
                + monthColumns("a.rcvdate", RCV_AMT)
                + "       SUM(" + RCV_AMT + ") AS total_sum\n"
                + "  FROM TB_DA026 a WITH(NOLOCK)\n"
                + "  LEFT OUTER JOIN TB_XCLIENT b WITH(NOLOCK) ON a.custcd = b.custcd AND a.cltcd = b.cltcd\n"
                + " WHERE a.custcd = :custcd\n"
                + "   AND a.spjangcd = :spjangcd\n"
                + "   AND LEFT(a.rcvdate, 4) = :year\n"
                + "   AND (:spcd = '' OR b.spcd = :spcd)\n"
                + "   AND (:cltcd = '' OR a.cltcd = :cltcd)\n"
                + " GROUP BY a.cltcd, b.cltnm\n"
                + " ORDER BY b.cltnm\n";

        return sqlRunner.getRows(sql, param(spjangcd, custcd, year, cltcd, spcd));
    }

    /** 매출 탭 — 파워빌더 원본 미확보. 입금 탭과 같은 모양으로 맞춘 것이다. */
    public List<Map<String, Object>> getMonthSalesList(String year, String cltcd, String spjangcd, String spcd) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        String sql = "SELECT a.cltcd AS cltcd,\n"
                + "       ISNULL(b.cltnm, '') AS cltname,\n"
                + monthColumns("a.misdate", "ISNULL(a.misamt, 0)")
                + "       SUM(ISNULL(a.misamt, 0)) AS total_sum\n"
                + "  FROM TB_DA023 a WITH(NOLOCK)\n"
                + "  LEFT OUTER JOIN TB_XCLIENT b WITH(NOLOCK) ON a.custcd = b.custcd AND a.cltcd = b.cltcd\n"
                + " WHERE a.custcd = :custcd\n"
                + "   AND a.spjangcd = :spjangcd\n"
                + "   AND LEFT(a.misdate, 4) = :year\n"
                + "   AND (:spcd = '' OR b.spcd = :spcd)\n"
                + "   AND (:cltcd = '' OR a.cltcd = :cltcd)\n"
                + " GROUP BY a.cltcd, b.cltnm\n"
                + " ORDER BY b.cltnm\n";

        return sqlRunner.getRows(sql, param(spjangcd, custcd, year, cltcd, spcd));
    }

    /**
     * 미수금 탭 — 파워빌더 월별미수현황(창 이름은 w_tb_da026_03w 로 추정).
     * 월 칸은 그 달 매출에서 그 전표로 들어온 수금을 뺀 잔액이고,
     * 전잔액(bemisamt)은 마감이월(TB_DA023_END)에 남은 미수에서 당해년도 수금을 뺀 값이다.
     *
     * 상계 처리된 전표(sangflag '0','1')는 뺀다. 경기 2025년 이후 15,752건 중 33건이 해당한다.
     *
     * 수금액 합산 컬럼 수가 파워빌더 화면마다 다르다.
     *   월별입금현황(w_tb_da026_01w) 12개 / 일별입금현황(w_tb_da026_02w) 11개(sunamt 없음) / 이 화면 13개(cdmar 추가)
     * 원본이 그렇게 되어 있어 화면별 원본대로 옮겼다. 경기 기준 sunamt 는 전부 0, cdmar 는 12건이라 실제 차이는 작다.
     */
    public List<Map<String, Object>> getMonthReceivableList(String year, String cltcd, String spjangcd, String spcd) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        String rcv13 = RCV_AMT + " + ISNULL(a.cdmar,0)";
        String rcvSub = "ISNULL(hamt,0) + ISNULL(eamt,0) + ISNULL(samt,0) + ISNULL(bamt,0) + ISNULL(damt,0)"
                      + " + ISNULL(gamt,0) + ISNULL(jamt,0) + ISNULL(jmar,0) + ISNULL(csamt,0) + ISNULL(cmar,0)"
                      + " + ISNULL(dcamt,0) + ISNULL(cdmar,0) + ISNULL(sunamt,0)";
        // 상계 처리된 전표 제외 조건 (파워빌더와 동일)
        String notSang = "(a.sangflag NOT IN ('1','0') OR a.sangflag IS NULL OR LEN(a.sangflag) = 0)";

        String sql = "SELECT z.cltcd,\n"
                + "       MAX(ISNULL(x.cltnm, '')) AS cltname,\n"
                + "       SUM(z.bemisamt) AS bemisamt,\n"
                + "       SUM(z.mon1) AS mon1, SUM(z.mon2) AS mon2, SUM(z.mon3) AS mon3, SUM(z.mon4) AS mon4,\n"
                + "       SUM(z.mon5) AS mon5, SUM(z.mon6) AS mon6, SUM(z.mon7) AS mon7, SUM(z.mon8) AS mon8,\n"
                + "       SUM(z.mon9) AS mon9, SUM(z.mon10) AS mon10, SUM(z.mon11) AS mon11, SUM(z.mon12) AS mon12,\n"
                + "       SUM(z.bemisamt) + SUM(z.mon1) + SUM(z.mon2) + SUM(z.mon3) + SUM(z.mon4) + SUM(z.mon5)\n"
                + "     + SUM(z.mon6) + SUM(z.mon7) + SUM(z.mon8) + SUM(z.mon9) + SUM(z.mon10) + SUM(z.mon11)\n"
                + "     + SUM(z.mon12) AS total_sum\n"
                + "  FROM (\n"
                // 1) 전잔액 — 마감이월에 남은 미수
                + "        SELECT a.cltcd, SUM(ISNULL(a.misamt,0)) AS bemisamt,\n"
                + zeroMonths()
                + "          FROM TB_DA023_END a WITH(NOLOCK)\n"
                + "          JOIN TB_XCLIENT c WITH(NOLOCK) ON a.custcd = c.custcd AND a.cltcd = c.cltcd\n"
                + "         WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd AND a.year = :year\n"
                + "           AND (:cltcd = '' OR a.cltcd = :cltcd) AND (:spcd = '' OR c.spcd = :spcd)\n"
                + "           AND a.misdate + a.misnum NOT IN (SELECT misdate + misnum FROM TB_DA023 WITH(NOLOCK)\n"
                + "                 WHERE custcd = a.custcd AND spjangcd = a.spjangcd AND sangflag IN ('1','0'))\n"
                + "         GROUP BY a.cltcd\n"
                + "        UNION ALL\n"
                // 2) 전잔액 차감 — 이월분에 대한 당해년도 수금
                + "        SELECT a.cltcd, SUM(" + rcv13 + ") * -1 AS bemisamt,\n"
                + zeroMonths()
                + "          FROM TB_DA026 a WITH(NOLOCK)\n"
                + "          JOIN TB_XCLIENT c WITH(NOLOCK) ON a.custcd = c.custcd AND a.cltcd = c.cltcd\n"
                + "         WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd AND LEFT(a.rcvdate,4) = :year\n"
                + "           AND (:cltcd = '' OR a.cltcd = :cltcd) AND (:spcd = '' OR c.spcd = :spcd)\n"
                + "           AND a.misdate + a.misnum IN (SELECT misdate + misnum FROM TB_DA023_END WITH(NOLOCK)\n"
                + "                 WHERE custcd = a.custcd AND spjangcd = a.spjangcd AND year = :year)\n"
                + "           AND a.misdate + a.misnum NOT IN (SELECT misdate + misnum FROM TB_DA023 WITH(NOLOCK)\n"
                + "                 WHERE custcd = a.custcd AND spjangcd = a.spjangcd AND sangflag IN ('0','1'))\n"
                + "         GROUP BY a.cltcd\n"
                + "        UNION ALL\n"
                // 3) 월별 미수 — 매출액 − 그 전표로 들어온 수금
                + "        SELECT a.cltcd, 0 AS bemisamt,\n"
                + stripTrailingComma(monthColumns("a.misdate", "a.misamt - ISNULL(r.iamt, 0)"))
                + "          FROM TB_DA023 a WITH(NOLOCK)\n"
                + "          LEFT OUTER JOIN (SELECT custcd, spjangcd, cltcd, misdate, misnum,\n"
                + "                                  SUM(" + rcvSub + ") AS iamt\n"
                + "                             FROM TB_DA026 WITH(NOLOCK)\n"
                + "                            WHERE (:cltcd = '' OR cltcd = :cltcd)\n"
                + "                            GROUP BY custcd, spjangcd, cltcd, misdate, misnum) r\n"
                + "            ON a.custcd = r.custcd AND a.spjangcd = r.spjangcd\n"
                + "           AND a.misdate = r.misdate AND a.misnum = r.misnum AND a.cltcd = r.cltcd\n"
                + "          JOIN TB_XCLIENT c WITH(NOLOCK) ON a.custcd = c.custcd AND a.cltcd = c.cltcd\n"
                + "         WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd AND LEFT(a.misdate,4) = :year\n"
                + "           AND " + notSang + "\n"
                + "           AND (:cltcd = '' OR a.cltcd = :cltcd) AND (:spcd = '' OR c.spcd = :spcd)\n"
                + "         GROUP BY a.cltcd\n"
                + "       ) z\n"
                + "  LEFT OUTER JOIN TB_XCLIENT x WITH(NOLOCK) ON x.custcd = :custcd AND x.cltcd = z.cltcd\n"
                + " GROUP BY z.cltcd\n"
                + " ORDER BY MAX(ISNULL(x.cltnm, ''))\n";

        return sqlRunner.getRows(sql, param(spjangcd, custcd, year, cltcd, spcd));
    }

    /**
     * 미수금 탭 (현장별) — 파워빌더 월별미수현황의 '현장별' 탭.
     * 거래처 대신 현장(TB_E601)으로 묶고 담당자(TB_JA001)까지 보여준다.
     *
     * 파워빌더 원본과 다르게 둔 곳이 두 군데 있다.
     *  1) 이월 차감(두 번째 갈래)에서 원본은 현장코드 자리에 거래처코드(b.cltcd)를 넣는다.
     *     바깥에서 현장으로 조인하므로 값이 엉뚱한 현장에 붙거나 사라진다. 여기서는 마감이월의 현장코드를 쓴다.
     *     (경기는 마감이월이 2020년치뿐이라 지금 조회에는 영향이 없다)
     *  2) 수금 합산 컬럼이 거래처별 탭(13개)과 달리 원본이 9개다. 기본값은 원본대로 9개이고,
     *     설정 elv.receivable.site-full-columns=true 로 13개(거래처별과 동일)로 바꿀 수 있다.
     */
    public List<Map<String, Object>> getMonthReceivableBySite(
            String year, String actcd, String divicd, String perid, String spjangcd) {

        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        // 현장별 탭의 수금액 — 기본은 파워빌더 원본과 같은 9개, 설정을 켜면 거래처별과 같은 13개
        String rcvSite = "ISNULL(hamt,0) + ISNULL(eamt,0) + ISNULL(samt,0) + ISNULL(bamt,0) + ISNULL(damt,0)"
                    + " + ISNULL(gamt,0) + ISNULL(jamt,0) + ISNULL(jmar,0) + ISNULL(dcamt,0)";
        if (siteFullColumns) {
            rcvSite += " + ISNULL(csamt,0) + ISNULL(cmar,0) + ISNULL(cdmar,0) + ISNULL(sunamt,0)";
        }
        String rcv13 = "ISNULL(b.hamt,0) + ISNULL(b.eamt,0) + ISNULL(b.samt,0) + ISNULL(b.bamt,0)"
                     + " + ISNULL(b.damt,0) + ISNULL(b.gamt,0) + ISNULL(b.jamt,0) + ISNULL(b.dcamt,0)"
                     + " + ISNULL(b.jmar,0) + ISNULL(b.csamt,0) + ISNULL(b.cmar,0) + ISNULL(b.cdmar,0)"
                     + " + ISNULL(b.sunamt,0)";

        String sql = "SELECT z.actcd,\n"
                + "       MAX(ISNULL(e.actnm, '')) AS actnm,\n"
                + "       MAX(ISNULL(j.pernm, '')) AS pernm,\n"
                + "       SUM(z.bemisamt) AS bemisamt,\n"
                + "       SUM(z.mon1) AS mon1, SUM(z.mon2) AS mon2, SUM(z.mon3) AS mon3, SUM(z.mon4) AS mon4,\n"
                + "       SUM(z.mon5) AS mon5, SUM(z.mon6) AS mon6, SUM(z.mon7) AS mon7, SUM(z.mon8) AS mon8,\n"
                + "       SUM(z.mon9) AS mon9, SUM(z.mon10) AS mon10, SUM(z.mon11) AS mon11, SUM(z.mon12) AS mon12,\n"
                + "       SUM(z.bemisamt) + SUM(z.mon1) + SUM(z.mon2) + SUM(z.mon3) + SUM(z.mon4) + SUM(z.mon5)\n"
                + "     + SUM(z.mon6) + SUM(z.mon7) + SUM(z.mon8) + SUM(z.mon9) + SUM(z.mon10) + SUM(z.mon11)\n"
                + "     + SUM(z.mon12) AS total_sum\n"
                + "  FROM (\n"
                // 1) 전잔액 — 마감이월
                + "        SELECT a.actcd, SUM(ISNULL(a.misamt,0)) AS bemisamt,\n"
                + zeroMonths()
                + "          FROM TB_DA023_END a WITH(NOLOCK)\n"
                + "         WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd AND a.year = :year\n"
                + "           AND (:actcd = '' OR a.actcd = :actcd)\n"
                + "           AND (:divicd = '' OR a.divicd = :divicd)\n"
                + "           AND (:perid = '' OR a.perid = :perid)\n"
                + "         GROUP BY a.actcd\n"
                + "        UNION ALL\n"
                // 2) 전잔액 차감 — 이월분에 대한 당해년도 수금
                + "        SELECT d.actcd, SUM(" + rcv13 + ") * -1 AS bemisamt,\n"
                + zeroMonths()
                + "          FROM TB_DA026 b WITH(NOLOCK)\n"
                + "          JOIN TB_DA023_END d WITH(NOLOCK)\n"
                + "            ON b.custcd = d.custcd AND b.spjangcd = d.spjangcd AND b.cltcd = d.cltcd\n"
                + "           AND b.misdate = d.misdate AND b.misnum = d.misnum\n"
                + "         WHERE b.custcd = :custcd AND b.spjangcd = :spjangcd\n"
                + "           AND d.year = :year AND LEFT(b.rcvdate,4) = :year\n"
                + "           AND (:actcd = '' OR d.actcd = :actcd)\n"
                + "           AND (:divicd = '' OR d.divicd = :divicd)\n"
                + "           AND (:perid = '' OR d.perid = :perid)\n"
                + "           AND b.misdate + b.misnum NOT IN (SELECT misdate + misnum FROM TB_DA023 WITH(NOLOCK)\n"
                + "                 WHERE custcd = b.custcd AND spjangcd = b.spjangcd AND sangflag IN ('0','1'))\n"
                + "         GROUP BY d.actcd\n"
                + "        UNION ALL\n"
                // 3) 월별 미수 — 매출액 − 그 전표로 들어온 수금(현장 기준)
                + "        SELECT a.actcd, 0 AS bemisamt,\n"
                + stripTrailingComma(monthColumns("a.misdate", "a.misamt - ISNULL(r.iamt, 0)"))
                + "          FROM TB_DA023 a WITH(NOLOCK)\n"
                + "          LEFT OUTER JOIN (SELECT custcd, spjangcd, actcd, misdate, misnum,\n"
                + "                                  SUM(" + rcvSite + ") AS iamt\n"
                + "                             FROM TB_DA026 WITH(NOLOCK)\n"
                + "                            WHERE LEFT(rcvdate,4) = :year AND (:actcd = '' OR actcd = :actcd)\n"
                + "                            GROUP BY custcd, spjangcd, actcd, misdate, misnum) r\n"
                + "            ON a.custcd = r.custcd AND a.spjangcd = r.spjangcd\n"
                + "           AND a.misdate = r.misdate AND a.misnum = r.misnum AND a.actcd = r.actcd\n"
                + "         WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd AND LEFT(a.misdate,4) = :year\n"
                + "           AND (:actcd = '' OR a.actcd = :actcd)\n"
                + "           AND (:divicd = '' OR a.divicd = :divicd)\n"
                + "           AND (:perid = '' OR a.perid = :perid)\n"
                + "         GROUP BY a.actcd\n"
                + "       ) z\n"
                + "  LEFT JOIN TB_E601 e WITH(NOLOCK) ON e.custcd = :custcd AND e.actcd = z.actcd\n"
                + "  LEFT JOIN TB_JA001 j WITH(NOLOCK)\n"
                + "         ON j.custcd = e.custcd AND j.spjangcd = e.spjangcd\n"
                + "        AND e.perid = SUBSTRING(j.perid, 2, 10)\n"
                + " GROUP BY z.actcd\n"
                + " ORDER BY MAX(ISNULL(e.actnm, ''))\n";

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("custcd", custcd);
        p.addValue("spjangcd", spjangcd);
        p.addValue("year", year == null ? "" : year.trim());
        p.addValue("actcd", actcd == null ? "" : actcd.trim());
        p.addValue("divicd", divicd == null ? "" : divicd.trim());
        p.addValue("perid", perid == null ? "" : perid.trim());

        return sqlRunner.getRows(sql, p);
    }

    /** 마지막 줄의 쉼표를 뗀다 (UNION 갈래에서 월 칸 뒤에 FROM 이 바로 오는 경우) */
    private static String stripTrailingComma(String cols) {
        String trimmed = cols.stripTrailing();
        if (trimmed.endsWith(",")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed + "\n";
    }

    /** UNION 갈래에서 월 칸을 0 으로 채울 때 */
    private static String zeroMonths() {
        StringBuilder sb = new StringBuilder();
        for (int m = 1; m <= 12; m++) {
            sb.append(String.format("               0 AS mon%d%s%n", m, m == 12 ? "" : ","));
        }
        return sb.toString();
    }

    /** 매출 탭에서 거래처를 눌렀을 때 — 그 해 매출 전표 */
    public List<Map<String, Object>> getSalesDetail(String year, String cltcd, String spjangcd) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null || cltcd == null || cltcd.isBlank()) return List.of();

        String sql = """
                SELECT STUFF(STUFF(a.misdate, 5, 0, '-'), 8, 0, '-') AS misdate,
                       a.misnum,
                       ISNULL(d.pname, '') AS itemnm,
                       ISNULL(d.psize, '') AS spec,
                       ISNULL(d.qty, 0)    AS qty,
                       ISNULL(a.amt, 0)    AS supplycost,
                       ISNULL(a.addamt, 0) AS taxtotal,
                       ISNULL(a.misamt, 0) AS totalamt
                  FROM TB_DA023 a WITH(NOLOCK)
                  OUTER APPLY (SELECT TOP 1 pname, psize, qty FROM TB_DA024 WITH(NOLOCK)
                                WHERE TB_DA024.custcd = a.custcd AND TB_DA024.spjangcd = a.spjangcd
                                  AND TB_DA024.misdate = a.misdate AND TB_DA024.misnum = a.misnum
                                ORDER BY seq) d
                 WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd
                   AND LEFT(a.misdate, 4) = :year
                   AND a.cltcd = :cltcd
                 ORDER BY a.misdate, a.misnum
                """;

        return sqlRunner.getRows(sql, param(spjangcd, custcd, year, cltcd, ""));
    }

    /** 입금 탭에서 거래처를 눌렀을 때 — 그 해 수금 전표 */
    public List<Map<String, Object>> getDepositDetail(String year, String cltcd, String spjangcd) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null || cltcd == null || cltcd.isBlank()) return List.of();

        String sql = "SELECT STUFF(STUFF(a.rcvdate, 5, 0, '-'), 8, 0, '-') AS trdate,\n"
                + "       a.rcvnum AS rcvnum,\n"
                + "       MAX(ISNULL(x.cltnm, '')) AS CompanyName,\n"
                + "       SUM(" + RCV_AMT + ") AS accin,\n"
                + "       MAX(ISNULL(acc.banknm, '')) AS banknm,\n"
                + "       MAX(ISNULL(acc.accnum, '')) AS accnum,\n"
                + "       MAX(ISNULL(a.remark, '')) AS memo\n"
                + "  FROM TB_DA026 a WITH(NOLOCK)\n"
                + "  LEFT JOIN TB_XCLIENT x WITH(NOLOCK) ON x.custcd = a.custcd AND x.cltcd = a.cltcd\n"
                + "  LEFT JOIN TB_AA040 acc WITH(NOLOCK) ON acc.custcd = a.custcd AND acc.bank + acc.bankcd = a.bankcd\n"
                + " WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd\n"
                + "   AND LEFT(a.rcvdate, 4) = :year\n"
                + "   AND a.cltcd = :cltcd\n"
                + " GROUP BY a.rcvdate, a.rcvnum\n"
                + " ORDER BY a.rcvdate, a.rcvnum\n";

        return sqlRunner.getRows(sql, param(spjangcd, custcd, year, cltcd, ""));
    }
}
