package mes.app.transaction;

import lombok.extern.slf4j.Slf4j;
import mes.app.transaction.service.YearlySalesListService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 년도별 매출현황 (파워빌더 w_tb_da023_02w 요약 + w_tb_da023_03w 월별) */
@Slf4j
@RestController
@RequestMapping("/api/transaction/yearly_sales_list")
public class YearlySalesListController {

    @Autowired
    YearlySalesListService yearlySalesListService;

    // 4개년 매출액·증가율
    @GetMapping("/summary")
    public AjaxResult getSummary(
            @RequestParam(value = "cboYear", required = false) String year,
            @RequestParam(value = "spjangcd") String spjangcd) {

        AjaxResult result = new AjaxResult();
        result.data = yearlySalesListService.getSummary(year, spjangcd);
        return result;
    }

    // 최근 4년 × 12개월 매출액
    @GetMapping("/monthly")
    public AjaxResult getMonthly(
            @RequestParam(value = "cboYear", required = false) String year,
            @RequestParam(value = "divicd", required = false, defaultValue = "") String divicd,
            @RequestParam(value = "spjangcd") String spjangcd) {

        AjaxResult result = new AjaxResult();
        result.data = yearlySalesListService.getMonthly(year, divicd, spjangcd);
        return result;
    }
}
