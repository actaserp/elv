package mes.app.mobile;

import lombok.extern.slf4j.Slf4j;
import mes.app.mobile.Service.VehicleManageService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

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

    /**
     * 유류 단가 정보 조회
     * 유종(fuelcd) 선택 시 TB_E037_1에서 uamt(단가), kmliter(연비), unit(단위) 반환
     */
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

    /**
     * 차량 목록 조회 (TB_E047 전체)
     * 차량번호(carnum) 키워드 검색 지원
     */
    @GetMapping("/getVehicleList")
    public AjaxResult getVehicleList(
            @RequestParam(value = "keyword", required = false) String keyword,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        List<Map<String, Object>> items = vehicleManageService.getVehicleList(keyword);
        result.data = items;
        return result;
    }
}
