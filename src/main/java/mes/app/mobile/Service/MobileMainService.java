package mes.app.mobile.Service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
public class MobileMainService {

    @Autowired
    SqlRunner sqlRunner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // =========================================================
    // 사용자 기본 정보 조회
    // =========================================================
    public Map<String, Object> getUserInfo(String username) {
        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("username", username);

        String sql = """
                SELECT TOP 1
                    a.first_name,
                    a.last_name,
                    jc.divinm,
                    t.starttime,
                    d.[Name],
                    s.[Value] as jik_id,
                    a.personid,
                    pz.RSPNM
                FROM auth_user a
                LEFT JOIN tb_pb201 t
                    ON  t.perid    = a.personid
                    AND t.idx      = 1
                    AND FORMAT(GETDATE(), 'dd')     = t.workday
                    AND FORMAT(GETDATE(), 'yyyyMM') = t.workym
                LEFT JOIN person p
                    ON p.id = a.personid
                LEFT JOIN depart d
                    ON p.Depart_id = d.id
                LEFT JOIN (
                    SELECT Code, Value
                    FROM sys_code
                    WHERE CodeType = 'jik_type'
                ) s ON s.Code = p.jik_id
                LEFT JOIN tb_xusers u
                    ON  u.userid   = a.username
                    AND a.last_name = u.pernm
                LEFT JOIN tb_ja001 j  ON j.perid  = CONCAT('p', u.perid)
                LEFT JOIN tb_jc002 jc ON j.divicd = jc.divicd
                LEFT JOIN tb_pz001 pz ON j.rspcd  = pz.RSPCD
                WHERE a.username = :username
                ORDER BY t.starttime DESC;
                """;

        return this.sqlRunner.getRow(sql, dicParam);
    }

    // =========================================================
    // 근태설정 조회 (근무구분별 출퇴근 기준시간)
    // =========================================================
    public Map<String, Object> getWorkTime(String workType) {
        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("workType", workType);

        String sql = """
                SELECT sttime,
                       endtime,
                       ovsttime,
                       ovedtime,
                       ngsttime,
                       ngedtime
                FROM tb_pbcont
                WHERE flag = :workType
                """;

        return this.sqlRunner.getRow(sql, dicParam);
    }

    // =========================================================
    // 일반 출근시간 조회 (idx=1)
    // =========================================================
    public Map<String, Object> getInOfficeTime(String username, String spjangcd) {
        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("username", username);
        dicParam.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT t.starttime,
                       t.workcd
                FROM auth_user a
                LEFT JOIN tb_pb201 t
                    ON  t.perid    = a.personid
                    AND t.spjangcd = :spjangcd
                    AND t.idx      = 1
                    AND FORMAT(GETDATE(), 'dd')     = t.workday
                    AND FORMAT(GETDATE(), 'yyyyMM') = t.workym
                WHERE a.username = :username
                """;

        return this.sqlRunner.getRow(sql, dicParam);
    }

    // =========================================================
    // 추가근무 출근시간 조회 (idx >= 2, 미퇴근 최신 레코드)
    // =========================================================
    public Map<String, Object> getOvertimeInfo(String username, String spjangcd) {
        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("username", username);
        dicParam.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT TOP 1
                    t.starttime,
                    t.endtime,
                    t.idx
                FROM auth_user a
                LEFT JOIN tb_pb201 t
                    ON  t.perid    = a.personid
                    AND t.spjangcd = :spjangcd
                    AND t.idx      >= 2
                    AND FORMAT(GETDATE(), 'dd')     = t.workday
                    AND FORMAT(GETDATE(), 'yyyyMM') = t.workym
                WHERE a.username  = :username
                  AND t.endtime   IS NULL
                ORDER BY t.idx DESC
                """;

        return this.sqlRunner.getRow(sql, dicParam);
    }

    // =========================================================
    // 직원코드 및 근무구분 조회
    // =========================================================
    public Map<String, Object> getPersonId(String username) {
        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("username", username);

        String sql = """
                SELECT
                    a.personid,
                    p.[PersonGroup_id]
                FROM auth_user a
                LEFT JOIN person p
                    ON p.id = a.personid
                WHERE a.username = :username
                """;

        return this.sqlRunner.getRow(sql, dicParam);
    }

    // =========================================================
    // 오늘 MAX(idx) 조회 (추가근무 출근 시 nextIdx 계산용)
    // =========================================================
    public int findMaxIdx(String spjangcd, String perId, String workym, String workday) {
        String sql = """
                SELECT ISNULL(MAX(idx), 0)
                FROM tb_pb201
                WHERE spjangcd = ? AND perid = ? AND workym = ? AND workday = ?
                """;
        Integer result = jdbcTemplate.queryForObject(sql, Integer.class, spjangcd, perId, workym, workday);
        return result != null ? result : 0;
    }

    // =========================================================
    // 특정 idx 레코드 조회 (출근 시 기존 데이터 확인용)
    // =========================================================
    public Map<String, Object> findRecord(String spjangcd, String perId, String workym, String workday, int idx) {
        String sql = """
                SELECT spjangcd, workym, workday, perid, idx,
                       starttime, endtime, workcd, jitime, holiyn,
                       worknum, inflag, address, latitude, longitude, remark
                FROM tb_pb201
                WHERE spjangcd = ? AND perid = ? AND workym = ? AND workday = ? AND idx = ?
                """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, spjangcd, perId, workym, workday, idx);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // =========================================================
    // 오늘 전체 레코드 조회 (퇴근 시 미퇴근 레코드 탐색용)
    // =========================================================
    public List<Map<String, Object>> findTodayAllRecords(String spjangcd, String perId, String workym, String workday) {
        String sql = """
                SELECT spjangcd, workym, workday, perid, idx,
                       starttime, endtime, workcd, jitime, holiyn,
                       worknum, inflag, address, latitude, longitude, remark
                FROM tb_pb201
                WHERE spjangcd = ? AND perid = ? AND workym = ? AND workday = ?
                ORDER BY idx ASC
                """;
        return jdbcTemplate.queryForList(sql, spjangcd, perId, workym, workday);
    }

    // =========================================================
    // 유연근무 신청 여부 조회
    // =========================================================
    public Map<String, Object> findFlexibleWork(String perId, String today, String workcd) {
        String sql = """
                SELECT TOP 1 id
                FROM tb_pb204
                WHERE perid = ? AND frdate <= ? AND todate >= ? AND workcd = ? AND fixflag = '1'
                """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, perId, today, today, workcd);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // =========================================================
    // 출근 저장 (INSERT or UPDATE)
    // =========================================================
    @Transactional
    public void saveCommute(String spjangcd, String perId, String workym, String workday, int idx,
                            Integer weekNum, String holiyn, String starttime, String inFlag,
                            String workcd, String address, String latitude, String longitude,
                            Integer jitime) {
        String checkSql = """
                SELECT COUNT(*) FROM tb_pb201
                WHERE spjangcd = ? AND perid = ? AND workym = ? AND workday = ? AND idx = ?
                """;
        int count = jdbcTemplate.queryForObject(checkSql, Integer.class, spjangcd, perId, workym, workday, idx);

        if (count > 0) {
            // UPDATE (기존 레코드 - 연차/반차 등이 있는 경우 출근시간만 갱신)
            jdbcTemplate.update("""
                    UPDATE tb_pb201
                    SET starttime = ?, inflag = ?, jitime = ?,
                        worknum = ?, holiyn = ?,
                        address = ?, latitude = ?, longitude = ?
                    WHERE spjangcd = ? AND perid = ? AND workym = ? AND workday = ? AND idx = ?
                    """,
                    starttime, inFlag, jitime,
                    weekNum, holiyn,
                    address, latitude, longitude,
                    spjangcd, perId, workym, workday, idx);
        } else {
            // INSERT (신규 레코드)
            jdbcTemplate.update("""
                    INSERT INTO tb_pb201
                        (spjangcd, workym, workday, perid, idx,
                         worknum, holiyn, starttime, inflag, workcd,
                         jitime, address, latitude, longitude)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    spjangcd, workym, workday, perId, idx,
                    weekNum, holiyn, starttime, inFlag, workcd,
                    jitime, address, latitude, longitude);
        }
    }

    // =========================================================
    // 퇴근 저장 (UPDATE)
    // =========================================================
    @Transactional
    public void saveEndtime(String spjangcd, String perId, String workym, String workday, int idx,
                            String endtime, String remark, String inFlag, String workyn,
                            int jotime, String workcd,
                            String address, String latitude, String longitude,
                            BigDecimal worktime, BigDecimal nomaltime, BigDecimal overtime,
                            BigDecimal nighttime, BigDecimal holitime) {
        jdbcTemplate.update("""
                UPDATE tb_pb201
                SET endtime   = ?,
                    remark    = ?,
                    inflag    = ?,
                    workyn    = ?,
                    jotime    = ?,
                    workcd    = ?,
                    address   = ?,
                    latitude  = ?,
                    longitude = ?,
                    worktime  = ?,
                    nomaltime = ?,
                    overtime  = ?,
                    nighttime = ?,
                    holitime  = ?
                WHERE spjangcd = ? AND perid = ? AND workym = ? AND workday = ? AND idx = ?
                """,
                endtime, remark, inFlag, workyn, jotime, workcd,
                address, latitude, longitude,
                worktime, nomaltime, overtime, nighttime, holitime,
                spjangcd, perId, workym, workday, idx);
    }
}
