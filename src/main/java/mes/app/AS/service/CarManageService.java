package mes.app.AS.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class CarManageService {

    @Autowired
    SqlRunner sqlRunner;

    /**
     * 차량운행기록 조회 (TB_E037_CONF)
     * - TB_JA001  : perid  → pernm  (사원명)
     * - TB_E047   : carcd  → carnum (차량번호)
     * - TB_E037_1 : gubun  → fuelnm (유종명)
     * ※ TB_E601 (현장명) JOIN은 실제 컬럼명 확인 후 추가 예정
     */
    public List<Map<String, Object>> getList(
            String startDate,
            String endDate,
            String pernm,
            String carnum,
            String spjangcd) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("startDate", startDate);
        param.addValue("endDate",   endDate);
        param.addValue("spjangcd",  spjangcd);

        String sql = """
                SELECT
                    c.kcdate,
                    j.pernm,
                    e.carnum,
                    c.km,
                    f.fuelnm,
                    c.samt
                FROM TB_E037_CONF c
                LEFT JOIN TB_JA001  j ON j.perid   = 'p'+c.perid
                LEFT JOIN TB_E047   e ON e.carcd    = c.carcd
                LEFT JOIN TB_E037_1 f ON f.fuelcd   = c.gubun
                                     AND f.spjangcd = c.spjangcd
                WHERE c.spjangcd = :spjangcd
                  AND c.kcdate  BETWEEN :startDate AND :endDate
                """;

        // 사원명 검색
        if (pernm != null && !pernm.isBlank()) {
            sql += " AND j.pernm LIKE :pernm";
            param.addValue("pernm", "%" + pernm.trim() + "%");
        }

        // 차량번호 검색
        if (carnum != null && !carnum.isBlank()) {
            sql += " AND e.carnum LIKE :carnum";
            param.addValue("carnum", "%" + carnum.trim() + "%");
        }

        sql += " ORDER BY c.kcdate DESC, j.pernm ASC";

        return sqlRunner.getRows(sql, param);
    }
}
