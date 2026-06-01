package mes.app.AS;

import lombok.extern.slf4j.Slf4j;
import mes.app.AS.service.WebCompanyService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

@Slf4j
@RestController
@Transactional
@RequestMapping("/api/AS/web_company")
public class WebCompanyController {

    @Autowired
    WebCompanyService webCompanyService;

    // ── 현장 목록 조회 (TB_E601) ──────────────────────────────
    @GetMapping("/list")
    public AjaxResult getSiteList(
            @RequestParam(value = "spjangcd")                    String spjangcd,
            @RequestParam(value = "keyword",  required = false)  String keyword,
            @RequestParam(value = "actgubun", required = false)  String actgubun,
            HttpServletRequest request) {
        AjaxResult result = new AjaxResult();
        result.data = webCompanyService.getSiteList(spjangcd, keyword, actgubun);
        return result;
    }

    // ── 호기 목록 조회 (TB_E611) ──────────────────────────────
    @GetMapping("/equp_list")
    public AjaxResult getEqupList(
            @RequestParam(value = "spjangcd")           String spjangcd,
            @RequestParam(value = "actcd", required = false) String actcd,
            HttpServletRequest request) {
        AjaxResult result = new AjaxResult();
        result.data = webCompanyService.getEqupList(spjangcd, actcd);
        return result;
    }
}
