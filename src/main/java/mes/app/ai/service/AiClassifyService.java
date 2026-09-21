package mes.app.ai.service;

import mes.app.ai.engine.FaultClassifier;
import mes.app.ai.engine.FaultText;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 고장유형 자동분류 (기능정의서 「고장유형 자동분류」) — 고장 상세내용으로 고장내용 코드(TB_E010) 추천
 */
@Service
public class AiClassifyService {

    public static final String MODEL_VERSION = "tfidf-knn-1";
    /** 이보다 짧으면 추천하지 않는다 (정의서 4-5: 5자 미만 생략. 공백 제외 글자수) */
    public static final int MIN_TEXT = 2;

    @Autowired
    SqlRunner sqlRunner;

    @Autowired
    AiIndexService indexService;

    @Autowired
    AiStoreService store;

    public Map<String, Object> suggest(String text, String memo) {
        String query = ((text == null ? "" : text) + " " + (memo == null ? "" : memo)).trim();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("threshold", store.intSetting("classify_threshold"));
        data.put("modelVersion", MODEL_VERSION);
        if (FaultText.compact(query).length() < MIN_TEXT) {
            data.put("candidates", List.of());
            return data;
        }

        AiIndexService.Built<FaultClassifier> built = indexService.classifier(store.dbKey(), store.spjangcd());
        Map<String, String> names = codeNames();
        List<Map<String, Object>> list = new ArrayList<>();
        for (FaultClassifier.Candidate c : built.value().classify(query, store.activeKeywords(), null, 3)) {
            if (!names.containsKey(c.code())) continue;   // 사용 안 하는 코드·사라진 코드
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", c.code());
            m.put("name", names.get(c.code()));
            m.put("confidence", c.confidence());
            m.put("basis", c.basis());
            m.put("keyword", c.matchedKeyword());
            m.put("similarity", Math.round(c.topSimilarity() * 100));
            m.put("neighbors", c.neighborCount());
            list.add(m);
        }
        data.put("candidates", list);
        data.put("indexSize", built.size());
        return data;
    }

    /** 사용중 고장내용 코드 → 이름 */
    public Map<String, String> codeNames() {
        Map<String, String> m = new LinkedHashMap<>();
        List<Map<String, Object>> rows = sqlRunner.getRows("""
                SELECT contcd, contnm FROM TB_E010 WITH(NOLOCK)
                 WHERE spjangcd = :spjangcd AND ISNULL(useyn, '1') = '1'
                 ORDER BY seq, contcd
                """, new MapSqlParameterSource("spjangcd", store.spjangcd()));
        if (rows != null) {
            for (Map<String, Object> r : rows) m.put(str(r.get("contcd")), str(r.get("contnm")));
        }
        return m;
    }

    /** 관리 화면 왼쪽 — 코드별 접수 건수·키워드 수 */
    public List<Map<String, Object>> codes() {
        List<Map<String, Object>> rows = sqlRunner.getRows("""
                SELECT c.contcd, c.contnm, ISNULL(c.useyn, '1') AS useyn, c.seq,
                       (SELECT COUNT(*) FROM TB_E401 a WITH(NOLOCK)
                         WHERE a.spjangcd = c.spjangcd AND a.contcd = c.contcd) AS rece_cnt
                  FROM TB_E010 c WITH(NOLOCK)
                 WHERE c.spjangcd = :spjangcd
                 ORDER BY c.seq, c.contcd
                """, new MapSqlParameterSource("spjangcd", store.spjangcd()));
        if (rows == null) throw new IllegalStateException("고장내용 코드를 읽지 못했습니다.");

        Map<String, Integer> kwCount = new HashMap<>();
        for (Map<String, Object> k : store.keywords(null)) kwCount.merge(str(k.get("contcd")), 1, Integer::sum);
        for (Map<String, Object> r : rows) r.put("kw_cnt", kwCount.getOrDefault(str(r.get("contcd")), 0));
        return rows;
    }

    /**
     * 키워드 후보 — 이 코드로 접수된 상세내용에 자주 나오고, 다른 코드에서는 잘 안 나오는 단어.
     * 비중(precision) = 이 코드에서 나온 횟수 / 전체에서 나온 횟수
     */
    public List<Map<String, Object>> keywordCandidates(String contcd) {
        List<Map<String, Object>> rows = sqlRunner.getRows("""
                SELECT TOP 30000 ISNULL(a.contcd, '') AS contcd, CAST(a.contents AS varchar(1000)) AS contents
                  FROM TB_E401 a WITH(NOLOCK)
                 WHERE a.spjangcd = :spjangcd AND ISNULL(a.contcd, '') <> ''
                   AND LEN(LTRIM(CAST(a.contents AS varchar(1000)))) >= 2
                 ORDER BY a.recedate DESC, a.recenum DESC
                """, new MapSqlParameterSource("spjangcd", store.spjangcd()));
        if (rows == null) throw new IllegalStateException("고장접수 데이터를 읽지 못했습니다.");

        Map<String, int[]> counts = new HashMap<>();   // [이 코드, 전체]
        int codeDocs = 0;
        for (Map<String, Object> r : rows) {
            boolean mine = contcd.equals(str(r.get("contcd")));
            if (mine) codeDocs++;
            Set<String> words = new HashSet<>();
            for (String t : FaultText.tokens(str(r.get("contents")), true)) {
                if (t.startsWith("w:")) words.add(t.substring(2));
            }
            for (String w : words) {
                int[] c = counts.computeIfAbsent(w, k -> new int[2]);
                if (mine) c[0]++;
                c[1]++;
            }
        }

        Set<String> existing = new HashSet<>();
        for (Map<String, Object> k : store.keywords(contcd)) existing.add(FaultText.compact(str(k.get("keyword"))));

        int minCount = Math.max(3, codeDocs / 200);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            int in = e.getValue()[0];
            int all = e.getValue()[1];
            if (in < minCount) continue;
            double precision = (double) in / all;
            if (precision < 0.6) continue;
            if (existing.contains(FaultText.compact(e.getKey()))) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("keyword", e.getKey());
            m.put("code_cnt", in);
            m.put("all_cnt", all);
            m.put("precision", Math.round(precision * 100));
            list.add(m);
        }
        list.sort((a, b) -> {
            int c = Integer.compare((int) b.get("code_cnt"), (int) a.get("code_cnt"));
            return c != 0 ? c : Long.compare((long) b.get("precision"), (long) a.get("precision"));
        });
        return list.size() > 40 ? new ArrayList<>(list.subList(0, 40)) : list;
    }

    /** 기간 내 저장 시점 추천 채택률 + 자주 정정되는 코드 쌍 */
    public Map<String, Object> stats(String fromDate, String toDate) {
        MapSqlParameterSource p = period(fromDate, toDate);
        List<Map<String, Object>> sum = store.queryLog("""
                SELECT COUNT(*) AS saves,
                       COUNT(*) FILTER (WHERE suggested IS NOT NULL) AS shown,
                       COUNT(*) FILTER (WHERE suggested IS NOT NULL AND adopted) AS adopted,
                       COUNT(*) FILTER (WHERE source = 'WEB') AS web,
                       COUNT(*) FILTER (WHERE source = 'MOBILE') AS mobile
                  FROM ai_event_log
                 WHERE db_key = :db_key AND feature = 'CLASSIFY' AND event = 'SAVE'
                   AND _created >= CAST(:fromDate AS date) AND _created < CAST(:toDate AS date) + 1
                """, p);
        List<Map<String, Object>> pairs = store.queryLog("""
                SELECT suggested, final_value, COUNT(*) AS cnt
                  FROM ai_event_log
                 WHERE db_key = :db_key AND feature = 'CLASSIFY' AND event = 'SAVE'
                   AND suggested IS NOT NULL AND adopted = false
                   AND _created >= CAST(:fromDate AS date) AND _created < CAST(:toDate AS date) + 1
                 GROUP BY suggested, final_value
                 ORDER BY cnt DESC
                 LIMIT 10
                """, p);
        Map<String, String> names = codeNames();
        for (Map<String, Object> r : pairs) {
            r.put("suggested_nm", names.getOrDefault(str(r.get("suggested")), str(r.get("suggested"))));
            r.put("final_nm", names.getOrDefault(str(r.get("final_value")), str(r.get("final_value"))));
        }
        List<Map<String, Object>> daily = store.queryLog("""
                SELECT to_char(_created, 'YYYY-MM-DD') AS ymd,
                       COUNT(*) FILTER (WHERE suggested IS NOT NULL) AS shown,
                       COUNT(*) FILTER (WHERE suggested IS NOT NULL AND adopted) AS adopted
                  FROM ai_event_log
                 WHERE db_key = :db_key AND feature = 'CLASSIFY' AND event = 'SAVE'
                   AND _created >= CAST(:fromDate AS date) AND _created < CAST(:toDate AS date) + 1
                 GROUP BY 1 ORDER BY 1
                """, p);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", sum.isEmpty() ? Map.of() : sum.get(0));
        data.put("corrections", pairs);
        data.put("daily", daily);
        data.put("logReady", store.tablesReady());
        return data;
    }

    static MapSqlParameterSource period(String fromDate, String toDate) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("fromDate", ymdDash(fromDate));
        p.addValue("toDate", ymdDash(toDate));
        return p;
    }

    static String ymdDash(String ymd) {
        String s = ymd == null ? "" : ymd.replace("-", "");
        if (!s.matches("\\d{8}")) throw new IllegalArgumentException("조회기간을 확인해주세요.");
        return s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8);
    }

    static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
