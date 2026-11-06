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
 *
 * 여기서 내려주는 값은 '화면 표시'용이다. 서버 계산을 가르는 설정은 서비스가
 * TenantDbService 를 직접 읽어야 한다 — 연차 산정기준이 그런 경우로, 생성 결과를
 * 가르는 건 ClockYearlyService 이고 아래 annual_basis 는 화면 안내에만 쓴다.
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
            return tenantDbService.isMidReportEnabled(currentDbKey());
        } catch (Exception e) {
            // 로그인 전 화면 등 테넌트를 알 수 없는 요청 — 기존 동작을 유지한다
            log.debug("중간보고 사용여부를 판단할 수 없어 사용으로 둡니다.", e);
            return true;
        }
    }

    /**
     * 연차 산정기준 — {@code tb_tenant_db.annual_basis}. 'F' 회계연도 / 'E' 입사일.
     * 화면에서 {@code ${annual_basis}} 로 쓴다. 설정이 없으면 'F'(기존 동작).
     *
     * 설정은 본사 관리 화면에 있어서 고객사 담당자는 지금 어느 기준으로 돌아가는지
     * 알 수 없다. 연차 화면 상단에 적어 주려고 내려보낸다.
     */
    @ModelAttribute("annual_basis")
    public String annualBasis() {
        try {
            return tenantDbService.getAnnualBasis(currentDbKey());
        } catch (Exception e) {
            log.debug("연차 산정기준을 판단할 수 없어 회계연도로 둡니다.", e);
            return TenantDbService.ANNUAL_BASIS_FISCAL;
        }
    }

    /** 지금 요청이 어느 사업체인가(db_key). 로그인 전 화면이면 null 일 수 있다 */
    private String currentDbKey() {
        String dbKey = null;

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user) {
            dbKey = user.getDbKey();
        }
        if (dbKey == null) dbKey = TenantContext.getDbKey();

        return dbKey;
    }
}
