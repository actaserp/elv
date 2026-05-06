package mes.app.AS;

import lombok.extern.slf4j.Slf4j;
import mes.app.AS.service.DailyStatusService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/AS/daily_status")
public class DailyStatusController {

    @Autowired
    DailyStatusService dailyStatusService;

    /**
     * 업무일지 작성현황 조회
     * - 사원별 + 해당 월 작성된 날짜(rptdate) 목록 반환
     * - 프론트에서 perid 기준 그룹핑하여 X / 0 표시
     */
    @GetMapping("/read")
    public AjaxResult read(
            @RequestParam(value = "year")                         String year,
            @RequestParam(value = "month")                        String month,
            @RequestParam(value = "dept",     required = false)   String dept,
            @RequestParam(value = "retire",   required = false)   String retire,
            @RequestParam(value = "spjangcd", required = false)   String spjangcd,
            HttpServletRequest request) {

        AjaxResult result = new AjaxResult();
        List<Map<String, Object>> items =
                dailyStatusService.getStatusList(year, month, dept, retire, spjangcd);
        result.data = items;
        return result;
    }
}
