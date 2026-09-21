package mes.app.ai.engine;

import java.util.*;

/**
 * 고장 상세내용 → 고장내용 코드 추천.
 *
 * 1) 키워드 사전: 관리자가 등록한 키워드가 문장에 들어 있으면 그 코드를 신뢰도 100 으로 추천
 * 2) 과거 접수 이력: 문장이 비슷한 과거 접수 K 건의 코드를 유사도로 가중 투표
 *    신뢰도 = 그 코드의 득표 비중 × 근거 강도(가장 비슷한 이웃의 유사도가 낮으면 깎는다)
 *
 * 스프링 의존 없음 — 오프라인 정확도 측정에도 같은 클래스를 쓴다.
 */
public final class FaultClassifier {

    public record Candidate(String code, int confidence, String basis, String matchedKeyword, double topSimilarity,
                            int neighborCount) {
    }

    // 기본값은 경기(GO) 접수 1만 건으로 오프라인 측정해 정했다 (과거 80% 학습 / 최근 20% 평가):
    //   top1 정확도 74.9%, 신뢰도 60%↑ 추천 노출 82.7% · 정답률 81.1%, 80%↑ 노출 74.3% · 정답률 84.3%
    //   '기타' 코드를 투표에서 빼면 오히려 떨어져서 기본은 빼지 않는다
    /** 이웃 수 */
    int topK = 10;
    /** 이 유사도 미만 이웃은 버린다 */
    double minSim = 0.15;
    /** 가장 비슷한 이웃이 이 유사도 이상이면 근거 강도 1.0 */
    double fullSim = 0.5;

    /** 오프라인 튜닝용 */
    public FaultClassifier params(int topK, double minSim, double fullSim) {
        this.topK = topK;
        this.minSim = minSim;
        this.fullSim = fullSim;
        return this;
    }

    private final TfIdfIndex<String> index;

    private FaultClassifier(TfIdfIndex<String> index) {
        this.index = index;
    }

    /** codes[i] 는 texts[i] 의 정답 고장내용 코드 */
    public static FaultClassifier build(List<String> codes, List<String> texts) {
        return new FaultClassifier(TfIdfIndex.build(codes, texts, true));
    }

    public int size() {
        return index.size();
    }

    /**
     * @param keywords 코드 → 키워드 목록 (없으면 빈 맵)
     * @param excluded 이력 투표에서 뺄 코드 (예: '기타'), null 가능
     */
    public List<Candidate> classify(String text, Map<String, List<String>> keywords, Set<String> excluded, int limit) {
        Map<String, Candidate> result = new LinkedHashMap<>();

        // 1) 키워드 — 긴 키워드가 더 구체적이므로 먼저
        String compact = FaultText.compact(text);
        if (!compact.isEmpty() && keywords != null) {
            List<String[]> hits = new ArrayList<>();
            keywords.forEach((code, list) -> {
                for (String kw : list) {
                    String k = FaultText.compact(kw);
                    if (!k.isEmpty() && compact.contains(k)) hits.add(new String[]{code, kw, String.valueOf(k.length())});
                }
            });
            hits.sort((a, b) -> Integer.parseInt(b[2]) - Integer.parseInt(a[2]));
            for (String[] h : hits) {
                result.putIfAbsent(h[0], new Candidate(h[0], 100, "KEYWORD", h[1], 1.0, 0));
            }
        }

        // 2) 이력 투표
        List<TfIdfIndex.Hit<String>> neighbors = index.search(text, topK, minSim,
                code -> code != null && !code.isEmpty() && (excluded == null || !excluded.contains(code)));
        if (!neighbors.isEmpty()) {
            Map<String, Double> votes = new HashMap<>();
            Map<String, Double> best = new HashMap<>();
            Map<String, Integer> counts = new HashMap<>();
            double total = 0;
            for (TfIdfIndex.Hit<String> h : neighbors) {
                double w = h.score() * h.score();
                votes.merge(h.meta(), w, Double::sum);
                best.merge(h.meta(), h.score(), Math::max);
                counts.merge(h.meta(), 1, Integer::sum);
                total += w;
            }
            double totalVotes = total;
            List<Map.Entry<String, Double>> ranked = new ArrayList<>(votes.entrySet());
            ranked.sort(Map.Entry.<String, Double>comparingByValue().reversed());
            for (Map.Entry<String, Double> e : ranked) {
                String code = e.getKey();
                double share = e.getValue() / totalVotes;
                double strength = Math.min(1.0, best.get(code) / fullSim);
                int conf = (int) Math.round(share * strength * 100);
                result.putIfAbsent(code, new Candidate(code, conf, "HISTORY", null, best.get(code), counts.get(code)));
            }
        }

        List<Candidate> list = new ArrayList<>(result.values());
        list.sort((a, b) -> b.confidence() - a.confidence());
        return list.size() > limit ? new ArrayList<>(list.subList(0, limit)) : list;
    }
}
