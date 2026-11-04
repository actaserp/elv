package mes.app.common;

import lombok.extern.slf4j.Slf4j;
import mes.app.system.service.TenantDbService;
import mes.domain.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 사업체별 기능 사용여부를 모든 화면 모델에 내려준다.
 *
 * 화면마다 컨트롤러가 갈려 있어서(웹은 {@code GuiController}, 모바일 메뉴는
 * {@code MobileController}, 출퇴근등록은 {@code HomeController}) 한 곳에 모았다.
 * 화면이 늘어나도 여기만 보면 된다.
 */
@Slf4j
@ControllerAdvice
public class TenantFeatureModelAdvice {

    @Autowired
    TenantDbService tenantDbService;

    /**
     * 중간보고 사용여부 — {@code tb_tenant_db.use_mid_report}.
     * 화면에서 {@code ${use_mid_report}} 로 쓴다. 설정이 없으면 true(기존 동작).
     */
    @ModelAttribute("use_mid_report")
    public boolean useMidReport() {
        try {
            String dbKey = null;

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof User user) {
                dbKey = user.getDbKey();
            }
            if (dbKey == null) dbKey = TenantContext.getDbKey();

            return tenantDbService.isMidReportEnabled(dbKey);
        } catch (Exception e) {
            // 로그인 전 화면 등 테넌트를 알 수 없는 요청 — 기존 동작을 유지한다
            log.debug("중간보고 사용여부를 판단할 수 없어 사용으로 둡니다.", e);
            return true;
        }
    }
}
