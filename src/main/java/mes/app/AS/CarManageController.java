package mes.app.AS;

import lombok.extern.slf4j.Slf4j;
import mes.app.AS.service.CarManageService;
import mes.app.annotation.ApiProduct;
import mes.app.common.TenantUserService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@ApiProduct(ApiProduct.P03)
@RestController
@Transactional
@RequestMapping("/api/AS/car_manage")
public class CarManageController {

    @Autowired
    CarManageService carManageService;

    @Autowired
    TenantUserService tenantUserService;

    @Autowired
    @Qualifier("mainSqlRunner")
    SqlRunner mainSqlRunner;

    // 로그인 사용자가 '사용자(User)' 그룹에 속하는지 — 로그인 사업장(dbKey) 한정, rela_data + user_profile 확인
    private boolean isUserGroup(User user) {
        try {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("userId", user.getId());
            p.addValue("dbKey",  user.getDbKey());
            String sql = """
                    SELECT COUNT(*) AS cnt FROM (
                        SELECT ug."Code" AS code
                        FROM rela_data rd
                        JOIN user_group ug ON ug.id = rd."DataPk2"
                                          AND ug.spjangcd = :dbKey
                        WHERE rd."RelationName" = 'auth_user-user_group'
                          AND rd."DataPk1" = :userId
                          AND rd."Char1"   = 'Y'
                        UNION ALL
                        SELECT ug."Code"
                        FROM user_profile up
                        JOIN user_group ug ON ug.id = up."UserGroup_id"
                                          AND ug.spjangcd = up.spjangcd
                        WHERE up."User_id" = :userId
                          AND up.spjangcd  = :dbKey
                    ) t WHERE LOWER(t.code) = 'user'
                    """;
            Map<String, Object> row = mainSqlRunner.getRow(sql, p);
            return row != null && row.get("cnt") != null && ((Number) row.get("cnt")).intValue() > 0;
        } catch (Exception e) {
            log.warn("[car_manage] 사용자 그룹 판별 실패 username={}", user.getUsername(), e);
            return false;
        }
    }

    // User 그룹이면 username 반환, 아니면 null
    private String getOwnUsernameIfUserGroup(User user) {
        return isUserGroup(user) ? user.getUsername() : null;
    }

    // 현재 사용자가 User 그룹인지 여부 (화면 버튼/등록 제어용)
    @GetMapping("/user_group")
    public AjaxResult userGroup(Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        Map<String, Object> data = new HashMap<>();
        data.put("isUserGroup", getOwnUsernameIfUserGroup(user) != null);
        result.data = data;
        return result;
    }

    // 차량운행기록 조회
    @GetMapping("/read")
    public AjaxResult read(
            @RequestParam(value = "startDate")                  String startDate,
            @RequestParam(value = "endDate")                    String endDate,
            @RequestParam(value = "pernm",   required = false)  String pernm,
            @RequestParam(value = "carnum",  required = false)  String carnum,
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();

        // 사용자(User) 그룹이면 본인 운행기록만, 그 외(관리자)는 전체
        String ownUsername = getOwnUsernameIfUserGroup(user);

        List<Map<String, Object>> items = carManageService.getList(startDate, endDate, pernm, carnum, spjangcd, ownUsername);
        result.data = items;
        return result;
    }

    // ════════════════════════════════════════════════════════
    //  차량운행 등록 (웹) — 본인 명의로만 저장
    // ════════════════════════════════════════════════════════

    // ── 차량 목록 (TB_E047) ──────────────────────────────────
    @GetMapping("/read_vehicle")
    public AjaxResult readVehicle(
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            @RequestParam(value = "keyword",  required = false) String keyword) {
        AjaxResult result = new AjaxResult();
        result.data = carManageService.getVehicleList(spjangcd, keyword);
        return result;
    }

    // ── 유류 단가 정보 (TB_E037_1) ───────────────────────────
    @GetMapping("/read_fuel")
    public AjaxResult readFuel(
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            @RequestParam(value = "fuelcd",   required = false) String fuelcd) {
        AjaxResult result = new AjaxResult();
        result.data = carManageService.getFuelInfo(spjangcd, fuelcd);
        return result;
    }

    // ── 현장 목록 (TB_E601) ──────────────────────────────────
    @GetMapping("/read_site")
    public AjaxResult readSite(
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            @RequestParam(value = "keyword",  required = false) String keyword) {
        AjaxResult result = new AjaxResult();
        result.data = carManageService.getSiteList(spjangcd, keyword);
        return result;
    }

    // ── 운행기록 등록 (항상 본인 username 저장) ───────────────
    @PostMapping("/save")
    public AjaxResult save(
            @RequestParam(value = "startDate")                  String startDate,
            @RequestParam(value = "vehicleCd", required = false) String vehicleCd,
            @RequestParam(value = "fuelKind",  required = false) String fuelKind,
            @RequestParam(value = "siteCd",    required = false) String siteCd,
            @RequestParam(value = "totalKM",   required = false, defaultValue = "0") String totalKM,
            @RequestParam(value = "liter",     required = false, defaultValue = "0") String liter,
            @RequestParam(value = "unitAmt",   required = false, defaultValue = "0") String unitAmt,
            @RequestParam(value = "total",     required = false, defaultValue = "0") String total,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();

        Map<String, Object> t = tenantUserService.getUserInfo(user.getUsername());
        if (t == null) {
            result.success = false; result.message = "사용자 정보를 찾을 수 없습니다."; return result;
        }
        String spjangcd = (String) t.get("spjangcd");
        String username = user.getUsername();   // TB_E037_CONF.perid = username

        if (vehicleCd == null || vehicleCd.isBlank()) {
            result.success = false; result.message = "차량을 선택해주세요."; return result;
        }

        try {
            carManageService.saveRun(
                    spjangcd, username, startDate,
                    vehicleCd, fuelKind, siteCd,
                    parseD(totalKM), parseD(liter), parseD(unitAmt), parseD(total)
            );
            result.success = true;
            result.message = "운행기록이 등록되었습니다.";
        } catch (Exception e) {
            log.error("차량운행 등록 오류", e);
            result.success = false;
            result.message = "등록 중 오류가 발생하였습니다: " + e.getMessage();
        }
        return result;
    }

    // ── 운행기록 수정 (User면 본인 것만) ─────────────────────
    @PostMapping("/update")
    public AjaxResult update(
            @RequestParam("kcdate")                              String kcdate,
            @RequestParam("kcnum")                               String kcnum,
            @RequestParam(value = "newKcdate", required = false) String newKcdate,
            @RequestParam(value = "siteCd",    required = false) String siteCd,
            @RequestParam(value = "fuelKind",  required = false) String fuelKind,
            @RequestParam(value = "totalKM",   required = false, defaultValue = "0") String totalKM,
            @RequestParam(value = "liter",     required = false, defaultValue = "0") String liter,
            @RequestParam(value = "unitAmt",   required = false, defaultValue = "0") String unitAmt,
            @RequestParam(value = "total",     required = false, defaultValue = "0") String total,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        Map<String, Object> t = tenantUserService.getUserInfo(user.getUsername());
        if (t == null) { result.success = false; result.message = "사용자 정보를 찾을 수 없습니다."; return result; }
        String spjangcd    = (String) t.get("spjangcd");
        String ownUsername = getOwnUsernameIfUserGroup(user);   // User면 본인 username, 관리자면 null(전체)

        try {
            int cnt = carManageService.updateRun(spjangcd, kcdate, kcnum, ownUsername,
                    newKcdate, siteCd, fuelKind,
                    parseD(totalKM), parseD(liter), parseD(unitAmt), parseD(total));
            if (cnt > 0) { result.success = true;  result.message = "수정되었습니다."; }
            else         { result.success = false; result.message = "수정 권한이 없거나 대상이 없습니다."; }
        } catch (Exception e) {
            log.error("차량운행 수정 오류", e);
            result.success = false;
            result.message = "수정 중 오류가 발생하였습니다: " + e.getMessage();
        }
        return result;
    }

    // ── 운행기록 삭제 (User면 본인 것만) ─────────────────────
    @PostMapping("/delete")
    public AjaxResult delete(
            @RequestParam("kcdate") String kcdate,
            @RequestParam("kcnum")  String kcnum,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        Map<String, Object> t = tenantUserService.getUserInfo(user.getUsername());
        if (t == null) { result.success = false; result.message = "사용자 정보를 찾을 수 없습니다."; return result; }
        String spjangcd    = (String) t.get("spjangcd");
        String ownUsername = getOwnUsernameIfUserGroup(user);

        try {
            int cnt = carManageService.deleteRun(spjangcd, kcdate, kcnum, ownUsername);
            if (cnt > 0) { result.success = true;  result.message = "삭제되었습니다."; }
            else         { result.success = false; result.message = "삭제 권한이 없거나 대상이 없습니다."; }
        } catch (Exception e) {
            log.error("차량운행 삭제 오류", e);
            result.success = false;
            result.message = "삭제 중 오류가 발생하였습니다.";
        }
        return result;
    }

    private double parseD(String v) {
        if (v == null || v.isBlank()) return 0.0;
        try { return Double.parseDouble(v.replace(",", "")); }
        catch (NumberFormatException e) { return 0.0; }
    }
}
