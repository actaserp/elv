package mes.app.transaction.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 매출관리 — 파워빌더 '매출등록'(w_tb_da023)
 *
 * 헤더 TB_DA023 + 상세 TB_DA024, 거래명세표 TB_DA024_PCODE(조회만).
 * 매입관리(TB_CA640/641)와 짝을 이루는 화면이다.
 *
 * 1단계 범위는 매출 등록 자체(헤더 + 상세)까지다. 파워빌더 저장 스크립트의 다음 기능은 뺐다.
 *   - 세금계산서 생성·삭제(wf_addtax, TB_IA011 / TB_IA012 / TB_IA090)
 *   - 팝빌·KTNET 발행상태 확인(GENRES_M, TB_IA090POPBILL) 과 그에 따른 수정 잠금
 *   - 증빙구분 변경 시 부가세 자료 삭제(wf_adddel)
 *   - 수리완료(tb_e471) · 출고(tb_da036) 연동(compflag)
 *   - 입금불필요 처리(misflag='1' → 헤더 bamt/gamt 자동 기입). 경기에는 misflag='1' 이 한 건도 없다
 *   - 더존 전송건 잠금(파워빌더는 custcd='samjung' 에서만 건다)
 *   - 상단 버튼들(지로고지서출력·유지보수매출생성·전자계산서·매출업로드·수리완료가져오기·매출상계·수정세금계산서)
 *
 * 파워빌더 저장 규칙 중 옮긴 것
 *   - 상세 품명(pname)이 빈 행은 저장에서 뺀다
 *   - 상세가 하나도 없으면 저장하지 않는다
 *   - 금액조정(halflag)이 아니면 헤더 amt/addamt/misamt = 상세 합계. 저장 후 한 번 더 비교해 보정한다
 *   - 적요(remark)는 저장할 때 자동으로 만든다. 상세가 2행 이상이면 '첫 품명 외 N건', 1행이면 그 품명
 *   - TB_DA026 에 수금일자가 있으면 "이미 입금되었습니다" 로 수정할 수 없다
 *   - 계산서발행(billkind)이 미발행이 아닌데 영수구분(receiptyn)이 비면 '1' 로 채운다
 *   - delflag(날짜표시 구분)는 TB_E101 의 최신 계약에서 가져온다
 *   - 전표번호(misnum)는 매출일자별 4자리 순번. 경기 53,598건 전부 그 형태이고 일자별 중복이 없다
 */
@Slf4j
@Service
public class SalesRegisterService {

    @Autowired
    SqlRunner sqlRunner;

    /** 수금액 합계식. TB_DA026 의 13개 금액 컬럼을 더한다 (파워빌더와 동일) */
    private static final String RCV_SUM = """
            ISNULL(hamt,0) + ISNULL(eamt,0) + ISNULL(samt,0) + ISNULL(bamt,0)
          + ISNULL(damt,0) + ISNULL(gamt,0) + ISNULL(jamt,0) + ISNULL(dcamt,0)
          + ISNULL(jmar,0) + ISNULL(csamt,0) + ISNULL(cmar,0) + ISNULL(cdmar,0)
          + ISNULL(sunamt,0)
            """;

    /**
     * 헤더가 직접 들고 있는 입금액.
     * 파워빌더 그리드의 미수잔액은 TB_DA026 이 아니라 이 값을 쓴다
     * (경기 2026-09-01~09-16 기준 미수 95,874,645 = 합계 95,848,905 - (-25,740) 로 확인)
     */
    private static final String IPAMOUNT = """
            ISNULL(a.hamt,0) + ISNULL(a.eamt,0) + ISNULL(a.samt,0) + ISNULL(a.bamt,0)
          + ISNULL(a.damt,0) + ISNULL(a.gamt,0) + ISNULL(a.jamt,0) + ISNULL(a.dcamt,0)
          + ISNULL(a.jmar,0) + ISNULL(a.csamt,0) + ISNULL(a.cmar,0) + ISNULL(a.cdmar,0)
          + ISNULL(a.sunamt,0)
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

    private MapSqlParameterSource base(String spjangcd, String custcd) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("custcd", custcd);
        p.addValue("spjangcd", spjangcd);
        return p;
    }

    // ────────────────────────────────────────────────────────────
    //  조회
    // ────────────────────────────────────────────────────────────

    /** 헤더 목록 (파워빌더 왼쪽 그리드) */
    public List<Map<String, Object>> getList(String spjangcd, String startDate, String endDate,
                                             String keyword, String gubun, String billgubun) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        MapSqlParameterSource p = base(spjangcd, custcd);
        p.addValue("stdate", startDate == null ? "" : startDate.replaceAll("-", ""));
        p.addValue("enddate", endDate == null ? "" : endDate.replaceAll("-", ""));
        p.addValue("keyword", keyword == null ? "" : keyword.trim());
        p.addValue("gubun", gubun == null ? "" : gubun.trim());
        p.addValue("billgubun", billgubun == null ? "" : billgubun.trim());

        String sql = """
                SELECT a.misdate,
                       a.misnum,
                       STUFF(STUFF(a.misdate, 5, 0, '-'), 8, 0, '-') AS misdate_fmt,
                       a.cltcd,
                       ISNULL(c.cltnm, '')    AS cltnm,
                       ISNULL(a.actcd, '')    AS actcd,
                       ISNULL(b.actnm, '')    AS actnm,
                       ISNULL(a.remark, '')   AS remark,
                       ISNULL(a.bigo, '')     AS bigo,
                       ISNULL(a.gubun, '')    AS gubun,
                       ISNULL(g.com_cnam, '') AS gubunnm,
                       ISNULL(a.billgubun, '') AS billgubun,
                       ISNULL(a.billkind, '') AS billkind,
                       ISNULL(a.receiptyn, '') AS receiptyn,
                       ISNULL(a.taxcls, '')   AS taxcls,
                       ISNULL(a.taxgubun, '') AS taxgubun,
                       ISNULL(t.com_cnam, '') AS taxgubunnm,
                       ISNULL(a.wkactcd, '')  AS wkactcd,
                       ISNULL(w.wkactnm, '')  AS wkactnm,
                       ISNULL(a.acccd, '')    AS acccd,
                       ISNULL(ac.accnm, '')   AS accnm,
                       ISNULL(a.yyyymm, '')   AS yyyymm,
                       ISNULL(a.divicd, '')   AS divicd,
                       ISNULL(jc.divinm, '')  AS divinm,
                       ISNULL(a.perid, '')    AS perid,
                       ISNULL(ja.pernm, '')   AS pernm,
                       ISNULL(a.misflag, '')  AS misflag,
                       ISNULL(a.accyn, '')    AS accyn,
                       ISNULL(a.bankcd, '')   AS bankcd,
                       ISNULL(a.tax_spdate, '') AS tax_spdate,
                       ISNULL(a.tax_spnum, '')  AS tax_spnum,
                       ISNULL(a.jirogubun, '') AS jirogubun,
                       ISNULL(a.jirodate, '')  AS jirodate,
                       ISNULL(a.projno, '')    AS projno,
                       ISNULL(pj.projnm, '')   AS projnm,
                       ISNULL(a.ibgdate, '')   AS ibgdate,
                       ISNULL(a.halflag, '')   AS halflag,
                       ISNULL(a.spjangnum, '') AS spjangnum,
                       ISNULL(a.emtaxbillno, '') AS emtaxbillno,
                       ISNULL(a.vatemail, '')  AS vatemail,
                       ISNULL(a.vatpernm, '')  AS vatpernm,
                       ISNULL(a.amt, 0)       AS amt,
                       ISNULL(a.addamt, 0)    AS addamt,
                       ISNULL(a.misamt, 0)    AS misamt,
                       (__IPAMOUNT__)         AS ipamount,
                       ISNULL(a.misamt, 0) - (__IPAMOUNT__) AS miamt,
                       -- 입금 형태. 파워빌더 그리드의 '형태' 컬럼과 같다
                       CASE WHEN ISNULL(a.hamt,0)   > 0 THEN '현금, '   ELSE '' END +
                       CASE WHEN ISNULL(a.bamt,0)   > 0 THEN '예금, '   ELSE '' END +
                       CASE WHEN ISNULL(a.jamt,0)   > 0 THEN '지로, '   ELSE '' END +
                       CASE WHEN ISNULL(a.csamt,0)  > 0 THEN 'CMS, '    ELSE '' END +
                       CASE WHEN ISNULL(a.eamt,0)   > 0 THEN '어음, '   ELSE '' END +
                       CASE WHEN ISNULL(a.samt,0)   > 0 THEN '수표, '   ELSE '' END +
                       CASE WHEN ISNULL(a.damt,0)   > 0 THEN '카드, '   ELSE '' END +
                       CASE WHEN ISNULL(a.dcamt,0)  > 0 THEN 'D/C, '    ELSE '' END +
                       CASE WHEN ISNULL(a.sunamt,0) > 0 THEN '선수금, ' ELSE '' END +
                       CASE WHEN ISNULL(a.gamt,0)   > 0 THEN '기타'     ELSE '' END AS ipchk
                  FROM TB_DA023 a WITH(NOLOCK)
                  LEFT JOIN TB_E601 b WITH(NOLOCK)
                    ON b.custcd = a.custcd AND b.spjangcd = a.spjangcd AND b.actcd = a.actcd
                  LEFT JOIN TB_XCLIENT c WITH(NOLOCK)
                    ON c.custcd = a.custcd AND c.cltcd = a.cltcd
                  LEFT JOIN TB_E018_1 w WITH(NOLOCK)
                    ON w.custcd = a.custcd AND w.spjangcd = a.spjangcd AND w.wkactcd = a.wkactcd
                  LEFT JOIN TB_DA003 pj WITH(NOLOCK)
                    ON pj.custcd = a.custcd AND pj.spjangcd = a.spjangcd AND pj.projno = a.projno
                  LEFT JOIN TB_JC002 jc WITH(NOLOCK)
                    ON jc.custcd = a.custcd AND jc.spjangcd = a.spjangcd AND jc.divicd = a.divicd
                  LEFT JOIN TB_JA001 ja WITH(NOLOCK)
                    ON ja.custcd = a.custcd AND ja.spjangcd = a.spjangcd
                   AND a.perid = SUBSTRING(ja.perid, 2, 10)
                  LEFT JOIN TB_CA510 g WITH(NOLOCK) ON g.com_cls = '013' AND g.com_code = a.gubun
                  LEFT JOIN TB_CA510 t WITH(NOLOCK) ON t.com_cls = '006' AND t.com_code = a.taxgubun
                  LEFT JOIN tb_ac001 ac WITH(NOLOCK) ON ac.custcd = a.custcd AND ac.acccd = a.acccd
                 WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd
                   AND a.misdate BETWEEN :stdate AND :enddate
                   AND (:keyword = '' OR a.cltcd LIKE '%' + :keyword + '%'
                                      OR ISNULL(c.cltnm, '')  LIKE '%' + :keyword + '%'
                                      OR a.actcd LIKE '%' + :keyword + '%'
                                      OR ISNULL(b.actnm, '')  LIKE '%' + :keyword + '%'
                                      OR ISNULL(a.remark, '') LIKE '%' + :keyword + '%')
                   AND (:gubun = '' OR a.gubun = :gubun)
                   AND (:billgubun = '' OR a.billgubun = :billgubun)
                 ORDER BY a.misdate, a.misnum
                """.replace("__IPAMOUNT__", IPAMOUNT);

        return sqlRunner.getRows(sql, p);
    }

    /** 매출상세 (TB_DA024) */
    public List<Map<String, Object>> getDetail(String spjangcd, String misdate, String misnum, String cltcd) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        MapSqlParameterSource p = keyParam(spjangcd, custcd, misdate, misnum, cltcd);

        return sqlRunner.getRows("""
                SELECT d.seq,
                       ISNULL(d.pcode, '')  AS pcode,
                       ISNULL(d.pname, '')  AS pname,
                       ISNULL(d.psize, '')  AS psize,
                       ISNULL(d.punit, '')  AS punit,
                       ISNULL(d.qty, 0)     AS qty,
                       ISNULL(d.uamt, 0)    AS uamt,
                       ISNULL(d.samt, 0)    AS samt,
                       ISNULL(d.addamt, 0)  AS addamt,
                       ISNULL(d.amt, 0)     AS amt,
                       ISNULL(d.actcd, '')  AS actcd,
                       ISNULL(e.actnm, '')  AS actnm,
                       ISNULL(d.equpcd, '') AS equpcd,
                       ISNULL(d.equpnm, '') AS equpnm
                  FROM TB_DA024 d WITH(NOLOCK)
                  LEFT JOIN TB_E601 e WITH(NOLOCK)
                    ON e.custcd = d.custcd AND e.spjangcd = d.spjangcd AND e.actcd = d.actcd
                 WHERE d.custcd = :custcd AND d.spjangcd = :spjangcd
                   AND d.misdate = :misdate AND d.misnum = :misnum AND d.cltcd = :cltcd
                 ORDER BY d.seq
                """, p);
    }

    /** 거래명세표 (TB_DA024_PCODE). 이 화면에서는 조회만 한다 */
    public List<Map<String, Object>> getPcodeList(String spjangcd, String misdate, String misnum, String cltcd) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        MapSqlParameterSource p = keyParam(spjangcd, custcd, misdate, misnum, cltcd);

        return sqlRunner.getRows("""
                SELECT seq,
                       ISNULL(pcode, '')  AS pcode,
                       ISNULL(pname, '')  AS pname,
                       ISNULL(psize, '')  AS psize,
                       ISNULL(punit, '')  AS punit,
                       ISNULL(qty, 0)     AS qty,
                       ISNULL(uamt, 0)    AS uamt,
                       ISNULL(samt, 0)    AS samt,
                       ISNULL(tamt, 0)    AS tamt,
                       ISNULL(misamt, 0)  AS misamt,
                       ISNULL(actcd, '')  AS actcd,
                       ISNULL(equpcd, '') AS equpcd,
                       ISNULL(equpnm, '') AS equpnm
                  FROM TB_DA024_PCODE WITH(NOLOCK)
                 WHERE custcd = :custcd AND spjangcd = :spjangcd
                   AND misdate = :misdate AND misnum = :misnum AND cltcd = :cltcd
                 ORDER BY seq
                """, p);
    }

    /** 현장 목록 (TB_E601). 현장을 고르면 거래처·회사구분·부서·담당자까지 따라온다 */
    public List<Map<String, Object>> getActList(String spjangcd, String keyword) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        MapSqlParameterSource p = base(spjangcd, custcd);
        p.addValue("keyword", keyword == null ? "" : keyword.trim());

        return sqlRunner.getRows("""
                SELECT e.actcd,
                       ISNULL(e.actnm, '')   AS actnm,
                       ISNULL(e.cltcd, '')   AS cltcd,
                       ISNULL(c.cltnm, '')   AS cltnm,
                       ISNULL(e.wkactcd, '') AS wkactcd,
                       ISNULL(e.divicd, '')  AS divicd,
                       ISNULL(e.perid, '')   AS perid,
                       ISNULL(e.address, '') AS address
                  FROM TB_E601 e WITH(NOLOCK)
                  LEFT JOIN TB_XCLIENT c WITH(NOLOCK) ON c.custcd = e.custcd AND c.cltcd = e.cltcd
                 WHERE e.custcd = :custcd AND e.spjangcd = :spjangcd
                   AND (:keyword = '' OR e.actcd LIKE '%' + :keyword + '%'
                                      OR ISNULL(e.actnm, '') LIKE '%' + :keyword + '%')
                 ORDER BY e.actnm
                """, p);
    }

    // ────────────────────────────────────────────────────────────
    //  저장 / 삭제
    // ────────────────────────────────────────────────────────────

    /** 이 전표로 들어온 수금의 마지막 수금일자. 있으면 파워빌더는 수정을 막는다 */
    public String receivedDate(String spjangcd, String custcd, String misdate, String misnum) {
        MapSqlParameterSource p = base(spjangcd, custcd);
        p.addValue("misdate", misdate);
        p.addValue("misnum", misnum);
        Map<String, Object> row = sqlRunner.getRow("""
                SELECT MAX(ISNULL(rcvdate, '')) AS rcvdate
                  FROM TB_DA026 WITH(NOLOCK)
                 WHERE custcd = :custcd AND spjangcd = :spjangcd
                   AND misdate = :misdate AND misnum = :misnum
                """, p);
        if (row == null) throw new IllegalStateException("입금내역을 확인하지 못했습니다.");
        Object v = row.get("rcvdate");
        return v == null ? "" : String.valueOf(v).trim();
    }

    /**
     * 헤더 + 상세 저장.
     *
     * @return 저장된 전표의 {misdate, misnum, cltcd}
     */
    @Transactional
    public Map<String, String> save(String spjangcd, Map<String, Object> header,
                                    List<Map<String, Object>> details, String userId) {

        String custcd = getCustcd(spjangcd);
        if (custcd == null) throw new IllegalStateException("사업장 정보를 찾을 수 없습니다.");

        String misdate = str(header.get("misdate")).replaceAll("-", "");
        if (!misdate.matches("\\d{8}")) throw new IllegalStateException("매출일자를 다시 선택해주세요.");

        String ibgdate = str(header.get("ibgdate")).replaceAll("-", "");
        if (!ibgdate.isBlank() && !ibgdate.matches("\\d{8}")) {
            throw new IllegalStateException("수금예정일자를 다시 선택해주세요.");
        }

        String cltcd = str(header.get("cltcd")).trim();
        if (cltcd.isEmpty()) throw new IllegalStateException("거래처를 선택해주세요.");

        String misnum = str(header.get("misnum")).trim();
        String orgCltcd = str(header.get("orgCltcd")).trim();
        boolean isNew = misnum.isEmpty();

        // 품명이 빈 상세는 저장하지 않는다 (파워빌더도 그 행을 지운다)
        List<Map<String, Object>> rows = new ArrayList<>();
        double sumSamt = 0, sumAddamt = 0;
        if (details != null) {
            for (Map<String, Object> d : details) {
                if (str(d.get("pname")).isBlank()) continue;
                rows.add(d);
                sumSamt += num(d.get("samt"));
                sumAddamt += num(d.get("addamt"));
            }
        }
        if (rows.isEmpty()) throw new IllegalStateException("매출할 품목이 없습니다.");

        // 수정이면 입금 여부부터 확인한다
        if (!isNew && !receivedDate(spjangcd, custcd, misdate, misnum).isEmpty()) {
            throw new IllegalStateException("해당 매출건은 이미 입금되었습니다.");
        }

        if (isNew) misnum = nextMisnum(spjangcd, custcd, misdate);

        // 금액조정을 켜면 화면에 입력한 금액을 그대로 쓴다 (파워빌더와 동일)
        String halflag = str(header.get("halflag")).isBlank() ? "0" : str(header.get("halflag"));
        double amt, addamt, misamt;
        if ("1".equals(halflag)) {
            amt = num(header.get("amt"));
            addamt = num(header.get("addamt"));
            misamt = num(header.get("misamt"));
        } else {
            amt = sumSamt;
            addamt = sumAddamt;
            misamt = sumSamt + sumAddamt;
        }

        // 적요는 저장할 때 상세에서 만든다
        String firstName = str(rows.get(0).get("pname"));
        String remark = rows.size() > 1 ? firstName + " 외 " + (rows.size() - 1) + "건" : firstName;
        if (remark.length() > 255) remark = remark.substring(0, 255);

        // 계산서를 발행하는 건인데 영수구분이 비면 '1' 로 채운다
        String billkind = str(header.get("billkind")).isBlank() ? "1" : str(header.get("billkind"));
        String receiptyn = str(header.get("receiptyn"));
        if (!"0".equals(billkind) && receiptyn.isBlank()) receiptyn = "1";

        String actcd = str(header.get("actcd"));

        MapSqlParameterSource p = base(spjangcd, custcd);
        p.addValue("cltcd", cltcd);
        p.addValue("misdate", misdate);
        p.addValue("misnum", misnum);
        p.addValue("actcd", actcd);
        p.addValue("wkactcd", str(header.get("wkactcd")));
        p.addValue("gubun", str(header.get("gubun")));
        p.addValue("billgubun", str(header.get("billgubun")).isBlank() ? "1" : str(header.get("billgubun")));
        p.addValue("billkind", billkind);
        p.addValue("receiptyn", receiptyn);
        p.addValue("taxcls", str(header.get("taxcls")).isBlank() ? "0" : str(header.get("taxcls")));
        p.addValue("taxgubun", str(header.get("taxgubun")).isBlank() ? "01" : str(header.get("taxgubun")));
        p.addValue("acccd", str(header.get("acccd")));
        // 귀속년월이 비면 매출일자의 년월로 채운다
        String yyyymm = str(header.get("yyyymm")).replaceAll("-", "");
        p.addValue("yyyymm", yyyymm.isBlank() ? misdate.substring(0, 6) : yyyymm);
        p.addValue("divicd", str(header.get("divicd")));
        p.addValue("perid", str(header.get("perid")));
        p.addValue("misflag", str(header.get("misflag")));
        p.addValue("accyn", str(header.get("accyn")).isBlank() ? "0" : str(header.get("accyn")));
        p.addValue("bankcd", str(header.get("bankcd")));
        p.addValue("tax_spdate", str(header.get("tax_spdate")).replaceAll("-", ""));
        p.addValue("tax_spnum", str(header.get("tax_spnum")));
        p.addValue("jirogubun", str(header.get("jirogubun")));
        p.addValue("jirodate", str(header.get("jirodate")).replaceAll("-", ""));
        p.addValue("projno", str(header.get("projno")));
        p.addValue("ibgdate", ibgdate);
        p.addValue("halflag", halflag);
        p.addValue("spjangnum", str(header.get("spjangnum")));
        p.addValue("emtaxbillno", str(header.get("emtaxbillno")));
        p.addValue("vatemail", str(header.get("vatemail")));
        p.addValue("vatpernm", str(header.get("vatpernm")));
        p.addValue("bigo", str(header.get("bigo")));
        p.addValue("remark", remark);
        p.addValue("amt", amt);
        p.addValue("addamt", addamt);
        p.addValue("misamt", misamt);
        p.addValue("delflag", contractDelflag(spjangcd, custcd, cltcd, actcd));
        p.addValue("inperid", userId == null ? "" : userId);

        // 거래처가 키의 일부라 거래처를 바꾸면 예전 키로 들어간 자료를 먼저 지운다
        boolean keyChanged = !isNew && !orgCltcd.isEmpty() && !orgCltcd.equals(cltcd);
        if (keyChanged) deleteRows(spjangcd, custcd, misdate, misnum, orgCltcd);

        int affected;
        if (isNew || keyChanged) {
            affected = sqlRunner.execute("""
                    INSERT INTO TB_DA023 (custcd, spjangcd, cltcd, misgubun, misdate, misnum,
                                          actcd, wkactcd, gubun, billgubun, billkind, receiptyn,
                                          taxcls, taxgubun, acccd, yyyymm, divicd, perid,
                                          misflag, accyn, bankcd, tax_spdate, tax_spnum,
                                          jirogubun, jirodate, projno, ibgdate, halflag,
                                          spjangnum, emtaxbillno, vatemail, vatpernm,
                                          remark, bigo, amt, addamt, misamt, delflag,
                                          indate, inperid)
                    VALUES (:custcd, :spjangcd, :cltcd, '0', :misdate, :misnum,
                            :actcd, :wkactcd, :gubun, :billgubun, :billkind, :receiptyn,
                            :taxcls, :taxgubun, :acccd, :yyyymm, :divicd, :perid,
                            :misflag, :accyn, :bankcd, :tax_spdate, :tax_spnum,
                            :jirogubun, :jirodate, :projno, :ibgdate, :halflag,
                            :spjangnum, :emtaxbillno, :vatemail, :vatpernm,
                            :remark, :bigo, :amt, :addamt, :misamt, :delflag,
                            CONVERT(varchar(8), GETDATE(), 112), :inperid)
                    """, p);
        } else {
            affected = sqlRunner.execute("""
                    UPDATE TB_DA023
                       SET actcd = :actcd, wkactcd = :wkactcd, gubun = :gubun,
                           billgubun = :billgubun, billkind = :billkind, receiptyn = :receiptyn,
                           taxcls = :taxcls, taxgubun = :taxgubun, acccd = :acccd, yyyymm = :yyyymm,
                           divicd = :divicd, perid = :perid, misflag = :misflag, accyn = :accyn,
                           bankcd = :bankcd, tax_spdate = :tax_spdate, tax_spnum = :tax_spnum,
                           jirogubun = :jirogubun, jirodate = :jirodate, projno = :projno,
                           ibgdate = :ibgdate, halflag = :halflag, spjangnum = :spjangnum,
                           emtaxbillno = :emtaxbillno, vatemail = :vatemail, vatpernm = :vatpernm,
                           remark = :remark, bigo = :bigo,
                           amt = :amt, addamt = :addamt, misamt = :misamt, delflag = :delflag,
                           indate = CONVERT(varchar(8), GETDATE(), 112), inperid = :inperid
                     WHERE custcd = :custcd AND spjangcd = :spjangcd AND cltcd = :cltcd
                       AND misgubun = '0' AND misdate = :misdate AND misnum = :misnum
                    """, p);
        }
        if (affected == 0) throw new IllegalStateException("매출 저장에 실패했습니다.");

        // 상세는 통째로 다시 넣는다 (파워빌더는 행 단위로 갱신하지만 결과는 같다)
        MapSqlParameterSource dp = keyParam(spjangcd, custcd, misdate, misnum, cltcd);
        sqlRunner.execute("""
                DELETE FROM TB_DA024
                 WHERE custcd = :custcd AND spjangcd = :spjangcd AND cltcd = :cltcd
                   AND misdate = :misdate AND misnum = :misnum
                """, dp);

        int seq = 1;
        for (Map<String, Object> d : rows) {
            MapSqlParameterSource rp = keyParam(spjangcd, custcd, misdate, misnum, cltcd);
            rp.addValue("seq", String.format("%03d", seq++));
            rp.addValue("pcode", str(d.get("pcode")));
            rp.addValue("pname", str(d.get("pname")));
            rp.addValue("psize", str(d.get("psize")));
            rp.addValue("punit", str(d.get("punit")));
            rp.addValue("qty", num(d.get("qty")));
            rp.addValue("uamt", num(d.get("uamt")));
            rp.addValue("samt", num(d.get("samt")));
            rp.addValue("addamt", num(d.get("addamt")));
            rp.addValue("amt", num(d.get("samt")) + num(d.get("addamt")));
            // 상세 현장이 비면 헤더 현장을 쓴다 (파워빌더도 헤더 현장을 내려준다)
            rp.addValue("actcd", str(d.get("actcd")).isBlank() ? actcd : str(d.get("actcd")));
            rp.addValue("equpcd", str(d.get("equpcd")));
            rp.addValue("equpnm", str(d.get("equpnm")));
            rp.addValue("inperid", userId == null ? "" : userId);

            int ins = sqlRunner.execute("""
                    INSERT INTO TB_DA024 (custcd, spjangcd, cltcd, misdate, misnum, seq,
                                          pcode, pname, psize, punit, qty, uamt, samt, addamt, amt,
                                          actcd, equpcd, equpnm, indate, inperid)
                    VALUES (:custcd, :spjangcd, :cltcd, :misdate, :misnum, :seq,
                            :pcode, :pname, :psize, :punit, :qty, :uamt, :samt, :addamt, :amt,
                            :actcd, :equpcd, :equpnm, CONVERT(varchar(8), GETDATE(), 112), :inperid)
                    """, rp);
            if (ins == 0) throw new IllegalStateException("매출상세가 등록되지 않았습니다.");
        }

        // 파워빌더도 저장 뒤 헤더·상세 금액을 한 번 더 맞춘다. 금액조정 건은 건드리지 않는다
        if (!"1".equals(halflag)) {
            sqlRunner.execute("""
                    UPDATE TB_DA023
                       SET amt    = (SELECT SUM(ISNULL(samt, 0))   FROM TB_DA024 WITH(NOLOCK)
                                      WHERE TB_DA024.custcd = TB_DA023.custcd
                                        AND TB_DA024.spjangcd = TB_DA023.spjangcd
                                        AND TB_DA024.cltcd = TB_DA023.cltcd
                                        AND TB_DA024.misdate = TB_DA023.misdate
                                        AND TB_DA024.misnum = TB_DA023.misnum),
                           addamt = (SELECT SUM(ISNULL(addamt, 0)) FROM TB_DA024 WITH(NOLOCK)
                                      WHERE TB_DA024.custcd = TB_DA023.custcd
                                        AND TB_DA024.spjangcd = TB_DA023.spjangcd
                                        AND TB_DA024.cltcd = TB_DA023.cltcd
                                        AND TB_DA024.misdate = TB_DA023.misdate
                                        AND TB_DA024.misnum = TB_DA023.misnum),
                           misamt = (SELECT SUM(ISNULL(samt, 0)) + SUM(ISNULL(addamt, 0)) FROM TB_DA024 WITH(NOLOCK)
                                      WHERE TB_DA024.custcd = TB_DA023.custcd
                                        AND TB_DA024.spjangcd = TB_DA023.spjangcd
                                        AND TB_DA024.cltcd = TB_DA023.cltcd
                                        AND TB_DA024.misdate = TB_DA023.misdate
                                        AND TB_DA024.misnum = TB_DA023.misnum)
                     WHERE custcd = :custcd AND spjangcd = :spjangcd AND cltcd = :cltcd
                       AND misdate = :misdate AND misnum = :misnum
                    """, dp);
        }

        return Map.of("misdate", misdate, "misnum", misnum, "cltcd", cltcd);
    }

    /**
     * 전표번호 채번 — 매출일자별 MAX+1.
     * 파워빌더도 화면에서 번호를 만들기 때문에 같은 순간에 저장하면 겹칠 수 있다.
     * 그래서 잠금을 걸고 뽑은 뒤, 이미 있는 번호면 다시 뽑는다 (매입관리와 같은 방식).
     */
    private String nextMisnum(String spjangcd, String custcd, String misdate) {
        MapSqlParameterSource p = base(spjangcd, custcd);
        p.addValue("misdate", misdate);

        for (int attempt = 0; attempt < 3; attempt++) {
            Map<String, Object> row = sqlRunner.getRow("""
                    SELECT ISNULL(MAX(TRY_CAST(misnum AS int)), 0) + 1 AS nextnum
                      FROM TB_DA023 WITH(UPDLOCK, HOLDLOCK)
                     WHERE custcd = :custcd AND spjangcd = :spjangcd AND misdate = :misdate
                    """, p);
            if (row == null) throw new IllegalStateException("전표번호를 채번하지 못했습니다.");

            int next = ((Number) row.get("nextnum")).intValue();
            // misnum 은 varchar(4) 라 5자리가 되면 더 못 넣는다
            if (next > 9999) throw new IllegalStateException("해당 일자의 전표번호를 모두 사용했습니다.");
            String candidate = String.format("%04d", next);
            p.addValue("misnum", candidate);

            Map<String, Object> dup = sqlRunner.getRow("""
                    SELECT COUNT(*) AS cnt FROM TB_DA023 WITH(NOLOCK)
                     WHERE custcd = :custcd AND spjangcd = :spjangcd
                       AND misdate = :misdate AND misnum = :misnum
                    """, p);
            if (dup != null && ((Number) dup.get("cnt")).intValue() == 0) return candidate;

            log.warn("[매출관리] 전표번호 충돌 misdate={}, misnum={} — 재채번", misdate, candidate);
        }
        throw new IllegalStateException("전표번호가 계속 충돌합니다. 잠시 후 다시 저장해주세요.");
    }

    /** 날짜표시 구분 — 해당 현장의 가장 최근 계약(TB_E101)에서 가져온다 */
    private String contractDelflag(String spjangcd, String custcd, String cltcd, String actcd) {
        if (actcd == null || actcd.isBlank()) return "";

        MapSqlParameterSource p = base(spjangcd, custcd);
        p.addValue("cltcd", cltcd);
        p.addValue("actcd", actcd);

        Map<String, Object> row = sqlRunner.getRow("""
                SELECT MAX(a.delflag) AS delflag
                  FROM TB_E101 a WITH(NOLOCK)
                  JOIN (SELECT custcd, spjangcd, cltcd, actcd, MAX(contdate) AS contdate
                          FROM TB_E101 WITH(NOLOCK)
                         GROUP BY custcd, spjangcd, cltcd, actcd) b
                    ON a.custcd = b.custcd AND a.spjangcd = b.spjangcd
                   AND a.cltcd = b.cltcd AND a.actcd = b.actcd AND a.contdate = b.contdate
                 WHERE a.custcd = :custcd AND a.spjangcd = :spjangcd
                   AND a.cltcd = :cltcd AND a.actcd = :actcd
                """, p);
        if (row == null || row.get("delflag") == null) return "";
        return String.valueOf(row.get("delflag")).trim();
    }

    /** 삭제 — 입금된 건은 막는다 */
    @Transactional
    public void delete(String spjangcd, String misdate, String misnum, String cltcd) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null) throw new IllegalStateException("사업장 정보를 찾을 수 없습니다.");

        String date = misdate == null ? "" : misdate.replaceAll("-", "");
        if (!receivedDate(spjangcd, custcd, date, misnum).isEmpty()) {
            throw new IllegalStateException("해당 매출건은 이미 입금되어 삭제할 수 없습니다.");
        }

        int deleted = deleteRows(spjangcd, custcd, date, misnum, cltcd);
        if (deleted == 0) throw new IllegalStateException("삭제할 매출을 찾을 수 없습니다.");
    }

    /** 헤더 + 상세 + 거래명세표를 한 키로 지운다 */
    private int deleteRows(String spjangcd, String custcd, String misdate, String misnum, String cltcd) {
        MapSqlParameterSource p = keyParam(spjangcd, custcd, misdate, misnum, cltcd);

        sqlRunner.execute("""
                DELETE FROM TB_DA024
                 WHERE custcd = :custcd AND spjangcd = :spjangcd AND cltcd = :cltcd
                   AND misdate = :misdate AND misnum = :misnum
                """, p);
        sqlRunner.execute("""
                DELETE FROM TB_DA024_PCODE
                 WHERE custcd = :custcd AND spjangcd = :spjangcd AND cltcd = :cltcd
                   AND misdate = :misdate AND misnum = :misnum
                """, p);
        return sqlRunner.execute("""
                DELETE FROM TB_DA023
                 WHERE custcd = :custcd AND spjangcd = :spjangcd AND cltcd = :cltcd
                   AND misdate = :misdate AND misnum = :misnum
                """, p);
    }

    private MapSqlParameterSource keyParam(String spjangcd, String custcd,
                                           String misdate, String misnum, String cltcd) {
        MapSqlParameterSource p = base(spjangcd, custcd);
        p.addValue("misdate", misdate == null ? "" : misdate.replaceAll("-", ""));
        p.addValue("misnum", misnum == null ? "" : misnum);
        p.addValue("cltcd", cltcd == null ? "" : cltcd);
        return p;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static double num(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.doubleValue();
        String s = String.valueOf(o).replaceAll(",", "").trim();
        if (s.isEmpty()) return 0;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }
}
