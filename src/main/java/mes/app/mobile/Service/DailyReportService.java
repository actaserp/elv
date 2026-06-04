package mes.app.mobile.Service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class DailyReportService {

    @Autowired
    SqlRunner sqlRunner;

    @Autowired
    NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    // ── 사용자 정보 조회 (personid 기준 - username 변경에 무관)
    public Map<String, Object> getUserInfo(int personId) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("personId", personId);

        String sql = """
                SELECT
                    j.custcd,
                    j.spjangcd,
                    j.perid,
                    j.pernm      AS first_name,
                    pz.RSPNM     AS position,
                    jc.divinm    AS department
                FROM person p
                JOIN TB_JA001 j ON j.perid = p.Code
                LEFT JOIN TB_JC002 jc ON j.divicd = jc.divicd
                LEFT JOIN TB_PZ001 pz ON j.rspcd  = pz.RSPCD
                WHERE p.id = :personId
                """;

        return this.sqlRunner.getRow(sql, param);
    }

    // ── 구분 목록 조회 (TB_E021) ───────────────────────────────
    public List<Map<String, Object>> getGubunList(String custcd, String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd", custcd);
        param.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT busicd,
                       businm
                FROM TB_E021
                WHERE custcd   = :custcd
                  AND spjangcd = :spjangcd
                ORDER BY busicd
                """;

        return this.sqlRunner.getRows(sql, param);
    }

    // ── 행선지 목록 조회 (TB_E601) ────────────────────────────
    public List<Map<String, Object>> getDestList(String custcd, String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd", custcd);
        param.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT actcd,
                       actnm
                FROM TB_E601
                WHERE custcd   = :custcd
                  AND spjangcd = :spjangcd
                ORDER BY actcd
                """;

        return this.sqlRunner.getRows(sql, param);
    }

    // ── 호기 목록 조회 (TB_E611) ─────────────────────────────
    public List<Map<String, Object>> getEqupList(String custcd, String spjangcd, String actcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd", custcd);
        param.addValue("spjangcd", spjangcd);
        param.addValue("actcd", actcd);

        String sql = """
                SELECT a.custcd,
                       a.spjangcd,
                       a.cltcd,
                       a.actcd,
                       a.equpcd,
                       a.equpnm
                FROM TB_E611 a WITH(NOLOCK)
                WHERE a.custcd   = :custcd
                  AND a.spjangcd = :spjangcd
                  AND a.actcd    = :actcd
                ORDER BY a.equpcd
                """;

        return this.sqlRunner.getRows(sql, param);
    }

    // ── 업무일지 등록 (TB_E037 HEAD MERGE + TB_E038 상세 INSERT) ──
    public void saveDailyReport(
            String custcd,
            String spjangcd,
            String rptdate,
            String perid,
            String wkcd,
            String actcd,
            String actnm,
            String frtime,
            String totime,
            String equpcd,
            String remark,
            String filesvnm,
            String filepath) {

        // 1단계: TB_E037 HEAD MERGE (없으면 INSERT, 있으면 유지)
        mergeHead(custcd, spjangcd, rptdate, perid);

        // 2단계: TB_E038 상세 rptnum 채번 후 INSERT
        String rptnum = getNextRptnum(custcd, spjangcd, rptdate, perid);

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd", custcd);
        param.addValue("spjangcd", spjangcd);
        param.addValue("rptdate", rptdate);
        param.addValue("perid", perid);
        param.addValue("rptnum", rptnum);
        param.addValue("actcd", actcd);
        param.addValue("actnm", actnm);
        param.addValue("wkcd", wkcd);
        param.addValue("frtime", frtime);
        param.addValue("totime", totime);
        param.addValue("equpcd", equpcd);
        param.addValue("remark", remark);
        param.addValue("filesvnm", filesvnm);
        param.addValue("filepath", filepath);

        String sql = """
                INSERT INTO TB_E038
                    (custcd, spjangcd, rptdate, perid, rptnum,
                     actcd, actnm, wkcd, frtime, totime, equpcd, remark,
                     filesvnm, filepath)
                VALUES
                    (:custcd, :spjangcd, :rptdate, :perid, :rptnum,
                     :actcd, :actnm, :wkcd, :frtime, :totime, :equpcd, :remark,
                     :filesvnm, :filepath)
                """;

        namedParameterJdbcTemplate.update(sql, param);
    }

    // ── TB_E037 HEAD MERGE ────────────────────────────────────
    private void mergeHead(String custcd, String spjangcd, String rptdate, String perid) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd", custcd);
        param.addValue("spjangcd", spjangcd);
        param.addValue("rptdate", rptdate);
        param.addValue("perid", perid);

        String sql = """
                MERGE INTO TB_E037 AS target
                USING (SELECT
                           :custcd   AS custcd,
                           :spjangcd AS spjangcd,
                           :rptdate  AS rptdate,
                           :perid    AS perid
                       ) AS source
                ON (    target.custcd   = source.custcd
                    AND target.spjangcd = source.spjangcd
                    AND target.rptdate  = source.rptdate
                    AND target.perid    = source.perid )
                WHEN NOT MATCHED THEN
                    INSERT (custcd, spjangcd, rptdate, perid)
                    VALUES (:custcd, :spjangcd, :rptdate, :perid);
                """;

        namedParameterJdbcTemplate.update(sql, param);
    }

    // ── TB_E038 rptnum 채번 (001 ~ 999) ──────────────────────
    private String getNextRptnum(String custcd, String spjangcd, String rptdate, String perid) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd", custcd);
        param.addValue("spjangcd", spjangcd);
        param.addValue("rptdate", rptdate);
        param.addValue("perid", perid);

        String sql = """
                SELECT ISNULL(MAX(CAST(rptnum AS INT)), 0) + 1
                FROM TB_E038
                WHERE custcd   = :custcd
                  AND spjangcd = :spjangcd
                  AND rptdate  = :rptdate
                  AND perid    = :perid
                """;

        Integer next = namedParameterJdbcTemplate.queryForObject(sql, param, Integer.class);
        if (next == null) next = 1;
        return String.format("%03d", next);
    }

    // ── 업무일지 현황 조회 (TB_E038 + TB_E037) ───────────────
    public List<Map<String, Object>> getStatusList(String custcd, String spjangcd, String fromDate, String toDate, String actnm) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd", custcd);
        param.addValue("spjangcd", spjangcd);
        param.addValue("fromDate", fromDate);
        param.addValue("toDate", toDate);

        String sql = """
                SELECT
                    e.rptdate,
                    e.perid,
                    e.rptnum,
                    j.pernm,
                    e.actcd,
                    e.actnm,
                    e.equpcd,
                    eq.equpnm,
                    e.wkcd,
                    e.frtime,
                    e.totime,
                    e.remark,
                    e.filesvnm,
                    e.filepath
                FROM TB_E038 e
                LEFT JOIN TB_JA001 j  ON j.perid    = 'p' + e.perid
                                     AND j.spjangcd  = e.spjangcd
                LEFT JOIN TB_E611 eq  ON eq.actcd    = e.actcd
                                     AND eq.equpcd   = e.equpcd
                                     AND eq.spjangcd = e.spjangcd
                WHERE e.custcd   = :custcd
                  AND e.spjangcd = :spjangcd
                  AND e.rptdate  BETWEEN :fromDate AND :toDate
                """;

        if (actnm != null && !actnm.trim().isEmpty()) {
            sql += " AND e.actnm LIKE :actnm";
            param.addValue("actnm", "%" + actnm.trim() + "%");
        }

        sql += " ORDER BY e.rptdate DESC, e.perid, e.rptnum";
        return this.sqlRunner.getRows(sql, param);
    }

    // ── 업무일지 수정 (TB_E038 UPDATE) ───────────────────────
    public void updateStatus(String custcd, String spjangcd,
                             String rptdate, String perid, String rptnum,
                             String actcd, String actnm, String equpcd,
                             String wkcd, String frtime, String totime, String remark,
                             String filesvnm, String filepath, String fileDeleted) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd",   custcd);
        param.addValue("spjangcd", spjangcd);
        param.addValue("rptdate",  rptdate);
        param.addValue("perid",    perid);
        param.addValue("rptnum",   rptnum);
        param.addValue("actcd",    actcd);
        param.addValue("actnm",    actnm);
        param.addValue("equpcd",   equpcd);
        param.addValue("wkcd",     wkcd);
        param.addValue("frtime",   frtime);
        param.addValue("totime",   totime);
        param.addValue("remark",   remark);

        String sql;
        if ("1".equals(fileDeleted)) {
            // 파일 교체 또는 삭제 — filesvnm, filepath도 UPDATE
            param.addValue("filesvnm", filesvnm != null ? filesvnm : "");
            param.addValue("filepath",  filepath  != null ? filepath  : "");
            sql = """
                    UPDATE TB_E038 SET
                        actcd    = :actcd,
                        actnm    = :actnm,
                        equpcd   = :equpcd,
                        wkcd     = :wkcd,
                        frtime   = :frtime,
                        totime   = :totime,
                        remark   = :remark,
                        filesvnm = :filesvnm,
                        filepath  = :filepath
                    WHERE custcd   = :custcd
                      AND spjangcd = :spjangcd
                      AND rptdate  = :rptdate
                      AND perid    = :perid
                      AND rptnum   = :rptnum
                    """;
        } else {
            // 파일 변경 없음
            sql = """
                    UPDATE TB_E038 SET
                        actcd  = :actcd,
                        actnm  = :actnm,
                        equpcd = :equpcd,
                        wkcd   = :wkcd,
                        frtime = :frtime,
                        totime = :totime,
                        remark = :remark
                    WHERE custcd   = :custcd
                      AND spjangcd = :spjangcd
                      AND rptdate  = :rptdate
                      AND perid    = :perid
                      AND rptnum   = :rptnum
                    """;
        }

        namedParameterJdbcTemplate.update(sql, param);
    }

    // ── 업무일지 단건 조회 (파일 삭제용) ─────────────────────
    public Map<String, Object> getStatusOne(String custcd, String spjangcd,
                                            String rptdate, String perid, String rptnum) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd",   custcd);
        param.addValue("spjangcd", spjangcd);
        param.addValue("rptdate",  rptdate);
        param.addValue("perid",    perid);
        param.addValue("rptnum",   rptnum);

        return namedParameterJdbcTemplate.queryForList("""
                SELECT filesvnm, filepath
                FROM TB_E038
                WHERE custcd   = :custcd
                  AND spjangcd = :spjangcd
                  AND rptdate  = :rptdate
                  AND perid    = :perid
                  AND rptnum   = :rptnum
                """, param).stream().findFirst().orElse(null);
    }

    // ── 업무일지 삭제 (TB_E038 DELETE) ───────────────────────
    public void deleteStatus(String custcd, String spjangcd, String rptdate, String perid, String rptnum) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd", custcd);
        param.addValue("spjangcd", spjangcd);
        param.addValue("rptdate", rptdate);
        param.addValue("perid", perid);
        param.addValue("rptnum", rptnum);

        namedParameterJdbcTemplate.update("""
                DELETE FROM TB_E038
                WHERE custcd   = :custcd
                  AND spjangcd = :spjangcd
                  AND rptdate  = :rptdate
                  AND perid    = :perid
                  AND rptnum   = :rptnum
                """, param);
    }
}
