package mes.app.account_management;

import mes.app.annotation.ApiProduct;

import lombok.extern.slf4j.Slf4j;
import mes.app.account_management.service.BankManagementService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 계좌번호 관리 (파워빌더 w_s004, 사업체 DB TB_AA040)
 */
@Slf4j
@ApiProduct(ApiProduct.P05)
@RestController
@RequestMapping("/api/account_management/bank_management")
public class BankManagementController {

	@Autowired
	BankManagementService bankManagementService;

	@GetMapping("/read")
	public AjaxResult getRegiAccountList(
			@RequestParam(value = "bankid", required = false) String bankid,
			@RequestParam(value = "accountnum", required = false) String accountnum,
			@RequestParam(value = "spjangcd") String spjangcd) {

		AjaxResult result = new AjaxResult();
		result.data = bankManagementService.getAccountList(bankid, accountnum, spjangcd);
		return result;
	}

	@PostMapping("/save")
	public AjaxResult save(@RequestParam Map<String, Object> param) {
		AjaxResult result = new AjaxResult();
		try {
			String bankcd = bankManagementService.save(param, String.valueOf(param.get("spjangcd")));
			result.success = true;
			result.message = "저장되었습니다.";
			result.data = Map.of("bankcd", bankcd);
		} catch (IllegalStateException e) {
			result.success = false;
			result.message = e.getMessage();
		} catch (Exception e) {
			log.error("계좌 저장 오류", e);
			result.success = false;
			result.message = "저장 중 오류가 발생했습니다.";
		}
		return result;
	}

	@PostMapping("/delete")
	public AjaxResult delete(
			@RequestParam(value = "bankid", required = false) String bankid,
			@RequestParam(value = "bankcd", required = false) String bankcd,
			@RequestParam(value = "accountNumber", required = false) String accountNumber,
			@RequestParam(value = "spjangcd") String spjangcd) {

		AjaxResult result = new AjaxResult();
		try {
			bankManagementService.delete(bankid, bankcd, accountNumber, spjangcd);
			result.success = true;
			result.message = "삭제되었습니다.";
		} catch (IllegalStateException e) {
			result.success = false;
			result.message = e.getMessage();
		} catch (Exception e) {
			log.error("계좌 삭제 오류", e);
			result.success = false;
			result.message = "삭제 중 오류가 발생했습니다.";
		}
		return result;
	}
}
