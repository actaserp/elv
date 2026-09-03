package mes.app.transaction.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 미수금 잔액명세 (거래처잔액명세) — 파워빌더 w_input_da023w_01 과 동일하게 재구현.
 *
 * 기존 구현은 sports 에서 가져온 것이라 본사 PostgreSQL 의
 * tb_yearamt / tb_salesment / tb_banktransit / company 를 썼으나,
 * 파워빌더는 사업체 MSSQL 의 TB_DA023(매출) / TB_DA026·TB_DA026H(수금) /
 * TB_DA023_END(연마감) / TB_XCLIENT(거래처) 를 쓴다. 데이터 소스를 전면 교체했다.
 *
 * 구성 (파워빌더의 sort 값 그대로)
 *   sort 1 전일잔액 = 전년마감(TB_DA023_END) + 당해 연초~조회시작 전일 매출 - 같은 기간 수금
 *   sort 2 기간내 매출   (TB_DA023)
 *   sort 3 기간내 입금   (TB_DA026H)
 *   sort 4 기간내 선수금 (TB_DA026H.sunamt > 0)
 */
@Slf4j
@Service
public class CompBalanceDetailService {

    /** 사업체 MSSQL (TenantContext 라우팅) */
    @Autowired
    SqlRunner sqlRunner;

    /**
     * 수금액 합계.
     * 파워빌더가 구간마다 구성을 달리 쓴다 — 전일잔액(TB_DA026)은 sunamt 를 포함하고,
     * 기간내 입금(TB_DA026H)은 sunamt 를 빼고 sort 4 에서 '선수금' 으로 따로 세운다.
     */
    private static final String RCV_12 =
            "ISNULL(%1$shamt,0) + ISNULL(%1$seamt,0) + ISNULL(%1$ssamt,0) + ISNULL(%1$sjamt,0) + "
          + "ISNULL(%1$sbamt,0) + ISNULL(%1$sdamt,0) + ISNULL(%1$sgamt,0) + ISNULL(%1$sdcamt,0) + "
          + "ISNULL(%1$sjmar,0) + ISNULL(%1$scsamt,0) + ISNULL(%1$scmar,0) + ISNULL(%1$scdmar,0)";

    /** @param prefix 별칭이 있으면 "a." 처럼 점까지 넘긴다. 없으면 빈 문자열. */
    private static String rcv12(String prefix) {
        return String.format(RCV_12, prefix);
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

    /**
     * @param cltcd 거래처. 미입력이면 '%'(전체). 코드 또는 거래처명으로 찾는다.
     * @param gubun 매출구분(TB_DA020.artcd). 미입력이면 전체.
     */
    public List<Map<String, Object>> getList(String startDate, String endDate, String spjangcd,
                                             String cltcd, String gubun) {

        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("custcd",   custcd);
        p.addValue("spjangcd", spjangcd);
        p.addValue("stdate",   startDate);
        p.addValue("enddate",  endDate);
        // 파워빌더 화면의 일자 입력이 한 줄이라 매출기간과 입금기간에 같은 값이 들어간다.
        p.addValue("rsdate",   startDate);
        p.addValue("redate",   endDate);
        p.addValue("cltcd",    (cltcd == null || cltcd.isBlank()) ? "%" : cltcd.trim());
        // 파워빌더가 gubun LIKE :as_gubun + '%' 라 빈 값이면 전체가 된다.
        p.addValue("gubun",    gubun == null ? "" : gubun.trim());

        String sql = """
                /*
                 * carry = 파워빌더 화면의 '전일이월'.
                 * SQL 이 아니라 윈도우 스크립트(toolbar.ue_retrieve)가 Retrieve 후 InsertRow 로
                 * 끼워 넣는 행이라 원본 계산식을 그대로 옮겼다.
                 *   SUM(z.misamt - ISNULL(b.hamt+b.eamt+b.samt+b.jamt+b.bamt+b.gamt, 0))
                 *   WHERE z.misdate < :stdate  (0 보다 클 때만 행 생성)
                 * ★ 수금 차감이 6개 컬럼뿐이다. damt·dcamt·jmar·csamt·cmar·cdmar·sunamt 는
                 *   원본에 빠져 있는데 그대로 뒀다. 경기엘리베이터 실화면 6건과 금액 일치 확인.
                 */
                WITH clt AS (
                    SELECT cltcd FROM TB_XCLIENT WITH(NOLOCK)
                     WHERE custcd = :custcd
                       AND (:cltcd = '%' OR cltcd LIKE '%' + :cltcd + '%' OR cltnm LIKE '%' + :cltcd + '%')
                ),
                act AS (
                    SELECT actcd FROM TB_E601 WITH(NOLOCK)
                     WHERE custcd = :custcd AND spjangcd = :spjangcd
                       AND (:cltcd = '%' OR actcd LIKE '%' + :cltcd + '%' OR actnm LIKE '%' + :cltcd + '%')
                ),
                carry AS (
                    SELECT z.cltcd,
                           SUM(z.misamt - ISNULL(b.hamt + b.eamt + b.samt + b.jamt + b.bamt + b.gamt, 0)) AS amt
                      FROM TB_DA023 z WITH(NOLOCK)
                      LEFT OUTER JOIN TB_DA026 b WITH(NOLOCK)
                             ON (z.custcd = b.custcd AND z.spjangcd = b.spjangcd AND z.cltcd = b.cltcd
                             AND z.misdate = b.misdate AND z.misnum = b.misnum)
                     WHERE z.misdate < :stdate
                       AND (z.cltcd IN (SELECT cltcd FROM clt) OR z.actcd IN (SELECT actcd FROM act))
                     GROUP BY z.cltcd
                    HAVING SUM(z.misamt - ISNULL(b.hamt + b.eamt + b.samt + b.jamt + b.bamt + b.gamt, 0)) > 0
                )
                SELECT a.cltcd,
                       ISNULL(x.cltnm, '')   AS cltnm,
                       ISNULL(x.saupnum, '') AS saupnum,
                       a.misdate,
                       a.misnum,
                       a.misdate2,
                       a.remark,
                       ISNULL(a.misamt, 0) AS misamt,
                       ISNULL(a.rcvamt, 0) AS rcvamt,
                       ISNULL(a.plamt,  0) AS plamt,
                       -- 잔액 = 전일이월 + 거래처별 (매출 - 입금) 누계.
                       -- 캡처의 모든 행이 이 식과 일치한다 (전일이월 행에서 시작해 한 줄씩 누적).
                       SUM(ISNULL(a.carry, 0) + ISNULL(a.misamt, 0) - ISNULL(a.rcvamt, 0))
                           OVER (PARTITION BY a.cltcd
                                 ORDER BY a.misdate, a.misnum, a.sort, a.misamt
                                 ROWS UNBOUNDED PRECEDING) AS balance,
                       a.bigo,
                       a.sangflag,
                       a.actcd,
                       ISNULL(e.actnm, '') AS actnm,
                       a.sort,
                       CAST(:stdate  AS VARCHAR(8)) AS stdate,
                       CAST(:enddate AS VARCHAR(8)) AS enddate
                  FROM (
                        /* sort 0 : 전일이월. 금액 칸은 비고 잔액만 세운다 (파워빌더 화면과 동일) */
                        SELECT '0' AS sort, :custcd AS custcd, :spjangcd AS spjangcd, c.cltcd,
                               '' AS misdate, '' AS misnum, '' AS misdate2,
                               '전일이월' AS remark,
                               0 AS misamt, 0 AS rcvamt, 0 AS plamt,
                               '' AS actcd, '' AS bigo, '' AS sangflag,
                               c.amt AS carry
                          FROM carry c
                         WHERE c.amt <> 0

                        UNION ALL

                        /* sort 1 : 전일잔액 */
                        SELECT '1' AS sort, z.custcd, z.spjangcd, z.cltcd,
                               '' AS misdate, '' AS misnum, '' AS misdate2,
                               '전일잔액' AS remark,
                               SUM(z.beamt) AS misamt,
                               0 AS rcvamt, 0 AS plamt,
                               z.actcd, '' AS bigo, z.sangflag,
                               0 AS carry
                          FROM (
                                /* 전년 마감잔액 */
                                SELECT custcd, spjangcd, cltcd, misamt AS beamt, actcd, '' AS sangflag
                                  FROM TB_DA023_END WITH(NOLOCK)
                                 WHERE custcd = :custcd AND spjangcd = :spjangcd
                                   AND year = LEFT(:stdate, 4)
                                   AND (cltcd IN (SELECT cltcd FROM clt) OR actcd IN (SELECT actcd FROM act))
                                   AND gubun LIKE :gubun + '%'

                                UNION ALL

                                /* 당해 연초 ~ 조회시작 전일 매출 */
                                SELECT custcd, spjangcd, cltcd, SUM(misamt) AS beamt, actcd, sangflag
                                  FROM TB_DA023 WITH(NOLOCK)
                                 WHERE custcd = :custcd AND spjangcd = :spjangcd
                                   AND misdate BETWEEN LEFT(:stdate, 4) + '0101'
                                                   AND CONVERT(VARCHAR(8), DATEADD(day, -1, CONVERT(datetime, :stdate)), 112)
                                   AND (cltcd IN (SELECT cltcd FROM clt) OR actcd IN (SELECT actcd FROM act))
                                   AND gubun LIKE :gubun + '%'
                                 GROUP BY custcd, spjangcd, cltcd, actcd, sangflag

                                UNION ALL

                                /* 같은 기간 수금 (차감). 파워빌더가 여기엔 gubun 조건을 걸지 않는다 */
                                SELECT custcd, spjangcd, cltcd,
                                       SUM(__RCV_12__ + ISNULL(sunamt, 0)) * -1 AS beamt,
                                       actcd, '' AS sangflag
                                  FROM TB_DA026 WITH(NOLOCK)
                                 WHERE custcd = :custcd AND spjangcd = :spjangcd
                                   AND rcvdate BETWEEN LEFT(:stdate, 4) + '0101'
                                                   AND CONVERT(VARCHAR(8), DATEADD(day, -1, CONVERT(datetime, :stdate)), 112)
                                   AND (cltcd IN (SELECT cltcd FROM clt) OR actcd IN (SELECT actcd FROM act))
                                 GROUP BY custcd, spjangcd, cltcd, actcd
                               ) z
                         GROUP BY z.custcd, z.spjangcd, z.cltcd, z.actcd, z.sangflag

                        UNION ALL

                        /* sort 2 : 기간내 매출 */
                        SELECT '2' AS sort, custcd, spjangcd, cltcd,
                               misdate, misnum, misdate AS misdate2,
                               remark, misamt, 0 AS rcvamt, 0 AS plamt,
                               actcd, bigo, sangflag, 0 AS carry
                          FROM TB_DA023 WITH(NOLOCK)
                         WHERE custcd = :custcd AND spjangcd = :spjangcd
                           AND misdate BETWEEN :stdate AND :enddate
                           AND (cltcd IN (SELECT cltcd FROM clt) OR actcd IN (SELECT actcd FROM act))
                           AND gubun LIKE :gubun + '%'

                        UNION ALL

                        /* sort 3 : 기간내 입금 (선수금 제외) */
                        SELECT '3' AS sort, custcd, spjangcd, cltcd,
                               rcvdate AS misdate, misnum, misdate AS misdate2,
                               remark, 0 AS misamt,
                               __RCV_12_NA__ AS rcvamt,
                               plamt,
                               actcd, '' AS bigo, '' AS sangflag, 0 AS carry
                          FROM TB_DA026H WITH(NOLOCK)
                         WHERE custcd = :custcd AND spjangcd = :spjangcd
                           AND rcvdate BETWEEN :rsdate AND :redate
                           AND (cltcd IN (SELECT cltcd FROM clt) OR actcd IN (SELECT actcd FROM act))

                        UNION ALL

                        /* sort 4 : 기간내 선수금 */
                        SELECT '4' AS sort, custcd, spjangcd, cltcd,
                               rcvdate AS misdate, misnum, misdate AS misdate2,
                               '선수금' AS remark, 0 AS misamt,
                               ISNULL(sunamt, 0) AS rcvamt, 0 AS plamt,
                               actcd, '' AS bigo, '' AS sangflag, 0 AS carry
                          FROM TB_DA026H WITH(NOLOCK)
                         WHERE custcd = :custcd AND spjangcd = :spjangcd
                           AND rcvdate BETWEEN :rsdate AND :redate
                           AND (cltcd IN (SELECT cltcd FROM clt) OR actcd IN (SELECT actcd FROM act))
                           AND ISNULL(sunamt, 0) > 0
                       ) a
                  -- TB_XCLIENT 에는 spjangcd 가 없다. custcd + cltcd 로만 잡는다.
                  LEFT OUTER JOIN TB_XCLIENT x WITH(NOLOCK)
                         ON x.custcd = a.custcd AND x.cltcd = a.cltcd
                  LEFT OUTER JOIN TB_E601 e WITH(NOLOCK)
                         ON e.custcd = a.custcd AND e.spjangcd = a.spjangcd AND e.actcd = a.actcd
                 WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd
                   -- 검색어 하나로 거래처(코드/명) 와 현장(코드/명) 을 모두 본다.
                   -- 파워빌더는 cltflag 토글로 DataWindow 를 바꿔 끼지만 웹은 한 화면으로 합쳤다.
                   AND (a.cltcd IN (SELECT cltcd FROM clt) OR a.actcd IN (SELECT actcd FROM act))
                 ORDER BY a.cltcd, a.misdate, a.misnum, a.sort, a.misamt
                """
                .replace("__RCV_12__",    rcv12(""))
                .replace("__RCV_12_NA__", rcv12(""));

        return this.sqlRunner.getRows(sql, p);
    }

    // ════════════════════════════════════════════════════════════════
    //  구분 1·2·3 : 미수내역 (d_tb_da023w_01_03 / _01_04 / _01_05)
    //
    //  셋 다 출력 컬럼이 같고 (코드·거래처명·현장명·계약·부서·담당자·일자·매출액·비고),
    //  전기이월을 잡는 방식만 다르다.
    //    _01_03 : 연마감 + 연초~전일 매출·수금        (5 브랜치)
    //    _01_04 : _01_03 + 연마감에 '미결제만' 조건    (5 브랜치)
    //    _01_05 : 연마감만                            (3 브랜치)
    //
    //  ★ 파워빌더가 바깥 SELECT 에서 misamt ↔ resuamt 별칭을 뒤바꿔 놓았다.
    //     화면의 '매출액' 은 resuamt 에, 미수잔액은 misamt 에 담긴다. 원본 그대로 유지했다.
    // ════════════════════════════════════════════════════════════════
    public List<Map<String, Object>> getMisuList(String dwSuffix,
                                                 String startDate, String endDate,
                                                 String rcvStart, String rcvEnd,
                                                 String spjangcd, String cltcd,
                                                 String gubun, String divicd) {

        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("custcd",   custcd);
        p.addValue("spjangcd", spjangcd);
        p.addValue("stdate",   startDate);
        p.addValue("enddate",  endDate);
        // 구분 2(별도기간)만 수금기간을 따로 받는다. 나머지는 매출기간과 같다.
        p.addValue("rsdate",   (rcvStart == null || rcvStart.isBlank()) ? startDate : rcvStart);
        p.addValue("redate",   (rcvEnd   == null || rcvEnd.isBlank())   ? endDate   : rcvEnd);
        p.addValue("cltcd",    (cltcd  == null || cltcd.isBlank())  ? "%" : cltcd.trim());
        p.addValue("gubun",    gubun  == null ? "" : gubun.trim());
        p.addValue("divicd",   divicd == null ? "" : divicd.trim());

        String inner = misuInnerBranches(dwSuffix);

        String sql = ("""
                SELECT a.custcd, a.spjangcd, a.cltcd,
                       ISNULL(x.cltnm, '') AS cltnm,
                       a.misdate, a.misnum, a.remark,
                       -- 파워빌더 원본의 별칭 스왑을 그대로 둔다 (화면 '매출액' = resuamt)
                       a.resuamt AS misamt,
                       a.rcvamt  AS rcvamt,
                       a.misamt  AS resuamt,
                       a.actcd,
                       ISNULL(e.actnm, '') AS actnm,
                       a.sangflag,
                       (SELECT TOP 1 contg FROM TB_E101 WITH(NOLOCK)
                         WHERE custcd = a.custcd AND spjangcd = a.spjangcd AND actcd = a.actcd
                         ORDER BY contdate DESC) AS contg,
                       (SELECT divinm FROM TB_JC002 WITH(NOLOCK)
                         WHERE custcd = a.custcd AND spjangcd = a.spjangcd
                           AND ISNULL(divicd, '') = (SELECT TOP 1 divicd FROM TB_JA001 WITH(NOLOCK)
                                                      WHERE custcd = a.custcd AND spjangcd = a.spjangcd
                                                        AND perid = 'p' + a.perid)) AS divinm,
                       a.pernm,
                       CAST(:stdate  AS VARCHAR(8)) AS stdate,
                       CAST(:enddate AS VARCHAR(8)) AS enddate
                  FROM (
                        SELECT z.custcd, z.spjangcd, z.cltcd, z.misdate, z.misnum,
                               MAX(z.remark) AS remark,
                               SUM(z.beamt)  AS beamt,
                               SUM(z.misamt) AS misamt,
                               SUM(z.rcvamt) AS rcvamt,
                               SUM(z.beamt) + SUM(z.misamt) - SUM(z.rcvamt) AS resuamt,
                               MAX(z.actcd)  AS actcd,
                               MAX(z.perid)  AS perid,
                               (SELECT pernm FROM TB_JA001 WITH(NOLOCK)
                                 WHERE custcd = z.custcd AND spjangcd = z.spjangcd
                                   AND perid = 'p' + MAX(z.perid)) AS pernm,
                               MAX(z.sangflag) AS sangflag
                          FROM (
                        __INNER__
                               ) z
                         GROUP BY z.custcd, z.spjangcd, z.cltcd, z.misdate, z.misnum
                       ) a
                  LEFT OUTER JOIN TB_XCLIENT x WITH(NOLOCK)
                         ON x.custcd = a.custcd AND x.cltcd = a.cltcd
                  LEFT OUTER JOIN TB_E601 e WITH(NOLOCK)
                         ON e.custcd = a.custcd AND e.spjangcd = a.spjangcd AND e.actcd = a.actcd
                 WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd
                   AND (:cltcd = '%'
                     OR a.cltcd            LIKE '%' + :cltcd + '%'
                     OR ISNULL(x.cltnm, '') LIKE '%' + :cltcd + '%'
                     OR a.actcd            LIKE '%' + :cltcd + '%'
                     OR ISNULL(e.actnm, '') LIKE '%' + :cltcd + '%')
                   AND a.resuamt <> 0
                   AND (a.sangflag NOT IN ('1','0') OR a.sangflag IS NULL)
                 ORDER BY a.custcd, a.spjangcd, a.cltcd, a.misdate, a.misnum
                """).replace("__INNER__", inner);

        return this.sqlRunner.getRows(sql, p);
    }

    /** 미수내역 3종의 안쪽 UNION 구성. dwSuffix = "03" | "04" | "05" */
    private static String misuInnerBranches(String dwSuffix) {
        String rcv = rcv12("") + " + ISNULL(sunamt, 0)";
        String rcvA = rcv12("a.") + " + ISNULL(a.sunamt, 0)";
        String rcvB = rcv12("b.") + " + ISNULL(b.sunamt, 0)";

        // 공통 : 기간내 매출 / 기간내 수금
        String base = """
                                SELECT custcd, spjangcd, cltcd, misdate, misnum, remark,
                                       0 AS beamt, misamt, 0 AS rcvamt,
                                       actcd,
                                       (SELECT TOP 1 perid FROM TB_E601 WITH(NOLOCK)
                                         WHERE custcd = :custcd AND spjangcd = :spjangcd AND actcd = TB_DA023.actcd) AS perid,
                                       ISNULL(sangflag, '2') AS sangflag
                                  FROM TB_DA023 WITH(NOLOCK)
                                 WHERE custcd = :custcd AND spjangcd = :spjangcd
                                   AND misdate BETWEEN :stdate AND :enddate
                                   AND ISNULL(gubun,  '') LIKE :gubun  + '%'
                                   AND ISNULL(divicd, '') LIKE :divicd + '%'

                                UNION ALL

                                SELECT a.custcd, a.spjangcd, a.cltcd, a.misdate, a.misnum, b.remark,
                                       0 AS beamt, 0 AS misamt, __RCV_B__ AS rcvamt,
                                       a.actcd,
                                       (SELECT TOP 1 perid FROM TB_E601 WITH(NOLOCK)
                                         WHERE custcd = :custcd AND spjangcd = :spjangcd AND actcd = a.actcd) AS perid,
                                       ISNULL(a.sangflag, '2') AS sangflag
                                  FROM TB_DA023 a WITH(NOLOCK)
                                  LEFT OUTER JOIN TB_DA026 b
                                         ON (a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.cltcd = b.cltcd
                                         AND a.misdate = b.misdate AND a.misnum = b.misnum
                                         AND b.misdate BETWEEN :stdate AND :enddate)
                                 WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd
                                   AND b.rcvdate BETWEEN :rsdate AND :redate
                                   AND ISNULL(a.gubun,  '') LIKE :gubun  + '%'
                                   AND ISNULL(a.divicd, '') LIKE :divicd + '%'
                """;

        // _01_03 / _01_04 에만 있는 '연초 ~ 조회시작 전일' 매출·수금
        String yearToDate = """

                                UNION ALL

                                SELECT custcd, spjangcd, cltcd, misdate, misnum, remark,
                                       misamt AS beamt, 0 AS misamt, 0 AS rcvamt,
                                       actcd,
                                       (SELECT TOP 1 perid FROM TB_E601 WITH(NOLOCK)
                                         WHERE custcd = :custcd AND spjangcd = :spjangcd AND actcd = TB_DA023.actcd) AS perid,
                                       ISNULL(sangflag, '2') AS sangflag
                                  FROM TB_DA023 WITH(NOLOCK)
                                 WHERE custcd = :custcd AND spjangcd = :spjangcd
                                   AND misdate BETWEEN LEFT(:stdate, 4) + '0101'
                                                   AND CONVERT(VARCHAR(8), DATEADD(day, -1, CONVERT(datetime, :stdate)), 112)
                                   AND ISNULL(gubun,  '') LIKE :gubun  + '%'
                                   AND ISNULL(divicd, '') LIKE :divicd + '%'

                                UNION ALL

                                SELECT a.custcd, a.spjangcd, a.cltcd, a.misdate, a.misnum, b.remark,
                                       (__RCV_A__) * -1 AS beamt, 0 AS misamt, 0 AS rcvamt,
                                       a.actcd,
                                       (SELECT TOP 1 perid FROM TB_E601 WITH(NOLOCK)
                                         WHERE custcd = :custcd AND spjangcd = :spjangcd AND actcd = a.actcd) AS perid,
                                       ISNULL(b.sangflag, '2') AS sangflag
                                  FROM TB_DA026 a WITH(NOLOCK)
                                  LEFT OUTER JOIN TB_DA023 b
                                         ON (a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.misdate = b.misdate
                                         AND a.cltcd = b.cltcd AND a.misnum = b.misnum
                                         AND ISNULL(a.divicd, '') LIKE :divicd + '%')
                                 WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd
                                   AND a.rcvdate BETWEEN LEFT(:rsdate, 4) + '0101'
                                                     AND CONVERT(VARCHAR(8), DATEADD(day, -1, CONVERT(datetime, :rsdate)), 112)
                """;

        // 전년 마감. _01_04 / _01_05 는 '수금합계 <> 매출액'(미결제분) 만 가져온다.
        String endBranch = """

                                UNION ALL

                                SELECT a.custcd, a.spjangcd, a.cltcd, a.misdate, a.misnum, a.remark,
                                       a.misamt AS beamt, 0 AS misamt, 0 AS rcvamt,
                                       a.actcd,
                                       (SELECT TOP 1 perid FROM TB_E601 WITH(NOLOCK)
                                         WHERE custcd = :custcd AND spjangcd = :spjangcd AND actcd = a.actcd) AS perid,
                                       '2' AS sangflag
                                  FROM TB_DA023_END a WITH(NOLOCK)
                                 WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd
                                   AND a.year = LEFT(:stdate, 4)
                                   AND ISNULL(a.gubun, '') LIKE :gubun + '%'
                __END_EXTRA__
                """;

        String inner;
        if ("05".equals(dwSuffix)) {
            inner = base + endBranch.replace("__END_EXTRA__",
                    "                                   AND (" + rcvA + ") <> a.misamt");
        } else if ("04".equals(dwSuffix)) {
            inner = base + yearToDate + endBranch.replace("__END_EXTRA__",
                    "                                   AND (" + rcvA + ") <> a.misamt");
        } else { // 03
            inner = base + yearToDate + endBranch.replace("__END_EXTRA__",
                    "                                   AND ISNULL(a.divicd, '') LIKE :divicd + '%'");
        }

        return inner.replace("__RCV_A__", rcvA).replace("__RCV_B__", rcvB).replace("__RCV__", rcv);
    }

    // ════════════════════════════════════════════════════════════════
    //  구분 4 : 매출별수금내역(현대양식) — d_tb_da023w_01_06
    //  매출(TB_DA023) / 입금(TB_DA026H) / 선수금(TB_DA021) 3종을 한 줄로 묶는다.
    //  resuamt(잔액) 는 원본 SQL 에서 항상 0 이고 파워빌더가 스크립트로 채운다.
    //  여기서는 _01_01 과 같은 방식으로 누계를 직접 계산한다.
    // ════════════════════════════════════════════════════════════════
    public List<Map<String, Object>> getHyundaiList(String startDate, String endDate,
                                                    String rcvStart, String rcvEnd,
                                                    String spjangcd, String cltcd, String gubun,
                                                    boolean includeDamt) {

        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("custcd",   custcd);
        p.addValue("spjangcd", spjangcd);
        p.addValue("stdate",   startDate);
        p.addValue("enddate",  endDate);
        p.addValue("rsdate",   (rcvStart == null || rcvStart.isBlank()) ? startDate : rcvStart);
        p.addValue("redate",   (rcvEnd   == null || rcvEnd.isBlank())   ? endDate   : rcvEnd);
        p.addValue("cltcd",    (cltcd == null || cltcd.isBlank()) ? "%" : cltcd.trim());
        p.addValue("gubun",    gubun == null ? "" : gubun.trim());

        // 원본 _01_06 의 입금액 합계식에는 damt 가 빠져 있다 (다른 화면과 어긋나는 원인).
        // includeDamt=true 면 다른 구분과 동일하게 12개 컬럼을 모두 더한다.
        String rcvSumH = includeDamt
                ? rcv12("") + " + ISNULL(sunamt, 0)"
                : "ISNULL(hamt,0) + ISNULL(eamt,0) + ISNULL(samt,0) + ISNULL(jamt,0) + ISNULL(bamt,0) + "
                + "ISNULL(gamt,0) + ISNULL(dcamt,0) + ISNULL(jmar,0) + ISNULL(csamt,0) + ISNULL(cmar,0) + "
                + "ISNULL(cdmar,0) + ISNULL(sunamt,0)";

        String sql = """
                SELECT a.custcd, a.spjangcd, a.cltcd,
                       ISNULL(x.cltnm, '') AS cltnm,
                       a.misdate, a.misnum,
                       MAX(a.misdate2) AS rcvdate,
                       MAX(a.misdate3) AS sundate,
                       a.remark,
                       SUM(ISNULL(a.misamt, 0)) AS misamt,
                       SUM(ISNULL(a.rcvamt, 0)) AS rcvamt,
                       -- 잔액 = 거래처별 (매출 - 입금) 누계. 원본은 항상 0 이라 여기서 계산한다.
                       SUM(SUM(ISNULL(a.misamt, 0)) - SUM(ISNULL(a.rcvamt, 0)))
                           OVER (PARTITION BY a.cltcd ORDER BY a.misdate, a.misnum
                                 ROWS UNBOUNDED PRECEDING) AS resuamt,
                       SUM(ISNULL(a.sunamt, 0)) AS sunamt,
                       SUM(ISNULL(a.minamt, 0)) AS minamt,
                       MAX(a.bigo)  AS bigo,
                       MAX(a.actcd) AS actcd,
                       MAX(a.sangflag) AS sangflag,
                       CAST(:stdate  AS VARCHAR(8)) AS stdate,
                       CAST(:enddate AS VARCHAR(8)) AS enddate
                  FROM (
                        /* 매출 */
                        SELECT custcd, spjangcd, cltcd, misdate, misnum,
                               '' AS misdate2, '' AS misdate3, remark,
                               misamt, 0 AS rcvamt, 0 AS sunamt, 0 AS minamt,
                               actcd, bigo, sangflag
                          FROM TB_DA023 WITH(NOLOCK)
                         WHERE custcd = :custcd AND spjangcd = :spjangcd
                           AND misdate BETWEEN :stdate AND :enddate
                           AND cltcd LIKE :cltcd
                           AND gubun LIKE :gubun + '%'

                        UNION ALL

                        /* 입금 */
                        SELECT custcd, spjangcd, cltcd, misdate, misnum,
                               rcvdate AS misdate2, '' AS misdate3, remark,
                               0 AS misamt, __RCV_H__ AS rcvamt, 0 AS sunamt, 0 AS minamt,
                               actcd, '' AS bigo, '' AS sangflag
                          FROM TB_DA026H WITH(NOLOCK)
                         WHERE custcd = :custcd AND spjangcd = :spjangcd
                           AND rcvdate BETWEEN :rsdate AND :redate
                           AND cltcd LIKE :cltcd

                        UNION ALL

                        /* 선수금 */
                        SELECT custcd, spjangcd, cltcd, sundate AS misdate, '' AS misnum,
                               '' AS misdate2, sundate AS misdate3, remark,
                               0 AS misamt, 0 AS rcvamt,
                               SUM(ISNULL(sunamt, 0)) AS sunamt, SUM(ISNULL(minamt, 0)) AS minamt,
                               '' AS actcd, '' AS bigo, '' AS sangflag
                          FROM TB_DA021 WITH(NOLOCK)
                         WHERE custcd = :custcd AND spjangcd = :spjangcd
                           AND sundate BETWEEN :rsdate AND :redate
                           AND cltcd LIKE :cltcd
                         GROUP BY custcd, spjangcd, cltcd, sundate, remark
                       ) a
                  LEFT OUTER JOIN TB_XCLIENT x WITH(NOLOCK)
                         ON x.custcd = a.custcd AND x.cltcd = a.cltcd
                 WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd
                   AND (:cltcd = '%'
                     OR a.cltcd            LIKE '%' + :cltcd + '%'
                     OR ISNULL(x.cltnm, '') LIKE '%' + :cltcd + '%')
                 GROUP BY a.custcd, a.spjangcd, a.cltcd, a.misdate, a.misnum, a.remark, x.cltnm
                 ORDER BY a.cltcd, a.misdate, a.misnum
                """.replace("__RCV_H__", rcvSumH);

        return this.sqlRunner.getRows(sql, p);
    }

    // ════════════════════════════════════════════════════════════════
    //  구분 5 : 거래처원장(입출금포함) — d_tb_da026_06_totlist
    //  매출(TB_DA023) / 수금(TB_DA026H) / 매입(TB_CA640) / 지급(TB_CA642) /
    //  은행입출금(TB_bank_accsave, 수금매칭 안 된 건만) 을 한 원장으로 합친다.
    //  restamt(잔액) 은 원본에서 항상 0 이고 DataWindow 계산열이라 여기서 누계를 만든다.
    // ════════════════════════════════════════════════════════════════
    public List<Map<String, Object>> getLedgerList(String startDate, String endDate,
                                                   String spjangcd, String cltcd) {

        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("custcd",   custcd);
        p.addValue("spjangcd", spjangcd);
        p.addValue("frdate",   startDate);
        p.addValue("todate",   endDate);
        p.addValue("cltcd",    (cltcd == null || cltcd.isBlank()) ? "%" : cltcd.trim());

        String rcvH  = rcv12("") + " + ISNULL(sunamt, 0)";
        String rcv642 = "ISNULL(hamt,0) + ISNULL(eamt,0) + ISNULL(samt,0) + ISNULL(bamt,0) + "
                      + "ISNULL(gamt,0) + ISNULL(sunamt,0)";

        String sql = """
                WITH clt AS (
                    SELECT cltcd FROM TB_XCLIENT WITH(NOLOCK)
                     WHERE custcd = :custcd
                       AND (:cltcd = '%' OR cltcd LIKE '%' + :cltcd + '%' OR cltnm LIKE '%' + :cltcd + '%')
                ),
                raw AS (
                        /* sort 1 : 매출 */
                        SELECT '1' AS sort, custcd, spjangcd, cltcd, misdate, misnum,
                               misdate AS misdate2, remark,
                               misamt, 0 AS rcvamt, 0 AS tran_amt, 0 AS wdr_amt,
                               actcd, bigo, '' AS inout_type
                          FROM TB_DA023 WITH(NOLOCK)
                         WHERE custcd = :custcd AND spjangcd = :spjangcd
                           AND misdate BETWEEN :frdate AND :todate
                           AND cltcd IN (SELECT cltcd FROM clt)

                        UNION ALL

                        /* sort 2 : 수금 */
                        SELECT '2', custcd, spjangcd, cltcd, rcvdate, misnum,
                               misdate, remark,
                               0, __RCV_H__, 0, 0,
                               actcd, '', ''
                          FROM TB_DA026H WITH(NOLOCK)
                         WHERE custcd = :custcd AND spjangcd = :spjangcd
                           AND rcvdate BETWEEN :frdate AND :todate
                           AND cltcd IN (SELECT cltcd FROM clt)

                        UNION ALL

                        /* sort 3 : 매입 */
                        SELECT '3', custcd, spjangcd, cltcd, mijdate, mijnum,
                               mijdate, remark,
                               mijamt, 0, 0, 0,
                               '', '', ''
                          FROM TB_CA640 WITH(NOLOCK)
                         WHERE custcd = :custcd AND spjangcd = :spjangcd
                           AND mijdate BETWEEN :frdate AND :todate
                           AND cltcd IN (SELECT cltcd FROM clt)

                        UNION ALL

                        /* sort 4 : 지급 */
                        SELECT '4', custcd, spjangcd, cltcd, snddate, sndnum,
                               snddate, remark,
                               0, __RCV_642__, 0, 0,
                               '', '', ''
                          FROM TB_CA642 WITH(NOLOCK)
                         WHERE custcd = :custcd AND spjangcd = :spjangcd
                           AND snddate BETWEEN :frdate AND :todate
                           AND cltcd IN (SELECT cltcd FROM clt)

                        UNION ALL

                        /* sort 5 : 은행 입출금 (수금 매칭 안 된 건만) */
                        SELECT '5', custcd, spjangcd, cltcd, tran_date, '',
                               tran_date, print_content,
                               0, 0, tran_amt, wdr_amt,
                               '', '', inout_type
                          FROM TB_bank_accsave WITH(NOLOCK)
                         WHERE custcd = :custcd AND spjangcd = :spjangcd
                           AND tran_date BETWEEN :frdate AND :todate
                           AND cltcd IN (SELECT cltcd FROM clt)
                           AND (rcvdate = '' OR rcvdate IS NULL)
                )
                SELECT a.sort,
                       CASE a.sort WHEN '1' THEN '매출' WHEN '2' THEN '수금'
                                   WHEN '3' THEN '매입' WHEN '4' THEN '지급'
                                   WHEN '5' THEN '입출금' ELSE '' END AS gubunnm,
                       a.custcd, a.spjangcd, a.cltcd,
                       ISNULL(x.cltnm, '') AS cltnm,
                       a.misdate, a.misnum, a.misdate2,
                       a.remark,
                       ISNULL(a.misamt,   0) AS misamt,
                       ISNULL(a.rcvamt,   0) AS rcvamt,
                       ISNULL(a.tran_amt, 0) AS tran_amt,
                       ISNULL(a.wdr_amt,  0) AS wdr_amt,
                       -- 잔액 = 거래처별 (거래액 - 결제액) 누계.
                       -- 실화면에서 매출은 더하고 수금은 빼는 것이 확인됐다.
                       -- 은행 입출금(tran_amt/wdr_amt)이 잔액에 반영되는지는 미확인이라 제외했다.
                       SUM(ISNULL(a.misamt, 0) - ISNULL(a.rcvamt, 0))
                           OVER (PARTITION BY a.cltcd ORDER BY a.misdate, a.sort, a.misnum
                                 ROWS UNBOUNDED PRECEDING) AS restamt,
                       a.bigo, a.actcd,
                       (SELECT TOP 1 actnm FROM TB_E601 WITH(NOLOCK)
                         WHERE custcd = :custcd AND spjangcd = :spjangcd AND actcd = a.actcd) AS actnm,
                       a.inout_type,
                       CAST(:frdate AS VARCHAR(8)) AS stdate,
                       CAST(:todate AS VARCHAR(8)) AS enddate
                  FROM raw a
                  LEFT OUTER JOIN TB_XCLIENT x WITH(NOLOCK)
                         ON x.custcd = a.custcd AND x.cltcd = a.cltcd
                 ORDER BY a.cltcd, a.misdate, a.sort, a.misnum
                """
                .replace("__RCV_H__",   rcvH)
                .replace("__RCV_642__", rcv642);

        return this.sqlRunner.getRows(sql, p);
    }
}
