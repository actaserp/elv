package mes.app.AS;

import lombok.extern.slf4j.Slf4j;
import mes.app.AS.service.DailyStatusService;
import mes.app.common.TenantUserService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/AS/daily_status")
public class DailyStatusController {

    @Autowired
    DailyStatusService dailyStatusService;

    @Autowired
    TenantUserService tenantUserService;

    @Autowired
    @Qualifier("mainSqlRunner")
    SqlRunner mainSqlRunner;

    // 로그인 사용자가 '사용자(User)' 그룹에 속하는지 — 로그인 사업장(dbKey) 한정, rela_data + user_profile 확인
    private boolean isUserGroup(User user) {
        try {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("userId", user.getId());
            p.addValue("dbKey",  user.getDbKey());
            String sql = """
                    SELECT COUNT(*) AS cnt FROM (
                        SELECT ug."Code" AS code
                        FROM rela_data rd
                        JOIN user_group ug ON ug.id = rd."DataPk2"
                                          AND ug.spjangcd = :dbKey
                        WHERE rd."RelationName" = 'auth_user-user_group'
                          AND rd."DataPk1" = :userId
                          AND rd."Char1"   = 'Y'
                        UNION ALL
                        SELECT ug."Code"
                        FROM user_profile up
                        JOIN user_group ug ON ug.id = up."UserGroup_id"
                                          AND ug.spjangcd = up.spjangcd
                        WHERE up."User_id" = :userId
                          AND up.spjangcd  = :dbKey
                    ) t WHERE LOWER(t.code) = 'user'
                    """;
            Map<String, Object> row = mainSqlRunner.getRow(sql, p);
            return row != null && row.get("cnt") != null && ((Number) row.get("cnt")).intValue() > 0;
        } catch (Exception e) {
            log.warn("[daily_status] 사용자 그룹 판별 실패 username={}", user.getUsername(), e);
            return false;
        }
    }

    // User 그룹이면 본인 perid('p' 제거) 반환, 아니면 null
    private String getOwnPeridIfUserGroup(User user) {
        if (!isUserGroup(user)) return null;
        Map<String, Object> userInfo = tenantUserService.getUserInfo(user.getUsername());
        if (userInfo == null || userInfo.get("perid") == null) return null;
        return ((String) userInfo.get("perid")).replaceFirst("^p", "");
    }

    /**
     * 업무일지 작성현황 조회
     * - 사원별 + 해당 월 작성된 날짜(rptdate) 목록 반환
     * - User 그룹이면 본인 행만 반환
     */
    @GetMapping("/read")
    public AjaxResult read(
            @RequestParam(value = "year")                         String year,
            @RequestParam(value = "month")                        String month,
            @RequestParam(value = "dept",     required = false)   String dept,
            @RequestParam(value = "retire",   required = false)   String retire,
            @RequestParam(value = "spjangcd", required = false)   String spjangcd,
            HttpServletRequest request,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();

        // 사용자(User) 그룹이면 본인 것만, 그 외(관리자)는 전체
        String ownPerid = getOwnPeridIfUserGroup(user);

        List<Map<String, Object>> items =
                dailyStatusService.getStatusList(year, month, dept, retire, spjangcd, ownPerid);
        result.data = items;
        return result;
    }
}
