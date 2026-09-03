package mes.app.transaction.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 매출현황 — 파워빌더 w_input_da026w 와 동일하게 재구현.
 *
 * 기존 구현은 sports 에서 가져온 것이라 본사 PostgreSQL 의 tb_banktransit(은행거래) 을 썼으나,
 * 파워빌더는 사업체 MSSQL 의 TB_DA026(수금전표) 을 기준으로 TB_DA023(매출)·TB_XCLIENT(거래처)·
 * TB_E601(현장)·TB_JA001(담당자)·TB_JC002(부서)·TB_XA012(사업장) 를 붙인다.
 * '은행거래 원장' 이 아니라 '수금전표' 기준이라 화면 성격 자체가 다르다.
 *
 * 파워빌더에서 그대로 옮긴 규칙
 *   · 입금형태(chk) 는 코드값이 아니라 금액컬럼이 0보다 큰지 보고 '현금, 예금, ...' 문자열을 만든다.
 *     검색도 그 조합 문자열에 LIKE 를 건다.
 *   · 담당자(pernm) 는 서버 조건이 아니라 조회 결과에 거는 화면 필터다 (dw_1.SetFilter).
 *     그래서 이 쿼리에는 담당자 조건이 없다.
 *   · 매출구분(gubun) 은 화면 왼쪽 체크박스에서 고른 값들을 IN 으로 넘긴다.
 */
@Slf4j
@Service
public class SalesListService {

    /** 사업체 MSSQL (TenantContext 라우팅) */
    @Autowired
    SqlRunner sqlRunner;

    /** 입금형태 문자열 조합. 파워빌더 SELECT 절과 WHERE 절이 같은 식을 쓴다. */
    private static final String CHK_EXPR = """
            Case When %1$shamt   > 0 Then '현금, '   Else '' End +
            Case When %1$sbamt   > 0 Then '예금, '   Else '' End +
            Case When %1$sjamt   > 0 Then '지로, '   Else '' End +
            Case When %1$scsamt  > 0 Then 'CMS, '    Else '' End +
            Case When %1$seamt   > 0 Then '어음, '   Else '' End +
            Case When %1$ssamt   > 0 Then '수표, '   Else '' End +
            Case When %1$sdamt   > 0 Then '카드, '   Else '' End +
            Case When %1$sdcamt  > 0 Then 'D/C, '    Else '' End +
            Case When %1$ssunamt > 0 Then '선수금, ' Else '' End +
            Case When %1$sgamt   > 0 Then '기타'     Else '' End""";

    private static String chkExpr(String prefix) {
        return String.format(CHK_EXPR, prefix);
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

    /** 화면 왼쪽 매출구분 체크박스 목록 (파워빌더가 전부 체크된 상태로 띄운다) */
    public List<Map<String, Object>> getGubunList(String spjangcd) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("custcd", custcd);
        p.addValue("spjangcd", spjangcd);
        return sqlRunner.getRows("""
                SELECT artcd AS code, artnm AS cnam, '1' AS chk
                  FROM TB_DA020 WITH(NOLOCK)
                 WHERE custcd = :custcd AND spjangcd = :spjangcd
                 ORDER BY artcd
                """, p);
    }

    /** 은행 코드도움 — TB_DA026.bankcd 는 은행코드(TB_XBANK) + 계좌코드(TB_AA040) 결합형('03B01') 이다. */
    public List<Map<String, Object>> getBankList(String spjangcd) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("custcd", custcd);
        p.addValue("spjangcd", spjangcd);
        return sqlRunner.getRows("""
                SELECT DISTINCT a.bankcd AS code,
                       ISNULL(a.banknm, '') AS banknm,
                       ISNULL(x.banknm, '') AS bankgrpnm
                  FROM (SELECT DISTINCT bankcd FROM TB_DA026 WITH(NOLOCK)
                         WHERE custcd = :custcd AND spjangcd = :spjangcd
                           AND ISNULL(bankcd, '') <> '') d
                  LEFT OUTER JOIN TB_AA040 a WITH(NOLOCK)
                         ON a.spjangcd = :spjangcd AND d.bankcd LIKE '%' + a.bankcd
                  LEFT OUTER JOIN TB_XBANK x WITH(NOLOCK)
                         ON LEFT(d.bankcd, 2) = x.bankcd
                 ORDER BY 1
                """.replace("a.bankcd AS code", "d.bankcd AS code"), p);
    }

    /**
     * 입금현황 조회.
     *
     * @param cltcd      거래처/현장 자유입력. 거래처코드·거래처명·현장코드·현장명 넷에 LIKE 를 건다.
     * @param accyn      전표발행 '%'(전체) / '0'(미발행) / '1'(발행)
     * @param gubunList  매출구분 체크박스에서 고른 값. 비면 조건을 걸지 않는다.
     * @param chk        입금형태. 조합 문자열에 LIKE.
     */
    public List<Map<String, Object>> getList(String startDate, String endDate, String spjangcd,
                                             String cltcd, String accyn, String billgubun,
                                             String bankcd, List<String> gubunList,
                                             String divicd, String chk, String remark) {

        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("custcd",    custcd);
        p.addValue("spjangcd",  spjangcd);
        p.addValue("stdate",    startDate);
        p.addValue("enddate",   endDate);
        p.addValue("cltcd",     orAll(cltcd));
        p.addValue("accyn",     orAll(accyn));
        p.addValue("billgubun", orAll(billgubun));
        p.addValue("bankcd",    orAll(bankcd));
        p.addValue("divicd",    orAll(divicd));
        p.addValue("chk",       chk    == null ? "" : chk.trim());
        p.addValue("remark",    orAll(remark));

        boolean hasGubun = gubunList != null && !gubunList.isEmpty();
        if (hasGubun) p.addValue("gubunList", gubunList);

        // 파워빌더의 misamt/differamt 는 같은 매출전표의 '직전 입금건'(rcvnum 이 더 작은 것 중 최대)을
        // 찾아 잔액을 계산한다. 원본은 행마다 도는 상관 서브쿼리 두 개인데,
        // 키가 같아 OUTER APPLY 한 번으로 묶었다 (계산식은 원본 그대로).
        String sql = """
                SELECT a.custcd, a.spjangcd,
                       a.rcvdate,
                       LEFT(a.rcvdate, 6) AS rcvmon,
                       a.rcvnum,
                       a.cltcd,
                       x.cltnm,
                       a.actcd,
                       q.actnm,
                       a.misdate,
                       a.misnum,
                       __CHK_A__ AS chk,
                       ISNULL(prev.misamt_prev, b.misamt) AS misamt,
                       ISNULL(a.hamt,0) + ISNULL(a.bamt,0) + ISNULL(a.eamt,0) + ISNULL(a.samt,0) +
                       ISNULL(a.damt,0) + ISNULL(a.jamt,0) + ISNULL(a.csamt,0) + ISNULL(a.dcamt,0) +
                       ISNULL(a.gamt,0) + ISNULL(a.sunamt,0) + ISNULL(a.cmar,0) AS rcvamt,
                       ISNULL(prev.misamt_prev2, b.misamt)
                         - (ISNULL(a.hamt,0) + ISNULL(a.bamt,0) + ISNULL(a.eamt,0) + ISNULL(a.samt,0) +
                            ISNULL(a.damt,0) + ISNULL(a.gamt,0) + ISNULL(a.jamt,0) + ISNULL(a.dcamt,0) +
                            ISNULL(a.jmar,0) + ISNULL(a.csamt,0) + ISNULL(a.cmar,0) + ISNULL(a.cdmar,0) +
                            ISNULL(a.sunamt,0)) AS differamt,
                       tot.differamt AS differamt1,
                       a.remark,
                       a.acc_spdate,
                       a.acc_spnum,
                       a.accyn,
                       b.acccd,
                       e.saupnum, e.adresa, e.adresb, e.prenm, e.emailadres,
                       jiro.sunapdate, jiro.cltnum,
                       jiro.misdate AS jiromisdate, jiro.misnum AS jiromisnum, jiro.filename,
                       p.pernm,
                       r.divinm,
                       x.taxmail,
                       CASE WHEN a.bamt  > 0 THEN a.bankcd
                            WHEN a.jamt  > 0 THEN a.jbankcd
                            WHEN a.csamt > 0 THEN a.cbankcd ELSE '' END AS bankcd,
                       CAST(:stdate  AS VARCHAR(8)) AS frdate,
                       CAST(:enddate AS VARCHAR(8)) AS enddate
                  FROM TB_DA026 a WITH(NOLOCK)
                  LEFT OUTER JOIN TB_DA023 b WITH(NOLOCK)
                         ON (a.custcd = b.custcd AND a.spjangcd = b.spjangcd
                         AND a.misdate = b.misdate AND a.misnum = b.misnum AND a.cltcd = b.cltcd)
                  LEFT OUTER JOIN TB_XCLIENT x WITH(NOLOCK)
                         ON (a.custcd = x.custcd AND a.cltcd = x.cltcd)
                  LEFT OUTER JOIN TB_XA012 e WITH(NOLOCK)
                         ON (a.custcd = e.custcd AND a.spjangcd = e.spjangcd)
                  LEFT OUTER JOIN TB_E601 q WITH(NOLOCK)
                         ON (a.custcd = q.custcd AND a.spjangcd = q.spjangcd AND b.actcd = q.actcd)
                  LEFT OUTER JOIN TB_JA001 p WITH(NOLOCK)
                         ON (a.custcd = p.custcd AND a.spjangcd = p.spjangcd AND 'p' + q.perid = p.perid)
                  LEFT OUTER JOIN TB_JC002 r WITH(NOLOCK)
                         ON (a.custcd = r.custcd AND a.spjangcd = r.spjangcd AND b.divicd = r.divicd)
                  /* 직전 입금건 기준 잔액 (원본의 상관 서브쿼리 2개를 하나로 묶음).
                     ★ 원본의 서브쿼리 안 'misamt' 는 TB_DA026H 에 없는 컬럼이라
                       실제로는 바깥 TB_DA023 b.misamt 를 참조하고 있었다. 그대로 옮겼다. */
                  OUTER APPLY (
                        SELECT TOP 1
                               b.misamt - (ISNULL(z.hamt,0) + ISNULL(z.bamt,0) + ISNULL(z.eamt,0) + ISNULL(z.samt,0) +
                                           ISNULL(z.damt,0) + ISNULL(z.jamt,0) + ISNULL(z.jmar,0) + ISNULL(z.csamt,0) +
                                           ISNULL(z.cmar,0) + ISNULL(z.cdmar,0) + ISNULL(z.dcamt,0) + ISNULL(z.gamt,0) +
                                           ISNULL(z.sunamt,0)) AS misamt_prev,
                               b.misamt - (ISNULL(z.hamt,0) + ISNULL(z.bamt,0) + ISNULL(z.eamt,0) + ISNULL(z.samt,0) +
                                           ISNULL(z.damt,0) + ISNULL(z.gamt,0) + ISNULL(z.jamt,0) + ISNULL(z.dcamt,0) +
                                           ISNULL(z.jmar,0) + ISNULL(a.csamt,0) + ISNULL(a.cmar,0) + ISNULL(a.cdmar,0) +
                                           ISNULL(a.sunamt,0)) AS misamt_prev2
                          FROM TB_DA026H z WITH(NOLOCK)
                         WHERE z.custcd = a.custcd AND z.spjangcd = a.spjangcd AND z.cltcd = a.cltcd
                           AND z.misdate = a.misdate AND z.misnum = a.misnum AND z.rcvdate = a.rcvdate
                           AND z.rcvnum < a.rcvnum
                         ORDER BY z.rcvnum DESC
                  ) prev
                  /* 매출전표 전체 기준 차액 */
                  OUTER APPLY (
                        SELECT MAX(h.rcvnum) AS rcvnum,
                               MAX(b2.misamt) - SUM(ISNULL(h.hamt,0) + ISNULL(h.bamt,0) + ISNULL(h.eamt,0) +
                                                    ISNULL(h.samt,0) + ISNULL(h.damt,0) + ISNULL(h.gamt,0) +
                                                    ISNULL(h.jamt,0) + ISNULL(h.dcamt,0) + ISNULL(h.jmar,0) +
                                                    ISNULL(h.csamt,0) + ISNULL(h.cmar,0) + ISNULL(h.cdmar,0) +
                                                    ISNULL(h.sunamt,0)) AS differamt
                          FROM TB_DA026H h WITH(NOLOCK)
                          LEFT OUTER JOIN TB_DA023 b2 WITH(NOLOCK)
                                 ON (h.custcd = b2.custcd AND h.spjangcd = b2.spjangcd AND h.cltcd = b2.cltcd
                                 AND h.actcd = b2.actcd AND h.misdate = b2.misdate AND h.misnum = b2.misnum)
                         WHERE h.custcd = a.custcd AND h.spjangcd = a.spjangcd
                           AND h.rcvdate = a.rcvdate AND h.misdate = a.misdate AND h.misnum = a.misnum
                        HAVING MAX(h.rcvnum) = a.rcvnum
                  ) tot
                  /* 지로 연동 (원본의 TOP 1 서브쿼리 5개를 하나로 묶음) */
                  OUTER APPLY (
                        SELECT TOP 1 i.sunapdate, i.cltnum, i.misdate, i.misnum, i.filename
                          FROM TB_DA027_IMPORT i WITH(NOLOCK)
                         WHERE i.spjangcd = a.spjangcd AND i.rcvdate = a.rcvdate AND i.rcvnum = a.rcvnum
                  ) jiro
                 WHERE a.custcd = :custcd
                   AND a.spjangcd = :spjangcd
                   AND a.rcvdate BETWEEN :stdate AND :enddate
                   AND (:cltcd = '%'
                     OR ISNULL(x.cltnm, '') LIKE '%' + :cltcd + '%'
                     OR ISNULL(a.cltcd, '') LIKE '%' + :cltcd + '%'
                     OR ISNULL(a.actcd, '') LIKE '%' + :cltcd + '%'
                     OR ISNULL(q.actnm, '') LIKE '%' + :cltcd + '%')
                   AND (a.accyn = :accyn OR :accyn = '%' OR a.accyn IS NULL)
                   AND (b.billgubun = :billgubun OR :billgubun = '%')
                   AND (a.bankcd = :bankcd OR :bankcd = '%' OR a.bankcd IS NULL)
                   __GUBUN__
                   AND (b.divicd = :divicd OR :divicd = '%')
                   AND (__CHK_A__) LIKE '%' + :chk + '%'
                   AND (a.remark LIKE '%' + :remark + '%' OR :remark = '%' OR a.remark IS NULL)
                 ORDER BY a.rcvdate, a.rcvnum
                """
                .replace("__CHK_A__", chkExpr("a."))
                .replace("__GUBUN__", hasGubun ? "AND (b.gubun IN (:gubunList) OR b.gubun IS NULL)" : "");

        return this.sqlRunner.getRows(sql, p);
    }

    private static String orAll(String v) {
        return (v == null || v.isBlank()) ? "%" : v.trim();
    }
}
