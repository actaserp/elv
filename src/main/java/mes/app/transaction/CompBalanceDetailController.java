package mes.app.transaction;

import lombok.extern.slf4j.Slf4j;
import mes.app.aspect.DecryptField;
import mes.app.transaction.service.CompBalanceDetailService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/transaction/CompBalanceDetail")
public class CompBalanceDetailController {

    @Autowired
    CompBalanceDetailService compBalanceDetailService;

    // 미수금 잔액명세
    @DecryptField(columns = {"accnum"})
    @GetMapping("/read")
    public AjaxResult getList(
            @RequestParam(value = "srchStartDt", required = false) String start,
            @RequestParam(value = "srchEndDt", required = false) String end,
            @RequestParam(value = "cboCompany", required = false) String company,
            @RequestParam(value = "spjangcd") String spjangcd) {

        List<Map<String, Object>> items = this.compBalanceDetailService.getList(start, end, company, spjangcd);

        AjaxResult result = new AjaxResult();
        result.data = items;
        return result;
    }
}
