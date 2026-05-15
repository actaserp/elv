package mes.app.AS.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KT 통화매니저 API 폴링 서비스
 *
 * KT REST API를 5초마다 폴링해서 새 수신전화를 감지하면
 * WebSocket(/topic/kt-call)으로 브라우저에 실시간 푸시합니다.
 *
 * KT API 엔드포인트: https://dev.fone.kt.com (또는 상용: https://fone.kt.com)
 * - 로그인:        POST /api/v1/login
 * - 수신내역 조회:  GET  /api/v1/calls?kind=3&start=0&count=20
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KtCallService {

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    // ── KT API 설정 (application.properties 또는 DB에서 관리 권장) ──
    private static final String KT_BASE_URL  = "https://fone.kt.com"; // 상용서버 (개발: https://dev.fone.kt.com)
    private static final String KT_AUTH_KEY  = "402920198688d2f63a3daa45ecf2fcb4a48d0922";

    // ── 세션 관리 ──
    // userId → { token, lastCallDbId }
    private final Map<String, String>  sessionTokenMap  = new ConcurrentHashMap<>();
    private final Map<String, String>  lastCallDbIdMap  = new ConcurrentHashMap<>();

    // ── 등록된 사용자 목록 (로그인 성공 후 추가) ──
    private final Map<String, String[]> userCredentials = new ConcurrentHashMap<>();
    // key: userId,  value: [userId, password]

    /**
     * 사용자 KT 로그인 요청 (프론트 → 컨트롤러 → 여기)
     * 로그인 성공 시 세션 토큰 저장 + 폴링 대상에 추가
     */
    public Map<String, Object> login(String userId, String password) {
        Map<String, Object> result = new HashMap<>();
        try {
            String url = KT_BASE_URL + "/api/v1/login";

            Map<String, String> body = new HashMap<>();
            body.put("authKey",  KT_AUTH_KEY);
            body.put("loginId",  userId);
            body.put("loginPwd", password);
            body.put("serverType", "1"); // 1=상용, 0=개발

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    url,
                    new HttpEntity<>(body, headers),
                    String.class
            );

            JsonNode json = objectMapper.readTree(response.getBody());
            int resultCode = json.path("result").asInt(500);

            if (resultCode == 200) {
                String token = json.path("token").asText("");
                sessionTokenMap.put(userId, token);
                userCredentials.put(userId, new String[]{userId, password});
                result.put("success", true);
                result.put("message", "KT 로그인 성공");
                log.info("[KtCall] 로그인 성공: userId={}", userId);
            } else {
                result.put("success", false);
                result.put("message", "KT 로그인 실패 (code=" + resultCode + ")");
            }
        } catch (Exception e) {
            log.error("[KtCall] 로그인 오류", e);
            result.put("success", false);
            result.put("message", "KT API 연결 오류: " + e.getMessage());
        }
        return result;
    }

    /**
     * 로그아웃
     */
    public void logout(String userId) {
        sessionTokenMap.remove(userId);
        userCredentials.remove(userId);
        lastCallDbIdMap.remove(userId);
        log.info("[KtCall] 로그아웃: userId={}", userId);
    }

    /**
     * 5초마다 로그인된 모든 사용자의 수신 전화 폴링
     * 새 수신전화 감지 시 WebSocket으로 브라우저에 푸시
     */
    @Scheduled(fixedDelay = 5000)
    public void pollIncomingCalls() {
        if (sessionTokenMap.isEmpty()) return;

        sessionTokenMap.forEach((userId, token) -> {
            try {
                String url = KT_BASE_URL + "/api/v1/calls?kind=3&start=0&count=10";

                HttpHeaders headers = new HttpHeaders();
                headers.set("Authorization", "Bearer " + token);
                headers.setContentType(MediaType.APPLICATION_JSON);

                ResponseEntity<String> response = restTemplate.exchange(
                        url, HttpMethod.GET,
                        new HttpEntity<>(headers),
                        String.class
                );

                JsonNode json = objectMapper.readTree(response.getBody());
                JsonNode calls = json.path("callList");

                if (calls.isArray() && calls.size() > 0) {
                    JsonNode latest = calls.get(0);
                    String dbId     = latest.path("dbId").asText("");
                    String caller   = latest.path("caller").asText(""); // 발신번호
                    String callee   = latest.path("callee").asText(""); // 수신번호
                    String callDate = latest.path("date").asText("");
                    String result   = latest.path("result").asText("");

                    // 이미 처리한 통화인지 확인
                    String lastDbId = lastCallDbIdMap.getOrDefault(userId, "");
                    if (!dbId.equals(lastDbId) && isNewCall(result)) {
                        lastCallDbIdMap.put(userId, dbId);

                        // 브라우저로 푸시할 데이터
                        Map<String, Object> pushData = new HashMap<>();
                        pushData.put("caller",   caller);
                        pushData.put("callee",   callee);
                        pushData.put("callDate", formatCallDate(callDate));
                        pushData.put("callTime", formatCallTime(callDate));
                        pushData.put("status",   translateStatus(result));
                        pushData.put("dbId",     dbId);

                        // WebSocket 브로드캐스트
                        // 특정 사용자에게만 보내려면 /topic/kt-call/{userId} 사용
                        messagingTemplate.convertAndSend("/topic/kt-call", pushData);
                        log.info("[KtCall] 새 수신전화 푸시: caller={}, callee={}", caller, callee);
                    }
                }
            } catch (Exception e) {
                log.warn("[KtCall] 폴링 오류 userId={}: {}", userId, e.getMessage());
                // 토큰 만료 시 재로그인 시도
                if (e.getMessage() != null && e.getMessage().contains("401")) {
                    reLogin(userId);
                }
            }
        });
    }

    /**
     * 수신 중인 통화인지 판단 (200=성공/통화중, 201=처리중)
     */
    private boolean isNewCall(String result) {
        return "200".equals(result) || "201".equals(result) || "700".equals(result) || "701".equals(result);
    }

    /**
     * 토큰 만료 시 자동 재로그인
     */
    private void reLogin(String userId) {
        String[] creds = userCredentials.get(userId);
        if (creds != null) {
            sessionTokenMap.remove(userId);
            login(creds[0], creds[1]);
        }
    }

    private String formatCallDate(String ktDate) {
        // KT 날짜 형식 "20260513143022" → "2026-05-13"
        if (ktDate == null || ktDate.length() < 8) return "";
        return ktDate.substring(0,4) + "-" + ktDate.substring(4,6) + "-" + ktDate.substring(6,8);
    }

    private String formatCallTime(String ktDate) {
        // KT 날짜 형식 "20260513143022" → "14:30"
        if (ktDate == null || ktDate.length() < 12) return "";
        return ktDate.substring(8,10) + ":" + ktDate.substring(10,12);
    }

    private String translateStatus(String code) {
        switch (code) {
            case "200": return "수신";
            case "201": return "처리중";
            case "202": case "203": return "부재중";
            case "401": return "결번";
            case "404": return "통화중";
            case "405": return "무응답";
            case "407": return "발신자 포기";
            case "408": return "착신자 포기";
            case "700": case "701": return "수신(모바일)";
            default:    return "알 수 없음";
        }
    }

    /**
     * 현재 로그인 상태 확인
     */
    public boolean isLoggedIn(String userId) {
        return sessionTokenMap.containsKey(userId);
    }
}
