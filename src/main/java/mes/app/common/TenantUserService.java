package mes.app.common;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 사업체DB 사용자 정보 공통 조회 서비스
 *
 * mobile, AS 경로 Controller에서 user.getSpjangcd(), user.getPersonid() 대신 사용
 * 본사DB username → personid → 사업체DB person.Code → TB_JA001 (spjangcd, custcd) 조회
 */
@Slf4j
@Service
public class TenantUserService {

    @Autowired
    @Qualifier("mainSqlRunner")
    SqlRunner mainSqlRunner;

    @Autowired
    SqlRunner tenantSqlRunner; // 사업체DB (@Primary)

    /**
     * 본사DB username → personid 조회
     * → 사업체DB person.id → TB_JA001 + TB_JC002 + TB_PZ001 조인으로 전체 사원 정보 조회
     *
     * @return { personid, spjangcd, custcd, perid, pernm, divinm, rspnm, username }
     */
    public Map<String, Object> getUserInfo(String username) {
        // 1. 본사DB에서 personid 조회
        MapSqlParameterSource mainParam = new MapSqlParameterSource();
        mainParam.addValue("username", username);

        String mainSql = """
                SELECT personid
                FROM auth_user
                WHERE username = :username
                """;

        List<Map<String, Object>> mainRows = mainSqlRunner.getRows(mainSql, mainParam);
        if (mainRows.isEmpty()) {
            log.warn("[TenantUserService] 본사DB에서 username={} 를 찾을 수 없습니다.", username);
            return null;
        }

        Object personid = mainRows.get(0).get("personid");
        if (personid == null) {
            log.warn("[TenantUserService] username={} 의 personid가 null 입니다.", username);
            return null;
        }

        // 2. 사업체DB에서 personid → TB_JA001 + 부서 + 직책 + auth_user 조인
        MapSqlParameterSource tenantParam = new MapSqlParameterSource();
        tenantParam.addValue("personid", personid);

        String tenantSql = """
                SELECT TOP 1
                    p.id              AS personid,
                    p.PersonGroup_id,
                    j.custcd,
                    COALESCE(j.spjangcd, p.spjangcd) AS spjangcd,
                    j.perid,
                    j.pernm,
                    jc.divinm,
                    pz.RSPNM          AS rspnm,
                    u.username        AS username
                FROM person p
                LEFT JOIN TB_JA001 j  ON j.perid    = p.Code
                                     AND j.spjangcd = p.spjangcd
                LEFT JOIN TB_JC002 jc ON jc.divicd  = j.divicd
                                     AND jc.spjangcd = j.spjangcd
                LEFT JOIN TB_PZ001 pz ON pz.RSPCD   = j.rspcd
                LEFT JOIN auth_user u ON u.personid  = p.id
                WHERE p.id = :personid
                """;

        List<Map<String, Object>> tenantRows = tenantSqlRunner.getRows(tenantSql, tenantParam);
        if (tenantRows.isEmpty()) {
            log.warn("[TenantUserService] 사업체DB에서 personid={} 를 찾을 수 없습니다.", personid);
            return null;
        }

        return tenantRows.get(0);
    }

    /**
     * 사업체DB spjangcd 조회
     */
    public String getSpjangcd(String username) {
        Map<String, Object> info = getUserInfo(username);
        return info != null ? (String) info.get("spjangcd") : null;
    }

    /**
     * 사업체DB personid 조회
     */
    public Integer getPersonid(String username) {
        Map<String, Object> info = getUserInfo(username);
        if (info == null) return null;
        Object personid = info.get("personid");
        return personid != null ? ((Number) personid).intValue() : null;
    }
}
