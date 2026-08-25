package mes.app.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import mes.app.annotation.ApiProduct;
import mes.app.common.TenantUserService;
import mes.app.mobile.Service.MobileMainService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@ApiProduct(ApiProduct.P02)
@RestController
@RequestMapping("/api/mobile_main")
public class MobileMainController {

    @Autowired
    MobileMainService mobileMainService;

    @Autowired
    TenantUserService tenantUserService;

    @Autowired
    @Qualifier("mainSqlRunner")
    SqlRunner mainSqlRunner;

    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");

    @GetMapping("/read_userInfo")
    public AjaxResult getUserInfo(HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user       = (User) auth.getPrincipal();
        String username = user.getUsername();
        String spjangcd = tenantUserService.getSpjangcd(username);

        // TenantUserService에서 사업체DB 기준 사원 정보 조회
        Map<String, Object> userInfo = tenantUserService.getUserInfo(username);
        if (userInfo == null) {
            result.success = false;
            result.message = "사용자 정보를 찾을 수 없습니다.";
            return result;
        }
        username = (String) userInfo.get("username"); //사업체 DB username 조회

        Map<String, Object> timeInfo     = mobileMainService.getInOfficeTime(username, spjangcd);
        Map<String, Object> overtimeInfo = mobileMainService.getOvertimeInfo(username, spjangcd);

        Map<String, Object> resultData = new HashMap<>();
        resultData.put("perid",   userInfo.get("personid") != null ? userInfo.get("personid").toString() : null);
        resultData.put("pernm",   userInfo.get("pernm"));
        resultData.put("divinm",  userInfo.get("divinm"));
        resultData.put("RSPNM",   userInfo.get("rspnm"));
        resultData.put("last_name", userInfo.get("pernm"));  // 화면 호환성 유지
        resultData.put("username",  userInfo.get("username"));

        if (timeInfo != null) {
            resultData.put("inOfficeTime",  timeInfo.get("starttime"));
            resultData.put("outOfficeTime", timeInfo.get("endtime"));   // ✅ idx=1 퇴근시간 (추가근무 진입 조건용)
            resultData.put("workcd",        timeInfo.get("workcd"));
            resultData.put("remark",        timeInfo.get("remark"));
        }

        if (overtimeInfo != null) {
            resultData.put("ovStartTime", overtimeInfo.get("starttime"));
            // 추가근무 중일 때는 추가근무 레코드의 remark로 덮어씀
            resultData.put("remark", overtimeInfo.get("remark"));
        }

        // Master 그룹 여부 (User 그룹이 아니면 Master)
        try {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("userId", user.getId());
            p.addValue("dbKey",  user.getDbKey());
            String groupSql = """
                    SELECT COUNT(*) AS cnt FROM (
                        SELECT ug."Code" AS code
                        FROM rela_data rd
                        JOIN user_group ug ON ug.id = rd."DataPk2" AND ug.spjangcd = :dbKey
                        WHERE rd."RelationName" = 'auth_user-user_group'
                          AND rd."DataPk1" = :userId AND rd."Char1" = 'Y'
                        UNION ALL
                        SELECT ug."Code"
                        FROM user_profile up
                        JOIN user_group ug ON ug.id = up."UserGroup_id" AND ug.spjangcd = up.spjangcd
                        WHERE up."User_id" = :userId AND up.spjangcd = :dbKey
                    ) t WHERE LOWER(t.code) = 'user'
                    """;
            Map<String, Object> groupRow = mainSqlRunner.getRow(groupSql, p);
            boolean isUser = groupRow != null && groupRow.get("cnt") != null
                             && ((Number) groupRow.get("cnt")).intValue() > 0;
            resultData.put("isMaster", !isUser);
        } catch (Exception e) {
            log.warn("[MobileMain] 그룹 판별 실패", e);
            resultData.put("isMaster", false);
        }

        result.data = resultData;
        result.success = true;
        return result;
    }

    @PostMapping("/submitCommute")
    public AjaxResult submitCommute(
            @RequestParam(value = "weekNum")                      Integer weekNum,
            @RequestParam(value = "office")                       String  office,
            @RequestParam(value = "workym",     required = false) String  workym,
            @RequestParam(value = "workday",    required = false) String  workday,
            @RequestParam(value = "isHoly",     required = false) String  isHoly,
            @RequestParam(value = "workcd",     required = false) String  workcd,
            @RequestParam(value = "latitude",   required = false) String  latitude,
            @RequestParam(value = "longitude",  required = false) String  longitude,
            @RequestParam(value = "gpsInfo",    required = false) String  gpsInfo,
            @RequestParam(value = "remark",     required = false) String  remark,
            @RequestParam(value = "isOvertime", required = false, defaultValue = "false") Boolean isOvertime,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user       = (User) auth.getPrincipal();
        String username = user.getUsername();

        Map<String, Object> userInfo = tenantUserService.getUserInfo(username);
        if (userInfo == null) {
            result.success = false;
            result.message = "사용자 정보를 찾을 수 없습니다.";
            return result;
        }
        username = (String) userInfo.get("username"); //사업체 DB username 조회
        String spjangcd = (String) userInfo.get("spjangcd");
        String perId    = userInfo.get("personid").toString();
        String workType = String.format("%02d", ((Number) userInfo.get("PersonGroup_id")).intValue());

        LocalTime currentTime       = LocalDateTime.now().toLocalTime();
        String    formattedCurrTime = currentTime.format(timeFormatter);

        int nextIdx = !isOvertime ? 1 : mobileMainService.findMaxIdx(spjangcd, perId, workym, workday) + 1;

        int    jitime      = 0;
        String finalWorkcd = workcd;

        if (!isOvertime) {
            Map<String, Object> existing = mobileMainService.findRecord(spjangcd, perId, workym, workday, 1);
            String sttime     = (String) mobileMainService.getWorkTime(workType).get("sttime");
            LocalTime lateTime = LocalTime.parse(sttime, timeFormatter).plusMinutes(1);

            if (existing != null) {
                jitime     = 0;
                finalWorkcd = (String) existing.get("workcd");
            } else {
                jitime     = currentTime.isBefore(lateTime) ? 0 : 1;
                finalWorkcd = "outOfficeIn".equals(office) ? workcd : null;
            }
        } else {
            jitime     = 0;
            finalWorkcd = "outOfficeIn".equals(office) ? workcd : null;
        }

        String inFlag         = office.startsWith("inOffice") ? "0" : "1";
        String finalAddress   = "1".equals(inFlag) ? gpsInfo   : null;
        String finalLatitude  = "1".equals(inFlag) && latitude  != null && !latitude.isEmpty()  ? latitude  : null;
        String finalLongitude = "1".equals(inFlag) && longitude != null && !longitude.isEmpty() ? longitude : null;

        try {
            mobileMainService.saveCommute(
                    spjangcd, perId, workym, workday, nextIdx,
                    weekNum, isHoly, formattedCurrTime, inFlag,
                    finalWorkcd, finalAddress, finalLatitude, finalLongitude,
                    jitime, remark != null ? remark.trim() : "");
            result.success = true;
            result.message = isOvertime ? "추가근무 출근이 등록되었습니다." : "출근등록이 완료되었습니다.";
        } catch (Exception e) {
            e.printStackTrace();
            result.success = false;
            result.message = "오류가 발생하였습니다.";
        }
        return result;
    }

    @PostMapping("/modifyCommute")
    public AjaxResult modifyCommute(
            @RequestParam(value = "office")                       String office,
            @RequestParam(value = "workym",    required = false)  String workym,
            @RequestParam(value = "workday",   required = false)  String workday,
            @RequestParam(value = "remark",    required = false)  String remark,
            @RequestParam(value = "workcd",    required = false)  String workcd,
            @RequestParam(value = "latitude",  required = false)  String latitude,
            @RequestParam(value = "longitude", required = false)  String longitude,
            @RequestParam(value = "gpsInfo",   required = false)  String gpsInfo,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user       = (User) auth.getPrincipal();
        String username = user.getUsername();

        Map<String, Object> userInfo = tenantUserService.getUserInfo(username);
        if (userInfo == null) {
            result.success = false;
            result.message = "사용자 정보를 찾을 수 없습니다.";
            return result;
        }
        String spjangcd = (String) userInfo.get("spjangcd");
        String perId    = userInfo.get("personid") != null ? userInfo.get("personid").toString() : null;
        String workType = String.format("%02d", ((Number) userInfo.get("PersonGroup_id")).intValue());

        LocalTime currentTime       = LocalDateTime.now().toLocalTime();
        String    formattedCurrTime = currentTime.format(timeFormatter);

        List<Map<String, Object>> todayRecords =
                mobileMainService.findTodayAllRecords(spjangcd, perId, workym, workday);

        Map<String, Object> entity = todayRecords.stream()
                .filter(r -> r.get("endtime") == null || r.get("endtime").toString().isEmpty())
                .max(Comparator.comparing(r -> ((Number) r.get("idx")).intValue()))
                .orElse(null);

        if (entity == null) {
            result.success = false;
            result.message = "미처리된 출근 기록이 없습니다.";
            return result;
        }

        int     targetIdx       = ((Number) entity.get("idx")).intValue();
        boolean isOvertimeOut   = targetIdx >= 2;
        String  entityWorkcd    = entity.get("workcd")    != null ? entity.get("workcd").toString()    : null;
        String  entityHoliyn    = entity.get("holiyn")    != null ? entity.get("holiyn").toString()    : null;
        String  entityStarttime = entity.get("starttime") != null ? entity.get("starttime").toString() : null;
        int     entityJitime    = entity.get("jitime")    != null ? ((Number) entity.get("jitime")).intValue() : 0;

        Map<String, Object> workTimeInfo = mobileMainService.getWorkTime(workType);
        String    endtimeStr    = (String) workTimeInfo.get("endtime");
        LocalTime endtimeParsed = LocalTime.parse(endtimeStr, timeFormatter);

        String inFlag;
        String finalWorkcd;
        String finalAddress     = null;
        String finalLatitude    = null;
        String finalLongitude   = null;
        String finalOutAddress  = null;
        String finalOutLatitude = null;
        String finalOutLongitude= null;

        if ("inOfficeOut".equals(office)) {
            inFlag      = "0";
            finalWorkcd = (entityWorkcd == null || entityWorkcd.isEmpty()) ? "01" : entityWorkcd;
        } else {
            inFlag      = "1";
            finalWorkcd = (entityWorkcd == null || entityWorkcd.isEmpty()) ? workcd : entityWorkcd;
            // 출근 위치는 기존 address 컬럼 유지, 퇴근 위치는 out_* 컬럼에 저장
            if (gpsInfo   != null && !gpsInfo.isEmpty())   finalOutAddress   = gpsInfo;
            if (latitude  != null && !latitude.isEmpty())  finalOutLatitude  = latitude;
            if (longitude != null && !longitude.isEmpty()) finalOutLongitude = longitude;
        }

        int jotFlag = 0;
        if (!isOvertimeOut) {
            boolean isBanchaOrYeoncha = "04".equals(finalWorkcd) || "08".equals(finalWorkcd);
            if (!isBanchaOrYeoncha) {
                jotFlag = currentTime.isAfter(endtimeParsed) ? 0 : 1;
            }
        }

        String workyn = (entityJitime == 1 || jotFlag == 1) ? "0" : "1";

        String sttime    = (String) workTimeInfo.get("sttime");
        String ovsttime  = (String) workTimeInfo.get("ovsttime");
        String ovedtime  = (String) workTimeInfo.get("ovedtime");
        String ngsttime  = (String) workTimeInfo.get("ngsttime");
        String ngedtime  = (String) workTimeInfo.get("ngedtime");

        LocalTime startTime  = LocalTime.parse(entityStarttime, timeFormatter);
        LocalTime endTime    = currentTime;
        LocalTime normalStart = LocalTime.parse(sttime,     timeFormatter);
        LocalTime normalEnd   = LocalTime.parse(endtimeStr, timeFormatter);
        LocalTime overStart   = LocalTime.parse(ovsttime,   timeFormatter);
        LocalTime overEnd     = LocalTime.parse(ovedtime,   timeFormatter);
        LocalTime nightStart  = LocalTime.parse(ngsttime,   timeFormatter);
        LocalTime nightEnd    = LocalTime.parse(ngedtime,   timeFormatter);
        LocalTime restStartT  = LocalTime.parse("12:00",    timeFormatter);
        LocalTime restEndT    = LocalTime.parse("13:00",    timeFormatter);

        BigDecimal normalTime, overTime, nightTime, totalTime, holitime;

        if (isOvertimeOut) {
            long restMinutes  = calculateRestOverlapMinutes(startTime, endTime, restStartT, restEndT);
            long totalMinutes = Math.max(Duration.between(startTime, endTime).toMinutes() - restMinutes, 0);
            normalTime = BigDecimal.ZERO;
            overTime   = BigDecimal.valueOf(totalMinutes).divide(BigDecimal.valueOf(60), 2, RoundingMode.DOWN);
            nightTime  = BigDecimal.ZERO;
            totalTime  = overTime;
            holitime   = BigDecimal.ZERO;
        } else {
            normalTime = calculateTimeOverlap(startTime, endTime, normalStart, normalEnd, restStartT, restEndT);
            overTime   = calculateTimeOverlap(startTime, endTime, overStart,   overEnd,   restStartT, restEndT);
            if (nightEnd.isBefore(nightStart)) {
                BigDecimal p1 = calculateTimeOverlap(startTime, endTime, nightStart, LocalTime.MAX,  restStartT, restEndT);
                BigDecimal p2 = calculateTimeOverlap(startTime, endTime, LocalTime.MIN, nightEnd,    restStartT, restEndT);
                nightTime = p1.add(p2);
            } else {
                nightTime = calculateTimeOverlap(startTime, endTime, nightStart, nightEnd, restStartT, restEndT);
            }
            totalTime = normalTime.add(overTime).add(nightTime);
            holitime  = BigDecimal.ZERO;
        }

        String today = workym + workday;
        Map<String, Object> flexibleWork = mobileMainService.findFlexibleWork(spjangcd, perId, today, "13");
        boolean isFlexibleWork = (flexibleWork != null) && !isOvertimeOut;

        BigDecimal finalWorktime, finalNomaltime, finalOvertime, finalNighttime, finalHolitime;

        if ("0".equals(entityHoliyn)) {
            if (isFlexibleWork) {
                BigDecimal flexTime = calculateFlexibleWorkTime(startTime, endTime, restStartT, restEndT);
                finalWorktime  = flexTime;
                finalNomaltime = flexTime;
                finalOvertime  = BigDecimal.ZERO;
                finalNighttime = BigDecimal.ZERO;
                finalHolitime  = BigDecimal.ZERO;
            } else {
                finalWorktime  = totalTime;
                finalNomaltime = normalTime;
                finalOvertime  = overTime;
                finalNighttime = nightTime;
                finalHolitime  = BigDecimal.ZERO;
            }
        } else {
            finalWorktime  = totalTime;
            finalNomaltime = BigDecimal.ZERO;
            finalOvertime  = BigDecimal.ZERO;
            finalNighttime = BigDecimal.ZERO;
            finalHolitime  = totalTime;
        }

        try {
            mobileMainService.saveEndtime(
                    spjangcd, perId, workym, workday, targetIdx,
                    formattedCurrTime, remark, inFlag, workyn, jotFlag,
                    finalWorkcd,
                    finalOutAddress, finalOutLatitude, finalOutLongitude,
                    finalWorktime, finalNomaltime, finalOvertime, finalNighttime, finalHolitime);
            result.success = true;
            result.message = isOvertimeOut ? "추가근무 퇴근처리가 완료되었습니다." : "퇴근처리가 마무리되었습니다.";
        } catch (Exception e) {
            result.success = false;
            result.message = "오류가 발생하였습니다: " + e.getMessage();
        }
        return result;
    }

    @PostMapping("/switchAddress")
    public AjaxResult switchAddress(@RequestParam("lat") String lat,
                                    @RequestParam("lon") String lon) {
        AjaxResult result = new AjaxResult();
        try {
            String apiKey = "672F3CC6-711E-3390-87DC-77190302557E";
            String apiUrl = "https://api.vworld.kr/req/address?service=address&request=getAddress"
                    + "&key=" + apiKey
                    + "&format=json&type=both&crs=epsg:4326&point=" + lon + "," + lat;

            RestTemplate restTemplate = new RestTemplate();
            String response = restTemplate.getForObject(apiUrl, String.class);

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root      = mapper.readTree(response);
            JsonNode resultArr = root.path("response").path("result");

            if (resultArr.isArray() && !resultArr.isEmpty()) {
                result.success = true;
                result.message = "주소 변환 성공";
                result.data    = resultArr.get(0).path("text").asText();
            } else {
                result.success = false;
                result.message = "주소를 찾을 수 없습니다.";
            }
        } catch (Exception e) {
            result.success = false;
            result.message = "API 호출 오류: " + e.getMessage();
        }
        return result;
    }

    private BigDecimal calculateFlexibleWorkTime(LocalTime start, LocalTime end,
                                                 LocalTime restStart, LocalTime restEnd) {
        long totalMinutes;
        if (end.isBefore(start)) {
            totalMinutes = Duration.between(start, LocalTime.MAX).toMinutes()
                    + Duration.between(LocalTime.MIN, end).toMinutes() + 1;
        } else {
            totalMinutes = Duration.between(start, end).toMinutes();
        }
        long workMinutes = Math.max(totalMinutes - calculateRestOverlapMinutes(start, end, restStart, restEnd), 0);
        return BigDecimal.valueOf(workMinutes).divide(BigDecimal.valueOf(60), 2, RoundingMode.DOWN);
    }

    private long calculateRestOverlapMinutes(LocalTime start, LocalTime end,
                                             LocalTime restStart, LocalTime restEnd) {
        LocalTime overlapStart = start.isAfter(restStart) ? start : restStart;
        LocalTime overlapEnd   = end.isBefore(restEnd)    ? end   : restEnd;
        if (overlapEnd.isAfter(overlapStart)) {
            return Duration.between(overlapStart, overlapEnd).toMinutes();
        }
        return 0;
    }

    public static BigDecimal calculateTimeOverlap(LocalTime start, LocalTime end,
                                                  LocalTime rangeStart, LocalTime rangeEnd,
                                                  LocalTime restStart, LocalTime restEnd) {
        LocalTime actualStart = start.isBefore(rangeStart) ? rangeStart : start;
        LocalTime actualEnd   = end.isAfter(rangeEnd)      ? rangeEnd   : end;

        if (actualStart.isBefore(actualEnd)) {
            Duration workDuration = Duration.between(actualStart, actualEnd);
            if (!(restEnd.isBefore(actualStart) || restStart.isAfter(actualEnd))) {
                LocalTime restOverlapStart = actualStart.isBefore(restStart) ? restStart : actualStart;
                LocalTime restOverlapEnd   = actualEnd.isAfter(restEnd)      ? restEnd   : actualEnd;
                if (restOverlapStart.isBefore(restOverlapEnd)) {
                    workDuration = workDuration.minus(Duration.between(restOverlapStart, restOverlapEnd));
                }
            }
            double hours      = workDuration.toMinutes() / 60.0;
            double intPart    = Math.floor(hours);
            double fractional = hours - intPart;
            double adjusted;
            if (fractional == 0.0 || fractional == 0.5) {
                adjusted = intPart + fractional;
            } else if (fractional < 0.5) {
                adjusted = intPart;
            } else {
                adjusted = intPart + 0.5;
            }
            return BigDecimal.valueOf(adjusted).setScale(1, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }
}
