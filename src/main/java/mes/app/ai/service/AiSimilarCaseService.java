package mes.app.ai.service;

import mes.app.ai.engine.TfIdfIndex;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 유사 고장사례 추천 (기능정의서 「유사 고장사례 추천」)
 *
 * ① 동일호기 처리이력 — 같은 현장·호기의 최근 처리 건 (단순 조회)
 * ② 유사사례 — 고장내용·상세내용이 비슷한 과거 처리 건. 같은 고장내용 코드면 가산(정의서 4-2 하이브리드)
 */
@Service
public class AiSimilarCaseService {

    public static final String MODEL_VERSION = "tfidf-case-1";
    /** 같은 고장내용 코드일 때 더하는 점수 (0~1 척도) */
    static final double SAME_CODE_BONUS = 0.15;

    @Autowired
    SqlRunner sqlRunner;

    @Autowired
    AiIndexService indexService;

    @Autowired
    AiStoreService store;

    /** 처리이력 상세 컬럼 — 동일호기 이력·유사사례·관리 목록이 같이 쓴다 */
    private static final String DETAIL_SELECT = """
            SELECT e.compdate, e.compnum, e.comptime, e.recedate, e.recenum,
                   e.actcd, ISNULL(e.actnm, '') AS actnm, ISNULL(e.equpcd, '') AS equpcd, ISNULL(e.equpnm, '') AS equpnm,
                   ISNULL(a.contcd, '') AS contcd, ISNULL(ct.contnm, '') AS contnm,
                   CAST(a.contents AS varchar(1000)) AS contents,
                   ISNULL(gr.greginm, '') AS greginm, ISNULL(eg.reginm, '') AS reginm,
                   ISNULL(em.remonm, '') AS remonm, CAST(e.remoremark AS varchar(1000)) AS remoremark,
                   ISNULL(es.resunm, '') AS resunm, CAST(e.resuremark AS varchar(1000)) AS resuremark,
                   ISNULL(er.resultnm, '') AS resultnm, ISNULL(p.pernm, '') AS pernm
              FROM TB_E411 e WITH(NOLOCK)
              LEFT JOIN TB_E401 a  WITH(NOLOCK) ON a.spjangcd = e.spjangcd AND a.recedate = e.recedate AND a.recenum = e.recenum AND a.actcd = e.actcd
              LEFT JOIN TB_E010 ct WITH(NOLOCK) ON ct.spjangcd = e.spjangcd AND ct.contcd  = a.contcd
              LEFT JOIN TB_E013 gr WITH(NOLOCK) ON gr.spjangcd = e.spjangcd AND gr.gregicd = e.gregicd
              LEFT JOIN TB_E014 eg WITH(NOLOCK) ON eg.spjangcd = e.spjangcd AND eg.gregicd = e.gregicd AND eg.regicd = e.regicd
              LEFT JOIN TB_E011 em WITH(NOLOCK) ON em.spjangcd = e.spjangcd AND em.remocd  = e.remocd
              LEFT JOIN TB_E012 es WITH(NOLOCK) ON es.spjangcd = e.spjangcd AND es.resucd  = e.resucd
              LEFT JOIN TB_E015 er WITH(NOLOCK) ON er.spjangcd = e.spjangcd AND er.resultcd = e.resultcd
              LEFT JOIN TB_JA001 p WITH(NOLOCK) ON p.spjangcd  = e.spjangcd AND p.perid    = 'p' + e.perid
            """;

    /** ① 동일호기 처리이력 */
    public List<Map<String, Object>> unitHistory(String actcd, String equpcd, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("spjangcd", store.spjangcd());
        p.addValue("actcd", actcd);
        p.addValue("equpcd", equpcd == null ? "" : equpcd);
        List<Map<String, Object>> rows = sqlRunner.getRows(
                "SELECT TOP " + Math.max(1, Math.min(limit, 50)) + " x.* FROM (" + DETAIL_SELECT + """
                         WHERE e.spjangcd = :spjangcd AND e.actcd = :actcd AND ISNULL(e.equpcd, '') = :equpcd
                        ) x ORDER BY x.compdate DESC, x.compnum DESC
                        """, p);
        if (rows == null) throw new IllegalStateException("처리이력을 읽지 못했습니다.");
        return rows;
    }

    /**
     * ② 유사사례. 접수키(recedate, recenum)가 오면 그 접수의 고장내용·상세내용으로 찾는다.
     *
     * @param excludeSameUnit 같은 호기 건은 동일호기 이력 탭에 이미 나오므로 뺀다
     */
    public Map<String, Object> similarCases(String recedate, String recenum, String contcd, String text,
                                            String actcd, String equpcd, int topN, boolean excludeSameUnit,
                                            boolean applyThreshold) {
        if (recedate != null && !recedate.isBlank() && recenum != null && !recenum.isBlank()) {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("spjangcd", store.spjangcd());
            p.addValue("recedate", recedate);
            p.addValue("recenum", recenum);
            Map<String, Object> rece = sqlRunner.getRow("""
                    SELECT a.contcd, ISNULL(ct.contnm, '') AS contnm, CAST(a.contents AS varchar(1000)) AS contents,
                           a.actcd, ISNULL(a.equpcd, '') AS equpcd
                      FROM TB_E401 a WITH(NOLOCK)
                      LEFT JOIN TB_E010 ct WITH(NOLOCK) ON ct.spjangcd = a.spjangcd AND ct.contcd = a.contcd
                     WHERE a.spjangcd = :spjangcd AND a.recedate = :recedate AND a.recenum = :recenum
                    """, p);
            if (rece != null) {
                contcd = str(rece.get("contcd"));
                text = str(rece.get("contnm")) + " " + str(rece.get("contents"));
                actcd = str(rece.get("actcd"));
                equpcd = str(rece.get("equpcd"));
            }
        }

        int threshold = applyThreshold ? store.intSetting("case_threshold") : 0;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("threshold", threshold);
        data.put("modelVersion", MODEL_VERSION);
        data.put("query", text);
        if (text == null || text.replaceAll("\\s", "").length() < 2) {
            data.put("cases", List.of());
            return data;
        }

        String qCode = contcd == null ? "" : contcd;
        String qAct = actcd == null ? "" : actcd;
        String qEqup = equpcd == null ? "" : equpcd;
        Set<String> excluded = store.excludedCases();

        AiIndexService.Built<TfIdfIndex<AiIndexService.CaseMeta>> built = indexService.caseIndex(store.dbKey(), store.spjangcd());
        List<TfIdfIndex.Hit<AiIndexService.CaseMeta>> hits = built.value().search(text, 60, 0.1,
                m -> !excluded.contains(m.key())
                        && !(excludeSameUnit && m.actcd().equals(qAct) && m.equpcd().equals(qEqup)));

        // 같은 고장내용 코드 가산 후 재정렬
        record Scored(AiIndexService.CaseMeta meta, double score, int doc) {
        }
        List<Scored> scored = new ArrayList<>();
        for (TfIdfIndex.Hit<AiIndexService.CaseMeta> h : hits) {
            double s = h.score() + (!qCode.isEmpty() && qCode.equals(h.meta().contcd()) ? SAME_CODE_BONUS : 0);
            scored.add(new Scored(h.meta(), Math.min(1.0, s), h.doc()));
        }
        // 점수가 같으면 최근 처리 건 먼저 (색인은 완료일자 최신순)
        scored.sort((a, b) -> a.score() != b.score() ? Double.compare(b.score(), a.score()) : Integer.compare(a.doc(), b.doc()));

        // 상세를 읽고, 처리내용이 똑같은 사례는 한 번만 보여준다 (같은 조치가 3건 나오면 참고가치가 없다)
        List<Scored> pool = scored.subList(0, Math.min(scored.size(), Math.max(topN * 4, 12)));
        Map<String, Map<String, Object>> details = details(pool.stream().map(s -> s.meta().key()).toList());
        boolean mask = "Y".equals(store.settings().get("mask_site"));

        List<Map<String, Object>> cases = new ArrayList<>();
        Set<String> seenAction = new HashSet<>();
        for (Scored s : pool) {
            int similarity = (int) Math.round(s.score() * 100);
            if (similarity < threshold) break;
            Map<String, Object> d = details.get(s.meta().key());
            if (d == null) continue;
            String action = (str(d.get("resunm")) + "|" + str(d.get("resuremark"))).replaceAll("\\s", "");
            if (!seenAction.add(action)) continue;
            Map<String, Object> m = new LinkedHashMap<>(d);
            m.put("similarity", similarity);
            m.put("same_code", !qCode.isEmpty() && qCode.equals(s.meta().contcd()));
            if (mask) m.put("actnm", maskSite(str(d.get("actnm"))));
            cases.add(m);
            if (cases.size() >= topN) break;
        }
        data.put("cases", cases);
        data.put("indexSize", built.size());
        return data;
    }

    private Map<String, Map<String, Object>> details(List<String> keys) {
        Map<String, Map<String, Object>> map = new HashMap<>();
        if (keys.isEmpty()) return map;
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("spjangcd", store.spjangcd());
        p.addValue("keys", keys);
        // compdate IN 을 먼저 걸어 PK 범위로 좁힌다
        p.addValue("dates", keys.stream().map(k -> k.substring(0, k.indexOf('-'))).distinct().toList());
        List<Map<String, Object>> rows = sqlRunner.getRows(DETAIL_SELECT + """
                 WHERE e.spjangcd = :spjangcd AND e.compdate IN (:dates) AND e.compdate + '-' + e.compnum IN (:keys)
                """, p);
        if (rows != null) {
            for (Map<String, Object> r : rows) map.put(str(r.get("compdate")) + "-" + str(r.get("compnum")), r);
        }
        return map;
    }

    /** 관리 화면 — 사례 목록 (제외 여부 포함) */
    public List<Map<String, Object>> caseList(String fromDate, String toDate, String actnm, String keyword, boolean excludedOnly) {
        Set<String> excluded = store.excludedCases();
        Map<String, String> reasons = new HashMap<>();
        for (Map<String, Object> r : store.excludedCaseRows()) {
            reasons.put(r.get("compdate") + "-" + r.get("compnum"), str(r.get("reason")));
        }

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("spjangcd", store.spjangcd());
        p.addValue("fromDate", fromDate);
        p.addValue("toDate", toDate);
        p.addValue("actnm", actnm == null ? "" : actnm.trim());
        p.addValue("keyword", keyword == null ? "" : keyword.trim());
        String sql = "SELECT TOP 3000 x.* FROM (" + DETAIL_SELECT + """
                 WHERE e.spjangcd = :spjangcd AND e.compdate BETWEEN :fromDate AND :toDate
                   AND (:actnm = '' OR ISNULL(e.actnm, '') LIKE '%' + :actnm + '%')
                ) x
                 WHERE (:keyword = '' OR x.contents LIKE '%' + :keyword + '%' OR x.resuremark LIKE '%' + :keyword + '%'
                        OR x.remoremark LIKE '%' + :keyword + '%' OR x.contnm LIKE '%' + :keyword + '%')
                 ORDER BY x.compdate DESC, x.compnum DESC
                """;
        List<Map<String, Object>> rows = sqlRunner.getRows(sql, p);
        if (rows == null) throw new IllegalStateException("처리이력을 읽지 못했습니다.");

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String key = str(r.get("compdate")) + "-" + str(r.get("compnum"));
            boolean ex = excluded.contains(key);
            if (excludedOnly && !ex) continue;
            r.put("excluded", ex);
            r.put("exclude_reason", reasons.getOrDefault(key, ""));
            // 처리내용이 없으면 색인 대상이 아니다 (색인 조건과 동일)
            r.put("indexed", !str(r.get("resunm")).isEmpty() || !str(r.get("resuremark")).isEmpty());
            out.add(r);
        }
        return out;
    }

    /** 관리 화면 — 참고카드 열람·클릭 통계 */
    public Map<String, Object> stats(String fromDate, String toDate) {
        MapSqlParameterSource p = AiClassifyService.period(fromDate, toDate);
        List<Map<String, Object>> sum = store.queryLog("""
                SELECT COUNT(*) FILTER (WHERE event = 'OPEN') AS opens,
                       COUNT(DISTINCT ref_key) FILTER (WHERE event = 'OPEN') AS open_receipts,
                       COUNT(*) FILTER (WHERE event = 'CLICK') AS clicks,
                       COUNT(DISTINCT ref_key) FILTER (WHERE event = 'CLICK') AS click_receipts,
                       ROUND(AVG(result_cnt) FILTER (WHERE event = 'OPEN'), 1) AS avg_cases,
                       COUNT(*) FILTER (WHERE event = 'OPEN' AND COALESCE(result_cnt, 0) = 0) AS no_case
                  FROM ai_event_log
                 WHERE db_key = :db_key AND feature = 'CASE'
                   AND _created >= CAST(:fromDate AS date) AND _created < CAST(:toDate AS date) + 1
                """, p);
        List<Map<String, Object>> recent = store.queryLog("""
                SELECT _created, user_name, event, ref_key, query_text, final_value, rank_no, top_score, result_cnt
                  FROM ai_event_log
                 WHERE db_key = :db_key AND feature = 'CASE'
                   AND _created >= CAST(:fromDate AS date) AND _created < CAST(:toDate AS date) + 1
                 ORDER BY _created DESC
                 LIMIT 300
                """, p);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", sum.isEmpty() ? Map.of() : sum.get(0));
        data.put("recent", recent);
        data.put("logReady", store.tablesReady());
        return data;
    }

    static String maskSite(String name) {
        if (name == null || name.length() <= 2) return "○○";
        String tail = name.replaceAll("^.*?(아파트|APT|빌딩|오피스텔|병원|학교|센터|타워|플라자|상가|호텔|역)$", "$1");
        if (!tail.equals(name)) return name.charAt(0) + "○○" + tail;
        return name.charAt(0) + "○○";
    }

    static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
