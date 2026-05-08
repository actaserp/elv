package mes.app.mobile;

import lombok.extern.slf4j.Slf4j;
import mes.app.mobile.Service.MaintenanceCompService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@Transactional
@RequestMapping("/api/maintenance_comp")
public class MaintenanceCompController {

    @Autowired
    MaintenanceCompService maintenanceCompService;

    // ── 사용자 정보 조회 ───────────────────────────────────────
    @GetMapping("/read_userInfo")
    public AjaxResult getUserInfo(HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        result.data = maintenanceCompService.getUserInfo(user.getUsername());
        return result;
    }

    // ── 현장 목록 조회 (TB_E601) ──────────────────────────────
    @GetMapping("/read_site")
    public AjaxResult getSiteList(
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            @RequestParam(value = "keyword",  required = false) String keyword,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        result.data = maintenanceCompService.getSiteList(spjangcd, keyword);
        return result;
    }

    // ── 호기 목록 조회 (TB_E611) ──────────────────────────────
    @GetMapping("/read_equp")
    public AjaxResult getEqupList(
            @RequestParam(value = "actcd",    required = false) String actcd,
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        result.data = maintenanceCompService.getEqupList(spjangcd, actcd);
        return result;
    }

    // ── 고장처리결과 목록 조회 (TB_E411) ─────────────────────
    @GetMapping("/read_comp")
    public AjaxResult getCompList(
            @RequestParam(value = "fromDate", required = false) String fromDate,
            @RequestParam(value = "toDate",   required = false) String toDate,
            @RequestParam(value = "actnm",    required = false) String actnm,
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        result.data = maintenanceCompService.getCompList(fromDate, toDate, actnm, spjangcd);
        return result;
    }

    // ── 고장처리결과 등록 (TB_E411 INSERT) ───────────────────
    @PostMapping("/save_comp")
    public AjaxResult saveComp(
            @RequestParam(value = "spjangcd",   required = false) String spjangcd,
            @RequestParam(value = "compdate",   required = false) String compdate,
            @RequestParam(value = "comptime",   required = false) String comptime,
            @RequestParam(value = "recedate",   required = false) String recedate,
            @RequestParam(value = "recetime",   required = false) String recetime,
            @RequestParam(value = "arrivdate",  required = false) String arrivdate,
            @RequestParam(value = "arrivtime",  required = false) String arrivtime,
            @RequestParam(value = "actcd",      required = false) String actcd,
            @RequestParam(value = "actnm",      required = false) String actnm,
            @RequestParam(value = "equpcd",     required = false) String equpcd,
            @RequestParam(value = "equpnm",     required = false) String equpnm,
            @RequestParam(value = "contremark", required = false) String contremark,
            @RequestParam(value = "remoremark", required = false) String remoremark,
            @RequestParam(value = "resuremark", required = false) String resuremark,
            @RequestParam(value = "resultcd",   required = false) String resultcd,
            @RequestParam(value = "customer",   required = false) String customer,
            @RequestParam(value = "remark",     required = false) String remark,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        String perid = user.getUsername();

        try {
            maintenanceCompService.saveComp(
                    spjangcd, compdate, comptime,
                    recedate, recetime,
                    arrivdate, arrivtime,
                    actcd, actnm, equpcd, equpnm,
                    contremark, remoremark, resuremark,
                    resultcd, customer, remark, perid
            );
            result.success = true;
            result.message = "고장처리결과가 등록되었습니다.";
        } catch (Exception e) {
            log.error("고장처리결과 등록 오류", e);
            result.success = false;
            result.message = "고장처리결과 등록 중 오류가 발생하였습니다.";
        }
        return result;
    }
}
