package mes.app.transaction;

import lombok.extern.slf4j.Slf4j;
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

    // 미수금 잔액명세 (파워빌더 w_input_da023w_01)
    //   cltcd/gubun 은 파워빌더와 동일하게 미입력 시 전체로 처리된다.
    @GetMapping("/read")
    public AjaxResult getList(
            @RequestParam(value = "srchStartDt", required = false) String start,
            @RequestParam(value = "srchEndDt",   required = false) String end,
            @RequestParam(value = "cltcd",       required = false) String cltcd,
            @RequestParam(value = "gubun",       required = false) String gubun,
            @RequestParam(value = "spjangcd")                      String spjangcd) {

        if (start != null) start = start.replace("-", "");
        if (end   != null) end   = end.replace("-", "");

        List<Map<String, Object>> items = this.compBalanceDetailService.getList(start, end, spjangcd, cltcd, gubun);

        AjaxResult result = new AjaxResult();
        result.data = items;
        return result;
    }
}
