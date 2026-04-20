package mes.app.mobile;

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


    @GetMapping("/read")
    public AjaxResult getVacInfo(
            @RequestParam(value="searchYear") String searchYear,
            HttpServletRequest request,
            Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User)auth.getPrincipal();
        String username = user.getUsername();

        List<Map<String, Object>> data = attendanceStatisticsService.getVacInfo(username, searchYear);

        result.data = data;

        return result;
    }

    @GetMapping("/getUserInfo")
    public AjaxResult getUserInfo(
            HttpServletRequest request,
            Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User)auth.getPrincipal();
        String username = user.getUsername();

        Map<String, Object> userInfo = attendanceStatisticsService.getUserInfo(username);
        result.data = userInfo;

        return result;
    }
}
