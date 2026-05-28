package mes.app.clock.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
public class ClockSystemService {

    @Autowired
    SqlRunner sqlRunner;

    @Autowired
    NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    // ── 근태항목 목록 조회 (TB_PB210) ────────────────────────
    public List<Map<String, Object>> getSystemList(String spjangcd) {
        MapSqlParameterSource paramMap = new MapSqlParameterSource();
        paramMap.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT
                    t.workcd,
                    t.yearflag,
                    t.worknm,
                    t.remark,
                    t.usenum
                FROM tb_pb210 t
                WHERE t.spjangcd = :spjangcd
                """;

        return this.sqlRunner.getRows(sql, paramMap);
    }

    // ── 근태시간 목록 조회 (TB_PBCONT) ───────────────────────
    public List<Map<String, Object>> getSystemtimeList(String spjangcd) {
        MapSqlParameterSource paramMap = new MapSqlParameterSource();
        paramMap.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT
                    t.flag,
                    t.sttime,
                    t.endtime,
                    t.ovsttime,
                    t.ovedtime,
                    t.ngsttime,
                    t.ngedtime
                FROM tb_pbcont t
                WHERE t.spjangcd = :spjangcd
                """;

        return this.sqlRunner.getRows(sql, paramMap);
    }

    // ── 근태항목 상세 조회 (TB_PB210) ────────────────────────
    public Map<String, Object> getSystemDetail(String workcd, String spjangcd) {
        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("workcd",   workcd);
        dicParam.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT
                    t.workcd,
                    t.yearflag,
                    t.worknm,
                    t.remark,
                    t.usenum
                FROM tb_pb210 t
                WHERE t.spjangcd = :spjangcd
                  AND t.workcd   = :workcd
                """;

        return this.sqlRunner.getRow(sql, dicParam);
    }

    // ── 근태항목 저장 (TB_PB210 MERGE) ───────────────────────
    // 사업체DB(MSSQL) 대상 - JPA Repository 대신 SqlRunner 사용
    public void savePb210(String spjangcd, String workcd, String worknm,
                          String remark, String yearflag, BigDecimal usenum) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("workcd",   workcd);
        param.addValue("worknm",   worknm);
        param.addValue("remark",   remark);
        param.addValue("yearflag", yearflag);
        param.addValue("usenum",   usenum);

        String sql = """
                MERGE INTO tb_pb210 AS target
                USING (SELECT
                           :spjangcd AS spjangcd,
                           :workcd   AS workcd
                       ) AS source
                ON (    target.spjangcd = source.spjangcd
                    AND target.workcd   = source.workcd )
                WHEN MATCHED THEN
                    UPDATE SET
                        worknm   = :worknm,
                        remark   = :remark,
                        yearflag = :yearflag,
                        usenum   = :usenum
                WHEN NOT MATCHED THEN
                    INSERT (spjangcd, workcd, worknm, remark, yearflag, usenum)
                    VALUES (:spjangcd, :workcd, :worknm, :remark, :yearflag, :usenum);
                """;

        namedParameterJdbcTemplate.update(sql, param);
    }

    // ── 근태항목 삭제 (TB_PB210 DELETE) ──────────────────────
    // 사업체DB(MSSQL) 대상 - JPA Repository 대신 SqlRunner 사용
    public void deletePb210(String spjangcd, String workcd) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("workcd",   workcd);

        String sql = """
                DELETE FROM tb_pb210
                WHERE spjangcd = :spjangcd
                  AND workcd   = :workcd
                """;

        namedParameterJdbcTemplate.update(sql, param);
    }

    // ── 근태시간 저장 (TB_PBCONT MERGE - 여러 건) ────────────
    // 사업체DB(MSSQL) 대상 - JPA Repository 대신 SqlRunner 사용
    public void savePbcont(String spjangcd, List<Map<String, Object>> dataList) {

        String sql = """
                MERGE INTO tb_pbcont AS target
                USING (SELECT
                           :spjangcd AS spjangcd,
                           :flag     AS flag
                       ) AS source
                ON (    target.spjangcd = source.spjangcd
                    AND target.flag     = source.flag )
                WHEN MATCHED THEN
                    UPDATE SET
                        sttime   = :sttime,
                        endtime  = :endtime,
                        ovsttime = :ovsttime,
                        ovedtime = :ovedtime,
                        ngsttime = :ngsttime,
                        ngedtime = :ngedtime
                WHEN NOT MATCHED THEN
                    INSERT (spjangcd, flag, sttime, endtime, ovsttime, ovedtime, ngsttime, ngedtime)
                    VALUES (:spjangcd, :flag, :sttime, :endtime, :ovsttime, :ovedtime, :ngsttime, :ngedtime);
                """;

        for (Map<String, Object> item : dataList) {
            MapSqlParameterSource param = new MapSqlParameterSource();
            param.addValue("spjangcd", spjangcd);
            param.addValue("flag",     item.get("flag"));
            param.addValue("sttime",   item.get("sttime"));
            param.addValue("endtime",  item.get("endtime"));
            param.addValue("ovsttime", item.get("ovsttime"));
            param.addValue("ovedtime", item.get("ovedtime"));
            param.addValue("ngsttime", item.get("ngsttime"));
            param.addValue("ngedtime", item.get("ngedtime"));

            namedParameterJdbcTemplate.update(sql, param);
        }
    }
}
