package mes.sse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.domain.entity.User;
import mes.sse.Transaction.SseClient;
import mes.sse.Transaction.SseSubject;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/sse")
public class SseController {

    private final SseSubject subject;

    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@RequestParam String spjangcd,
                                Authentication authentication) {

        User user = (User) authentication.getPrincipal();
        String userId = user.getUsername();

        // ✅ timeout 0L = 무제한 (Nginx proxy_read_timeout으로 제어)
        SseEmitter emitter = new SseEmitter(0L);
        SseClient client = new SseClient(userId, emitter);

        subject.addObserver(spjangcd, client);

        // ✅ 30초마다 heartbeat 전송 (Nginx가 연결을 끊지 않도록)
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event()
                        .name("HEARTBEAT")
                        .data("ping"));
            } catch (Exception e) {
                log.debug("SSE heartbeat 전송 실패 - userId: {}, 연결 종료", userId);
                subject.removeObserver(spjangcd, client);
                scheduler.shutdown();
            }
        }, 30, 30, TimeUnit.SECONDS);

        // ✅ emitter 종료 시 scheduler + observer 모두 정리
        emitter.onCompletion(() -> {
            subject.removeObserver(spjangcd, client);
            scheduler.shutdown();
        });
        emitter.onTimeout(() -> {
            subject.removeObserver(spjangcd, client);
            scheduler.shutdown();
        });
        emitter.onError(e -> {
            subject.removeObserver(spjangcd, client);
            scheduler.shutdown();
        });

        // ✅ 최초 연결 확인 이벤트
        try {
            emitter.send(SseEmitter.event()
                    .name("CONNECTED")
                    .data("connected"));
        } catch (IOException e) {
            emitter.completeWithError(e);
            scheduler.shutdown();
        }

        return emitter;
    }
}
