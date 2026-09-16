package mes.app.transaction;

import lombok.extern.slf4j.Slf4j;
import mes.app.transaction.service.YearEndCloseService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 매입매출년마감 (파워빌더 w_tb_da023_end + w_tb_ca640_end 를 탭 2개로 합친 화면)
 *
 * 기존 /api/definition/yearamt 는 sports 유산(본사 yearamt 테이블)이라 건드리지 않았다.
 */
@Slf4j
@RestController
@RequestMapping("/api/tran/yearamt")
public class YearEndCloseController {

    @Autowired
    YearEndCloseService yearEndCloseService;

    // 두 탭의 마감 여부
    @GetMapping("/status")
    public AjaxResult getStatus(@RequestParam("year") String year,
                                @RequestParam("spjangcd") String spjangcd) {
        AjaxResult result = new AjaxResult();
        try {
            result.data = yearEndCloseService.getStatus(spjangcd, year);
        } catch (IllegalStateException e) {
            result.success = false;
            result.message = e.getMessage();
        }
        return result;
    }

    // ── 미수금마감이월 ───────────────────────────────────────

    @GetMapping("/sales/read")
    public AjaxResult salesList(@RequestParam("year") String year,
                                @RequestParam("spjangcd") String spjangcd) {
        AjaxResult result = new AjaxResult();
        result.data = yearEndCloseService.getSalesList(spjangcd, year);
        return result;
    }

    @GetMapping("/sales/preview")
    public AjaxResult salesPreview(@RequestParam("year") String year,
                                   @RequestParam("spjangcd") String spjangcd) {
        AjaxResult result = new AjaxResult();
        result.data = yearEndCloseService.previewSales(spjangcd, year);
        return result;
    }

    @PostMapping("/sales/close")
    public AjaxResult salesClose(@RequestParam("year") String year,
                                 @RequestParam("spjangcd") String spjangcd) {
        AjaxResult result = new AjaxResult();
        try {
            int n = yearEndCloseService.closeSales(spjangcd, year);
            result.success = true;
            result.message = n + "건이 이월되었습니다.";
        } catch (IllegalStateException e) {
            result.success = false;
            result.message = e.getMessage();
        } catch (Exception e) {
            log.error("미수금 마감 오류", e);
            result.success = false;
            result.message = "마감 중 오류가 발생했습니다.";
        }
        return result;
    }

    @PostMapping("/sales/cancel")
    public AjaxResult salesCancel(@RequestParam("year") String year,
                                  @RequestParam("spjangcd") String spjangcd) {
        AjaxResult result = new AjaxResult();
        try {
            int n = yearEndCloseService.cancelSales(spjangcd, year);
            result.success = true;
            result.message = n + "건의 마감이 취소되었습니다.";
        } catch (IllegalStateException e) {
            result.success = false;
            result.message = e.getMessage();
        } catch (Exception e) {
            log.error("미수금 마감취소 오류", e);
            result.success = false;
            result.message = "마감취소 중 오류가 발생했습니다.";
        }
        return result;
    }

    // ── 미지급마감이월 ───────────────────────────────────────

    @GetMapping("/purchase/read")
    public AjaxResult purchaseList(@RequestParam("year") String year,
                                   @RequestParam("spjangcd") String spjangcd) {
        AjaxResult result = new AjaxResult();
        result.data = yearEndCloseService.getPurchaseList(spjangcd, year);
        return result;
    }

    @GetMapping("/purchase/preview")
    public AjaxResult purchasePreview(@RequestParam("year") String year,
                                      @RequestParam("spjangcd") String spjangcd) {
        AjaxResult result = new AjaxResult();
        result.data = yearEndCloseService.previewPurchase(spjangcd, year);
        return result;
    }

    @PostMapping("/purchase/close")
    public AjaxResult purchaseClose(@RequestParam("year") String year,
                                    @RequestParam("spjangcd") String spjangcd) {
        AjaxResult result = new AjaxResult();
        try {
            int n = yearEndCloseService.closePurchase(spjangcd, year);
            result.success = true;
            result.message = n + "건이 이월되었습니다.";
        } catch (IllegalStateException e) {
            result.success = false;
            result.message = e.getMessage();
        } catch (Exception e) {
            log.error("미지급 마감 오류", e);
            result.success = false;
            result.message = "마감 중 오류가 발생했습니다.";
        }
        return result;
    }

    @PostMapping("/purchase/cancel")
    public AjaxResult purchaseCancel(@RequestParam("year") String year,
                                     @RequestParam("spjangcd") String spjangcd) {
        AjaxResult result = new AjaxResult();
        try {
            int n = yearEndCloseService.cancelPurchase(spjangcd, year);
            result.success = true;
            result.message = n + "건의 마감이 취소되었습니다.";
        } catch (IllegalStateException e) {
            result.success = false;
            result.message = e.getMessage();
        } catch (Exception e) {
            log.error("미지급 마감취소 오류", e);
            result.success = false;
            result.message = "마감취소 중 오류가 발생했습니다.";
        }
        return result;
    }
}
