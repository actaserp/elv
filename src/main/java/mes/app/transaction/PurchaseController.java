package mes.app.transaction;


import mes.app.transaction.service.PurchaseService;
import mes.domain.model.AjaxResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 매입현황 (파워빌더 '비용발생현황' w_tb_ca640w)
 *
 * 조회조건은 파워빌더와 같다. 빈 값이면 전체를 뜻한다(파워빌더는 '%').
 */
@RestController
@RequestMapping("/api/purchase/list")
public class PurchaseController {

    private final PurchaseService purchaseService;

    public PurchaseController(PurchaseService purchaseService) {
        this.purchaseService = purchaseService;
    }

    private Map<String, Object> paramSet(
            String spjangcd, String frdate, String todate, String cltcd,
            String gubun, String divicd, String artcd, String taxreclafi, String bhflag) {

        Map<String, Object> p = new HashMap<>();
        p.put("spjangcd", spjangcd);
        p.put("searchfrdate", frdate == null ? "" : frdate.replaceAll("-", ""));
        p.put("searchtodate", todate == null ? "" : todate.replaceAll("-", ""));
        p.put("cltcd", cltcd);
        p.put("gubun", gubun);
        p.put("divicd", divicd);
        p.put("artcd", artcd);
        p.put("taxreclafi", taxreclafi);
        p.put("bhflag", bhflag);
        return p;
    }

    @GetMapping("/search")
    public AjaxResult searchList(
            @RequestParam String spjangcd,
            @RequestParam String searchfrdate,
            @RequestParam String searchtodate,
            @RequestParam(required = false, defaultValue = "") String cltcd,
            @RequestParam(required = false, defaultValue = "") String gubun,
            @RequestParam(required = false, defaultValue = "") String divicd,
            @RequestParam(required = false, defaultValue = "") String artcd,
            @RequestParam(required = false, defaultValue = "") String taxreclafi,
            @RequestParam(required = false, defaultValue = "") String bhflag
    ) {
        AjaxResult result = new AjaxResult();
        List<Map<String, Object>> list = purchaseService.getList(
                paramSet(spjangcd, searchfrdate, searchtodate, cltcd, gubun, divicd, artcd, taxreclafi, bhflag));
        result.data = list;
        return result;
    }

    // 집계현황 탭. 사업자번호는 사업체 DB(TB_XCLIENT)에 평문으로 있어 복호화하지 않는다.
    @GetMapping("/search2")
    public AjaxResult searchList2(
            @RequestParam String spjangcd,
            @RequestParam String searchfrdate2,
            @RequestParam String searchtodate2,
            @RequestParam(required = false, defaultValue = "") String cltcd2,
            @RequestParam(required = false, defaultValue = "") String gubun2,
            @RequestParam(required = false, defaultValue = "") String divicd2,
            @RequestParam(required = false, defaultValue = "") String artcd2,
            @RequestParam(required = false, defaultValue = "") String taxreclafi2,
            @RequestParam(required = false, defaultValue = "") String bhflag2
    ) {
        AjaxResult result = new AjaxResult();
        List<Map<String, Object>> list = purchaseService.getList2(
                paramSet(spjangcd, searchfrdate2, searchtodate2, cltcd2, gubun2, divicd2, artcd2, taxreclafi2, bhflag2));
        result.data = list;
        return result;
    }
}
