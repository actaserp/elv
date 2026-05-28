package mes.app.mobile;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantUserService;
import mes.app.mobile.Service.AttendanceSubmitService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@Transactional
@RequestMapping("/api/attendance_submit")
public class AttendanceSubmitController {

    @Autowired
    AttendanceSubmitService attendanceSubmitService;

    @Autowired
    TenantUserService tenantUserService;

    @GetMapping("/read_userInfo")
    public AjaxResult getUserInfo(HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();

        // 본사 auth_user.personid = 사업체 person.id 이므로 getUserInfo로 personid 확보
        Map<String, Object> tenantInfo = tenantUserService.getUserInfo(user.getUsername());
        if (tenantInfo == null) {
            result.message = "사업체 DB에서 유저 정보를 찾을 수 없습니다.";
            return result;
        }
        int personId = ((Number) tenantInfo.get("personid")).intValue();

        // 사원 기본정보 조회 (person + tb_pbcont)
        Map<String, Object> userInfo = attendanceSubmitService.getUserInfo(personId);
        if (userInfo == null) {
            result.message = "사원 정보를 찾을 수 없습니다.";
            return result;
        }

        // tenantInfo + userInfo 합치고 login_id 추가해서 반환
        userInfo.putAll(tenantInfo);
        userInfo.put("username", user.getUsername());

        result.data = userInfo;
        return result;
    }

    @PostMapping("/submitAttendance")
    public AjaxResult submitCommute(
            @RequestParam(value = "userId") String userId,
            @RequestParam(value = "userName") String userName,
            @RequestParam(value = "attKind", required = false) String attKind,
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "startTime", required = false) String startTime,
            @RequestParam(value = "endDate", required = false) String endDate,
            @RequestParam(value = "endTime", required = false) String endTime,
            @RequestParam(value = "isAnnual", required = false) String isAnnual,
            @RequestParam(value = "useDate", required = false) BigDecimal useDate,
            @RequestParam(value = "usedDate", required = false) String usedDate,
            @RequestParam(value = "remark", required = false) String remark,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();

        // 사업체DB에서 spjangcd, personid 조회
        Map<String, Object> tenantInfo = tenantUserService.getUserInfo(user.getUsername());
        if (tenantInfo == null) {
            result.message = "사업체 DB에서 유저 정보를 찾을 수 없습니다.";
            return result;
        }
        String spjangcd    = (String) tenantInfo.get("spjangcd");
        int    personId    = ((Number) tenantInfo.get("personid")).intValue();
        String personidStr = String.valueOf(personId);

        String reqdate            = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String formattedStartDate = startDate.replaceAll("-", "");
        String formattedEndDate   = endDate.replaceAll("-", "");

        try {
            long savedId = attendanceSubmitService.insertTbPb204(
                    spjangcd, reqdate, personidStr,
                    formattedStartDate, startTime, formattedEndDate, endTime,
                    useDate, attKind, remark,
                    reqdate, personidStr, user.getUsername(), isAnnual
            );

            String savedIdStr = String.format("%08d", savedId);
            String appnum     = reqdate + savedIdStr + spjangcd;
            attendanceSubmitService.updateTbPb204Appnum(savedId, appnum);

            List<Map<String, Object>> appInfo = attendanceSubmitService.getAppInfoList(personId);

            if (appInfo == null || appInfo.isEmpty()) {
                Map<String, Object> emptyAppInfo = new HashMap<>();
                emptyAppInfo.put("kcperid", "");
                emptyAppInfo.put("gubun", "");
                appInfo = new ArrayList<>();
                appInfo.add(emptyAppInfo);
            }

            int index = 0;
            for (Map<String, Object> appInfoDetail : appInfo) {
                String seq       = String.format("%03d", index + 1);
                String flag      = (index == 0) ? "1" : "0";
                String appgubun  = (index == 0) ? "001" : null;
                String repoperid = (index == 0) ? personidStr : null;

                attendanceSubmitService.insertTbE080(
                        spjangcd, appnum,
                        (String) appInfoDetail.get("kcperid"),
                        seq, "휴가신청서", flag, repoperid, appgubun,
                        "301", personidStr, reqdate,
                        (String) appInfoDetail.get("gubun")
                );
                index++;
            }

            result.message = "휴가등록이 완료되었습니다.";
            result.data    = savedId;

        } catch (Exception e) {
            log.error("휴가등록 오류: {}", e.getMessage(), e);
            result.message = "오류가 발생하였습니다.";
        }

        return result;
    }

    @GetMapping("/bindPeriod")
    public AjaxResult bindPeriod(@RequestParam Map<String, String> params,
                                 HttpServletRequest request) {
        AjaxResult result = new AjaxResult();
        result.data = attendanceSubmitService.getPeriod(params.get("attKind"));
        return result;
    }
}
