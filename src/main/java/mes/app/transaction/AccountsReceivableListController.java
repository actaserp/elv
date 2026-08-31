package mes.app.transaction;

import lombok.extern.slf4j.Slf4j;
import mes.app.transaction.service.AccountsReceivableListService;
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
@RequestMapping("/api/transaction/AccRList")
public class AccountsReceivableListController {

    @Autowired
    AccountsReceivableListService accountsReceivableListService;

    // 미수금 현황 집계
    // cltcd/gubun/billgubun/perid 는 파워빌더와 동일하게 미입력 시 '%'(전체) 로 처리된다.
    @GetMapping("/TotalList")
    public AjaxResult getTotalList(
            @RequestParam(value = "srchStartDt", required = false) String start_date,
            @RequestParam(value = "srchEndDt",   required = false) String end_date,
            @RequestParam(value = "cltcd",       required = false) String cltcd,
            @RequestParam(value = "gubun",       required = false) String gubun,
            @RequestParam(value = "billgubun",   required = false) String billgubun,
            @RequestParam(value = "perid",       required = false) String perid,
            @RequestParam(value = "divicd",      required = false) String divicd,
            @RequestParam(value = "siteGubun",   required = false) String siteGubun,
            // 화면의 '매출기준' — 켜면 입금도 매출일자 기준으로 조회한다
            @RequestParam(value = "salesBasis",  required = false, defaultValue = "false") boolean salesBasis,
            // 화면의 '잔액체크' — 기본 켜짐 (파워빌더와 동일)
            @RequestParam(value = "balanceOnly", required = false, defaultValue = "true") boolean balanceOnly,
            @RequestParam(value = "spjangcd") String spjangcd) {

        List<Map<String, Object>> items = this.accountsReceivableListService
                .getTotalList(start_date, end_date, spjangcd, cltcd, gubun, billgubun, perid, divicd, siteGubun,
                        salesBasis, balanceOnly);

        AjaxResult result = new AjaxResult();
        result.data = items;
        return result;
    }

    // 미수금 현황 상세 (거래처 더블클릭)
    @GetMapping("/DetailList")
    public AjaxResult getDetailList(
            @RequestParam(value = "srchStartDt", required = false) String start_date,
            @RequestParam(value = "srchEndDt",   required = false) String end_date,
            @RequestParam(value = "cltcd",       required = false) String cltcd,
            @RequestParam(value = "gubun",       required = false) String gubun,
            @RequestParam(value = "billgubun",   required = false) String billgubun,
            @RequestParam(value = "spjangcd") String spjangcd) {

        List<Map<String, Object>> items = this.accountsReceivableListService
                .getDetailList(start_date, end_date, spjangcd, cltcd, gubun, billgubun);

        AjaxResult result = new AjaxResult();
        result.data = items;
        return result;
    }
}
