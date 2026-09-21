package mes.app.ai.engine;

import java.util.*;
import java.util.function.Predicate;

/**
 * 메모리 TF-IDF 색인 + 코사인 유사도 검색.
 *
 * 문서마다 토큰 가중치를 L2 정규화해 두고, 질의는 역색인(posting)으로 겹치는 문서만 점수를 더한다.
 * 사업체당 수만~10만 건의 짧은 문장이면 수십 MB 안쪽, 질의 한 번에 수 ms.
 * 한번 만든 색인은 바꾸지 않는다(읽기 전용) — 재색인은 새로 만들어 통째로 교체한다.
 *
 * @param <M> 문서에 붙는 부가정보 (코드, 키 등)
 */
public final class TfIdfIndex<M> {

    public record Hit<M>(M meta, double score, int doc) {
    }

    private final List<M> metas;
    private final Map<String, Integer> vocab;
    private final float[] idf;
    private final int[][] docTerms;
    private final float[][] docWeights;
    private final int[][] postings;
    private final boolean dropDigits;

    private TfIdfIndex(List<M> metas, Map<String, Integer> vocab, float[] idf, int[][] docTerms,
                       float[][] docWeights, int[][] postings, boolean dropDigits) {
        this.metas = metas;
        this.vocab = vocab;
        this.idf = idf;
        this.docTerms = docTerms;
        this.docWeights = docWeights;
        this.postings = postings;
        this.dropDigits = dropDigits;
    }

    public static <M> TfIdfIndex<M> build(List<M> metas, List<String> texts, boolean dropDigits) {
        int n = texts.size();
        Map<String, Integer> vocab = new HashMap<>();
        List<Integer> df = new ArrayList<>();
        int[][] termIds = new int[n][];
        int[][] termTfs = new int[n][];

        for (int d = 0; d < n; d++) {
            Map<Integer, Integer> tf = new HashMap<>();
            for (String t : FaultText.tokens(texts.get(d), dropDigits)) {
                Integer id = vocab.get(t);
                if (id == null) {
                    id = vocab.size();
                    vocab.put(t, id);
                    df.add(0);
                }
                tf.merge(id, 1, Integer::sum);
            }
            int[] ids = new int[tf.size()];
            int[] tfs = new int[tf.size()];
            int i = 0;
            for (Map.Entry<Integer, Integer> e : tf.entrySet()) {
                ids[i] = e.getKey();
                tfs[i] = e.getValue();
                df.set(e.getKey(), df.get(e.getKey()) + 1);
                i++;
            }
            termIds[d] = ids;
            termTfs[d] = tfs;
        }

        int v = vocab.size();
        float[] idf = new float[v];
        for (int t = 0; t < v; t++) {
            idf[t] = (float) (Math.log((n + 1.0) / (df.get(t) + 1.0)) + 1.0);
        }

        float[][] weights = new float[n][];
        int[] postCount = new int[v];
        for (int d = 0; d < n; d++) {
            int[] ids = termIds[d];
            float[] w = new float[ids.length];
            double norm = 0;
            for (int i = 0; i < ids.length; i++) {
                w[i] = (float) ((1 + Math.log(termTfs[d][i])) * idf[ids[i]]);
                norm += w[i] * w[i];
                postCount[ids[i]]++;
            }
            norm = Math.sqrt(norm);
            if (norm > 0) {
                for (int i = 0; i < w.length; i++) w[i] /= (float) norm;
            }
            weights[d] = w;
        }

        int[][] postings = new int[v][];
        for (int t = 0; t < v; t++) postings[t] = new int[postCount[t]];
        int[] fill = new int[v];
        for (int d = 0; d < n; d++) {
            for (int id : termIds[d]) postings[id][fill[id]++] = d;
        }

        return new TfIdfIndex<>(List.copyOf(metas), vocab, idf, termIds, weights, postings, dropDigits);
    }

    public int size() {
        return metas.size();
    }

    public M meta(int doc) {
        return metas.get(doc);
    }

    /**
     * @param minScore 이 점수(0~1) 미만은 버린다
     * @param filter   null 이면 전체
     */
    public List<Hit<M>> search(String text, int topN, double minScore, Predicate<M> filter) {
        Map<Integer, Float> q = queryVector(text);
        if (q.isEmpty() || topN <= 0) return List.of();

        Map<Integer, Float> acc = new HashMap<>();
        for (Map.Entry<Integer, Float> e : q.entrySet()) {
            int term = e.getKey();
            float qw = e.getValue();
            for (int d : postings[term]) {
                float dw = weightOf(d, term);
                acc.merge(d, qw * dw, Float::sum);
            }
        }

        // 점수가 같으면 먼저 넣은 문서(색인을 최신순으로 만들므로 최근 건)를 남긴다
        Comparator<Hit<M>> worstFirst = Comparator.<Hit<M>>comparingDouble(Hit::score)
                .thenComparing(Comparator.<Hit<M>>comparingInt(Hit::doc).reversed());
        PriorityQueue<Hit<M>> heap = new PriorityQueue<>(worstFirst);
        for (Map.Entry<Integer, Float> e : acc.entrySet()) {
            double score = e.getValue();
            if (score < minScore) continue;
            M meta = metas.get(e.getKey());
            if (filter != null && !filter.test(meta)) continue;
            if (heap.size() < topN) {
                heap.add(new Hit<>(meta, score, e.getKey()));
            } else if (score > heap.peek().score() || (score == heap.peek().score() && e.getKey() < heap.peek().doc())) {
                heap.poll();
                heap.add(new Hit<>(meta, score, e.getKey()));
            }
        }
        List<Hit<M>> hits = new ArrayList<>(heap);
        hits.sort(worstFirst.reversed());
        return hits;
    }

    /**
     * 질의 커버리지 (0~1): 질의 토큰 중 이 문서에 들어 있는 비율을 IDF 로 가중.
     * 긴 문서는 코사인이 낮게 나오므로, "물어본 말이 자료에 다 들어 있나"를 따로 본다 (기술자료 검색).
     * 색인에 없는 질의 토큰도 분모에 넣는다 (자료 어디에도 없는 말).
     */
    public double coverage(String text, int doc) {
        Set<String> seen = new HashSet<>();
        double total = 0, hit = 0;
        int[] ids = docTerms[doc];
        double unknownIdf = Math.log(metas.size() + 1.0) + 1.0;
        for (String t : FaultText.tokens(text, dropDigits)) {
            // 한글 단어 전체(w:)는 빼고 두 글자 조각으로 본다 — "안전" 이 "안전수첩" 안에 있어도 일치로 쳐야 한다
            if (t.startsWith("w:") && FaultText.isHangul(t.charAt(2))) continue;
            if (!seen.add(t)) continue;
            Integer id = vocab.get(t);
            if (id == null) {
                total += unknownIdf;
                continue;
            }
            total += idf[id];
            for (int x : ids) {
                if (x == id) {
                    hit += idf[id];
                    break;
                }
            }
        }
        return total > 0 ? hit / total : 0;
    }

    /** 질의 토큰 중 색인에 있는 것 — 발췌 하이라이트용 */
    public Set<String> knownTokens(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : FaultText.tokens(text, dropDigits)) {
            if (vocab.containsKey(t)) out.add(t);
        }
        return out;
    }

    private Map<Integer, Float> queryVector(String text) {
        Map<Integer, Integer> tf = new HashMap<>();
        for (String t : FaultText.tokens(text, dropDigits)) {
            Integer id = vocab.get(t);
            if (id != null) tf.merge(id, 1, Integer::sum);
        }
        Map<Integer, Float> q = new HashMap<>();
        double norm = 0;
        for (Map.Entry<Integer, Integer> e : tf.entrySet()) {
            float w = (float) ((1 + Math.log(e.getValue())) * idf[e.getKey()]);
            q.put(e.getKey(), w);
            norm += w * w;
        }
        // 질의에만 있고 색인에 없는 토큰도 분모에 넣어야 "일부만 겹침"이 과대평가되지 않는다
        for (String t : FaultText.tokens(text, dropDigits)) {
            if (!vocab.containsKey(t)) norm += 1.0;
        }
        double len = Math.sqrt(norm);
        if (len > 0) q.replaceAll((k, w) -> (float) (w / len));
        return q;
    }

    private float weightOf(int doc, int term) {
        int[] ids = docTerms[doc];
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] == term) return docWeights[doc][i];
        }
        return 0f;
    }
}
