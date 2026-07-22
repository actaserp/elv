package mes.app.common;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpSession;

public class TenantContext {

    /** 사업장 코드 – SQL WHERE spjangcd 필터용 (테넌트 DB 내부 코드) */
    private static final ThreadLocal<String> currentTenant = new ThreadLocal<>();

    /** DB 라우팅 키 – 어떤 물리 DB로 붙을지 결정 (RoutingDataSource 사용) */
    private static final ThreadLocal<String> currentDbKey = new ThreadLocal<>();

    // ── spjangcd (SQL 필터) ──────────────────────────────────────────────────

    public static void set(String tenantId) {
        currentTenant.set(tenantId);
    }

    public static String get() {
        // ★ 세션이 있으면 항상 세션 값을 우선 (ThreadLocal 잔재값에 의한 테넌트 오염 방지)
        //   - 서버 스레드 재사용 시 이전 사용자의 ThreadLocal 값이 남아
        //     다른 회사 사용자가 그 값을 쓰는 문제를 원천 차단
        try {
            ServletRequestAttributes attr = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attr != null) {
                HttpSession session = attr.getRequest().getSession(false);
                if (session != null) {
                    String sessionTenant = (String) session.getAttribute("spjangcd");
                    if (sessionTenant != null) {
                        currentTenant.set(sessionTenant);   // ThreadLocal 도 최신 세션 값으로 동기화
                        return sessionTenant;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // 세션이 없는 경우(배치/비웹 스레드 등)에만 ThreadLocal fallback
        return currentTenant.get();
    }

    // ── dbKey (DB 라우팅) ────────────────────────────────────────────────────

    public static void setDbKey(String dbKey) {
        currentDbKey.set(dbKey);
    }

    public static String getDbKey() {
        // ★ 세션이 있으면 항상 세션 값을 우선 (ThreadLocal 잔재값에 의한 DB 라우팅 오염 방지)
        //   - 이전 사용자의 dbKey 가 남아 다른 회사 DB로 붙는 문제를 원천 차단
        try {
            ServletRequestAttributes attr = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attr != null) {
                HttpSession session = attr.getRequest().getSession(false);
                if (session != null) {
                    String sessionDbKey = (String) session.getAttribute("db_key");
                    if (sessionDbKey != null) {
                        currentDbKey.set(sessionDbKey);   // ThreadLocal 도 최신 세션 값으로 동기화
                        return sessionDbKey;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // 세션이 없는 경우(배치/비웹 스레드 등)에만 ThreadLocal fallback
        return currentDbKey.get();
    }

    // ── clear ────────────────────────────────────────────────────────────────

    public static void clear() {
        currentTenant.remove();
        currentDbKey.remove();
    }
}
