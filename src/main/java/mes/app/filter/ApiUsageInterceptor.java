package mes.app.filter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.app.annotation.ApiProduct;
import mes.app.util.RedisService;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 상품별 API 호출 수 집계 인터셉터.
 *
 * <p>Redis 키: {@code MES:{사업장코드}:{상품코드}:{yyyyMMdd}}
 *
 * <p>기존 {@code ControllerExecutionTimeAspect}(서블릿 Filter)는 실행 시점상
 * "어느 컨트롤러로 갈 요청인지"를 알 수 없어 어노테이션을 읽지 못한다.
 * 그래서 집계는 HandlerInterceptor 로 옮겼다.
 *
 * <p>집계 조건 (모두 만족해야 카운트):
 * <ol>
 *   <li>핸들러가 컨트롤러 메서드일 것</li>
 *   <li>{@link ApiProduct} 가 메서드 또는 클래스에 부착돼 있을 것</li>
 *   <li>요청에서 사업장코드를 얻을 수 있을 것</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiUsageInterceptor implements HandlerInterceptor {

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String KEY_PREFIX = "MES";

    private final RedisService redisService;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        try {
            if (!(handler instanceof HandlerMethod handlerMethod)) {
                return true;   // 정적 리소스 등
            }

            String productCd = resolveProductCd(handlerMethod);
            if (productCd == null) {
                return true;   // 매핑 없는 API 는 집계 대상 아님
            }

            String spjangcd = resolveSpjangcd(request);
            if (spjangcd == null || spjangcd.isBlank()) {
                return true;
            }

            String key = KEY_PREFIX + ":" + spjangcd + ":" + productCd + ":"
                       + LocalDate.now().format(YMD);
            log.info("[ApiUsage] uri={} spjangcd={} product={} param_spjangcd={} session_dbkey={}",
                    request.getRequestURI(), spjangcd, productCd,
                    request.getParameter("spjangcd"),
                    request.getSession(false) != null ? request.getSession(false).getAttribute("db_key") : "NO_SESSION");
            redisService.incrementValue(key);

        } catch (Exception e) {
            // 집계 실패가 실제 요청을 막아서는 안 됨
            log.warn("[ApiUsage] 집계 실패 uri={} : {}", request.getRequestURI(), e.getMessage());
        }
        return true;
    }

    /** 메서드 우선, 없으면 클래스에서 상품코드를 찾는다. */
    private String resolveProductCd(HandlerMethod handlerMethod) {
        ApiProduct onMethod = handlerMethod.getMethodAnnotation(ApiProduct.class);
        if (onMethod != null) {
            return onMethod.value();
        }
        ApiProduct onClass = handlerMethod.getBeanType().getAnnotation(ApiProduct.class);
        return (onClass != null) ? onClass.value() : null;
    }

    /**
     * 사업장코드 취득.
     *
     * 세션의 db_key(실제 테넌트 사업장코드)만 사용한다.
     *
     * 파라미터의 spjangcd 는 SpjangSecurityInterceptor 보안 검증용으로
     * 항상 세션의 spjangcd(=ZZ)와 동일하게 전송되므로 집계에 사용하면 안 된다.
     * 헤더(X-Spjangcd)는 명시적으로 사업장을 지정하는 경우에만 fallback으로 사용.
     */
    private String resolveSpjangcd(HttpServletRequest request) {
        // 1. 세션 db_key 우선 — 실제 테넌트 사업장코드
        javax.servlet.http.HttpSession session = request.getSession(false);
        if (session != null) {
            Object dbKey = session.getAttribute("db_key");
            if (dbKey != null && !String.valueOf(dbKey).isBlank()) {
                return String.valueOf(dbKey).trim();
            }
        }

        // 2. 커스텀 헤더 (비세션 API 등 특수 케이스)
        String spjangcd = request.getHeader("X-Spjangcd");
        if (spjangcd != null && !spjangcd.isBlank()) {
            return spjangcd.trim();
        }

        // 3. 세션 spjangcd fallback (본사 계정 등 db_key 없는 경우)
        if (session != null) {
            Object sessionSpjangcd = session.getAttribute("spjangcd");
            if (sessionSpjangcd != null && !String.valueOf(sessionSpjangcd).isBlank()) {
                return String.valueOf(sessionSpjangcd).trim();
            }
        }

        return null;
    }
}
