package mes.app.AS;

import lombok.extern.slf4j.Slf4j;
import mes.app.AS.service.WebHandleService;
import mes.app.annotation.ApiProduct;
import mes.app.common.TenantUserService;
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
@RequestMapping("/api/AS/web_handle")
public class WebHandleController {

    @Autowired
    WebHandleService webHandleService;

    @Autowired
    TenantUserService tenantUserService;

    // ── 고장접수 목록 (왼쪽 그리드, TB_E401) ─────────────────
    // showAll='1' 이면 전체, 아니면 로그인 사용자가 통보자(perid)인 건만 조회
    @GetMapping("/list")
    public AjaxResult getList(
            @RequestParam(value = "fromDate", required = false) String fromDate,
            @RequestParam(value = "toDate",   required = false) String toDate,
            @RequestParam(value = "actnm",    required = false) String actnm,
            @RequestParam(value = "showAll",  required = false, defaultValue = "0") String showAll,
            @RequestParam(value = "spjangcd") String spjangcd,
            HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();

        String myPerid = null;
        if (!"1".equals(showAll)) {
            // 로그인 사용자의 통보자 perid (TB_JA001.perid 에서 'p' 제거 → TB_E401.perid 형식)
            User user = (User) auth.getPrincipal();
            Map<String, Object> userInfo = tenantUserService.getUserInfo(user.getUsername());
            if (userInfo != null && userInfo.get("perid") != null) {
                myPerid = String.valueOf(userInfo.get("perid")).replaceFirst("^p", "");
            } else {
                myPerid = "__none__";   // 사용자 정보 없으면 본인건 0건 처리
            }
        }

        result.data = webHandleService.getRequestList(spjangcd, fromDate, toDate, actnm, myPerid);
        return result;
    }

    // ── 고장처리 단건 조회 (접수건 클릭 시) ──────────────────
    @GetMapping("/comp_by_receive")
    public AjaxResult getCompByReceive(
            @RequestParam(value = "spjangcd") String spjangcd,
            @RequestParam(value = "recedate") String recedate,
            @RequestParam(value = "recenum")  String recenum,
            @RequestParam(value = "actcd", required = false) String actcd,
            HttpServletRequest request) {
        AjaxResult result = new AjaxResult();
        result.data = webHandleService.getCompByReceive(spjangcd, recedate, recenum, actcd);
        return result;
    }

    // ── 고장처리 목록 조회 API ────────────────────────────────
    @GetMapping("/comp_list")
    public AjaxResult getCompList(
            @RequestParam(value = "fromDate", required = false) String fromDate,
            @RequestParam(value = "toDate",   required = false) String toDate,
            @RequestParam(value = "actnm",    required = false) String actnm,
            @RequestParam(value = "spjangcd") String spjangcd,
            HttpServletRequest request) {
        AjaxResult result = new AjaxResult();
        result.data = webHandleService.getCompList(spjangcd, fromDate, toDate, actnm);
        return result;
    }

    // ── 고장처리결과 저장 (TB_E411 INSERT) ───────────────────
    @PostMapping("/save")
    public AjaxResult save(
            @RequestParam(value = "spjangcd")                    String spjangcd,
            @RequestParam(value = "compdate",  required = false) String compdate,
            @RequestParam(value = "comptime",  required = false) String comptime,
            @RequestParam(value = "recedate",  required = false) String recedate,
            @RequestParam(value = "recenum",   required = false) String recenum,
            @RequestParam(value = "recetime",  required = false) String recetime,
            @RequestParam(value = "arrivdate", required = false) String arrivdate,
            @RequestParam(value = "arrivtime", required = false) String arrivtime,
            @RequestParam(value = "actcd",     required = false) String actcd,
            @RequestParam(value = "actnm",     required = false) String actnm,
            @RequestParam(value = "equpcd",    required = false) String equpcd,
            @RequestParam(value = "equpnm",    required = false) String equpnm,
            @RequestParam(value = "contremark",required = false) String contremark,
            @RequestParam(value = "gregicd",   required = false) String gregicd,
            @RequestParam(value = "regicd",    required = false) String regicd,
            @RequestParam(value = "remocd",    required = false) String remocd,
            @RequestParam(value = "faccd",     required = false) String faccd,
            @RequestParam(value = "remoremark",required = false) String remoremark,
            @RequestParam(value = "resucd",    required = false) String resucd,
            @RequestParam(value = "resuremark",required = false) String resuremark,
            @RequestParam(value = "resultcd",  required = false) String resultcd,
            @RequestParam(value = "remark",    required = false) String remark,
            @RequestParam(value = "customer",  required = false) String customer,
            @RequestParam(value = "perid",     required = false) String perid,
            @RequestParam(value = "filesvnm",  required = false) String filesvnm,
            @RequestParam(value = "filepath",  required = false) String filepath,
            HttpServletRequest request, Authentication auth) {

        AjaxResult result = new AjaxResult();

        User user = (User) auth.getPrincipal();
        Map<String, Object> userInfo = tenantUserService.getUserInfo(user.getUsername());
        if (userInfo == null) {
            result.success = false;
            result.message = "사용자 정보를 찾을 수 없습니다.";
            return result;
        }
        String custcd = (String) userInfo.get("custcd");

        try {
            webHandleService.saveComp(
                    custcd, spjangcd, compdate, comptime,
                    recedate, recenum, recetime,
                    arrivdate, arrivtime,
                    actcd, actnm, equpcd, equpnm,
                    contremark, gregicd, regicd,
                    remocd, faccd, remoremark,
                    resucd, resuremark, resultcd,
                    remark, customer, perid,
                    filesvnm, filepath);
            result.success = true;
            result.message = "고장처리가 등록되었습니다.";
        } catch (Exception e) {
            log.error("고장처리 저장 오류", e);
            result.success = false;
            result.message = "저장 중 오류가 발생하였습니다.";
        }
        return result;
    }

    // ── 고장처리결과 수정 (TB_E411 UPDATE) ───────────────────
    @PostMapping("/update")
    public AjaxResult update(
            @RequestParam(value = "spjangcd")                    String spjangcd,
            @RequestParam(value = "compdate")                    String compdate,
            @RequestParam(value = "compnum")                     String compnum,
            @RequestParam(value = "comptime",  required = false) String comptime,
            @RequestParam(value = "recedate",  required = false) String recedate,
            @RequestParam(value = "recenum",   required = false) String recenum,
            @RequestParam(value = "recetime",  required = false) String recetime,
            @RequestParam(value = "arrivdate", required = false) String arrivdate,
            @RequestParam(value = "arrivtime", required = false) String arrivtime,
            @RequestParam(value = "actcd",     required = false) String actcd,
            @RequestParam(value = "actnm",     required = false) String actnm,
            @RequestParam(value = "equpcd",    required = false) String equpcd,
            @RequestParam(value = "equpnm",    required = false) String equpnm,
            @RequestParam(value = "contremark",required = false) String contremark,
            @RequestParam(value = "gregicd",   required = false) String gregicd,
            @RequestParam(value = "regicd",    required = false) String regicd,
            @RequestParam(value = "remocd",    required = false) String remocd,
            @RequestParam(value = "faccd",     required = false) String faccd,
            @RequestParam(value = "remoremark",required = false) String remoremark,
            @RequestParam(value = "resucd",    required = false) String resucd,
            @RequestParam(value = "resuremark",required = false) String resuremark,
            @RequestParam(value = "resultcd",  required = false) String resultcd,
            @RequestParam(value = "remark",    required = false) String remark,
            @RequestParam(value = "customer",  required = false) String customer,
            @RequestParam(value = "perid",     required = false) String perid,
            HttpServletRequest request, Authentication auth) {

        AjaxResult result = new AjaxResult();
        try {
            webHandleService.updateComp(
                    spjangcd, compdate, compnum, comptime,
                    recedate, recenum, recetime,
                    arrivdate, arrivtime,
                    actcd, actnm, equpcd, equpnm,
                    contremark, gregicd, regicd,
                    remocd, faccd, remoremark,
                    resucd, resuremark, resultcd,
                    remark, customer, perid);
            result.success = true;
            result.message = "수정되었습니다.";
        } catch (Exception e) {
            log.error("고장처리 수정 오류", e);
            result.success = false;
            result.message = "수정 중 오류가 발생하였습니다.";
        }
        return result;
    }

    // ── 고장처리결과 삭제 (TB_E411 DELETE) ───────────────────
    @PostMapping("/delete")
    public AjaxResult delete(
            @RequestParam(value = "spjangcd") String spjangcd,
            @RequestParam(value = "compdate") String compdate,
            @RequestParam(value = "compnum")  String compnum,
            HttpServletRequest request) {

        AjaxResult result = new AjaxResult();
        try {
            webHandleService.deleteComp(spjangcd, compdate, compnum);
            result.success = true;
            result.message = "삭제되었습니다.";
        } catch (Exception e) {
            log.error("고장처리 삭제 오류", e);
            result.success = false;
            result.message = "삭제 중 오류가 발생하였습니다.";
        }
        return result;
    }

    // ── 팝업: 현장 검색 (TB_E601) ────────────────────────────
    @GetMapping("/popup/actnm")
    public AjaxResult popupActnm(
            @RequestParam(value = "spjangcd") String spjangcd,
            @RequestParam(value = "actnm",    required = false) String actnm,
            HttpServletRequest request) {
        AjaxResult result = new AjaxResult();
        result.data = webHandleService.popupActnm(spjangcd, actnm);
        return result;
    }

    // ── 팝업: 호기 검색 (TB_E611) ────────────────────────────
    @GetMapping("/popup/equpnm")
    public AjaxResult popupEqupnm(
            @RequestParam(value = "spjangcd") String spjangcd,
            @RequestParam(value = "actcd",    required = false) String actcd,
            HttpServletRequest request) {
        AjaxResult result = new AjaxResult();
        result.data = webHandleService.popupEqupnm(spjangcd, actcd);
        return result;
    }

    // ── 팝업: 사원 검색 (처리자, TB_JA001) ───────────────────
    @GetMapping("/popup/pernm")
    public AjaxResult popupPernm(
            @RequestParam(value = "spjangcd") String spjangcd,
            @RequestParam(value = "pernm",    required = false) String pernm,
            HttpServletRequest request) {
        AjaxResult result = new AjaxResult();
        result.data = webHandleService.popupPernm(spjangcd, pernm);
        return result;
    }
}
