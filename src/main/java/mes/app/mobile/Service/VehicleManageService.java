package mes.app.mobile.Service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class VehicleManageService {
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
                          t.sttime,
                          e.carcd,
                          e.carnum,
                          e.gubun AS fuelcd,
                          e.samt
                      FROM auth_user a
                      LEFT JOIN tb_pb209 an ON an.perid = a.personid
                      LEFT JOIN person p ON p.id = a.personid
                      LEFT JOIN tb_pbcont t ON t.flag = RIGHT('0' + CAST(p.PersonGroup_id AS VARCHAR), 2)
                      LEFT JOIN TB_E047 e ON e.perid = a.username
                      WHERE a.username = :username
                      ORDER BY an.todate DESC
        		""";

        Map<String, Object> item = this.sqlRunner.getRow(sql, dicParam);
        return item;
    }

    // 현장 목록 조회 (TB_E601)
    public List<Map<String, Object>> getSiteList(String spjangcd, String keyword) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT actcd, actnm, address
                FROM TB_E601
                WHERE spjangcd = :spjangcd
                """;

        if (keyword != null && !keyword.trim().isEmpty()) {
            sql += " AND actnm LIKE :keyword";
            dicParam.addValue("keyword", "%" + keyword.trim() + "%");
        }

        sql += " ORDER BY actnm";

        List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
        return items;
    }

    /**
     * 유류 단가 정보 조회 (TB_E037_1)
     * fuelcd 선택 시 해당 유류의 uamt(단가), kmliter(연비), unit(단위) 반환
     */
    public Map<String, Object> getFuelInfo(String spjangcd, String fuelcd) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("spjangcd", spjangcd);
        dicParam.addValue("fuelcd", fuelcd);

        String sql = """
                SELECT TOP 1
                    fuelcd,
                    fuelnm,
                    uamt,
                    kmliter,
                    unit
                FROM TB_E037_1
                WHERE spjangcd = :spjangcd
                  AND fuelcd   = :fuelcd
                  AND useyn    = '1'
                """;

        Map<String, Object> item = this.sqlRunner.getRow(sql, dicParam);
        return item;
    }

    /**
     * 차량 목록 조회 (TB_E047)
     * 전체 차량 조회, 차량번호(carnum) 키워드 검색 지원
     */
    public List<Map<String, Object>> getVehicleList(String keyword) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();

        String sql = """
                SELECT carcd, carnum, gubun AS fuelcd, samt
                FROM TB_E047
                WHERE 1=1
                """;

        if (keyword != null && !keyword.trim().isEmpty()) {
            sql += " AND carnum LIKE :keyword";
            dicParam.addValue("keyword", "%" + keyword.trim() + "%");
        }

        sql += " ORDER BY carnum";

        List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
        return items;
    }
}
