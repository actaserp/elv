package mes.app.AS.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class WebCompanyService {

    @Autowired
    SqlRunner sqlRunner;

    // ── 현장 목록 조회 (TB_E601) ──────────────────────────────
    public List<Map<String, Object>> getSiteList(
            String spjangcd, String keyword, String equpcd, String tel, String actgubun,
            String cltnum, String emtelnum) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT
                    e.actcd,
                    e.actnm,
                    e.cltcd,
                    e.cltnum,
                    e.actgubun,
                    e.bildyd,
                    e.bildlv,
                    e.bildju,
                    e.bilddate,
                    e.actperid,
                    e.actpernm,
                    e.divicd,
                    jc.divinm,
                    e.actmail,
                    e.tel,
                    e.hp,
                    e.fax,
                    e.areacd,
                    e.gareacd,
                    e.zipcode,
                    e.address,
                    e.address2,
                    e.stdate,
                    e.enddate,
                    e.gubun,
                    e.remark
                FROM TB_E601 e
                LEFT JOIN TB_JC002 jc ON e.divicd   = jc.divicd
                                     AND e.spjangcd  = jc.spjangcd
                WHERE e.spjangcd = :spjangcd
                """;

        if (keyword != null && !keyword.trim().isEmpty()) {
            sql += " AND e.actnm LIKE :keyword";
            param.addValue("keyword", "%" + keyword.trim() + "%");
        }
        if (equpcd != null && !equpcd.trim().isEmpty()) {
            sql += " AND EXISTS (SELECT 1 FROM TB_E611 eq WHERE eq.actcd = e.actcd AND eq.spjangcd = e.spjangcd AND eq.equpcd LIKE :equpcd)";
            param.addValue("equpcd", "%" + equpcd.trim() + "%");
        }
        if (tel != null && !tel.trim().isEmpty()) {
            // 전화번호: '-' 포맷 무시하고 숫자만 비교
            sql += " AND REPLACE(e.tel, '-', '') LIKE :tel";
            param.addValue("tel", "%" + tel.trim().replace("-", "") + "%");
        }
        if (actgubun != null && !actgubun.trim().isEmpty()) {
            sql += " AND e.actgubun = :actgubun";
            param.addValue("actgubun", actgubun.trim());
        }

        if (cltnum != null && !cltnum.trim().isEmpty()) {
            sql += " AND e.cltnum LIKE :cltnum";   // 프로젝트번호
            param.addValue("cltnum", "%" + cltnum.trim() + "%");
        }

        if (emtelnum != null && !emtelnum.trim().isEmpty()) {
            // 비상통화장치번호 (호기 TB_E611)
            sql += " AND EXISTS (SELECT 1 FROM TB_E611 q2 WHERE q2.spjangcd = e.spjangcd AND q2.actcd = e.actcd AND q2.emtelnum LIKE :emtelnum)";
            param.addValue("emtelnum", "%" + emtelnum.trim() + "%");
        }

        sql += " ORDER BY e.actnm ASC";
        return this.sqlRunner.getRows(sql, param);
    }

    // ── 호기 목록 조회 (TB_E611) ──────────────────────────────
    public List<Map<String, Object>> getEqupList(String spjangcd, String actcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("actcd",    actcd);

        String sql = """
                SELECT equpcd, equpnm, actcd, emtelnum
                FROM TB_E611 WITH(NOLOCK)
                WHERE spjangcd = :spjangcd
                  AND actcd    = :actcd
                ORDER BY equpcd ASC
                """;

        return this.sqlRunner.getRows(sql, param);
    }
}
