package mes.app.AS.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.files.NcpObjectStorageService;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class DailyManageService {

    /** 결재문서번호 채번 재시도 횟수. MAX+1 이라 한 번만 다시 받아도 대개 풀린다. */
    private static final int APPNUM_MAX_ATTEMPT = 3;

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

    /**
     * 결재문서번호(TB_E037.appnum) 확보. 이미 있으면 그대로 쓰고, 없으면 채번한다.
     *
     * 지금까지 appnum 은 파워빌더가 업무일지를 저장할 때만 붙였다. 웹/모바일의 업무일지 등록은
     * TB_E037 을 MERGE 로 만들기만 하고 appnum 을 넣지 않아서, 웹에서 만든 일지는 상신할 때
     * "결재문서번호(appnum)가 없습니다" 로 막혔다. (경기 8,522건 중 1건이 그 상태)
     *
     * 형식은 파워빌더와 동일하게 rptdate 의 YYYYMM + '101'(업무일지 양식코드) + 4자리 일련번호.
     * 일련번호는 월 단위로 0001 부터 시작하며, 삭제된 번호는 재사용하지 않으므로 건수가 아니라
     * 그 달의 최대값 + 1 이다. (2026-04: 138건인데 최대 0139)
     *
     * 채번과 기록을 한 UPDATE 문 안에서 끝내고 같은 달 범위에 UPDLOCK/HOLDLOCK 을 걸어
     * 웹 요청끼리는 같은 번호가 나오지 않게 한다. 파워빌더는 이 잠금 밖이라 동시 저장 시
     * 충돌 가능성이 남는데, 웹 채번은 '상신 시점의 미채번 건'에만 걸려 창이 매우 좁다.
     *
     * 그 좁은 창까지 막기 위해, 채번 후 그 번호가 유일한지 확인하고 겹쳤으면 비우고 다시 받는다.
     * (MAX+1 이므로 재시도하면 방금 쓴 번호보다 큰 값이 나와 같은 충돌이 반복되지 않는다)
     * 겹치는 순간 WARN 을 남긴다 — 파워빌더와의 충돌이 실제로 일어나는지 확인할 근거가 된다.
     * TB_E037 에는 appnum 유니크 인덱스가 없어서(PK 는 custcd+spjangcd+rptdate+perid) DB 가
     * 막아주지 못한다. 실제로 중복이 남아 있다 — 경기 2건, 히츠 16건. 같은 appnum 을 쓰는
     * 문서끼리는 결재 이력이 섞인다. 결재 조회가 전부 'WHERE appnum = ...' 만 보기 때문이다.
     *
     * @return 확보된 appnum. 헤드가 없거나 끝내 유일한 번호를 못 받으면 null.
     */
    public String ensureAppnum(String custcd, String spjangcd, String rptdate, String perid) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd",   custcd);
        param.addValue("spjangcd", spjangcd);
        param.addValue("rptdate",  rptdate);
        param.addValue("perid",    perid);

        String selectSql = """
                SELECT appnum FROM TB_E037
                 WHERE custcd=:custcd AND spjangcd=:spjangcd AND rptdate=:rptdate AND perid=:perid
                """;
        Map<String, Object> row = sqlRunner.getRow(selectSql, param);
        if (row == null) return null;                       // 헤드 자체가 없음

        String appnum = row.get("appnum") == null ? "" : String.valueOf(row.get("appnum")).trim();
        if (!appnum.isEmpty()) return appnum;               // 파워빌더가 이미 붙여둔 번호

        for (int attempt = 1; attempt <= APPNUM_MAX_ATTEMPT; attempt++) {
            assignAppnum(param);

            row = sqlRunner.getRow(selectSql, param);
            appnum = (row == null || row.get("appnum") == null) ? "" : String.valueOf(row.get("appnum")).trim();
            if (appnum.isEmpty()) return null;              // 채번 실패

            if (countByAppnum(custcd, spjangcd, appnum) <= 1) return appnum;

            log.warn("[업무일지 채번 충돌] appnum={} 이 이미 사용 중이다. rptdate={}, perid={}, {}회차 — 비우고 재채번한다.",
                    appnum, rptdate, perid, attempt);
            clearAppnum(param);
        }

        // 여기까지 오면 비운 상태로 끝난다. 번호를 붙이지 않는 편이 남의 결재에 섞이는 것보다 낫다.
        log.error("[업무일지 채번 실패] {}회 시도했으나 유일한 appnum 을 얻지 못했다. custcd={}, rptdate={}, perid={}",
                APPNUM_MAX_ATTEMPT, custcd, rptdate, perid);
        return null;
    }

    /** 같은 사업체 안에서 그 appnum 을 쓰는 헤드 수. 1 이면 정상. */
    private int countByAppnum(String custcd, String spjangcd, String appnum) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("custcd",   custcd);
        p.addValue("spjangcd", spjangcd);
        p.addValue("appnum",   appnum);
        Map<String, Object> r = sqlRunner.getRow("""
                SELECT COUNT(*) AS cnt FROM TB_E037
                 WHERE custcd=:custcd AND spjangcd=:spjangcd AND appnum=:appnum
                """, p);
        return r == null ? 0 : ((Number) r.get("cnt")).intValue();
    }

    /** 재채번을 위해 내 헤드의 appnum 만 비운다. */
    private void clearAppnum(MapSqlParameterSource param) {
        namedParameterJdbcTemplate.update("""
                UPDATE TB_E037 SET appnum = ''
                 WHERE custcd=:custcd AND spjangcd=:spjangcd AND rptdate=:rptdate AND perid=:perid
                """, param);
    }

    private void assignAppnum(MapSqlParameterSource param) {
        namedParameterJdbcTemplate.update("""
                UPDATE h
                   SET h.appnum = LEFT(h.rptdate, 6) + '101'
                       + RIGHT('0000' + CAST(
                             ISNULL((SELECT MAX(CAST(RIGHT(x.appnum, 4) AS INT))
                                       FROM TB_E037 x WITH(UPDLOCK, HOLDLOCK)
                                      WHERE x.custcd = h.custcd
                                        AND x.spjangcd = h.spjangcd
                                        AND LEN(x.appnum) = 13
                                        AND LEFT(x.appnum, 9) = LEFT(h.rptdate, 6) + '101'), 0) + 1
                         AS varchar(4)), 4)
                  FROM TB_E037 h
                 WHERE h.custcd=:custcd AND h.spjangcd=:spjangcd
                   AND h.rptdate=:rptdate AND h.perid=:perid
                   AND ISNULL(h.appnum, '') = ''
                """, param);
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
        //
        // flag 는 "지금 이 사람 차례인가" 를 뜻하는 순번 게이트다.
        // 결재함 조회가 flag='1' 만 보므로, 첫 결재자만 '1' 이고 나머지는 '0' 으로 넣는다.
        // 앞사람이 승인하면 changeApprovalState 가 다음 사람의 flag 를 '1' 로 켠다.
        //
        // 순서는 TB_E064.seq 를 따르는데 그 값이 비어 있는 사업체가 있어(경기 일부),
        // seq 값 자체로 판단하지 않고 조회 순서(ORDER BY seq)의 첫 행을 첫 결재자로 본다.
        int lineNo = 0;
        for (Map<String, Object> line : lines) {
            String kcperid = (String) line.get("kcperid");
            String seq     = String.valueOf(line.get("seq"));
            String flag    = (lineNo == 0) ? "1" : "0";
            lineNo++;

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

        // 4) TB_E037 appgubun='111'(결재중), appdate=오늘 UPDATE
        //    '101'(결재)은 결재선 전원이 승인했을 때 붙는 값이다. 상신 시점에 넣으면
        //    아무도 결재하지 않은 문서가 업무일지 화면에서 "결재" 뱃지로 보인다.
        //    최종 '101' 승격은 DailyApprovalService.changeApprovalState 가 담당한다.
        MapSqlParameterSource updParam = new MapSqlParameterSource();
        updParam.addValue("custcd",   custcd);
        updParam.addValue("spjangcd", spjangcd);
        updParam.addValue("rptdate",  rptdate);
        updParam.addValue("perid",    perid);
        updParam.addValue("today",    today);

        String updSql = """
                UPDATE TB_E037 SET
                    appgubun = '111',
                    appdate  = :today
                WHERE custcd   = :custcd
                  AND spjangcd = :spjangcd
                  AND rptdate  = :rptdate
                  AND perid    = :perid
                """;
        namedParameterJdbcTemplate.update(updSql, updParam);

        return null; // null = 성공
    }

    /**
     * 결재선에 이미 승인(101)한 사람이 있는지.
     *
     * cancelApproval 은 tb_e080 행을 지워버리므로, 결재가 시작된 뒤에 취소하면
     * 이미 처리된 결재 이력까지 사라진다. 상신자 본인이 되돌릴 수 있는 범위는
     * "아무도 결재하지 않은 상태" 까지다.
     */
    public boolean hasAnyApproval(String custcd, String spjangcd, String appnum) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd",   custcd);
        param.addValue("spjangcd", spjangcd);
        param.addValue("appnum",   appnum);
        Map<String, Object> row = sqlRunner.getRow("""
                SELECT COUNT(*) AS cnt FROM tb_e080
                 WHERE custcd=:custcd AND spjangcd=:spjangcd AND appnum=:appnum
                   AND appgubun='101'
                """, param);
        return row != null && ((Number) row.get("cnt")).intValue() > 0;
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
