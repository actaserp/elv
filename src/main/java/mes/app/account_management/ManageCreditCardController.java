package mes.app.account_management;

import lombok.extern.slf4j.Slf4j;
import mes.app.account_management.service.BaroCardService;
import mes.app.account_management.service.ManageCreditCardService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@Slf4j
@RequestMapping("/api/account_management/manageCreditCard") //신용카드 등록
public class ManageCreditCardController {

	@Autowired
	ManageCreditCardService cardService;

	@Autowired
	BaroCardService baroCardService;

	@GetMapping("/read")
	public AjaxResult getList (@RequestParam(value = "txtcardnm", required = false) String txtcardnm,
														 @RequestParam(value = "txtcardnum", required = false) String txtcardnum) {

		List<Map<String, Object>> items = this.cardService.getList(txtcardnm, txtcardnum);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;
	}

	@PostMapping("/RegistCardEx")
	public AjaxResult baroNewCardSave(@RequestParam Map<String,Object> param,
																		Authentication auth){

		AjaxResult result = new AjaxResult();

		try {
			result = baroCardService.baroNewCardSave(param, auth);
		} catch (Exception e) {
			result.success = false;
			result.message = "카드등록 중 오류가 발생했습니다. " + e.getMessage();
			log.error("RegistCardEx error", e);
		}

		return result;
	}

	@GetMapping("/baroUrl")
	public AjaxResult getCardManagementURL(@RequestParam Map<String, Object> param) {
		AjaxResult result = new AjaxResult();

		try {
			result = baroCardService.getCardManagementURL(param);
		} catch (Exception e) {
			result.success = false;
			result.message ="카드관리 URL 조회 중 오류가 발생했습니다. " + e.getMessage();
		}

		return result;
	}

	@PostMapping("/StopCard")
	public AjaxResult StopCard(@RequestParam Map<String, Object> param) {
		AjaxResult result = new AjaxResult();

		try {
			result = baroCardService.getStopCard(param);
		} catch (Exception e) {
			result.success = false;
			result.message = "카드연동 해지 중 오류가 발생했습니다. " + e.getMessage();
		}

		return result;
	}

	@PostMapping("/save")
	@ResponseBody
	public AjaxResult cardSave(@RequestParam Map<String, Object> param) {
		AjaxResult result = new AjaxResult();

		try {
			cardService.save(param);
			result.success = true;
			result.message = "저장되었습니다.";
		} catch (IllegalStateException e) {
			result.success = false;
			result.message = e.getMessage();
		} catch (Exception e) {
			log.error("신용카드 저장 오류", e);
			result.success = false;
			result.message = "저장 중 오류가 발생했습니다.";
		}

		return result;
	}

	// 결제계좌 선택 목록 (사업체 계좌 TB_AA040)
	@GetMapping("/accounts")
	public AjaxResult getAccounts() {
		AjaxResult result = new AjaxResult();
		result.data = cardService.getAccountList();
		return result;
	}

	// 삭제. 예전에는 /api/transaction/manageCreditCard/delete(옛 화면용)를 호출해서
	// 암호화된 카드번호로 JPA 삭제를 시도했고, 평문으로 저장된 tb_iz010 행을 찾지 못했다.
	@PostMapping("/delete")
	public AjaxResult cardDelete(@RequestParam("cardnum") String cardnum) {
		AjaxResult result = new AjaxResult();

		try {
			cardService.delete(cardnum);
			result.success = true;
			result.message = "삭제되었습니다.";
		} catch (IllegalStateException e) {
			result.success = false;
			result.message = e.getMessage();
		} catch (Exception e) {
			log.error("신용카드 삭제 오류", e);
			result.success = false;
			result.message = "삭제 중 오류가 발생했습니다.";
		}

		return result;
	}
}
