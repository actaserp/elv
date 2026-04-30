package mes.app.AS.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class DailyManageService {

    @Autowired
    SqlRunner sqlRunner;

    /**
     * 업무일지 조회 (TB_E038)
     * - TB_JA001 : perid → pernm(사원명), clanm(직위명), divinm(부서명)
     * - TB_E021  : wkcd  → businm(구분명)
     */
    public List<Map<String, Object>> getList(
            String startDate,
            String endDate,
            String pernm,
            String spjangcd) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("startDate", startDate);
        param.addValue("endDate",   endDate);
        param.addValue("spjangcd",  spjangcd);

        String sql = """
                SELECT
                    e.rptdate,
                    j.pernm,
                    j.clanm,
                    j.divinm,
                    e.rptnum,
                    b.businm,
                    e.actnm,
                    e.frtime,
                    e.totime,
                    e.equpcd,
                    e.equpnm,
                    e.remark
                FROM TB_E038 e
                LEFT JOIN TB_JA001 j ON j.perid    = e.perid
                                    AND j.spjangcd  = e.spjangcd
                LEFT JOIN TB_E021  b ON b.custcd   = e.custcd
                                    AND b.spjangcd  = e.spjangcd
                                    AND b.busicd    = e.wkcd
                WHERE e.spjangcd = :spjangcd
                  AND e.rptdate BETWEEN :startDate AND :endDate
                """;

        if (pernm != null && !pernm.isBlank()) {
            sql += " AND j.pernm LIKE :pernm";
            param.addValue("pernm", "%" + pernm.trim() + "%");
        }

        sql += " ORDER BY e.rptdate DESC, j.pernm ASC, e.rptnum ASC";

        return sqlRunner.getRows(sql, param);
    }
}
