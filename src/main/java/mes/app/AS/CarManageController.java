package mes.app.AS;

import lombok.extern.slf4j.Slf4j;
import mes.app.AS.service.CarManageService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/AS/car_manage")
public class CarManageController {

    @Autowired
    CarManageService carManageService;

    // 차량운행기록 조회
    @GetMapping("/read")
    public AjaxResult read(
            @RequestParam(value = "startDate")                  String startDate,
            @RequestParam(value = "endDate")                    String endDate,
            @RequestParam(value = "pernm",   required = false)  String pernm,
            @RequestParam(value = "carnum",  required = false)  String carnum,
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            HttpServletRequest request) {

        AjaxResult result = new AjaxResult();
        List<Map<String, Object>> items = carManageService.getList(startDate, endDate, pernm, carnum, spjangcd);
        result.data = items;
        return result;
    }
}
