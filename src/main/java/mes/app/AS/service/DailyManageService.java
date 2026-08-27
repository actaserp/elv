package mes.app.AS.service;

import mes.app.files.NcpObjectStorageService;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class DailyManageService {

    @Autowired
    SqlRunner sqlRunner;

    @Autowired
    NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Autowired
    NcpObjectStorageService storageService;

    // ── 헤드 목록 조회 (TB_E037 기준) ────────────────────────
    // 날짜 + 사원 단위로 묶어서 작성건수 포함 반환
    public List<Map<String, Object>> getHeadList(
            String year,
            String month,
            String pernm,
            String spjangcd,
            String perid) {

        String startDate = year + month + "01";
        String endDate   = year + month
                + String.format("%02d",
                    new java.util.GregorianCalendar(
                        Integer.parseInt(year),
                        Integer.parseInt(month) - 1, 1
                    ).getActualMaximum(java.util.Calendar.DAY_OF_MONTH));

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("startDate", startDate);
        param.addValue("endDate",   endDate);
        param.addValue("spjangcd",  spjangcd);

        String sql = """
                SELECT
                    h.custcd,
                    h.spjangcd,
                    h.rptdate,
                    h.perid,
                    h.appgubun,
                    h.appnum,
                    j.pernm,
                    pz.RSPNM     AS clanm,
                    jc.divinm,
                    COUNT(e.rptnum) AS rptcnt
                FROM TB_E037 h
                LEFT JOIN TB_E038  e  ON e.custcd   = h.custcd
                                     AND e.spjangcd  = h.spjangcd
                                     AND e.rptdate   = h.rptdate
                                     AND e.perid     = h.perid
                LEFT JOIN TB_JA001 j  ON j.perid    = 'p' + h.perid
                                     AND j.spjangcd  = h.spjangcd
                LEFT JOIN TB_JC002 jc ON j.divicd   = jc.divicd
                LEFT JOIN TB_PZ001 pz ON j.rspcd    = pz.RSPCD
                WHERE h.spjangcd = :spjangcd
                  AND h.rptdate BETWEEN :startDate AND :endDate
                """;

        if (pernm != null && !pernm.isBlank()) {
            sql += " AND j.pernm LIKE :pernm ";
            param.addValue("pernm", "%" + pernm.trim() + "%");
        }

        // 사용자(User) 그룹: 본인이 작성한 건만 조회
        if (perid != null && !perid.isBlank()) {
            sql += " AND h.perid = :perid ";
            param.addValue("perid", perid);
        }

        sql += """
                 GROUP BY
                    h.custcd, h.spjangcd, h.rptdate, h.perid,
                    h.appgubun, h.appnum,
                    j.pernm, pz.RSPNM, jc.divinm
                 ORDER BY h.rptdate DESC, j.pernm ASC
                """;

        return sqlRunner.getRows(sql, param);
    }

    // ── 상세 목록 조회 (TB_E038 기준) ────────────────────────
    // 헤드 행 클릭 시 rptdate + perid 기준으로 상세 조회
    public List<Map<String, Object>> getDetailList(
            String custcd,
            String spjangcd,
            String rptdate,
            String perid) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd",   custcd);
        param.addValue("spjangcd", spjangcd);
        param.addValue("rptdate",  rptdate);
        param.addValue("perid",    perid);

        String sql = """
                SELECT
                    e.custcd,
                    e.spjangcd,
                    e.rptdate,
                    e.perid,
                    e.rptnum,
                    e.wkcd,
                    b.businm,
                    e.actcd,
                    e.actnm,
                    e.frtime,
                    e.totime,
                    e.equpcd,
                    m.equpnm,
                    e.remark,
                    e.filesvnm,
                    e.filepath
                FROM TB_E038 e
                LEFT JOIN TB_E611 m ON m.equpcd   = e.equpcd 
                                    AND e.actcd = m.actcd
                                    AND m.spjangcd = e.spjangcd
                                    AND m.custcd = e.custcd
                LEFT JOIN TB_E021 b ON b.custcd   = e.custcd
                                   AND b.spjangcd  = e.spjangcd
                                   AND b.busicd    = e.wkcd
                WHERE e.custcd   = :custcd
                  AND e.spjangcd = :spjangcd
                  AND e.rptdate  = :rptdate
                  AND e.perid    = :perid
                ORDER BY e.rptnum ASC
                """;

        return sqlRunner.getRows(sql, param);
    }

    // ── 부서 목록 조회 (TB_JC002) ────────────────────────────
    public List<Map<String, Object>> getDeptList(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        String sql = """
                SELECT divicd, divinm
                FROM TB_JC002
                WHERE spjangcd = :spjangcd
                ORDER BY divicd ASC
                """;
        return sqlRunner.getRows(sql, param);
    }

    // ── 부서별 업무보고 조회 (TB_E037 + TB_E038 기준) ─────────
    // 특정 날짜, 특정 부서(들)에 속한 사원의 업무일지 전체 반환
    public List<Map<String, Object>> getDeptReport(
            String rptdate,
            String spjangcd,
            String divicd) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("rptdate",  rptdate);
        param.addValue("divicd",   divicd);

        String sql = """
                SELECT
                    jc.divinm,
                    j.pernm,
                    e.actnm,
                    m.equpnm,
                    e.remark,
                    e.rptdate,
                    e.perid,
                    e.rptnum
                FROM TB_E038 e
                LEFT JOIN TB_JA001 j  ON j.perid    = 'p' + e.perid
                                     AND j.spjangcd  = e.spjangcd
                LEFT JOIN TB_JC002 jc ON j.divicd   = jc.divicd
                LEFT JOIN TB_E611  m  ON m.equpcd    = e.equpcd
                                     AND m.actcd     = e.actcd
                                     AND m.spjangcd  = e.spjangcd
                WHERE e.spjangcd = :spjangcd
                  AND e.rptdate  = :rptdate
                  AND jc.divicd  = :divicd
                ORDER BY j.pernm ASC, e.rptnum ASC
                """;

        return sqlRunner.getRows(sql, param);
    }
    // 1. TB_E038 해당 건 filesvnm 조회 → NCP 파일 삭제
    // 2. TB_E038 DELETE
    // 3. TB_E037 HEAD: 잔여 TB_E038 없으면 DELETE
    public void deleteDailyReport(String custcd, String spjangcd,
                                  String rptdate, String perid, String rptnum,
                                  String dbKey) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd",   custcd);
        param.addValue("spjangcd", spjangcd);
        param.addValue("rptdate",  rptdate);
        param.addValue("perid",    perid);
        param.addValue("rptnum",   rptnum);

        // 1단계: filesvnm 조회 후 NCP 파일 삭제
        String selectSql = """
                SELECT filesvnm
                FROM TB_E038
                WHERE custcd   = :custcd
                  AND spjangcd = :spjangcd
                  AND rptdate  = :rptdate
                  AND perid    = :perid
                  AND rptnum   = :rptnum
                """;

        Map<String, Object> fileInfo = sqlRunner.getRow(selectSql, param);
        if (fileInfo != null) {
            String filesvnm = (String) fileInfo.get("filesvnm");
            if (filesvnm != null && !filesvnm.isBlank()) {
                try {
                    String objectKey = storageService.buildObjectKey(dbKey, "DAILY_REPORT", filesvnm);
                    storageService.delete(objectKey);
                } catch (Exception e) {
                    // NCP 파일 삭제 실패해도 DB 삭제는 진행
                }
            }
        }

        // 2단계: TB_E038 DELETE
        String deleteSql = """
                DELETE FROM TB_E038
                WHERE custcd   = :custcd
                  AND spjangcd = :spjangcd
                  AND rptdate  = :rptdate
                  AND perid    = :perid
                  AND rptnum   = :rptnum
                """;
        namedParameterJdbcTemplate.update(deleteSql, param);

        // 3단계: TB_E037 HEAD - 잔여 TB_E038 없으면 DELETE
        MapSqlParameterSource headParam = new MapSqlParameterSource();
        headParam.addValue("custcd",   custcd);
        headParam.addValue("spjangcd", spjangcd);
        headParam.addValue("rptdate",  rptdate);
        headParam.addValue("perid",    perid);

        String countSql = """
                SELECT COUNT(*)
                FROM TB_E038
                WHERE custcd   = :custcd
                  AND spjangcd = :spjangcd
                  AND rptdate  = :rptdate
                  AND perid    = :perid
                """;

        Integer remaining = namedParameterJdbcTemplate.queryForObject(countSql, headParam, Integer.class);
        if (remaining != null && remaining == 0) {
            String deleteHeadSql = """
                    DELETE FROM TB_E037
                    WHERE custcd   = :custcd
                      AND spjangcd = :spjangcd
                      AND rptdate  = :rptdate
                      AND perid    = :perid
                    """;
            namedParameterJdbcTemplate.update(deleteHeadSql, headParam);
        }
    }

    // ════════════════════════════════════════════════════════
    //  업무일지 등록 (웹) — 모바일 daily_report 로직 이식
    // ════════════════════════════════════════════════════════

    // ── 구분 목록 (TB_E021) ──────────────────────────────────
    public List<Map<String, Object>> getGubunList(String custcd, String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd", custcd);
        param.addValue("spjangcd", spjangcd);
        String sql = """
                SELECT busicd, businm
                FROM TB_E021
                WHERE custcd   = :custcd
                  AND spjangcd = :spjangcd
                ORDER BY busicd
                """;
        return sqlRunner.getRows(sql, param);
    }

    // ── 행선지/현장 목록 (TB_E601) ───────────────────────────
    public List<Map<String, Object>> getDestList(String custcd, String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd", custcd);
        param.addValue("spjangcd", spjangcd);
        String sql = """
                SELECT actcd, actnm
                FROM TB_E601
                WHERE custcd   = :custcd
                  AND spjangcd = :spjangcd
                ORDER BY actcd
                """;
        return sqlRunner.getRows(sql, param);
    }

    // ── 호기 목록 (TB_E611) ──────────────────────────────────
    public List<Map<String, Object>> getEqupList(String custcd, String spjangcd, String actcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd", custcd);
        param.addValue("spjangcd", spjangcd);
        param.addValue("actcd", actcd);
        String sql = """
                SELECT a.equpcd, a.equpnm
                FROM TB_E611 a WITH(NOLOCK)
                WHERE a.custcd   = :custcd
                  AND a.spjangcd = :spjangcd
                  AND a.actcd    = :actcd
                ORDER BY a.equpcd
                """;
        return sqlRunner.getRows(sql, param);
    }

    // ── 업무일지 등록 (TB_E037 MERGE + TB_E038 INSERT) ───────
    public void saveDailyReport(
            String custcd, String spjangcd, String rptdate, String perid,
            String wkcd, String actcd, String actnm,
            String frtime, String totime, String equpcd, String remark,
            String filesvnm, String filepath) {

        // 1) TB_E037 HEAD MERGE (없으면 INSERT)
        MapSqlParameterSource headParam = new MapSqlParameterSource();
        headParam.addValue("custcd", custcd);
        headParam.addValue("spjangcd", spjangcd);
        headParam.addValue("rptdate", rptdate);
        headParam.addValue("perid", perid);
        String mergeSql = """
                MERGE INTO TB_E037 AS target
                USING (SELECT :custcd AS custcd, :spjangcd AS spjangcd,
                              :rptdate AS rptdate, :perid AS perid) AS source
                ON (    target.custcd   = source.custcd
                    AND target.spjangcd = source.spjangcd
                    AND target.rptdate  = source.rptdate
                    AND target.perid    = source.perid )
                WHEN NOT MATCHED THEN
                    INSERT (custcd, spjangcd, rptdate, perid)
                    VALUES (:custcd, :spjangcd, :rptdate, :perid);
                """;
        namedParameterJdbcTemplate.update(mergeSql, headParam);

        // 2) rptnum 채번 (001~)
        String nextSql = """
                SELECT ISNULL(MAX(CAST(rptnum AS INT)), 0) + 1
                FROM TB_E038
                WHERE custcd   = :custcd
                  AND spjangcd = :spjangcd
                  AND rptdate  = :rptdate
                  AND perid    = :perid
                """;
        Integer next = namedParameterJdbcTemplate.queryForObject(nextSql, headParam, Integer.class);
        if (next == null) next = 1;
        String rptnum = String.format("%03d", next);

        // 3) TB_E038 상세 INSERT
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
        String insertSql = """
                INSERT INTO TB_E038
                    (custcd, spjangcd, rptdate, perid, rptnum,
                     actcd, actnm, wkcd, frtime, totime, equpcd, remark,
                     filesvnm, filepath)
                VALUES
                    (:custcd, :spjangcd, :rptdate, :perid, :rptnum,
                     :actcd, :actnm, :wkcd, :frtime, :totime, :equpcd, :remark,
                     :filesvnm, :filepath)
                """;
        namedParameterJdbcTemplate.update(insertSql, param);
    }

    // ── 결재상신 (tb_e064 결재라인 → tb_e080 INSERT + TB_E037 UPDATE) ──
    public String submitApproval(String custcd, String spjangcd, String appnum, String rptdate, String perid, String today) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd",   custcd);
        param.addValue("spjangcd", spjangcd);
        param.addValue("appnum",   appnum);
        param.addValue("perid",    perid);

        // 1) 반려(131) 재상신 시 기존 tb_e080 삭제
        String deleteSql = """
                DELETE FROM tb_e080
                WHERE custcd    = :custcd
                  AND spjangcd  = :spjangcd
                  AND appnum    = :appnum
                  AND repoperid = :perid
                """;
        namedParameterJdbcTemplate.update(deleteSql, param);

        // 2) tb_e064에서 결재라인 조회 (papercd='101', 본인 perid 기준)
        MapSqlParameterSource lineParam = new MapSqlParameterSource();
        lineParam.addValue("custcd",   custcd);
        lineParam.addValue("spjangcd", spjangcd);
        lineParam.addValue("perid",    perid);

        String lineSql = """
                SELECT kcperid, seq
                FROM tb_e064 WITH(NOLOCK)
                WHERE custcd   = :custcd
                  AND spjangcd = :spjangcd
                  AND perid    = :perid
                  AND papercd  = '101'
                ORDER BY seq
                """;
        List<Map<String, Object>> lines = sqlRunner.getRows(lineSql, lineParam);

        if (lines == null || lines.isEmpty()) {
            return "결재라인이 등록되어 있지 않습니다.";
        }

        // 3) tb_e080 INSERT (결재라인 순서대로)
        for (Map<String, Object> line : lines) {
            String kcperid = (String) line.get("kcperid");
            String seq     = String.valueOf(line.get("seq"));
            // flag 는 파워빌더가 결재선 전 행을 '1' 로 넣는다. 여기서 seq=1 만 '1' 을 넣으면
            // 2번 이후 결재자가 getPendingApprovalList 의 flag='1' 필터에 걸려 문서를 볼 수 없다.
            String flag    = "1";

            MapSqlParameterSource insParam = new MapSqlParameterSource();
            insParam.addValue("custcd",   custcd);
            insParam.addValue("spjangcd", spjangcd);
            insParam.addValue("appnum",   appnum);
            insParam.addValue("kcperid",  kcperid);
            insParam.addValue("seq",      seq);
            insParam.addValue("flag",     flag);
            insParam.addValue("rptdate",  rptdate);
            insParam.addValue("perid",    perid);
            insParam.addValue("today",    today);

            String insSql = """
                    INSERT INTO tb_e080
                        (custcd, spjangcd, appnum, appperid, seq,
                         flag,   repodate, papercd, repoperid, title,
                         appgubun, inperid, indate)
                    VALUES
                        (:custcd, :spjangcd, :appnum, :kcperid, :seq,
                         :flag,   :rptdate, '101',   :perid,   '업무일지',
                         '001',   :perid,   :today)
                    """;
            namedParameterJdbcTemplate.update(insSql, insParam);
        }

        // 4) TB_E037 appgubun='101'(결재), appdate=오늘 UPDATE
        MapSqlParameterSource updParam = new MapSqlParameterSource();
        updParam.addValue("custcd",   custcd);
        updParam.addValue("spjangcd", spjangcd);
        updParam.addValue("rptdate",  rptdate);
        updParam.addValue("perid",    perid);
        updParam.addValue("today",    today);

        String updSql = """
                UPDATE TB_E037 SET
                    appgubun = '101',
                    appdate  = :today
                WHERE custcd   = :custcd
                  AND spjangcd = :spjangcd
                  AND rptdate  = :rptdate
                  AND perid    = :perid
                """;
        namedParameterJdbcTemplate.update(updSql, updParam);

        return null; // null = 성공
    }

    // ── 결재상신 취소 (tb_e080 DELETE + TB_E037 UPDATE) ──────
    public void cancelApproval(String custcd, String spjangcd, String appnum, String rptdate, String perid) {

        // 1) tb_e080 삭제
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd",   custcd);
        param.addValue("spjangcd", spjangcd);
        param.addValue("appnum",   appnum);
        param.addValue("perid",    perid);

        String deleteSql = """
                DELETE FROM tb_e080
                WHERE custcd    = :custcd
                  AND spjangcd  = :spjangcd
                  AND appnum    = :appnum
                  AND repoperid = :perid
                """;
        namedParameterJdbcTemplate.update(deleteSql, param);

        // 2) TB_E037 appgubun='', appdate='' UPDATE
        MapSqlParameterSource updParam = new MapSqlParameterSource();
        updParam.addValue("custcd",   custcd);
        updParam.addValue("spjangcd", spjangcd);
        updParam.addValue("rptdate",  rptdate);
        updParam.addValue("perid",    perid);

        String updSql = """
                UPDATE TB_E037 SET
                    appgubun = '',
                    appdate  = ''
                WHERE custcd   = :custcd
                  AND spjangcd = :spjangcd
                  AND rptdate  = :rptdate
                  AND perid    = :perid
                """;
        namedParameterJdbcTemplate.update(updSql, updParam);
    }

    // ── 업무일지 수정 (TB_E038 UPDATE) ───────────────────────
    public void updateDailyReport(
            String custcd, String spjangcd, String rptdate, String perid, String rptnum,
            String wkcd, String actcd, String actnm, String equpcd,
            String frtime, String totime, String remark) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd",   custcd);
        param.addValue("spjangcd", spjangcd);
        param.addValue("rptdate",  rptdate);
        param.addValue("perid",    perid);
        param.addValue("rptnum",   rptnum);
        param.addValue("wkcd",     wkcd);
        param.addValue("actcd",    actcd);
        param.addValue("actnm",    actnm);
        param.addValue("equpcd",   equpcd);
        param.addValue("frtime",   frtime);
        param.addValue("totime",   totime);
        param.addValue("remark",   remark);

        String sql = """
                UPDATE TB_E038 SET
                    wkcd   = :wkcd,
                    actcd  = :actcd,
                    actnm  = :actnm,
                    equpcd = :equpcd,
                    frtime = :frtime,
                    totime = :totime,
                    remark = :remark
                WHERE custcd   = :custcd
                  AND spjangcd = :spjangcd
                  AND rptdate  = :rptdate
                  AND perid    = :perid
                  AND rptnum   = :rptnum
                """;
        namedParameterJdbcTemplate.update(sql, param);
    }
}
