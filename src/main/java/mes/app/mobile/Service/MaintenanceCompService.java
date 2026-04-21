package mes.app.mobile.Service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class MaintenanceCompService {
    @Autowired
    SqlRunner sqlRunner;

    // 사용자 정보 조회
    public Map<String, Object> getUserInfo(String username) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("username", username);

        String sql = """
                SELECT TOP 1
                          a.username,
                          a.first_name,
                          p.id,
                          an.restnum,
                          t.sttime
                      FROM auth_user a
                      LEFT JOIN tb_pb209 an ON an.perid = a.personid
                      LEFT JOIN person p ON p.id = a.personid
                      LEFT JOIN tb_pbcont t ON t.flag = RIGHT('0' + CAST(p.PersonGroup_id AS VARCHAR), 2)
                      WHERE a.username = :username
                      ORDER BY an.todate DESC
        		""";


        Map<String, Object> item = this.sqlRunner.getRow(sql, dicParam);

        return item;
    }

    // 결재구분별 결재라인 및 정보 조회(결재자 직원코드)
    public List<Map<String, Object>> getAppInfoList (int personid) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("personid", personid);

        String sql = """
                SELECT
                      *
                  FROM tb_e064 e
                  WHERE e.papercd = '301'
                  AND e.perid = :personid
                  ORDER BY e.SEQ ASC
        		""";


        List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);

        return items;
    }
    // 휴가항목 선택시 근태설정 고정값있는지 확인
    public Map<String, Object> getPeriod (String attKind) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("attKind", attKind);

        String sql = """
                SELECT
                      yearflag,
                      usenum
                  FROM tb_pb210
                  WHERE workcd = :attKind
        		""";


        Map<String, Object> item = this.sqlRunner.getRow(sql, dicParam);

        return item;
    }

}
