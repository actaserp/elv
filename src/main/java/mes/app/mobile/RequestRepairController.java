package mes.app.mobile;

import lombok.extern.slf4j.Slf4j;
import mes.app.mobile.Service.RequestRepairService;
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
@RequestMapping("/api/request_repair")
public class RequestRepairController {

    @Autowired
    RequestRepairService requestRepairService;

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
            @RequestParam(value = "fromDate", required = false) String fromDate,
            @RequestParam(value = "toDate",   required = false) String toDate,
            @RequestParam(value = "actnm",    required = false) String actnm,
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        result.data = requestRepairService.getRepairList(fromDate, toDate, actnm, spjangcd);
        return result;
    }

    // ── 현장 목록 조회 (TB_E601) ─────────────────────────────
    @GetMapping("/read_act")
    public AjaxResult getActList(
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        result.data = requestRepairService.getActList(spjangcd);
        return result;
    }

    // ── 호기 목록 조회 (TB_E611) ─────────────────────────────
    @GetMapping("/read_equp")
    public AjaxResult getEqupList(
            @RequestParam(value = "actcd",    required = false) String actcd,
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        result.data = requestRepairService.getEqupList(actcd, spjangcd);
        return result;
    }

    // ── 고장접수 등록 (TB_E401 INSERT) ───────────────────────
    @PostMapping("/save")
    public AjaxResult saveRepair(
            @RequestParam(value = "spjangcd",  required = false) String spjangcd,
            @RequestParam(value = "recedate",  required = false) String recedate,
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
            @RequestParam(value = "bigo",      required = false) String bigo,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        String perid = user.getUsername();

        try {
            requestRepairService.saveRepair(
                    spjangcd, recedate, recetime,
                    hitchdate, hitchhour,
                    actcd, actnm, equpcd, equpnm,
                    contents, remark, reperid, bigo, perid
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
