package mes.app.clock.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
public class ClockMemberService {

    @Autowired
    SqlRunner sqlRunner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // =========================================================
    // 휴가 목록 조회
    // =========================================================
    public List<Map<String, Object>> getMemberList(String start_date, String end_date, String person_name, String spjangcd) {
        MapSqlParameterSource paramMap = new MapSqlParameterSource();

        paramMap.addValue("start_date", start_date);
        paramMap.addValue("end_date", end_date);
        paramMap.addValue("spjangcd", spjangcd);

        String personStr = (person_name != null) ? person_name : "";
        paramMap.addValue("person_name", personStr);

        String sql = """
          SELECT
                t.id as id,
                t.spjangcd as spjangcd,
                t.reqdate as reqdate,
                t.perid as personid,
                t.frdate as frdate,
                t.todate as todate,
                t.sttime as sttime,
                t.edtime as edtime,
                t.daynum as daynum,
                t.workcd as workcd,
                t.remark as remark,
                t.fixflag as fixflag,
                tb210.yearflag as yearflag,
                tb210.worknm as worknm,
                p.[Name] as first_name,
                s.[Value] as jik_id,
                pz.RSPNM,
                sc.[Value] as appgubunnm
            from tb_pb204 t
              LEFT JOIN person p ON p.id = t.perid
              LEFT JOIN (
                   SELECT [Code], [Value]
                   FROM sys_code
                   WHERE [CodeType] = 'jik_type'
               ) s ON s.[Code] = p.jik_id
               LEFT JOIN (
                   SELECT [Code], [Value]
                   FROM sys_code
                   WHERE [CodeType] = 'approval_status'
               ) sc ON sc.[Code] = t.appgubun
               LEFT JOIN tb_pb210 tb210 ON tb210.workcd = t.workcd
               LEFT JOIN auth_user au ON au.personid = p.id
               left join tb_xusers u on u.userid =au.username and au.last_name =u.pernm
               LEFT JOIN tb_ja001 j  ON j.perid = CONCAT('p', u.perid)
               LEFT JOIN tb_jc002 jc ON j.divicd = jc.divicd
               LEFT JOIN tb_pz001 pz  ON j.rspcd = pz.RSPCD
            WHERE t.reqdate between :start_date and :end_date
            AND t.spjangcd = :spjangcd
            AND (:person_name = '' OR CAST(t.perid AS VARCHAR(50)) = :person_name)
            order by reqdate
        """;

        return this.sqlRunner.getRows(sql, paramMap);
    }

    // =========================================================
    // 휴가 승인 저장 (TB_PB204 fixflag=1 + TB_PB201 upsert)
    // =========================================================
    @Transactional
    public void saveMember(Map<String, Object> item, String spjangcd) {
        int id = ((Number) item.get("id")).intValue();

        // TB_PB204 조회
        String selectSql = """
                SELECT frdate, todate, workcd, perid
                FROM tb_pb204
                WHERE id = ?
                """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(selectSql, id);
        if (rows.isEmpty()) return;

        Map<String, Object> pb204 = rows.get(0);
        String frdateStr = String.valueOf(pb204.get("frdate"));
        String todateStr = String.valueOf(pb204.get("todate"));
        String workcd    = String.valueOf(pb204.get("workcd"));
        String perid     = String.valueOf(pb204.get("perid"));

        // TB_PB204 fixflag = '1' 업데이트
        jdbcTemplate.update("UPDATE tb_pb204 SET fixflag = '1' WHERE id = ?", id);

        // 날짜 범위만큼 TB_PB201 upsert
        LocalDate frdate = LocalDate.parse(frdateStr, DateTimeFormatter.ofPattern("yyyyMMdd"));
        LocalDate todate = LocalDate.parse(todateStr, DateTimeFormatter.ofPattern("yyyyMMdd"));

        for (LocalDate date = frdate; !date.isAfter(todate); date = date.plusDays(1)) {
            String workym  = date.format(DateTimeFormatter.ofPattern("yyyyMM"));
            String workday = date.format(DateTimeFormatter.ofPattern("dd"));

            // 존재 여부 확인
            String checkSql = """
                    SELECT COUNT(*) FROM tb_pb201
                    WHERE spjangcd = ? AND workym = ? AND workday = ? AND perid = ?
                    """;
            int count = jdbcTemplate.queryForObject(checkSql, Integer.class, spjangcd, workym, workday, perid);

            if (count > 0) {
                // UPDATE
                jdbcTemplate.update("""
                        UPDATE tb_pb201
                        SET workcd = ?, remark = '연차 자동반영'
                        WHERE spjangcd = ? AND workym = ? AND workday = ? AND perid = ?
                        """, workcd, spjangcd, workym, workday, perid);
            } else {
                // INSERT
                jdbcTemplate.update("""
                        INSERT INTO tb_pb201 (spjangcd, workym, workday, perid, workcd, remark)
                        VALUES (?, ?, ?, ?, ?, '연차 자동반영')
                        """, spjangcd, workym, workday, perid, workcd);
            }
        }
    }

    // =========================================================
    // 휴가 승인 취소 (TB_PB204 fixflag=0)
    // =========================================================
    @Transactional
    public void cancelMember(int id) {
        jdbcTemplate.update("UPDATE tb_pb204 SET fixflag = '0' WHERE id = ?", id);
    }
}
