package mes.app.ai;

import lombok.extern.slf4j.Slf4j;
import mes.app.ai.service.AiIndexService;
import mes.app.ai.service.AiStoreService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * AI 화면이 뒤에서 부르는 보조 API — 사용량(P04)에 넣지 않는다.
 *
 * AiFaultController 와 경로는 같지만 일부러 따로 뒀다.
 * 그쪽에는 @ApiProduct(P04) 가 붙어 있어 호출마다 사업체 API 사용량(과금 집계)이 올라가는데,
 * 아래 두 가지는 사용자가 기능을 쓴 게 아니라 화면이 자동으로 보내는 요청이다.
 *   · /log          저장 시 채택 여부, 참고카드 열람·클릭, 검색 결과 클릭 기록
 *   · /index_status 화면을 열 때 색인을 미리 준비시키는 요청 / 관리 화면 색인 현황 표시
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
public class AiSupportController {

    @Autowired
    AiStoreService store;

    @Autowired
    AiIndexService indexService;

    private static final Set<String> FEATURES = Set.of("CLASSIFY", "CASE", "DOC");
    private static final Set<String> EVENTS = Set.of("SAVE", "OPEN", "CLICK");

    @PostMapping("/log")
    public AjaxResult log(@RequestParam Map<String, Object> params, Authentication auth) {
        AjaxResult result = new AjaxResult();
        String feature = String.valueOf(params.getOrDefault("feature", ""));
        String event = String.valueOf(params.getOrDefault("event", ""));
        if (!FEATURES.contains(feature) || !EVENTS.contains(event)) {
            result.success = false;
            result.message = "잘못된 기록입니다.";
            return result;
        }
        Map<String, Object> values = new HashMap<>(params);
        values.remove("_csrf");
        store.logEvent(values, auth);
        return result;
    }

    /** which = classify / case / doc. build=true 면 없을 때 만들어서 돌려준다 */
    @GetMapping("/index_status")
    public AjaxResult indexStatus(@RequestParam("which") String which,
                                  @RequestParam(value = "build", defaultValue = "false") boolean build) {
        AjaxResult result = new AjaxResult();
        try {
            String dbKey = store.dbKey();
            if (build) {
                result.data = switch (which) {
                    case "classify" -> indexService.classifier(dbKey, store.spjangcd()).status();
                    case "case" -> indexService.caseIndex(dbKey, store.spjangcd()).status();
                    default -> indexService.docIndex(dbKey).status();
                };
            } else {
                Map<String, Object> s = indexService.peekStatus(dbKey, which);
                result.data = s == null ? Map.of("size", -1) : s;
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            result.success = false;
            result.message = e.getMessage();
        } catch (Exception e) {
            log.error("[AI] 색인 상태 조회 오류", e);
            result.success = false;
            result.message = "처리 중 오류가 발생했습니다.";
        }
        return result;
    }
}
