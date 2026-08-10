package mes.app.dashboard;

import mes.app.annotation.ApiProduct;
import mes.app.dashboard.service.DashBreakService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

@ApiProduct(ApiProduct.P01)
@RestController
@RequestMapping("/api/dash_break")
public class DashBreakController {

    @Autowired
    DashBreakService dashBreakService;

    @GetMapping("/receive_count")
    public AjaxResult getReceiveCount(@RequestParam String spjangcd,
            HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        result.data = dashBreakService.getReceiveCount(spjangcd);
        return result;
    }

    @GetMapping("/receive_list")
    public AjaxResult getReceiveList(@RequestParam String spjangcd,
            HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        result.data = dashBreakService.getReceiveList(spjangcd);
        return result;
    }

    @GetMapping("/handle_count")
    public AjaxResult getHandleCount(@RequestParam String spjangcd,
            HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        result.data = dashBreakService.getHandleCount(spjangcd);
        return result;
    }

    @GetMapping("/handle_list")
    public AjaxResult getHandleList(@RequestParam String spjangcd,
            HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        result.data = dashBreakService.getHandleList(spjangcd);
        return result;
    }

    @GetMapping("/expire_count")
    public AjaxResult getExpireCount(@RequestParam String spjangcd,
            HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        result.data = dashBreakService.getExpireCount(spjangcd);
        return result;
    }

    @GetMapping("/expire_list")
    public AjaxResult getExpireList(@RequestParam String spjangcd,
            HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        result.data = dashBreakService.getExpireList(spjangcd);
        return result;
    }

    @GetMapping("/manage_count")
    public AjaxResult getManageCount(@RequestParam String spjangcd,
            HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        result.data = dashBreakService.getManageCount(spjangcd);
        return result;
    }

    @GetMapping("/manage_list")
    public AjaxResult getManageList(@RequestParam String spjangcd,
            HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        result.data = dashBreakService.getManageList(spjangcd);
        return result;
    }
}
