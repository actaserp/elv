package mes.app.transaction.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 미수금현황 — 파워빌더(w_tb_da023w_..) 와 동일한 계산으로 재구현.
 *
 * sports 프로젝트에서 가져온 기존 구현은 본사 PostgreSQL 의
 * tb_salesment / tb_banktransit / tb_yearamt / company 를 사용했으나,
 * 파워빌더는 사업체 MSSQL 의 TB_DA023(매출) / TB_DA026(수금) / TB_DA023_END(연마감) /
 * TB_XCLIENT(거래처) 를 사용한다. 데이터 소스 자체가 달라 전면 교체했다.
 *
 * 계산식
 *   전일미수 = 전년말 마감잔액(TB_DA023_END) + 당해 연초~조회시작 전일 매출 - 같은 기간 입금
 *   미수잔액 = 전일미수 + 매출액 - 입금액
 *
 * 요약은 TB_DA026, 상세는 TB_DA026H 를 쓴다. 파워빌더가 그렇게 되어 있어 그대로 따랐다.
 */
@Slf4j
@Service
public class AccountsReceivableListService {

    /** 사업체 MSSQL (TenantContext 라우팅) */
    @Autowired
    SqlRunner sqlRunner;

    /** 수금액 = 13개 금액 컬럼의 합. 파워빌더와 동일한 구성. */
    private static final String RCV_SUM =
            "ISNULL(%1$s.hamt,0) + ISNULL(%1$s.eamt,0) + ISNULL(%1$s.samt,0) + ISNULL(%1$s.bamt,0) + "
          + "ISNULL(%1$s.damt,0) + ISNULL(%1$s.gamt,0) + ISNULL(%1$s.jamt,0) + ISNULL(%1$s.dcamt,0) + "
          + "ISNULL(%1$s.jmar,0) + ISNULL(%1$s.csamt,0) + ISNULL(%1$s.cmar,0) + ISNULL(%1$s.cdmar,0) + "
          + "ISNULL(%1$s.sunamt,0)";

    private static String rcvSum(String alias) {
        return String.format(RCV_SUM, alias);
    }

    /**
     * 현장구분(TB_CA510 com_cls='813')을 담는 TB_E601 의 컬럼명.
     * 이름상 actgubun 이 유력하나 확정 전이라 한 곳에 모아둔다. 다르면 여기만 바꾸면 된다.
     */
    private static final String SITE_GUBUN_COL = "actgubun";

    /** 지정한 별칭의 actcd 가 해당 현장구분에 속하는지 검사하는 조건절 */
    private static String siteGubunCond(String alias) {
        return " AND (:siteGubun = '%' OR EXISTS ("
             + " SELECT 1 FROM TB_E601 e6 WITH(NOLOCK)"
             + "  WHERE e6.custcd = " + alias + ".custcd AND e6.spjangcd = " + alias + ".spjangcd"
             + "    AND e6.actcd = " + alias + ".actcd AND e6." + SITE_GUBUN_COL + " = :siteGubun)) ";
    }

    /** spjangcd 로 custcd 조회 (파워빌더의 as_custcd) */
    public String getCustcd(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        Map<String, Object> row = sqlRunner.getRow(
                "SELECT custcd FROM tb_xa012 WHERE spjangcd = :spjangcd", param);
        if (row == null || row.get("custcd") == null) return null;
        return String.valueOf(row.get("custcd")).trim();
    }

    private static String orAll(String v) {
        return (v == null || v.isBlank()) ? "%" : v.trim();
    }

    // ════════════════════════════════════════════════════════
    //  미수금현황 집계 (상단 그리드)
    // ════════════════════════════════════════════════════════
    public List<Map<String, Object>> getTotalList(String startDate, String endDate, String spjangcd,
                                                  String cltcd, String gubun, String billgubun, String perid,
                                                  String divicd, String siteGubun,
                                                  boolean salesBasis, boolean balanceOnly) {

        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("custcd",    custcd);
        p.addValue("spjangcd",  spjangcd);
        p.addValue("stdate",    startDate);
        p.addValue("enddate",   endDate);
        // 파워빌더 화면의 일자 입력이 한 줄이라 매출기간과 입금기간에 같은 값이 들어간다.
        p.addValue("rsdate",    startDate);
        p.addValue("redate",    endDate);
        p.addValue("gubun",     orAll(gubun));
        p.addValue("billgubun", orAll(billgubun));
        p.addValue("perid",     orAll(perid));
        p.addValue("divicd",    orAll(divicd));
        p.addValue("siteGubun", orAll(siteGubun));
        p.addValue("cltcd",     orAll(cltcd));

        String misOutstanding =
                "ISNULL(misamt,0) - (ISNULL(hamt,0) + ISNULL(eamt,0) + ISNULL(samt,0) + ISNULL(bamt,0) + "
              + "ISNULL(damt,0) + ISNULL(gamt,0) + ISNULL(jamt,0) + ISNULL(dcamt,0) + ISNULL(jmar,0) + "
              + "ISNULL(csamt,0) + ISNULL(cmar,0) + ISNULL(cdmar,0))";

        String sql = """
                SELECT z.cltcd,
                       MAX(x.cltnm)   AS cltnm,
                       MAX(x.saupnum) AS saupnum,
                       ISNULL(SUM(z.beamt),  0) AS beamt,
                       ISNULL(SUM(z.misamt), 0) AS misamt,
                       ISNULL(SUM(z.iamt),   0) AS rcvamt,
                       (ISNULL(SUM(z.beamt), 0) + ISNULL(SUM(z.misamt), 0)) - ISNULL(SUM(z.iamt), 0) AS resuamt,
                       -- 비고 = 미결 건수. 파워빌더 화면이 안쪽 count 합계를 그대로 보여준다.
                       ISNULL(SUM(z.remark), 0) AS remark,
                       CAST(:stdate  AS VARCHAR(8)) AS frdate,
                       CAST(:enddate AS VARCHAR(8)) AS todate
                  FROM (
                        /* 미결 건수 (금액 기여 없음) */
                        SELECT a.custcd, a.cltcd, 0 beamt, 0 AS misamt, 0 iamt,
                               COUNT(a.custcd) AS remark, '2' AS sangflag
                          FROM TB_DA023_end a WITH(NOLOCK), TB_E601 b
                         WHERE a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.cltcd = b.cltcd
                           AND a.custcd = :custcd AND a.spjangcd = :spjangcd
                           AND a.year = LEFT(:enddate, 4)
                           AND (a.billgubun = :billgubun OR :billgubun = '%')
                           AND (a.gubun     = :gubun     OR :gubun     = '%')
                           AND __MIS_OUTSTANDING__ <> 0
                         GROUP BY a.custcd, a.cltcd

                        UNION ALL

                        /* 기간내 매출 */
                        SELECT custcd, cltcd, 0 beamt, ISNULL(SUM(misamt),0) misamt, 0 iamt,
                               COUNT(custcd) AS remark, ISNULL(sangflag,'2') AS sangflag
                          FROM TB_DA023 WITH(NOLOCK)
                         WHERE custcd = :custcd AND spjangcd = :spjangcd
                           AND misdate BETWEEN :stdate AND :enddate
                           AND (billgubun = :billgubun OR :billgubun = '%')
                           AND (gubun     = :gubun     OR :gubun     = '%')
                           AND (perid     = :perid     OR :perid     = '%')
                           AND (divicd    = :divicd    OR :divicd    = '%')
                           __SITE_GUBUN_PLAIN__
                           AND (sangflag IS NULL OR LEN(sangflag) = 0 OR sangflag = '')
                         GROUP BY custcd, cltcd, sangflag

                        UNION ALL

                        /* 기간내 입금 */
                        SELECT a.custcd, a.cltcd, 0 beamt, 0 misamt,
                               SUM(__RCV_B__) AS iamt,
                               COUNT(a.misdate) * -1 AS remark, ISNULL(MAX(a.sangflag),'2') AS sangflag
                          FROM TB_DA023 a WITH(NOLOCK)
                          LEFT OUTER JOIN TB_DA026 b
                                 ON (a.custcd = b.custcd AND a.spjangcd = b.spjangcd
                                 AND a.misdate = b.misdate AND a.misnum = b.misnum AND a.cltcd = b.cltcd)
                         WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd
                           __RCV_PERIOD__
                           AND (a.billgubun = :billgubun OR :billgubun = '%')
                           AND (a.gubun     = :gubun     OR :gubun     = '%')
                           AND (a.perid     = :perid     OR :perid     = '%')
                           AND (a.divicd    = :divicd    OR :divicd    = '%')
                           __SITE_GUBUN_A__
                           AND (a.sangflag IS NULL OR LEN(a.sangflag) = 0 OR a.sangflag = '')
                         GROUP BY a.custcd, a.cltcd

                        UNION ALL

                        /* 전일미수 : 당해 연초 ~ 조회시작 전일 매출 */
                        SELECT custcd, cltcd, ISNULL(SUM(misamt),0) beamt, 0 misamt, 0 iamt,
                               COUNT(misdate) AS remark, ISNULL(sangflag,'2') AS sangflag
                          FROM TB_DA023 WITH(NOLOCK)
                         WHERE custcd = :custcd AND spjangcd = :spjangcd
                           AND misdate BETWEEN LEFT(:stdate,4) + '0101'
                                           AND CONVERT(VARCHAR(8), DATEADD(day, -1, CONVERT(datetime, :stdate)), 112)
                           AND (billgubun = :billgubun OR :billgubun = '%')
                           AND (gubun     = :gubun     OR :gubun     = '%')
                           AND (perid     = :perid     OR :perid     = '%')
                           AND (divicd    = :divicd    OR :divicd    = '%')
                           __SITE_GUBUN_PLAIN__
                           AND (sangflag IS NULL OR LEN(sangflag) = 0 OR sangflag = '')
                         GROUP BY custcd, cltcd, sangflag

                        UNION ALL

                        /* 전일미수 : 당해 연초 ~ 조회시작 전일 입금 (차감) */
                        SELECT a.custcd, a.cltcd,
                               SUM(__RCV_A__) * -1 AS beamt,
                               0 misamt, 0 iamt,
                               COUNT(a.misdate) * -1 AS remark, ISNULL(b.sangflag,'2') AS sangflag
                          FROM TB_DA026 a WITH(NOLOCK)
                          LEFT OUTER JOIN TB_DA023 b
                                 ON (a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.cltcd = b.cltcd
                                 AND a.misdate = b.misdate AND a.misnum = b.misnum
                                 AND (b.perid  = :perid  OR :perid  = '%')
                                 AND (b.divicd = :divicd OR :divicd = '%'))
                           __SITE_GUBUN_B__
                         WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd
                           __RCV_PRIOR_PERIOD__
                           AND (b.billgubun = :billgubun OR :billgubun = '%')
                           AND (b.gubun     = :gubun     OR :gubun     = '%')
                           AND (b.sangflag IS NULL OR LEN(b.sangflag) = 0 OR b.sangflag = '')
                         GROUP BY a.custcd, a.spjangcd, a.cltcd, b.sangflag

                        UNION ALL

                        /* 전일미수 : 전년말 마감 이월 */
                        SELECT custcd, cltcd, SUM(misamt) AS beamt, 0 misamt, 0 iamt,
                               COUNT(misdate) AS remark, '2' AS sangflag
                          FROM TB_DA023_END WITH(NOLOCK)
                         WHERE custcd = :custcd AND spjangcd = :spjangcd
                           AND year = LEFT(:stdate, 4)
                           AND (billgubun = :billgubun OR :billgubun = '%')
                           AND (gubun     = :gubun     OR :gubun     = '%')
                           __SITE_GUBUN_END__
                         GROUP BY custcd, spjangcd, cltcd
                       ) z,
                       TB_XCLIENT AS x WITH(NOLOCK)
                 WHERE z.cltcd = x.cltcd
                   AND ((z.cltcd LIKE '%' + :cltcd + '%' OR :cltcd = '%')
                     OR (x.cltnm LIKE '%' + :cltcd + '%' OR :cltcd = '%'))
                   AND (z.sangflag NOT IN ('1','0') OR z.sangflag IS NULL)
                 GROUP BY z.cltcd
                __BALANCE_ONLY__
                 ORDER BY resuamt DESC
                """
                // 화면의 '잔액체크' — 미수잔액이 0 인 거래처는 제외한다
                .replace("__BALANCE_ONLY__", balanceOnly
                        ? " HAVING (ISNULL(SUM(z.beamt),0) + ISNULL(SUM(z.misamt),0)) - ISNULL(SUM(z.iamt),0) <> 0 "
                        : "")
                .replace("__MIS_OUTSTANDING__", misOutstanding)
                .replace("__RCV_A__", rcvSum("a"))
                .replace("__RCV_B__", rcvSum("b"))
                // 현장구분 — TB_DA023 / TB_DA023_END 의 actcd 를 TB_E601 로 확인한다
                // 화면의 '매출기준' — 켜면 입금도 매출일자(misdate) 기준으로 거른다.
                // 끄면 입금은 자기 날짜(rcvdate) 기준. 파워빌더가 as_rsdate/as_redate 를
                // 별도 파라미터로 둔 이유가 이 전환이다.
                .replace("__RCV_PERIOD__", salesBasis
                        ? " AND a.misdate BETWEEN :stdate AND :enddate "
                        : " AND b.rcvdate BETWEEN :rsdate AND :redate ")
                .replace("__RCV_PRIOR_PERIOD__", salesBasis
                        ? " AND b.misdate BETWEEN LEFT(:stdate,4) + '0101'"
                        + "                   AND CONVERT(VARCHAR(8), DATEADD(day, -1, CONVERT(datetime, :stdate)), 112) "
                        : " AND a.rcvdate BETWEEN LEFT(:rsdate,4) + '0101'"
                        + "                   AND CONVERT(VARCHAR(8), DATEADD(day, -1, CONVERT(datetime, :rsdate)), 112) ")
                .replace("__SITE_GUBUN_PLAIN__", siteGubunCond("TB_DA023"))
                .replace("__SITE_GUBUN_END__", siteGubunCond("TB_DA023_END"))
                .replace("__SITE_GUBUN_A__", siteGubunCond("a"))
                .replace("__SITE_GUBUN_B__", siteGubunCond("b"));

        return sqlRunner.getRows(sql, p);
    }

    // ════════════════════════════════════════════════════════
    //  미수금현황 상세 (거래처 더블클릭 팝업)
    // ════════════════════════════════════════════════════════
    public List<Map<String, Object>> getDetailList(String startDate, String endDate, String spjangcd,
                                                   String cltcd, String gubun, String billgubun) {

        String custcd = getCustcd(spjangcd);
        if (custcd == null || cltcd == null || cltcd.isBlank()) return List.of();

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("custcd",    custcd);
        p.addValue("spjangcd",  spjangcd);
        p.addValue("stdate",    startDate);
        p.addValue("enddate",   endDate);
        // 파워빌더의 as_yyyymm — 마감연도/당해연도 기준. 조회 시작일의 연도를 쓴다.
        p.addValue("yyyymm",    startDate == null ? null : startDate.substring(0, 4));
        p.addValue("cltcd",     cltcd.trim());
        p.addValue("gubun",     orAll(gubun));
        p.addValue("billgubun", orAll(billgubun));

        String sql = """
                SELECT a.cltcd,
                       ISNULL(a.actcd, '') AS actcd,
                       MAX(ISNULL(e.actnm, '')) AS actnm,
                       a.misdate, a.misnum, a.misdate2, a.remark,
                       LEFT(a.misdate2, 6) AS mismon,
                       ISNULL(SUM(a.misamt), 0) AS misamt,
                       ISNULL(SUM(a.iamt),   0) AS rcvamt,
                       ISNULL(SUM(a.misamt), 0) - ISNULL(SUM(a.iamt), 0) AS resuamt,
                       a.sort,
                       CAST(:stdate  AS VARCHAR(8)) AS stdate,
                       CAST(:enddate AS VARCHAR(8)) AS enddate
                  FROM (
                        /* sort 0 : 전일잔액 (마감 + 당해누적매출 - 당해누적입금) */
                        SELECT z.custcd, z.spjangcd, z.actcd, '0' AS sort, z.cltcd,
                               '' AS misdate, '' AS misnum, '' AS misdate2,
                               SUM(z.misamt) AS misamt, SUM(z.iamt) AS iamt,
                               '전일잔액' AS remark
                          FROM (
                                SELECT custcd, spjangcd, cltcd, actcd,
                                       ISNULL(SUM(misamt),0) misamt, 0 iamt
                                  FROM TB_DA023_end WITH(NOLOCK)
                                 WHERE custcd = :custcd AND spjangcd = :spjangcd
                                   AND year = LEFT(:yyyymm, 4)
                                   AND (gubun     = :gubun     OR :gubun     = '%')
                                   AND (billgubun = :billgubun OR :billgubun = '%')
                                 GROUP BY custcd, spjangcd, cltcd, actcd

                                UNION ALL

                                SELECT custcd, spjangcd, cltcd, actcd,
                                       ISNULL(SUM(misamt),0) misamt, 0 iamt
                                  FROM TB_DA023 WITH(NOLOCK)
                                 WHERE custcd = :custcd AND spjangcd = :spjangcd
                                   AND (gubun     = :gubun     OR :gubun     = '%')
                                   AND (billgubun = :billgubun OR :billgubun = '%')
                                   AND misdate >= LEFT(:yyyymm,4) + '0101' AND misdate < :stdate
                                 GROUP BY custcd, spjangcd, cltcd, actcd

                                UNION ALL

                                SELECT a.custcd, a.spjangcd, a.cltcd, b.actcd,
                                       0 AS misamt, SUM(__RCV_A__) AS iamt
                                  FROM TB_DA026h a WITH(NOLOCK)
                                  LEFT OUTER JOIN TB_DA023 b
                                         ON (a.custcd = b.custcd AND a.spjangcd = b.spjangcd
                                         AND a.cltcd = b.cltcd AND a.misdate = b.misdate AND a.misnum = b.misnum)
                                 WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd
                                   AND (b.gubun     = :gubun     OR :gubun     = '%')
                                   AND (b.billgubun = :billgubun OR :billgubun = '%')
                                   AND LEFT(a.rcvdate,4) >= LEFT(:yyyymm,4) AND a.rcvdate < :stdate
                                 GROUP BY a.custcd, a.spjangcd, a.cltcd, b.actcd, a.rcvdate, a.rcvnum
                               ) z
                         GROUP BY z.custcd, z.spjangcd, z.cltcd, z.actcd

                        UNION ALL

                        /* sort 1 : 기간내 매출 */
                        SELECT custcd, spjangcd, actcd, '1' AS sort, cltcd,
                               misdate, misnum, misdate AS misdate2,
                               ISNULL(misamt, 0) misamt, 0 iamt,
                               (SELECT TOP 1 pname FROM TB_DA024
                                 WHERE custcd = :custcd AND spjangcd = :spjangcd
                                   AND misdate = TB_DA023.misdate AND misnum = TB_DA023.misnum
                                 ORDER BY seq) AS remark
                          FROM TB_DA023 WITH(NOLOCK)
                         WHERE custcd = :custcd AND spjangcd = :spjangcd
                           AND (gubun     = :gubun     OR :gubun     = '%')
                           AND (billgubun = :billgubun OR :billgubun = '%')
                           AND misdate BETWEEN :stdate AND :enddate

                        UNION ALL

                        /* sort 2 : 기간내 입금 (misdate2 = 원 매출일자, 월 그룹핑용) */
                        SELECT a.custcd, a.spjangcd,
                               (SELECT actcd FROM TB_DA023
                                 WHERE cltcd = a.cltcd AND misdate = a.misdate AND misnum = a.misnum) AS actcd,
                               '2' AS sort, a.cltcd,
                               a.rcvdate AS misdate, a.rcvnum AS misnum, a.misdate AS misdate2,
                               0 AS misamt, __RCV_A__ AS iamt,
                               a.remark
                          FROM TB_DA026h a WITH(NOLOCK)
                         WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd
                           AND a.rcvdate BETWEEN :stdate AND :enddate
                       ) a
                  LEFT OUTER JOIN TB_E601 e
                         ON (e.custcd = a.custcd AND e.spjangcd = a.spjangcd AND e.actcd = a.actcd)
                 WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd AND a.cltcd = :cltcd
                 GROUP BY a.custcd, a.spjangcd, a.cltcd, a.actcd, a.misdate, a.misdate2, a.misnum, a.remark, a.sort
                 ORDER BY a.sort, a.misdate, a.misnum
                """
                .replace("__RCV_A__", rcvSum("a"));

        return sqlRunner.getRows(sql, p);
    }
}
