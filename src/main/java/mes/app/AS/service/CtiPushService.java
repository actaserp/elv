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

    /** 사업체(db_key) → 그 사업체에서 열어둔 화면들 */
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /** 사업체(db_key) → 그 사업체 에이전트가 마지막으로 신호를 보낸 시각 */
    private final Map<String, Long> agentSeen = new ConcurrentHashMap<>();

    /**
     * 사업체(db_key) → 에이전트가 가져갈 명령. 먼저 가져간 PC 가 실행하고 명령은 없어진다.
     * 브라우저는 PC 의 프로그램을 직접 부를 수 없어서, 화면이 여기에 적어두면
     * 에이전트가 주기적으로 가져간다. KT 비밀번호는 PC 에만 있고 여기를 지나가지 않는다.
     */
    private final Map<String, String> commands = new ConcurrentHashMap<>();

    /** 화면 한 개를 구독에 등록한다. 탭을 여러 개 열면 그 수만큼 쌓인다. */
    public SseEmitter subscribe(String site) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);

        emitters.computeIfAbsent(site, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(site, emitter));
        emitter.onTimeout(()    -> remove(site, emitter));
        emitter.onError(e       -> remove(site, emitter));

        // 첫 이벤트를 즉시 보내야 프록시가 연결을 확정한다.
        // 지금 에이전트가 붙어 있는지도 같이 알려줘야 화면이 처음부터 올바른 상태를 그린다.
        try {
            emitter.send(SseEmitter.event().name("ready").data(Map.of("ok", true)));
            emitter.send(SseEmitter.event().name("agent-status")
                    .data(Map.of("online", isAgentOnline(site))));
        } catch (IOException e) {
            remove(site, emitter);
        }

        log.debug("[CTI] 구독 시작: site={}, 화면수={}", site, count(site));
        return emitter;
    }

    /**
     * 지정한 사용자에게만 전화 수신 알림을 보낸다.
     * @return 실제로 전달된 화면 수. 0이면 그 사용자가 화면을 열어두지 않은 것이다.
     */
    public int pushIncomingCall(String site, Map<String, Object> payload) {
        List<SseEmitter> targets = emitters.get(site);
        if (targets == null || targets.isEmpty()) {
            log.info("[CTI] 수신 알림 대상 없음: site={}, caller={}", site, payload.get("caller"));
            return 0;
        }

        int sent = 0;
        for (SseEmitter emitter : targets) {
            try {
                emitter.send(SseEmitter.event().name("incoming-call").data(payload));
                sent++;
            } catch (Exception e) {
                remove(site, emitter);
            }
        }
        log.info("[CTI] 수신 알림 전달: site={}, caller={}, 화면수={}", site, payload.get("caller"), sent);
        return sent;
    }

    // ── 에이전트 생사 ────────────────────────────────────────
    // 설정으로 "이 사업체는 CTI 를 쓴다"고 선언하는 대신, 에이전트가 실제로 붙어 있는지로 판단한다.
    // 설정은 거짓말을 할 수 있지만(에이전트가 죽어도 켜져 있다고 나온다) 이 값은 그렇지 않다.

    /** 에이전트가 살아 있다고 알려왔다. 상태가 바뀌었으면 화면에 알린다. */
    public void agentOnline(String site) {
        boolean was = isAgentOnline(site);
        agentSeen.put(site, System.currentTimeMillis());
        if (!was) {
            log.info("[CTI] 에이전트 연결: site={}", site);
            sendAgentStatus(site, true);
        }
    }

    /** 에이전트가 내려갔다 */
    public void agentOffline(String site) {
        if (agentSeen.remove(site) != null) {
            log.info("[CTI] 에이전트 해제: site={}", site);
            sendAgentStatus(site, false);

            // 가져갈 사람이 사라졌다. 기다리던 화면을 그대로 두면 시간초과까지 멈춰 있다.
            failPendingCalls(site, "전화 프로그램이 종료됐습니다.");
        }
    }

    // ── 화면 → 에이전트 명령 ─────────────────────────────────

    /** 화면이 [연결]/[해제] 를 눌렀다. 에이전트가 가져갈 때까지 들고 있는다. */
    public void queueCommand(String site, String command) {
        commands.put(site, System.currentTimeMillis() + "|" + command);
        log.info("[CTI] 명령 대기: site={}, command={}", site, command);
    }

    // ── 문자 보내기 ──────────────────────────────────────────
    //
    // 문자도 KT COM 함수라 서버가 직접 못 보낸다. 전화를 받는 그 PC 의 에이전트가
    // SetRecvPhone → SendSMS 를 호출해야 해서, 연결/해제와 같은 명령 큐에 실어 보낸다.
    // 내용은 글자가 길고 쉼표·따옴표가 섞일 수 있어 명령 문자열에 끼워 넣지 않고 따로 둔다.
    private final Map<String, Map<String, Object>> smsJobs = new ConcurrentHashMap<>();

    // ── KT 기능 호출 (범용) ──────────────────────────────────
    //
    // 문자는 '보내고 끝' 이라 결과를 그 사업체 화면 전체에 뿌려도 됐다.
    // 주소록·팩스 조회는 다르다 — 누른 사람이 답을 기다리고 있고, 두 사람이
    // 동시에 눌렀을 때 결과가 뒤바뀌면 안 된다. 그래서 건마다 번호를 붙이고,
    // 그 번호로 부른 쪽에게만 돌려준다.
    //
    // 큐로 둔 이유: 슬롯 하나면 두 번째 요청이 첫 번째를 덮어쓴다.

    /** 사업체(db_key) → 에이전트가 가져갈 호출 대기열 */
    private final Map<String, java.util.Deque<Map<String, Object>>> callJobs = new ConcurrentHashMap<>();

    /** 호출번호 → 답을 기다리는 화면. 에이전트가 결과를 올리면 깨운다. */
    private final Map<String, java.util.concurrent.CompletableFuture<Map<String, Object>>> waiting
            = new ConcurrentHashMap<>();

    /** 화면이 부른 KT 기능을 대기열에 넣는다. 돌려주는 번호로 결과를 받는다. */
    public String queueCall(String site, String fn, String args) {
        String jobId = java.util.UUID.randomUUID().toString().substring(0, 8);

        Map<String, Object> job = new java.util.LinkedHashMap<>();
        job.put("id",   jobId);
        job.put("fn",   fn);
        job.put("args", args == null ? "" : args);

        callJobs.computeIfAbsent(site, k -> new java.util.concurrent.ConcurrentLinkedDeque<>()).add(job);
        waiting.put(jobId, new java.util.concurrent.CompletableFuture<>());

        log.info("[CTI] 기능 호출 대기: site={}, fn={}, id={}", site, fn, jobId);
        return jobId;
    }

    /** 에이전트가 하나 가져간다. 없으면 null. */
    public Map<String, Object> takeCall(String site) {
        var q = callJobs.get(site);
        return q == null ? null : q.poll();
    }

    /**
     * 결과가 올 때까지 기다린다. 에이전트가 꺼져 있거나 느리면 하염없이 기다리게 되므로
     * 시간을 끊고 사실대로 알린다 — 화면이 멈춘 채 두는 것보다 낫다.
     */
    public Map<String, Object> awaitCall(String jobId, long timeoutMs) {
        var f = waiting.get(jobId);
        if (f == null) return null;

        try {
            return f.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("[CTI] 기능 호출 시간초과: id={}", jobId);
            return null;
        } catch (Exception e) {
            log.warn("[CTI] 기능 호출 대기 중 오류: id={}, {}", jobId, e.getMessage());
            return null;
        } finally {
            waiting.remove(jobId);
        }
    }

    /** 에이전트가 결과를 올렸다. 기다리던 화면을 깨운다. */
    public void completeCall(String jobId, boolean ok, String message, String data) {
        var f = waiting.get(jobId);
        if (f == null) {
            // 화면이 이미 포기했거나(시간초과) 중복으로 올라온 결과다. 버린다.
            log.info("[CTI] 기다리지 않는 결과: id={}", jobId);
            return;
        }

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("ok",      ok);
        payload.put("message", message == null ? "" : message);
        payload.put("data",    data    == null ? "" : data);

        f.complete(payload);
    }

    /** 에이전트가 끊기면 기다리던 것들을 모두 깨운다 — 그대로 두면 화면이 시간초과까지 멈춰 있다. */
    public void failPendingCalls(String site, String reason) {
        var q = callJobs.remove(site);
        if (q == null) return;

        for (Map<String, Object> job : q) {
            completeCall((String) job.get("id"), false, reason, "");
        }
    }

    public void queueSms(String site, List<String> recipients, String message) {
        Map<String, Object> job = new java.util.LinkedHashMap<>();
        job.put("to", recipients);
        job.put("message", message);
        smsJobs.put(site, job);

        queueCommand(site, "sms");
        log.info("[CTI] 문자 대기: site={}, 수신={}명, 길이={}자", site, recipients.size(), message.length());
    }

    /** 에이전트가 'sms' 명령을 가져갈 때 같이 집어간다. 한 번 가져가면 지운다. */
    public Map<String, Object> takeSms(String site) {
        return smsJobs.remove(site);
    }

    /** 발송 결과를 화면으로 올린다. 보냈는지 못 보냈는지 모르면 같은 문자를 또 보내게 된다. */
    public void pushSmsResult(String site, boolean ok, String message) {
        List<SseEmitter> targets = emitters.get(site);
        if (targets == null) return;

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("ok", ok);
        payload.put("message", message);

        for (SseEmitter emitter : targets) {
            try {
                emitter.send(SseEmitter.event().name("sms-result").data(payload));
            } catch (Exception e) {
                remove(site, emitter);
            }
        }
        log.info("[CTI] 문자 결과: site={}, ok={}, {}", site, ok, message);
    }

    /**
     * 에이전트가 가져간다. 한 번 가져가면 지운다. 없으면 빈 문자열.
     * 오래된 명령은 버린다 — 퇴근 전에 누른 [연결] 이 다음 날 부팅 때 되살아나면
     * 그 사이 다른 사람이 쓰고 있던 계정을 밀어낼 수 있다.
     */
    public String takeCommand(String site) {
        String raw = commands.remove(site);
        if (raw == null) return "";

        int i = raw.indexOf('|');
        long at = Long.parseLong(raw.substring(0, i));
        String command = raw.substring(i + 1);

        if (System.currentTimeMillis() - at > COMMAND_TTL_MS) {
            log.info("[CTI] 오래된 명령 버림: site={}, command={}", site, command);
            return "";
        }
        return command;
    }

    public boolean isAgentOnline(String site) {
        Long seen = agentSeen.get(site);
        return seen != null && (System.currentTimeMillis() - seen) < AGENT_TTL_MS;
    }

    private void sendAgentStatus(String site, boolean online) {
        List<SseEmitter> targets = emitters.get(site);
        if (targets == null) return;
        for (SseEmitter emitter : targets) {
            try {
                emitter.send(SseEmitter.event().name("agent-status").data(Map.of("online", online)));
            } catch (Exception e) {
                remove(site, emitter);
            }
        }
    }

    /** 현재 그 사용자가 열어둔 화면 수 */
    public int count(String site) {
        List<SseEmitter> list = emitters.get(site);
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
                log.info("[CTI] 에이전트 신호 끊김: site={}", e.getKey());
                sendAgentStatus(e.getKey(), false);
            }
        }

        if (emitters.isEmpty()) return;

        emitters.forEach((site, list) -> {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception e) {
                    remove(site, emitter);
                }
            }
        });
    }

    private void remove(String site, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(site);
        if (list == null) return;
        list.remove(emitter);
        if (list.isEmpty()) emitters.remove(site);
    }

    /** 운영 확인용 — 사용자별 열린 화면 수 */
    public Map<String, Integer> snapshot() {
        Map<String, Integer> out = new HashMap<>();
        emitters.forEach((k, v) -> out.put(k, v.size()));
        return out;
    }
}
