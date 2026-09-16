package mes.app.transaction;

import lombok.extern.slf4j.Slf4j;
import mes.app.transaction.service.MonthlyExpenseItemService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 비용항목별 월별현황 (파워빌더 w_tb_ca642w_11) */
@Slf4j
@RestController
@RequestMapping("/api/transaction/monthly_expense_item")
public class MonthlyExpenseItemController {

    @Autowired
    MonthlyExpenseItemService monthlyExpenseItemService;

    // 비용항목별 (대분류)
    @GetMapping("/category")
    public AjaxResult getByCategory(
            @RequestParam(value = "cboYear", required = false) String year,
            @RequestParam(value = "artnm", required = false, defaultValue = "") String artnm,
            @RequestParam(value = "spjangcd") String spjangcd) {

        AjaxResult result = new AjaxResult();
        result.data = monthlyExpenseItemService.getByCategory(year, artnm, spjangcd);
        return result;
    }

    // 비용항목상세 (세부 항목)
    @GetMapping("/item")
    public AjaxResult getByItem(
            @RequestParam(value = "cboYear", required = false) String year,
            @RequestParam(value = "artnm", required = false, defaultValue = "") String artnm,
            @RequestParam(value = "spjangcd") String spjangcd) {

        AjaxResult result = new AjaxResult();
        result.data = monthlyExpenseItemService.getByItem(year, artnm, spjangcd);
        return result;
    }
}
