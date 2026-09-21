package mes.app.ai;

import lombok.extern.slf4j.Slf4j;
import mes.app.ai.service.AiFaultDashboardService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * AI고장분석대시보드 (관리자운영 > ai_support) — 반복고장 패턴분석
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/dashboard")
public class AiFaultDashboardController {

    @Autowired
    AiFaultDashboardService dashboardService;

    @GetMapping("/summary")
    public AjaxResult getSummary(@RequestParam("fromDate") String fromDate,
                                 @RequestParam("toDate") String toDate,
                                 @RequestParam(value = "actnm", required = false) String actnm,
                                 @RequestParam(value = "threshold", defaultValue = "2") int threshold,
                                 @RequestParam(value = "groupBy", defaultValue = "greg") String groupBy,
                                 @RequestParam("spjangcd") String spjangcd) {
        AjaxResult result = new AjaxResult();
        String err = checkPeriod(fromDate, toDate);
        if (err != null) {
            result.success = false;
            result.message = err;
            return result;
        }
        if (threshold < 2 || threshold > 99) {
            result.success = false;
            result.message = "반복기준을 확인해주세요.";
            return result;
        }
        result.data = dashboardService.getSummary(spjangcd, fromDate, toDate, actnm, threshold, groupBy);
        return result;
    }

    @GetMapping("/history")
    public AjaxResult getHistory(@RequestParam("fromDate") String fromDate,
                                 @RequestParam("toDate") String toDate,
                                 @RequestParam(value = "groupBy", defaultValue = "greg") String groupBy,
                                 @RequestParam("actcd") String actcd,
                                 @RequestParam(value = "equpcd", required = false) String equpcd,
                                 @RequestParam("gkey") String gkey,
                                 @RequestParam("spjangcd") String spjangcd) {
        AjaxResult result = new AjaxResult();
        String err = checkPeriod(fromDate, toDate);
        if (err != null) {
            result.success = false;
            result.message = err;
            return result;
        }
        result.data = dashboardService.getHistory(spjangcd, fromDate, toDate, groupBy, actcd, equpcd, gkey);
        return result;
    }

    private static String checkPeriod(String fromDate, String toDate) {
        try {
            LocalDate f = LocalDate.parse(fromDate, DateTimeFormatter.BASIC_ISO_DATE);
            LocalDate t = LocalDate.parse(toDate, DateTimeFormatter.BASIC_ISO_DATE);
            if (f.isAfter(t)) return "조회기간 시작일이 종료일보다 늦습니다.";
            return null;
        } catch (DateTimeParseException | NullPointerException e) {
            return "조회기간을 확인해주세요.";
        }
    }
}
