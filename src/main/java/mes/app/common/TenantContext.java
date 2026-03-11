package mes.app.common;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpSession;

public class TenantContext {

    /** 사업장 코드 – SQL WHERE spjangcd 필터용 (테넌트 DB 내부 코드) */
    private static final ThreadLocal<String> currentTenant = new ThreadLocal<>();

    /** DB 라우팅 키 – 메인 DB tb_xa012.spjangcd (어떤 물리 DB로 붙을지 결정) */
    private static final ThreadLocal<String> currentDbKey = new ThreadLocal<>();

    /** 메인 DB 강제 라우팅 플래그 – 시스템 테이블 쿼리 시 true */
    private static final ThreadLocal<Boolean> forceMainDb = new ThreadLocal<>();

    // ── spjangcd (SQL 필터) ──────────────────────────────────────────────────

    public static void set(String tenantId) {
        currentTenant.set(tenantId);
    }

    public static String get() {
        String tenantId = currentTenant.get();

        if (tenantId == null) {
            try {
                ServletRequestAttributes attr = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                if (attr != null) {
                    HttpSession session = attr.getRequest().getSession(false);
                    if (session != null) {
                        tenantId = (String) session.getAttribute("spjangcd");
                        set(tenantId);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return tenantId;
    }

    // ── dbKey (DB 라우팅) ────────────────────────────────────────────────────

    public static void setDbKey(String dbKey) {
        currentDbKey.set(dbKey);
    }

    public static String getDbKey() {
        String dbKey = currentDbKey.get();

        if (dbKey == null) {
            try {
                ServletRequestAttributes attr = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                if (attr != null) {
                    HttpSession session = attr.getRequest().getSession(false);
                    if (session != null) {
                        dbKey = (String) session.getAttribute("db_key");
                        setDbKey(dbKey);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return dbKey;
    }

    // ── forceMainDb (시스템 테이블 라우팅) ───────────────────────────────────

    public static void setForceMainDb(boolean value) {
        if (value) forceMainDb.set(true);
        else forceMainDb.remove();
    }

    public static boolean isForceMainDb() {
        return Boolean.TRUE.equals(forceMainDb.get());
    }

    // ── clear ────────────────────────────────────────────────────────────────

    public static void clear() {
        currentTenant.remove();
        currentDbKey.remove();
        forceMainDb.remove();
    }
}