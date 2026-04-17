package mes.app.clock;

import mes.app.clock.service.ClockMemberService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.transaction.Transactional;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/clock/member")
public class ClockMemberController {

    @Autowired
    private ClockMemberService clockMemberService;

    // =========================================================
    // 휴가 목록 조회
    // =========================================================
    @GetMapping("/read")
    public AjaxResult getMemberList(
            @RequestParam(value = "start_date", required = false) String start_date,
            @RequestParam(value = "end_date",   required = false) String end_date,
            @RequestParam(value = "person_name", required = false) String person_name,
            @RequestParam(value = "spjangcd") String spjangcd,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();

        if (start_date != null && start_date.contains("-")) {
            start_date = start_date.replaceAll("-", "");
        }
        if (end_date != null && end_date.contains("-")) {
            end_date = end_date.replaceAll("-", "");
        }

        List<Map<String, Object>> items = this.clockMemberService.getMemberList(start_date, end_date, person_name, spjangcd);
        result.data = items;
        return result;
    }

    // =========================================================
    // 휴가 승인 저장
    // =========================================================
    @PostMapping("/save")
    @Transactional
    public AjaxResult saveMemberList(
            @RequestBody Map<String, Object> requestData,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();

        List<Map<String, Object>> dataList = (List<Map<String, Object>>) requestData.get("list");
        String spjangcd = (String) requestData.get("spjangcd");

        if (dataList == null || dataList.isEmpty()) {
            result.success = false;
            result.message = "저장할 데이터가 없습니다.";
            return result;
        }

        try {
            for (Map<String, Object> item : dataList) {
                clockMemberService.saveMember(item, spjangcd);
            }
            result.success = true;
        } catch (Exception e) {
            result.success = false;
            result.message = "저장 중 오류가 발생했습니다: " + e.getMessage();
        }

        return result;
    }

    // =========================================================
    // 휴가 승인 취소
    // =========================================================
    @PostMapping("/Cancel")
    @Transactional
    public AjaxResult CancelMemberList(
            @RequestBody Map<String, Object> requestData,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();

        List<Map<String, Object>> dataList = (List<Map<String, Object>>) requestData.get("list");

        try {
            for (Map<String, Object> item : dataList) {
                int id = ((Number) item.get("id")).intValue();
                clockMemberService.cancelMember(id);
            }
            result.success = true;
        } catch (Exception e) {
            result.success = false;
            result.message = "취소 중 오류가 발생했습니다: " + e.getMessage();
        }

        return result;
    }
}
