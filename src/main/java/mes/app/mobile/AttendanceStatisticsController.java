package mes.app.mobile;

import mes.app.common.TenantUserService;
import mes.app.mobile.Service.AttendanceStatisticsService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/attendance_statistics")
public class AttendanceStatisticsController {

    @Autowired
    AttendanceStatisticsService attendanceStatisticsService;

    @Autowired
    TenantUserService tenantUserService;

    @GetMapping("/read")
    public AjaxResult getVacInfo(
            @RequestParam(value="searchYear") String searchYear,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();

        Integer personId = tenantUserService.getPersonid(user.getUsername());
        if (personId == null) {
            result.message = "사업체 DB에서 유저 정보를 찾을 수 없습니다.";
            return result;
        }

        List<Map<String, Object>> data = attendanceStatisticsService.getVacInfo(personId, searchYear);
        result.data = data;
        return result;
    }

    @GetMapping("/getUserInfo")
    public AjaxResult getUserInfo(
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();

        Map<String, Object> userInfo = tenantUserService.getUserInfo(user.getUsername());
        if (userInfo == null) {
            result.message = "사업체 DB에서 유저 정보를 찾을 수 없습니다.";
            return result;
        }
        result.data = userInfo;
        return result;
    }
}
