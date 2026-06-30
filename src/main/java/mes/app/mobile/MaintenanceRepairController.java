package mes.app.mobile;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantUserService;
import mes.app.mobile.Service.MaintenanceRepairService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

@Slf4j
@RestController
@Transactional
@RequestMapping("/api/maintenance_repair")
public class MaintenanceRepairController {

    @Autowired
    MaintenanceRepairService maintenanceRepairService;

    @Autowired
    TenantUserService tenantUserService;

    // ── 사용자 정보 조회 ───────────────────────────────────────
    @GetMapping("/read_userInfo")
    public AjaxResult getUserInfo(HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        result.data = maintenanceRepairService.getUserInfo(user.getUsername());
        return result;
    }

    // ── 고장접수 목록 조회 (TB_E401) ─────────────────────────
    @GetMapping("/read_repair_list")
    public AjaxResult getRepairList(
            @RequestParam(value = "fromDate", required = false) String fromDate,
            @RequestParam(value = "toDate",   required = false) String toDate,
            @RequestParam(value = "actnm",    required = false) String actnm,
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            HttpServletRequest request, Authentication auth) {

        AjaxResult result = new AjaxResult();
        result.data = maintenanceRepairService.getRepairList(fromDate, toDate, actnm, spjangcd);
        return result;
    }

    // ── 고장처리결과 목록 조회 (TB_E411) ─────────────────────
    @GetMapping("/read_comp")
    public AjaxResult getCompList(
            @RequestParam(value = "fromDate",  required = false) String fromDate,
            @RequestParam(value = "toDate",    required = false) String toDate,
            @RequestParam(value = "actnm",     required = false) String actnm,
            @RequestParam(value = "resultck",  required = false) String resultck,
            @RequestParam(value = "spjangcd",  required = false) String spjangcd,
            HttpServletRequest request, Authentication auth) {

        AjaxResult result = new AjaxResult();
        result.data = maintenanceRepairService.getCompList(fromDate, toDate, actnm, resultck, spjangcd);
        return result;
    }

    // ── 현장 목록 조회 (TB_E601) ─────────────────────────────
    @GetMapping("/read_site")
    public AjaxResult getSiteList(
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            @RequestParam(value = "keyword",  required = false) String keyword,
            HttpServletRequest request, Authentication auth) {

        AjaxResult result = new AjaxResult();
        result.data = maintenanceRepairService.getSiteList(spjangcd, keyword);
        return result;
    }

    // ── 호기 목록 조회 (TB_E611) ─────────────────────────────
    @GetMapping("/read_equp")
    public AjaxResult getEqupList(
            @RequestParam(value = "actcd",    required = false) String actcd,
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            HttpServletRequest request, Authentication auth) {

        AjaxResult result = new AjaxResult();
        result.data = maintenanceRepairService.getEqupList(spjangcd, actcd);
        return result;
    }

    // ── 고장부위 조회 (TB_E013) ──────────────────────────────
    @GetMapping("/read_greginm")
    public AjaxResult getGreginmList(
            @RequestParam(value = "keyword", required = false, defaultValue = "") String keyword,
            Authentication auth) {
        AjaxResult result = new AjaxResult();
        result.data = maintenanceRepairService.getGreginmList(keyword);
        return result;
    }

    // ── 고장부위상세 조회 (TB_E014, gregicd 조건) ─────────────
    @GetMapping("/read_reginm")
    public AjaxResult getReginmList(
            @RequestParam(value = "gregicd") String gregicd,
            @RequestParam(value = "keyword", required = false, defaultValue = "") String keyword,
            Authentication auth) {
        AjaxResult result = new AjaxResult();
        result.data = maintenanceRepairService.getReginmList(gregicd, keyword);
        return result;
    }

    // ── 고장요인 조회 (TB_E011) ──────────────────────────────
    @GetMapping("/read_remonm")
    public AjaxResult getRemonmList(
            @RequestParam(value = "keyword", required = false, defaultValue = "") String keyword,
            Authentication auth) {
        AjaxResult result = new AjaxResult();
        result.data = maintenanceRepairService.getRemonmList(keyword);
        return result;
    }

    // ── 고장원인 조회 (TB_E019) ──────────────────────────────
    @GetMapping("/read_facnm")
    public AjaxResult getFacnmList(
            @RequestParam(value = "keyword", required = false, defaultValue = "") String keyword,
            Authentication auth) {
        AjaxResult result = new AjaxResult();
        result.data = maintenanceRepairService.getFacnmList(keyword);
        return result;
    }

    // ── 처리내용 조회 (TB_E012) ──────────────────────────────
    @GetMapping("/read_resunm")
    public AjaxResult getResunmList(
            @RequestParam(value = "keyword", required = false, defaultValue = "") String keyword,
            Authentication auth) {
        AjaxResult result = new AjaxResult();
        result.data = maintenanceRepairService.getResunmList(keyword);
        return result;
    }

    // ── 처리결과 조회 (TB_E015) ──────────────────────────────
    @GetMapping("/read_resultnm")
    public AjaxResult getResultnmList(
            @RequestParam(value = "keyword", required = false, defaultValue = "") String keyword,
            Authentication auth) {
        AjaxResult result = new AjaxResult();
        result.data = maintenanceRepairService.getResultnmList(keyword);
        return result;
    }

    // ── 고장처리결과 등록 (TB_E411 INSERT) ───────────────────
    @PostMapping("/save_comp")
    public AjaxResult saveComp(
            @RequestParam(value = "compdate",   required = false) String compdate,
            @RequestParam(value = "comptime",   required = false) String comptime,
            @RequestParam(value = "recedate",   required = false) String recedate,
            @RequestParam(value = "recenum",    required = false) String recenum,
            @RequestParam(value = "recetime",   required = false) String recetime,
            @RequestParam(value = "arrivdate",  required = false) String arrivdate,
            @RequestParam(value = "arrivtime",  required = false) String arrivtime,
            @RequestParam(value = "actcd",      required = false) String actcd,
            @RequestParam(value = "actnm",      required = false) String actnm,
            @RequestParam(value = "equpcd",     required = false) String equpcd,
            @RequestParam(value = "equpnm",     required = false) String equpnm,
            @RequestParam(value = "contremark", required = false) String contremark,
            @RequestParam(value = "gregicd",    required = false) String gregicd,
            @RequestParam(value = "remoremark", required = false) String remoremark,
            @RequestParam(value = "regicd",     required = false) String regicd,
            @RequestParam(value = "resuremark", required = false) String resuremark,
            @RequestParam(value = "remocd",     required = false) String remocd,
            @RequestParam(value = "resultcd",   required = false) String resultcd,
            @RequestParam(value = "faccd",      required = false) String faccd,
            @RequestParam(value = "customer",   required = false) String customer,
            @RequestParam(value = "resucd",     required = false) String resucd,
            @RequestParam(value = "remark",     required = false) String remark,
            @RequestParam(value = "actperid",   required = false) String actperid,
            @RequestParam(value = "filesvnm",   required = false) String filesvnm,
            @RequestParam(value = "filepath",   required = false) String filepath,
            HttpServletRequest request, Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();

        Map<String, Object> userInfo = tenantUserService.getUserInfo(user.getUsername());
        if (userInfo == null) {
            result.success = false;
            result.message = "사업체 DB에서 사용자 정보를 찾을 수 없습니다.";
            return result;
        }
        String custcd   = (String) userInfo.get("custcd");
        String spjangcd = (String) userInfo.get("spjangcd");
        String perid    = userInfo.get("perid") != null ? userInfo.get("perid").toString() : null;

        try {
            maintenanceRepairService.saveComp(
                    custcd, spjangcd, compdate, comptime,
                    recedate, recenum, recetime,
                    arrivdate, arrivtime,
                    actcd, actnm, equpcd, equpnm,
                    contremark, gregicd,
                    remoremark, regicd,
                    resuremark, remocd,
                    resultcd, faccd,
                    customer, resucd,
                    remark, actperid, perid,
                    filesvnm, filepath
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

    // ── 고장처리결과 수정 (TB_E411 UPDATE) ───────────────────
    @PostMapping("/update_comp")
    public AjaxResult updateComp(
            @RequestParam(value = "spjangcd")               String spjangcd,
            @RequestParam(value = "compdate")               String compdate,
            @RequestParam(value = "compnum")                String compnum,
            @RequestParam(value = "comptime",  required = false) String comptime,
            @RequestParam(value = "recedate",  required = false) String recedate,
            @RequestParam(value = "recetime",  required = false) String recetime,
            @RequestParam(value = "arrivdate", required = false) String arrivdate,
            @RequestParam(value = "arrivtime", required = false) String arrivtime,
            @RequestParam(value = "actcd",     required = false) String actcd,
            @RequestParam(value = "actnm",     required = false) String actnm,
            @RequestParam(value = "equpcd",    required = false) String equpcd,
            @RequestParam(value = "equpnm",    required = false) String equpnm,
            @RequestParam(value = "contremark",required = false) String contremark,
            @RequestParam(value = "gregicd",   required = false) String gregicd,
            @RequestParam(value = "remoremark",required = false) String remoremark,
            @RequestParam(value = "regicd",    required = false) String regicd,
            @RequestParam(value = "resuremark",required = false) String resuremark,
            @RequestParam(value = "remocd",    required = false) String remocd,
            @RequestParam(value = "resultcd",  required = false) String resultcd,
            @RequestParam(value = "faccd",     required = false) String faccd,
            @RequestParam(value = "customer",  required = false) String customer,
            @RequestParam(value = "resucd",    required = false) String resucd,
            @RequestParam(value = "remark",    required = false) String remark,
            @RequestParam(value = "actperid",  required = false) String actperid,
            HttpServletRequest request, Authentication auth) {

        AjaxResult result = new AjaxResult();
        try {
            maintenanceRepairService.updateComp(
                    spjangcd, compdate, compnum, comptime,
                    recedate, recetime, arrivdate, arrivtime,
                    actcd, actnm, equpcd, equpnm,
                    contremark, gregicd, remoremark, regicd,
                    resuremark, remocd, resultcd, faccd,
                    customer, resucd, remark, actperid);
            result.success = true;
            result.message = "수정되었습니다.";
        } catch (Exception e) {
            log.error("고장처리결과 수정 오류", e);
            result.success = false;
            result.message = "수정 중 오류가 발생하였습니다.";
        }
        return result;
    }

    // ── 고장처리결과 삭제 (TB_E411 DELETE) ───────────────────
    @PostMapping("/delete_comp")
    public AjaxResult deleteComp(
            @RequestParam(value = "spjangcd") String spjangcd,
            @RequestParam(value = "compdate") String compdate,
            @RequestParam(value = "compnum")  String compnum,
            HttpServletRequest request, Authentication auth) {

        AjaxResult result = new AjaxResult();
        try {
            maintenanceRepairService.deleteComp(spjangcd, compdate, compnum);
            result.success = true;
            result.message = "삭제되었습니다.";
        } catch (Exception e) {
            log.error("고장처리결과 삭제 오류", e);
            result.success = false;
            result.message = "삭제 중 오류가 발생하였습니다.";
        }
        return result;
    }
}
