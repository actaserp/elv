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
                sc.[Value] as appgubunnm,
                t.appperid as appperid,
                app_p.[Name] as appernm,
                t.appdate  as appdate
            from tb_pb204 t
               LEFT JOIN person p ON p.id = TRY_CAST(t.perid AS INT)
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
               LEFT JOIN auth_user app_au ON app_au.username = t.appuserid
               LEFT JOIN person app_p    ON app_p.id = TRY_CAST(app_au.personid AS INT)
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
    public void saveMember(Map<String, Object> item, String spjangcd, String appperid, String appuserid) {
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

        // 오늘 날짜 yyyyMMdd
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        // TB_PB204 fixflag = '1' + 승인자 정보 업데이트
        jdbcTemplate.update("""
                UPDATE tb_pb204
                SET fixflag   = '1',
                    appdate   = ?,
                    appperid  = ?,
                    appuserid = ?
                WHERE id = ?
                """, today, appperid, appuserid, id);

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
    // 휴가 일괄 등록 (Excel Upload)
    // =========================================================
    @Transactional
    public void bulkInsertMember(List<Map<String, Object>> list, String spjangcd) {
        for (Map<String, Object> item : list) {
            item.put("spjangcd", spjangcd);
            insertMember(item, spjangcd);
        }
    }

    // =========================================================
    // 휴가 임의 등록 (INSERT)
    // =========================================================
    @Transactional
    public void insertMember(Map<String, Object> item, String spjangcd) {
        String peridRaw  = String.valueOf(item.get("perid")).replace("/^p/", "").trim();
        String workcd    = String.valueOf(item.get("workcd"));
        String frdate    = String.valueOf(item.get("frdate")).replace("-", "");
        String todate    = String.valueOf(item.get("todate")).replace("-", "");
        String sttime    = item.get("sttime") != null ? String.valueOf(item.get("sttime")).replace(":", "") : "";
        String edtime    = item.get("edtime") != null ? String.valueOf(item.get("edtime")).replace(":", "") : "";
        String daynum    = String.valueOf(item.get("daynum"));
        String remark    = item.get("remark") != null ? String.valueOf(item.get("remark")) : "";
        String yearflag  = item.get("yearflag") != null ? String.valueOf(item.get("yearflag")) : "0";
        String reqdate   = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        // person 테이블에서 id 조회 (Code = 'p' + perid 형태)
        String personCode = "p" + peridRaw;
        List<Map<String, Object>> personRows = jdbcTemplate.queryForList(
                "SELECT id FROM person WHERE Code = ?", personCode);

        String perid;
        if (!personRows.isEmpty()) {
            perid = String.valueOf(personRows.get(0).get("id"));
        } else {
            // person에 없으면 원본값 그대로 사용
            perid = peridRaw;
        }

        jdbcTemplate.update("""
                INSERT INTO tb_pb204
                    (spjangcd, reqdate, perid, frdate, todate, sttime, edtime, daynum, workcd, remark, yearflag, fixflag)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '0')
                """, spjangcd, reqdate, perid, frdate, todate, sttime, edtime, daynum, workcd, remark, yearflag);
    }

    // =========================================================
    // 휴가 수정 (UPDATE)
    // =========================================================
    @Transactional
    public void updateMember(Map<String, Object> item) {
        int id       = ((Number) item.get("id")).intValue();
        String workcd   = String.valueOf(item.get("workcd"));
        String frdate   = String.valueOf(item.get("frdate")).replace("-", "");
        String todate   = String.valueOf(item.get("todate")).replace("-", "");
        String sttime   = item.get("sttime") != null ? String.valueOf(item.get("sttime")).replace(":", "") : "";
        String edtime   = item.get("edtime") != null ? String.valueOf(item.get("edtime")).replace(":", "") : "";
        String daynum   = String.valueOf(item.get("daynum"));
        String remark   = item.get("remark") != null ? String.valueOf(item.get("remark")) : "";
        String yearflag = item.get("yearflag") != null ? String.valueOf(item.get("yearflag")) : "0";

        jdbcTemplate.update("""
                UPDATE tb_pb204
                SET workcd   = ?,
                    frdate   = ?,
                    todate   = ?,
                    sttime   = ?,
                    edtime   = ?,
                    daynum   = ?,
                    remark   = ?,
                    yearflag = ?
                WHERE id = ?
                """, workcd, frdate, todate, sttime, edtime, daynum, remark, yearflag, id);
    }

    // =========================================================
    // 휴가 삭제 (DELETE)
    // =========================================================
    @Transactional
    public void deleteMember(int id) {
        jdbcTemplate.update("DELETE FROM tb_pb204 WHERE id = ?", id);
    }

    // =========================================================
    // 휴가 승인 취소 (TB_PB204 fixflag=0)
    // =========================================================
    @Transactional
    public void cancelMember(int id) {
        jdbcTemplate.update("UPDATE tb_pb204 SET fixflag = '0', appdate = NULL, appperid = NULL, appuserid = NULL WHERE id = ?", id);
    }
}
