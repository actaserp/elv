package mes.app.ai;

import lombok.extern.slf4j.Slf4j;
import mes.app.ai.service.*;
import mes.app.annotation.ApiProduct;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;

/**
 * AI 기반 고장분석 API — 상품 P04 로 사용량 집계
 *
 * /api/ai/classify/*  고장유형 자동분류 (고장유형분류체계관리 · 고장접수 추천 배지)
 * /api/ai/similar/*   유사 고장사례 (유사사례DB관리 · 모바일 고장처리등록 참고카드)
 * /api/ai/doc/*       기술자료 검색 (기술자료검색엔진관리 · 모바일 자료실 AI 검색)
 *
 * 화면이 뒤에서 보내는 사용 기록(/api/ai/log)과 색인 준비(/api/ai/index_status)는
 * 사용량에 넣지 않으려고 {@link AiSupportController} 로 뺐다.
 *
 * 사업체·사업장은 요청 파라미터가 아니라 로그인 세션 값으로 정한다.
 */
@Slf4j
@ApiProduct(ApiProduct.P04)
@RestController
@RequestMapping("/api/ai")
public class AiFaultController {

    @Autowired
    AiStoreService store;

    @Autowired
    AiIndexService indexService;

    @Autowired
    AiClassifyService classifyService;

    @Autowired
    AiSimilarCaseService similarCaseService;

    @Autowired
    AiDocSearchService docSearchService;

    // ── 고장유형 자동분류 ───────────────────────────────────

    @PostMapping("/classify/suggest")
    public AjaxResult suggest(@RequestParam(value = "text", required = false) String text,
                              @RequestParam(value = "memo", required = false) String memo) {
        return run(() -> classifyService.suggest(text, memo));
    }

    @GetMapping("/classify/codes")
    public AjaxResult codes() {
        return run(() -> classifyService.codes());
    }

    @GetMapping("/classify/keywords")
    public AjaxResult keywords(@RequestParam(value = "contcd", required = false) String contcd) {
        return run(() -> store.keywords(contcd));
    }

    @PostMapping("/classify/keyword/save")
    public AjaxResult saveKeyword(@RequestParam(value = "id", required = false) Integer id,
                                  @RequestParam("contcd") String contcd,
                                  @RequestParam("keyword") String keyword,
                                  @RequestParam(value = "useYn", defaultValue = "Y") String useYn,
                                  Authentication auth) {
        return run(() -> {
            String kw = keyword == null ? "" : keyword.trim();
            if (contcd == null || contcd.isBlank()) throw new IllegalArgumentException("고장내용을 선택해주세요.");
            if (kw.length() < 1 || kw.length() > 50) throw new IllegalArgumentException("키워드는 1~50자로 입력해주세요.");
            store.saveKeyword(id, contcd, kw, useYn, AiStoreService.userId(auth));
            return null;
        }, "저장되었습니다.");
    }

    /** 후보 여러 개를 한 번에 추가 — 이미 있는 것은 건너뛴다 */
    @PostMapping("/classify/keyword/add_many")
    public AjaxResult addKeywords(@RequestParam("contcd") String contcd,
                                  @RequestParam("keywords") List<String> keywords,
                                  Authentication auth) {
        return run(() -> {
            int added = 0, skipped = 0;
            for (String k : keywords) {
                if (k == null || k.isBlank() || k.trim().length() > 50) {
                    skipped++;
                    continue;
                }
                try {
                    store.saveKeyword(null, contcd, k.trim(), "Y", AiStoreService.userId(auth));
                    added++;
                } catch (IllegalArgumentException dup) {
                    skipped++;
                }
            }
            return Map.of("added", added, "skipped", skipped);
        });
    }

    @PostMapping("/classify/keyword/delete")
    public AjaxResult deleteKeyword(@RequestParam("id") Integer id) {
        return run(() -> {
            store.deleteKeyword(id);
            return null;
        }, "삭제되었습니다.");
    }

    @GetMapping("/classify/keyword_candidates")
    public AjaxResult keywordCandidates(@RequestParam("contcd") String contcd) {
        return run(() -> classifyService.keywordCandidates(contcd));
    }

    @GetMapping("/classify/stats")
    public AjaxResult classifyStats(@RequestParam("fromDate") String fromDate, @RequestParam("toDate") String toDate) {
        return run(() -> classifyService.stats(fromDate, toDate));
    }

    @PostMapping("/classify/reindex")
    public AjaxResult classifyReindex() {
        return run(() -> indexService.rebuildClassifier(store.dbKey(), store.spjangcd()).status());
    }

    // ── 설정 · 색인 현황 ────────────────────────────────────

    @GetMapping("/settings")
    public AjaxResult settings() {
        return run(() -> {
            Map<String, Object> m = new LinkedHashMap<>(store.settings());
            m.put("tablesReady", store.tablesReady());
            return m;
        });
    }

    @PostMapping("/settings/save")
    public AjaxResult saveSettings(@RequestParam Map<String, String> params, Authentication auth) {
        return run(() -> {
            Map<String, String> values = new LinkedHashMap<>();
            for (String key : AiStoreService.DEFAULTS.keySet()) {
                if (!params.containsKey(key)) continue;
                String v = params.get(key).trim();
                if (key.endsWith("_threshold")) {
                    int n;
                    try {
                        n = Integer.parseInt(v);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("임계치는 0~100 숫자로 입력해주세요.");
                    }
                    if (n < 0 || n > 100) throw new IllegalArgumentException("임계치는 0~100 숫자로 입력해주세요.");
                    v = String.valueOf(n);
                } else {
                    v = "Y".equals(v) ? "Y" : "N";
                }
                values.put(key, v);
            }
            store.saveSettings(values, AiStoreService.userId(auth));
            return null;
        }, "저장되었습니다.");
    }

    // ── 유사 고장사례 ───────────────────────────────────────

    @GetMapping("/similar/unit_history")
    public AjaxResult unitHistory(@RequestParam("actcd") String actcd,
                                  @RequestParam(value = "equpcd", required = false) String equpcd,
                                  @RequestParam(value = "limit", defaultValue = "5") int limit) {
        return run(() -> similarCaseService.unitHistory(actcd, equpcd, limit));
    }

    @PostMapping("/similar/cases")
    public AjaxResult similarCases(@RequestParam(value = "recedate", required = false) String recedate,
                                   @RequestParam(value = "recenum", required = false) String recenum,
                                   @RequestParam(value = "contcd", required = false) String contcd,
                                   @RequestParam(value = "text", required = false) String text,
                                   @RequestParam(value = "actcd", required = false) String actcd,
                                   @RequestParam(value = "equpcd", required = false) String equpcd,
                                   @RequestParam(value = "topN", defaultValue = "3") int topN,
                                   @RequestParam(value = "excludeSameUnit", defaultValue = "true") boolean excludeSameUnit,
                                   @RequestParam(value = "test", defaultValue = "false") boolean test) {
        // test=true 는 관리 화면 검색 테스트 — 임계치 없이 보여준다
        return run(() -> similarCaseService.similarCases(recedate, recenum, contcd, text, actcd, equpcd,
                Math.max(1, Math.min(topN, 30)), excludeSameUnit, !test));
    }

    @PostMapping("/similar/reindex")
    public AjaxResult similarReindex() {
        return run(() -> indexService.rebuildCaseIndex(store.dbKey(), store.spjangcd()).status());
    }

    @GetMapping("/similar/case_list")
    public AjaxResult caseList(@RequestParam("fromDate") String fromDate,
                               @RequestParam("toDate") String toDate,
                               @RequestParam(value = "actnm", required = false) String actnm,
                               @RequestParam(value = "keyword", required = false) String keyword,
                               @RequestParam(value = "excludedOnly", defaultValue = "false") boolean excludedOnly) {
        return run(() -> similarCaseService.caseList(ymd(fromDate), ymd(toDate), actnm, keyword, excludedOnly));
    }

    @PostMapping("/similar/exclude")
    public AjaxResult exclude(@RequestParam("compdate") String compdate,
                              @RequestParam("compnum") String compnum,
                              @RequestParam("exclude") boolean exclude,
                              @RequestParam(value = "reason", required = false) String reason,
                              Authentication auth) {
        return run(() -> {
            store.setCaseExcluded(ymd(compdate), compnum, exclude, reason, AiStoreService.userId(auth));
            return null;
        }, exclude ? "추천에서 제외했습니다." : "제외를 해제했습니다.");
    }

    @GetMapping("/similar/stats")
    public AjaxResult similarStats(@RequestParam("fromDate") String fromDate, @RequestParam("toDate") String toDate) {
        return run(() -> similarCaseService.stats(fromDate, toDate));
    }

    // ── 기술자료 검색 ───────────────────────────────────────

    @PostMapping("/doc/search")
    public AjaxResult docSearch(@RequestParam("query") String query,
                                @RequestParam(value = "topN", defaultValue = "10") int topN,
                                @RequestParam(value = "source", defaultValue = "MOBILE") String source,
                                @RequestParam(value = "test", defaultValue = "false") boolean test,
                                Authentication auth) {
        // 관리 화면 테스트 검색은 사용 기록에 남기지 않는다
        return run(() -> docSearchService.search(query, topN, !test, source, test ? null : auth));
    }

    @PostMapping("/doc/reindex")
    public AjaxResult docReindex() {
        return run(() -> indexService.rebuildDocIndex(store.dbKey()).status());
    }

    @GetMapping("/doc/logs")
    public AjaxResult docLogs(@RequestParam("fromDate") String fromDate,
                              @RequestParam("toDate") String toDate,
                              @RequestParam(value = "noResultOnly", defaultValue = "false") boolean noResultOnly) {
        return run(() -> docSearchService.logs(fromDate, toDate, noResultOnly));
    }

    // ── 공통 ────────────────────────────────────────────────

    private static String ymd(String s) {
        String v = s == null ? "" : s.replace("-", "");
        if (!v.matches("\\d{8}")) throw new IllegalArgumentException("날짜를 확인해주세요.");
        LocalDate.parse(v.substring(0, 4) + "-" + v.substring(4, 6) + "-" + v.substring(6, 8));
        return v;
    }

    private AjaxResult run(Supplier<Object> work) {
        return run(work, null);
    }

    private AjaxResult run(Supplier<Object> work, String okMessage) {
        AjaxResult result = new AjaxResult();
        try {
            result.data = work.get();
            if (okMessage != null) result.message = okMessage;
        } catch (IllegalArgumentException | IllegalStateException e) {
            result.success = false;
            result.message = e.getMessage();
        } catch (Exception e) {
            log.error("[AI] 처리 오류", e);
            result.success = false;
            result.message = "처리 중 오류가 발생했습니다.";
        }
        return result;
    }
}
