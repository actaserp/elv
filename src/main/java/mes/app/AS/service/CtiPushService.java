package mes.app.AS.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 인터넷전화 수신 알림 푸시 (SSE)
 *
 * 브라우저가 /api/AS/cti/stream 을 구독하면 그 사용자 앞으로만 알림을 보낸다.
 * 구독 주소에 사용자 정보가 없고 서버가 로그인 계정으로만 대상을 고르므로,
 * 클라이언트가 주소를 바꿔서 남의 전화를 엿볼 방법이 없다.
 *
 * KT 통화매니저 API 는 32bit 윈도우 COM 규격이라 이 서버(리눅스)에서 직접 붙지 못한다.
 * 별도 윈도우 서버의 에이전트가 EventV2CID 를 받아 POST 로 넘겨주면
 * 이 서비스가 해당 사업체 담당자 화면으로 중계하는 구조를 전제로 한다.
 * 그 에이전트가 붙기 전까지는 모의 푸시(CtiCallController.mock)로 화면을 검증한다.
 */
@Slf4j
@Service
public class CtiPushService {

    /** 브라우저 연결 유지 시간. 끊겨도 EventSource 가 자동 재연결한다. */
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    /** 에이전트가 살아 있다고 보는 시간. 이 시간 안에 신호가 없으면 끊긴 것으로 본다. */
    private static final long AGENT_TTL_MS = 90 * 1000L;

    /** 화면에서 누른 명령이 유효한 시간. 지나면 버린다. */
    private static final long COMMAND_TTL_MS = 3 * 60 * 1000L;

    /** elv 로그인 계정(username) → 그 사용자가 열어둔 화면들 */
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /** elv 로그인 계정(username) → 그 사용자의 에이전트가 마지막으로 신호를 보낸 시각 */
    private final Map<String, Long> agentSeen = new ConcurrentHashMap<>();

    /**
     * elv 로그인 계정(username) → 에이전트가 가져갈 명령 ("connect" / "disconnect").
     * 브라우저는 PC 의 프로그램을 직접 부를 수 없어서, 화면이 여기에 적어두면
     * 에이전트가 주기적으로 가져간다. KT 비밀번호는 PC 에만 있고 여기를 지나가지 않는다.
     */
    private final Map<String, String> commands = new ConcurrentHashMap<>();

    /** 화면 한 개를 구독에 등록한다. 탭을 여러 개 열면 그 수만큼 쌓인다. */
    public SseEmitter subscribe(String username) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);

        emitters.computeIfAbsent(username, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(username, emitter));
        emitter.onTimeout(()    -> remove(username, emitter));
        emitter.onError(e       -> remove(username, emitter));

        // 첫 이벤트를 즉시 보내야 프록시가 연결을 확정한다.
        // 지금 에이전트가 붙어 있는지도 같이 알려줘야 화면이 처음부터 올바른 상태를 그린다.
        try {
            emitter.send(SseEmitter.event().name("ready").data(Map.of("ok", true)));
            emitter.send(SseEmitter.event().name("agent-status")
                    .data(Map.of("online", isAgentOnline(username))));
        } catch (IOException e) {
            remove(username, emitter);
        }

        log.debug("[CTI] 구독 시작: user={}, 화면수={}", username, count(username));
        return emitter;
    }

    /**
     * 지정한 사용자에게만 전화 수신 알림을 보낸다.
     * @return 실제로 전달된 화면 수. 0이면 그 사용자가 화면을 열어두지 않은 것이다.
     */
    public int pushIncomingCall(String username, Map<String, Object> payload) {
        List<SseEmitter> targets = emitters.get(username);
        if (targets == null || targets.isEmpty()) {
            log.info("[CTI] 수신 알림 대상 없음: user={}, caller={}", username, payload.get("caller"));
            return 0;
        }

        int sent = 0;
        for (SseEmitter emitter : targets) {
            try {
                emitter.send(SseEmitter.event().name("incoming-call").data(payload));
                sent++;
            } catch (Exception e) {
                remove(username, emitter);
            }
        }
        log.info("[CTI] 수신 알림 전달: user={}, caller={}, 화면수={}", username, payload.get("caller"), sent);
        return sent;
    }

    // ── 에이전트 생사 ────────────────────────────────────────
    // 설정으로 "이 사업체는 CTI 를 쓴다"고 선언하는 대신, 에이전트가 실제로 붙어 있는지로 판단한다.
    // 설정은 거짓말을 할 수 있지만(에이전트가 죽어도 켜져 있다고 나온다) 이 값은 그렇지 않다.

    /** 에이전트가 살아 있다고 알려왔다. 상태가 바뀌었으면 화면에 알린다. */
    public void agentOnline(String username) {
        boolean was = isAgentOnline(username);
        agentSeen.put(username, System.currentTimeMillis());
        if (!was) {
            log.info("[CTI] 에이전트 연결: user={}", username);
            sendAgentStatus(username, true);
        }
    }

    /** 에이전트가 내려갔다 */
    public void agentOffline(String username) {
        if (agentSeen.remove(username) != null) {
            log.info("[CTI] 에이전트 해제: user={}", username);
            sendAgentStatus(username, false);
        }
    }

    // ── 화면 → 에이전트 명령 ─────────────────────────────────

    /** 화면이 [연결]/[해제] 를 눌렀다. 에이전트가 가져갈 때까지 들고 있는다. */
    public void queueCommand(String username, String command) {
        commands.put(username, System.currentTimeMillis() + "|" + command);
        log.info("[CTI] 명령 대기: user={}, command={}", username, command);
    }

    // ── 문자 보내기 ──────────────────────────────────────────
    //
    // 문자도 KT COM 함수라 서버가 직접 못 보낸다. 전화를 받는 그 PC 의 에이전트가
    // SetRecvPhone → SendSMS 를 호출해야 해서, 연결/해제와 같은 명령 큐에 실어 보낸다.
    // 내용은 글자가 길고 쉼표·따옴표가 섞일 수 있어 명령 문자열에 끼워 넣지 않고 따로 둔다.
    private final Map<String, Map<String, Object>> smsJobs = new ConcurrentHashMap<>();

    public void queueSms(String username, List<String> recipients, String message) {
        Map<String, Object> job = new java.util.LinkedHashMap<>();
        job.put("to", recipients);
        job.put("message", message);
        smsJobs.put(username, job);

        queueCommand(username, "sms");
        log.info("[CTI] 문자 대기: user={}, 수신={}명, 길이={}자", username, recipients.size(), message.length());
    }

    /** 에이전트가 'sms' 명령을 가져갈 때 같이 집어간다. 한 번 가져가면 지운다. */
    public Map<String, Object> takeSms(String username) {
        return smsJobs.remove(username);
    }

    /** 발송 결과를 화면으로 올린다. 보냈는지 못 보냈는지 모르면 같은 문자를 또 보내게 된다. */
    public void pushSmsResult(String username, boolean ok, String message) {
        List<SseEmitter> targets = emitters.get(username);
        if (targets == null) return;

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("ok", ok);
        payload.put("message", message);

        for (SseEmitter emitter : targets) {
            try {
                emitter.send(SseEmitter.event().name("sms-result").data(payload));
            } catch (Exception e) {
                remove(username, emitter);
            }
        }
        log.info("[CTI] 문자 결과: user={}, ok={}, {}", username, ok, message);
    }

    /**
     * 에이전트가 가져간다. 한 번 가져가면 지운다. 없으면 빈 문자열.
     * 오래된 명령은 버린다 — 퇴근 전에 누른 [연결] 이 다음 날 부팅 때 되살아나면
     * 그 사이 다른 사람이 쓰고 있던 계정을 밀어낼 수 있다.
     */
    public String takeCommand(String username) {
        String raw = commands.remove(username);
        if (raw == null) return "";

        int i = raw.indexOf('|');
        long at = Long.parseLong(raw.substring(0, i));
        String command = raw.substring(i + 1);

        if (System.currentTimeMillis() - at > COMMAND_TTL_MS) {
            log.info("[CTI] 오래된 명령 버림: user={}, command={}", username, command);
            return "";
        }
        return command;
    }

    public boolean isAgentOnline(String username) {
        Long seen = agentSeen.get(username);
        return seen != null && (System.currentTimeMillis() - seen) < AGENT_TTL_MS;
    }

    private void sendAgentStatus(String username, boolean online) {
        List<SseEmitter> targets = emitters.get(username);
        if (targets == null) return;
        for (SseEmitter emitter : targets) {
            try {
                emitter.send(SseEmitter.event().name("agent-status").data(Map.of("online", online)));
            } catch (Exception e) {
                remove(username, emitter);
            }
        }
    }

    /** 현재 그 사용자가 열어둔 화면 수 */
    public int count(String username) {
        List<SseEmitter> list = emitters.get(username);
        return list == null ? 0 : list.size();
    }

    /**
     * 끊긴 연결 정리 겸 유지용 신호.
     * 중간 프록시가 조용한 연결을 끊어버리는 것을 막는다.
     */
    @Scheduled(fixedDelay = 25000)
    public void heartbeat() {
        // 신호가 끊긴 에이전트를 내려놓고 화면의 표시도 꺼 준다.
        // (에이전트가 그냥 죽으면 agentOffline 을 못 보내므로 여기서 걸러진다)
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : new ArrayList<>(agentSeen.entrySet())) {
            if (now - e.getValue() >= AGENT_TTL_MS) {
                agentSeen.remove(e.getKey());
                log.info("[CTI] 에이전트 신호 끊김: user={}", e.getKey());
                sendAgentStatus(e.getKey(), false);
            }
        }

        if (emitters.isEmpty()) return;

        emitters.forEach((username, list) -> {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception e) {
                    remove(username, emitter);
                }
            }
        });
    }

    private void remove(String username, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(username);
        if (list == null) return;
        list.remove(emitter);
        if (list.isEmpty()) emitters.remove(username);
    }

    /** 운영 확인용 — 사용자별 열린 화면 수 */
    public Map<String, Integer> snapshot() {
        Map<String, Integer> out = new HashMap<>();
        emitters.forEach((k, v) -> out.put(k, v.size()));
        return out;
    }
}
