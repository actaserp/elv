package mes.app.clock;

import lombok.extern.slf4j.Slf4j;
import mes.app.annotation.ApiProduct;
import mes.app.clock.service.ClockYearlyService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.transaction.Transactional;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@ApiProduct(ApiProduct.P02)
@RestController
@RequestMapping("/api/clock/Yearly")
public class YearlyController {

    @Autowired
    private ClockYearlyService clockYearlyService;

    @GetMapping("/read")
    public AjaxResult getYearlyList(
            @RequestParam(value = "year") String year,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "spjangcd") String spjangcd,
            @RequestParam(value = "rtflag", required = false) String rtflag,
            HttpServletRequest request) {

        List<Map<String, Object>> items =
                this.clockYearlyService.getYearlyList(year, name, spjangcd, toRtclafi(rtflag));
        formatDate(items, "rtdate", "startdate", "enddate");

        AjaxResult result = new AjaxResult();
        result.success = true;
        result.data = items;
        return result;
    }

    /** 연차생성 — 근속 1년 이상자 */
    @PostMapping("/YearlyCreate")
    @Transactional
    public AjaxResult createYearly(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return generate(body, false);
    }

    /** 월차생성 — 근속 1년 미만자 */
    @PostMapping("/MonthlyCreate")
    @Transactional
    public AjaxResult createMonthly(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return generate(body, true);
    }

    private AjaxResult generate(Map<String, Object> body, boolean monthly) {

        AjaxResult result = new AjaxResult();

        String year = str(body.get("year"));
        String spjangcd = str(body.get("spjangcd"));
        String startdate = str(body.get("startdate"));
        String name = str(body.get("name"));
        String rtflag = str(body.get("rtflag"));
        String createFlag = str(body.get("createFlag"));

        if (year.length() != 4) {
            result.success = false;
            result.message = "조회년도를 선택하세요.";
            return result;
        }
        if (!"1".equals(createFlag) && !"2".equals(createFlag)) {
            result.success = false;
            result.message = "생성방식을 선택하세요.";
            return result;
        }

        List<Map<String, Object>> rows = monthly
                ? this.clockYearlyService.createMonthly(year, spjangcd, startdate, name, toRtclafi(rtflag), createFlag)
                : this.clockYearlyService.createYearly(year, spjangcd, startdate, name, toRtclafi(rtflag), createFlag);

        if (rows.isEmpty()) {
            result.success = false;
            result.message = "2".equals(createFlag)
                    ? "새로 생성할 대상자가 없습니다."
                    : "대상자가 존재하지 않습니다.";
            return result;
        }

        int saved = this.clockYearlyService.saveGenerated(rows, year, spjangcd, createFlag);

        Map<String, Object> data = new HashMap<>();
        data.put("saved", saved);
        data.put("rows", rows);

        result.success = true;
        result.message = saved + "명 생성되었습니다.";
        result.data = data;
        return result;
    }

    /** 조정일수 저장 — 발생행의 monthnum 만 바꾸고 이력을 remark 에 남긴다 */
    @PostMapping("/saveAdjust")
    @Transactional
    public AjaxResult saveAdjust(@RequestBody Map<String, Object> body,
                                 HttpServletRequest request,
                                 Authentication auth) {

        AjaxResult result = new AjaxResult();

        String year = str(body.get("year"));
        String spjangcd = str(body.get("spjangcd"));
        if (year.length() != 4) {
            result.success = false;
            result.message = "조회년도를 선택하세요.";
            return result;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("saveList");
        if (rows == null || rows.isEmpty()) {
            result.success = false;
            result.message = "변경된 내용이 없습니다.";
            return result;
        }

        Map<String, Object> out = this.clockYearlyService.saveAdjust(rows, year, spjangcd, actorName(auth));

        int saved = (int) out.get("saved");
        @SuppressWarnings("unchecked")
        List<String> notGenerated = (List<String>) out.get("notGenerated");
        @SuppressWarnings("unchecked")
        List<String> outOfRange = (List<String>) out.get("outOfRange");

        StringBuilder msg = new StringBuilder(saved + "명 저장되었습니다.");
        if (!notGenerated.isEmpty()) {
            msg.append("\n연차가 아직 생성되지 않아 건너뛴 사원: ").append(join(notGenerated));
        }
        if (!outOfRange.isEmpty()) {
            msg.append("\n입력값이 범위를 벗어나 건너뛴 사원: ").append(join(outOfRange));
        }

        result.success = true;
        result.message = msg.toString();
        result.data = out;
        return result;
    }

    @GetMapping("/detail")
    public AjaxResult getYearlyDetail(
            @RequestParam(value = "id") Integer id,
            @RequestParam(value = "year") String year,
            @RequestParam(value = "spjangcd") String spjangcd,
            HttpServletRequest request) {

        List<Map<String, Object>> items = this.clockYearlyService.getYearlyDetail(id, year, spjangcd);
        formatDate(items, "reqdate", "frdate", "todate");

        AjaxResult result = new AjaxResult();
        result.success = true;
        result.data = items;
        return result;
    }

    // ------------------------------------------------------------- 내부

    /** 화면 재직구분(0 재직 / 1 퇴직 / 2 휴직) → TB_JA001.rtclafi(001 / 002 / 003) */
    private String toRtclafi(String rtflag) {
        if (rtflag == null || rtflag.isBlank()) return null;
        switch (rtflag.trim()) {
            case "0": case "001": return "001";
            case "1": case "002": return "002";
            case "2": case "003": return "003";
            default: return null;
        }
    }

    private void formatDate(List<Map<String, Object>> items, String... keys) {
        if (items == null) return;
        for (Map<String, Object> item : items) {
            for (String key : keys) {
                Object v = item.get(key);
                if (v == null) continue;
                String s = String.valueOf(v).trim();
                if (s.length() != 8 || !s.chars().allMatch(Character::isDigit)) continue;
                if (s.endsWith("0000")) continue;   // yyyy0000 은 날짜가 아니다
                item.put(key, s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8));
            }
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    /** 조정 이력에 남길 작업자 표시명 */
    private String actorName(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof User)) return "-";
        User user = (User) auth.getPrincipal();
        if (user.getUserProfile() != null && user.getUserProfile().getName() != null
            && !user.getUserProfile().getName().isBlank()) {
            return user.getUserProfile().getName();
        }
        return user.getUsername();
    }

    /** 이름이 많으면 뒤는 생략한다 */
    private String join(List<String> names) {
        if (names.size() <= 5) return String.join(", ", names);
        return String.join(", ", names.subList(0, 5)) + " 외 " + (names.size() - 5) + "명";
    }
}
