package mes.app.clock;

import mes.app.clock.service.ClockSystemService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.transaction.Transactional;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/clock/System")
public class ClockSystemController {

    @Autowired
    private ClockSystemService clockSystemService;

    // ── 근태항목 목록 조회 ────────────────────────────────────
    @GetMapping("/read")
    public AjaxResult getSystemList(
            @RequestParam(value = "spjangcd") String spjangcd,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        result.data = clockSystemService.getSystemList(spjangcd);
        return result;
    }

    // ── 근태시간 목록 조회 ────────────────────────────────────
    @GetMapping("/tiemread")
    public AjaxResult getSystemtimeList(
            @RequestParam(value = "spjangcd") String spjangcd,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        result.data = clockSystemService.getSystemtimeList(spjangcd);
        return result;
    }

    // ── 근태항목 상세 조회 ────────────────────────────────────
    @GetMapping("/detail")
    public AjaxResult getSystemDetail(
            @RequestParam(value = "workcd")   String workcd,
            @RequestParam(value = "spjangcd") String spjangcd,
            HttpServletRequest request) {

        AjaxResult result = new AjaxResult();
        result.data = clockSystemService.getSystemDetail(workcd, spjangcd);
        return result;
    }

    // ── 근태항목 저장 (TB_PB210 MERGE) ───────────────────────
    @PostMapping("/save")
    public AjaxResult savePb210(
            @RequestParam(value = "workcd")             String workcd,
            @RequestParam(value = "worknm",   required = false) String worknm,
            @RequestParam(value = "remark",   required = false) String remark,
            @RequestParam(value = "yearflag", required = false) String yearflag,
            @RequestParam(value = "usenum",   required = false) String usenum,
            @RequestParam(value = "spjangcd") String spjangcd,
            Authentication auth) {

        AjaxResult result = new AjaxResult();

        BigDecimal usenumVal = (usenum != null && !usenum.isEmpty())
                ? new BigDecimal(usenum) : null;

        clockSystemService.savePb210(spjangcd, workcd, worknm, remark, yearflag, usenumVal);

        result.success = true;
        return result;
    }

    // ── 근태항목 삭제 (TB_PB210 DELETE) ──────────────────────
    @PostMapping("/delete")
    public AjaxResult deletePb210(
            @RequestParam("workcd")   String workcd,
            @RequestParam("spjangcd") String spjangcd) {

        AjaxResult result = new AjaxResult();
        clockSystemService.deletePb210(spjangcd, workcd);
        result.success = true;
        return result;
    }

    // ── 근태시간 저장 (TB_PBCONT MERGE) ──────────────────────
    @PostMapping("/savetime")
    @Transactional
    public AjaxResult saveTime(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();

        String spjangcd = (String) body.get("spjangcd");
        List<Map<String, Object>> dataList =
                (List<Map<String, Object>>) body.get("data");

        clockSystemService.savePbcont(spjangcd, dataList);

        result.success = true;
        return result;
    }
}
