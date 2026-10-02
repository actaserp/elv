package mes.app.AS.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
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

    /** elv 로그인 계정(username) → 그 사용자가 열어둔 화면들 */
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /** 화면 한 개를 구독에 등록한다. 탭을 여러 개 열면 그 수만큼 쌓인다. */
    public SseEmitter subscribe(String username) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);

        emitters.computeIfAbsent(username, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(username, emitter));
        emitter.onTimeout(()    -> remove(username, emitter));
        emitter.onError(e       -> remove(username, emitter));

        // 첫 이벤트를 즉시 보내야 프록시가 연결을 확정한다
        try {
            emitter.send(SseEmitter.event().name("ready").data(Map.of("ok", true)));
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
