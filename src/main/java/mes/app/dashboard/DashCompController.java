package mes.app.dashboard;

import mes.app.annotation.ApiProduct;
import mes.app.dashboard.service.DashCompService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

@ApiProduct(ApiProduct.P01)
@RestController
@RequestMapping("/api/dash_comp")
public class DashCompController {

    @Autowired
    DashCompService dashCompService;

    @GetMapping("/contract_count")
    public AjaxResult getContractCount(@RequestParam String spjangcd,
            HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        result.data = dashCompService.getContractCount(spjangcd);
        return result;
    }

    @GetMapping("/contract_list")
    public AjaxResult getContractList(@RequestParam String spjangcd,
            HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        result.data = dashCompService.getContractList(spjangcd);
        return result;
    }

    @GetMapping("/expire_count")
    public AjaxResult getExpireCount(@RequestParam String spjangcd,
            HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        result.data = dashCompService.getExpireCount(spjangcd);
        return result;
    }

    @GetMapping("/expire_list")
    public AjaxResult getExpireList(@RequestParam String spjangcd,
            HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        result.data = dashCompService.getExpireList(spjangcd);
        return result;
    }

    @GetMapping("/manage_count")
    public AjaxResult getManageCount(@RequestParam String spjangcd,
            HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        result.data = dashCompService.getManageCount(spjangcd);
        return result;
    }

    @GetMapping("/manage_list")
    public AjaxResult getManageList(@RequestParam String spjangcd,
            HttpServletRequest request, Authentication auth) {
        AjaxResult result = new AjaxResult();
        result.data = dashCompService.getManageList(spjangcd);
        return result;
    }
}
