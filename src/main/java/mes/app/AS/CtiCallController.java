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
import java.util.List;
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

    // CTI 사용 여부를 설정으로 선언하지 않는다.
    // 에이전트가 실제로 붙어 있는지로 판단한다 — 설정은 에이전트가 죽어도 '켜짐'이라고
    // 거짓말을 하지만, 에이전트 신호는 그렇지 않다. 화면의 연결 표시도 이 값을 따른다.

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
        User user = (User) auth.getPrincipal();
        return ResponseEntity.ok(ctiPushService.subscribe(user.getUsername()));
    }

    // ── 화면 초기화용 설정 ───────────────────────────────────
    @GetMapping("/config")
    public AjaxResult config() {
        AjaxResult result = new AjaxResult();
        result.data = Map.of("mockEnabled", mockEnabled);
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

        if (!mockEnabled) {
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

        ResponseEntity<Map<String, Object>> denied = checkAgent(secret);
        if (denied != null) return denied;

        // 전화가 왔다는 건 에이전트가 살아 있다는 뜻이기도 하다
        ctiPushService.agentOnline(username);

        String digits = caller == null ? "" : caller.replaceAll("[^0-9]", "");
        if (digits.isEmpty() || username.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "username 과 caller 는 필수입니다."));
        }

        Map<String, Object> payload = buildPayload(digits, callee, resultCode, false);
        if (!dbId.isBlank()) payload.put("dbId", dbId);   // 같은 통화의 상태 변화를 묶는 키

        int sent = ctiPushService.pushIncomingCall(username, payload);
        return ResponseEntity.ok(Map.of("sent", sent));
    }

    // ── 화면 → 에이전트 (연결 / 해제) ────────────────────────
    //    브라우저는 PC 의 프로그램을 직접 부를 수 없다.
    //    여기에 명령을 적어두면 에이전트가 주기적으로 가져가 실행한다.
    //    KT 계정과 비밀번호는 그 PC 에만 있고 이 경로를 지나가지 않는다.
    @PostMapping("/connect")
    public AjaxResult connect(Authentication auth) {
        User user = (User) auth.getPrincipal();
        ctiPushService.queueCommand(user.getUsername(), "connect");

        AjaxResult result = new AjaxResult();
        result.message = ctiPushService.isAgentOnline(user.getUsername())
                ? "이미 연결돼 있습니다."
                : "연결을 요청했습니다. 잠시 후 연결됩니다.";
        return result;
    }

    @PostMapping("/disconnect")
    public AjaxResult disconnect(Authentication auth) {
        User user = (User) auth.getPrincipal();
        ctiPushService.queueCommand(user.getUsername(), "disconnect");

        AjaxResult result = new AjaxResult();
        result.message = "해제를 요청했습니다.";
        return result;
    }

    /** 에이전트가 할 일이 있는지 물어본다. 가져가면 지워진다. */
    @GetMapping("/agent-poll")
    public ResponseEntity<Map<String, Object>> agentPoll(
            @RequestHeader(value = "X-Cti-Secret", required = false) String secret,
            @RequestParam(value = "username") String username) {

        ResponseEntity<Map<String, Object>> denied = checkAgent(secret);
        if (denied != null) return denied;

        String command = ctiPushService.takeCommand(username);

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("command", command);

        // 문자는 내용이 길고 쉼표·따옴표가 섞이므로 명령 문자열에 끼우지 않고 따로 싣는다
        if ("sms".equals(command)) {
            Map<String, Object> job = ctiPushService.takeSms(username);
            if (job == null) return ResponseEntity.ok(Map.of("command", ""));   // 내용이 사라졌으면 보내지 않는다
            body.put("sms", job);
        }
        return ResponseEntity.ok(body);
    }

    // ── 문자 보내기 (화면 → 서버 → 그 PC 의 에이전트) ───────────
    //
    // 문자는 KT COM 함수(SetRecvPhone/SendSMS)라 서버가 직접 못 보낸다.
    // 규격서 5.1.1 — 수신 최대 32명, 단문 80바이트.
    @PostMapping("/sms")
    public AjaxResult sendSms(
            @RequestParam(value = "to")      String to,
            @RequestParam(value = "message") String message,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        String username = ((User) auth.getPrincipal()).getUsername();

        if (!ctiPushService.isAgentOnline(username)) {
            result.success = false;
            result.message = "전화 프로그램이 연결돼 있지 않습니다. [연결] 을 먼저 눌러주세요.";
            return result;
        }

        List<String> recipients = new java.util.ArrayList<>();
        for (String one : to.split(",")) {
            String digits = one.replaceAll("[^0-9]", "");
            if (!digits.isEmpty() && !recipients.contains(digits)) recipients.add(digits);
        }

        if (recipients.isEmpty()) {
            result.success = false;
            result.message = "받는 사람을 넣어주세요.";
            return result;
        }
        if (recipients.size() > 32) {
            result.success = false;
            result.message = "한 번에 32명까지만 보낼 수 있습니다. (지금 " + recipients.size() + "명)";
            return result;
        }
        if (message == null || message.isBlank()) {
            result.success = false;
            result.message = "내용을 넣어주세요.";
            return result;
        }

        int bytes = message.getBytes(java.nio.charset.Charset.forName("EUC-KR")).length;
        if (bytes > 80) {
            result.success = false;
            result.message = "내용이 깁니다. 80바이트(한글 40자)까지입니다. (지금 " + bytes + "바이트)";
            return result;
        }

        ctiPushService.queueSms(username, recipients, message);
        result.success = true;
        result.message = "보내는 중입니다.";
        return result;
    }

    /** 에이전트가 발송 결과를 알려온다. 화면으로 그대로 올린다. */
    @PostMapping("/sms-result")
    public ResponseEntity<Map<String, Object>> smsResult(
            @RequestHeader(value = "X-Cti-Secret", required = false) String secret,
            @RequestParam(value = "username") String username,
            @RequestParam(value = "ok")       String ok,
            @RequestParam(value = "message", required = false) String message) {

        ResponseEntity<Map<String, Object>> denied = checkAgent(secret);
        if (denied != null) return denied;

        ctiPushService.pushSmsResult(username, "1".equals(ok) || "true".equalsIgnoreCase(ok),
                                     message == null ? "" : message);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── 에이전트 생사 신고 ───────────────────────────────────
    //    에이전트가 KT 로그인에 성공하면 online, 종료하면 offline 을 보낸다.
    //    online 은 30초마다 다시 보내며, 90초 동안 소식이 없으면 서버가 끊긴 것으로 본다.
    //    화면의 '전화 연결됨' 표시가 이 상태를 그대로 따라간다.
    @PostMapping("/agent-online")
    public ResponseEntity<Map<String, Object>> agentOnline(
            @RequestHeader(value = "X-Cti-Secret", required = false) String secret,
            @RequestParam(value = "username") String username) {

        ResponseEntity<Map<String, Object>> denied = checkAgent(secret);
        if (denied != null) return denied;

        ctiPushService.agentOnline(username);
        return ResponseEntity.ok(Map.of("screens", ctiPushService.count(username)));
    }

    @PostMapping("/agent-offline")
    public ResponseEntity<Map<String, Object>> agentOffline(
            @RequestHeader(value = "X-Cti-Secret", required = false) String secret,
            @RequestParam(value = "username") String username) {

        ResponseEntity<Map<String, Object>> denied = checkAgent(secret);
        if (denied != null) return denied;

        ctiPushService.agentOffline(username);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 에이전트 공통 인증. 통과하면 null, 막히면 그대로 돌려줄 응답을 반환한다. */
    private ResponseEntity<Map<String, Object>> checkAgent(String secret) {
        if (agentSecret == null || agentSecret.isBlank()) {
            log.warn("[CTI] 에이전트 요청이 왔으나 cti.agent.secret 이 비어 있음");
            return ResponseEntity.status(503).body(Map.of(
                    "message", "cti.agent.secret 이 비어 있습니다. server_elv.env 에 cti_agent_secret 을 넣으세요."));
        }
        if (secret == null || !agentSecret.equals(secret)) {
            log.warn("[CTI] 에이전트 시크릿 불일치");
            return ResponseEntity.status(401).body(Map.of("message", "인증 실패"));
        }
        return null;
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
