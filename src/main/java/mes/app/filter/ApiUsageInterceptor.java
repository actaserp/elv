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
     * <p>파라미터(쿼리스트링/폼) → 헤더 → 세션 순으로 시도한다.
     * 기존 필터는 {@code request.getParameter()} 만 사용해
     * <b>POST JSON 바디로만 사업장을 보내는 요청이 집계에서 누락</b>됐다.
     */
    private String resolveSpjangcd(HttpServletRequest request) {
        String spjangcd = request.getParameter("spjangcd");
        if (spjangcd != null && !spjangcd.isBlank()) {
            return spjangcd.trim();
        }

        spjangcd = request.getHeader("X-Spjangcd");
        if (spjangcd != null && !spjangcd.isBlank()) {
            return spjangcd.trim();
        }

        if (request.getSession(false) != null) {
            Object sessionVal = request.getSession(false).getAttribute("spjangcd");
            if (sessionVal != null && !String.valueOf(sessionVal).isBlank()) {
                return String.valueOf(sessionVal).trim();
            }
        }
        return null;
    }
}
