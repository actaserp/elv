package mes.app.AS;

import lombok.extern.slf4j.Slf4j;
import mes.app.AS.service.DailyManageService;
import mes.app.common.TenantUserService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/AS/daily_manage")
public class DailyManageController {

    @Autowired
    DailyManageService dailyManageService;

    @Autowired
    TenantUserService tenantUserService;

    // 로그인 사용자가 사용자(User) 그룹이면 본인 perid(p 제거) 반환, 아니면 null
    private String getOwnPeridIfUserGroup(User user) {
        try {
            String groupCode = user.getUserProfile().getUserGroup().getCode();
            if (!"User".equals(groupCode)) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        Map<String, Object> userInfo = tenantUserService.getUserInfo(user.getUsername());
        if (userInfo == null || userInfo.get("perid") == null) {
            return null;
        }
        return ((String) userInfo.get("perid")).replaceFirst("^p", "");
    }

    // ── 헤드 목록 조회 (TB_E037 기준) ────────────────────────
    @GetMapping("/read/head")
    public AjaxResult readHead(
            @RequestParam(value = "year")                       String year,
            @RequestParam(value = "month")                      String month,
            @RequestParam(value = "pernm",    required = false) String pernm,
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();

        // 사용자(User) 그룹이면 본인 작성건만 조회, 그 외(관리자 등)는 전체
        String ownPerid = getOwnPeridIfUserGroup(user);

        result.data = dailyManageService.getHeadList(year, month, pernm, spjangcd, ownPerid);
        return result;
    }

    // ── 상세 목록 조회 (TB_E038 기준) ────────────────────────
    @GetMapping("/read/detail")
    public AjaxResult readDetail(
            @RequestParam(value = "custcd")   String custcd,
            @RequestParam(value = "spjangcd") String spjangcd,
            @RequestParam(value = "rptdate")  String rptdate,
            @RequestParam(value = "perid")    String perid,
            HttpServletRequest request) {

        AjaxResult result = new AjaxResult();
        result.data = dailyManageService.getDetailList(custcd, spjangcd, rptdate, perid);
        return result;
    }

    // ── 부서 목록 조회 ───────────────────────────────────────
    @GetMapping("/read/dept_list")
    public AjaxResult readDeptList(
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            HttpServletRequest request) {
        AjaxResult result = new AjaxResult();
        result.data = dailyManageService.getDeptList(spjangcd);
        return result;
    }

    // ── 부서별 업무보고 조회 ──────────────────────────────────
    @GetMapping("/read/dept_report")
    public AjaxResult readDeptReport(
            @RequestParam(value = "rptdate")                    String rptdate,
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            @RequestParam(value = "divicd",   required = false) String divicd,
            HttpServletRequest request) {
        AjaxResult result = new AjaxResult();
        result.data = dailyManageService.getDeptReport(rptdate, spjangcd, divicd);
        return result;
    }

    // ── 업무일지 삭제 ─────────────────────────────────────────
    @PostMapping("/delete")
    public AjaxResult delete(
            @RequestParam(value = "custcd")   String custcd,
            @RequestParam(value = "spjangcd") String spjangcd,
            @RequestParam(value = "rptdate")  String rptdate,
            @RequestParam(value = "perid")    String perid,
            @RequestParam(value = "rptnum")   String rptnum,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();

        // 사용자(User) 그룹은 본인이 작성한 업무일지만 삭제 가능
        String ownPerid = getOwnPeridIfUserGroup(user);
        if (ownPerid != null && !ownPerid.equals(perid)) {
            result.success = false;
            result.message = "본인이 작성한 업무일지만 삭제할 수 있습니다.";
            return result;
        }

        try {
            dailyManageService.deleteDailyReport(
                    custcd, spjangcd, rptdate, perid, rptnum,
                    user.getDbKey()
            );
            result.success = true;
            result.message = "삭제되었습니다.";
        } catch (Exception e) {
            log.error("업무일지 삭제 오류", e);
            result.success = false;
            result.message = "삭제 중 오류가 발생하였습니다.";
        }

        return result;
    }
}
