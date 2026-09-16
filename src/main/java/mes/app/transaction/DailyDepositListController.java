package mes.app.transaction;

import lombok.extern.slf4j.Slf4j;
import mes.app.transaction.service.DailyDepositListService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 일별입금현황 (파워빌더 w_tb_da026_02w) */
@Slf4j
@RestController
@RequestMapping("/api/transaction/daily_deposit_list")
public class DailyDepositListController {

    @Autowired
    DailyDepositListService dailyDepositListService;

    @GetMapping("/read")
    public AjaxResult getDailyDepositList(
            @RequestParam(value = "srchMonth", required = false) String mon,
            @RequestParam(value = "cltcd", required = false, defaultValue = "") String cltcd,
            @RequestParam(value = "spcd", required = false, defaultValue = "") String spcd,
            @RequestParam(value = "spjangcd") String spjangcd) {

        AjaxResult result = new AjaxResult();
        result.data = dailyDepositListService.getDailyDepositList(mon, cltcd, spjangcd, spcd);
        return result;
    }
}
