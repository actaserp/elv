package mes.app.mobile.Service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
public class AttendanceCurrentService {
    @Autowired
    SqlRunner sqlRunner;

    @Autowired
    NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    // 사용자 연차정보 조회
    public Map<String, Object> getAnnInfo(int personId) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("personid", personId);

        String sql = """
                SELECT t.ewolnum,
                    t.holinum,
                    t.daynum,
                    t.restnum,
                    p.rtdate
                FROM tb_pb209 t
                LEFT JOIN person p ON p.id = t.perid
                WHERE perid = :personid
        		""";

        Map<String, Object> item = this.sqlRunner.getRow(sql, dicParam);
        return item;
    }

    // 사용자 휴가정보 조회
    public List<Map<String, Object>> getVacInfo(String workcd, String searchYear, int personId) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("personid", personId);
        dicParam.addValue("workcd", workcd);
        dicParam.addValue("searchYear", searchYear);

        String sql = """
                SELECT t.reqdate,
                    t.id,
                    t.workcd,
                    i.worknm,
                    t.yearflag,
                    t.frdate,
                    t.sttime,
                    t.edtime,
                    t.todate,
                    t.daynum,
                    t.remark,
                    t.appgubun,
                    t.fixflag
                FROM tb_pb204 t
                LEFT JOIN tb_pb210 i ON t.workcd = i.workcd 
                WHERE t.perid = :personid
        		""";
        if (workcd != null && !workcd.isEmpty()) {
            dicParam.addValue("workcd", workcd);
            sql += " AND t.workcd = :workcd";
        }
        if (searchYear != null && !searchYear.isEmpty()) {
            dicParam.addValue("searchYear", searchYear);
            sql += " AND LEFT(t.reqdate, 4) = :searchYear";
        }
        sql += " ORDER BY t.reqdate DESC";

        List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
        return items;
    }

    // 휴가결재데이터 조회(appnum)
    public Map<String, Object> getAppInfo(String appnum) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("appnum", appnum);

        String sql = """
                SELECT *
                FROM tb_e080
                WHERE appnum = :appnum
        		""";

        Map<String, Object> item = this.sqlRunner.getRow(sql, dicParam);
        return item;
    }

    /**
     * [기존] tbPb204Repository.findById(vacId) 대체
     * → tb_pb204에서 id로 단건 조회 (수정/삭제 전 존재여부 확인 및 appnum 등 데이터 취득용)
     */
    public Map<String, Object> selectTbPb204ById(Integer vacId) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("id", vacId);

        String sql = """
                SELECT *
                FROM tb_pb204
                WHERE id = :id
                """;

        return this.sqlRunner.getRow(sql, param);
    }

    /**
     * [기존] tbPb204Repository.save(savedtbPb204) 대체 (updateAttendance)
     * → tb_pb204의 휴가 정보를 id 기준으로 UPDATE
     */
    public void updateTbPb204(Integer vacId, String frdate, String sttime,
                              String todate, String edtime, BigDecimal daynum,
                              String workcd, String remark, String yearflag) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("id", vacId);
        param.addValue("frdate", frdate);
        param.addValue("sttime", sttime);
        param.addValue("todate", todate);
        param.addValue("edtime", edtime);
        param.addValue("daynum", daynum);
        param.addValue("workcd", workcd);
        param.addValue("remark", remark);
        param.addValue("yearflag", yearflag);

        String sql = """
                UPDATE tb_pb204
                SET frdate   = :frdate,
                    sttime   = :sttime,
                    todate   = :todate,
                    edtime   = :edtime,
                    daynum   = :daynum,
                    workcd   = :workcd,
                    remark   = :remark,
                    yearflag = :yearflag
                WHERE id = :id
                """;

        namedParameterJdbcTemplate.update(sql, param);
    }

    /**
     * [기존] tbE080Repository.deleteById(tbE080Pk) 대체
     * → tb_e080에서 appnum + spjangcd 기준으로 결재라인 전체 DELETE
     */
    public void deleteTbE080(String appnum, String spjangcd) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("appnum", appnum);
        param.addValue("spjangcd", spjangcd);

        String sql = """
                DELETE FROM tb_e080
                WHERE appnum   = :appnum
                  AND spjangcd = :spjangcd
                """;

        namedParameterJdbcTemplate.update(sql, param);
    }

    /**
     * [기존] tbPb204Repository.delete(savedtbPb204) 대체
     * → tb_pb204에서 id 기준으로 휴가 데이터 DELETE
     */
    public void deleteTbPb204(Integer vacId) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("id", vacId);

        String sql = """
                DELETE FROM tb_pb204
                WHERE id = :id
                """;

        namedParameterJdbcTemplate.update(sql, param);
    }

    /**
     * [기존] tbPb204Repository.findDistinctYearsByPersonId(personidStr) 대체
     * → tb_pb204에서 perid 기준으로 reqdate 연도 목록을 DISTINCT 조회
     */
    public List<String> getDistinctYears(String personidStr) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("perid", personidStr);

        String sql = """
                SELECT DISTINCT LEFT(reqdate, 4) AS year
                FROM tb_pb204
                WHERE perid = :perid
                ORDER BY year DESC
                """;

        List<Map<String, Object>> rows = this.sqlRunner.getRows(sql, param);

        return rows.stream()
                .map(row -> (String) row.get("year"))
                .toList();
    }
}
