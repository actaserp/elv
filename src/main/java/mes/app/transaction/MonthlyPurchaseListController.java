package mes.app.transaction;

import lombok.extern.slf4j.Slf4j;
import mes.app.transaction.service.MonthlyPurchaseListService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 월별매입현황
 *
 * 매입 탭은 파워빌더 '월별비용현황'(w_tb_ca642w_05) 과 같다.
 * 지급·미지급 탭은 파워빌더 원본을 못 받아 매입 탭과 같은 모양으로 맞춰 만든 것이다.
 */
@Slf4j
@RestController
@RequestMapping("/api/transaction/monthly_purchase_list")
public class MonthlyPurchaseListController {

    @Autowired
    MonthlyPurchaseListService monthlyPurchaseListService;

    // 매입 탭
    @GetMapping("/PurchaseDetails")
    public AjaxResult getMonthPurchaseList(
            @RequestParam(value = "cboYear", required = false) String cboYear,
            @RequestParam(value = "cltcd", required = false, defaultValue = "") String cltcd,
            @RequestParam(value = "spjangcd") String spjangcd) {

        AjaxResult result = new AjaxResult();
        result.data = monthlyPurchaseListService.getMonthPurchaseList(cboYear, cltcd, spjangcd);
        return result;
    }

    // 미지급 탭
    @GetMapping("/ProvisionRead")
    public AjaxResult getMonthPayableList(
            @RequestParam(value = "cboYear", required = false) String cboYear,
            @RequestParam(value = "cltcd", required = false, defaultValue = "") String cltcd,
            @RequestParam(value = "spjangcd") String spjangcd) {

        AjaxResult result = new AjaxResult();
        result.data = monthlyPurchaseListService.getMonthPayableList(cboYear, cltcd, spjangcd);
        return result;
    }

    // 지급 탭
    @GetMapping("/PaymentRead")
    public AjaxResult getMonthPaymentList(
            @RequestParam(value = "cboYear", required = false) String cboYear,
            @RequestParam(value = "cltcd", required = false, defaultValue = "") String cltcd,
            @RequestParam(value = "spjangcd") String spjangcd) {

        AjaxResult result = new AjaxResult();
        result.data = monthlyPurchaseListService.getMonthPaymentList(cboYear, cltcd, spjangcd);
        return result;
    }

    // 매입 탭에서 거래처 클릭 → 그 해 매입 전표
    @GetMapping("/PurchaseDetail")
    public AjaxResult getPurchaseDetail(
            @RequestParam(value = "cboYear", required = false) String cboYear,
            @RequestParam(value = "cltcd", required = false, defaultValue = "") String cltcd,
            @RequestParam(value = "spjangcd") String spjangcd) {

        List<Map<String, Object>> items = monthlyPurchaseListService.getPurchaseDetail(cboYear, cltcd, spjangcd);
        AjaxResult result = new AjaxResult();
        result.data = items;
        return result;
    }

    // 지급 탭에서 거래처 클릭 → 그 해 지급 전표
    @GetMapping("/PaymentDetail")
    public AjaxResult getPaymentDetail(
            @RequestParam(value = "cboYear", required = false) String cboYear,
            @RequestParam(value = "cltcd", required = false, defaultValue = "") String cltcd,
            @RequestParam(value = "spjangcd") String spjangcd) {

        List<Map<String, Object>> items = monthlyPurchaseListService.getPaymentDetail(cboYear, cltcd, spjangcd);
        AjaxResult result = new AjaxResult();
        result.data = items;
        return result;
    }
}
