package mes.app.mobile;

import lombok.extern.slf4j.Slf4j;
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

    // 사용자 정보 조회(부서 이름 출근여부)
    @GetMapping("/read_userInfo")
    public AjaxResult getUserInfo(
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        String username = user.getUsername();

        Map<String, Object> resultData = attendanceSubmitService.getUserInfo(username);
        result.data = resultData;
        return result;
    }

    // 휴가등록 메서드
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

        System.out.println("request Data : " + userId);
        AjaxResult result = new AjaxResult();

        User user = (User) auth.getPrincipal();
        String spjangcd = user.getSpjangcd();
        String reqdate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String formattedStartDate = startDate.replaceAll("-", "");
        String formattedEndDate = endDate.replaceAll("-", "");
        // DDL 기준 appperid, repoperid, inperid 모두 varchar(10) → String으로 변환
        String personidStr = String.valueOf(user.getPersonid());

        try {
            // ① tb_pb204 INSERT → 생성된 ID 반환
            // [기존] TB_PB204Repository.save(tbPb204) 1차 호출 대체
            long savedId = attendanceSubmitService.insertTbPb204(
                    spjangcd, reqdate, personidStr,
                    formattedStartDate, startTime, formattedEndDate, endTime,
                    useDate, attKind, remark,
                    reqdate, personidStr, user.getUsername(), isAnnual
            );

            // ② appnum 생성 후 tb_pb204 UPDATE
            // [기존] TB_PB204Repository.save(tbPb204) 2차 호출 대체
            String savedIdStr = String.format("%08d", savedId);
            String appnum = reqdate + savedIdStr + spjangcd;
            attendanceSubmitService.updateTbPb204Appnum(savedId, appnum);

            // ③ 결재라인 조회
            List<Map<String, Object>> appInfo = attendanceSubmitService.getAppInfoList(user.getPersonid());

            // 결재라인이 없을 경우 빈값으로 기본 1건 추가
            if (appInfo == null || appInfo.isEmpty()) {
                Map<String, Object> emptyAppInfo = new HashMap<>();
                emptyAppInfo.put("kcperid", "");
                emptyAppInfo.put("gubun", "");
                appInfo = new ArrayList<>();
                appInfo.add(emptyAppInfo);
            }

            // ④ 결재라인 tb_e080 INSERT (루프)
            // [기존] E080Repository.save(e080Info) 루프 대체
            int index = 0;
            for (Map<String, Object> appInfoDetail : appInfo) {
                String seq      = String.format("%03d", index + 1);
                String flag     = (index == 0) ? "1" : "0";
                String appgubun = (index == 0) ? "001" : null;
                // DDL 기준 repoperid varchar(10) → String
                String repoperid = (index == 0) ? personidStr : null;

                attendanceSubmitService.insertTbE080(
                        spjangcd,
                        appnum,
                        (String) appInfoDetail.get("kcperid"),  // tb_e080.perid
                        seq,
                        "휴가신청서",
                        flag,
                        repoperid,
                        appgubun,
                        "301",
                        personidStr,                            // tb_e080.inperid
                        reqdate,
                        (String) appInfoDetail.get("gubun")
                );
                index++;
            }

            result.message = "휴가등록이 완료되었습니다.";
            result.data = savedId;

        } catch (Exception e) {
            e.printStackTrace();
            result.message = "오류가 발생하였습니다.";
        }

        return result;
    }

    // 휴가구분 선택(근태설정에서 설정값있다면 적용)
    @GetMapping("/bindPeriod")
    public AjaxResult bindPeriod(@RequestParam Map<String, String> params,
                                 HttpServletRequest request) {
        AjaxResult result = new AjaxResult();
        String attKind = params.get("attKind");
        Map<String, Object> attInfo = attendanceSubmitService.getPeriod(attKind);
        result.data = attInfo;
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
}
