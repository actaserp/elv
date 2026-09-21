package mes.app.transaction;

import mes.app.annotation.ApiProduct;

import lombok.extern.slf4j.Slf4j;
import mes.app.transaction.service.AccountsPayableListService;
import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 미지급현황 (파워빌더 'w_tb_ca642w_02')
 *
 * 조회조건은 기간·거래처·매입구분이다. 빈 값이면 전체를 뜻한다(파워빌더는 '%').
 * 예전에 있던 cltflag(거래처구분: 업체/직원/은행/카드)는 sports 가 거래처를 네 테이블로
 * 나눠 쓰던 구조라 없앴다. 사업체 DB 는 TB_XCLIENT 하나로 관리한다.
 */
@Slf4j
@ApiProduct(ApiProduct.P05)
@RestController
@RequestMapping("/api/transaction/accounts_payable_list")
public class AccountsPayableListController {

    @Autowired
    SqlRunner sqlRunner;

    @Autowired
    AccountsPayableListService accountsPayableListService;

    @GetMapping("/read")
    public AjaxResult getPayableList(
            @RequestParam(value = "srchStartDt", required = false) String start,
            @RequestParam(value = "srchEndDt", required = false) String end,
            @RequestParam(value = "cltcd", required = false, defaultValue = "") String cltcd,
            @RequestParam(value = "gubun", required = false, defaultValue = "") String gubun,
            // 파워빌더 화면의 '잔액체크' — 기본은 켠 상태다
            @RequestParam(value = "balanceOnly", required = false, defaultValue = "true") boolean balanceOnly,
            @RequestParam(value = "spjangcd") String spjangcd) {

        AjaxResult result = new AjaxResult();
        result.data = accountsPayableListService.getPayableList(start, end, spjangcd, cltcd, gubun, balanceOnly);
        return result;
    }

    @GetMapping("/DetailList")
    public AjaxResult getPayableDetail(
            @RequestParam(value = "srchStartDt", required = false) String start,
            @RequestParam(value = "srchEndDt", required = false) String end,
            @RequestParam(value = "code", required = false, defaultValue = "") String cltcd,
            @RequestParam(value = "gubun", required = false, defaultValue = "") String gubun,
            @RequestParam(value = "spjangcd") String spjangcd) {

        AjaxResult result = new AjaxResult();
        result.data = accountsPayableListService.getPayableDetailList(start, end, spjangcd, cltcd, gubun);
        return result;
    }
}
