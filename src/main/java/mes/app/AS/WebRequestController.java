package mes.app.AS;

import lombok.extern.slf4j.Slf4j;
import mes.app.AS.service.WebRequestService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

@Slf4j
@RestController
@Transactional
@RequestMapping("/api/web_request")
public class WebRequestController {

    @Autowired
    WebRequestService webRequestService;

    // ── 요약 카운트 ───────────────────────────────────────────
    @GetMapping("/summary")
    public AjaxResult getSummary(HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        result.data = webRequestService.getSummary(user.getUsername());
        return result;
    }

    // ── 고장접수 목록 조회 ────────────────────────────────────
    @GetMapping("/read")
    public AjaxResult getRepairList(
            @RequestParam(value = "fromDate", required = false) String fromDate,
            @RequestParam(value = "toDate",   required = false) String toDate,
            @RequestParam(value = "actnm",    required = false) String actnm,
            HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        result.data = webRequestService.getRepairList(user.getUsername(), fromDate, toDate, actnm);
        return result;
    }

    // ── 현장 목록 조회 ────────────────────────────────────────
    @GetMapping("/read_act")
    public AjaxResult getActList(HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        result.data = webRequestService.getActList(user.getUsername());
        return result;
    }

    // ── 호기 목록 조회 ────────────────────────────────────────
    @GetMapping("/read_equp")
    public AjaxResult getEqupList(
            @RequestParam(value = "actcd", required = false) String actcd,
            HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        result.data = webRequestService.getEqupList(user.getUsername(), actcd);
        return result;
    }

    // ── 고장접수 저장 (신규/수정) ─────────────────────────────
    @PostMapping("/save")
    public AjaxResult saveRepair(
            @RequestParam(value = "recedate",  required = false) String recedate,
            @RequestParam(value = "recenum",   required = false) String recenum,
            @RequestParam(value = "recetime",  required = false) String recetime,
            @RequestParam(value = "hitchdate", required = false) String hitchdate,
            @RequestParam(value = "hitchhour", required = false) String hitchhour,
            @RequestParam(value = "actcd",     required = false) String actcd,
            @RequestParam(value = "actnm",     required = false) String actnm,
            @RequestParam(value = "equpcd",    required = false) String equpcd,
            @RequestParam(value = "equpnm",    required = false) String equpnm,
            @RequestParam(value = "contents",  required = false) String contents,
            @RequestParam(value = "remark",    required = false) String remark,
            @RequestParam(value = "reperid",   required = false) String reperid,
            HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        String username = user.getUsername();
        try {
            webRequestService.saveRepair(username, recedate, recenum, recetime,
                    hitchdate, hitchhour, actcd, actnm, equpcd, equpnm,
                    contents, remark, reperid, username);
            result.success = true;
            result.message = (recenum == null || recenum.isBlank())
                    ? "고장접수가 등록되었습니다." : "고장접수가 수정되었습니다.";
        } catch (Exception e) {
            log.error("고장접수 저장 오류", e);
            result.success = false;
            result.message = "고장접수 저장 중 오류가 발생하였습니다.";
        }
        return result;
    }

    // ── 고장접수 삭제 ─────────────────────────────────────────
    @PostMapping("/delete")
    public AjaxResult deleteRepair(
            @RequestParam(value = "recedate", required = false) String recedate,
            @RequestParam(value = "recenum",  required = false) String recenum,
            HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        try {
            webRequestService.deleteRepair(user.getUsername(), recedate, recenum);
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
