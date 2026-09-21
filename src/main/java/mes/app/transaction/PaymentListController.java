package mes.app.transaction;

import mes.app.annotation.ApiProduct;

import lombok.extern.slf4j.Slf4j;
import mes.app.transaction.service.PaymentListService;
import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 지급현황 (파워빌더 '비용지급현황' w_tb_ca642w_01)
 *
 * 조회조건은 파워빌더와 같이 기간·거래처·적요 세 가지다. 빈 값이면 전체를 뜻한다.
 * 예전 화면에 있던 계좌명·입금형태·어음번호 조건은 sports 의 입출금 테이블 기준이라 없앴다.
 */
@Slf4j
@ApiProduct(ApiProduct.P05)
@RestController
@RequestMapping("/api/transaction/payment_list")
public class PaymentListController {
    @Autowired
    SqlRunner sqlRunner;

    @Autowired
    PaymentListService paymentListService;

    @GetMapping("/read")
    public AjaxResult getPaymentList(
            @RequestParam(value = "srchStartDt", required = false) String startDate,
            @RequestParam(value = "srchEndDt", required = false) String endDate,
            @RequestParam(value = "cltcd", required = false, defaultValue = "") String cltcd,
            @RequestParam(value = "txtDescription", required = false, defaultValue = "") String remark,
            @RequestParam(value = "spjangcd") String spjangcd) {

        AjaxResult result = new AjaxResult();
        List<Map<String, Object>> items =
                paymentListService.getPaymentList(spjangcd, startDate, endDate, cltcd, remark);
        result.data = items;
        return result;
    }
}
