package mes.app.mobile;

import lombok.extern.slf4j.Slf4j;
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
        String spjangcd = tenantUserService.getSpjangcd(user.getUsername());
        result.data = requestRepairService.getRepairList(fromDate, toDate, actnm, resultck, spjangcd);
        return result;
    }

    // ── 현장 목록 조회 (TB_E601) ─────────────────────────────
    @GetMapping("/read_act")
    public AjaxResult getActList(
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        String spjangcd = tenantUserService.getSpjangcd(user.getUsername()); // ← 수정
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
        String spjangcd = tenantUserService.getSpjangcd(user.getUsername()); // ← 수정
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
            @RequestParam(value = "reperid",   required = false) String reperid,
            @RequestParam(value = "bigo",      required = false) String bigo,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        String username = user.getUsername();

        // 사업체DB에서 spjangcd, custcd 조회 ← 수정
        Map<String, Object> userInfo = tenantUserService.getUserInfo(username);
        if (userInfo == null) {
            result.success = false;
            result.message = "사업체 DB에서 사용자 정보를 찾을 수 없습니다.";
            return result;
        }
        String spjangcd = (String) userInfo.get("spjangcd");
        String custcd   = (String) userInfo.get("custcd");

        try {
            requestRepairService.saveRepair(
                    custcd, spjangcd, recedate, recetime,
                    hitchdate, hitchhour,
                    actcd, actnm, equpcd, equpnm,
                    contcd, contents, remark, reperid, bigo, username
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
}
