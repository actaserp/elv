package mes.app.transaction.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 미지급현황 — 파워빌더 '미지급현황'(w_tb_ca642w_02) 과 같은 자료를 본다.
 *
 * 거래처별로 [전잔액 + 당기매입 − 당기지급 = 잔액] 을 보여준다.
 * 파워빌더는 여섯 갈래를 UNION ALL 로 붙인 뒤 거래처 단위로 합친다.
 *   1) 미결건수  : 전체 기간(~종료일) 중 매입액에서 지급액을 뺀 값이 0 이 아닌 건수
 *   2) 당기매입  : 조회기간 매입액
 *   3) 당기지급  : 조회기간 지급액
 *   4) 전잔액(+) : 그 해 1월 1일 ~ 조회 시작 전일 매입액
 *   5) 전잔액(−) : 같은 기간 지급액
 *   6) 전잔액(이월) : TB_CA640_END 의 해당 연도 이월액
 *
 * 지급액은 TB_CA640 헤더에도 쌓여 있어 1)은 헤더만 보고 판단한다.
 * (경기 2023년 1,206건 중 1,137건에 헤더 지급액이 들어 있다)
 *
 * 예전 코드는 sports 의 tb_invoicement/tb_banktransit/tb_yearamt 를 읽었는데
 * 사업체 DB 에는 그 테이블이 없다. 이월 방식도 다르다(잔액 저장 → 전표 누적).
 */
@Slf4j
@Service
public class AccountsPayableListService {

  @Autowired
  SqlRunner sqlRunner;

  // 상세(원장)는 '거래처별잔액명세서' 화면과 같은 자료라 그쪽 서비스를 그대로 쓴다.
  @Autowired
  VendorBalanceDetailService vendorBalanceDetailService;

  /** spjangcd 로 custcd 조회 (파워빌더의 as_custcd) */
  public String getCustcd(String spjangcd) {
    MapSqlParameterSource param = new MapSqlParameterSource();
    param.addValue("spjangcd", spjangcd);
    Map<String, Object> row = sqlRunner.getRow(
            "SELECT custcd FROM tb_xa012 WHERE spjangcd = :spjangcd", param);
    if (row == null || row.get("custcd") == null) return null;
    return String.valueOf(row.get("custcd")).trim();
  }

  private MapSqlParameterSource baseParam(String spjangcd, String custcd, String start, String end,
                                          String cltcd, String gubun) {
    MapSqlParameterSource p = new MapSqlParameterSource();
    p.addValue("custcd", custcd);
    p.addValue("spjangcd", spjangcd);
    p.addValue("stdate", start == null ? "" : start.replaceAll("-", ""));
    p.addValue("enddate", end == null ? "" : end.replaceAll("-", ""));
    p.addValue("cltcd", cltcd == null ? "" : cltcd.trim());
    p.addValue("gubun", gubun == null ? "" : gubun.trim());
    return p;
  }

  /**
   * 거래처별 미지급 현황
   *
   * @param balanceOnly 잔액이 0 이 아닌 거래처만 (파워빌더 화면의 '잔액체크' 체크박스, 기본 켬)
   */
  public List<Map<String, Object>> getPayableList(
          String start, String end, String spjangcd, String cltcd, String gubun, boolean balanceOnly) {

    String custcd = getCustcd(spjangcd);
    if (custcd == null) return List.of();

    String sql = """
        SELECT z.cltcd,
               z.cltnm AS cltname,
               ISNULL(SUM(z.beamt), 0) AS payable,
               ISNULL(SUM(z.mijamt), 0) AS purchase,
               ISNULL(SUM(z.iamt), 0) AS amount_paid,
               (ISNULL(SUM(z.beamt), 0) + ISNULL(SUM(z.mijamt), 0)) - ISNULL(SUM(z.iamt), 0) AS balance,
               CONVERT(varchar(10), SUM(z.remark)) + '건' AS remark
          FROM (
                -- 1) 미결건수
                SELECT mijcltcd AS cltcd, mijcltnm AS cltnm,
                       0 AS beamt, 0 AS mijamt, 0 AS iamt, COUNT(mijcltcd) AS remark
                  FROM TB_CA640 WITH(NOLOCK)
                 WHERE custcd = :custcd AND spjangcd = :spjangcd
                   AND mijdate <= :enddate
                   AND (:gubun = '' OR gubun = :gubun)
                   AND ISNULL(mijamt, 0) - (ISNULL(hamt,0) + ISNULL(eamt,0) + ISNULL(samt,0)
                                          + ISNULL(bamt,0) + ISNULL(damt,0) + ISNULL(sunamt,0)
                                          + ISNULL(gamt,0)) <> 0
                 GROUP BY mijcltcd, mijcltnm

                UNION ALL

                -- 2) 당기매입
                SELECT mijcltcd, mijcltnm,
                       0, ISNULL(SUM(mijamt), 0), 0, 0
                  FROM TB_CA640 WITH(NOLOCK)
                 WHERE custcd = :custcd AND spjangcd = :spjangcd
                   AND mijdate BETWEEN :stdate AND :enddate
                   AND (:gubun = '' OR gubun = :gubun)
                 GROUP BY mijcltcd, mijcltnm

                UNION ALL

                -- 3) 당기지급
                SELECT a.cltcd, b.mijcltnm,
                       0, 0,
                       SUM(ISNULL(a.hamt,0) + ISNULL(a.eamt,0) + ISNULL(a.samt,0) + ISNULL(a.bamt,0)
                         + ISNULL(a.damt,0) + ISNULL(a.gamt,0) + ISNULL(a.sunamt,0)),
                       0
                  FROM TB_CA642 a WITH(NOLOCK)
                  LEFT OUTER JOIN TB_CA640 b WITH(NOLOCK)
                    ON  a.custcd = b.custcd AND a.spjangcd = b.spjangcd
                    AND a.cltcd = b.mijcltcd AND a.mijdate = b.mijdate AND a.mijnum = b.mijnum
                 WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd
                   AND a.snddate BETWEEN :stdate AND :enddate
                   AND (:gubun = '' OR b.gubun = :gubun)
                 GROUP BY a.cltcd, b.mijcltnm

                UNION ALL

                -- 4) 전잔액(+) : 연초 ~ 조회 시작 전일 매입
                SELECT mijcltcd, mijcltnm,
                       ISNULL(SUM(mijamt), 0), 0, 0, 0
                  FROM TB_CA640 WITH(NOLOCK)
                 WHERE custcd = :custcd AND spjangcd = :spjangcd
                   AND mijdate BETWEEN LEFT(:stdate, 4) + '0101'
                                   AND CONVERT(varchar(8), DATEADD(day, -1, CONVERT(datetime, :stdate)), 112)
                   AND (:gubun = '' OR gubun = :gubun)
                 GROUP BY mijcltcd, mijcltnm

                UNION ALL

                -- 5) 전잔액(−) : 같은 기간 지급
                SELECT a.cltcd, b.mijcltnm,
                       SUM(ISNULL(a.hamt,0) + ISNULL(a.eamt,0) + ISNULL(a.samt,0) + ISNULL(a.bamt,0)
                         + ISNULL(a.damt,0) + ISNULL(a.gamt,0) + ISNULL(a.sunamt,0)) * -1,
                       0, 0, 0
                  FROM TB_CA642 a WITH(NOLOCK)
                  LEFT OUTER JOIN TB_CA640 b WITH(NOLOCK)
                    ON  a.custcd = b.custcd AND a.spjangcd = b.spjangcd
                    AND a.cltcd = b.mijcltcd AND a.mijdate = b.mijdate AND a.mijnum = b.mijnum
                 WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd
                   AND a.snddate BETWEEN LEFT(:stdate, 4) + '0101'
                                     AND CONVERT(varchar(8), DATEADD(day, -1, CONVERT(datetime, :stdate)), 112)
                   AND (:gubun = '' OR b.gubun = :gubun)
                 GROUP BY a.custcd, a.spjangcd, a.cltcd, b.mijcltnm

                UNION ALL

                -- 6) 전잔액(이월) : 마감이월 테이블
                SELECT mijcltcd, mijcltnm,
                       ISNULL(SUM(mijamt), 0), 0, 0, 0
                  FROM TB_CA640_END WITH(NOLOCK)
                 WHERE custcd = :custcd AND spjangcd = :spjangcd
                   AND year = LEFT(:stdate, 4)
                   AND (:gubun = '' OR gubun = :gubun)
                 GROUP BY mijcltcd, mijcltnm
               ) z
         WHERE (:cltcd = '' OR z.cltcd LIKE '%' + :cltcd + '%')
         GROUP BY z.cltcd, z.cltnm
        """
        // 잔액체크: 잔액이 0 인 거래처는 감춘다 (파워빌더 기본값)
        + (balanceOnly
            ? " HAVING (ISNULL(SUM(z.beamt),0) + ISNULL(SUM(z.mijamt),0)) - ISNULL(SUM(z.iamt),0) <> 0\n"
            : "")
        + " ORDER BY z.cltnm\n";

    return sqlRunner.getRows(sql, baseParam(spjangcd, custcd, start, end, cltcd, gubun));
  }

  /**
   * 거래처 한 곳의 매입·지급 원장 (화면 아래쪽 상세).
   *
   * 같은 자료를 보는 파워빌더 화면이 '거래처별잔액명세서'(w_tb_ca642w_03) 이고
   * 그쪽을 {@link VendorBalanceDetailService} 에 옮겨놨다. 두 화면이 갈라지지 않도록 그대로 쓴다.
   */
  public List<Map<String, Object>> getPayableDetailList(
          String start, String end, String spjangcd, String cltcd, String gubun) {

    if (cltcd == null || cltcd.isBlank()) return List.of();
    return vendorBalanceDetailService.getPaymentList(spjangcd, start, end, cltcd, gubun);
  }
}
