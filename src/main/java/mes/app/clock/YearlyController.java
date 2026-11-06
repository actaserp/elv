package mes.app.clock;

import lombok.extern.slf4j.Slf4j;
import mes.app.annotation.ApiProduct;
import mes.app.clock.service.ClockYearlyService;
import mes.app.common.TenantContext;
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

    /**
     * 연차생성 미리보기 — 근속 1년 이상(연차)과 1년 미만(월차)을 한 번에 계산한다.
     * 산정기준(회계연도/입사일)은 사업체 설정을 따른다.
     *
     * <b>저장하지 않는다.</b> 화면이 결과를 목록에 얹어 보여주고, 사용자가 [저장]을 누르면
     * {@link #createSave} 로 다시 온다. 생성방식 '1' 은 기존 발생일수를 덮어쓰므로,
     * 잘못 눌렀을 때 되돌릴 수 없는 것을 막으려고 두 단계로 나눴다.
     */
    @PostMapping("/Create")
    public AjaxResult create(@RequestBody Map<String, Object> body, Authentication auth) {
        return generate(body, auth, false);
    }

    /**
     * 연차생성 저장 — 미리보기와 같은 조건으로 <b>서버가 다시 계산해서</b> 저장한다.
     *
     * 화면이 보낸 숫자를 그대로 쓰지 않는다. 미리보기와 저장 사이에 인사정보가 바뀌었으면
     * 최신 값이 들어가고, 값을 고쳐 보내도 반영되지 않는다. 같은 조건이면 결과도 같다.
     */
    @PostMapping("/CreateSave")
    @Transactional
    public AjaxResult createSave(@RequestBody Map<String, Object> body, Authentication auth) {
        return generate(body, auth, true);
    }

    /** @deprecated 버튼이 하나로 합쳐졌다. /Create + /CreateSave 를 쓴다. 옛 호출은 즉시 저장으로 둔다 */
    @Deprecated
    @PostMapping("/YearlyCreate")
    @Transactional
    public AjaxResult createYearly(@RequestBody Map<String, Object> body, Authentication auth) {
        return generate(body, auth, true);
    }

    /** @deprecated 버튼이 하나로 합쳐졌다. /Create + /CreateSave 를 쓴다. 옛 호출은 즉시 저장으로 둔다 */
    @Deprecated
    @PostMapping("/MonthlyCreate")
    @Transactional
    public AjaxResult createMonthly(@RequestBody Map<String, Object> body, Authentication auth) {
        return generate(body, auth, true);
    }

    /**
     * @param persist false 면 계산만 하고 돌려준다(미리보기), true 면 저장까지 한다.
     */
    private AjaxResult generate(Map<String, Object> body, Authentication auth, boolean persist) {

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

        // 산정기준은 사업체 단위 설정이라 db_key 로 찾는다. 화면이 보내는 spjangcd('ZZ')가 아니다
        String dbKey = currentDbKey(auth);

        List<Map<String, Object>> rows =
                this.clockYearlyService.createAll(year, spjangcd, dbKey, startdate, name, toRtclafi(rtflag), createFlag);

        if (rows.isEmpty()) {
            result.success = false;
            result.message = "2".equals(createFlag)
                    ? "새로 생성할 대상자가 없습니다."
                    : "대상자가 존재하지 않습니다.";
            return result;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("rows", rows);

        if (!persist) {
            // 미리보기 — 아직 저장하지 않았다. 화면이 목록에 얹어 보여준다
            data.put("saved", 0);
            result.success = true;
            result.message = rows.size() + "명이 대상입니다. 내용을 확인한 뒤 [저장]을 누르세요.";
            result.data = data;
            return result;
        }

        int saved = this.clockYearlyService.saveGenerated(rows, year, spjangcd, createFlag);
        data.put("saved", saved);

        result.success = true;
        result.message = saved + "명 생성되었습니다.";
        result.data = data;
        return result;
    }

    /**
     * 지금 요청이 어느 사업체인가(tb_tenant_db.spjangcd = db_key).
     * 로그인 사용자에서 먼저 찾고, 없으면 요청 컨텍스트에서 가져온다.
     */
    private String currentDbKey(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof User user && user.getDbKey() != null) {
            return user.getDbKey();
        }
        return TenantContext.getDbKey();
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
