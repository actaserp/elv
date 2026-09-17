package mes.app.account_management;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import mes.app.account_management.service.CardExpenseService;
import mes.app.account_management.service.CardHistoryService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 카드내역 (파워빌더 카드내역가져오기 + 카드거래정보등록)
 *
 * 바로빌 수집(/baroCardList, /requestCardHistory)은 CardHistoryService, 나머지는 CardExpenseService.
 * 예전 /read · /saveSelected · /createSlip · /cancelSlip · /find* 는 sports 의 예산회계 구조(사업·관·항·목)라
 * 사업체 DB 에 컬럼·테이블이 없어 어디서도 동작하지 않았다.
 */
@Slf4j
@RestController
@RequestMapping("/api/account_management/card_history")
public class CardHistoryController {

	@Autowired
	CardHistoryService cardHistoryService;

	@Autowired
	CardExpenseService cardExpenseService;

	private final ObjectMapper objectMapper = new ObjectMapper();

	// 등록 카드 목록
	@GetMapping("/cards")
	public AjaxResult getCards(@RequestParam("spjangcd") String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.data = cardExpenseService.getCards(spjangcd);
		return result;
	}

	// 카드 사용내역
	@GetMapping("/read")
	public AjaxResult getList(@RequestParam("frdate") String frdate,
	                          @RequestParam("todate") String todate,
	                          @RequestParam(value = "cardnum", required = false, defaultValue = "") String cardnum,
	                          @RequestParam(value = "flag", required = false, defaultValue = "") String flag,
	                          @RequestParam("spjangcd") String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.data = cardExpenseService.getList(spjangcd, frdate, todate, cardnum, flag);
		return result;
	}

	// 바로빌 연동 카드 목록 (수집 팝업)
	@GetMapping("/baroCardList")
	public AjaxResult getBaroCardList() {
		AjaxResult result = new AjaxResult();
		result.data = cardHistoryService.getbaroCardList();
		return result;
	}

	// 바로빌 카드내역 수집
	@PostMapping("/requestCardHistory")
	public AjaxResult baroCardHistory(@RequestParam Map<String, Object> param, Authentication auth) {
		return cardHistoryService.requestCardHistory(param, auth);
	}

	// 비용항목·계정·거래처 등 입력칸 저장
	@PostMapping("/saveInfo")
	public AjaxResult saveInfo(@RequestParam("spjangcd") String spjangcd, @RequestParam("items") String items) {
		return run(() -> {
			int n = cardExpenseService.saveInfo(spjangcd, parse(items));
			return n + "건 저장되었습니다.";
		}, "저장");
	}

	// 비용처리
	@PostMapping("/process")
	public AjaxResult process(@RequestParam("spjangcd") String spjangcd, @RequestParam("items") String items,
	                          Authentication auth) {
		String userId = auth != null && auth.getPrincipal() instanceof User u ? u.getUsername() : "";
		return run(() -> {
			int n = cardExpenseService.process(spjangcd, parse(items), userId);
			return n + "건 처리하였습니다.";
		}, "비용처리");
	}

	// 비용취소
	@PostMapping("/cancel")
	public AjaxResult cancel(@RequestParam("spjangcd") String spjangcd, @RequestParam("items") String items) {
		return run(() -> {
			int n = cardExpenseService.cancel(spjangcd, parse(items));
			return n + "건 취소되었습니다.";
		}, "비용취소");
	}

	private List<Map<String, Object>> parse(String json) {
		try {
			return objectMapper.readValue(json, new TypeReference<>() {});
		} catch (Exception e) {
			throw new IllegalStateException("요청 형식이 올바르지 않습니다.");
		}
	}

	private AjaxResult run(Supplier<String> work, String label) {
		AjaxResult result = new AjaxResult();
		try {
			result.message = work.get();
			result.success = true;
		} catch (IllegalStateException e) {
			result.success = false;
			result.message = e.getMessage();
		} catch (Exception e) {
			log.error("카드내역 {} 오류", label, e);
			result.success = false;
			result.message = label + " 중 오류가 발생했습니다.";
		}
		return result;
	}
}
