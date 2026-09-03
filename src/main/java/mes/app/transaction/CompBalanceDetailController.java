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
    //
    //   flag = 화면의 '구분' 콤보. 파워빌더 dw_key.itemchanged 의 Case 'flag' 와 동일하다.
    //     0 매출수금거래내역            → d_tb_da023w_01_01
    //     1 매출수금(동일기간)미수내역   → d_tb_da023w_01_03
    //     2 매출수금(별도기간)미수내역   → d_tb_da023w_01_04
    //     3 매출기간내 미수내역          → d_tb_da023w_01_05
    //     4 매출별수금내역(현대양식)     → d_tb_da023w_01_06
    //     5 거래처원장(입출금포함)       → d_tb_da026_06_totlist
    //
    //   수금기간(rsdate/redate) 규칙도 toolbar.ue_retrieve 를 그대로 따른다.
    //     · 화면 수금일자가 비면 rsdate='19000101', redate='29000101' (전 기간)
    //     · flag 3 만 rsdate 를 강제로 '19000101' 로 넘긴다
    //     · flag 0·4 는 divicd(부서) 를 넘기지 않는다 (dw_key 에서도 부서칸을 숨긴다)
    @GetMapping("/read")
    public AjaxResult getList(
            @RequestParam(value = "srchStartDt", required = false) String start,
            @RequestParam(value = "srchEndDt",   required = false) String end,
            @RequestParam(value = "rcvStartDt",  required = false) String rcvStart,
            @RequestParam(value = "rcvEndDt",    required = false) String rcvEnd,
            @RequestParam(value = "cltcd",       required = false) String cltcd,
            @RequestParam(value = "gubun",       required = false) String gubun,
            @RequestParam(value = "divicd",      required = false) String divicd,
            @RequestParam(value = "flag",        required = false, defaultValue = "0") String flag,
            @RequestParam(value = "spjangcd")                      String spjangcd) {

        start = stripDash(start);
        end   = stripDash(end);
        // 파워빌더는 수금일자가 비면 전 기간으로 본다 (ue_retrieve 의 19000101 / 29000101)
        String rs = blankTo(stripDash(rcvStart), "19000101");
        String re = blankTo(stripDash(rcvEnd),   "29000101");

        AjaxResult result = new AjaxResult();

        switch (flag == null ? "0" : flag) {
            case "1" -> result.data = compBalanceDetailService.getMisuList("03", start, end, rs, re,
                                        spjangcd, cltcd, gubun, divicd);
            case "2" -> result.data = compBalanceDetailService.getMisuList("04", start, end, rs, re,
                                        spjangcd, cltcd, gubun, divicd);
            // flag 3 은 수금 시작일을 강제로 19000101 로 넘긴다
            case "3" -> result.data = compBalanceDetailService.getMisuList("05", start, end, "19000101", re,
                                        spjangcd, cltcd, gubun, divicd);
            case "4" -> result.data = compBalanceDetailService.getHyundaiList(start, end, rs, re,
                                        spjangcd, cltcd, gubun, false);
            case "5" -> result.data = compBalanceDetailService.getLedgerList(start, end, spjangcd, cltcd);
            default  -> result.data = compBalanceDetailService.getList(start, end, spjangcd, cltcd, gubun);
        }

        return result;
    }

    private static String blankTo(String v, String def) {
        return (v == null || v.isBlank()) ? def : v;
    }

    private static String stripDash(String v) {
        return (v == null) ? null : v.replace("-", "");
    }
}
