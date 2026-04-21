package mes.app.mobile;

import lombok.extern.slf4j.Slf4j;
import mes.app.mobile.Service.MaintenanceCompService;
import mes.domain.entity.User;
import mes.domain.entity.approval.TB_E080;
import mes.domain.entity.approval.TB_E080_PK;
import mes.domain.entity.mobile.TB_PB204;
import mes.domain.model.AjaxResult;
import mes.domain.repository.approval.E080Repository;
import mes.domain.repository.mobile.TB_PB204Repository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@Transactional
@RequestMapping("/api/maintenance_comp")
public class MaintenanceCompController {
    @Autowired
    MaintenanceCompService maintenanceCompService;

    // 사용자 정보 조회(부서 이름 출근여부)
    @GetMapping("/read_userInfo")
    public AjaxResult getUserInfo(
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User)auth.getPrincipal();
        String username = user.getUsername();

        Map<String, Object> resultData = maintenanceCompService.getUserInfo(username);

        result.data = resultData;

        return result;
    }

}
