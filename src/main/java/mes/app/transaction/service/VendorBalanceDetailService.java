package mes.app.transaction.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 미지급금 잔액명세 — 파워빌더 '거래처별잔액명세서'(w_tb_ca642w_03) 와 같은 자료를 본다.
 *
 * 세 갈래를 붙인다.
 *   1) 전일잔액 : 마감이월(TB_CA640_END) + 그 해 1월 1일 ~ 조회 시작 전일 매입 중
 *                 지급이 한 번도 붙지 않은 건
 *   2) 매입     : 조회기간 TB_CA640
 *   3) 지급     : 지급기간 TB_CA642 를 전표(snddate+sndnum) 단위로 합산, 할인(plamt) 차감
 *
 * 잔액(누적)은 파워빌더에서 데이터윈도우가 계산한다. 여기서는 같은 정렬(거래처 → 일자 → 전표번호 → 구분)로
 * 누적합을 내서 같은 값이 나오게 했다.
 *
 * 지급 기간은 매입 기간과 별도 파라미터다(파워빌더 as_rsdate/as_redate). 화면에서 따로 주지 않으면
 * 매입 기간과 같은 값을 쓴다.
 *
 * 주의: 1)의 '지급이 붙지 않은 건' 판정은 파워빌더가 거래처코드를 정확히 지정했을 때만 동작한다.
 * 거래처를 비우고 전체 조회하면 그 조건이 아무 것도 걸러내지 못해 이미 지급된 매입까지 전일잔액에 들어간다.
 * (파워빌더 원본의 동작을 그대로 옮긴 것이다. 이 화면은 거래처를 지정해서 보는 화면이다)
 */
@Slf4j
@Service
public class VendorBalanceDetailService {

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

  public List<Map<String, Object>> getPaymentList(
          String spjangcd, String start, String end, String cltcd, String gubun) {
    return getPaymentList(spjangcd, start, end, null, null, cltcd, gubun);
  }

  /**
   * @param start   매입 조회 시작일
   * @param end     매입 조회 종료일
   * @param rsdate  지급 조회 시작일 (null 이면 start)
   * @param redate  지급 조회 종료일 (null 이면 end)
   * @param cltcd   거래처코드. 빈 값이면 전체
   * @param gubun   매입구분. 빈 값이면 전체
   */
  public List<Map<String, Object>> getPaymentList(
          String spjangcd, String start, String end, String rsdate, String redate,
          String cltcd, String gubun) {

    String custcd = getCustcd(spjangcd);
    if (custcd == null) return List.of();

    String stdate = start == null ? "" : start.replaceAll("-", "");
    String enddate = end == null ? "" : end.replaceAll("-", "");

    MapSqlParameterSource p = new MapSqlParameterSource();
    p.addValue("custcd", custcd);
    p.addValue("spjangcd", spjangcd);
    p.addValue("stdate", stdate);
    p.addValue("enddate", enddate);
    p.addValue("rsdate", (rsdate == null || rsdate.isBlank()) ? stdate : rsdate.replaceAll("-", ""));
    p.addValue("redate", (redate == null || redate.isBlank()) ? enddate : redate.replaceAll("-", ""));
    p.addValue("cltcd", cltcd == null ? "" : cltcd.trim());
    p.addValue("gubun", gubun == null ? "" : gubun.trim());

    // 거래처명은 파워빌더가 dbo.df_nm_rtn('tb_xclient', ...) 로 가져온다. 같은 값이라 조인으로 대체했다.
    String sql = """
        SELECT a.cltcd,
               ISNULL(x.cltnm, a.cltnm) AS cltnm,
               CASE WHEN a.mijdate = '' THEN ''
                    ELSE STUFF(STUFF(a.mijdate, 5, 0, '-'), 8, 0, '-') END AS misdate,
               a.mijnum AS misnum,
               a.mijdate2 AS misdate2,
               CASE a.sort WHEN '1' THEN '전일잔액' WHEN '2' THEN '매입' ELSE '지급' END AS summary,
               a.remark,
               ISNULL(a.mijamt, 0) AS misamt,
               ISNULL(a.rcvamt, 0) AS rcvamt,
               SUM(ISNULL(a.mijamt, 0) - ISNULL(a.rcvamt, 0))
                   OVER (PARTITION BY a.cltcd ORDER BY a.mijdate, a.mijnum, a.sort
                         ROWS UNBOUNDED PRECEDING) AS balance,
               a.sort
          FROM (
                -- 1) 전일잔액
                SELECT '1' AS sort, z.custcd, z.spjangcd, z.cltcd, MAX(z.cltnm) AS cltnm,
                       '' AS mijdate, '' AS mijnum, '' AS mijdate2,
                       '전일잔액' AS remark,
                       SUM(z.beamt) AS mijamt, 0 AS rcvamt
                  FROM (
                        SELECT custcd, spjangcd, cltcd, '' AS cltnm, mijamt AS beamt
                          FROM TB_CA640_END WITH(NOLOCK)
                         WHERE custcd = :custcd AND spjangcd = :spjangcd
                           AND year = LEFT(:stdate, 4)
                           AND cltcd LIKE :cltcd + '%'
                           AND ISNULL(gubun, '') LIKE :gubun + '%'
                        UNION ALL
                        SELECT custcd, spjangcd, cltcd, cltnm, SUM(mijamt)
                          FROM TB_CA640 m WITH(NOLOCK)
                         WHERE custcd = :custcd AND spjangcd = :spjangcd
                           AND mijdate BETWEEN LEFT(:stdate, 4) + '0101'
                                           AND CONVERT(varchar(8), DATEADD(day, -1, CONVERT(datetime, :stdate)), 112)
                           AND cltcd LIKE :cltcd + '%'
                           AND ISNULL(gubun, '') LIKE :gubun + '%'
                           -- 지급이 한 번이라도 붙은 매입은 전일잔액에서 뺀다(파워빌더와 동일)
                           AND (:cltcd = '' OR m.mijdate + m.mijnum NOT IN (
                                   SELECT s.mijdate + s.mijnum FROM TB_CA642 s WITH(NOLOCK)
                                    WHERE s.custcd = :custcd AND s.spjangcd = :spjangcd AND s.cltcd = :cltcd))
                         GROUP BY custcd, spjangcd, cltcd, cltnm, remark
                       ) z
                 GROUP BY z.custcd, z.spjangcd, z.cltcd

                UNION ALL

                -- 2) 매입
                SELECT '2', custcd, spjangcd, cltcd, cltnm,
                       mijdate, mijnum, mijdate,
                       ISNULL(remark, ''), mijamt, 0
                  FROM TB_CA640 WITH(NOLOCK)
                 WHERE custcd = :custcd AND spjangcd = :spjangcd
                   AND mijdate BETWEEN :stdate AND :enddate
                   AND cltcd LIKE :cltcd + '%'
                   AND ISNULL(gubun, '') LIKE :gubun + '%'

                UNION ALL

                -- 3) 지급 (전표 단위 합산, 할인 차감)
                SELECT '3', custcd, spjangcd, cltcd, '',
                       snddate, '', MAX(mijdate),
                       '',
                       0,
                       SUM(ISNULL(hamt,0)) + SUM(ISNULL(eamt,0)) + SUM(ISNULL(samt,0)) + SUM(ISNULL(bamt,0))
                     + SUM(ISNULL(damt,0)) + SUM(ISNULL(gamt,0)) + SUM(ISNULL(sunamt,0)) - SUM(ISNULL(plamt,0))
                  FROM TB_CA642 WITH(NOLOCK)
                 WHERE custcd = :custcd AND spjangcd = :spjangcd
                   AND snddate BETWEEN :rsdate AND :redate
                   AND cltcd LIKE :cltcd + '%'
                 GROUP BY custcd, spjangcd, cltcd, snddate, sndnum
               ) a
          LEFT JOIN TB_XCLIENT x WITH(NOLOCK)
            ON x.custcd = a.custcd AND x.cltcd = a.cltcd
         WHERE (:cltcd = '' OR a.cltcd = :cltcd OR ISNULL(x.cltnm, '') LIKE '%' + :cltcd + '%')
         ORDER BY a.cltcd, a.mijdate, a.mijnum, a.sort
        """;

    return sqlRunner.getRows(sql, p);
  }
}
