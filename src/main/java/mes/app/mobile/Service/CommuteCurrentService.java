package mes.app.mobile.Service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class CommuteCurrentService {
    @Autowired
    SqlRunner sqlRunner;

    public List<Map<String, Object>> getUserInfo(String username, String workcd, String searchFromDate, String searchToDate) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("username", username);
        dicParam.addValue("workcd", workcd);

        // 날짜 포맷 처리 (yyyy-MM-dd -> yyyyMMdd)
        String fromDate = searchFromDate.replace("-", ""); // yyyyMMdd
        String toDate = searchToDate.replace("-", "");     // yyyyMMdd

        dicParam.addValue("fromDate", fromDate);
        dicParam.addValue("toDate", toDate);

        String sql = """
            SELECT
                t.workym,
                t.workday,
                t.perid,
                t.worknum,
                t.holiyn,
                t.workyn,
                t.workcd,
                td.worknm,
                t.starttime,
                t.endtime,
                t.worktime,
                t.nomaltime,
                t.overtime,
                t.nighttime,
                t.holitime,
                t.jitime,
                t.jotime,
                t.yuntime,
                t.abtime,
                t.bantime,
                t.remark,
                t.fixflag,
                t.address,
                a.first_name,
                STUFF(
                    CASE WHEN t.jitime = 1 THEN ', 지각' ELSE '' END +
                    CASE WHEN t.jotime = 1 THEN ', 조퇴' ELSE '' END +
                    CASE WHEN t.yuntime = 1 THEN ', 연차' ELSE '' END +
                    CASE WHEN t.abtime = 1 THEN ', 결근' ELSE '' END +
                    CASE WHEN t.bantime = 1 THEN ', 반차' ELSE '' END
                , 1, 2, '') AS status_text
            FROM tb_pb201 t
            LEFT JOIN auth_user a ON a.personid = t.perid
            LEFT JOIN person p ON p.id = a.personid
            LEFT JOIN tb_pb210 td ON t.workcd = td.workcd
            WHERE 1=1
              AND a.username = :username
           """;

        if (workcd != null && !workcd.isEmpty()) {
            sql += " AND t.workcd = :workcd ";
        }

        // ✅ workym + workday 합쳐서 8자리 문자열로 비교 (MSSQL)
        sql += """
            AND (t.workym + t.workday) >= :fromDate
            AND (t.workym + t.workday) <= :toDate
           """;

        sql += " ORDER BY t.workym DESC, t.workday DESC ";

        return this.sqlRunner.getRows(sql, dicParam);
    }

}
