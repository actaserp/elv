package mes.app.mobile;

import mes.app.mobile.Service.CommuteLogService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * 앱 화면의 출퇴근 단계 기록 수신 (버튼 클릭·화면에서 막힘·요청·응답·통신 실패).
 *
 * MobileMainController 와 경로는 같지만 일부러 따로 뒀다.
 * 그쪽에는 @ApiProduct 가 붙어 있어 호출마다 사업체 API 사용량(과금 집계)이 올라가는데,
 * 이 기록 전송은 진단용이라 사용량에 넣지 않는다.
 */
@RestController
@RequestMapping("/api/mobile_main")
public class CommuteLogController {

	@Autowired
	CommuteLogService commuteLogService;

	@PostMapping("/commute_client_log")
	public AjaxResult commuteClientLog(@RequestBody List<Map<String, Object>> traces,
	                                   HttpServletRequest request, Authentication auth) {
		AjaxResult result = new AjaxResult();
		result.data = commuteLogService.recordClient(traces, request, auth);
		return result;
	}
}
