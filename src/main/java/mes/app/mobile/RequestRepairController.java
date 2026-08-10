package mes.app.mobile;

import lombok.extern.slf4j.Slf4j;
import mes.app.annotation.ApiProduct;
import mes.app.common.TenantUserService;
import mes.app.mobile.Service.RequestRepairService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

@Slf4j
@ApiProduct(ApiProduct.P01)
@RestController
@Transactional
@RequestMapping("/api/request_repair")
public class RequestRepairController {

    @Autowired
    RequestRepairService requestRepairService;

    @Autowired
    TenantUserService tenantUserService;

    // ── 사용자 정보 조회 ───────────────────────────────────────
    @GetMapping("/read_userInfo")
    public AjaxResult getUserInfo(HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        result.data = requestRepairService.getUserInfo(user.getUsername());
        return result;
    }

    // ── 고장접수 목록 조회 (TB_E401) ─────────────────────────
    @GetMapping("/read")
    public AjaxResult getRepairList(
            @RequestParam(value = "fromDate",  required = false) String fromDate,
            @RequestParam(value = "toDate",    required = false) String toDate,
            @RequestParam(value = "actnm",     required = false) String actnm,
            @RequestParam(value = "resultck",  required = false) String resultck,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        String username = user.getUsername();

        Map<String, Object> userInfo = tenantUserService.getUserInfo(username);
        if (userInfo == null) {
            result.success = false;
            result.message = "사용자 정보를 찾을 수 없습니다.";
            return result;
        }

        String spjangcd = (String) userInfo.get("spjangcd");
        // TB_E401.perid는 p 없는 형태 (ex: HY010405)
        String perid = ((String) userInfo.get("perid")).replaceFirst("^p", "");

        result.data = requestRepairService.getRepairList(fromDate, toDate, actnm, resultck, spjangcd, perid);
        return result;
    }

    // ── 현장 목록 조회 (TB_E601) ─────────────────────────────
    @GetMapping("/read_act")
    public AjaxResult getActList(
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        String spjangcd = tenantUserService.getSpjangcd(user.getUsername());
        result.data = requestRepairService.getActList(spjangcd);
        return result;
    }

    // ── 호기 목록 조회 (TB_E611) ─────────────────────────────
    @GetMapping("/read_equp")
    public AjaxResult getEqupList(
            @RequestParam(value = "actcd", required = false) String actcd,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        String spjangcd = tenantUserService.getSpjangcd(user.getUsername());
        result.data = requestRepairService.getEqupList(actcd, spjangcd);
        return result;
    }

    // ── 고장내용 목록 조회 (TB_E010) ────────────────────────
    @GetMapping("/read_contnm")
    public AjaxResult getContnmList(
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        String spjangcd = tenantUserService.getSpjangcd(user.getUsername());
        result.data = requestRepairService.getContnmList(spjangcd);
        return result;
    }

    // ── 고장접수 등록 (TB_E401 INSERT) ───────────────────────
    @PostMapping("/save")
    public AjaxResult saveRepair(
            @RequestParam(value = "recedate",  required = false) String recedate,
            @RequestParam(value = "recetime",  required = false) String recetime,
            @RequestParam(value = "hitchdate", required = false) String hitchdate,
            @RequestParam(value = "hitchhour", required = false) String hitchhour,
            @RequestParam(value = "actcd",     required = false) String actcd,
            @RequestParam(value = "actnm",     required = false) String actnm,
            @RequestParam(value = "equpcd",    required = false) String equpcd,
            @RequestParam(value = "equpnm",    required = false) String equpnm,
            @RequestParam(value = "contents",  required = false) String contents,
            @RequestParam(value = "contcd",    required = false) String contcd,
            @RequestParam(value = "remark",    required = false) String remark,
            @RequestParam(value = "perid",     required = false) String perid,
            @RequestParam(value = "bigo",      required = false) String bigo,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        String username = user.getUsername();

        Map<String, Object> userInfo = tenantUserService.getUserInfo(username);
        if (userInfo == null) {
            result.success = false;
            result.message = "사업체 DB에서 사용자 정보를 찾을 수 없습니다.";
            return result;
        }
        String spjangcd = (String) userInfo.get("spjangcd");
        String custcd   = (String) userInfo.get("custcd");

        try {
            // ★ perid = 화면에서 선택한 통보자, username = 로그인 사용자(접수자)
            requestRepairService.saveRepair(
                    custcd, spjangcd, recedate, recetime,
                    hitchdate, hitchhour,
                    actcd, actnm, equpcd, equpnm,
                    contcd, contents, remark, perid, bigo, username
            );
            result.success = true;
            result.message = "고장접수가 등록되었습니다.";
        } catch (Exception e) {
            log.error("고장접수 등록 오류", e);
            result.success = false;
            result.message = "고장접수 등록 중 오류가 발생하였습니다.";
        }

        return result;
    }

    // ── 고장접수 수정 (TB_E401 UPDATE) ───────────────────────
    @PostMapping("/update")
    public AjaxResult updateRepair(
            @RequestParam(value = "spjangcd")                    String spjangcd,
            @RequestParam(value = "recedate")                    String recedate,
            @RequestParam(value = "recenum")                     String recenum,
            @RequestParam(value = "recetime",  required = false) String recetime,
            @RequestParam(value = "hitchdate", required = false) String hitchdate,
            @RequestParam(value = "hitchhour", required = false) String hitchhour,
            @RequestParam(value = "actcd",     required = false) String actcd,
            @RequestParam(value = "actnm",     required = false) String actnm,
            @RequestParam(value = "equpcd",    required = false) String equpcd,
            @RequestParam(value = "equpnm",    required = false) String equpnm,
            @RequestParam(value = "contcd",    required = false) String contcd,
            @RequestParam(value = "contents",  required = false) String contents,
            @RequestParam(value = "remark",    required = false) String remark,
            @RequestParam(value = "perid",     required = false) String perid,
            HttpServletRequest request, Authentication auth) {

        AjaxResult result = new AjaxResult();
        try {
            requestRepairService.updateRepair(
                    spjangcd, recedate, recenum,
                    recetime, hitchdate, hitchhour,
                    actcd, actnm, equpcd, equpnm,
                    contcd, contents, remark, perid);
            result.success = true;
            result.message = "수정되었습니다.";
        } catch (Exception e) {
            log.error("고장접수 수정 오류", e);
            result.success = false;
            result.message = "수정 중 오류가 발생하였습니다.";
        }
        return result;
    }

    // ── 고장접수 삭제 (TB_E401 DELETE) ───────────────────────
    @PostMapping("/delete")
    public AjaxResult deleteRepair(
            @RequestParam(value = "spjangcd") String spjangcd,
            @RequestParam(value = "recedate") String recedate,
            @RequestParam(value = "recenum")  String recenum,
            HttpServletRequest request, Authentication auth) {

        AjaxResult result = new AjaxResult();
        try {
            requestRepairService.deleteRepair(spjangcd, recedate, recenum);
            result.success = true;
            result.message = "삭제되었습니다.";
        } catch (Exception e) {
            log.error("고장접수 삭제 오류", e);
            result.success = false;
            result.message = "삭제 중 오류가 발생하였습니다.";
        }
        return result;
    }
}
