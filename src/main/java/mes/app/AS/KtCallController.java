package mes.app.AS;

import lombok.RequiredArgsConstructor;
import mes.app.AS.service.KtCallService;
import mes.app.annotation.ApiProduct;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * KT 통화매니저 API 연동 컨트롤러
 * 프론트에서 로그인/로그아웃 요청을 받아 KtCallService에 위임
 */
@ApiProduct(ApiProduct.P01)
@RestController
@RequestMapping("/api/kt-call")
@RequiredArgsConstructor
public class KtCallController {

    private final KtCallService ktCallService;

    /**
     * KT API 로그인
     * POST /api/kt-call/login
     * body: { "userId": "...", "password": "..." }
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestParam String userId,
            @RequestParam String password,
            Authentication auth) {

        if (userId == null || userId.isBlank() ||
            password == null || password.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "ID/PW를 입력해주세요."));
        }

        Map<String, Object> result = ktCallService.login(userId, password);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(
            @RequestParam String userId) {

        ktCallService.logout(userId);
        return ResponseEntity.ok(Map.of("success", true, "message", "로그아웃 되었습니다."));
    }

    /**
     * 로그인 상태 확인
     * GET /api/kt-call/status?userId=...
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(@RequestParam String userId) {
        boolean loggedIn = ktCallService.isLoggedIn(userId);
        return ResponseEntity.ok(Map.of("loggedIn", loggedIn));
    }
}
