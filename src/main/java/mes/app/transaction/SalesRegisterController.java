package mes.app.transaction;

import mes.app.annotation.ApiProduct;

import lombok.extern.slf4j.Slf4j;
import mes.app.transaction.service.SalesRegisterService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 매출관리 (파워빌더 '매출등록' w_tb_da023)
 *
 * 1단계 범위: 헤더(TB_DA023) + 상세(TB_DA024) 등록·수정·삭제.
 * 세금계산서 생성, 팝빌·KTNET 연동, 수리완료·출고 연동은 넣지 않았다.
 *
 * 팝빌 전자세금계산서 쪽은 기존 SalesInvoiceController(/api/tran/sales)가 그대로 들고 있다.
 */
@Slf4j
@ApiProduct(ApiProduct.P05)
@RestController
@RequestMapping("/api/tran/sales_register")
public class SalesRegisterController {

    @Autowired
    SalesRegisterService salesRegisterService;

    // 헤더 목록
    @GetMapping("/read")
    public AjaxResult getList(
            @RequestParam(value = "start", required = false) String start,
            @RequestParam(value = "end", required = false) String end,
            @RequestParam(value = "keyword", required = false, defaultValue = "") String keyword,
            @RequestParam(value = "gubun", required = false, defaultValue = "") String gubun,
            @RequestParam(value = "billgubun", required = false, defaultValue = "") String billgubun,
            @RequestParam(value = "spjangcd") String spjangcd) {

        AjaxResult result = new AjaxResult();
        result.data = salesRegisterService.getList(spjangcd, start, end, keyword, gubun, billgubun);
        return result;
    }

    // 매출상세 탭
    @GetMapping("/detail")
    public AjaxResult getDetail(
            @RequestParam("misdate") String misdate,
            @RequestParam("misnum") String misnum,
            @RequestParam("cltcd") String cltcd,
            @RequestParam("spjangcd") String spjangcd) {

        AjaxResult result = new AjaxResult();
        result.data = salesRegisterService.getDetail(spjangcd, misdate, misnum, cltcd);
        return result;
    }

    // 거래명세표 탭
    @GetMapping("/pcode")
    public AjaxResult getPcodeList(
            @RequestParam("misdate") String misdate,
            @RequestParam("misnum") String misnum,
            @RequestParam("cltcd") String cltcd,
            @RequestParam("spjangcd") String spjangcd) {

        AjaxResult result = new AjaxResult();
        result.data = salesRegisterService.getPcodeList(spjangcd, misdate, misnum, cltcd);
        return result;
    }

    // 현장 목록 (현장 선택 팝업)
    @GetMapping("/actcd")
    public AjaxResult getActList(
            @RequestParam(value = "keyword", required = false, defaultValue = "") String keyword,
            @RequestParam("spjangcd") String spjangcd) {

        AjaxResult result = new AjaxResult();
        result.data = salesRegisterService.getActList(spjangcd, keyword);
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
            result.data = salesRegisterService.save(spjangcd, header, details, userId);
            result.success = true;
            result.message = "저장되었습니다.";
        } catch (IllegalStateException e) {
            result.success = false;
            result.message = e.getMessage();
        } catch (Exception e) {
            log.error("매출 저장 오류", e);
            result.success = false;
            result.message = "저장 중 오류가 발생했습니다.";
        }
        return result;
    }

    // 삭제
    @PostMapping("/delete")
    public AjaxResult delete(
            @RequestParam("misdate") String misdate,
            @RequestParam("misnum") String misnum,
            @RequestParam("cltcd") String cltcd,
            @RequestParam("spjangcd") String spjangcd) {

        AjaxResult result = new AjaxResult();
        try {
            salesRegisterService.delete(spjangcd, misdate, misnum, cltcd);
            result.success = true;
            result.message = "삭제되었습니다.";
        } catch (IllegalStateException e) {
            result.success = false;
            result.message = e.getMessage();
        } catch (Exception e) {
            log.error("매출 삭제 오류", e);
            result.success = false;
            result.message = "삭제 중 오류가 발생했습니다.";
        }
        return result;
    }
}
