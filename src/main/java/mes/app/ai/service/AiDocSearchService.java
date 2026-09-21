package mes.app.ai.service;

import mes.app.ai.engine.FaultText;
import mes.app.ai.engine.TfIdfIndex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 기술자료 지능형 검색 (기능정의서 「기술자료 지능형 검색」 1단계 — 제목·내용 + 첨부파일명)
 *
 * 첨부파일 본문(PDF 텍스트, OCR)은 정의서 2·3단계라 아직 색인하지 않는다.
 * 자료실 테이블에 공개범위 컬럼이 없어 권한 필터는 기존 자료실 화면과 같다(로그인 사용자 전체).
 */
@Service
public class AiDocSearchService {

    public static final String MODEL_VERSION = "tfidf-doc-1";

    @Autowired
    AiIndexService indexService;

    @Autowired
    AiStoreService store;

    public Map<String, Object> search(String query, int topN, boolean applyThreshold, String source, Authentication auth) {
        String q = query == null ? "" : query.trim();
        int threshold = applyThreshold ? store.intSetting("doc_threshold") : 0;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("threshold", threshold);
        data.put("modelVersion", MODEL_VERSION);

        AiIndexService.Built<TfIdfIndex<AiIndexService.DocMeta>> built = indexService.docIndex(store.dbKey());
        data.put("indexSize", built.size());

        List<Map<String, Object>> results = new ArrayList<>();
        if (FaultText.compact(q).length() >= 2) {
            TfIdfIndex<AiIndexService.DocMeta> idx = built.value();
            Set<String> tokens = idx.knownTokens(q);

            // 관련도 = 질문 단어가 자료에 들어 있는 정도(75%) + 문장 유사도(25%, 0.3 이상이면 만점)
            // 자료 본문은 길어서 코사인만 쓰면 단어가 다 들어 있어도 10% 안팎으로 나온다 (IX 자료실 실측)
            record Ranked(TfIdfIndex.Hit<AiIndexService.DocMeta> hit, int relevance) {
            }
            List<Ranked> ranked = new ArrayList<>();
            for (TfIdfIndex.Hit<AiIndexService.DocMeta> h : idx.search(q, 200, 0.001, null)) {
                double cov = idx.coverage(q, h.doc());
                int rel = (int) Math.round(100 * (0.75 * cov + 0.25 * Math.min(1.0, h.score() / 0.3)));
                ranked.add(new Ranked(h, rel));
            }
            ranked.sort((a, b) -> a.relevance() != b.relevance()
                    ? Integer.compare(b.relevance(), a.relevance())
                    : Double.compare(b.hit().score(), a.hit().score()));

            for (Ranked r : ranked) {
                if (results.size() >= Math.max(1, Math.min(topN, 50))) break;
                int relevance = r.relevance();
                if (relevance < threshold) break;
                AiIndexService.DocMeta d = r.hit().meta();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", d.bbsseq());
                m.put("title", d.title());
                m.put("write_date_time", d.bbsdate());
                m.put("user", d.user());
                m.put("relevance", relevance);
                Match match = bestMatch(d, tokens);
                m.put("matched_in", match.where());
                m.put("snippet", match.snippet());
                m.put("terms", match.terms());
                results.add(m);
            }
        }
        data.put("results", results);

        if (auth != null && !q.isEmpty()) {
            Map<String, Object> ev = new HashMap<>();
            ev.put("source", source);
            ev.put("feature", "DOC");
            ev.put("event", "SEARCH");
            ev.put("query_text", q);
            ev.put("result_cnt", results.size());
            ev.put("top_score", results.isEmpty() ? 0 : results.get(0).get("relevance"));
            store.logEvent(ev, auth);
        }
        return data;
    }

    record Match(String where, String snippet, List<String> terms) {
    }

    /**
     * 질의 단어가 가장 많이 겹치는 곳을 찾아 발췌한다.
     * 제목 → 본문 문장 → 첨부파일명 순서로 본다. terms 는 화면 하이라이트용 원문 단어.
     */
    static Match bestMatch(AiIndexService.DocMeta d, Set<String> tokens) {
        List<String> words = new ArrayList<>();
        for (String t : tokens) {
            if (t.startsWith("w:") || t.startsWith("u:")) words.add(t.substring(2));
        }
        List<String> bigrams = new ArrayList<>();
        for (String t : tokens) {
            if (t.startsWith("b:")) bigrams.add(t.substring(2));
        }

        if (score(d.title(), words, bigrams) > 0) {
            String sentence = bestSentence(d.content(), words, bigrams);
            return new Match("title", sentence.isEmpty() ? cut(d.content(), 120) : sentence, hitTerms(d.title() + " " + sentence, words, bigrams));
        }
        String sentence = bestSentence(d.content(), words, bigrams);
        if (!sentence.isEmpty()) return new Match("content", sentence, hitTerms(sentence, words, bigrams));
        if (score(d.files(), words, bigrams) > 0) return new Match("attachment", d.files(), hitTerms(d.files(), words, bigrams));
        return new Match("content", cut(d.content(), 120), List.of());
    }

    private static String bestSentence(String content, List<String> words, List<String> bigrams) {
        if (content == null || content.isEmpty()) return "";
        String best = "";
        int bestScore = 0;
        for (String s : content.split("(?<=[.!?。])\\s+|\\n")) {
            int sc = score(s, words, bigrams);
            if (sc > bestScore) {
                bestScore = sc;
                best = s.trim();
            }
        }
        return cut(best, 160);
    }

    private static int score(String s, List<String> words, List<String> bigrams) {
        if (s == null || s.isEmpty()) return 0;
        String low = s.toLowerCase(Locale.ROOT);
        int sc = 0;
        for (String w : words) if (low.contains(w)) sc += 3;
        for (String b : bigrams) if (low.contains(b)) sc += 1;
        return sc;
    }

    private static List<String> hitTerms(String s, List<String> words, List<String> bigrams) {
        String low = s == null ? "" : s.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String w : words) if (low.contains(w)) out.add(w);
        if (out.isEmpty()) {
            for (String b : bigrams) if (low.contains(b)) out.add(b);
        }
        return out;
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    /** 관리 화면 — 검색 로그 */
    public Map<String, Object> logs(String fromDate, String toDate, boolean noResultOnly) {
        MapSqlParameterSource p = AiClassifyService.period(fromDate, toDate);
        p.addValue("noResult", noResultOnly);
        List<Map<String, Object>> sum = store.queryLog("""
                SELECT COUNT(*) FILTER (WHERE event = 'SEARCH') AS searches,
                       COUNT(*) FILTER (WHERE event = 'SEARCH' AND COALESCE(result_cnt, 0) = 0) AS no_result,
                       COUNT(*) FILTER (WHERE event = 'CLICK') AS clicks,
                       COUNT(DISTINCT user_id) FILTER (WHERE event = 'SEARCH') AS users
                  FROM ai_event_log
                 WHERE db_key = :db_key AND feature = 'DOC'
                   AND _created >= CAST(:fromDate AS date) AND _created < CAST(:toDate AS date) + 1
                """, p);
        List<Map<String, Object>> rows = store.queryLog("""
                SELECT _created, user_name, source, event, query_text, result_cnt, top_score, final_value, rank_no
                  FROM ai_event_log
                 WHERE db_key = :db_key AND feature = 'DOC'
                   AND _created >= CAST(:fromDate AS date) AND _created < CAST(:toDate AS date) + 1
                   AND (:noResult = false OR (event = 'SEARCH' AND COALESCE(result_cnt, 0) = 0))
                 ORDER BY _created DESC
                 LIMIT 500
                """, p);
        List<Map<String, Object>> topNoResult = store.queryLog("""
                SELECT query_text, COUNT(*) AS cnt, MAX(_created) AS last_at
                  FROM ai_event_log
                 WHERE db_key = :db_key AND feature = 'DOC' AND event = 'SEARCH' AND COALESCE(result_cnt, 0) = 0
                   AND _created >= CAST(:fromDate AS date) AND _created < CAST(:toDate AS date) + 1
                 GROUP BY query_text
                 ORDER BY cnt DESC, last_at DESC
                 LIMIT 20
                """, p);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", sum.isEmpty() ? Map.of() : sum.get(0));
        data.put("rows", rows);
        data.put("topNoResult", topNoResult);
        data.put("logReady", store.tablesReady());
        return data;
    }
}
