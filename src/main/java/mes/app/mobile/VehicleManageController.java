package mes.app.mobile;

import lombok.extern.slf4j.Slf4j;
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

    // 사용자 정보 조회
    @GetMapping("/read_userInfo")
    public AjaxResult getUserInfo(
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        String username = user.getUsername();

        Map<String, Object> resultData = vehicleManageService.getUserInfo(username);
        result.data = resultData;
        return result;
    }

    // 현장 목록 조회
    @GetMapping("/getSiteList")
    public AjaxResult getSiteList(
            @RequestParam(value = "keyword", required = false) String keyword,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        String spjangcd = user.getSpjangcd();

        List<Map<String, Object>> items = vehicleManageService.getSiteList(spjangcd, keyword);
        result.data = items;
        return result;
    }

    // 유류 단가 정보 조회
    @GetMapping("/getFuelInfo")
    public AjaxResult getFuelInfo(
            @RequestParam(value = "fuelcd") String fuelcd,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        String spjangcd = user.getSpjangcd();

        Map<String, Object> item = vehicleManageService.getFuelInfo(spjangcd, fuelcd);
        result.data = item;
        return result;
    }

    // 차량 목록 조회
    @GetMapping("/getVehicleList")
    public AjaxResult getVehicleList(
            @RequestParam(value = "keyword", required = false) String keyword,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        List<Map<String, Object>> items = vehicleManageService.getVehicleList(keyword);
        result.data = items;
        return result;
    }

    // 계기판 사진 OCR 분석 → km 수치 반환
    @PostMapping("/ocrAnalyze")
    public AjaxResult ocrAnalyze(
            @RequestParam("imageFile") MultipartFile imageFile,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        try {
            Map<String, Object> ocrResult = vehicleManageService.extractKmFromImage(imageFile);
            result.data = ocrResult;
            result.success = (Boolean) ocrResult.getOrDefault("success", false);
        } catch (Exception e) {
            log.error("[OCR] 분석 오류", e);
            result.success = false;
            result.message = "OCR 분석 중 오류가 발생했습니다.";
        }
        return result;
    }

    // 차량 운행 등록
    @PostMapping("/submitAttendance")
    public AjaxResult submitAttendance(
            @RequestBody Map<String, Object> param,
            Authentication auth) {

        User user = (User) auth.getPrincipal();
        String spjangcd = user.getSpjangcd();
        return vehicleManageService.submitAttendance(param, spjangcd);
    }
}
