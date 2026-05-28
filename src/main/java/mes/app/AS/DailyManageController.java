package mes.app.AS;

import lombok.extern.slf4j.Slf4j;
import mes.app.AS.service.DailyManageService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/AS/daily_manage")
public class DailyManageController {

    @Autowired
    DailyManageService dailyManageService;

    // ── 업무일지 목록 조회 (year + month 기준) ─────────────────
    @GetMapping("/read")
    public AjaxResult read(
            @RequestParam(value = "year")                       String year,
            @RequestParam(value = "month")                      String month,
            @RequestParam(value = "pernm",    required = false) String pernm,
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            HttpServletRequest request) {

        AjaxResult result = new AjaxResult();
        result.data = dailyManageService.getList(year, month, pernm, spjangcd);
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
