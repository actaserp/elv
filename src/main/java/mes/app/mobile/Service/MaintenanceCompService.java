package mes.app.mobile.Service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class MaintenanceCompService {

    @Autowired
    SqlRunner sqlRunner;

    @Autowired
    NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    // ── 사용자 정보 조회 ───────────────────────────────────────
    public Map<String, Object> getUserInfo(String username) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("username", username);

        String sql = """
                SELECT TOP 1
                    a.username,
                    a.first_name,
                    p.id,
                    an.restnum,
                    t.sttime
                FROM auth_user a
                LEFT JOIN tb_pb209 an ON TRY_CAST(an.perid AS INT) IS NOT NULL AND TRY_CAST(an.perid AS INT) = a.personid
                LEFT JOIN person p ON p.id = a.personid
                LEFT JOIN tb_pbcont t ON t.flag = RIGHT('0' + CAST(p.PersonGroup_id AS VARCHAR), 2)
                WHERE a.username = :username
                ORDER BY an.todate DESC
                """;

        return this.sqlRunner.getRow(sql, param);
    }

    // ── 현장 목록 조회 (TB_E601) ──────────────────────────────
    public List<Map<String, Object>> getSiteList(String spjangcd, String keyword, String equpcd, String tel, String actgubun) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT
                    e.actcd,
                    e.actnm,
                    e.cltcd,
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
            sql += " AND EXISTS (SELECT 1 FROM TB_E611 q WHERE q.spjangcd = e.spjangcd AND q.actcd = e.actcd AND q.equpcd LIKE :equpcd)";
            param.addValue("equpcd", "%" + equpcd.trim() + "%");
        }

        if (tel != null && !tel.trim().isEmpty()) {
            sql += " AND e.tel LIKE :tel";
            param.addValue("tel", "%" + tel.trim() + "%");
        }

        if (actgubun != null && !actgubun.trim().isEmpty()) {
            sql += " AND e.actgubun = :actgubun";
            param.addValue("actgubun", actgubun.trim());
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
                SELECT equpcd, equpnm, actcd
                FROM TB_E611 WITH(NOLOCK)
                WHERE spjangcd = :spjangcd
                  AND actcd    = :actcd
                ORDER BY equpcd ASC
                """;

        return this.sqlRunner.getRows(sql, param);
    }

    // ── 고장처리결과 목록 조회 (TB_E411) ─────────────────────
    public List<Map<String, Object>> getCompList(
            String fromDate, String toDate, String actnm, String spjangcd) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("fromDate", fromDate);
        param.addValue("toDate",   toDate);

        String sql = """
                SELECT
                    e.compdate,
                    e.compnum,
                    e.comptime,
                    e.recedate,
                    e.recenum,
                    e.recetime,
                    e.arrivdate,
                    e.arrivtime,
                    e.actcd,
                    e.actnm,
                    e.equpcd,
                    e.equpnm,
                    e.contremark,
                    e.remoremark,
                    e.resuremark,
                    e.resultcd,
                    e.customer,
                    e.remark,
                    e.perid,
                    j.pernm,
                    jc.divinm
                FROM TB_E411 e
                LEFT JOIN TB_JA001 j  ON j.perid    = 'p' + e.perid
                                     AND j.spjangcd  = e.spjangcd
                LEFT JOIN TB_JC002 jc ON j.divicd   = jc.divicd
                WHERE e.spjangcd = :spjangcd
                  AND e.compdate BETWEEN :fromDate AND :toDate
                """;

        if (actnm != null && !actnm.isBlank()) {
            sql += " AND e.actnm LIKE :actnm";
            param.addValue("actnm", "%" + actnm.trim() + "%");
        }

        sql += " ORDER BY e.compdate DESC, e.compnum DESC";
        return this.sqlRunner.getRows(sql, param);
    }

    // ── 고장처리결과 등록 (TB_E411 INSERT) ───────────────────
    public void saveComp(
            String spjangcd,
            String compdate,
            String comptime,
            String recedate,
            String recetime,
            String arrivdate,
            String arrivtime,
            String actcd,
            String actnm,
            String equpcd,
            String equpnm,
            String contremark,
            String remoremark,
            String resuremark,
            String resultcd,
            String customer,
            String remark,
            String perid) {

        // compnum 채번
        String compnum = getNextCompnum(spjangcd, compdate);

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd",   spjangcd);
        param.addValue("compdate",   compdate);
        param.addValue("compnum",    compnum);
        param.addValue("comptime",   comptime);
        param.addValue("recedate",   recedate);
        param.addValue("recetime",   recetime);
        param.addValue("arrivdate",  arrivdate);
        param.addValue("arrivtime",  arrivtime);
        param.addValue("actcd",      actcd);
        param.addValue("actnm",      actnm);
        param.addValue("equpcd",     equpcd);
        param.addValue("equpnm",     equpnm);
        param.addValue("contremark", contremark);
        param.addValue("remoremark", remoremark);
        param.addValue("resuremark", resuremark);
        param.addValue("resultcd",   resultcd);
        param.addValue("customer",   customer);
        param.addValue("remark",     remark);
        param.addValue("perid",      perid);
        param.addValue("inperid",    perid);
        param.addValue("indate",     compdate);

        String sql = """
                INSERT INTO TB_E411
                    (spjangcd, compdate, compnum, comptime,
                     recedate, recetime, arrivdate, arrivtime,
                     actcd, actnm, equpcd, equpnm,
                     contremark, remoremark, resuremark, resultcd,
                     customer, remark,
                     perid, inperid, indate)
                VALUES
                    (:spjangcd, :compdate, :compnum, :comptime,
                     :recedate, :recetime, :arrivdate, :arrivtime,
                     :actcd, :actnm, :equpcd, :equpnm,
                     :contremark, :remoremark, :resuremark, :resultcd,
                     :customer, :remark,
                     :perid, :inperid, :indate)
                """;

        namedParameterJdbcTemplate.update(sql, param);
    }

    // ── compnum 채번 (001 ~ 999) ──────────────────────────────
    private String getNextCompnum(String spjangcd, String compdate) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("compdate", compdate);

        String sql = """
                SELECT ISNULL(MAX(CAST(compnum AS INT)), 0) + 1
                FROM TB_E411
                WHERE spjangcd = :spjangcd
                  AND compdate = :compdate
                """;

        Integer next = namedParameterJdbcTemplate.queryForObject(sql, param, Integer.class);
        if (next == null) next = 1;
        return String.format("%03d", next);
    }
}
