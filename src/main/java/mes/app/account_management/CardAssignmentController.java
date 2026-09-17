package mes.app.account_management;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import mes.app.account_management.service.CardPaymentService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 카드지급 (파워빌더 w_tb_ca642_01_card)
 *
 * 예전 /read · /createPaymentSlip · /PaymentCancelSlip 은 사업체 DB 에 없는 tb_aa009 를 써서
 * 어디서도 동작하지 않았다. 지금은 CardPaymentService 가 TB_CA642 로 지급한다.
 */
@Slf4j
@RestController
@RequestMapping("/api/account_management/card_assignment")
public class CardAssignmentController {

	@Autowired
	CardPaymentService cardPaymentService;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@GetMapping("/cardcos")
	public AjaxResult getCardCompanies() {
		AjaxResult result = new AjaxResult();
		result.data = cardPaymentService.getCardCompanies();
		return result;
	}

	// 미지급 목록
	@GetMapping("/unpaid")
	public AjaxResult getUnpaid(@RequestParam("cardco") String cardco,
	                            @RequestParam("stdate") String stdate,
	                            @RequestParam("enddate") String enddate,
	                            @RequestParam(value = "onlyBalance", required = false, defaultValue = "1") String onlyBalance,
	                            @RequestParam("spjangcd") String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.data = cardPaymentService.getUnpaid(spjangcd, cardco, stdate, enddate, onlyBalance);
		return result;
	}

	// 지급 목록
	@GetMapping("/payments")
	public AjaxResult getPayments(@RequestParam("cardco") String cardco,
	                              @RequestParam("stdate") String stdate,
	                              @RequestParam("enddate") String enddate,
	                              @RequestParam("spjangcd") String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.data = cardPaymentService.getPayments(spjangcd, cardco, stdate, enddate);
		return result;
	}

	// 지급 상세
	@GetMapping("/payment_detail")
	public AjaxResult getPaymentDetail(@RequestParam("cardco") String cardco,
	                                   @RequestParam("snddate") String snddate,
	                                   @RequestParam("sndnum") String sndnum,
	                                   @RequestParam("spjangcd") String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.data = cardPaymentService.getPaymentDetail(spjangcd, cardco, snddate, sndnum);
		return result;
	}

	// 자동/수동계산 미리보기. 저장 때 서버가 같은 계산을 다시 한다
	@PostMapping("/allocate")
	public AjaxResult allocate(@RequestBody Map<String, Object> body) {
		AjaxResult result = new AjaxResult();
		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> header = (Map<String, Object>) body.get("header");
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("rows");
			result.data = cardPaymentService.allocate(header, rows == null ? List.of() : rows);
		} catch (Exception e) {
			log.error("카드지급 배분 계산 오류", e);
			result.success = false;
			result.message = "지급액 계산 중 오류가 발생했습니다.";
		}
		return result;
	}

	@PostMapping("/save")
	public AjaxResult save(@RequestBody Map<String, Object> body, Authentication auth) {
		AjaxResult result = new AjaxResult();
		String userId = auth != null && auth.getPrincipal() instanceof User u ? u.getUsername() : "";
		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> header = (Map<String, Object>) body.get("header");
			List<Map<String, Object>> keys = objectMapper.convertValue(body.get("keys"), new TypeReference<>() {});
			result.data = cardPaymentService.save(
					str(body.get("spjangcd")), str(body.get("cardco")), header, str(body.get("mode")),
					keys, str(body.get("stdate")), str(body.get("enddate")), userId);
			result.success = true;
			result.message = "지급이 저장되었습니다.";
		} catch (IllegalStateException e) {
			result.success = false;
			result.message = e.getMessage();
		} catch (Exception e) {
			log.error("카드지급 저장 오류", e);
			result.success = false;
			result.message = "지급 저장 중 오류가 발생했습니다.";
		}
		return result;
	}

	@PostMapping("/delete")
	public AjaxResult delete(@RequestParam("cardco") String cardco,
	                         @RequestParam("snddate") String snddate,
	                         @RequestParam("sndnum") String sndnum,
	                         @RequestParam("spjangcd") String spjangcd) {
		AjaxResult result = new AjaxResult();
		try {
			int n = cardPaymentService.delete(spjangcd, cardco, snddate, sndnum);
			result.success = true;
			result.message = "지급 " + n + "건이 취소되었습니다.";
		} catch (IllegalStateException e) {
			result.success = false;
			result.message = e.getMessage();
		} catch (Exception e) {
			log.error("카드지급 취소 오류", e);
			result.success = false;
			result.message = "지급 취소 중 오류가 발생했습니다.";
		}
		return result;
	}

	private static String str(Object o) {
		return o == null ? "" : String.valueOf(o).trim();
	}
}
