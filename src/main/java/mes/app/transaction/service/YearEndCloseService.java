package mes.app.transaction.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 매입매출년마감 — 파워빌더 화면 두 개를 한 화면(탭 2개)으로 합친 것
 *   · 미수금마감이월 w_tb_da023_end : TB_DA023 → TB_DA023_END
 *   · 미지급마감이월 w_tb_ca640_end : TB_CA640 → TB_CA640_END
 *
 * 마감은 원본을 지우지 않고 복사만 한다. 경기 TB_DA023_END(year 2020) 50건이
 * TB_DA023 에 그대로 남아 있는 것으로 확인했다. 마감취소는 그 해 이월자료를 지운다.
 *
 * year 는 '이월년도'다(화면 오른쪽 칸). setyymm 은 year + '00' 이고 TB_DA023_END 에만 있다.
 *
 * 이월 대상과 금액은 파워빌더 원본 DataWindow 를 그대로 옮겼다.
 *   · d_tb_da023_end_da023 (미수금) / d_tb_ca640_end_ca640 (미지급)
 *   · 이월년도 이전에 받은 수금·지급만 빼서 잔액이 0 이 아닌 전표를 고른다
 *   · chaamt = 원금 - 그때까지의 수금/지급. 즉 <b>이월 잔액</b>이고, 마감 스크립트가
 *     chaamt 가 0 이 아니면 misamt/mijamt 자리에 그 값을 넣는다.
 *     수금/지급이 아예 없던 전표는 chaamt 가 NULL 이라 원금이 그대로 넘어간다.
 *
 * ※ 파워빌더의 마감취소 DELETE 와 원본 조회에는 spjangcd 조건이 없다(전 사업장 일괄).
 *   사업체 DB 는 사업장이 하나라 결과는 같지만 격리를 위해 spjangcd 를 넣었다.
 */
@Slf4j
@Service
public class YearEndCloseService {

    @Autowired
    SqlRunner sqlRunner;

    /** 화면 표시용 수금액 합계 — TB_DA026 의 13개 금액 컬럼 (미수금 잔액명세와 같은 식) */
    private static final String RCV_SUM = """
            SUM(ISNULL(hamt,0) + ISNULL(eamt,0) + ISNULL(samt,0) + ISNULL(bamt,0)
              + ISNULL(damt,0) + ISNULL(gamt,0) + ISNULL(jamt,0) + ISNULL(dcamt,0)
              + ISNULL(jmar,0) + ISNULL(csamt,0) + ISNULL(cmar,0) + ISNULL(cdmar,0)
              + ISNULL(sunamt,0))
            """;

    /** 화면 표시용 지급액 합계 — TB_CA642 의 6개 금액 컬럼 (미지급현황과 같은 식) */
    private static final String PAY_SUM = """
            SUM(ISNULL(hamt,0) + ISNULL(eamt,0) + ISNULL(samt,0)
              + ISNULL(bamt,0) + ISNULL(damt,0) + ISNULL(gamt,0))
            """;

    /**
     * 마감이 쓰는 수금 집계 — 파워빌더 d_tb_da023_end_da023 원본 그대로.
     * 화면용 RCV_SUM 과 달리 <b>cdmar 가 빠진 12개</b>이고, 이월년도 이전에 받은 수금만 센다.
     * rcvdate 가 비면 LEFT(NULL,4) 비교라 자연히 빠지는데 원본도 그렇다.
     */
    private static final String CLOSE_RCV_JOIN = """
            LEFT JOIN (SELECT custcd, spjangcd, misdate, misnum, cltcd,
                              SUM(ISNULL(hamt,0) + ISNULL(eamt,0) + ISNULL(samt,0) + ISNULL(bamt,0)
                                + ISNULL(damt,0) + ISNULL(gamt,0) + ISNULL(jamt,0) + ISNULL(dcamt,0)
                                + ISNULL(jmar,0) + ISNULL(csamt,0) + ISNULL(cmar,0) + ISNULL(sunamt,0)) AS iamt
                         FROM TB_DA026 WITH(NOLOCK)
                        WHERE LEFT(rcvdate, 4) < :year
                        GROUP BY custcd, spjangcd, misdate, misnum, cltcd) b
                   ON b.custcd = a.custcd AND b.spjangcd = a.spjangcd
                  AND b.misdate = a.misdate AND b.misnum = a.misnum AND b.cltcd = a.cltcd
            """;

    /**
     * 마감이 쓰는 지급 집계 — 파워빌더 d_tb_ca640_end_ca640 원본 그대로.
     * 화면용 PAY_SUM 과 달리 <b>선급금(sunamt)을 더하고 예정금액(plamt)을 뺀다</b>.
     * 날짜는 지급일자(snddate) 기준이고, 조인도 매입 거래처가 아니라
     * <b>지급처(a.mijcltcd = b.cltcd)</b>로 붙는다. 경기는 둘이 5,782건 전부 같다.
     */
    private static final String CLOSE_PAY_JOIN = """
            LEFT JOIN (SELECT custcd, spjangcd, mijdate, mijnum, cltcd,
                              SUM(ISNULL(hamt,0) + ISNULL(eamt,0) + ISNULL(samt,0) + ISNULL(bamt,0)
                                + ISNULL(damt,0) + ISNULL(gamt,0) + ISNULL(sunamt,0) - ISNULL(plamt,0)) AS iamt
                         FROM TB_CA642 WITH(NOLOCK)
                        WHERE LEFT(snddate, 4) < :year
                        GROUP BY custcd, spjangcd, mijdate, mijnum, cltcd) b
                   ON b.custcd = a.custcd AND b.spjangcd = a.spjangcd
                  AND b.mijdate = a.mijdate AND b.mijnum = a.mijnum AND b.cltcd = a.mijcltcd
            """;

    /** spjangcd 로 custcd 조회 (파워빌더의 as_custcd) */
    public String getCustcd(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        Map<String, Object> row = sqlRunner.getRow(
                "SELECT custcd FROM tb_xa012 WHERE spjangcd = :spjangcd", param);
        if (row == null || row.get("custcd") == null) return null;
        return String.valueOf(row.get("custcd")).trim();
    }

    private MapSqlParameterSource param(String spjangcd, String custcd, String year) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("custcd", custcd);
        p.addValue("spjangcd", spjangcd);
        p.addValue("year", year);
        p.addValue("setyymm", year + "00");
        return p;
    }

    private static String normYear(String year) {
        String y = year == null ? "" : year.trim();
        if (!y.matches("\\d{4}")) throw new IllegalStateException("이월년도를 4자리로 입력해주세요.");
        return y;
    }

    // ════════════════════════════════════════════════════════════
    //  미수금마감이월 (매출)
    // ════════════════════════════════════════════════════════════

    /** 마감된 미수금 이월자료 (파워빌더 dw_1) */
    public List<Map<String, Object>> getSalesList(String spjangcd, String year) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        MapSqlParameterSource p = param(spjangcd, custcd, normYear(year));

        return sqlRunner.getRows("""
                SELECT e.year,
                       e.cltcd,
                       ISNULL(x.cltnm, '')   AS cltnm,
                       ISNULL(e.actcd, '')   AS actcd,
                       ISNULL(s.actnm, '')   AS actnm,
                       e.misdate,
                       STUFF(STUFF(e.misdate, 5, 0, '-'), 8, 0, '-') AS misdate_fmt,
                       e.misnum,
                       ISNULL(e.remark, '')  AS remark,
                       ISNULL(e.misamt, 0)   AS misamt,
                       ISNULL(r.iamt, 0)     AS iamt,
                       ISNULL(e.setyymm, '') AS setyymm,
                       e.spjangcd,
                       ISNULL(a.spjangnm, '') AS spjangnm
                  FROM TB_DA023_END e WITH(NOLOCK)
                  LEFT JOIN TB_XCLIENT x WITH(NOLOCK) ON x.custcd = e.custcd AND x.cltcd = e.cltcd
                  LEFT JOIN TB_E601 s WITH(NOLOCK)
                    ON s.custcd = e.custcd AND s.spjangcd = e.spjangcd AND s.actcd = e.actcd
                  LEFT JOIN tb_xa012 a WITH(NOLOCK) ON a.custcd = e.custcd AND a.spjangcd = e.spjangcd
                  OUTER APPLY (SELECT __RCV__ AS iamt FROM TB_DA026 WITH(NOLOCK)
                                WHERE custcd = e.custcd AND spjangcd = e.spjangcd
                                  AND misdate = e.misdate AND misnum = e.misnum AND cltcd = e.cltcd) r
                 WHERE e.custcd = :custcd AND e.spjangcd = :spjangcd AND e.year = :year
                 ORDER BY e.cltcd, e.misdate, e.misnum
                """.replace("__RCV__", RCV_SUM), p);
    }

    /** 이월 대상 미리보기. 마감을 누르면 들어갈 자료와 같다 */
    public List<Map<String, Object>> previewSales(String spjangcd, String year) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        MapSqlParameterSource p = param(spjangcd, custcd, normYear(year));

        return sqlRunner.getRows("""
                SELECT :year AS year,
                       a.cltcd,
                       ISNULL(x.cltnm, '')  AS cltnm,
                       ISNULL(a.actcd, '')  AS actcd,
                       ISNULL(s.actnm, '')  AS actnm,
                       a.misdate,
                       STUFF(STUFF(a.misdate, 5, 0, '-'), 8, 0, '-') AS misdate_fmt,
                       a.misnum,
                       ISNULL(a.remark, '') AS remark,
                       -- 수금이 있었으면 그 잔액, 없었으면 원금 (파워빌더 chaamt 규칙)
                       ISNULL(a.misamt - b.iamt, ISNULL(a.misamt, 0)) AS misamt,
                       ISNULL(b.iamt, 0)    AS iamt,
                       a.spjangcd
                  FROM TB_DA023 a WITH(NOLOCK)
                  __RCVJOIN__
                  LEFT JOIN TB_XCLIENT x WITH(NOLOCK) ON x.custcd = a.custcd AND x.cltcd = a.cltcd
                  LEFT JOIN TB_E601 s WITH(NOLOCK)
                    ON s.custcd = a.custcd AND s.spjangcd = a.spjangcd AND s.actcd = a.actcd
                 WHERE ISNULL(a.misamt, 0) - ISNULL(b.iamt, 0) <> 0
                   AND a.custcd = :custcd AND a.spjangcd = :spjangcd
                   AND LEFT(a.misdate, 4) < :year
                 ORDER BY a.cltcd, a.misdate, a.misnum
                """.replace("__RCVJOIN__", CLOSE_RCV_JOIN), p);
    }

    /** 마감 — 잔액이 남은 매출 전표를 TB_DA023_END 로 복사한다 */
    @Transactional
    public int closeSales(String spjangcd, String year) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null) throw new IllegalStateException("사업장 정보를 찾을 수 없습니다.");

        MapSqlParameterSource p = param(spjangcd, custcd, normYear(year));

        if (salesClosedCount(p) > 0) {
            throw new IllegalStateException("이미 마감된 년도입니다. 마감취소 후 다시 실행해주세요.");
        }

        // 파워빌더가 한 행씩 복사하는 컬럼 그대로다. cltnm 은 TB_DA023 에 없는 화면 전용 컬럼이라 뺐다
        int inserted = sqlRunner.execute("""
                INSERT INTO TB_DA023_END
                      (custcd, spjangcd, year, setyymm, cltcd, misgubun, misdate, misnum,
                       actcd, wkactcd, contamt, gubun, yyyymm, pubgubun, accyn, divicd, perid,
                       amt, addamt, misamt, chaamt, schdate, billkind, taxcls, taxgubun, jirogubun,
                       hamt, eamt, enum, edate, eidate, ecltcd, samt, snum, bamt, bankcd,
                       damt, cardcd, persent, cardno, ggubun, gamt, jamt, jmar, jnum, jbankcd,
                       dcamt, pubnum, camt, ctaxamt, tax_spdate, tax_spnum, acc_spdate, acc_spnum,
                       remark, bigo, indate, inperid, sbankcd, serinum, jcltno,
                       sunflag, sunamt, sunchk, billgubun, compflag, compdate, compnum,
                       delflag, deldate, delnum, acccd, ungijun, jirochk, jirodate,
                       bemisdate, bemisnum)
                SELECT a.custcd, a.spjangcd, :year, :setyymm, a.cltcd, a.misgubun, a.misdate, a.misnum,
                       a.actcd, a.wkactcd, a.contamt, a.gubun, a.yyyymm, a.pubgubun, a.accyn, a.divicd, a.perid,
                       a.amt, a.addamt,
                       -- 수금이 있었으면 잔액으로, 없었으면 원금 그대로 이월한다
                       ISNULL(a.misamt - b.iamt, a.misamt),
                       a.misamt - b.iamt,
                       a.schdate, a.billkind, a.taxcls, a.taxgubun, a.jirogubun,
                       a.hamt, a.eamt, a.enum, a.edate, a.eidate, a.ecltcd, a.samt, a.snum, a.bamt, a.bankcd,
                       a.damt, a.cardcd, a.persent, a.cardno, a.ggubun, a.gamt, a.jamt, a.jmar, a.jnum, a.jbankcd,
                       a.dcamt, a.pubnum, a.camt, a.ctaxamt, a.tax_spdate, a.tax_spnum, a.acc_spdate, a.acc_spnum,
                       a.remark, a.bigo, a.indate, a.inperid, a.sbankcd, a.serinum, a.jcltno,
                       a.sunflag, a.sunamt, a.sunchk, a.billgubun, a.compflag, a.compdate, a.compnum,
                       a.delflag, a.deldate, a.delnum, a.acccd, a.ungijun, a.jirochk, a.jirodate,
                       a.bemisdate, a.bemisnum
                  FROM TB_DA023 a WITH(NOLOCK)
                  __RCVJOIN__
                 WHERE ISNULL(a.misamt, 0) - ISNULL(b.iamt, 0) <> 0
                   AND a.custcd = :custcd AND a.spjangcd = :spjangcd
                   AND LEFT(a.misdate, 4) < :year
                """.replace("__RCVJOIN__", CLOSE_RCV_JOIN), p);

        if (inserted == 0) throw new IllegalStateException("이월할 미수금 자료가 없습니다.");
        log.info("[년마감] 미수금 이월 {}건 (spjangcd={}, year={})", inserted, spjangcd, year);
        return inserted;
    }

    /** 마감취소 — 그 해 이월자료를 지운다 */
    @Transactional
    public int cancelSales(String spjangcd, String year) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null) throw new IllegalStateException("사업장 정보를 찾을 수 없습니다.");

        MapSqlParameterSource p = param(spjangcd, custcd, normYear(year));

        int deleted = sqlRunner.execute("""
                DELETE FROM TB_DA023_END
                 WHERE custcd = :custcd AND spjangcd = :spjangcd AND year = :year
                """, p);
        if (deleted == 0) throw new IllegalStateException("취소할 마감 자료가 없습니다.");
        log.info("[년마감] 미수금 마감취소 {}건 (spjangcd={}, year={})", deleted, spjangcd, year);
        return deleted;
    }

    private int salesClosedCount(MapSqlParameterSource p) {
        Map<String, Object> row = sqlRunner.getRow("""
                SELECT COUNT(*) AS cnt FROM TB_DA023_END WITH(NOLOCK)
                 WHERE custcd = :custcd AND spjangcd = :spjangcd AND year = :year
                """, p);
        if (row == null) throw new IllegalStateException("마감 상태를 확인하지 못했습니다.");
        return ((Number) row.get("cnt")).intValue();
    }

    // ════════════════════════════════════════════════════════════
    //  미지급마감이월 (매입)
    // ════════════════════════════════════════════════════════════

    /** 마감된 미지급 이월자료 (파워빌더 dw_1) */
    public List<Map<String, Object>> getPurchaseList(String spjangcd, String year) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        MapSqlParameterSource p = param(spjangcd, custcd, normYear(year));

        return sqlRunner.getRows("""
                SELECT e.year,
                       e.cltcd,
                       ISNULL(x.cltnm, ISNULL(e.cltnm, '')) AS cltnm,
                       e.mijdate,
                       STUFF(STUFF(e.mijdate, 5, 0, '-'), 8, 0, '-') AS mijdate_fmt,
                       e.mijnum,
                       ISNULL(e.remark, '') AS remark,
                       ISNULL(e.mijamt, 0)  AS mijamt,
                       ISNULL(q.iamt, 0)    AS iamt,
                       e.spjangcd
                  FROM TB_CA640_END e WITH(NOLOCK)
                  LEFT JOIN TB_XCLIENT x WITH(NOLOCK) ON x.custcd = e.custcd AND x.cltcd = e.cltcd
                  OUTER APPLY (SELECT __PAY__ AS iamt FROM TB_CA642 WITH(NOLOCK)
                                WHERE custcd = e.custcd AND spjangcd = e.spjangcd
                                  AND mijdate = e.mijdate AND mijnum = e.mijnum) q
                 WHERE e.custcd = :custcd AND e.spjangcd = :spjangcd AND e.year = :year
                 ORDER BY e.cltcd, e.mijdate, e.mijnum
                """.replace("__PAY__", PAY_SUM), p);
    }

    /** 이월 대상 미리보기 */
    public List<Map<String, Object>> previewPurchase(String spjangcd, String year) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        MapSqlParameterSource p = param(spjangcd, custcd, normYear(year));

        return sqlRunner.getRows("""
                SELECT :year AS year,
                       a.cltcd,
                       ISNULL(x.cltnm, ISNULL(a.cltnm, '')) AS cltnm,
                       a.mijdate,
                       STUFF(STUFF(a.mijdate, 5, 0, '-'), 8, 0, '-') AS mijdate_fmt,
                       a.mijnum,
                       ISNULL(a.remark, '') AS remark,
                       -- 지급이 있었으면 그 잔액, 없었으면 원금 (파워빌더 chaamt 규칙)
                       ISNULL(a.mijamt - b.iamt, ISNULL(a.mijamt, 0)) AS mijamt,
                       ISNULL(b.iamt, 0)    AS iamt,
                       a.spjangcd
                  FROM TB_CA640 a WITH(NOLOCK)
                  __PAYJOIN__
                  LEFT JOIN TB_XCLIENT x WITH(NOLOCK) ON x.custcd = a.custcd AND x.cltcd = a.cltcd
                 WHERE ISNULL(a.mijamt, 0) - ISNULL(b.iamt, 0) <> 0
                   AND a.custcd = :custcd AND a.spjangcd = :spjangcd
                   AND LEFT(a.mijdate, 4) < :year
                 ORDER BY a.cltcd, a.mijdate, a.mijnum
                """.replace("__PAYJOIN__", CLOSE_PAY_JOIN), p);
    }

    /** 마감 — 잔액이 남은 매입 전표를 TB_CA640_END 로 복사한다 */
    @Transactional
    public int closePurchase(String spjangcd, String year) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null) throw new IllegalStateException("사업장 정보를 찾을 수 없습니다.");

        MapSqlParameterSource p = param(spjangcd, custcd, normYear(year));

        if (purchaseClosedCount(p) > 0) {
            throw new IllegalStateException("이미 마감된 년도입니다. 마감취소 후 다시 실행해주세요.");
        }

        // 파워빌더가 복사하는 컬럼 그대로다 (accyn 은 원본 조회에는 있지만 복사 루프엔 없다)
        int inserted = sqlRunner.execute("""
                INSERT INTO TB_CA640_END
                      (custcd, spjangcd, year, cltcd, cltnm, mijgubun, mijdate, mijnum,
                       divicd, perid, mijamt, chaamt, schdate, billkind, taxcls, moncls, monrate,
                       hamt, eamt, enum, edate, eidate, ecltcd, samt, snum, bamt, bankcd, bankno,
                       damt, cardcd, persent, cardno, camt, ctaxamt, ggubun, gamt,
                       tax_spdate, tax_spnum, acc_spdate, acc_spnum, remark, indate, inperid,
                       sbankcd, serinum, sunflag, sunamt, jcltno, gubun, bhflag, osflag, cdflag,
                       jsflag, yyyymm, setcls, acccd, jamt, jmar, bigo, artcd, taxreclafi,
                       mijcltcd, mijcltnm, cardcltcd, cardcltnm, cltflag, cardco, cardnm, cardnum,
                       ibgdate, ibgnum, jflag)
                SELECT a.custcd, a.spjangcd, :year, a.cltcd, ISNULL(x.cltnm, a.cltnm),
                       a.mijgubun, a.mijdate, a.mijnum, a.divicd, a.perid,
                       -- 지급이 있었으면 잔액으로, 없었으면 원금 그대로 이월한다
                       ISNULL(a.mijamt - b.iamt, a.mijamt),
                       a.mijamt - b.iamt,
                       a.schdate, a.billkind, a.taxcls, a.moncls, a.monrate,
                       a.hamt, a.eamt, a.enum, a.edate, a.eidate, a.ecltcd, a.samt, a.snum, a.bamt, a.bankcd, a.bankno,
                       a.damt, a.cardcd, a.persent, a.cardno, a.camt, a.ctaxamt, a.ggubun, a.gamt,
                       a.tax_spdate, a.tax_spnum, a.acc_spdate, a.acc_spnum, a.remark, a.indate, a.inperid,
                       a.sbankcd, a.serinum, a.sunflag, a.sunamt, a.jcltno, a.gubun, a.bhflag, a.osflag, a.cdflag,
                       a.jsflag, a.yyyymm, a.setcls, a.acccd, a.jamt, a.jmar, a.bigo, a.artcd, a.taxreclafi,
                       a.mijcltcd, a.mijcltnm, a.cardcltcd, a.cardcltnm, a.cltflag, a.cardco, a.cardnm, a.cardnum,
                       a.ibgdate, a.ibgnum, a.jflag
                  FROM TB_CA640 a WITH(NOLOCK)
                  __PAYJOIN__
                  LEFT JOIN TB_XCLIENT x WITH(NOLOCK) ON x.custcd = a.custcd AND x.cltcd = a.cltcd
                 WHERE ISNULL(a.mijamt, 0) - ISNULL(b.iamt, 0) <> 0
                   AND a.custcd = :custcd AND a.spjangcd = :spjangcd
                   AND LEFT(a.mijdate, 4) < :year
                """.replace("__PAYJOIN__", CLOSE_PAY_JOIN), p);

        if (inserted == 0) throw new IllegalStateException("이월할 미지급 자료가 없습니다.");
        log.info("[년마감] 미지급 이월 {}건 (spjangcd={}, year={})", inserted, spjangcd, year);
        return inserted;
    }

    /** 마감취소 */
    @Transactional
    public int cancelPurchase(String spjangcd, String year) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null) throw new IllegalStateException("사업장 정보를 찾을 수 없습니다.");

        MapSqlParameterSource p = param(spjangcd, custcd, normYear(year));

        int deleted = sqlRunner.execute("""
                DELETE FROM TB_CA640_END
                 WHERE custcd = :custcd AND spjangcd = :spjangcd AND year = :year
                """, p);
        if (deleted == 0) throw new IllegalStateException("취소할 마감 자료가 없습니다.");
        log.info("[년마감] 미지급 마감취소 {}건 (spjangcd={}, year={})", deleted, spjangcd, year);
        return deleted;
    }

    private int purchaseClosedCount(MapSqlParameterSource p) {
        Map<String, Object> row = sqlRunner.getRow("""
                SELECT COUNT(*) AS cnt FROM TB_CA640_END WITH(NOLOCK)
                 WHERE custcd = :custcd AND spjangcd = :spjangcd AND year = :year
                """, p);
        if (row == null) throw new IllegalStateException("마감 상태를 확인하지 못했습니다.");
        return ((Number) row.get("cnt")).intValue();
    }

    /** 두 탭의 마감 여부를 한 번에 알려준다 */
    public Map<String, Object> getStatus(String spjangcd, String year) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null) return Map.of("sales", 0, "purchase", 0);

        MapSqlParameterSource p = param(spjangcd, custcd, normYear(year));
        return Map.of("sales", salesClosedCount(p), "purchase", purchaseClosedCount(p));
    }
}
