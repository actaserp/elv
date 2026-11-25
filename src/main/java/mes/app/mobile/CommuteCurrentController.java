package mes.app.mobile;

import mes.app.annotation.ApiProduct;
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

@ApiProduct(ApiProduct.P02)
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

        // workym('YYYYMM')·workday('DD') 는 그대로 내려보낸다.
        // 예전에는 여기서 'YYYY.MM.DD' 로 바꿔 두고 화면에서 또 잘라내 '26-.1-24' 처럼 깨졌다.
        // 날짜 표시는 화면(formatDateToYYMMDD)이 한 곳에서만 한다.
        List<Map<String, Object>> data = commuteCurrentService.getUserInfo(tenantUsername, workcd, searchFromDate, searchToDate);
        result.data = data;
        return result;
    }
}
