package mes.app.clock.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class DayMonthlyService {

    @Autowired
    SqlRunner sqlRunner;

    @Autowired
    @Qualifier("mainSqlRunner")
    SqlRunner mainSqlRunner;   // 본사 DB (auth_user.is_active 기준 활성여부 판단용)

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NamedParameterJdbcTemplate namedJdbc;

    // =========================================================
    // 본사 DB(auth_user)에서 비활성(is_active=false) 사용자의 personid 목록 조회
    //   - is_active 의 실제 값은 본사 DB에만 정확히 반영됨(사업체 DB 복제본은 미동기화)
    //   - 사업체 DB 근태 조회에서 이 personid 들을 제외하기 위함
    // =========================================================
    private List<Integer> getInactivePersonIds(String spjangcd) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("spjangcd", spjangcd);
        String sql = """
                SELECT personid
                FROM auth_user
                WHERE is_active = false
                  AND personid IS NOT NULL
                  AND spjangcd = :spjangcd
                """;
        try {
            List<Map<String, Object>> rows = this.mainSqlRunner.getRows(sql, p);
            return rows.stream()
                    .map(r -> r.get("personid"))
                    .filter(java.util.Objects::nonNull)
                    .map(v -> ((Number) v).intValue())
                    .toList();
        } catch (Exception e) {
            log.warn("getInactivePersonIds 조회 실패 - 활성필터 미적용: {}", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    // =========================================================
    // 일별 근태 목록 조회
    // =========================================================
    public List<Map<String, Object>> getDayList(String work_division, String serchday, String spjangcd, String depart,
                                                String officecd) {
        MapSqlParameterSource paramMap = new MapSqlParameterSource();

        String workym = null;
        String workday = null;
        if (serchday != null && serchday.length() == 8) {
            workym  = serchday.substring(0, 6);
            workday = serchday.substring(6, 8);
        }

        paramMap.addValue("work_division", work_division != null ? work_division : "");
        paramMap.addValue("depart_id",     depart        != null ? depart        : "");
        paramMap.addValue("officecd",      officecd      != null ? officecd.trim() : "");
        paramMap.addValue("workym",  workym);
        paramMap.addValue("workday", workday);
        paramMap.addValue("spjangcd", spjangcd);

        // 본사 DB 기준 비활성 사용자 personid (사업체 DB에서 제외)
        List<Integer> inactiveIds = getInactivePersonIds(spjangcd);
        boolean hasInactive = inactiveIds != null && !inactiveIds.isEmpty();
        paramMap.addValue("inactiveIds", hasInactive ? inactiveIds : java.util.List.of(-1));

        String sql = """
                SELECT 
                        ROW_NUMBER() OVER (ORDER BY p.Name) AS row_num,
                        t.workym,
                        t.workday,
                        SUBSTRING(t.workym, 1, 4) + '-' + SUBSTRING(t.workym, 5, 2) + '-' + RIGHT('0' + t.workday, 2) AS workymd,
                        p.id,
                        t.worknum,
                        t.holiyn,
                        t.workyn,
                        t.workcd,
                        t.starttime,
                        t.endtime,
                        t.worktime,
                        t.nomaltime,
                        t.overtime,
                        t.nighttime,
                        t.holitime,
                        t.jitime,
                        t.jotime,
                        t.yuntime,
                        t.abtime,
                        t.bantime,
                        t.remark,
                        t.out_remark,
                        t.fixflag,
                        t.address,
                        t.latitude,
                        t.longitude,
                        t.out_address,
                        t.out_latitude,
                        t.out_longitude,
                        t.latitude,
                        t.longitude,
                        t.out_address,
                        t.out_latitude,
                        t.out_longitude,
                        g.Value AS group_name,
                        s.Value as jik_id,
                        tp210.worknm as worknm,
                        p.Name as first_name,
                        au.username as username,
                        pz.RSPNM,
                        t.spjangcd as spjangcd
                    FROM person p
                    LEFT JOIN tb_pb201 t
                        ON t.perid = p.id
                        AND t.workym = :workym
                        AND t.workday = :workday
                    LEFT JOIN (
                        SELECT Code, Value
                        FROM sys_code
                        WHERE CodeType = 'work_division'
                    ) g ON g.Code = RIGHT('0' + CAST(p.PersonGroup_id AS VARCHAR), 2)
                    LEFT JOIN (
                        SELECT Code, Value
                        FROM sys_code
                        WHERE CodeType = 'jik_type'
                    ) s ON s.Code = p.jik_id
                    LEFT JOIN tb_pb210 tp210 ON tp210.workcd = t.workcd
                    LEFT JOIN auth_user au ON au.personid = p.id
                    LEFT JOIN tb_ja001 j  ON j.perid = CONCAT('p', au.username) AND j.spjangcd = p.spjangcd
                    LEFT JOIN tb_jc002 jc ON j.divicd = jc.divicd
                    LEFT JOIN tb_pz001 pz  ON j.rspcd = pz.[RSPCD]
                    WHERE (
                        :work_division = '' OR
                        RIGHT('0' + CAST(p.PersonGroup_id AS VARCHAR), 2) = :work_division
                    )
                    AND (
                        :depart_id = ''
                        OR jc.divicd = :depart_id
                    )
                    -- 팀(TB_PZ012.officecd). 사원의 소속팀은 TB_JA001.officecd 에만 있다.
                    -- char(2) vs char(3) 이라 공백 패딩을 걷어내고 비교한다.
                    AND (
                        :officecd = ''
                        OR LTRIM(RTRIM(ISNULL(j.officecd, ''))) = :officecd
                    )
                    AND p.spjangcd = :spjangcd
                    AND p.id NOT IN (:inactiveIds)
                """;

        long start = System.currentTimeMillis();
        List<Map<String, Object>> result = this.sqlRunner.getRows(sql, paramMap);
        long end = System.currentTimeMillis();
        log.info("getDayList 쿼리 실행시간: {}ms / spjangcd={}, workym={}, workday={}",
                (end - start), spjangcd, workym, workday);

        return result;
    }

    // =========================================================
    // 근태구분 목록 조회
    // =========================================================
    public List<Map<String, String>> workcdList(String spjangcd) {
        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT worknm, workcd
                FROM tb_pb210
                WHERE spjangcd = :spjangcd
            """;

        List<Map<String, Object>> rows = this.sqlRunner.getRows(sql, dicParam);
        return rows.stream()
                .map(row -> {
                    Map<String, String> map = new HashMap<>();
                    map.put("worknm", (String) row.get("worknm"));
                    map.put("workcd", (String) row.get("workcd"));
                    return map;
                })
                .toList();
    }

    // =========================================================
    // 일별 근태 저장 (savedata) - UPSERT
    // =========================================================
    @Transactional
    public int saveDayData(Map<String, Object> item, String spjangcd) {
        String workymd = (String) item.get("workymd");
        String workym  = workymd.substring(0, 4) + workymd.substring(5, 7);
        String workday = workymd.substring(8, 10);

        String perid = String.valueOf(((Number) item.get("id")).intValue());
        int idx      = item.get("idx") != null ? ((Number) item.get("idx")).intValue() : 1;

        String workcd    = (String) item.get("workcd");
        String starttime = nullIfBlank((String) item.get("starttime"));
        String endtime   = nullIfBlank((String) item.get("endtime"));
        String address   = (String) item.get("address");

        BigDecimal nomaltime = toBigDecimal(item.get("nomaltime"));
        BigDecimal overtime  = toBigDecimal(item.get("overtime"));
        BigDecimal nighttime = toBigDecimal(item.get("nighttime"));
        BigDecimal holitime  = toBigDecimal(item.get("holitime"));
        BigDecimal worktime  = toBigDecimal(item.get("worktime"));
        Integer    jitime    = toInteger(item.get("jitime"));
        Integer    yuntime   = toInteger(item.get("yuntime"));
        Integer    abtime    = toInteger(item.get("abtime"));

        // 존재 여부 확인
        String checkSql = """
                SELECT COUNT(*) FROM tb_pb201
                WHERE spjangcd = ? AND workym = ? AND workday = ? AND perid = ? AND idx = ?
                """;
        int count = jdbcTemplate.queryForObject(checkSql, Integer.class, spjangcd, workym, workday, perid, idx);

        if (count > 0) {
            // UPDATE
            String updateSql = """
                    UPDATE tb_pb201
                    SET workcd    = ?,
                        starttime = ?,
                        endtime   = ?,
                        nomaltime = ?,
                        jitime    = ?,
                        overtime  = ?,
                        nighttime = ?,
                        yuntime   = ?,
                        abtime    = ?,
                        holitime  = ?,
                        worktime  = ?,
                        address   = ?
                    WHERE spjangcd = ? AND workym = ? AND workday = ? AND perid = ? AND idx = ?
                    """;
            return jdbcTemplate.update(updateSql,
                    workcd, starttime, endtime,
                    nomaltime, jitime, overtime, nighttime, yuntime, abtime, holitime, worktime,
                    address,
                    spjangcd, workym, workday, perid, idx);
        } else {
            // INSERT
            String insertSql = """
                    INSERT INTO tb_pb201
                        (spjangcd, workym, workday, perid, idx, fixflag,
                         workcd, starttime, endtime,
                         nomaltime, jitime, overtime, nighttime, yuntime, abtime, holitime, worktime,
                         address)
                    VALUES (?, ?, ?, ?, ?, '0', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            return jdbcTemplate.update(insertSql,
                    spjangcd, workym, workday, perid, idx,
                    workcd, starttime, endtime,
                    nomaltime, jitime, overtime, nighttime, yuntime, abtime, holitime, worktime,
                    address);
        }
    }

    // =========================================================
    // 일별 근태 수정 저장 (save - 마감 처리용)
    // =========================================================
    @Transactional
    public int saveDayMagam(Map<String, Object> item, String spjangcd) {
        String workym  = (String) item.get("workym");
        String workday = (String) item.get("workday");
        String perid   = String.valueOf(((Number) item.get("id")).intValue());
        int    idx     = item.get("idx") != null ? ((Number) item.get("idx")).intValue() : 1;

        String workcd    = (String) item.get("workcd");
        String starttime = nullIfBlank((String) item.get("starttime"));
        String endtime   = nullIfBlank((String) item.get("endtime"));
        String address   = (String) item.get("address");

        BigDecimal nomaltime = toBigDecimal(item.get("nomaltime"));
        BigDecimal overtime  = toBigDecimal(item.get("overtime"));
        BigDecimal nighttime = toBigDecimal(item.get("nighttime"));
        BigDecimal holitime  = toBigDecimal(item.get("holitime"));
        BigDecimal worktime  = toBigDecimal(item.get("worktime"));
        Integer    jitime    = toInteger(item.get("jitime"));
        Integer    yuntime   = toInteger(item.get("yuntime"));
        Integer    abtime    = toInteger(item.get("abtime"));

        String sql = """
                UPDATE tb_pb201
                SET fixflag   = '1',
                    workcd    = ?,
                    starttime = ?,
                    endtime   = ?,
                    nomaltime = ?,
                    jitime    = ?,
                    overtime  = ?,
                    nighttime = ?,
                    yuntime   = ?,
                    abtime    = ?,
                    holitime  = ?,
                    worktime  = ?,
                    address   = ?
                WHERE spjangcd = ? AND workym = ? AND workday = ? AND perid = ? AND idx = ?
                """;
        return jdbcTemplate.update(sql,
                workcd, starttime, endtime,
                nomaltime, jitime, overtime, nighttime, yuntime, abtime, holitime, worktime,
                address,
                spjangcd, workym, workday, perid, idx);
    }

    // =========================================================
    // 일별 마감 취소 - 해당 날짜 전체 idx fixflag=0
    // =========================================================
    @Transactional
    public int cancelDayMagam(String spjangcd, String perid, String workym, String workday) {
        String sql = """
                UPDATE tb_pb201
                SET fixflag = '0'
                WHERE spjangcd = ? AND perid = ? AND workym = ? AND workday = ?
                """;
        return jdbcTemplate.update(sql, spjangcd, perid, workym, workday);
    }

    // =========================================================
    // 일별 근태 삭제 (미마감 레코드만)
    // =========================================================
    @Transactional
    public int deleteDayData(String spjangcd, String perid, String workym, String workday) {
        String sql = """
                DELETE FROM tb_pb201
                WHERE spjangcd = ? AND perid = ? AND workym = ? AND workday = ?
                  AND (fixflag = '0' OR fixflag IS NULL)
                """;
        return jdbcTemplate.update(sql, spjangcd, perid, workym, workday);
    }

    // =========================================================
    // 월정산 목록 조회
    // =========================================================
    public List<Map<String, Object>> getMonthlyReadList(String person_name, String startdate, String spjangcd, String depart,
                                                        String officecd) {
        MapSqlParameterSource paramMap = new MapSqlParameterSource();
        paramMap.addValue("person_name", person_name != null ? person_name : "");
        paramMap.addValue("depart_id",   depart      != null ? depart      : "");
        paramMap.addValue("officecd",    officecd    != null ? officecd.trim() : "");
        paramMap.addValue("startdate",   startdate);
        paramMap.addValue("spjangcd",    spjangcd);

        // 본사 DB 기준 비활성 사용자 personid (사업체 DB에서 제외)
        List<Integer> inactiveIds = getInactivePersonIds(spjangcd);
        boolean hasInactive = inactiveIds != null && !inactiveIds.isEmpty();
        paramMap.addValue("inactiveIds", hasInactive ? inactiveIds : java.util.List.of(-1));

        String sql = """
            SELECT
                t.workym,
                t.personid,
                t.workday,
                t.worktime,
                t.nomaltime,
                t.overtime,
                t.nighttime,
                t.holitime,
                t.jitime,
                t.jotime,
                t.yuntime,
                t.abtime,
                t.bantime,
                t.fixflag,
                s.Value as jik_id,
                p.Name as first_name,
                pz.RSPNM,
                t.spjangcd as spjangcd
            FROM tb_pb203 t
            LEFT JOIN person p ON p.id = t.personid
            LEFT JOIN (
                SELECT Code, Value
                FROM sys_code
                WHERE CodeType = 'jik_type'
            ) s ON s.Code = p.jik_id
            LEFT JOIN auth_user au ON au.personid = p.id
            LEFT JOIN tb_ja001 j  ON j.perid = CONCAT('p', au.username) AND j.spjangcd = p.spjangcd
            LEFT JOIN tb_jc002 jc ON j.divicd = jc.divicd
            LEFT JOIN tb_pz001 pz  ON j.rspcd = pz.RSPCD
            WHERE
                (:person_name = '' OR CAST(t.personid AS VARCHAR) = :person_name)
                AND (:depart_id = '' OR jc.divicd = :depart_id)
                -- 팀(TB_PZ012.officecd) — getDayList 와 동일한 조건
                AND (:officecd = '' OR LTRIM(RTRIM(ISNULL(j.officecd, ''))) = :officecd)
                AND t.spjangcd = :spjangcd
                AND t.workym = :startdate
                AND p.id NOT IN (:inactiveIds)
        """;

        return this.sqlRunner.getRows(sql, paramMap);
    }

    // =========================================================
    // 월정산 실행 (재정산 가능)
    //   - 이미 월마감(fixflag='1')된 인원이 한 명이라도 있으면 중단
    //     → 월 마감 취소 후 재정산하도록 안내
    //   - 미마감(fixflag='0') 행만 삭제 후 재집계하므로 중복 누적 없음
    //   반환: { fixedCount : 월마감 인원수, insertCount : 재집계 건수 }
    // =========================================================
    @Transactional
    public Map<String, Object> insertWorkSummary(String spjangcd, String workym) {
        Map<String, Object> ret = new HashMap<>();

        // 1) 월마감 인원 확인
        String fixedSql = """
                SELECT COUNT(*) FROM tb_pb203
                WHERE spjangcd = ? AND workym = ? AND fixflag = '1'
                """;
        int fixedCount = jdbcTemplate.queryForObject(fixedSql, Integer.class, spjangcd, workym);

        if (fixedCount > 0) {
            ret.put("fixedCount",  fixedCount);
            ret.put("insertCount", 0);
            return ret;   // 재정산 중단
        }

        // 2) 미마감 행 삭제 (재정산 시 중복 방지)
        String deleteSql = """
                DELETE FROM tb_pb203
                WHERE spjangcd = ? AND workym = ?
                  AND (fixflag = '0' OR fixflag IS NULL)
                """;
        jdbcTemplate.update(deleteSql, spjangcd, workym);

        // 3) 일별 마감(fixflag='1') 데이터로 재집계
        String insertSql = """
                INSERT INTO tb_pb203 (
                    workym, workday, personid, fixflag,
                    worktime, nomaltime, overtime, nighttime, holitime,
                    jitime, jotime, yuntime, abtime, bantime, spjangcd
                )
                SELECT
                    ? AS workym,
                    COUNT(DISTINCT t.workday) AS workday,
                    t.perid,
                    '0' AS fixflag,
                    SUM(t.worktime),
                    SUM(t.nomaltime),
                    SUM(t.overtime),
                    SUM(t.nighttime),
                    SUM(t.holitime),
                    SUM(t.jitime),
                    SUM(t.jotime),
                    SUM(t.yuntime),
                    SUM(t.abtime),
                    SUM(t.bantime),
                    t.spjangcd
                FROM tb_pb201 t
                WHERE t.spjangcd = ?
                  AND t.workym = ?
                  AND t.fixflag = '1'
                GROUP BY t.perid, t.workym, t.spjangcd
                """;
        int insertCount = jdbcTemplate.update(insertSql, workym, spjangcd, workym);

        ret.put("fixedCount",  0);
        ret.put("insertCount", insertCount);
        return ret;
    }

    // =========================================================
    // 월정산 마감 저장
    // =========================================================
    @Transactional
    public int saveMonthlyMagam(Map<String, Object> item) {
        String     workym    = (String) item.get("workym");
        String     spjangcd  = (String) item.get("spjangcd");
        int        personid  = ((Number) item.get("personid")).intValue();

        BigDecimal nomaltime = toBigDecimal(item.get("nomaltime"));
        BigDecimal worktime  = toBigDecimal(item.get("worktime"));
        BigDecimal jitime    = toBigDecimal(item.get("jitime"));
        BigDecimal jotime    = toBigDecimal(item.get("jotime"));
        BigDecimal overtime  = toBigDecimal(item.get("overtime"));
        BigDecimal nighttime = toBigDecimal(item.get("nighttime"));
        BigDecimal yuntime   = toBigDecimal(item.get("yuntime"));
        BigDecimal abtime    = toBigDecimal(item.get("abtime"));
        BigDecimal holitime  = toBigDecimal(item.get("holitime"));
        Integer    workday   = toInteger(item.get("workday"));

        String sql = """
                UPDATE tb_pb203
                SET fixflag   = '1',
                    workday   = ?,
                    nomaltime = ?,
                    worktime  = ?,
                    jitime    = ?,
                    jotime    = ?,
                    overtime  = ?,
                    nighttime = ?,
                    yuntime   = ?,
                    abtime    = ?,
                    holitime  = ?
                WHERE spjangcd = ? AND workym = ? AND personid = ?
                """;
        return jdbcTemplate.update(sql,
                workday, nomaltime, worktime, jitime, jotime,
                overtime, nighttime, yuntime, abtime, holitime,
                spjangcd, workym, personid);
    }

    // =========================================================
    // 월정산 삭제
    // =========================================================
    @Transactional
    public int deleteMonthly(String spjangcd, String workym, int personid) {
        String sql = "DELETE FROM tb_pb203 WHERE spjangcd = ? AND workym = ? AND personid = ?";
        return jdbcTemplate.update(sql, spjangcd, workym, personid);
    }

    // =========================================================
    // 월정산 마감 취소
    // =========================================================
    @Transactional
    public int cancelMonthlyMagam(String spjangcd, String workym, int personid) {
        String sql = "UPDATE tb_pb203 SET fixflag = '0' WHERE spjangcd = ? AND workym = ? AND personid = ?";
        return jdbcTemplate.update(sql, spjangcd, workym, personid);
    }

    // =========================================================
    // 팀 목록 조회 (TB_PZ012)
    //   ACTAS 마스터는 한 테이블에 여러 회사가 섞이는 구조라 custcd 를 반드시 건다.
    //   (실제로 일부 사업체 DB의 TB_PZ012 에 타 회사 행이 남아 있었다)
    // =========================================================
    public List<Map<String, Object>> getTeamList(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);

        Map<String, Object> row = this.sqlRunner.getRow(
                "SELECT custcd FROM tb_xa012 WHERE spjangcd = :spjangcd", param);
        if (row == null || row.get("custcd") == null) return List.of();
        param.addValue("custcd", String.valueOf(row.get("custcd")).trim());

        String sql = """
                    SELECT
                        LTRIM(RTRIM(officecd)) as value,
                        officenm              as text
                    FROM TB_PZ012 WITH(NOLOCK)
                    WHERE custcd   = :custcd
                      AND spjangcd = :spjangcd
                      AND useyn    = '1'
                    ORDER BY officecd
                """;
        return this.sqlRunner.getRows(sql, param);
    }

    // =========================================================
    // 부서 목록 조회
    // =========================================================
    public List<Map<String, Object>> getDepartList(String spjangcd) {
        String sql = """
                    SELECT
                        divicd as value,
                        divinm as text
                    FROM tb_jc002
                    WHERE spjangcd = :spjangcd
                    ORDER BY divinm
                """;
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        return sqlRunner.getRows(sql, param);
    }

    // =========================================================
    // 유틸 메서드
    // =========================================================
    private String nullIfBlank(String s) {
        return (s != null && !s.trim().isEmpty()) ? s.trim() : null;
    }

    private BigDecimal toBigDecimal(Object val) {
        if (val == null) return null;
        try { return new BigDecimal(val.toString()); } catch (NumberFormatException e) { return null; }
    }

    private Integer toInteger(Object val) {
        if (val == null) return null;
        try { return Integer.parseInt(val.toString()); } catch (NumberFormatException e) { return null; }
    }
}
