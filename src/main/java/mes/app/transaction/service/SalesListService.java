package mes.app.transaction.service;

import mes.app.util.UtilClass;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class SalesListService {

    @Autowired
    SqlRunner sqlRunner;

    public List<Map<String, Object>> getList(Map<String, Object> parameter) {
        MapSqlParameterSource param = new MapSqlParameterSource();

        String spjangcd = UtilClass.getStringSafe(parameter.get("spjangcd"));
        String searchfrdate = UtilClass.getStringSafe(parameter.get("searchfrdate"));
        String searchtodate = UtilClass.getStringSafe(parameter.get("searchtodate"));
        Integer cltcd = UtilClass.parseInteger(parameter.get("cltcd"));
        String taxtype = UtilClass.getStringSafe(parameter.get("taxtype"));
        String misgubun = UtilClass.getStringSafe(parameter.get("misgubun"));

        param.addValue("spjangcd", spjangcd);
        param.addValue("searchfrdate", searchfrdate);
        param.addValue("searchtodate", searchtodate);
        param.addValue("cltcd", cltcd);
        param.addValue("taxtype", taxtype);
        param.addValue("misgubun", misgubun);

        String sql = """
                SELECT
                    to_char(to_date(a.misdate, 'YYYYMMDD'), 'YYYY-MM-DD') AS misdate,
                    b.spjangcd,
                    s."Value" AS misgubun,
                    c."Code" AS companyCode,
                    c."Name" AS companyName,
                    b.iveremail,
                    a.itemnm,
                    a.spec,
                    COALESCE(a.supplycost, 0) AS supplycost,
                    COALESCE(a.taxtotal, 0) AS taxtotal,
                    (COALESCE(a.supplycost, 0) + COALESCE(a.taxtotal, 0)) AS totalamt,
                    b.statecode
                FROM tb_salesdetail a
                LEFT JOIN tb_salesment b ON a.misdate = b.misdate AND a.misnum = b.misnum
                LEFT JOIN company c ON b.cltcd = c.id
                LEFT JOIN sys_code s ON s."Code" = b.misgubun
                WHERE b.spjangcd = :spjangcd
                  AND a.misdate BETWEEN :searchfrdate AND :searchtodate
                """;

        if (cltcd != null) {
            sql += " AND b.cltcd = :cltcd\n";
        }
        if (taxtype != null && !taxtype.isEmpty()) {
            sql += " AND b.taxtype = :taxtype\n";
        }
        if (misgubun != null && !misgubun.isEmpty()) {
            sql += " AND b.misgubun = :misgubun\n";
        }

        sql += " ORDER BY a.misdate, b.misgubun DESC";

        return sqlRunner.getRows(sql, param);
    }

    public List<Map<String, Object>> getList2(Map<String, Object> parameter) {
        MapSqlParameterSource param = new MapSqlParameterSource();

        String spjangcd = UtilClass.getStringSafe(parameter.get("spjangcd"));
        String searchfrdate = UtilClass.getStringSafe(parameter.get("searchfrdate"));
        String searchtodate = UtilClass.getStringSafe(parameter.get("searchtodate"));
        Integer cltcd = UtilClass.parseInteger(parameter.get("cltcd"));
        String taxtype = UtilClass.getStringSafe(parameter.get("taxtype"));
        String misgubun = UtilClass.getStringSafe(parameter.get("misgubun"));

        param.addValue("spjangcd", spjangcd);
        param.addValue("searchfrdate", searchfrdate);
        param.addValue("searchtodate", searchtodate);
        param.addValue("cltcd", cltcd);
        param.addValue("taxtype", taxtype);
        param.addValue("misgubun", misgubun);

        String sql = """
                SELECT
                    ivercorpnum,
                    COUNT(ivercorpnum) AS cnt,
                    ivercorpnm,
                    SUM(supplycost) AS supplycost,
                    SUM(taxtotal) AS taxtotal
                FROM tb_salesment
                WHERE spjangcd = :spjangcd
                  AND misdate BETWEEN :searchfrdate AND :searchtodate
                """;

        if (cltcd != null) {
            sql += " AND cltcd = :cltcd\n";
        }
        if (taxtype != null && !taxtype.isEmpty()) {
            sql += " AND taxtype = :taxtype\n";
        }
        if (misgubun != null && !misgubun.isEmpty()) {
            sql += " AND misgubun = :misgubun\n";
        }

        sql += " GROUP BY ivercorpnum, ivercorpnm ORDER BY ivercorpnum";

        return sqlRunner.getRows(sql, param);
    }
}
