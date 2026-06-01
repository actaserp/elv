package mes.app.AS;

import lombok.extern.slf4j.Slf4j;
import mes.app.AS.service.WebHandleService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

@Slf4j
@RestController
@Transactional
@RequestMapping("/api/AS/web_handle")
public class WebHandleController {

    @Autowired
    WebHandleService webHandleService;

    // ── 고장접수 목록 (왼쪽 그리드, TB_E401) ─────────────────
    @GetMapping("/list")
    public AjaxResult getList(
            @RequestParam(value = "fromDate", required = false) String fromDate,
            @RequestParam(value = "toDate",   required = false) String toDate,
            @RequestParam(value = "actnm",    required = false) String actnm,
            @RequestParam(value = "spjangcd") String spjangcd,
            HttpServletRequest request) {
        AjaxResult result = new AjaxResult();
        result.data = webHandleService.getRequestList(spjangcd, fromDate, toDate, actnm);
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
            @RequestParam(value = "remoremark",required = false) String remoremark,
            @RequestParam(value = "resuremark",required = false) String resuremark,
            @RequestParam(value = "resultcd",  required = false) String resultcd,
            @RequestParam(value = "customer",  required = false) String customer,
            @RequestParam(value = "remark",    required = false) String remark,
            @RequestParam(value = "perid",     required = false) String perid,
            HttpServletRequest request) {

        AjaxResult result = new AjaxResult();
        try {
            webHandleService.saveComp(
                    spjangcd, compdate, comptime,
                    recedate, recenum, recetime,
                    arrivdate, arrivtime,
                    actcd, actnm, equpcd, equpnm,
                    contremark, remoremark, resuremark,
                    resultcd, customer, remark, perid);
            result.success = true;
            result.message = "고장처리가 등록되었습니다.";
        } catch (Exception e) {
            log.error("고장처리 저장 오류", e);
            result.success = false;
            result.message = "저장 중 오류가 발생하였습니다.";
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
