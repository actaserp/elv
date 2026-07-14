package mes.app.clock.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
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
                t.appdate  as appdate,
                t.apptime  as apptime,
                t.induserid as induserid,
                ind_j.pernm as indpernm
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
               LEFT JOIN tb_ja001 ind_j  ON ind_j.perid = CONCAT('p', t.inperid) AND ind_j.spjangcd = t.spjangcd
            WHERE t.frdate <= :end_date AND t.todate >= :start_date
            AND t.spjangcd = :spjangcd
            AND (:person_name = '' OR p.Code = :person_name)
            order by t.frdate
        """;

        return this.sqlRunner.getRows(sql, paramMap);
    }

    // =========================================================
    // 휴가 승인 저장 (TB_PB204 fixflag=1 + TB_PB201 upsert)
    // =========================================================
    @Transactional
    public String saveMember(Map<String, Object> item, String spjangcd, String appperid, String appuserid) {
        int id = ((Number) item.get("id")).intValue();

        // TB_PB204 조회 (fixflag 포함 — 동시성 검증)
        String selectSql = """
                SELECT frdate, todate, workcd, perid, fixflag
                FROM tb_pb204
                WHERE id = ?
                """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(selectSql, id);
        if (rows.isEmpty()) return "대상 휴가내역을 찾을 수 없습니다.";

        Map<String, Object> pb204 = rows.get(0);

        // ★ 백엔드 상태 재확인: 이미 다른 관리자가 승인한 건이면 거부
        String curFixflag = pb204.get("fixflag") != null ? String.valueOf(pb204.get("fixflag")) : "0";
        if ("1".equals(curFixflag)) {
            return "이미 승인(확인) 처리된 휴가내역입니다. 화면을 새로고침 해주세요.";
        }

        String frdateStr = String.valueOf(pb204.get("frdate"));
        String todateStr = String.valueOf(pb204.get("todate"));
        String workcd    = String.valueOf(pb204.get("workcd"));
        String perid     = String.valueOf(pb204.get("perid"));

        // 오늘 날짜 yyyyMMdd + 현재 시각 HHmmss
        String today   = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String apptime = LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));

        // TB_PB204 fixflag = '1' + 승인자 정보 + 승인시간 업데이트
        jdbcTemplate.update("""
                UPDATE tb_pb204
                SET fixflag   = '1',
                    appdate   = ?,
                    apptime   = ?,
                    appperid  = ?,
                    appuserid = ?
                WHERE id = ?
                """, today, apptime, appperid, appuserid, id);

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
                // 행 존재 → 휴가 구분값만 UPDATE (remark 는 건드리지 않음)
                jdbcTemplate.update("""
                        UPDATE tb_pb201
                        SET workcd = ?
                        WHERE spjangcd = ? AND workym = ? AND workday = ? AND perid = ?
                        """, workcd, spjangcd, workym, workday, perid);
            } else {
                // 행 없음 → INSERT (remark 미입력)
                jdbcTemplate.update("""
                        INSERT INTO tb_pb201 (spjangcd, workym, workday, perid, workcd)
                        VALUES (?, ?, ?, ?, ?)
                        """, spjangcd, workym, workday, perid, workcd);
            }
        }
        return null;   // 성공
    }

    // =========================================================
    // 휴가 일괄 삭제
    // =========================================================
    @Transactional
    public void bulkDeleteMember(List<Object> ids) {
        for (Object id : ids) {
            jdbcTemplate.update(
                "DELETE FROM tb_pb204 WHERE id = ?",
                Integer.parseInt(String.valueOf(id))
            );
        }
    }

    // =========================================================
    // 휴가 일괄 등록 (Excel Upload)
    // =========================================================
    @Transactional
    public void bulkInsertMember(List<Map<String, Object>> list, String spjangcd, String induserid, String inperid) {
        for (Map<String, Object> item : list) {
            item.put("spjangcd", spjangcd);
            insertMember(item, spjangcd, induserid, inperid);
        }
    }

    // =========================================================
    // 휴가 임의 등록 (INSERT)
    //   신청자(perid) = 화면에서 선택된 사원, 등록자(induserid/inperid) = 로그인 사용자
    // =========================================================
    @Transactional
    public void insertMember(Map<String, Object> item, String spjangcd, String induserid, String inperid) {
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
                    (spjangcd, reqdate, perid, frdate, todate, sttime, edtime, daynum, workcd, remark, yearflag, fixflag, appgubun, induserid, inperid)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '0', '001', ?, ?)
                """, spjangcd, reqdate, perid, frdate, todate, sttime, edtime, daynum, workcd, remark, yearflag, induserid, inperid);
    }

    // =========================================================
    // 휴가 수정 (UPDATE)
    // =========================================================
    @Transactional
    public String updateMember(Map<String, Object> item) {
        int id       = ((Number) item.get("id")).intValue();

        // ★ 백엔드 상태 재확인: 승인(확인) 완료 건은 확인취소 선행 필요
        List<Map<String, Object>> chk = jdbcTemplate.queryForList(
                "SELECT fixflag FROM tb_pb204 WHERE id = ?", id);
        if (chk.isEmpty()) return "대상 휴가내역을 찾을 수 없습니다.";
        String curFixflag = chk.get(0).get("fixflag") != null ? String.valueOf(chk.get(0).get("fixflag")) : "0";
        if ("1".equals(curFixflag)) {
            return "승인(확인)된 휴가내역입니다. '확인취소' 후 수정할 수 있습니다.";
        }

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
        return null;   // 성공
    }

    // =========================================================
    // 휴가 삭제 (DELETE)
    // =========================================================
    @Transactional
    public String deleteMember(int id) {
        // ★ 백엔드 상태 재확인: 승인(확인) 완료 건은 확인취소 선행 필요
        List<Map<String, Object>> chk = jdbcTemplate.queryForList(
                "SELECT fixflag FROM tb_pb204 WHERE id = ?", id);
        if (chk.isEmpty()) return "대상 휴가내역을 찾을 수 없습니다.";
        String curFixflag = chk.get(0).get("fixflag") != null ? String.valueOf(chk.get(0).get("fixflag")) : "0";
        if ("1".equals(curFixflag)) {
            return "승인(확인)된 휴가내역입니다. '확인취소' 후 삭제할 수 있습니다.";
        }

        jdbcTemplate.update("DELETE FROM tb_pb204 WHERE id = ?", id);
        return null;   // 성공
    }

    // =========================================================
    // 휴가 승인 취소 (TB_PB204 fixflag=0 + TB_PB201 원복)
    //  - 해당 일자에 출퇴근정보(starttime/endtime) 존재 → workcd='01'(정상)
    //  - 출퇴근정보 없음 → 행 삭제
    // =========================================================
    @Transactional
    public void cancelMember(int id) {
        // 1) 대상 휴가 기간/사원 조회
        String selectSql = """
                SELECT spjangcd, frdate, todate, perid
                FROM tb_pb204
                WHERE id = ?
                """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(selectSql, id);

        // 2) fixflag 원복
        jdbcTemplate.update(
                "UPDATE tb_pb204 SET fixflag = '0', appdate = NULL, apptime = NULL, appperid = NULL, appuserid = NULL WHERE id = ?", id);

        if (rows.isEmpty()) return;

        Map<String, Object> pb204 = rows.get(0);
        String spjangcd  = String.valueOf(pb204.get("spjangcd"));
        String frdateStr = String.valueOf(pb204.get("frdate"));
        String todateStr = String.valueOf(pb204.get("todate"));
        String perid     = String.valueOf(pb204.get("perid"));

        LocalDate frdate = LocalDate.parse(frdateStr, DateTimeFormatter.ofPattern("yyyyMMdd"));
        LocalDate todate = LocalDate.parse(todateStr, DateTimeFormatter.ofPattern("yyyyMMdd"));

        // 3) 기간 날짜별로 TB_PB201 원복
        for (LocalDate date = frdate; !date.isAfter(todate); date = date.plusDays(1)) {
            String workym  = date.format(DateTimeFormatter.ofPattern("yyyyMM"));
            String workday = date.format(DateTimeFormatter.ofPattern("dd"));

            // 출근 정보(starttime) 존재 여부 확인 (퇴근 전일 수 있어 endtime 은 보지 않음)
            String checkSql = """
                    SELECT COUNT(*) FROM tb_pb201
                    WHERE spjangcd = ? AND workym = ? AND workday = ? AND perid = ?
                      AND starttime IS NOT NULL AND starttime <> ''
                    """;
            int hasCommute = jdbcTemplate.queryForObject(
                    checkSql, Integer.class, spjangcd, workym, workday, perid);

            if (hasCommute > 0) {
                // 출퇴근 정보 있음 → 구분값을 '정상(01)'으로 (remark 는 건드리지 않음)
                jdbcTemplate.update("""
                        UPDATE tb_pb201
                        SET workcd = '01'
                        WHERE spjangcd = ? AND workym = ? AND workday = ? AND perid = ?
                        """, spjangcd, workym, workday, perid);
            } else {
                // 출퇴근 정보 없음 → 행 삭제
                jdbcTemplate.update("""
                        DELETE FROM tb_pb201
                        WHERE spjangcd = ? AND workym = ? AND workday = ? AND perid = ?
                        """, spjangcd, workym, workday, perid);
            }
        }
    }
}
