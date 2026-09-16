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
 * 매입관리 — 파워빌더 '비용등록'(w_tb_ca640)
 *
 * 헤더 TB_CA640 + 상세 TB_CA641. 파워빌더 화면 이름이 '비용등록' 이라 헷갈리는데 우리 매입관리와 같은 자료다.
 *
 * 1단계 범위는 매입 등록 자체(헤더 + 상세)까지다. 파워빌더 저장 스크립트의 다음 기능은 뺐다.
 *   - 자동지급처리(etflag='1' → TB_CA642 생성 + hamt/bamt 기록)  ※ 헤더 지급액 컬럼은 건드리지 않는다
 *   - 증빙구분별 매입증빙(부가세) 자료 생성 — wf_addtax01/02/03, TB_IA055
 *   - 자재입고 연동(tb_ca611/tb_ca613 의 mijflag 갱신)
 *   - 더존 전송건 잠금(파워빌더는 custcd='samjung' 에서만 건다)
 *
 * 파워빌더 저장 규칙 중 옮긴 것
 *   - 상세 적요(remark)가 빈 행은 저장에서 뺀다
 *   - 헤더 mijamt = 상세 mijamt 합계 (파워빌더도 저장 후 한 번 더 비교해 보정한다)
 *   - 상세가 없거나 공급가액 합계가 0 이면 저장하지 않는다
 *   - 지급예정일(schdate)이 비어 있으면 매입일자로 채운다
 *   - TB_CA642 에 지급액이 있으면 수정할 수 없다
 *   - 전표번호(mijnum)는 매입일자별 4자리 순번. 경기 5,782건 전부 그 형태이고 중복이 없다
 */
@Slf4j
@Service
public class PurchaseInvoiceService {

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
                                             String keyword, String gubun, String bhflag) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        MapSqlParameterSource p = base(spjangcd, custcd);
        p.addValue("stdate", startDate == null ? "" : startDate.replaceAll("-", ""));
        p.addValue("enddate", endDate == null ? "" : endDate.replaceAll("-", ""));
        p.addValue("keyword", keyword == null ? "" : keyword.trim());
        p.addValue("gubun", gubun == null ? "" : gubun.trim());
        p.addValue("bhflag", bhflag == null ? "" : bhflag.trim());

        String sql = """
                SELECT h.mijdate,
                       h.mijnum,
                       STUFF(STUFF(h.mijdate, 5, 0, '-'), 8, 0, '-') AS mijdate_fmt,
                       ISNULL(h.remark, '')    AS remark,
                       h.cltcd,
                       ISNULL(h.cltnm, '')     AS cltnm,
                       ISNULL(h.mijcltcd, '')  AS mijcltcd,
                       ISNULL(h.mijcltnm, '')  AS mijcltnm,
                       ISNULL(h.gubun, '')     AS gubun,
                       ISNULL(g.com_cnam, '')  AS gubunnm,
                       ISNULL(h.bhflag, '')    AS bhflag,
                       ISNULL(h.mijamt, 0)     AS mijamt,
                       ISNULL(h.artcd, '')     AS artcd,
                       ISNULL(art.artnm, '')   AS artnm,
                       ISNULL(h.divicd, '')    AS divicd,
                       ISNULL(e.divinm, '')    AS divinm,
                       ISNULL(h.actcd, '')     AS actcd,
                       ISNULL(q.actnm, '')     AS actnm,
                       ISNULL(h.acccd, '')     AS acccd,
                       ISNULL(h.schdate, '')   AS schdate,
                       ISNULL(h.tax_spdate, '') AS tax_spdate,
                       ISNULL(h.taxreclafi, '') AS taxreclafi,
                       ISNULL(tx.nm, '')       AS taxrenm,
                       ISNULL(h.billkind, '')  AS billkind,
                       ISNULL(h.yyyymm, '')    AS yyyymm,
                       ISNULL(h.bigo, '')      AS bigo,
                       -- 이미 지급된 금액. 0 보다 크면 수정할 수 없다 (파워빌더와 동일)
                       ISNULL(pay.iamt, 0)     AS iamt,
                       CASE WHEN ISNULL(pay.iamt, 0) > 0 THEN '지급' ELSE '미지급' END AS payyn
                  FROM TB_CA640 h WITH(NOLOCK)
                  LEFT JOIN TB_CA510 g WITH(NOLOCK) ON g.com_cls = '113' AND g.com_code = h.gubun
                  LEFT JOIN TB_JC002 e WITH(NOLOCK)
                    ON e.custcd = h.custcd AND e.spjangcd = h.spjangcd AND e.divicd = h.divicd
                  LEFT JOIN TB_E601 q WITH(NOLOCK)
                    ON q.custcd = h.custcd AND q.spjangcd = h.spjangcd AND q.actcd = h.actcd
                  OUTER APPLY (SELECT TOP 1 artnm FROM TB_CA648 WITH(NOLOCK)
                                WHERE TB_CA648.custcd = h.custcd AND TB_CA648.spjangcd = h.spjangcd
                                  AND TB_CA648.artcd = h.artcd) art
                  OUTER APPLY (SELECT TOP 1 nm FROM TB_IZ903 WITH(NOLOCK) WHERE TB_IZ903.cd = h.taxreclafi) tx
                  OUTER APPLY (SELECT SUM(ISNULL(a.hamt,0) + ISNULL(a.eamt,0) + ISNULL(a.samt,0)
                                        + ISNULL(a.bamt,0) + ISNULL(a.damt,0) + ISNULL(a.gamt,0)) AS iamt
                                 FROM TB_CA642 a WITH(NOLOCK)
                                WHERE a.custcd = h.custcd AND a.spjangcd = h.spjangcd
                                  AND a.mijdate = h.mijdate AND a.mijnum = h.mijnum) pay
                 WHERE h.custcd = :custcd AND h.spjangcd = :spjangcd
                   AND h.mijdate BETWEEN :stdate AND :enddate
                   AND (:keyword = '' OR h.cltcd LIKE '%' + :keyword + '%'
                                      OR ISNULL(h.cltnm, '') LIKE '%' + :keyword + '%'
                                      OR ISNULL(h.remark, '') LIKE '%' + :keyword + '%')
                   AND (:gubun = '' OR h.gubun = :gubun)
                   AND (:bhflag = '' OR h.bhflag = :bhflag)
                 ORDER BY h.mijdate, h.mijnum
                """;

        return sqlRunner.getRows(sql, p);
    }

    /** 상세 (파워빌더 '비용상세' 탭) */
    public List<Map<String, Object>> getDetail(String spjangcd, String mijdate, String mijnum) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        MapSqlParameterSource p = base(spjangcd, custcd);
        p.addValue("mijdate", mijdate == null ? "" : mijdate.replaceAll("-", ""));
        p.addValue("mijnum", mijnum == null ? "" : mijnum);

        String sql = """
                SELECT d.seq,
                       ISNULL(d.remark, '') AS remark,
                       ISNULL(d.size, '')   AS size,
                       ISNULL(d.unit, '')   AS unit,
                       ISNULL(d.qty, 0)     AS qty,
                       ISNULL(d.uamt, 0)    AS uamt,
                       ISNULL(d.samt, 0)    AS samt,
                       ISNULL(d.tamt, 0)    AS tamt,
                       ISNULL(d.mijamt, 0)  AS mijamt,
                       ISNULL(d.actcd, '')  AS actcd,
                       ISNULL(d.projno, '') AS projno,
                       ISNULL(d.projectnm, '') AS projectnm,
                       ISNULL(d.acc_spdate, '') AS acc_spdate,
                       ISNULL(d.acc_spnum, '')  AS acc_spnum
                  FROM TB_CA641 d WITH(NOLOCK)
                 WHERE d.custcd = :custcd AND d.spjangcd = :spjangcd
                   AND d.mijdate = :mijdate AND d.mijnum = :mijnum
                 ORDER BY d.seq
                """;

        return sqlRunner.getRows(sql, p);
    }

    /** 거래명세표 탭 (TB_CA641_PCODE). 조회만 한다. */
    public List<Map<String, Object>> getPcodeList(String spjangcd, String mijdate, String mijnum) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        MapSqlParameterSource p = base(spjangcd, custcd);
        p.addValue("mijdate", mijdate == null ? "" : mijdate.replaceAll("-", ""));
        p.addValue("mijnum", mijnum == null ? "" : mijnum);

        return sqlRunner.getRows("""
                SELECT seq, ISNULL(pcode, '') AS pcode, ISNULL(pname, '') AS pname,
                       ISNULL(psize, '') AS psize, ISNULL(punit, '') AS punit,
                       ISNULL(qty, 0) AS qty, ISNULL(uamt, 0) AS uamt,
                       ISNULL(samt, 0) AS samt, ISNULL(tamt, 0) AS tamt, ISNULL(mijamt, 0) AS mijamt,
                       ISNULL(ibgdate, '') AS ibgdate, ISNULL(ibgnum, '') AS ibgnum
                  FROM TB_CA641_PCODE WITH(NOLOCK)
                 WHERE custcd = :custcd AND spjangcd = :spjangcd
                   AND mijdate = :mijdate AND mijnum = :mijnum
                 ORDER BY seq
                """, p);
    }

    // ────────────────────────────────────────────────────────────
    //  저장 / 삭제
    // ────────────────────────────────────────────────────────────

    /** 이미 지급된 금액 (파워빌더가 수정을 막는 기준) */
    public double paidAmount(String spjangcd, String custcd, String mijdate, String mijnum) {
        MapSqlParameterSource p = base(spjangcd, custcd);
        p.addValue("mijdate", mijdate);
        p.addValue("mijnum", mijnum);
        Map<String, Object> row = sqlRunner.getRow("""
                SELECT ISNULL(SUM(ISNULL(hamt,0) + ISNULL(eamt,0) + ISNULL(samt,0)
                                + ISNULL(bamt,0) + ISNULL(damt,0) + ISNULL(gamt,0)), 0) AS iamt
                  FROM TB_CA642 WITH(NOLOCK)
                 WHERE custcd = :custcd AND spjangcd = :spjangcd
                   AND mijdate = :mijdate AND mijnum = :mijnum
                """, p);
        if (row == null) throw new IllegalStateException("지급내역을 확인하지 못했습니다.");
        return ((Number) row.get("iamt")).doubleValue();
    }

    /**
     * 헤더 + 상세 저장.
     *
     * @return 저장된 전표의 {mijdate, mijnum}
     */
    @Transactional
    public Map<String, String> save(String spjangcd, Map<String, Object> header,
                                    List<Map<String, Object>> details, String userId) {

        String custcd = getCustcd(spjangcd);
        if (custcd == null) throw new IllegalStateException("사업장 정보를 찾을 수 없습니다.");

        String mijdate = str(header.get("mijdate")).replaceAll("-", "");
        if (mijdate.length() != 8 || !mijdate.matches("\\d{8}")) {
            throw new IllegalStateException("매입일자를 다시 입력해주세요.");
        }
        String mijnum = str(header.get("mijnum")).trim();
        boolean isNew = mijnum.isEmpty();

        // 적요가 빈 상세는 저장하지 않는다 (파워빌더도 그 행을 지운다)
        List<Map<String, Object>> rows = new ArrayList<>();
        double sumSamt = 0, sumMijamt = 0;
        if (details != null) {
            for (Map<String, Object> d : details) {
                if (str(d.get("remark")).isBlank()) continue;
                rows.add(d);
                sumSamt += num(d.get("samt"));
                sumMijamt += num(d.get("mijamt"));
            }
        }
        if (rows.isEmpty()) throw new IllegalStateException("매입 상세를 입력해주세요.");
        if (sumSamt == 0 || sumMijamt == 0) throw new IllegalStateException("공급가액을 입력해주세요.");

        // 수정이면 지급 여부부터 확인한다
        if (!isNew && paidAmount(spjangcd, custcd, mijdate, mijnum) > 0) {
            throw new IllegalStateException("이미 지급된 내역이 있어 수정할 수 없습니다.");
        }

        if (isNew) mijnum = nextMijnum(spjangcd, custcd, mijdate);

        MapSqlParameterSource p = base(spjangcd, custcd);
        p.addValue("mijdate", mijdate);
        p.addValue("mijnum", mijnum);
        p.addValue("cltcd", str(header.get("cltcd")));
        p.addValue("cltnm", str(header.get("cltnm")));
        p.addValue("mijcltcd", str(header.get("mijcltcd")));
        p.addValue("mijcltnm", str(header.get("mijcltnm")));
        p.addValue("remark", str(header.get("remark")));
        p.addValue("bigo", str(header.get("bigo")));
        p.addValue("gubun", str(header.get("gubun")));
        p.addValue("bhflag", str(header.get("bhflag")));
        p.addValue("artcd", str(header.get("artcd")));
        p.addValue("divicd", str(header.get("divicd")));
        p.addValue("actcd", str(header.get("actcd")));
        p.addValue("acccd", str(header.get("acccd")));
        p.addValue("taxreclafi", str(header.get("taxreclafi")));
        p.addValue("billkind", str(header.get("billkind")));
        // 귀속년월이 비면 매입일자의 년월로 채운다 (경기 5,782건 중 5,776건이 그 형태다)
        String yyyymm = str(header.get("yyyymm")).replaceAll("-", "");
        p.addValue("yyyymm", yyyymm.isBlank() ? mijdate.substring(0, 6) : yyyymm);
        p.addValue("tax_spdate", str(header.get("tax_spdate")).replaceAll("-", ""));
        // 지급예정일이 비면 매입일자로 채운다 (파워빌더와 동일)
        String schdate = str(header.get("schdate")).replaceAll("-", "");
        p.addValue("schdate", schdate.isBlank() ? mijdate : schdate);
        p.addValue("mijamt", sumMijamt);
        p.addValue("inperid", userId == null ? "" : userId);

        int affected;
        if (isNew) {
            affected = sqlRunner.execute("""
                    INSERT INTO TB_CA640 (custcd, spjangcd, mijdate, mijnum, cltcd, cltnm, mijcltcd, mijcltnm,
                                          remark, bigo, gubun, bhflag, artcd, divicd, actcd, acccd,
                                          taxreclafi, billkind, yyyymm, tax_spdate, schdate, mijamt,
                                          mijgubun, taxcls, indate, inperid)
                    VALUES (:custcd, :spjangcd, :mijdate, :mijnum, :cltcd, :cltnm, :mijcltcd, :mijcltnm,
                            :remark, :bigo, :gubun, :bhflag, :artcd, :divicd, :actcd, :acccd,
                            :taxreclafi, :billkind, :yyyymm, :tax_spdate, :schdate, :mijamt,
                            '0', '01', CONVERT(varchar(8), GETDATE(), 112), :inperid)
                    """, p);
        } else {
            affected = sqlRunner.execute("""
                    UPDATE TB_CA640
                       SET cltcd = :cltcd, cltnm = :cltnm, mijcltcd = :mijcltcd, mijcltnm = :mijcltnm,
                           remark = :remark, bigo = :bigo, gubun = :gubun, bhflag = :bhflag,
                           artcd = :artcd, divicd = :divicd, actcd = :actcd, acccd = :acccd,
                           taxreclafi = :taxreclafi, billkind = :billkind, yyyymm = :yyyymm,
                           tax_spdate = :tax_spdate, schdate = :schdate, mijamt = :mijamt,
                           indate = CONVERT(varchar(8), GETDATE(), 112), inperid = :inperid
                     WHERE custcd = :custcd AND spjangcd = :spjangcd
                       AND mijdate = :mijdate AND mijnum = :mijnum
                    """, p);
        }
        if (affected == 0) throw new IllegalStateException("매입 저장에 실패했습니다.");

        // 상세는 통째로 다시 넣는다 (파워빌더는 행 단위로 갱신하지만 결과는 같다)
        MapSqlParameterSource dp = base(spjangcd, custcd);
        dp.addValue("mijdate", mijdate);
        dp.addValue("mijnum", mijnum);
        sqlRunner.execute("""
                DELETE FROM TB_CA641
                 WHERE custcd = :custcd AND spjangcd = :spjangcd
                   AND mijdate = :mijdate AND mijnum = :mijnum
                """, dp);

        int seq = 1;
        for (Map<String, Object> d : rows) {
            MapSqlParameterSource rp = base(spjangcd, custcd);
            rp.addValue("mijdate", mijdate);
            rp.addValue("mijnum", mijnum);
            rp.addValue("seq", String.format("%03d", seq++));
            rp.addValue("cltcd", str(header.get("cltcd")));
            rp.addValue("accdate", mijdate);
            rp.addValue("remark", str(d.get("remark")));
            rp.addValue("size", str(d.get("size")));
            rp.addValue("unit", str(d.get("unit")));
            rp.addValue("qty", num(d.get("qty")));
            rp.addValue("uamt", num(d.get("uamt")));
            rp.addValue("samt", num(d.get("samt")));
            rp.addValue("tamt", num(d.get("tamt")));
            rp.addValue("mijamt", num(d.get("mijamt")));
            // 경기 상세 5,879건은 actcd·projno·artcd 가 모두 비어 있다. 채워 넣지 않고 들어온 값만 쓴다
            rp.addValue("actcd", str(d.get("actcd")));
            rp.addValue("projno", str(d.get("projno")));
            rp.addValue("projectnm", str(d.get("projectnm")));
            rp.addValue("inperid", userId == null ? "" : userId);

            int ins = sqlRunner.execute("""
                    INSERT INTO TB_CA641 (custcd, spjangcd, cltcd, mijdate, mijnum, seq, accdate,
                                          remark, size, unit, qty, uamt, samt, tamt, mijamt,
                                          actcd, projno, projectnm, indate, inperid)
                    VALUES (:custcd, :spjangcd, :cltcd, :mijdate, :mijnum, :seq, :accdate,
                            :remark, :size, :unit, :qty, :uamt, :samt, :tamt, :mijamt,
                            :actcd, :projno, :projectnm, CONVERT(varchar(8), GETDATE(), 112), :inperid)
                    """, rp);
            if (ins == 0) throw new IllegalStateException("매입 상세 저장에 실패했습니다.");
        }

        // 파워빌더도 저장 뒤 헤더·상세 합계를 한 번 더 맞춘다
        sqlRunner.execute("""
                UPDATE TB_CA640
                   SET mijamt = (SELECT SUM(ISNULL(mijamt, 0)) FROM TB_CA641 WITH(NOLOCK)
                                  WHERE TB_CA641.custcd = TB_CA640.custcd
                                    AND TB_CA641.spjangcd = TB_CA640.spjangcd
                                    AND TB_CA641.mijdate = TB_CA640.mijdate
                                    AND TB_CA641.mijnum = TB_CA640.mijnum)
                 WHERE custcd = :custcd AND spjangcd = :spjangcd
                   AND mijdate = :mijdate AND mijnum = :mijnum
                """, dp);

        return Map.of("mijdate", mijdate, "mijnum", mijnum);
    }

    /**
     * 전표번호 채번 — 매입일자별 MAX+1.
     * 파워빌더도 화면에서 번호를 만들기 때문에 같은 순간에 저장하면 겹칠 수 있다.
     * 그래서 잠금을 걸고 뽑은 뒤, 이미 있는 번호면 다시 뽑는다 (업무일지 채번과 같은 방식).
     */
    private String nextMijnum(String spjangcd, String custcd, String mijdate) {
        MapSqlParameterSource p = base(spjangcd, custcd);
        p.addValue("mijdate", mijdate);

        for (int attempt = 0; attempt < 3; attempt++) {
            Map<String, Object> row = sqlRunner.getRow("""
                    SELECT ISNULL(MAX(TRY_CAST(mijnum AS int)), 0) + 1 AS nextnum
                      FROM TB_CA640 WITH(UPDLOCK, HOLDLOCK)
                     WHERE custcd = :custcd AND spjangcd = :spjangcd AND mijdate = :mijdate
                    """, p);
            if (row == null) throw new IllegalStateException("전표번호를 채번하지 못했습니다.");

            int next = ((Number) row.get("nextnum")).intValue();
            // mijnum 은 varchar(4) 라 5자리가 되면 더 못 넣는다
            if (next > 9999) throw new IllegalStateException("해당 일자의 전표번호를 모두 사용했습니다.");
            String candidate = String.format("%04d", next);
            p.addValue("mijnum", candidate);

            Map<String, Object> dup = sqlRunner.getRow("""
                    SELECT COUNT(*) AS cnt FROM TB_CA640 WITH(NOLOCK)
                     WHERE custcd = :custcd AND spjangcd = :spjangcd
                       AND mijdate = :mijdate AND mijnum = :mijnum
                    """, p);
            if (dup != null && ((Number) dup.get("cnt")).intValue() == 0) return candidate;

            log.warn("[매입관리] 전표번호 충돌 mijdate={}, mijnum={} — 재채번", mijdate, candidate);
        }
        throw new IllegalStateException("전표번호가 계속 충돌합니다. 잠시 후 다시 저장해주세요.");
    }

    /** 삭제 — 지급된 건은 막는다 */
    @Transactional
    public void delete(String spjangcd, String mijdate, String mijnum) {
        String custcd = getCustcd(spjangcd);
        if (custcd == null) throw new IllegalStateException("사업장 정보를 찾을 수 없습니다.");

        String date = mijdate == null ? "" : mijdate.replaceAll("-", "");
        if (paidAmount(spjangcd, custcd, date, mijnum) > 0) {
            throw new IllegalStateException("이미 지급된 내역이 있어 삭제할 수 없습니다.");
        }

        MapSqlParameterSource p = base(spjangcd, custcd);
        p.addValue("mijdate", date);
        p.addValue("mijnum", mijnum);

        sqlRunner.execute("""
                DELETE FROM TB_CA641
                 WHERE custcd = :custcd AND spjangcd = :spjangcd
                   AND mijdate = :mijdate AND mijnum = :mijnum
                """, p);

        int deleted = sqlRunner.execute("""
                DELETE FROM TB_CA640
                 WHERE custcd = :custcd AND spjangcd = :spjangcd
                   AND mijdate = :mijdate AND mijnum = :mijnum
                """, p);
        if (deleted == 0) throw new IllegalStateException("삭제할 매입을 찾을 수 없습니다.");
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
