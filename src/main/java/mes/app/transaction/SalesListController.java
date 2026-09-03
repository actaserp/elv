package mes.app.transaction;

import lombok.extern.slf4j.Slf4j;
import mes.app.transaction.service.SalesListService;
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
@RequestMapping("/api/sales/list")
public class SalesListController {

  @Autowired
  SalesListService salesListService;

  // 매출현황 (파워빌더 w_input_da026w — 메뉴명은 입금현황이지만 매출현황으로 쓴다)
  //   담당자(pernm)는 파워빌더가 화면 필터(dw_1.SetFilter)로 처리하므로 서버 조건이 아니다.
  //   그래서 여기에도 파라미터가 없다 — 화면에서 그리드를 거른다.
  @GetMapping("/read")
  public AjaxResult getSalesList(
      @RequestParam(value = "srchStartDt", required = false) String start,
      @RequestParam(value = "srchEndDt",   required = false) String end,
      @RequestParam(value = "cltcd",       required = false) String cltcd,
      @RequestParam(value = "accyn",       required = false) String accyn,
      @RequestParam(value = "billgubun",   required = false) String billgubun,
      @RequestParam(value = "bankcd",      required = false) String bankcd,
      @RequestParam(value = "gubun",       required = false) List<String> gubun,
      @RequestParam(value = "divicd",      required = false) String divicd,
      @RequestParam(value = "chk",         required = false) String chk,
      @RequestParam(value = "remark",      required = false) String remark,
      @RequestParam(value = "spjangcd")                      String spjangcd) {

    if (start != null) start = start.replace("-", "");
    if (end   != null) end   = end.replace("-", "");

    AjaxResult result = new AjaxResult();
    result.data = this.salesListService.getList(start, end, spjangcd, cltcd, accyn, billgubun,
                                                  bankcd, gubun, divicd, chk, remark);
    return result;
  }

  // 화면 왼쪽 매출구분 체크박스 목록 (TB_DA020)
  @GetMapping("/gubunList")
  public AjaxResult getGubunList(@RequestParam(value = "spjangcd") String spjangcd) {
    AjaxResult result = new AjaxResult();
    result.data = this.salesListService.getGubunList(spjangcd);
    return result;
  }

  // 은행(계좌) 목록 — TB_DA026.bankcd 는 은행코드+계좌코드 결합형('03B01')
  @GetMapping("/bankList")
  public AjaxResult getBankList(@RequestParam(value = "spjangcd") String spjangcd) {
    AjaxResult result = new AjaxResult();
    result.data = this.salesListService.getBankList(spjangcd);
    return result;
  }
}
