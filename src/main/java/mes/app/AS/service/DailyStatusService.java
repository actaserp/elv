package mes.app.AS.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class DailyStatusService {

    @Autowired
    SqlRunner sqlRunner;

    /**
     * 업무일지 작성현황 조회
     * - TB_JA001.perid = 'p' + TB_E037.perid
     * - TB_JC002 : 부서명
     * - TB_PZ001 : 직급명
     * - 작성한 날짜만 rptdate 로 row 반환 (1인 N건)
     * - 미작성 사원도 rptdate=NULL 로 1건 반환
     */
    public List<Map<String, Object>> getStatusList(
            String year,
            String month,
            String dept,
            String retire,
            String spjangcd,
            String ownPerid) {

        String yyyyMM    = year + month;
        String startDate = yyyyMM + "01";
        String endDate   = yyyyMM + "31";

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("startDate", startDate);
        param.addValue("endDate",   endDate);
        param.addValue("spjangcd",  spjangcd);

        String sql = """
                SELECT
                    j.perid,
                    j.pernm,
                    pz.RSPNM    AS clanm,
                    jc.divinm,
                    e.rptdate
                FROM TB_JA001 j
                LEFT JOIN TB_JC002 jc ON j.divicd   = jc.divicd
                LEFT JOIN TB_PZ001 pz ON j.rspcd    = pz.RSPCD
                LEFT JOIN (
                    SELECT DISTINCT perid, spjangcd, rptdate
                    FROM TB_E037
                    WHERE spjangcd = :spjangcd
                      AND rptdate BETWEEN :startDate AND :endDate
                ) e ON j.perid    = 'p' + e.perid
                   AND j.spjangcd = e.spjangcd
                WHERE j.spjangcd = :spjangcd
                """;

        if (dept != null && !dept.isBlank()) {
            sql += " AND jc.divinm LIKE :dept";
            param.addValue("dept", "%" + dept.trim() + "%");
        }

        if (retire != null && !retire.isBlank()) {
            sql += " AND j.rtclafi = :retire";
            param.addValue("retire", retire);
        }

        // 사용자(User) 그룹: 본인 행만 (TB_JA001.perid = 'p' + ownPerid)
        if (ownPerid != null && !ownPerid.isBlank()) {
            sql += " AND j.perid = 'p' + :ownPerid";
            param.addValue("ownPerid", ownPerid);
        }

        sql += " ORDER BY jc.divinm ASC, j.pernm ASC, e.rptdate ASC";

        return sqlRunner.getRows(sql, param);
    }
}
