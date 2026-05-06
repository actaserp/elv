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
     *
     * 반환 컬럼:
     *   perid   - 사원ID
     *   pernm   - 사원명
     *   clanm   - 직위명
     *   divinm  - 부서명
     *   rptdate - 작성한 날짜(yyyyMMdd), 미작성이면 NULL
     *
     * 동작 방식:
     *   TB_JA001(사원) LEFT JOIN TB_E037(업무일지 HEAD)
     *   → 작성한 날짜만 rptdate 로 row 반환 (1인 N건)
     *   → 미작성 사원도 rptdate=NULL 로 1건 반환 (프론트에서 0 표시)
     */
    public List<Map<String, Object>> getStatusList(
            String year,
            String month,
            String dept,
            String retire,
            String spjangcd) {

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
                    j.clanm,
                    j.divinm,
                    e.rptdate
                FROM TB_JA001 j
                LEFT JOIN (
                    SELECT DISTINCT perid, spjangcd, rptdate
                    FROM TB_E037
                    WHERE spjangcd = :spjangcd
                      AND rptdate BETWEEN :startDate AND :endDate
                ) e ON e.perid    = j.perid
                   AND e.spjangcd = j.spjangcd
                WHERE j.spjangcd = :spjangcd
                """;

        // 부서명 필터
        if (dept != null && !dept.isBlank()) {
            sql += " AND j.divinm LIKE :dept";
            param.addValue("dept", "%" + dept.trim() + "%");
        }

        // 재직구분 필터 (컬럼명은 실 테이블 기준으로 조정)
        if (retire != null && !retire.isBlank()) {
            sql += " AND j.rtclafi = :retire";
            param.addValue("retire", retire);
        }

        sql += " ORDER BY j.divinm ASC, j.pernm ASC, e.rptdate ASC";

        return sqlRunner.getRows(sql, param);
    }
}
