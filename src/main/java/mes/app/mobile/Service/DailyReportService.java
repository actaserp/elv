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

    // ── 사용자 정보 조회 (TB_JA001) ───────────────────────────
    public Map<String, Object> getUserInfo(String username) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("perid", "p" + username);

        String sql = """
                SELECT
                    j.custcd,
                    j.spjangcd,
                    j.perid,
                    j.pernm      AS first_name,
                    pz.RSPNM     AS position,
                    jc.divinm    AS department
                FROM TB_JA001 j
                LEFT JOIN TB_JC002 jc ON j.divicd = jc.divicd
                LEFT JOIN TB_PZ001 pz ON j.rspcd  = pz.RSPCD
                WHERE j.perid = :perid
                """;

        return this.sqlRunner.getRow(sql, param);
    }

    // ── 구분 목록 조회 (TB_E021) ───────────────────────────────
    public List<Map<String, Object>> getGubunList(String custcd, String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd",   custcd);
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
        param.addValue("custcd",   custcd);
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
        param.addValue("custcd",   custcd);
        param.addValue("spjangcd", spjangcd);
        param.addValue("actcd",    actcd);

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
        param.addValue("custcd",   custcd);
        param.addValue("spjangcd", spjangcd);
        param.addValue("rptdate",  rptdate);
        param.addValue("perid",    perid);
        param.addValue("rptnum",   rptnum);
        param.addValue("actcd",    actcd);
        param.addValue("actnm",    actnm);
        param.addValue("wkcd",     wkcd);
        param.addValue("frtime",   frtime);
        param.addValue("totime",   totime);
        param.addValue("equpcd",   equpcd);
        param.addValue("remark",   remark);
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
        param.addValue("custcd",   custcd);
        param.addValue("spjangcd", spjangcd);
        param.addValue("rptdate",  rptdate);
        param.addValue("perid",    perid);

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
        param.addValue("custcd",   custcd);
        param.addValue("spjangcd", spjangcd);
        param.addValue("rptdate",  rptdate);
        param.addValue("perid",    perid);

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
}
