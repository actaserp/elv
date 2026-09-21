package mes.app.transaction;

import mes.app.annotation.ApiProduct;

import lombok.extern.slf4j.Slf4j;
import mes.app.transaction.service.PurchaseInvoiceService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 매입관리 (파워빌더 '비용등록' w_tb_ca640)
 *
 * 1단계 범위: 헤더(TB_CA640) + 상세(TB_CA641) 등록·수정·삭제.
 * 자동지급처리, 부가세 자료 생성, 자재입고 연동은 넣지 않았다.
 */
@Slf4j
@ApiProduct(ApiProduct.P05)
@RestController
@RequestMapping("/api/tran/purchase")
public class PurchaseInvoiceController {

    @Autowired
    PurchaseInvoiceService purchaseInvoiceService;

    // 헤더 목록
    @GetMapping("/read")
    public AjaxResult getList(
            @RequestParam(value = "start", required = false) String start,
            @RequestParam(value = "end", required = false) String end,
            @RequestParam(value = "keyword", required = false, defaultValue = "") String keyword,
            @RequestParam(value = "gubun", required = false, defaultValue = "") String gubun,
            @RequestParam(value = "bhflag", required = false, defaultValue = "") String bhflag,
            @RequestParam(value = "spjangcd") String spjangcd) {

        AjaxResult result = new AjaxResult();
        result.data = purchaseInvoiceService.getList(spjangcd, start, end, keyword, gubun, bhflag);
        return result;
    }

    // 비용상세 탭
    @GetMapping("/detail")
    public AjaxResult getDetail(
            @RequestParam("mijdate") String mijdate,
            @RequestParam("mijnum") String mijnum,
            @RequestParam(value = "spjangcd") String spjangcd) {

        AjaxResult result = new AjaxResult();
        result.data = purchaseInvoiceService.getDetail(spjangcd, mijdate, mijnum);
        return result;
    }

    // 거래명세표 탭
    @GetMapping("/pcode")
    public AjaxResult getPcodeList(
            @RequestParam("mijdate") String mijdate,
            @RequestParam("mijnum") String mijnum,
            @RequestParam(value = "spjangcd") String spjangcd) {

        AjaxResult result = new AjaxResult();
        result.data = purchaseInvoiceService.getPcodeList(spjangcd, mijdate, mijnum);
        return result;
    }

    // 저장 (헤더 + 상세)
    @SuppressWarnings("unchecked")
    @PostMapping("/save")
    public AjaxResult save(@RequestBody Map<String, Object> form, Authentication auth) {
        AjaxResult result = new AjaxResult();

        String spjangcd = (String) form.get("spjangcd");
        Map<String, Object> header = (Map<String, Object>) form.get("header");
        List<Map<String, Object>> details = (List<Map<String, Object>>) form.get("details");

        String userId = "";
        if (auth != null && auth.getPrincipal() instanceof User user && user.getUserProfile() != null) {
            userId = user.getUserProfile().getName();
        }

        try {
            result.data = purchaseInvoiceService.save(spjangcd, header, details, userId);
            result.success = true;
            result.message = "저장되었습니다.";
        } catch (IllegalStateException e) {
            result.success = false;
            result.message = e.getMessage();
        } catch (Exception e) {
            log.error("매입 저장 오류", e);
            result.success = false;
            result.message = "저장 중 오류가 발생했습니다.";
        }
        return result;
    }

    // 삭제
    @PostMapping("/delete")
    public AjaxResult delete(
            @RequestParam("mijdate") String mijdate,
            @RequestParam("mijnum") String mijnum,
            @RequestParam("spjangcd") String spjangcd) {

        AjaxResult result = new AjaxResult();
        try {
            purchaseInvoiceService.delete(spjangcd, mijdate, mijnum);
            result.success = true;
            result.message = "삭제되었습니다.";
        } catch (IllegalStateException e) {
            result.success = false;
            result.message = e.getMessage();
        } catch (Exception e) {
            log.error("매입 삭제 오류", e);
            result.success = false;
            result.message = "삭제 중 오류가 발생했습니다.";
        }
        return result;
    }
}
