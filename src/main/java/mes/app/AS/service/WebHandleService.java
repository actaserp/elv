package mes.app.AS.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class WebHandleService {

    @Autowired
    SqlRunner sqlRunner;

    @Autowired
    NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    // ── 고장접수 목록 조회 (TB_E401) ─────────────────────────
    // myPerid 가 null 이면 전체, 값이 있으면 통보자(e.perid)=본인 건만
    public List<Map<String, Object>> getRequestList(
            String spjangcd, String fromDate, String toDate, String actnm, String myPerid) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("fromDate", fromDate);
        param.addValue("toDate",   toDate);

        String sql = """
                SELECT
                    e.recedate,
                    e.recenum,
                    e.recetime,
                    e.actcd,
                    e.actnm,
                    e.equpcd,
                    e.equpnm,
                    e.contcd,
                    ct.contnm,
                    e.contents,
                    e.remark,
                    e.resultck,
                    e.perid,
                    j.pernm   AS pernm,
                    jc.divinm AS divinm
                FROM TB_E401 e
                LEFT JOIN TB_E010 ct ON ct.contcd  = e.contcd
                                    AND ct.spjangcd = e.spjangcd
                LEFT JOIN TB_JA001 j  ON j.perid    = 'p' + e.perid
                                     AND j.spjangcd  = e.spjangcd
                LEFT JOIN TB_JC002 jc ON jc.divicd  = j.divicd
                                     AND jc.spjangcd = j.spjangcd
                WHERE e.spjangcd = :spjangcd
                  AND e.recedate BETWEEN :fromDate AND :toDate
                """;

        if (myPerid != null && !myPerid.isBlank()) {
            sql += " AND e.perid = :myPerid";
            param.addValue("myPerid", myPerid);
        }

        if (actnm != null && !actnm.isBlank()) {
            sql += " AND e.actnm LIKE :actnm";
            param.addValue("actnm", "%" + actnm.trim() + "%");
        }

        sql += " ORDER BY e.recedate DESC, e.recenum DESC";
        return this.sqlRunner.getRows(sql, param);
    }

    // ── 고장처리 단건 조회 (recedate+recenum[+actcd]) ─────────
    //   ★ 조인 규격을 PB 와 동일하게 맞춤
    //     - TB_E014(고장부위상세) 는 gregicd+regicd 복합키
    //     - 코드 마스터 조인에 spjangcd 조건 필수 (없으면 타 사업장 값이 붙음)
    //     - actcd 가 오면 조건에 포함 (같은 recedate+recenum 에 현장이 다른 건 존재)
    public Map<String, Object> getCompByReceive(String spjangcd, String recedate, String recenum, String actcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("recedate", recedate);
        param.addValue("recenum",  recenum);

        String sql = """
                SELECT
                    e.compdate, e.compnum, e.comptime,
                    e.recedate, e.recenum, e.recetime,
                    e.arrivdate, e.arrivtime,
                    e.actcd, e.actnm, e.equpcd, e.equpnm,
                    e.gregicd,
                    gr.greginm,
                    e.contremark,
                    e.regicd,
                    eg.reginm,
                    e.remocd,
                    em.remonm,
                    e.faccd,
                    f19.facnm,
                    e.remoremark,
                    e.resucd,
                    es.resunm,
                    e.resuremark,
                    e.resultcd,
                    er.resultnm,
                    e.customer,
                    e.remark,
                    e.perid  AS actperid,
                    ap.pernm AS actpernm,
                    e.filesvnm,
                    e.filepath
                FROM TB_E411 e
                LEFT JOIN TB_JA001 ap ON ap.perid    = 'p' + e.perid
                                     AND ap.spjangcd = e.spjangcd
                LEFT JOIN TB_E013 gr  ON gr.spjangcd = e.spjangcd
                                     AND gr.gregicd  = e.gregicd
                LEFT JOIN TB_E014 eg  ON eg.spjangcd = e.spjangcd
                                     AND eg.gregicd  = e.gregicd
                                     AND eg.regicd   = e.regicd
                LEFT JOIN TB_E011 em  ON em.spjangcd = e.spjangcd
                                     AND em.remocd   = e.remocd
                LEFT JOIN TB_E019 f19 ON f19.spjangcd = e.spjangcd
                                     AND f19.faccd    = e.faccd
                LEFT JOIN TB_E012 es  ON es.spjangcd = e.spjangcd
                                     AND es.resucd   = e.resucd
                LEFT JOIN TB_E015 er  ON er.spjangcd = e.spjangcd
                                     AND er.resultcd = e.resultcd
                WHERE e.spjangcd = :spjangcd
                  AND e.recedate = :recedate
                  AND e.recenum  = :recenum
                """;

        if (actcd != null && !actcd.isBlank()) {
            sql += " AND e.actcd = :actcd";
            param.addValue("actcd", actcd);
        }

        List<Map<String, Object>> rows = this.sqlRunner.getRows(sql, param);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── 고장처리 목록 조회 (TB_E411) ─────────────────────────
    public List<Map<String, Object>> getCompList(
            String spjangcd, String fromDate, String toDate, String actnm) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("fromDate", fromDate);
        param.addValue("toDate",   toDate);

        String sql = """
                SELECT
                    e.compdate,
                    e.compnum,
                    e.comptime,
                    e.recedate,
                    e.recenum,
                    e.actcd,
                    e.actnm,
                    e.equpcd,
                    e.equpnm,
                    e.contremark,
                    e.remoremark,
                    e.resuremark,
                    e.customer,
                    e.remark,
                    e.perid  AS actperid,
                    ap.pernm AS actpernm
                FROM TB_E411 e
                LEFT JOIN TB_JA001 ap ON ap.perid    = 'p' + e.perid
                                     AND ap.spjangcd = e.spjangcd
                WHERE e.spjangcd = :spjangcd
                  AND e.recedate BETWEEN :fromDate AND :toDate
                """;

        if (actnm != null && !actnm.isBlank()) {
            sql += " AND e.actnm LIKE :actnm";
            param.addValue("actnm", "%" + actnm.trim() + "%");
        }

        sql += " ORDER BY e.recedate DESC, e.recenum DESC";
        return this.sqlRunner.getRows(sql, param);
    }

    // ── 고장처리결과 등록 (TB_E411 INSERT) ───────────────────
    public void saveComp(
            String custcd, String spjangcd, String compdate, String comptime,
            String recedate, String recenum, String recetime,
            String arrivdate, String arrivtime,
            String actcd, String actnm, String equpcd, String equpnm,
            String contremark, String gregicd, String regicd,
            String remocd, String faccd, String remoremark,
            String resucd, String resuremark, String resultcd,
            String remark, String customer, String perid,
            String filesvnm, String filepath) {

        String compnum = getNextCompnum(spjangcd, compdate);

        // ★ 처리자 perid 는 'p' 없는 사번으로 저장 (파워빌더 / elv 모바일과 동일 규칙)
        //   TB_JA001.perid = 'p'+사번, 자식테이블(TB_E411/E037/E038) = 'p' 없이 저장
        String peridRaw = (perid != null) ? perid.replaceFirst("^p", "") : "";

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd",     custcd);
        param.addValue("spjangcd",   spjangcd);
        param.addValue("compdate",   compdate);
        param.addValue("compnum",    compnum);
        param.addValue("comptime",   comptime);
        param.addValue("recedate",   recedate);
        param.addValue("recenum",    recenum);
        param.addValue("recetime",   recetime);
        param.addValue("arrivdate",  arrivdate);
        param.addValue("arrivtime",  arrivtime);
        param.addValue("actcd",      actcd);
        param.addValue("actnm",      actnm);
        param.addValue("equpcd",     equpcd);
        param.addValue("equpnm",     equpnm);
        param.addValue("contremark", contremark);
        param.addValue("gregicd",    gregicd);
        param.addValue("regicd",     regicd);
        param.addValue("remocd",     remocd);
        param.addValue("faccd",      faccd);
        param.addValue("remoremark", remoremark);
        param.addValue("resucd",     resucd);
        param.addValue("resuremark", resuremark);
        param.addValue("resultcd",   resultcd);
        param.addValue("remark",     remark);
        param.addValue("customer",   customer);
        param.addValue("result",     "1");
        // ★ 담당자(actperid) = 접수건(TB_E401)의 통보자 — PB 로직과 동일. 없으면 빈값
        param.addValue("actperid",   getRecePerid(spjangcd, recedate, recenum, actcd));
        param.addValue("perid",      peridRaw);
        param.addValue("inperid",    peridRaw);
        param.addValue("indate",     compdate);
        param.addValue("filesvnm",   filesvnm != null ? filesvnm : "");
        param.addValue("filepath",   filepath  != null ? filepath  : "");

        // ── PB 규격 부가 컬럼 (의미 확정된 것만) ──────────────
        //   store / gubun / addgubun / trouble / troublesu 는 의미 미상 + DB별 값이
        //   다를 수 있어 우선 미입력(NULL)으로 둔다
        param.addValue("divicd",     getPeridDivicd(spjangcd, peridRaw));            // 처리자 부서
        param.addValue("cltcd",      getActCltcd(spjangcd, actcd));                  // 현장 거래처
        param.addValue("resutime",   calcMinutes(recedate, recetime, arrivdate, arrivtime)); // 대응시간(분)
        param.addValue("resulttime", calcMinutes(arrivdate, arrivtime, compdate, comptime)); // 처리시간(분)

        namedParameterJdbcTemplate.update("""
                INSERT INTO TB_E411
                    (custcd, spjangcd, compdate, compnum, comptime,
                     recedate, recenum, recetime, arrivdate, arrivtime,
                     actcd, actnm, equpcd, equpnm,
                     contremark, gregicd, regicd,
                     remocd, faccd, remoremark,
                     resucd, resuremark, resultcd,
                     remark, customer, result,
                     actperid, perid, inperid, indate,
                     filesvnm, filepath,
                     divicd, cltcd, resutime, resulttime)
                VALUES
                    (:custcd, :spjangcd, :compdate, :compnum, :comptime,
                     :recedate, :recenum, :recetime, :arrivdate, :arrivtime,
                     :actcd, :actnm, :equpcd, :equpnm,
                     :contremark, :gregicd, :regicd,
                     :remocd, :faccd, :remoremark,
                     :resucd, :resuremark, :resultcd,
                     :remark, :customer, :result,
                     :actperid, :perid, :inperid, :indate,
                     :filesvnm, :filepath,
                     :divicd, :cltcd, :resutime, :resulttime)
                """, param);

        // ── TB_E401 처리완료 상태 업데이트 ──────────────────
        if (recedate != null && !recedate.isBlank() && recenum != null && !recenum.isBlank()) {
            MapSqlParameterSource updateParam = new MapSqlParameterSource();
            updateParam.addValue("spjangcd", spjangcd);
            updateParam.addValue("recedate",  recedate);
            updateParam.addValue("recenum",   recenum);
            updateParam.addValue("actcd",     actcd);
            // ★ actcd 조건 필수: 같은 (recedate,recenum)에 현장이 다른 접수건이 존재할 수 있어
            //   조건이 없으면 남의 접수건까지 완료 처리됨 (실제 피해 사례 확인됨)
            namedParameterJdbcTemplate.update("""
                    UPDATE TB_E401 SET resultck = '1', trouble = '0'
                    WHERE spjangcd = :spjangcd
                      AND recedate = :recedate
                      AND recenum  = :recenum
                      AND actcd    = :actcd
                    """, updateParam);
        }

        // ── TB_E037 HEAD MERGE + TB_E038 업무일지 자동 등록 ──
        MapSqlParameterSource headParam = new MapSqlParameterSource();
        headParam.addValue("custcd",   custcd);
        headParam.addValue("spjangcd", spjangcd);
        headParam.addValue("rptdate",  compdate);
        headParam.addValue("perid",    peridRaw);  // 처리자 기준 ('p' 제거)

        namedParameterJdbcTemplate.update("""
                MERGE INTO TB_E037 AS target
                USING (SELECT :custcd AS custcd, :spjangcd AS spjangcd,
                              :rptdate AS rptdate, :perid AS perid) AS source
                ON (    target.custcd   = source.custcd
                    AND target.spjangcd = source.spjangcd
                    AND target.rptdate  = source.rptdate
                    AND target.perid    = source.perid)
                WHEN NOT MATCHED THEN
                    INSERT (custcd, spjangcd, rptdate, perid)
                    VALUES (:custcd, :spjangcd, :rptdate, :perid);
                """, headParam);

        Integer nextRpt = namedParameterJdbcTemplate.queryForObject("""
                SELECT ISNULL(MAX(CAST(rptnum AS INT)), 0) + 1
                FROM TB_E038
                WHERE custcd   = :custcd
                  AND spjangcd = :spjangcd
                  AND rptdate  = :rptdate
                  AND perid    = :perid
                """, headParam, Integer.class);
        String rptnum = String.format("%03d", nextRpt != null ? nextRpt : 1);

        MapSqlParameterSource detailParam = new MapSqlParameterSource();
        detailParam.addValue("custcd",   custcd);
        detailParam.addValue("spjangcd", spjangcd);
        detailParam.addValue("rptdate",  compdate);
        detailParam.addValue("perid",    peridRaw);
        detailParam.addValue("rptnum",   rptnum);
        detailParam.addValue("actcd",    actcd);
        detailParam.addValue("actnm",    actnm);
        detailParam.addValue("equpcd",   equpcd != null ? equpcd : "");
        // ★ wkcd = TB_E021 구분코드 '004'(고장수리)
        detailParam.addValue("wkcd",     "004");
        // ★ frtime/totime = 도착~완료 (일일보고가 시작~종료를 넣는 것과 동일한 규칙)
        //   도착시간이 없으면 완료시간으로 대체
        detailParam.addValue("frtime",   (arrivtime != null && !arrivtime.isBlank()) ? arrivtime
                                                                                    : (comptime != null ? comptime : ""));
        detailParam.addValue("totime",   comptime != null ? comptime : "");
        detailParam.addValue("remark",   customer != null ? customer : "");
        detailParam.addValue("filesvnm", filesvnm != null ? filesvnm : "");
        detailParam.addValue("filepath", filepath  != null ? filepath  : "");

        namedParameterJdbcTemplate.update("""
                INSERT INTO TB_E038
                    (custcd, spjangcd, rptdate, perid, rptnum,
                     actcd, actnm, equpcd, wkcd, frtime, totime, remark,
                     filesvnm, filepath)
                VALUES
                    (:custcd, :spjangcd, :rptdate, :perid, :rptnum,
                     :actcd, :actnm, :equpcd, :wkcd, :frtime, :totime, :remark,
                     :filesvnm, :filepath)
                """, detailParam);
    }

    // ── 고장처리결과 수정 (TB_E411 UPDATE) ───────────────────
    public void updateComp(
            String spjangcd, String compdate, String compnum, String comptime,
            String recedate, String recenum, String recetime,
            String arrivdate, String arrivtime,
            String actcd, String actnm, String equpcd, String equpnm,
            String contremark, String gregicd, String regicd,
            String remocd, String faccd, String remoremark,
            String resucd, String resuremark, String resultcd,
            String remark, String customer, String perid) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd",   spjangcd);
        param.addValue("compdate",   compdate);
        param.addValue("compnum",    compnum);
        param.addValue("comptime",   comptime);
        param.addValue("recedate",   recedate);
        param.addValue("recenum",    recenum);
        param.addValue("recetime",   recetime);
        param.addValue("arrivdate",  arrivdate);
        param.addValue("arrivtime",  arrivtime);
        param.addValue("actcd",      actcd);
        param.addValue("actnm",      actnm);
        param.addValue("equpcd",     equpcd);
        param.addValue("equpnm",     equpnm);
        param.addValue("contremark", contremark);
        param.addValue("gregicd",    gregicd);
        param.addValue("regicd",     regicd);
        param.addValue("remocd",     remocd);
        param.addValue("faccd",      faccd);
        param.addValue("remoremark", remoremark);
        param.addValue("resucd",     resucd);
        param.addValue("resuremark", resuremark);
        param.addValue("resultcd",   resultcd);
        param.addValue("remark",     remark);
        param.addValue("customer",   customer);
        // ★ 처리자(perid)만 갱신. 담당자(actperid)는 PB/등록시점 값 보존을 위해 건드리지 않음
        String peridRaw = (perid != null) ? perid.replaceFirst("^p", "") : "";
        param.addValue("perid",      peridRaw);
        // ── PB 규격 부가 컬럼 (일시/처리자 변경 시 재계산) ──
        param.addValue("divicd",     getPeridDivicd(spjangcd, peridRaw));
        param.addValue("cltcd",      getActCltcd(spjangcd, actcd));
        param.addValue("resutime",   calcMinutes(recedate, recetime, arrivdate, arrivtime));
        param.addValue("resulttime", calcMinutes(arrivdate, arrivtime, compdate, comptime));

        namedParameterJdbcTemplate.update("""
                UPDATE TB_E411 SET
                    comptime   = :comptime,
                    recedate   = :recedate,
                    recenum    = :recenum,
                    recetime   = :recetime,
                    arrivdate  = :arrivdate,
                    arrivtime  = :arrivtime,
                    actcd      = :actcd,
                    actnm      = :actnm,
                    equpcd     = :equpcd,
                    equpnm     = :equpnm,
                    contremark = :contremark,
                    gregicd    = :gregicd,
                    regicd     = :regicd,
                    remocd     = :remocd,
                    faccd      = :faccd,
                    remoremark = :remoremark,
                    resucd     = :resucd,
                    resuremark = :resuremark,
                    resultcd   = :resultcd,
                    remark     = :remark,
                    customer   = :customer,
                    perid      = :perid,
                    divicd     = :divicd,
                    cltcd      = :cltcd,
                    resutime   = :resutime,
                    resulttime = :resulttime
                WHERE spjangcd = :spjangcd
                  AND compdate = :compdate
                  AND compnum  = :compnum
                """, param);
    }

    // ── 고장처리결과 삭제 (TB_E411 DELETE) ───────────────────
    public void deleteComp(String spjangcd, String compdate, String compnum) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("compdate", compdate);
        param.addValue("compnum",  compnum);

        // 삭제 전 recedate, recenum, actcd 조회 (TB_E401 상태 복원용)
        Map<String, Object> row = namedParameterJdbcTemplate.queryForList("""
                SELECT recedate, recenum, actcd FROM TB_E411
                WHERE spjangcd = :spjangcd
                  AND compdate = :compdate
                  AND compnum  = :compnum
                """, param).stream().findFirst().orElse(null);

        namedParameterJdbcTemplate.update("""
                DELETE FROM TB_E411
                WHERE spjangcd = :spjangcd
                  AND compdate = :compdate
                  AND compnum  = :compnum
                """, param);

        // TB_E401 resultck 처리전으로 복원
        if (row != null) {
            String recedate = row.get("recedate") != null ? row.get("recedate").toString() : null;
            String recenum  = row.get("recenum")  != null ? row.get("recenum").toString()  : null;
            String actcd    = row.get("actcd")    != null ? row.get("actcd").toString()    : null;
            if (recedate != null && recenum != null && actcd != null) {
                MapSqlParameterSource updateParam = new MapSqlParameterSource();
                updateParam.addValue("spjangcd", spjangcd);
                updateParam.addValue("recedate",  recedate);
                updateParam.addValue("recenum",   recenum);
                updateParam.addValue("actcd",     actcd);
                // ★ actcd 조건 필수: 없으면 같은 (recedate,recenum)의 남의 접수건까지 미처리로 되돌아감
                namedParameterJdbcTemplate.update("""
                        UPDATE TB_E401
                        SET resultck = NULL
                        WHERE spjangcd = :spjangcd
                          AND recedate = :recedate
                          AND recenum  = :recenum
                          AND actcd    = :actcd
                        """, updateParam);
            }
        }
    }

    // ── 팝업: 현장 검색 (TB_E601) ────────────────────────────
    public List<Map<String, Object>> popupActnm(String spjangcd, String actnm) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT actcd, actnm
                FROM TB_E601
                WHERE spjangcd = :spjangcd
                """;

        if (actnm != null && !actnm.isBlank()) {
            sql += " AND actnm LIKE :actnm";
            param.addValue("actnm", "%" + actnm.trim() + "%");
        }

        sql += " ORDER BY actnm ASC";
        return this.sqlRunner.getRows(sql, param);
    }

    // ── 팝업: 호기 검색 (TB_E611) ────────────────────────────
    public List<Map<String, Object>> popupEqupnm(String spjangcd, String actcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("actcd",    actcd);

        String sql = """
                SELECT equpcd, equpnm, actcd
                FROM TB_E611
                WHERE spjangcd = :spjangcd
                  AND actcd    = :actcd
                ORDER BY equpcd ASC
                """;

        return this.sqlRunner.getRows(sql, param);
    }

    // ── 팝업: 사원 검색 (TB_JA001, 처리자) ───────────────────
    public List<Map<String, Object>> popupPernm(String spjangcd, String pernm) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT j.perid, j.pernm, jc.divinm
                FROM TB_JA001 j
                LEFT JOIN TB_JC002 jc ON j.divicd   = jc.divicd
                                     AND j.spjangcd  = jc.spjangcd
                WHERE j.spjangcd = :spjangcd
                  AND j.rtclafi  = '001'
                """;

        if (pernm != null && !pernm.isBlank()) {
            sql += " AND j.pernm LIKE :pernm";
            param.addValue("pernm", "%" + pernm.trim() + "%");
        }

        sql += " ORDER BY j.pernm ASC";
        return this.sqlRunner.getRows(sql, param);
    }

    // ── 두 일시(yyyyMMdd + HHmm) 사이의 분 차이 ────────────────
    //   PB 규격: resutime = 접수→도착(대응시간), resulttime = 도착→완료(처리시간)
    //   일자/시간이 없거나 음수면 null (컬럼 미입력)
    private Integer calcMinutes(String fromDate, String fromTime, String toDate, String toTime) {
        try {
            if (fromDate == null || fromDate.isBlank() || toDate == null || toDate.isBlank()) return null;
            if (fromTime == null || fromTime.length() < 4 || toTime == null || toTime.length() < 4) return null;

            java.time.LocalDateTime from = java.time.LocalDateTime.of(
                    Integer.parseInt(fromDate.substring(0, 4)),
                    Integer.parseInt(fromDate.substring(4, 6)),
                    Integer.parseInt(fromDate.substring(6, 8)),
                    Integer.parseInt(fromTime.substring(0, 2)),
                    Integer.parseInt(fromTime.substring(2, 4)));
            java.time.LocalDateTime to = java.time.LocalDateTime.of(
                    Integer.parseInt(toDate.substring(0, 4)),
                    Integer.parseInt(toDate.substring(4, 6)),
                    Integer.parseInt(toDate.substring(6, 8)),
                    Integer.parseInt(toTime.substring(0, 2)),
                    Integer.parseInt(toTime.substring(2, 4)));

            long min = java.time.Duration.between(from, to).toMinutes();
            return (min < 0) ? null : (int) min;
        } catch (Exception e) {
            return null;
        }
    }

    // ── 처리자 부서코드 조회 (TB_JA001.divicd) ─────────────────
    private String getPeridDivicd(String spjangcd, String peridRaw) {
        if (peridRaw == null || peridRaw.isBlank()) return null;
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("perid",    "p" + peridRaw);
        List<Map<String, Object>> rows = this.sqlRunner.getRows("""
                SELECT TOP 1 divicd FROM TB_JA001
                WHERE spjangcd = :spjangcd AND perid = :perid
                """, param);
        return rows.isEmpty() ? null : (String) rows.get(0).get("divicd");
    }

    // ── 현장 거래처코드 조회 (TB_E601.cltcd) ───────────────────
    private String getActCltcd(String spjangcd, String actcd) {
        if (actcd == null || actcd.isBlank()) return null;
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("actcd",    actcd);
        List<Map<String, Object>> rows = this.sqlRunner.getRows("""
                SELECT TOP 1 cltcd FROM TB_E601
                WHERE spjangcd = :spjangcd AND actcd = :actcd
                """, param);
        return rows.isEmpty() ? null : (String) rows.get(0).get("cltcd");
    }

    // ── 접수건 통보자 조회 (담당자 actperid 세팅용) ────────────
    //   PB: dw_1.SetItem(row, "actperid", str_e401.perid) 과 동일한 규칙
    //   ★ custcd 는 조건에서 제외: TB_E401 에 custcd 가 빈 행이 존재해
    //     (spjangcd, recedate, recenum, actcd) 로 특정한다
    private String getRecePerid(String spjangcd, String recedate, String recenum, String actcd) {
        if (recedate == null || recedate.isBlank() || recenum == null || recenum.isBlank()) return "";

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("recedate", recedate);
        param.addValue("recenum",  recenum);
        param.addValue("actcd",    actcd);

        List<Map<String, Object>> rows = this.sqlRunner.getRows("""
                SELECT TOP 1 perid
                FROM TB_E401
                WHERE spjangcd = :spjangcd
                  AND recedate = :recedate
                  AND recenum  = :recenum
                  AND actcd    = :actcd
                """, param);

        if (rows.isEmpty() || rows.get(0).get("perid") == null) return "";
        return String.valueOf(rows.get(0).get("perid")).trim().replaceFirst("^p", "");
    }

    // ── compnum 채번 ──────────────────────────────────────────
    private String getNextCompnum(String spjangcd, String compdate) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("compdate", compdate);

        String sql = """
                SELECT ISNULL(MAX(CAST(compnum AS INT)), 0) + 1
                FROM TB_E411
                WHERE spjangcd = :spjangcd
                  AND compdate = :compdate
                """;

        Integer next = namedParameterJdbcTemplate.queryForObject(sql, param, Integer.class);
        if (next == null) next = 1;
        return String.format("%03d", next);
    }
}
