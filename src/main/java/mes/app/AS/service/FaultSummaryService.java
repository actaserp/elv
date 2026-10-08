package mes.app.AS.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 고장통계 — 종합현황. PB `w_fault_summary` 그리드 이식.
 *
 * PB 는 구분별로 UNION ALL 6개를 붙여 한 번에 뽑았는데, 월 컬럼이 mon1~mon4 로 고정돼 있어
 * 조회기간을 10개월로 잡아도 최근 3개월 + 나머지 합계만 나왔다.
 * 여기서는 월별 집계를 한 번만 하고 Java 에서 가로로 펼친다. 기간 안의 모든 월이 나온다.
 *
 * PB 원본과 달라진 점 (전부 의도한 것)
 *  · tb_e601.qty 를 SUM 한다. PB 는 SUM 이 없어 현장을 지정하지 않으면 관리대수가 여러 줄로 나왔다.
 *  · 사람갇힘의 tb_e401 조인을 뺐다. 조건이 전부 tb_e411 에 있어 조인이 하는 일이 없고,
 *    같은 (recedate, recenum) 에 현장이 다른 접수건이 있으면 건수가 부풀려진다.
 *  · resutime / resulttime 을 TRY_CAST 로 읽는다. PB 의 CONVERT(int, ...) 는 숫자가 아닌 값을
 *    만나면 쿼리 전체가 죽는다 (남양 기준 resutime 10건 · resulttime 16건 존재).
 *  · '돌상돌하' 행은 뺐다. PB 는 trouble='2' 로 셌는데 그 값이 전 사업체에 한 건도 없어
 *    늘 0 만 찍혔다. 어디에 기록되는지 확인되면 되살린다. (2026-10-08 사용자 확인)
 */
@Service
public class FaultSummaryService {

    /** 한 번에 펼칠 수 있는 최대 월 수 */
    private static final int MAX_MONTHS = 24;

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    @Autowired
    SqlRunner sqlRunner;

    /**
     * @param stmon  조회 시작월 yyyyMM
     * @param endmon 조회 종료월 yyyyMM
     * @param cltnm  거래처명 (부분일치, 선택)
     * @param actnm  현장명 (부분일치, 선택)
     */
    public Map<String, Object> getSummary(String spjangcd, String stmon, String endmon,
                                          String cltnm, String actnm) {

        List<String> months = monthsBetween(stmon, endmon);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("months", months);

        if (months.isEmpty()) {
            out.put("rows", new ArrayList<>());
            return out;
        }

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("spjangcd", spjangcd);
        p.addValue("stmon", months.get(months.size() - 1));   // 오래된 월
        p.addValue("endmon", months.get(0));                  // 최근 월

        String where = filter(p, cltnm, actnm, "a");

        // ── 월별 집계 (tb_e411) ────────────────────────────────
        String sql = """
                SELECT z.ym,
                       COUNT(*) AS cnt,
                       SUM(CASE WHEN z.su > 0 THEN 1 ELSE 0 END) AS trapped,
                       SUM(z.su)                                 AS trapped_pr,
                       SUM(ISNULL(TRY_CAST(z.resutime   AS INT), 0)) AS resu_sum,
                       SUM(CASE WHEN TRY_CAST(z.resutime   AS INT) IS NOT NULL THEN 1 ELSE 0 END) AS resu_cnt,
                       SUM(ISNULL(TRY_CAST(z.resulttime AS INT), 0)) AS resl_sum,
                       SUM(CASE WHEN TRY_CAST(z.resulttime AS INT) IS NOT NULL THEN 1 ELSE 0 END) AS resl_cnt
                  FROM (
                        SELECT LEFT(a.compdate, 6) AS ym,
                               a.resutime, a.resulttime,
                               -- 갇힌 인원은 처리행에 있으면 그 값을, 없으면 접수행의 값을 쓴다.
                               -- PB 고장접수등록이 '시간/사람수' 를 TB_E401 에 적고 처리행으로 넘기지 않아
                               -- 처리행만 보면 최근 건이 전혀 안 잡힌다. 과거 PB 데이터는 처리행에 들어 있다.
                               ISNULL(NULLIF(ISNULL(a.troublesu, 0), 0), ISNULL(rq.su, 0)) AS su
                          FROM tb_e411 a WITH(NOLOCK)
                          OUTER APPLY (
                                SELECT TOP 1 ISNULL(r.troublesu, 0) AS su
                                  FROM TB_E401 r WITH(NOLOCK)
                                 WHERE r.spjangcd = a.spjangcd
                                   AND r.recedate = a.recedate
                                   AND r.recenum  = a.recenum
                                   AND r.actcd    = a.actcd
                          ) rq
                         WHERE a.spjangcd = :spjangcd
                           AND a.result = '1'
                           AND LEFT(a.compdate, 6) BETWEEN :stmon AND :endmon
                """ + where + """
                  ) z
                 GROUP BY z.ym
                """;

        Map<String, Map<String, Object>> byMonth = new HashMap<>();
        for (Map<String, Object> r : nvl(this.sqlRunner.getRows(sql, p))) {
            byMonth.put(str(r.get("ym")), r);
        }

        // ── 관리대수 (tb_e601) — 월과 무관하게 현재 보유 대수 ──
        MapSqlParameterSource pq = new MapSqlParameterSource();
        pq.addValue("spjangcd", spjangcd);
        String whereQty = filter(pq, cltnm, actnm, "e");
        Map<String, Object> qtyRow = this.sqlRunner.getRow("""
                SELECT SUM(ISNULL(e.qty, 0)) AS qty
                  FROM tb_e601 e WITH(NOLOCK)
                 WHERE e.spjangcd = :spjangcd
                """ + whereQty, pq);
        BigDecimal qty = (qtyRow == null) ? BigDecimal.ZERO : dec(qtyRow.get("qty"));

        // ── 가로로 펼치기 ──────────────────────────────────────
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> rFault  = newRow("고장건수");
        Map<String, Object> rQty    = newRow("관리대수");
        Map<String, Object> rRate   = newRow("고장율");
        Map<String, Object> rTrap   = newRow("사람갇힘");
        Map<String, Object> rTrapPr = newRow("갇힌 인원");
        Map<String, Object> rResu   = newRow("평균대응시간");
        Map<String, Object> rResl   = newRow("평균처리시간");

        for (String ym : months) {
            Map<String, Object> m = byMonth.get(ym);
            BigDecimal cnt     = (m == null) ? BigDecimal.ZERO : dec(m.get("cnt"));
            BigDecimal trapped = (m == null) ? BigDecimal.ZERO : dec(m.get("trapped"));

            rFault.put(ym, cnt);
            rQty.put(ym, qty);
            // 고장율(%) = 고장건수 / 관리대수 × 100
            rRate.put(ym, qty.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                    : cnt.multiply(BigDecimal.valueOf(100)).divide(qty, 0, RoundingMode.HALF_UP));
            rTrap.put(ym, trapped);
            rTrapPr.put(ym, (m == null) ? BigDecimal.ZERO : dec(m.get("trapped_pr")));
            rResu.put(ym, avg(m, "resu_sum", "resu_cnt"));
            rResl.put(ym, avg(m, "resl_sum", "resl_cnt"));
        }

        rows.add(rFault);
        rows.add(rQty);
        rows.add(rRate);
        rows.add(rTrap);
        rows.add(rTrapPr);
        rows.add(rResu);
        rows.add(rResl);

        // 평균 칸 — PB 와 같이 화면에 보이는 월들의 단순 평균
        for (Map<String, Object> r : rows) {
            BigDecimal sum = BigDecimal.ZERO;
            for (String ym : months) sum = sum.add(dec(r.get(ym)));
            r.put("avg", sum.divide(BigDecimal.valueOf(months.size()), 0, RoundingMode.HALF_UP));
        }

        out.put("rows", rows);
        return out;
    }

    // --------------------------------------------------------------- 내부

    private Map<String, Object> newRow(String gubun) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("gubun", gubun);
        return r;
    }

    /**
     * 합 / 건수. 건수가 0 이면 0.
     * PB 는 int 끼리 나눠 소수점을 버린다(263/7 → 37). 숫자를 맞추려고 여기서도 버린다.
     */
    private BigDecimal avg(Map<String, Object> m, String sumKey, String cntKey) {
        if (m == null) return BigDecimal.ZERO;
        BigDecimal cnt = dec(m.get(cntKey));
        if (cnt.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return dec(m.get(sumKey)).divide(cnt, 0, RoundingMode.DOWN);
    }

    /** 거래처명·현장명 부분일치 조건. alias 는 tb_e411(a) 또는 tb_e601(e) */
    private String filter(MapSqlParameterSource p, String cltnm, String actnm, String alias) {
        StringBuilder sb = new StringBuilder();
        if (cltnm != null && !cltnm.isBlank()) {
            sb.append("   AND EXISTS (SELECT 1 FROM TB_XCLIENT c WITH(NOLOCK)")
              .append("                WHERE c.cltcd = ").append(alias).append(".cltcd")
              .append("                  AND c.cltnm LIKE '%' + :cltnm + '%')\n");
            p.addValue("cltnm", cltnm.trim());
        }
        if (actnm != null && !actnm.isBlank()) {
            if ("e".equals(alias)) {
                sb.append("   AND e.actnm LIKE '%' + :actnm + '%'\n");
            } else {
                // tb_e411.actnm 은 접수 당시 값이라 비어 있는 행이 있다. 현장 원장도 같이 본다
                sb.append("   AND ( a.actnm LIKE '%' + :actnm + '%'")
                  .append("      OR EXISTS (SELECT 1 FROM tb_e601 s WITH(NOLOCK)")
                  .append("                  WHERE s.spjangcd = a.spjangcd AND s.actcd = a.actcd")
                  .append("                    AND s.actnm LIKE '%' + :actnm + '%') )\n");
            }
            p.addValue("actnm", actnm.trim());
        }
        return sb.toString();
    }

    /** 최근월이 앞에 오도록 내림차순. 최대 MAX_MONTHS 개 */
    private List<String> monthsBetween(String stmon, String endmon) {
        List<String> out = new ArrayList<>();
        YearMonth from = parse(stmon), to = parse(endmon);
        if (from == null || to == null) return out;
        if (from.isAfter(to)) { YearMonth t = from; from = to; to = t; }

        YearMonth cur = to;
        while (!cur.isBefore(from) && out.size() < MAX_MONTHS) {
            out.add(cur.format(YM));
            cur = cur.minusMonths(1);
        }
        return out;
    }

    private YearMonth parse(String ym) {
        String s = (ym == null) ? "" : ym.replace("-", "").trim();
        if (s.length() < 6) return null;
        try {
            return YearMonth.of(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(4, 6)));
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal dec(Object v) {
        if (v == null) return BigDecimal.ZERO;
        try { return new BigDecimal(String.valueOf(v)); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static List<Map<String, Object>> nvl(List<Map<String, Object>> rows) {
        return rows == null ? new ArrayList<>() : rows;
    }
}
