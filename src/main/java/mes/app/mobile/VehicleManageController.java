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
        result.data = vehicleManageService.getVehicleList(keyword);
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
}
