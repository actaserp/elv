package mes.app.mobile;

import mes.app.mobile.Service.AttendanceCurrentService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/attendance_current")
public class AttendanceCurrentController {

    @Autowired
    AttendanceCurrentService attendanceCurrentService;

    // 개인별 휴가 현황 조회
    @GetMapping("/read")
    public AjaxResult getUserInfo(
            @RequestParam(value = "workcd", required = false) String workcd,
            @RequestParam(value = "searchYear") String searchYear,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        int personId = user.getPersonid();

        // 개인별 연차정보 조회
        Map<String, Object> annInfo = attendanceCurrentService.getAnnInfo(personId);
        if (annInfo != null) {
            String rtdate = (String) annInfo.get("rtdate");
            annInfo.put("rtdate", rtdate.substring(0, 4) + "." + rtdate.substring(4, 6) + "." + rtdate.substring(6));
        }

        // 개인별 휴가정보 조회
        List<Map<String, Object>> vacInfo = attendanceCurrentService.getVacInfo(workcd, searchYear, personId);
        for (Map<String, Object> vacDetail : vacInfo) {
            String reqdate  = (String) vacDetail.get("reqdate");
            String frdate   = (String) vacDetail.get("frdate");
            String todate   = (String) vacDetail.get("todate");
            String yearflag = (String) vacDetail.get("yearflag");
            vacDetail.put("reqdate",  reqdate.substring(0, 4) + "." + reqdate.substring(4, 6) + "." + reqdate.substring(6));
            vacDetail.put("frdate",   frdate.substring(0, 4)  + "." + frdate.substring(4, 6)  + "." + frdate.substring(6));
            vacDetail.put("todate",   todate.substring(0, 4)  + "." + todate.substring(4, 6)  + "." + todate.substring(6));
            vacDetail.put("yearflag", "1".equals(yearflag) ? "O" : "-");
        }

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("annInfo", annInfo);
        resultMap.put("vacInfo", vacInfo);
        result.data = resultMap;
        return result;
    }

    // 휴가정보 수정
    @PostMapping("/updateAttendance")
    public AjaxResult updateAttendance(
            @RequestParam(value = "vacId", required = false) Integer vacId,
            @RequestParam(value = "attKind", required = false) String attKind,
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "startTime", required = false) String startTime,
            @RequestParam(value = "endDate", required = false) String endDate,
            @RequestParam(value = "endTime", required = false) String endTime,
            @RequestParam(value = "isAnnual", required = false) String isAnnual,
            @RequestParam(value = "useDate", required = false) BigDecimal useDate,
            @RequestParam(value = "remark", required = false) String remark,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();

        // [기존] tbPb204Repository.findById(vacId) 대체
        Map<String, Object> existing = attendanceCurrentService.selectTbPb204ById(vacId);
        String formattedStartDate = startDate.replaceAll("-", "");
        String formattedEndDate   = endDate.replaceAll("-", "");

        if (existing != null && !existing.isEmpty()) {
            // [기존] tbPb204Repository.save(savedtbPb204) 대체
            attendanceCurrentService.updateTbPb204(
                    vacId,
                    formattedStartDate,
                    startTime,
                    formattedEndDate,
                    endTime,
                    useDate,
                    attKind,
                    remark,
                    isAnnual
            );
            result.message = "휴가수정이 완료되었습니다.";
            result.data = vacId;
        } else {
            System.out.println("해당 ID로 데이터를 찾을 수 없습니다.");
            result.message = "해당 데이터를 찾을 수 없습니다.";
        }
        return result;
    }

    // 휴가정보 삭제
    @PostMapping("/deleteAttendance")
    public AjaxResult deleteAttendance(
            @RequestParam(value = "vacId", required = false) Integer vacId,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        String spjangcd = user.getSpjangcd();

        // [기존] tbPb204Repository.findById(vacId) 대체
        Map<String, Object> existing = attendanceCurrentService.selectTbPb204ById(vacId);

        if (existing != null && !existing.isEmpty()) {
            String appnum = (String) existing.get("appnum");

            // [기존] tbE080Repository.deleteById(tbE080Pk) 대체
            Map<String, Object> tb080 = attendanceCurrentService.getAppInfo(appnum);
            if (tb080 != null && !tb080.isEmpty()) {
                attendanceCurrentService.deleteTbE080(appnum, spjangcd);
            }

            // [기존] tbPb204Repository.delete(savedtbPb204) 대체
            attendanceCurrentService.deleteTbPb204(vacId);
            result.message = "휴가삭제가 완료되었습니다.";
        } else {
            System.out.println("해당 ID로 데이터를 찾을 수 없습니다.");
            result.message = "해당 데이터를 찾을 수 없습니다.";
        }
        return result;
    }

    // 날짜 시간 분리 메서드
    private Map<String, String> extractDateTimeParts(String dateTime) {
        Map<String, String> dateTimeParts = new HashMap<>();
        if (dateTime != null && dateTime.contains("T")) {
            String[] parts = dateTime.split("T");
            dateTimeParts.put("date", parts[0].replaceAll("-", ""));
            dateTimeParts.put("time", parts[1]);
        } else {
            dateTimeParts.put("date", null);
            dateTimeParts.put("time", null);
        }
        return dateTimeParts;
    }

    // 연도 목록 조회
    @GetMapping("/years")
    public AjaxResult getAvailableYears(Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        String personidStr = String.valueOf(user.getPersonid());

        // [기존] tbPb204Repository.findDistinctYearsByPersonId(personidStr) 대체
        List<String> years = attendanceCurrentService.getDistinctYears(personidStr);
        result.data = years;
        return result;
    }
}
