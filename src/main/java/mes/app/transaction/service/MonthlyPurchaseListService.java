package mes.app.transaction.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 월별매입현황 — 매입 탭은 파워빌더 '월별비용현황'(w_tb_ca642w_05) 과 같다.
 * 거래처별로 그 해 1~12월 매입액을 늘어놓는다.
 *
 * 지급·미지급 탭은 파워빌더 원본(월별미지급현황 w_tb_ca642w_11)을 아직 못 받아서
 * 매입 탭과 같은 모양으로 맞춰 만들었다. 원본을 받으면 대조해야 한다.
 *
 * 예전 코드는 sports 의 tb_invoicement/tb_banktransit 를 읽었는데 사업체 DB 에는 그 테이블이 없다.
 */
@Slf4j
@Service
public class MonthlyPurchaseListService {

  @Autowired
  SqlRunner sqlRunner;

  /** spjangcd 로 custcd 조회 (파워빌더의 as_custcd) */
  public String getCustcd(String spjangcd) {
    MapSqlParameterSource param = new MapSqlParameterSource();
    param.addValue("spjangcd", spjangcd);
    Map<String, Object> row = sqlRunner.getRow(
            "SELECT custcd FROM tb_xa012 WHERE spjangcd = :spjangcd", param);
    if (row == null || row.get("custcd") == null) return null;
    return String.valueOf(row.get("custcd")).trim();
  }

  private MapSqlParameterSource param(String spjangcd, String custcd, String year, String cltcd) {
    MapSqlParameterSource p = new MapSqlParameterSource();
    p.addValue("custcd", custcd);
    p.addValue("spjangcd", spjangcd);
    p.addValue("year", year == null ? "" : year.trim());
    p.addValue("cltcd", cltcd == null ? "" : cltcd.trim());
    return p;
  }

  /** 월 컬럼 12개 (mon1 ~ mon12). dateCol 의 5~6번째 자리가 월이다. */
  private static String monthColumns(String dateCol, String amtExpr) {
    StringBuilder sb = new StringBuilder();
    for (int m = 1; m <= 12; m++) {
      sb.append(String.format(
              "       SUM(CASE WHEN SUBSTRING(%s, 5, 2) = '%02d' THEN %s ELSE 0 END) AS mon%d,%n",
              dateCol, m, amtExpr, m));
    }
    return sb.toString();
  }

  /** 매입 탭 — 파워빌더 w_tb_ca642w_05 */
  public List<Map<String, Object>> getMonthPurchaseList(String year, String cltcd, String spjangcd) {
    String custcd = getCustcd(spjangcd);
    if (custcd == null) return List.of();

    String sql = """
        SELECT a.mijcltcd AS cltcd,
               a.mijcltnm AS cltname,
        """
        + monthColumns("a.mijdate", "ISNULL(a.mijamt, 0)")
        + """
               SUM(ISNULL(a.mijamt, 0)) AS total_sum
          FROM TB_CA640 a WITH(NOLOCK)
         WHERE a.custcd = :custcd
           AND a.spjangcd = :spjangcd
           AND LEFT(a.mijdate, 4) = :year
           AND (:cltcd = '' OR a.mijcltcd = :cltcd)
         GROUP BY a.mijcltcd, a.mijcltnm
         ORDER BY a.mijcltnm
        """;

    return sqlRunner.getRows(sql, param(spjangcd, custcd, year, cltcd));
  }

  /** 지급 탭 — 파워빌더 원본 미확보. 매입 탭과 같은 모양으로 맞춘 것이다. */
  public List<Map<String, Object>> getMonthPaymentList(String year, String cltcd, String spjangcd) {
    String custcd = getCustcd(spjangcd);
    if (custcd == null) return List.of();

    String paid = "ISNULL(a.hamt,0) + ISNULL(a.eamt,0) + ISNULL(a.samt,0) + ISNULL(a.bamt,0)"
                + " + ISNULL(a.damt,0) + ISNULL(a.gamt,0) + ISNULL(a.sunamt,0) - ISNULL(a.plamt,0)";

    String sql = "SELECT a.cltcd AS cltcd,\n"
        + "       MAX(ISNULL(x.cltnm, '')) AS cltname,\n"
        + monthColumns("a.snddate", paid)
        + "       SUM(" + paid + ") AS total_sum\n"
        + "  FROM TB_CA642 a WITH(NOLOCK)\n"
        + "  LEFT JOIN TB_XCLIENT x WITH(NOLOCK) ON x.custcd = a.custcd AND x.cltcd = a.cltcd\n"
        + " WHERE a.custcd = :custcd\n"
        + "   AND a.spjangcd = :spjangcd\n"
        + "   AND LEFT(a.snddate, 4) = :year\n"
        + "   AND (:cltcd = '' OR a.cltcd = :cltcd)\n"
        + " GROUP BY a.cltcd\n"
        + " ORDER BY MAX(ISNULL(x.cltnm, ''))\n";

    return sqlRunner.getRows(sql, param(spjangcd, custcd, year, cltcd));
  }

  /** 미지급 탭 — 월별 (매입 − 지급). 파워빌더 원본 미확보. */
  public List<Map<String, Object>> getMonthPayableList(String year, String cltcd, String spjangcd) {
    String custcd = getCustcd(spjangcd);
    if (custcd == null) return List.of();

    String sql = """
        SELECT z.cltcd,
               MAX(z.cltname) AS cltname,
        """
        + monthColumns("z.ymd", "z.amt")
        + """
               SUM(z.amt) AS total_sum
          FROM (
                SELECT a.mijcltcd AS cltcd, a.mijcltnm AS cltname, a.mijdate AS ymd, ISNULL(a.mijamt,0) AS amt
                  FROM TB_CA640 a WITH(NOLOCK)
                 WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd AND LEFT(a.mijdate,4) = :year
                   AND (:cltcd = '' OR a.mijcltcd = :cltcd)
                UNION ALL
                SELECT a.cltcd, ISNULL(x.cltnm,''), a.snddate,
                       -1 * (ISNULL(a.hamt,0) + ISNULL(a.eamt,0) + ISNULL(a.samt,0) + ISNULL(a.bamt,0)
                           + ISNULL(a.damt,0) + ISNULL(a.gamt,0) + ISNULL(a.sunamt,0) - ISNULL(a.plamt,0))
                  FROM TB_CA642 a WITH(NOLOCK)
                  LEFT JOIN TB_XCLIENT x WITH(NOLOCK) ON x.custcd = a.custcd AND x.cltcd = a.cltcd
                 WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd AND LEFT(a.snddate,4) = :year
                   AND (:cltcd = '' OR a.cltcd = :cltcd)
               ) z
         GROUP BY z.cltcd
         ORDER BY MAX(z.cltname)
        """;

    return sqlRunner.getRows(sql, param(spjangcd, custcd, year, cltcd));
  }

  /** 매입 탭에서 거래처를 눌렀을 때 — 그 해 매입 전표 목록 */
  public List<Map<String, Object>> getPurchaseDetail(String year, String cltcd, String spjangcd) {
    String custcd = getCustcd(spjangcd);
    if (custcd == null || cltcd == null || cltcd.isBlank()) return List.of();

    String sql = """
        SELECT STUFF(STUFF(b.mijdate, 5, 0, '-'), 8, 0, '-') AS misdate,
               ISNULL(g.com_cnam, '') AS misgubun,
               ISNULL(b.remark, '') AS itemnm,
               ISNULL(b.size, '')   AS spec,
               ISNULL(b.qty, 0)     AS qty,
               ISNULL(b.samt, 0)    AS supplycost,
               ISNULL(b.tamt, 0)    AS taxtotal,
               ISNULL(b.mijamt, 0)  AS totalamt
          FROM TB_CA640 a WITH(NOLOCK)
          JOIN TB_CA641 b WITH(NOLOCK)
            ON a.custcd = b.custcd AND a.spjangcd = b.spjangcd
           AND a.mijdate = b.mijdate AND a.mijnum = b.mijnum
          LEFT JOIN TB_CA510 g WITH(NOLOCK) ON g.com_cls = '113' AND g.com_code = a.gubun
         WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd
           AND LEFT(a.mijdate, 4) = :year
           AND a.mijcltcd = :cltcd
         ORDER BY a.mijdate, a.mijnum, b.seq
        """;

    return sqlRunner.getRows(sql, param(spjangcd, custcd, year, cltcd));
  }

  /** 지급 탭에서 거래처를 눌렀을 때 — 그 해 지급 전표 목록 */
  public List<Map<String, Object>> getPaymentDetail(String year, String cltcd, String spjangcd) {
    String custcd = getCustcd(spjangcd);
    if (custcd == null || cltcd == null || cltcd.isBlank()) return List.of();

    String sql = """
        SELECT STUFF(STUFF(a.snddate, 5, 0, '-'), 8, 0, '-') AS trdate,
               a.sndnum AS sndnum,
               MAX(ISNULL(x.cltnm, '')) AS CompanyName,
               SUM(ISNULL(a.hamt,0) + ISNULL(a.eamt,0) + ISNULL(a.samt,0) + ISNULL(a.bamt,0)
                 + ISNULL(a.damt,0) + ISNULL(a.gamt,0) + ISNULL(a.sunamt,0) - ISNULL(a.plamt,0)) AS accout,
               MAX(ISNULL(acc.banknm, '')) AS banknm,
               MAX(ISNULL(acc.accnum, '')) AS accnum,
               MAX(ISNULL(a.remark, '')) AS remark
          FROM TB_CA642 a WITH(NOLOCK)
          LEFT JOIN TB_XCLIENT x WITH(NOLOCK) ON x.custcd = a.custcd AND x.cltcd = a.cltcd
          LEFT JOIN TB_AA040 acc WITH(NOLOCK) ON acc.custcd = a.custcd AND acc.bank + acc.bankcd = a.bankcd
         WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd
           AND LEFT(a.snddate, 4) = :year
           AND a.cltcd = :cltcd
         GROUP BY a.snddate, a.sndnum
         ORDER BY a.snddate, a.sndnum
        """;

    return sqlRunner.getRows(sql, param(spjangcd, custcd, year, cltcd));
  }
}
