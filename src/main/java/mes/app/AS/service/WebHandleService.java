package mes.app.AS.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class WebHandleService {

    @Autowired
    SqlRunner sqlRunner;

    @Autowired
    NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    // ── 고장접수 목록 조회 (TB_E401) ─────────────────────────
    public List<Map<String, Object>> getRequestList(
            String spjangcd, String fromDate, String toDate, String actnm) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("fromDate", fromDate);
        param.addValue("toDate",   toDate);

        String sql = """
                SELECT
                    e.recedate,
                    e.recenum,
                    e.recetime,
                    e.actcd,
                    e.actnm,
                    e.equpcd,
                    e.equpnm,
                    e.contcd,
                    e.contents,
                    e.remark,
                    e.resultck
                FROM TB_E401 e
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

    // ── 고장처리결과 등록 (TB_E411 INSERT) ───────────────────
    public void saveComp(
            String spjangcd, String compdate, String comptime,
            String recedate, String recenum, String recetime,
            String arrivdate, String arrivtime,
            String actcd, String actnm, String equpcd, String equpnm,
            String contremark, String remoremark, String resuremark,
            String resultcd, String customer, String remark, String perid) {

        String compnum = getNextCompnum(spjangcd, compdate);

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd",   spjangcd);
        param.addValue("compdate",   compdate);
        param.addValue("compnum",    compnum);
        param.addValue("comptime",   comptime);
        param.addValue("recedate",   recedate);
        param.addValue("recenum",    recenum);
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
        param.addValue("perid",      perid != null ? perid : "");
        param.addValue("inperid",    perid != null ? perid : "");
        param.addValue("indate",     compdate);

        String sql = """
                INSERT INTO TB_E411
                    (spjangcd, compdate, compnum, comptime,
                     recedate, recenum, recetime, arrivdate, arrivtime,
                     actcd, actnm, equpcd, equpnm,
                     contremark, remoremark, resuremark, resultcd,
                     customer, remark,
                     perid, inperid, indate)
                VALUES
                    (:spjangcd, :compdate, :compnum, :comptime,
                     :recedate, :recenum, :recetime, :arrivdate, :arrivtime,
                     :actcd, :actnm, :equpcd, :equpnm,
                     :contremark, :remoremark, :resuremark, :resultcd,
                     :customer, :remark,
                     :perid, :inperid, :indate)
                """;

        namedParameterJdbcTemplate.update(sql, param);

        // ── TB_E401 처리완료 상태 업데이트 ──────────────────
        if (recedate != null && !recedate.isBlank() && recenum != null && !recenum.isBlank()) {
            MapSqlParameterSource updateParam = new MapSqlParameterSource();
            updateParam.addValue("spjangcd", spjangcd);
            updateParam.addValue("recedate",  recedate);
            updateParam.addValue("recenum",   recenum);

            namedParameterJdbcTemplate.update("""
                    UPDATE TB_E401
                    SET resultck = '1'
                    WHERE spjangcd = :spjangcd
                      AND recedate = :recedate
                      AND recenum  = :recenum
                    """, updateParam);
        }
    }

    // ── 고장처리결과 삭제 (TB_E411 DELETE) ───────────────────
    public void deleteComp(String spjangcd, String compdate, String compnum) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("compdate", compdate);
        param.addValue("compnum",  compnum);

        String sql = """
                DELETE FROM TB_E411
                WHERE spjangcd = :spjangcd
                  AND compdate = :compdate
                  AND compnum  = :compnum
                """;

        namedParameterJdbcTemplate.update(sql, param);
    }

    // ── 팝업: 현장 검색 (TB_E601) ────────────────────────────
    public List<Map<String, Object>> popupActnm(String spjangcd, String actnm) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT actcd, actnm
                FROM TB_E601
                WHERE spjangcd = :spjangcd
                """;

        if (actnm != null && !actnm.isBlank()) {
            sql += " AND actnm LIKE :actnm";
            param.addValue("actnm", "%" + actnm.trim() + "%");
        }

        sql += " ORDER BY actnm ASC";
        return this.sqlRunner.getRows(sql, param);
    }

    // ── 팝업: 호기 검색 (TB_E611) ────────────────────────────
    public List<Map<String, Object>> popupEqupnm(String spjangcd, String actcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("actcd",    actcd);

        String sql = """
                SELECT equpcd, equpnm, actcd
                FROM TB_E611
                WHERE spjangcd = :spjangcd
                  AND actcd    = :actcd
                ORDER BY equpcd ASC
                """;

        return this.sqlRunner.getRows(sql, param);
    }

    // ── 팝업: 사원 검색 (TB_JA001, 처리자) ───────────────────
    public List<Map<String, Object>> popupPernm(String spjangcd, String pernm) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT j.perid, j.pernm, jc.divinm
                FROM TB_JA001 j
                LEFT JOIN TB_JC002 jc ON j.divicd   = jc.divicd
                                     AND j.spjangcd  = jc.spjangcd
                WHERE j.spjangcd = :spjangcd
                  AND j.retiredate IS NULL
                """;

        if (pernm != null && !pernm.isBlank()) {
            sql += " AND j.pernm LIKE :pernm";
            param.addValue("pernm", "%" + pernm.trim() + "%");
        }

        sql += " ORDER BY j.pernm ASC";
        return this.sqlRunner.getRows(sql, param);
    }

    // ── compnum 채번 ──────────────────────────────────────────
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
