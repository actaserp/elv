package mes.app.mobile;

import mes.app.common.TenantUserService;
import mes.app.mobile.Service.CommuteCurrentService;
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
@RequestMapping("/api/commute_current")
public class CommuteCurrentController {

    @Autowired
    CommuteCurrentService commuteCurrentService;

    @Autowired
    TenantUserService tenantUserService;

    @GetMapping("/read")
    public AjaxResult getUserInfo(
            @RequestParam(value="workcd", required = false) String workcd,
            @RequestParam(value="searchFromDate") String searchFromDate,
            @RequestParam(value="searchToDate") String searchToDate,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();

        Map<String, Object> userInfo = tenantUserService.getUserInfo(user.getUsername());
        if (userInfo == null) {
            result.message = "사업체 DB에서 유저 정보를 찾을 수 없습니다.";
            return result;
        }
        String tenantUsername = userInfo.get("username") != null ? userInfo.get("username").toString() : null;

        List<Map<String, Object>> data = commuteCurrentService.getUserInfo(tenantUsername, workcd, searchFromDate, searchToDate);
        for (Map<String, Object> dataDetail : data) {
            String workym  = (String) dataDetail.get("workym");
            String workday = (String) dataDetail.get("workday");
            if (workym != null && workday != null && workym.length() == 6 && workday.length() == 2) {
                dataDetail.put("workym", workym.substring(0, 4) + "." + workym.substring(4) + "." + workday);
            }
        }
        result.data = data;
        return result;
    }
}
