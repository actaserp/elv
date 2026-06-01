package mes.app.mobile.Service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class MaintenanceRepairService {

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
                LEFT JOIN tb_pb209 an ON an.perid = a.personid
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

    // ── 고장처리결과 목록 조회 (TB_E411) ─────────────────────
    public List<Map<String, Object>> getCompList(
            String fromDate, String toDate, String actnm, String resultck, String spjangcd) {

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
                    jc.divinm,
                    a.resultck
                FROM TB_E411 e
                LEFT JOIN TB_JA001 j  ON j.perid    = 'p' + e.perid
                                     AND j.spjangcd  = e.spjangcd
                LEFT JOIN TB_JC002 jc ON j.divicd   = jc.divicd
                LEFT JOIN TB_E401  a  ON a.recedate  = e.recedate
                                     AND a.recenum   = e.recenum
                                     AND a.spjangcd  = e.spjangcd
                WHERE e.spjangcd = :spjangcd
                  AND e.compdate BETWEEN :fromDate AND :toDate
                """;

        if (actnm != null && !actnm.isBlank()) {
            sql += " AND e.actnm LIKE :actnm";
            param.addValue("actnm", "%" + actnm.trim() + "%");
        }

        if (resultck != null && !resultck.isBlank()) {
            if (resultck.equals("1")) {
                sql += " AND a.resultck = '1'";
            } else if (resultck.equals("null")) {
                sql += " AND (a.resultck IS NULL OR a.resultck <> '1')";
            }
        }

        sql += " ORDER BY e.compdate DESC, e.compnum DESC";
        return this.sqlRunner.getRows(sql, param);
    }

    // ── 현장 목록 조회 (TB_E601) ─────────────────────────────
    public List<Map<String, Object>> getSiteList(String spjangcd, String keyword) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT actcd, actnm
                FROM TB_E601
                WHERE spjangcd = :spjangcd
                """;

        if (keyword != null && !keyword.trim().isEmpty()) {
            sql += " AND actnm LIKE :keyword";
            param.addValue("keyword", "%" + keyword.trim() + "%");
        }

        sql += " ORDER BY actnm ASC";
        return this.sqlRunner.getRows(sql, param);
    }

    // ── 호기 목록 조회 (TB_E611) ─────────────────────────────
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

    // ── 고장처리결과 등록 (TB_E411 INSERT) ───────────────────
    public void saveComp(
            String custcd,
            String spjangcd,
            String compdate,
            String comptime,
            String recedate,
            String recenum,
            String recetime,
            String arrivdate,
            String arrivtime,
            String actcd,
            String actnm,
            String equpcd,
            String equpnm,
            String contremark,   // 고장부위
            String remoremark,   // 고장부위상세
            String resuremark,   // 고장요인
            // String resultcd   // 고장원인 — 매핑 확인 후 추가
            // String customer   // 처리내용 — 매핑 확인 후 추가
            // String remark     // 처리결과 — 매핑 확인 후 추가
            String perid) {

        String compnum = getNextCompnum(spjangcd, compdate);

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd",     custcd);
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
        param.addValue("contremark", contremark);  // 고장부위
        param.addValue("remoremark", remoremark);  // 고장부위상세
        param.addValue("resuremark", resuremark);  // 고장요인
        param.addValue("result",     "1");          // 처리완료 고정
        param.addValue("perid",      perid);
        param.addValue("inperid",    perid);
        param.addValue("indate",     compdate);
        // param.addValue("resultcd",   resultcd);  // 고장원인 — 매핑 확인 후 추가
        // param.addValue("customer",   customer);  // 처리내용 — 매핑 확인 후 추가
        // param.addValue("remark",     remark);    // 처리결과 — 매핑 확인 후 추가

        String sql = """
                INSERT INTO TB_E411
                    (custcd, spjangcd, compdate, compnum, comptime,
                     recedate, recenum, recetime,
                     arrivdate, arrivtime,
                     actcd, actnm, equpcd, equpnm,
                     contremark, remoremark, resuremark,
                     result,
                     perid, inperid, indate)
                VALUES
                    (:custcd, :spjangcd, :compdate, :compnum, :comptime,
                     :recedate, :recenum, :recetime,
                     :arrivdate, :arrivtime,
                     :actcd, :actnm, :equpcd, :equpnm,
                     :contremark, :remoremark, :resuremark,
                     :result,
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
