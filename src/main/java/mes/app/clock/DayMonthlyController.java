package mes.app.clock;

import lombok.extern.slf4j.Slf4j;
import mes.app.clock.service.DayMonthlyService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.transaction.Transactional;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/clock/DayMonthly")
public class DayMonthlyController {

    @Autowired
    private DayMonthlyService dayMonthlyService;

    // =========================================================
    // 일별 근태 목록 조회
    // =========================================================
    @GetMapping("/read")
    public AjaxResult getDayList(
            @RequestParam(value = "work_division", required = false) String work_division,
            @RequestParam(value = "serchday",      required = false) String serchday,
            @RequestParam(value = "depart",        required = false) String depart,
            @RequestParam(value = "spjangcd")                        String spjangcd,
            HttpServletRequest request,
            Authentication auth) {

        long start = System.currentTimeMillis();

        AjaxResult result = new AjaxResult();

        if (serchday != null && serchday.contains("-")) {
            serchday = serchday.replaceAll("-", "");
        }

        List<Map<String, Object>> items = this.dayMonthlyService.getDayList(work_division, serchday, spjangcd, depart);
        result.data = items;

        long end = System.currentTimeMillis();
        log.info("getDayList Controller 총 실행시간: {}ms", (end - start));

        return result;
    }

    // =========================================================
    // 일별 근태 저장 (savedata)
    // =========================================================
    @PostMapping("/savedata")
    @Transactional
    public AjaxResult saveDayDataList(
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
                // 시간 형식 검증
                String starttime = (String) item.get("starttime");
                String endtime   = (String) item.get("endtime");
                if (starttime != null && !starttime.trim().isEmpty() && !starttime.matches("^\\d{2}:\\d{2}$")) {
                    result.success = false;
                    result.message = "출근시간 형식이 올바르지 않습니다. (예: 09:30)";
                    return result;
                }
                if (endtime != null && !endtime.trim().isEmpty() && !endtime.matches("^\\d{2}:\\d{2}$")) {
                    result.success = false;
                    result.message = "퇴근시간 형식이 올바르지 않습니다. (예: 09:30)";
                    return result;
                }
                dayMonthlyService.saveDayData(item, spjangcd);
            }
            result.success = true;
        } catch (Exception e) {
            result.success = false;
            result.message = "저장 중 오류가 발생했습니다: " + e.getMessage();
        }

        return result;
    }

    // =========================================================
    // 일별 근태 수정 저장 (save - 마감 처리용)
    // =========================================================
    @PostMapping("/save")
    @Transactional
    public AjaxResult saveDayList(
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
                String starttime = (String) item.get("starttime");
                String endtime   = (String) item.get("endtime");
                if (starttime != null && !starttime.trim().isEmpty() && !starttime.matches("^\\d{2}:\\d{2}$")) {
                    result.success = false;
                    result.message = "출근시간 형식이 올바르지 않습니다. (예: 09:30)";
                    return result;
                }
                if (endtime != null && !endtime.trim().isEmpty() && !endtime.matches("^\\d{2}:\\d{2}$")) {
                    result.success = false;
                    result.message = "퇴근시간 형식이 올바르지 않습니다. (예: 09:30)";
                    return result;
                }
                dayMonthlyService.saveDayMagam(item, spjangcd);
            }
            result.success = true;
        } catch (Exception e) {
            result.success = false;
            result.message = "저장 중 오류가 발생했습니다: " + e.getMessage();
        }

        return result;
    }

    // =========================================================
    // 일별 마감 취소 (MagamCancel)
    // =========================================================
    @PostMapping("/MagamCancel")
    @Transactional
    public AjaxResult DayMagamCancel(
            @RequestBody Map<String, Object> requestData,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        List<Map<String, Object>> dataList = (List<Map<String, Object>>) requestData.get("list");

        try {
            for (Map<String, Object> item : dataList) {
                String workym   = (String) item.get("workym");
                String spjangcd = (String) item.get("spjangcd");
                String workday  = (String) item.get("workday");
                String personid = (String) item.get("id");
                dayMonthlyService.cancelDayMagam(spjangcd, personid, workym, workday);
            }
            result.success = true;
        } catch (Exception e) {
            result.success = false;
            result.message = "마감 취소 중 오류가 발생했습니다: " + e.getMessage();
        }

        return result;
    }

    // =========================================================
    // 근태구분 목록 조회
    // =========================================================
    @PostMapping("/workcdList")
    public AjaxResult getspjangcd(@RequestParam(value = "spjangcd") String spjangcd) {
        AjaxResult result = new AjaxResult();
        result.data = dayMonthlyService.workcdList(spjangcd);
        return result;
    }

    // =========================================================
    // 월정산 목록 조회
    // =========================================================
    @GetMapping("/MonthlyRead")
    public AjaxResult getMonthlyRead(
            @RequestParam(value = "person_name", required = false) String person_name,
            @RequestParam(value = "startdate",   required = false) String startdate,
            @RequestParam(value = "depart",      required = false) String depart,
            @RequestParam(value = "spjangcd")                      String spjangcd,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();

        if (startdate != null && startdate.contains("-")) {
            startdate = startdate.replaceAll("-", "");
        }

        List<Map<String, Object>> items = this.dayMonthlyService.getMonthlyReadList(person_name, startdate, spjangcd, depart);
        result.data = items;
        return result;
    }

    // =========================================================
    // 월정산 실행
    // =========================================================
    @GetMapping("/getMonthlyList")
    public AjaxResult getMonthlyList(
            @RequestParam(value = "startdate", required = false) String startdate,
            @RequestParam(value = "spjangcd")                    String spjangcd) {

        AjaxResult result = new AjaxResult();

        if (startdate != null && startdate.contains("-")) {
            startdate = startdate.replaceAll("-", "");
        }

        int insertCount = this.dayMonthlyService.insertWorkSummary(spjangcd, startdate);

        Map<String, Object> responseData = new HashMap<>();
        responseData.put("insertCount", insertCount);
        result.data = responseData;

        if (insertCount == 0) {
            result.success = false;
            result.message = "일별마감 데이터가 없습니다.";
        } else {
            result.success = true;
        }

        return result;
    }

    // =========================================================
    // 월정산 마감 저장
    // =========================================================
    @PostMapping("/MonthlysaveMagam")
    @Transactional
    public AjaxResult saveMonthlyMagamList(
            @RequestBody Map<String, Object> requestData,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();

        List<Map<String, Object>> dataList = (List<Map<String, Object>>) requestData.get("list");

        if (dataList == null || dataList.isEmpty()) {
            result.success = false;
            result.message = "저장할 데이터가 없습니다.";
            return result;
        }

        try {
            for (Map<String, Object> item : dataList) {
                dayMonthlyService.saveMonthlyMagam(item);
            }
            result.success = true;
        } catch (Exception e) {
            result.success = false;
            result.message = "저장 중 오류가 발생했습니다: " + e.getMessage();
        }

        return result;
    }

    // =========================================================
    // 월정산 삭제
    // =========================================================
    @PostMapping("/delete")
    @Transactional
    public AjaxResult deleteMonthlyList(
            @RequestBody Map<String, Object> requestData,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        List<Map<String, Object>> dataList = (List<Map<String, Object>>) requestData.get("list");

        try {
            for (Map<String, Object> item : dataList) {
                String  workym   = (String) item.get("workym");
                String  spjangcd = (String) item.get("spjangcd");
                int     personid = ((Number) item.get("personid")).intValue();
                dayMonthlyService.deleteMonthly(spjangcd, workym, personid);
            }
            result.success = true;
        } catch (Exception e) {
            result.success = false;
            result.message = "삭제 중 오류가 발생했습니다: " + e.getMessage();
        }

        return result;
    }

    // =========================================================
    // 월정산 마감 취소
    // =========================================================
    @PostMapping("/MonthlyCancelMagam")
    @Transactional
    public AjaxResult CancelMonthlyList(
            @RequestBody Map<String, Object> requestData,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        List<Map<String, Object>> dataList = (List<Map<String, Object>>) requestData.get("list");

        try {
            for (Map<String, Object> item : dataList) {
                String  workym   = (String) item.get("workym");
                String  spjangcd = (String) item.get("spjangcd");
                int     personid = ((Number) item.get("personid")).intValue();
                dayMonthlyService.cancelMonthlyMagam(spjangcd, workym, personid);
            }
            result.success = true;
        } catch (Exception e) {
            result.success = false;
            result.message = "마감 취소 중 오류가 발생했습니다: " + e.getMessage();
        }

        return result;
    }

    // =========================================================
    // 일별 근태 삭제 (deletedata) - 미마감 레코드만
    // =========================================================
    @PostMapping("/deletedata")
    @Transactional
    public AjaxResult deleteDataList(
            @RequestBody Map<String, Object> requestData,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();

        List<Map<String, Object>> dataList = (List<Map<String, Object>>) requestData.get("list");
        String spjangcd = (String) requestData.get("spjangcd");

        if (dataList == null || dataList.isEmpty()) {
            result.success = false;
            result.message = "삭제할 데이터가 없습니다.";
            return result;
        }

        int deleteCount = 0;

        try {
            for (Map<String, Object> item : dataList) {
                Object fixflagObj = item.get("fixflag");
                String fixflag    = fixflagObj != null ? String.valueOf(fixflagObj) : null;

                // fixflag가 "0" 또는 null일 때만 삭제 가능
                if (fixflag == null || "0".equals(fixflag) || "null".equalsIgnoreCase(fixflag)) {
                    String workymd = (String) item.get("workymd");
                    Object idObj   = item.get("id");

                    if (workymd == null || workymd.length() < 10 || idObj == null) continue;

                    String workym  = workymd.substring(0, 4) + workymd.substring(5, 7);
                    String workday = workymd.substring(8, 10);
                    String perid   = String.valueOf(((Number) idObj).intValue());

                    deleteCount += dayMonthlyService.deleteDayData(spjangcd, perid, workym, workday);
                }
            }
        } catch (Exception e) {
            result.success = false;
            result.message = "삭제 중 오류가 발생했습니다: " + e.getMessage();
            return result;
        }

        if (deleteCount > 0) {
            result.success = true;
            result.message = deleteCount + "건이 삭제되었습니다.";
        } else {
            result.success = false;
            result.message = "삭제할 데이터가 없습니다. \n(마감된 데이터는 삭제되지 않습니다)";
        }

        return result;
    }

    // =========================================================
    // 부서 목록 조회
    // =========================================================
    @GetMapping("/departList")
    public AjaxResult getDepartList(String spjangcd) {
        AjaxResult result = new AjaxResult();
        result.data = this.dayMonthlyService.getDepartList(spjangcd);
        return result;
    }
}
