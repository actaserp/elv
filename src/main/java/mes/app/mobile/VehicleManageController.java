package mes.app.mobile;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantUserService;
import mes.app.mobile.Service.VehicleManageService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@Transactional
@RequestMapping("/api/vehicle_manage")
public class VehicleManageController {

    @Autowired
    VehicleManageService vehicleManageService;

    @Autowired
    TenantUserService tenantUserService;

    @GetMapping("/read_userInfo")
    public AjaxResult getUserInfo(HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();

        Map<String, Object> tenantInfo = tenantUserService.getUserInfo(user.getUsername());
        if (tenantInfo == null) {
            result.message = "사업체 DB에서 유저 정보를 찾을 수 없습니다.";
            return result;
        }
        int personId = ((Number) tenantInfo.get("personid")).intValue();

        Map<String, Object> userInfo = vehicleManageService.getUserInfo(personId);
        if (userInfo == null) {
            result.message = "사원 정보를 찾을 수 없습니다.";
            return result;
        }

        // tenantInfo + userInfo 합치고 login_id 추가해서 반환
        userInfo.putAll(tenantInfo);
        userInfo.put("login_id", user.getUsername());

        result.data = userInfo;
        return result;
    }

    @GetMapping("/getSiteList")
    public AjaxResult getSiteList(
            @RequestParam(value = "keyword", required = false) String keyword,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        String spjangcd = tenantUserService.getSpjangcd(user.getUsername());
        result.data = vehicleManageService.getSiteList(spjangcd, keyword);
        return result;
    }

    @GetMapping("/getFuelInfo")
    public AjaxResult getFuelInfo(
            @RequestParam(value = "fuelcd") String fuelcd,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        String spjangcd = tenantUserService.getSpjangcd(user.getUsername());
        result.data = vehicleManageService.getFuelInfo(spjangcd, fuelcd);
        return result;
    }

    @GetMapping("/getVehicleList")
    public AjaxResult getVehicleList(
            @RequestParam(value = "keyword", required = false) String keyword,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        String spjangcd = tenantUserService.getSpjangcd(user.getUsername());
        result.data = vehicleManageService.getVehicleList(spjangcd, keyword);
        return result;
    }

    @PostMapping("/ocrAnalyze")
    public AjaxResult ocrAnalyze(
            @RequestParam("imageFile") MultipartFile imageFile,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        try {
            Map<String, Object> ocrResult = vehicleManageService.extractKmFromImage(imageFile);
            result.data    = ocrResult;
            result.success = (Boolean) ocrResult.getOrDefault("success", false);
        } catch (Exception e) {
            log.error("[OCR] 분석 오류", e);
            result.success = false;
            result.message = "OCR 분석 중 오류가 발생하였습니다.";
        }
        return result;
    }

    @PostMapping("/submitAttendance")
    public AjaxResult submitAttendance(
            @RequestBody Map<String, Object> param,
            Authentication auth) {

        User user = (User) auth.getPrincipal();
        String spjangcd = tenantUserService.getSpjangcd(user.getUsername());
        return vehicleManageService.submitAttendance(param, spjangcd);
    }

    @GetMapping("/read_status")
    public AjaxResult getStatusList(
            @RequestParam(value = "fromDate", required = false) String fromDate,
            @RequestParam(value = "toDate",   required = false) String toDate,
            @RequestParam(value = "carnum",   required = false) String carnum,
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        result.data = vehicleManageService.getStatusList(spjangcd, fromDate, toDate, carnum);
        return result;
    }

    @PostMapping("/update_status")
    public AjaxResult updateStatus(
            @RequestParam(value = "kcdate")    String kcdate,
            @RequestParam(value = "kcnum")     String kcnum,
            @RequestParam(value = "spjangcd")  String spjangcd,
            @RequestParam(value = "newKcdate", required = false) String newKcdate,
            @RequestParam(value = "actcd",     required = false) String actcd,
            @RequestParam(value = "actnm",     required = false) String actnm,
            @RequestParam(value = "gubun",     required = false) String gubun,
            @RequestParam(value = "km",        required = false) String km,
            @RequestParam(value = "liter",     required = false) String liter,
            @RequestParam(value = "uamt",      required = false) String uamt,
            @RequestParam(value = "samt",      required = false) String samt,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        try {
            vehicleManageService.updateStatus(spjangcd, kcdate, kcnum,
                    newKcdate, actcd, actnm, gubun, km, liter, uamt, samt);
            result.success = true;
            result.message = "수정되었습니다.";
        } catch (Exception e) {
            log.error("차량운행 수정 오류", e);
            result.success = false;
            result.message = "수정 중 오류가 발생하였습니다.";
        }
        return result;
    }

    @PostMapping("/delete_status")
    public AjaxResult deleteStatus(
            @RequestParam(value = "kcdate")   String kcdate,
            @RequestParam(value = "kcnum")    String kcnum,
            @RequestParam(value = "spjangcd") String spjangcd,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        try {
            vehicleManageService.deleteStatus(spjangcd, kcdate, kcnum);
            result.success = true;
            result.message = "삭제되었습니다.";
        } catch (Exception e) {
            log.error("차량운행 삭제 오류", e);
            result.success = false;
            result.message = "삭제 중 오류가 발생하였습니다.";
        }
        return result;
    }
}
