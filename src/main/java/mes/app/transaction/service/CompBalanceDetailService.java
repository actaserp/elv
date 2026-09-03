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
                 * SQL 이 아니라 윈도우 스크립트가 Retrieve 후 InsertRow 로 끼워 넣는 행이라
                 * 원본 계산식을 아직 확보하지 못했다. 경동 실화면과 대조해 보면
                 * '조회시작일 이전 누적 (매출 - 수금)' 으로 6건 중 4건이 정확히 맞는다.
                 * 스크립트를 확보하면 이 블록만 바꾸면 된다.
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
                    SELECT t.cltcd, SUM(t.amt) AS amt
                      FROM (
                            SELECT cltcd, ISNULL(SUM(misamt), 0) AS amt
                              FROM TB_DA023 WITH(NOLOCK)
                             WHERE custcd = :custcd AND spjangcd = :spjangcd
                               AND misdate < :stdate
                               AND (cltcd IN (SELECT cltcd FROM clt) OR actcd IN (SELECT actcd FROM act))
                             GROUP BY cltcd
                            UNION ALL
                            SELECT cltcd, ISNULL(SUM(__RCV_12__ + ISNULL(sunamt, 0)), 0) * -1
                              FROM TB_DA026 WITH(NOLOCK)
                             WHERE custcd = :custcd AND spjangcd = :spjangcd
                               AND rcvdate < :stdate
                               AND (cltcd IN (SELECT cltcd FROM clt) OR actcd IN (SELECT actcd FROM act))
                             GROUP BY cltcd
                           ) t
                     GROUP BY t.cltcd
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
}
