package mes.app.transaction;

import mes.app.annotation.ApiProduct;

import lombok.extern.slf4j.Slf4j;
import mes.app.transaction.service.ExpenseAccountSetupService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 비용항목등록 (파워빌더 w_tb_ca647)
 *
 * 대분류 TB_CA647 + 세부항목 TB_CA648, 둘 다 사업체 DB 다.
 * 예전에는 대분류가 본사 sys_code, 세부 저장이 JPA(본사 tb_ca648)로 갈라져 있었다.
 */
@Slf4j
@ApiProduct(ApiProduct.P05)
@RestController
@RequestMapping("/api/transaction/ExpenseAccountSetup")
public class ExpenseAccountSetupController {

  @Autowired
  ExpenseAccountSetupService accountSetupService;

  // 왼쪽 그리드 — 대분류 목록
  @GetMapping("/read")
  public AjaxResult getExpenseAccountList(
          @RequestParam(value = "txtDescription", required = false, defaultValue = "") String keyword,
          @RequestParam(value = "spjangcd") String spjangcd) {

    AjaxResult result = new AjaxResult();
    result.data = accountSetupService.getExpenseAccountList(spjangcd, keyword);
    return result;
  }

  // 오른쪽 그리드 — 대분류 아래 세부항목
  @GetMapping("/readDetail")
  public AjaxResult getExpenseAccountDetail(
          @RequestParam(value = "groupCode") String groupCode,
          @RequestParam(value = "spjangcd") String spjangcd) {

    AjaxResult result = new AjaxResult();
    result.data = accountSetupService.getExpenseAccountDetail(groupCode, spjangcd);
    return result;
  }

  // 대분류 + 세부항목 한 번에 저장
  @SuppressWarnings("unchecked")
  @PostMapping("/save")
  public AjaxResult saveExpenseItems(@RequestBody Map<String, Object> payload, Authentication auth) {
    AjaxResult result = new AjaxResult();

    String spjangcd = (String) payload.get("spjangcd");
    String gartcd   = (String) payload.get("gartcd");
    String gartnm   = (String) payload.get("gart_name");
    String remark   = (String) payload.get("remark");

    String userId = "";
    if (auth != null && auth.getPrincipal() instanceof User user && user.getUserProfile() != null) {
      userId = user.getUserProfile().getName();
    }

    try {
      accountSetupService.saveGroup(spjangcd, gartcd, gartnm, remark, null);

      List<Map<String, Object>> details = (List<Map<String, Object>>) payload.get("details");
      if (details != null) {
        for (Map<String, Object> row : details) {
          String rowGartcd = row.get("gartcd") != null ? (String) row.get("gartcd") : gartcd;
          accountSetupService.saveItem(spjangcd, rowGartcd, row, userId);
        }
      }

      result.success = true;
      result.message = "저장되었습니다.";
    } catch (IllegalStateException e) {
      result.success = false;
      result.message = e.getMessage();
    } catch (Exception e) {
      log.error("비용항목 저장 오류", e);
      result.success = false;
      result.message = "저장 중 오류가 발생했습니다.";
    }
    return result;
  }

  // 행 삭제
  @PostMapping("/delete")
  public AjaxResult deleteData(@RequestParam("artcd") String artcd,
                               @RequestParam("gartcd") String gartcd,
                               @RequestParam("spjangcd") String spjangcd) {
    AjaxResult result = new AjaxResult();
    try {
      accountSetupService.deleteItem(spjangcd, gartcd, artcd);
      result.success = true;
      result.message = "삭제되었습니다.";
    } catch (IllegalStateException e) {
      result.success = false;
      result.message = e.getMessage();
    } catch (Exception e) {
      log.error("비용항목 삭제 오류", e);
      result.success = false;
      result.message = "삭제 중 오류가 발생했습니다.";
    }
    return result;
  }

  // 그룹 삭제
  @PostMapping("/deleteGroup")
  public AjaxResult deleteGroup(@RequestBody Map<String, Object> param) {
    AjaxResult result = new AjaxResult();
    try {
      accountSetupService.deleteGroup((String) param.get("spjangcd"), (String) param.get("gartcd"));
      result.success = true;
      result.message = "삭제되었습니다.";
    } catch (IllegalStateException e) {
      result.success = false;
      result.message = e.getMessage();
    } catch (Exception e) {
      log.error("비용항목 그룹 삭제 오류", e);
      result.success = false;
      result.message = "삭제 중 오류가 발생했습니다.";
    }
    return result;
  }
}
