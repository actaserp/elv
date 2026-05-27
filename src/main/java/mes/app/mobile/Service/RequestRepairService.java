package mes.app.mobile.Service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class RequestRepairService {

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

    // ── 고장접수 목록 조회 (TB_E401) ─────────────────────────
    public List<Map<String, Object>> getRepairList(
            String fromDate, String toDate, String actnm, String spjangcd) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("fromDate", fromDate);
        param.addValue("toDate",   toDate);

        String sql = """
                SELECT
                    e.recedate,
                    e.recenum,
                    e.recetime,
                    e.hitchdate,
                    e.hitchhour,
                    e.actnm,
                    e.equpcd,
                    e.equpnm,
                    e.contents,
                    e.remark,
                    e.reperid,
                    e.divicd,
                    jc.divinm,
                    j.pernm,
                    e.perid
                FROM TB_E401 e
                LEFT JOIN TB_JA001 j  ON j.perid    = 'p' + e.perid
                                     AND j.spjangcd  = e.spjangcd
                LEFT JOIN TB_JC002 jc ON j.divicd   = jc.divicd
                WHERE e.spjangcd = :spjangcd
                  AND e.recedate BETWEEN :fromDate AND :toDate
                """;

        if (actnm != null && !actnm.isBlank()) {
            sql += " AND e.actnm LIKE :actnm";
            param.addValue("actnm", "%" + actnm.trim() + "%");
        }

        sql += " ORDER BY e.recedate DESC, e.recenum DESC";

        return this.sqlRunner.getRows(sql, param);
    }

    // ── 현장 목록 조회 (TB_E601) ─────────────────────────────
    public List<Map<String, Object>> getActList(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT actcd, actnm
                FROM TB_E601
                WHERE spjangcd = :spjangcd
                ORDER BY actnm ASC
                """;

        return this.sqlRunner.getRows(sql, param);
    }

    // ── 호기 목록 조회 (TB_E611) ─────────────────────────────
    public List<Map<String, Object>> getEqupList(String actcd, String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("actcd",    actcd);
        param.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT equpcd, equpnm
                FROM TB_E611 WITH(NOLOCK)
                WHERE spjangcd = :spjangcd
                  AND actcd    = :actcd
                ORDER BY equpcd ASC
                """;

        return this.sqlRunner.getRows(sql, param);
    }

    // ── 고장접수 등록 (TB_E401 INSERT) ───────────────────────
    public void saveRepair(
            String spjangcd,
            String recedate,
            String recetime,
            String hitchdate,
            String hitchhour,
            String actcd,
            String actnm,
            String equpcd,
            String equpnm,
            String contents,
            String remark,
            String reperid,
            String bigo,
            String perid) {

        // recenum 채번
        String recenum = getNextRecenum(spjangcd, recedate);

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd",  spjangcd);
        param.addValue("recedate",  recedate);
        param.addValue("recenum",   recenum);
        param.addValue("recetime",  recetime);
        param.addValue("hitchdate", hitchdate);
        param.addValue("hitchhour", hitchhour);
        param.addValue("actcd",     actcd);
        param.addValue("actnm",     actnm);
        param.addValue("equpcd",    equpcd);
        param.addValue("equpnm",    equpnm);
        param.addValue("contents",  contents);
        param.addValue("remark",    remark);
        param.addValue("reperid",   reperid);
        param.addValue("perid",     perid);
        param.addValue("inperid",   perid);
        param.addValue("indate",    recedate);

        String sql = """
                INSERT INTO TB_E401
                    (spjangcd, recedate, recenum, recetime,
                     hitchdate, hitchhour,
                     actcd, actnm, equpcd, equpnm,
                     contents, remark, reperid,
                     perid, inperid, indate)
                VALUES
                    (:spjangcd, :recedate, :recenum, :recetime,
                     :hitchdate, :hitchhour,
                     :actcd, :actnm, :equpcd, :equpnm,
                     :contents, :remark, :reperid,
                     :perid, :inperid, :indate)
                """;

        namedParameterJdbcTemplate.update(sql, param);
    }

    // ── recenum 채번 (001 ~ 999) ──────────────────────────────
    private String getNextRecenum(String spjangcd, String recedate) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("recedate", recedate);

        String sql = """
                SELECT ISNULL(MAX(CAST(recenum AS INT)), 0) + 1
                FROM TB_E401
                WHERE spjangcd = :spjangcd
                  AND recedate = :recedate
                """;

        Integer next = namedParameterJdbcTemplate.queryForObject(sql, param, Integer.class);
        if (next == null) next = 1;
        return String.format("%03d", next);
    }

    // ── 결재구분별 결재라인 조회 (기존 유지) ──────────────────
    public List<Map<String, Object>> getAppInfoList(int personid) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("personid", personid);

        String sql = """
                SELECT *
                FROM tb_e064 e
                WHERE e.papercd = '301'
                  AND e.perid = :personid
                ORDER BY e.SEQ ASC
                """;

        return this.sqlRunner.getRows(sql, param);
    }

    // ── 휴가항목 근태설정 고정값 조회 (기존 유지) ─────────────
    public Map<String, Object> getPeriod(String attKind) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("attKind", attKind);

        String sql = """
                SELECT yearflag, usenum
                FROM tb_pb210
                WHERE workcd = :attKind
                """;

        return this.sqlRunner.getRow(sql, param);
    }
}
