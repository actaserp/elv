package mes.app.transaction;

import lombok.extern.slf4j.Slf4j;
import mes.app.aspect.DecryptField;
import mes.app.transaction.service.VendorBalanceDetailService;
import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@Slf4j
@RestController
@RequestMapping("/api/transaction/vendor_balance_detail")
public class VendorBalanceDetailController {
    @Autowired
    SqlRunner sqlRunner;

    @Autowired
    VendorBalanceDetailService vendorBalanceDetailService;

    // 미지급금 잔액명세 (파워빌더 '거래처별잔액명세서' w_tb_ca642w_03)
    // 지급 기간(rsdate/redate)은 파워빌더처럼 따로 받을 수 있고, 안 주면 매입 기간과 같다.
    @GetMapping("/read")
    public AjaxResult getVendorBalanceDetail(
            @RequestParam(value = "srchStartDt", required = false) String start,
            @RequestParam(value = "srchEndDt", required = false) String end,
            @RequestParam(value = "rsdate", required = false) String rsdate,
            @RequestParam(value = "redate", required = false) String redate,
            @RequestParam(value = "cltcd", required = false, defaultValue = "") String cltcd,
            @RequestParam(value = "gubun", required = false, defaultValue = "") String gubun,
            @RequestParam(value = "spjangcd") String spjangcd) {

        AjaxResult result = new AjaxResult();
        result.data = vendorBalanceDetailService.getPaymentList(
                spjangcd, start, end, rsdate, redate, cltcd, gubun);
        return result;
    }
}
