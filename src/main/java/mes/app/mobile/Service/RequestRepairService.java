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
            String fromDate, String toDate, String actnm, String resultck, String spjangcd, String perid) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("fromDate", fromDate);
        param.addValue("toDate",   toDate);
        param.addValue("perid",    perid);

        String sql = """
                SELECT
                    e.recedate,
                    e.recenum,
                    e.recetime,
                    e.hitchdate,
                    e.hitchhour,
                    e.actcd,
                    e.actnm,
                    e.equpcd,
                    e.equpnm,
                    e.contcd,
                    ct.contnm,
                    e.contents,
                    e.remark,
                    e.reperid,
                    rp.pernm    AS repernm,
                    e.divicd,
                    jc.divinm,
                    j.pernm     AS pernm,
                    e.perid,
                    e.resultck
                FROM TB_E401 e
                LEFT JOIN TB_JA001 j  ON j.perid    = 'p' + e.perid
                                     AND j.spjangcd  = e.spjangcd
                LEFT JOIN TB_JC002 jc ON j.divicd   = jc.divicd
                LEFT JOIN TB_JA001 rp ON rp.perid    = 'p' + e.reperid
                                     AND rp.spjangcd = e.spjangcd
                LEFT JOIN TB_E010  ct ON ct.contcd   = e.contcd
                                     AND ct.spjangcd = e.spjangcd
                WHERE e.spjangcd = :spjangcd
                  AND e.recedate BETWEEN :fromDate AND :toDate
                  AND e.perid    = :perid
                """;

        if (actnm != null && !actnm.isBlank()) {
            sql += " AND e.actnm LIKE :actnm";
            param.addValue("actnm", "%" + actnm.trim() + "%");
        }

        if (resultck != null && !resultck.isBlank()) {
            if (resultck.equals("1")) {
                sql += " AND e.resultck = '1'";
            } else if (resultck.equals("null")) {
                sql += " AND (e.resultck IS NULL OR e.resultck <> '1')";
            }
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

    // ── 고장내용 목록 조회 (TB_E010) ────────────────────────
    public List<Map<String, Object>> getContnmList(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT contcd, contnm
                FROM TB_E010
                WHERE spjangcd = :spjangcd
                ORDER BY contcd ASC
                """;

        return this.sqlRunner.getRows(sql, param);
    }

    // ── 사원 목록 조회 (TB_JA001) ────────────────────────────
    public List<Map<String, Object>> getPerList(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT perid, pernm
                FROM TB_JA001
                WHERE spjangcd = :spjangcd
                ORDER BY pernm ASC
                """;

        return this.sqlRunner.getRows(sql, param);
    }
    public void saveRepair(
            String custcd,
            String spjangcd,
            String recedate,
            String recetime,
            String hitchdate,
            String hitchhour,
            String actcd,
            String actnm,
            String equpcd,
            String equpnm,
            String contcd,
            String contents,
            String remark,
            String perid,      // ★ 화면에서 선택한 접수자(전화 받은 사람) → TB_E401.perid
            String bigo,
            String reperid) {  // ★ 통보자(현장으로 가는 사람)           → TB_E401.reperid

        String recenum = getNextRecenum(spjangcd, recedate);

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd",    custcd);
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
        param.addValue("contcd",    contcd);
        param.addValue("contents",  contents);
        param.addValue("remark",    remark);
        // ★ PB 규격: perid = 접수자(전화 받은 사람), reperid = 통보자(현장 가는 사람)
        //   PB 실데이터 검증: inperid(등록자)와 perid 일치 5,278건 vs reperid 일치 2,110건
        //   ※ 고장처리의 담당자(TB_E411.actperid)는 접수건이 아니라
        //     현장 마스터(TB_E601.perid = 점검자(정))에서 유래 — 8,140 vs 6,119
        param.addValue("perid",     perid);
        param.addValue("reperid",   reperid);
        param.addValue("inperid",   reperid);
        param.addValue("indate",    recedate);

        // datetime  : 접수일자+시간 (yyyyMMdd + HHmm → datetime)
        // datetime2 : 고장일자+시간 (yyyyMMdd + HHmm → datetime)
        java.time.LocalDateTime receDt = toLocalDateTime(recedate, recetime);
        java.time.LocalDateTime hitchDt = toLocalDateTime(hitchdate, hitchhour);
        param.addValue("datetime",  receDt);
        param.addValue("datetime2", hitchDt);

        // ── PB 규격 부가 컬럼 ────────────────────────────────
        param.addValue("divicd",   getPeridDivicd(spjangcd, perid));   // 접수자 부서 (PB 규격)
        param.addValue("cltcd",    getActCltcd(spjangcd, actcd));      // 현장 거래처
        param.addValue("resultck", "0");                                // PB는 접수 시 '0'

        String sql = """
                INSERT INTO TB_E401
                    (custcd, spjangcd, recedate, recenum, recetime,
                     hitchdate, hitchhour,
                     actcd, actnm, equpcd, equpnm,
                     contcd, contents, remark, reperid,
                     perid, inperid, indate,
                     [datetime], [datetime2],
                     divicd, cltcd, resultck)
                VALUES
                    (:custcd, :spjangcd, :recedate, :recenum, :recetime,
                     :hitchdate, :hitchhour,
                     :actcd, :actnm, :equpcd, :equpnm,
                     :contcd, :contents, :remark, :reperid,
                     :perid, :inperid, :indate,
                     :datetime, :datetime2,
                     :divicd, :cltcd, :resultck)
                """;

        namedParameterJdbcTemplate.update(sql, param);
    }

    // ── 고장접수 수정 (TB_E401 UPDATE) ───────────────────────
    public void updateRepair(
            String spjangcd, String recedate, String recenum,
            String recetime, String hitchdate, String hitchhour,
            String actcd, String actnm, String equpcd, String equpnm,
            String contcd, String contents, String remark, String perid) {

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
        param.addValue("contcd",    contcd);
        param.addValue("contents",  contents);
        param.addValue("remark",    remark);
        // ★ 담당기사(perid)만 갱신. 통보자(reperid)는 등록 시점 값 보존
        param.addValue("perid",     perid);

        java.time.LocalDateTime receDt  = toLocalDateTime(recedate, recetime);
        java.time.LocalDateTime hitchDt = toLocalDateTime(hitchdate, hitchhour);
        param.addValue("datetime",  receDt);
        param.addValue("datetime2", hitchDt);

        namedParameterJdbcTemplate.update("""
                UPDATE TB_E401 SET
                    recetime  = :recetime,
                    hitchdate = :hitchdate,
                    hitchhour = :hitchhour,
                    actcd     = :actcd,
                    actnm     = :actnm,
                    equpcd    = :equpcd,
                    equpnm    = :equpnm,
                    contcd    = :contcd,
                    contents  = :contents,
                    remark    = :remark,
                    perid     = :perid,
                    [datetime]  = :datetime,
                    [datetime2] = :datetime2
                WHERE spjangcd = :spjangcd
                  AND recedate = :recedate
                  AND recenum  = :recenum
                """, param);
    }

    // ── 고장접수 삭제 (TB_E401 DELETE) ───────────────────────
    public void deleteRepair(String spjangcd, String recedate, String recenum) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("recedate", recedate);
        param.addValue("recenum",  recenum);

        namedParameterJdbcTemplate.update("""
                DELETE FROM TB_E401
                WHERE spjangcd = :spjangcd
                  AND recedate = :recedate
                  AND recenum  = :recenum
                """, param);
    }

    // ── 담당기사 부서코드 조회 (TB_JA001.divicd) ───────────────
    private String getPeridDivicd(String spjangcd, String peridRaw) {
        if (peridRaw == null || peridRaw.isBlank()) return null;
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("perid",    "p" + peridRaw.trim().replaceFirst("^p", ""));
        List<Map<String, Object>> rows = this.sqlRunner.getRows("""
                SELECT TOP 1 divicd FROM TB_JA001
                WHERE spjangcd = :spjangcd AND perid = :perid
                """, param);
        return rows.isEmpty() ? null : (String) rows.get(0).get("divicd");
    }

    // ── 현장 거래처코드 조회 (TB_E601.cltcd) ───────────────────
    private String getActCltcd(String spjangcd, String actcd) {
        if (actcd == null || actcd.isBlank()) return null;
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("actcd",    actcd);
        List<Map<String, Object>> rows = this.sqlRunner.getRows("""
                SELECT TOP 1 cltcd FROM TB_E601
                WHERE spjangcd = :spjangcd AND actcd = :actcd
                """, param);
        return rows.isEmpty() ? null : (String) rows.get(0).get("cltcd");
    }

    // ── yyyyMMdd + HHmm → LocalDateTime ─────────────────────
    private java.time.LocalDateTime toLocalDateTime(String date, String time) {
        try {
            if (date == null || date.isBlank()) return null;
            String t = (time != null && time.length() >= 4) ? time.substring(0, 4) : "0000";
            int year  = Integer.parseInt(date.substring(0, 4));
            int month = Integer.parseInt(date.substring(4, 6));
            int day   = Integer.parseInt(date.substring(6, 8));
            int hour  = Integer.parseInt(t.substring(0, 2));
            int min   = Integer.parseInt(t.substring(2, 4));
            return java.time.LocalDateTime.of(year, month, day, hour, min);
        } catch (Exception e) {
            return null;
        }
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
