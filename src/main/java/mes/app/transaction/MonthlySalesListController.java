package mes.app.transaction;

import mes.app.annotation.ApiProduct;

import lombok.extern.slf4j.Slf4j;
import mes.app.transaction.service.MonthlySalesListService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 월별매출현황
 *
 * 입금 탭은 파워빌더 '월별입금현황'(w_tb_da026_01w) 과 같다.
 * 매출·미수 탭은 파워빌더 원본을 못 받아 입금 탭과 같은 모양으로 맞춰 만든 것이다.
 */
@Slf4j
@ApiProduct(ApiProduct.P05)
@RestController
@RequestMapping("/api/transaction/MonthlySalesList")
public class MonthlySalesListController {

    @Autowired
    MonthlySalesListService monthlySalesListService;

    // 매출 탭
    @GetMapping("/SalesRead")
    public AjaxResult getMonthlySalesList(
            @RequestParam(value = "cboYear", required = false) String cboYear,
            @RequestParam(value = "cltcd", required = false, defaultValue = "") String cltcd,
            @RequestParam(value = "spcd", required = false, defaultValue = "") String spcd,
            @RequestParam(value = "spjangcd") String spjangcd) {

        AjaxResult result = new AjaxResult();
        result.data = monthlySalesListService.getMonthSalesList(cboYear, cltcd, spjangcd, spcd);
        return result;
    }

    // 입금 탭
    @GetMapping("/DepositRead")
    public AjaxResult getMonthDepositList(
            @RequestParam(value = "cboYear", required = false) String cboYear,
            @RequestParam(value = "cltcd", required = false, defaultValue = "") String cltcd,
            @RequestParam(value = "spcd", required = false, defaultValue = "") String spcd,
            @RequestParam(value = "spjangcd") String spjangcd) {

        AjaxResult result = new AjaxResult();
        result.data = monthlySalesListService.getMonthDepositList(cboYear, cltcd, spjangcd, spcd);
        return result;
    }

    // 미수금 탭
    @GetMapping("/ReceivableRead")
    public AjaxResult getMonthReceivableList(
            @RequestParam(value = "cboYear", required = false) String cboYear,
            @RequestParam(value = "cltcd", required = false, defaultValue = "") String cltcd,
            @RequestParam(value = "spcd", required = false, defaultValue = "") String spcd,
            @RequestParam(value = "spjangcd") String spjangcd) {

        AjaxResult result = new AjaxResult();
        result.data = monthlySalesListService.getMonthReceivableList(cboYear, cltcd, spjangcd, spcd);
        return result;
    }

    // 미수금 탭 (현장별)
    @GetMapping("/ReceivableSiteRead")
    public AjaxResult getMonthReceivableBySite(
            @RequestParam(value = "cboYear", required = false) String cboYear,
            @RequestParam(value = "actcd", required = false, defaultValue = "") String actcd,
            @RequestParam(value = "divicd", required = false, defaultValue = "") String divicd,
            @RequestParam(value = "perid", required = false, defaultValue = "") String perid,
            @RequestParam(value = "spjangcd") String spjangcd) {

        AjaxResult result = new AjaxResult();
        result.data = monthlySalesListService.getMonthReceivableBySite(cboYear, actcd, divicd, perid, spjangcd);
        return result;
    }

    // 매출 탭에서 거래처 클릭 → 그 해 매출 전표
    @GetMapping("/SalesDetail")
    public AjaxResult getSalesDetail(
            @RequestParam(value = "cboYear", required = false) String cboYear,
            @RequestParam(value = "cltcd", required = false, defaultValue = "") String cltcd,
            @RequestParam(value = "spjangcd") String spjangcd) {

        List<Map<String, Object>> items = monthlySalesListService.getSalesDetail(cboYear, cltcd, spjangcd);
        AjaxResult result = new AjaxResult();
        result.data = items;
        return result;
    }

    // 입금 탭에서 거래처 클릭 → 그 해 수금 전표
    @GetMapping("/DepositDetail")
    public AjaxResult getDepositDetail(
            @RequestParam(value = "cboYear", required = false) String cboYear,
            @RequestParam(value = "cltcd", required = false, defaultValue = "") String cltcd,
            @RequestParam(value = "spjangcd") String spjangcd) {

        List<Map<String, Object>> items = monthlySalesListService.getDepositDetail(cboYear, cltcd, spjangcd);
        AjaxResult result = new AjaxResult();
        result.data = items;
        return result;
    }
}
