package mes.app.AS;

import lombok.extern.slf4j.Slf4j;
import mes.app.AS.service.CtiPushService;
import mes.app.annotation.ApiProduct;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 인터넷전화 수신 알림 (CTI)
 *
 * 실제 KT 연동은 별도 윈도우 서버 에이전트가 담당한다(규격이 32bit 윈도우 COM).
 * 여기서는 브라우저 쪽 구독과, 에이전트가 붙기 전까지 쓸 모의 푸시만 제공한다.
 */
@Slf4j
@ApiProduct(ApiProduct.P01)
@RestController
@RequestMapping("/api/AS/cti")
public class CtiCallController {

    @Autowired
    CtiPushService ctiPushService;

    /**
     * CTI(전화 수신 알림) 사용 여부.
     * 꺼져 있으면 화면이 구독을 아예 시작하지 않는다 — 쓰지도 않는 SSE 연결을
     * 사용자 수만큼 들고 있을 이유가 없고, '전화 연결됨' 표시도 보이면 안 된다.
     * 윈도우 에이전트가 붙은 사업체에서만 켠다.
     */
    @Value("${cti.enabled:false}")
    private boolean ctiEnabled;

    /** 모의 푸시 허용 여부. 운영에서는 반드시 false 로 둔다. */
    @Value("${cti.mock.enabled:false}")
    private boolean mockEnabled;

    /**
     * 윈도우 에이전트가 쓰는 공유 시크릿.
     * 비어 있으면 /event 엔드포인트 자체가 닫힌다(기본 닫힘).
     */
    @Value("${cti.agent.secret:}")
    private String agentSecret;

    // ── 화면 구독 ────────────────────────────────────────────
    // 로그인한 본인 앞으로 오는 알림만 받는다. 대상 지정 파라미터가 없다.
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(Authentication auth) {
        if (!ctiEnabled) return ResponseEntity.status(503).build();

        User user = (User) auth.getPrincipal();
        return ResponseEntity.ok(ctiPushService.subscribe(user.getUsername()));
    }

    // ── 화면 초기화용 설정 ───────────────────────────────────
    @GetMapping("/config")
    public AjaxResult config() {
        AjaxResult result = new AjaxResult();
        result.data = Map.of("enabled", ctiEnabled, "mockEnabled", ctiEnabled && mockEnabled);
        return result;
    }

    // ── 모의 수신 (개발/시연용) ──────────────────────────────
    // 자기 자신에게만 보낸다. 남의 화면으로는 보낼 수 없다.
    @PostMapping("/mock")
    public AjaxResult mock(
            @RequestParam(value = "callnum") String callnum,
            @RequestParam(value = "result", required = false, defaultValue = "201") String resultCode,
            @RequestParam(value = "callee", required = false, defaultValue = "") String callee,
            Authentication auth) {

        AjaxResult res = new AjaxResult();

        if (!ctiEnabled || !mockEnabled) {
            res.success = false;
            res.message = "모의 수신이 꺼져 있습니다. (cti.mock.enabled=false)";
            return res;
        }

        String digits = callnum == null ? "" : callnum.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            res.success = false;
            res.message = "발신번호를 입력해주세요.";
            return res;
        }

        User user = (User) auth.getPrincipal();
        Map<String, Object> payload = buildPayload(digits, callee, resultCode, true);

        int sent = ctiPushService.pushIncomingCall(user.getUsername(), payload);

        res.data    = Map.of("sent", sent, "payload", payload);
        res.message = sent > 0 ? "모의 수신을 보냈습니다." : "열려 있는 화면이 없습니다.";
        return res;
    }

    // ── 에이전트 → 서버 (실제 전화 수신 통지) ────────────────
    //    KT 통화매니저 API 는 32bit 윈도우 COM 규격이라 이 서버가 직접 붙지 못한다.
    //    윈도우 서버의 에이전트가 EventV2CID 를 받아 이 창구로 넘기면,
    //    서버가 해당 사업체 담당자 화면으로만 중계한다.
    //
    //    브라우저가 아니라 세션/CSRF 가 없어 공유 시크릿으로 검증한다.
    //    cti.agent.secret 이 비어 있으면 아예 닫혀 있다.
    @PostMapping("/event")
    public ResponseEntity<Map<String, Object>> event(
            @RequestHeader(value = "X-Cti-Secret", required = false) String secret,
            @RequestParam(value = "username") String username,
            @RequestParam(value = "caller")   String caller,
            @RequestParam(value = "callee",   required = false, defaultValue = "")    String callee,
            @RequestParam(value = "result",   required = false, defaultValue = "201") String resultCode,
            @RequestParam(value = "dbId",     required = false, defaultValue = "")    String dbId) {

        if (!ctiEnabled || agentSecret == null || agentSecret.isBlank()) {
            log.warn("[CTI] 에이전트 수신 요청이 왔으나 cti.agent.secret 이 설정돼 있지 않음");
            return ResponseEntity.status(503).body(Map.of("message", "에이전트 연동이 설정돼 있지 않습니다."));
        }
        if (secret == null || !agentSecret.equals(secret)) {
            log.warn("[CTI] 에이전트 시크릿 불일치 — username={}", username);
            return ResponseEntity.status(401).body(Map.of("message", "인증 실패"));
        }

        String digits = caller == null ? "" : caller.replaceAll("[^0-9]", "");
        if (digits.isEmpty() || username.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "username 과 caller 는 필수입니다."));
        }

        Map<String, Object> payload = buildPayload(digits, callee, resultCode, false);
        if (!dbId.isBlank()) payload.put("dbId", dbId);   // 같은 통화의 상태 변화를 묶는 키

        int sent = ctiPushService.pushIncomingCall(username, payload);
        return ResponseEntity.ok(Map.of("sent", sent));
    }

    // ── 연결 상태 확인 ───────────────────────────────────────
    @GetMapping("/status")
    public AjaxResult status(Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        result.data = Map.of("screens", ctiPushService.count(user.getUsername()));
        return result;
    }

    /**
     * 화면으로 내려보낼 수신 정보.
     * KT 규격의 EventV2CID(sCaller, sCallee, sResult, sDBID) 를 그대로 옮겨두었다.
     * 실제 에이전트가 붙어도 화면 코드를 고치지 않아도 되게 하기 위함이다.
     */
    private Map<String, Object> buildPayload(String caller, String callee, String resultCode, boolean mock) {
        LocalDateTime now = LocalDateTime.now();

        Map<String, Object> payload = new HashMap<>();
        payload.put("caller",     caller);                                   // sCaller  발신번호
        payload.put("callee",     callee);                                   // sCallee  수신번호
        payload.put("result",     resultCode);                               // sResult  수신상태
        payload.put("statusText", statusText(resultCode));
        payload.put("dbId",       (mock ? "MOCK-" : "") + System.currentTimeMillis()); // sDBID
        payload.put("callDate",   now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        payload.put("callTime",   now.format(DateTimeFormatter.ofPattern("HH:mm")));
        payload.put("mock",       mock);
        return payload;
    }

    /** 규격서 10.1 수신상태 */
    private String statusText(String code) {
        if (code == null) return "알 수 없음";
        switch (code) {
            case "200": return "통화 연결";
            case "201": return "수신중";
            case "202":
            case "203": return "부재중";
            case "204": return "부재중(수신차단)";
            case "291": return "통화 종료";
            case "401": return "결번";
            case "404": return "통화중";
            case "405": return "무응답";
            case "406": return "착신 연결 실패";
            case "407": return "발신자가 끊음";
            case "408": return "착신자가 끊음";
            default:    return "상태 " + code;
        }
    }
}
