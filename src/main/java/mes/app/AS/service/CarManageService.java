package mes.app.AS.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class CarManageService {

    @Autowired
    SqlRunner sqlRunner;

    /**
     * 차량운행기록 조회 (TB_E037_CONF)
     * - TB_JA001  : perid  → pernm  (사원명)
     * - TB_E047   : carcd  → carnum (차량번호)
     * - TB_E037_1 : gubun  → fuelnm (유종명) - gareacd 무관, 단가 최고값 서브쿼리로 중복 제거
     */
    public List<Map<String, Object>> getList(
            String startDate,
            String endDate,
            String pernm,
            String carnum,
            String spjangcd,
            String username) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("startDate", startDate);
        param.addValue("endDate",   endDate);
        param.addValue("spjangcd",  spjangcd);

        String sql = """
                SELECT
                    c.kcdate,
                    c.kcnum,
                    c.perid,
                    j.pernm,
                    c.carcd,
                    e.carnum,
                    c.gubun,
                    f.fuelnm,
                    c.actcd,
                    s.actnm,
                    c.km,
                    c.liter,
                    c.uamt,
                    c.samt
                FROM TB_E037_CONF c
                LEFT JOIN TB_JA001  j ON j.perid   = 'p'+c.perid
                LEFT JOIN TB_E047   e ON e.carcd    = c.carcd
                LEFT JOIN TB_E601   s ON s.actcd    = c.actcd
                                    AND s.spjangcd  = c.spjangcd
                LEFT JOIN (
                    SELECT f1.fuelcd, f1.fuelnm
                    FROM TB_E037_1 f1
                    WHERE f1.spjangcd = :spjangcd
                      AND f1.useyn    = '1'
                      AND f1.uamt = (
                          SELECT MAX(f2.uamt)
                          FROM TB_E037_1 f2
                          WHERE f2.fuelcd   = f1.fuelcd
                            AND f2.spjangcd = f1.spjangcd
                            AND f2.useyn    = '1'
                      )
                ) f ON f.fuelcd = c.gubun
                WHERE c.spjangcd = :spjangcd
                  AND c.kcdate  BETWEEN :startDate AND :endDate
                """;

        // 사원명 검색
        if (pernm != null && !pernm.isBlank()) {
            sql += " AND j.pernm LIKE :pernm";
            param.addValue("pernm", "%" + pernm.trim() + "%");
        }

        // 차량번호 검색
        if (carnum != null && !carnum.isBlank()) {
            sql += " AND e.carnum LIKE :carnum";
            param.addValue("carnum", "%" + carnum.trim() + "%");
        }

        // 사용자(User) 그룹: 본인 운행기록만 (TB_E037_CONF.perid = username)
        if (username != null && !username.isBlank()) {
            sql += " AND c.perid = :username";
            param.addValue("username", username);
        }

        sql += " ORDER BY c.kcdate DESC, j.pernm ASC";

        return sqlRunner.getRows(sql, param);
    }

    // ════════════════════════════════════════════════════════
    //  차량운행 등록 (웹) — 모바일 vehicle_manage 로직 이식
    // ════════════════════════════════════════════════════════

    // ── 차량 목록 (TB_E047) — 단가 최고값 서브쿼리로 유종명 매핑 ──
    public List<Map<String, Object>> getVehicleList(String spjangcd, String keyword) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        String sql = """
                SELECT e.carcd, e.carnum, e.gubun AS fuelcd, e.samt, f.fuelnm
                FROM TB_E047 e
                LEFT JOIN (
                    SELECT f1.fuelcd, f1.fuelnm, f1.uamt
                    FROM TB_E037_1 f1
                    WHERE f1.spjangcd = :spjangcd
                      AND f1.useyn    = '1'
                      AND f1.uamt = (
                          SELECT MAX(f2.uamt)
                          FROM TB_E037_1 f2
                          WHERE f2.fuelcd   = f1.fuelcd
                            AND f2.spjangcd = f1.spjangcd
                            AND f2.useyn    = '1'
                      )
                ) f ON f.fuelcd = e.gubun
                WHERE e.spjangcd = :spjangcd
                """;
        if (keyword != null && !keyword.isBlank()) {
            sql += " AND e.carnum LIKE :keyword";
            param.addValue("keyword", "%" + keyword.trim() + "%");
        }
        sql += " ORDER BY e.carnum";
        return sqlRunner.getRows(sql, param);
    }

    // ── 유류 단가 정보 (TB_E037_1) — 단가 최고값 1건 ──────────
    public Map<String, Object> getFuelInfo(String spjangcd, String fuelcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("fuelcd", fuelcd);
        String sql = """
                SELECT TOP 1 fuelcd, fuelnm, uamt, kmliter, unit
                FROM TB_E037_1
                WHERE spjangcd = :spjangcd
                  AND fuelcd   = :fuelcd
                  AND useyn    = '1'
                ORDER BY uamt DESC
                """;
        return sqlRunner.getRow(sql, param);
    }

    // ── 현장 목록 (TB_E601) ──────────────────────────────────
    public List<Map<String, Object>> getSiteList(String spjangcd, String keyword) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        String sql = """
                SELECT actcd, actnm
                FROM TB_E601
                WHERE spjangcd = :spjangcd
                """;
        if (keyword != null && !keyword.isBlank()) {
            sql += " AND actnm LIKE :keyword";
            param.addValue("keyword", "%" + keyword.trim() + "%");
        }
        sql += " ORDER BY actnm";
        return sqlRunner.getRows(sql, param);
    }

    // ── 운행기록 등록 (TB_E037_CONF INSERT) ──────────────────
    public void saveRun(String spjangcd, String username, String startDate,
                        String vehicleCd, String fuelKind, String actcd,
                        double totalKM, double liter, double uamt, double total) {

        String custcd = getCustcdBySpjangcd(spjangcd);
        if (custcd == null || custcd.isBlank()) {
            throw new RuntimeException("거래처코드(custcd)를 찾을 수 없습니다.");
        }

        String kcdate    = startDate.replace("-", "");
        String confmon   = kcdate.substring(0, 6);
        double usedLiter = (liter > 0) ? (totalKM / liter) : 0;
        String divicd    = getDivicd(username);
        String kcnum     = getNextKcnum(custcd, spjangcd, kcdate);

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd",   custcd);
        param.addValue("spjangcd", spjangcd);
        param.addValue("kcdate",   kcdate);
        param.addValue("kcnum",    kcnum);
        param.addValue("confmon",  confmon);
        param.addValue("perid",    username);
        param.addValue("kcseq",    "001");
        param.addValue("carcd",    vehicleCd);
        param.addValue("gubun",    fuelKind);
        param.addValue("km",       totalKM);
        param.addValue("liter",    usedLiter);
        param.addValue("uamt",     uamt);
        param.addValue("samt",     total);
        param.addValue("actcd",    actcd);
        param.addValue("divicd",   divicd);
        param.addValue("unit",     "KM");
        param.addValue("confyn",   "0");
        param.addValue("indate",   kcdate);

        String sql = """
                INSERT INTO TB_E037_CONF (
                    custcd, spjangcd, kcdate, kcnum, confmon, perid, kcseq,
                    carcd, gubun,
                    km, liter, uamt, samt,
                    actcd, divicd, unit, confyn, indate
                ) VALUES (
                    :custcd, :spjangcd, :kcdate, :kcnum, :confmon, :perid, :kcseq,
                    :carcd, :gubun,
                    :km, :liter, :uamt, :samt,
                    :actcd, :divicd, :unit, :confyn, :indate
                )
                """;
        sqlRunner.execute(sql, param);
    }

    private String getNextKcnum(String custcd, String spjangcd, String kcdate) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd",   custcd);
        param.addValue("spjangcd", spjangcd);
        param.addValue("kcdate",   kcdate);
        String sql = """
                SELECT ISNULL(MAX(CAST(kcnum AS INT)), 0) + 1 AS nextnum
                FROM TB_E037_CONF
                WHERE custcd   = :custcd
                  AND spjangcd = :spjangcd
                  AND kcdate   = :kcdate
                """;
        Map<String, Object> row = sqlRunner.getRow(sql, param);
        int next = (row != null && row.get("nextnum") != null)
                ? ((Number) row.get("nextnum")).intValue() : 1;
        return String.format("%04d", next);
    }

    private String getDivicd(String username) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("perid", "p" + username);
        Map<String, Object> row = sqlRunner.getRow(
                "SELECT divicd FROM TB_JA001 WHERE perid = :perid", param);
        if (row == null || row.isEmpty()) return null;
        Object divicd = row.get("divicd");
        return divicd == null ? null : String.valueOf(divicd).trim();
    }

    private String getCustcdBySpjangcd(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        Map<String, Object> row = sqlRunner.getRow(
                "SELECT custcd FROM tb_xa012 WHERE spjangcd = :spjangcd", param);
        if (row == null || row.isEmpty()) return null;
        Object custcd = row.get("custcd");
        return custcd == null ? null : String.valueOf(custcd).trim();
    }

    // ── 운행기록 수정 (TB_E037_CONF UPDATE) ──────────────────
    // ownUsername != null 이면 본인(perid) 건만 수정 (User 그룹 제한)
    public int updateRun(String spjangcd, String kcdate, String kcnum, String ownUsername,
                         String newKcdate, String actcd, String gubun,
                         double km, double liter, double uamt, double total) {

        double usedLiter = (liter > 0) ? (km / liter) : 0;

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd",  spjangcd);
        param.addValue("kcdate",    kcdate);
        param.addValue("kcnum",     kcnum);
        param.addValue("newKcdate", (newKcdate != null && !newKcdate.isBlank()) ? newKcdate.replace("-", "") : kcdate);
        param.addValue("actcd",     actcd);
        param.addValue("gubun",     gubun);
        param.addValue("km",        km);
        param.addValue("liter",     usedLiter);
        param.addValue("uamt",      uamt);
        param.addValue("samt",      total);

        String sql = """
                UPDATE TB_E037_CONF SET
                    kcdate = :newKcdate,
                    actcd  = :actcd,
                    gubun  = :gubun,
                    km     = :km,
                    liter  = :liter,
                    uamt   = :uamt,
                    samt   = :samt
                WHERE spjangcd = :spjangcd
                  AND kcdate   = :kcdate
                  AND kcnum    = :kcnum
                """;
        if (ownUsername != null && !ownUsername.isBlank()) {
            sql += " AND perid = :ownUsername";
            param.addValue("ownUsername", ownUsername);
        }
        return sqlRunner.execute(sql, param);
    }

    // ── 운행기록 삭제 (TB_E037_CONF DELETE) ──────────────────
    // ownUsername != null 이면 본인(perid) 건만 삭제 (User 그룹 제한)
    public int deleteRun(String spjangcd, String kcdate, String kcnum, String ownUsername) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("kcdate",   kcdate);
        param.addValue("kcnum",    kcnum);

        String sql = """
                DELETE FROM TB_E037_CONF
                WHERE spjangcd = :spjangcd
                  AND kcdate   = :kcdate
                  AND kcnum    = :kcnum
                """;
        if (ownUsername != null && !ownUsername.isBlank()) {
            sql += " AND perid = :ownUsername";
            param.addValue("ownUsername", ownUsername);
        }
        return sqlRunner.execute(sql, param);
    }
}
