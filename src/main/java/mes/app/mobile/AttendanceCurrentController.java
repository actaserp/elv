package mes.app.mobile;

import lombok.extern.slf4j.Slf4j;
import mes.app.annotation.ApiProduct;
import mes.app.common.TenantUserService;
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

@Slf4j
@ApiProduct(ApiProduct.P02)
@RestController
@RequestMapping("/api/attendance_current")
public class AttendanceCurrentController {

    @Autowired
    AttendanceCurrentService attendanceCurrentService;

    @Autowired
    TenantUserService tenantUserService;

    @GetMapping("/read")
    public AjaxResult getUserInfo(
            @RequestParam(value = "workcd", required = false) String workcd,
            @RequestParam(value = "searchYear") String searchYear,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();

        // 사업체DB에서 personid 조회 ← 수정
        Integer personId = tenantUserService.getPersonid(user.getUsername());
        if (personId == null) {
            result.message = "사업체 DB에서 유저 정보를 찾을 수 없습니다.";
            return result;
        }

        Map<String, Object> annInfo = attendanceCurrentService.getAnnInfo(personId);
        if (annInfo != null) {
            // 입사일이 비어 있는 사원도 있다. 예전에는 조회가 늘 0건이라 여기까지 오지 않았다.
            annInfo.put("rtdate", ymd(annInfo.get("rtdate")));
        }

        List<Map<String, Object>> vacInfo = attendanceCurrentService.getVacInfo(workcd, searchYear, personId);
        for (Map<String, Object> vacDetail : vacInfo) {
            // 날짜가 빈 행이 실제로 있다. 저장이 중간에 끊긴 흔적으로, 여기서 터지면 화면 전체가 안 열린다.
            vacDetail.put("reqdate", ymd(vacDetail.get("reqdate")));
            vacDetail.put("frdate",  ymd(vacDetail.get("frdate")));
            vacDetail.put("todate",  ymd(vacDetail.get("todate")));
            vacDetail.put("yearflag", "1".equals(vacDetail.get("yearflag")) ? "O" : "-");
        }

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("annInfo", annInfo);
        resultMap.put("vacInfo", vacInfo);
        result.data = resultMap;
        return result;
    }

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

        // 신청 쪽과 같은 이유로 막는다 — 빈 날짜가 저장되면 조회 화면이 열리지 않는다.
        String formattedStartDate = (startDate == null) ? "" : startDate.replaceAll("[^0-9]", "");
        String formattedEndDate   = (endDate   == null) ? "" : endDate.replaceAll("[^0-9]", "");
        if (formattedStartDate.length() != 8 || formattedEndDate.length() != 8) {
            result.message = "시작일과 종료일을 입력해 주세요.";
            return result;
        }

        Map<String, Object> existing = attendanceCurrentService.selectTbPb204ById(vacId);

        if (existing != null && !existing.isEmpty()) {
            attendanceCurrentService.updateTbPb204(
                    vacId, formattedStartDate, startTime,
                    formattedEndDate, endTime, useDate, attKind, remark, isAnnual);
            result.message = "휴가수정이 완료되었습니다.";
            result.data = vacId;
        } else {
            log.warn("해당 ID로 데이터를 찾을 수 없습니다. vacId={}", vacId);
            result.message = "해당 데이터를 찾을 수 없습니다.";
        }
        return result;
    }

    @PostMapping("/deleteAttendance")
    public AjaxResult deleteAttendance(
            @RequestParam(value = "vacId", required = false) Integer vacId,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();

        // 사업체DB에서 spjangcd 조회 ← 수정
        String spjangcd = tenantUserService.getSpjangcd(user.getUsername());

        Map<String, Object> existing = attendanceCurrentService.selectTbPb204ById(vacId);

        if (existing != null && !existing.isEmpty()) {
            String appnum = (String) existing.get("appnum");
            Map<String, Object> tb080 = attendanceCurrentService.getAppInfo(appnum);
            if (tb080 != null && !tb080.isEmpty()) {
                attendanceCurrentService.deleteTbE080(appnum, spjangcd);
            }
            attendanceCurrentService.deleteTbPb204(vacId);
            result.message = "휴가삭제가 완료되었습니다.";
        } else {
            log.warn("해당 ID로 데이터를 찾을 수 없습니다. vacId={}", vacId);
            result.message = "해당 데이터를 찾을 수 없습니다.";
        }
        return result;
    }

    @GetMapping("/years")
    public AjaxResult getAvailableYears(Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();

        // 사업체DB에서 personid 조회 ← 수정
        Integer personId = tenantUserService.getPersonid(user.getUsername());
        if (personId == null) {
            result.message = "사업체 DB에서 유저 정보를 찾을 수 없습니다.";
            return result;
        }

        List<String> years = attendanceCurrentService.getDistinctYears(String.valueOf(personId));
        result.data = years;
        return result;
    }

    /** yyyyMMdd 를 yyyy.MM.dd 로. 값이 없거나 8자리가 아니면 빈 문자열. */
    private static String ymd(Object v) {
        String s = (v == null) ? "" : v.toString().trim();
        return s.length() == 8
                ? s.substring(0, 4) + "." + s.substring(4, 6) + "." + s.substring(6)
                : "";
    }
}
