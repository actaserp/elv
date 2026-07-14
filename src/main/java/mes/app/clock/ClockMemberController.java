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

        // 로그인 사용자 정보 추출
        User user = (User) auth.getPrincipal();
        String appuserid = user.getUsername();                          // 로그인 ID
        String appperid  = appuserid.replaceFirst("^p", "");           // p 제거한 perid

        try {
            for (Map<String, Object> item : dataList) {
                String msg = clockMemberService.saveMember(item, spjangcd, appperid, appuserid);
                if (msg != null) {
                    result.success = false;
                    result.message = msg;
                    return result;   // 하나라도 실패(이미 승인됨 등)면 중단
                }
            }
            result.success = true;
        } catch (Exception e) {
            result.success = false;
            result.message = "저장 중 오류가 발생했습니다: " + e.getMessage();
        }

        return result;
    }

    // =========================================================
    // 휴가 일괄 삭제
    // =========================================================
    @PostMapping("/bulk_delete")
    public AjaxResult bulkDelete(
            @RequestBody Map<String, Object> requestData,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        try {
            List<Object> ids = (List<Object>) requestData.get("ids");
            if (ids == null || ids.isEmpty()) {
                result.success = false;
                result.message = "삭제할 데이터가 없습니다.";
                return result;
            }
            clockMemberService.bulkDeleteMember(ids);
            result.success = true;
            result.message = ids.size() + "건이 삭제되었습니다.";
        } catch (Exception e) {
            result.success = false;
            result.message = "일괄 삭제 중 오류가 발생하였습니다: " + e.getMessage();
        }
        return result;
    }

    // =========================================================
    // 휴가 일괄 등록 (Excel Upload)
    // =========================================================
    @PostMapping("/bulk_insert")
    public AjaxResult bulkInsertMember(
            @RequestBody Map<String, Object> requestData,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        try {
            List<Map<String, Object>> list = (List<Map<String, Object>>) requestData.get("list");
            String spjangcd = (String) requestData.get("spjangcd");

            if (list == null || list.isEmpty()) {
                result.success = false;
                result.message = "등록할 데이터가 없습니다.";
                return result;
            }

            // 등록자 = 로그인 사용자 (일괄등록도 로그인한 사람)
            User user = (User) auth.getPrincipal();
            String induserid = user.getUsername();
            String inperid   = induserid.replaceFirst("^p", "");
            clockMemberService.bulkInsertMember(list, spjangcd, induserid, inperid);
            result.success = true;
            result.message = list.size() + "건이 등록되었습니다.";
        } catch (Exception e) {
            result.success = false;
            result.message = "일괄 등록 중 오류가 발생하였습니다: " + e.getMessage();
        }
        return result;
    }

    // =========================================================
    // 휴가 임의 등록
    // =========================================================
    @PostMapping("/insert")
    public AjaxResult insertMember(
            @RequestBody Map<String, Object> requestData,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        try {
            String spjangcd = (String) requestData.get("spjangcd");
            // 등록자 = 로그인 사용자
            User user = (User) auth.getPrincipal();
            String induserid = user.getUsername();                 // 로그인 ID
            String inperid   = induserid.replaceFirst("^p", "");   // p 제거한 perid
            clockMemberService.insertMember(requestData, spjangcd, induserid, inperid);
            result.success = true;
            result.message = "등록되었습니다.";
        } catch (Exception e) {
            result.success = false;
            result.message = "등록 중 오류가 발생했습니다: " + e.getMessage();
        }
        return result;
    }

    // =========================================================
    // 휴가 수정
    // =========================================================
    @PostMapping("/update")
    public AjaxResult updateMember(
            @RequestBody Map<String, Object> requestData,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        try {
            String msg = clockMemberService.updateMember(requestData);
            if (msg != null) {
                result.success = false;
                result.message = msg;
                return result;
            }
            result.success = true;
            result.message = "수정되었습니다.";
        } catch (Exception e) {
            result.success = false;
            result.message = "수정 중 오류가 발생했습니다: " + e.getMessage();
        }
        return result;
    }

    // =========================================================
    // 휴가 삭제
    // =========================================================
    @PostMapping("/delete")
    public AjaxResult deleteMember(
            @RequestBody Map<String, Object> requestData,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        try {
            int id = ((Number) requestData.get("id")).intValue();
            String msg = clockMemberService.deleteMember(id);
            if (msg != null) {
                result.success = false;
                result.message = msg;
                return result;
            }
            result.success = true;
            result.message = "삭제되었습니다.";
        } catch (Exception e) {
            result.success = false;
            result.message = "삭제 중 오류가 발생했습니다: " + e.getMessage();
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
