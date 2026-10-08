package mes.app.AS;

import lombok.extern.slf4j.Slf4j;
import mes.app.annotation.ApiProduct;
import mes.app.AS.service.FaultSummaryService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/** 고장통계 — 종합현황 */
@Slf4j
@ApiProduct(ApiProduct.P01)
@RestController
@RequestMapping("/api/AS/fault_summary")
public class FaultSummaryController {

    @Autowired
    private FaultSummaryService faultSummaryService;

    @GetMapping("/read")
    public AjaxResult read(
            @RequestParam(value = "spjangcd") String spjangcd,
            @RequestParam(value = "stmon") String stmon,
            @RequestParam(value = "endmon") String endmon,
            @RequestParam(value = "cltnm", required = false) String cltnm,
            @RequestParam(value = "actnm", required = false) String actnm,
            HttpServletRequest request) {

        AjaxResult result = new AjaxResult();

        String from = ym(stmon), to = ym(endmon);
        if (from.length() != 6 || to.length() != 6) {
            result.success = false;
            result.message = "조회기간을 선택하세요.";
            return result;
        }

        Map<String, Object> data = this.faultSummaryService.getSummary(spjangcd, from, to, cltnm, actnm);

        result.success = true;
        result.data = data;
        return result;
    }

    /** 'yyyy-MM' / 'yyyyMM' → 'yyyyMM' */
    private String ym(String v) {
        return v == null ? "" : v.replace("-", "").trim();
    }
}
