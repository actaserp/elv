package mes.app.mobile.Service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class MaintenanceRepairService {

    @Autowired
    SqlRunner sqlRunner;

    @Autowired
    NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    // ── 사용자 정보 조회 ───────────────────────────────────────
    public Map<String, Object> getUserInfo(String username) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("username", username);

        String sql = """
                SELECT TOP 1
                    a.username,
                    a.first_name,
                    p.id,
                    an.restnum,
                    t.sttime
                FROM auth_user a
                LEFT JOIN tb_pb209 an ON an.perid = a.personid
                LEFT JOIN person p ON p.id = a.personid
                LEFT JOIN tb_pbcont t ON t.flag = RIGHT('0' + CAST(p.PersonGroup_id AS VARCHAR), 2)
                WHERE a.username = :username
                ORDER BY an.todate DESC
                """;

        return this.sqlRunner.getRow(sql, param);
    }

    // ── 고장접수 목록 조회 (TB_E401) ─────────────────────────
    //   myPerid != null 이면 통보자(e.reperid)=본인 건만, null 이면 전체
    //   ※ 통보자 = 현장으로 가는 사람. 고장처리는 통보자가 수행하므로 통보자 기준으로 필터
    public List<Map<String, Object>> getRepairList(
            String fromDate, String toDate, String actnm, String spjangcd, String myPerid, String repernm) {

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
                    e.perid,
                    j.pernm     AS pernm,
                    e.reperid,
                    rj.pernm    AS repernm,
                    e.resultck
                FROM TB_E401 e
                LEFT JOIN TB_E010 ct ON ct.contcd   = e.contcd
                                    AND ct.spjangcd  = e.spjangcd
                LEFT JOIN TB_JA001 j ON j.perid     = 'p' + e.perid
                                    AND j.spjangcd   = e.spjangcd
                LEFT JOIN TB_JA001 rj ON rj.perid   = 'p' + e.reperid
                                     AND rj.spjangcd = e.spjangcd
                WHERE e.spjangcd = :spjangcd
                  AND e.recedate BETWEEN :fromDate AND :toDate
                  AND (e.resultck IS NULL OR e.resultck <> '1')
                """;

        // 통보자=본인 필터 (전체가 아닐 때만)
        // TB_E401.perid = 통보자 (현장 가는 사람) — PB 기준 확정
        if (myPerid != null && !myPerid.isBlank()) {
            sql += " AND e.perid = :myPerid";
            param.addValue("myPerid", myPerid);
        }

        if (actnm != null && !actnm.isBlank()) {
            sql += " AND e.actnm LIKE :actnm";
            param.addValue("actnm", "%" + actnm.trim() + "%");
        }

        // 통보자명 검색
        if (repernm != null && !repernm.isBlank()) {
            sql += " AND rj.pernm LIKE :repernm";
            param.addValue("repernm", "%" + repernm.trim() + "%");
        }

        sql += " ORDER BY e.recedate DESC, e.recenum DESC";
        return this.sqlRunner.getRows(sql, param);
    }

    // ── 고장처리결과 목록 조회 (TB_E411) ─────────────────────
    public List<Map<String, Object>> getCompList(
            String fromDate, String toDate, String actnm, String resultck, String spjangcd) {

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
                    e.recetime,
                    e.arrivdate,
                    e.arrivtime,
                    e.actcd,
                    e.actnm,
                    e.equpcd,
                    e.equpnm,
                    e.gregicd,
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
                    e.perid,
                    e.actperid,
                    j.pernm,
                    ap.pernm    AS actpernm,
                    a.perid     AS recperid,
                    rp.pernm    AS recpernm,
                    a.reperid   AS delperid,
                    dp.pernm    AS delpernm,
                    jc.divinm,
                    a.resultck
                FROM TB_E411 e
                LEFT JOIN TB_JA001 j   ON j.perid    = 'p' + e.perid
                                      AND j.spjangcd  = e.spjangcd
                LEFT JOIN TB_JA001 ap  ON ap.perid    = 'p' + e.actperid
                                      AND ap.spjangcd = e.spjangcd
                LEFT JOIN TB_JC002 jc  ON j.divicd   = jc.divicd
                LEFT JOIN TB_E019 f19  ON f19.faccd   = e.faccd
                LEFT JOIN TB_E014 eg   ON eg.regicd   = e.regicd
                                      AND eg.gregicd  = e.gregicd
                LEFT JOIN TB_E011 em   ON em.remocd   = e.remocd
                LEFT JOIN TB_E012 es   ON es.resucd   = e.resucd
                LEFT JOIN TB_E015 er   ON er.resultcd = e.resultcd
                LEFT JOIN TB_E401  a   ON a.recedate  = e.recedate
                                      AND a.recenum   = e.recenum
                                      AND a.spjangcd  = e.spjangcd
                                      AND a.actcd     = e.actcd
                LEFT JOIN TB_JA001 rp  ON rp.perid    = 'p' + a.perid
                                      AND rp.spjangcd = e.spjangcd
                LEFT JOIN TB_JA001 dp  ON dp.perid    = 'p' + a.reperid
                                      AND dp.spjangcd = e.spjangcd
                WHERE e.spjangcd = :spjangcd
                  AND e.recedate BETWEEN :fromDate AND :toDate
                """;

        if (actnm != null && !actnm.isBlank()) {
            sql += " AND e.actnm LIKE :actnm";
            param.addValue("actnm", "%" + actnm.trim() + "%");
        }

        if (resultck != null && !resultck.isBlank()) {
            if (resultck.equals("1")) {
                sql += " AND a.resultck = '1'";
            } else if (resultck.equals("null")) {
                sql += " AND (a.resultck IS NULL OR a.resultck <> '1')";
            }
        }

        sql += " ORDER BY e.recedate DESC, e.recenum DESC";
        return this.sqlRunner.getRows(sql, param);
    }

    // ── 현장 목록 조회 (TB_E601) ─────────────────────────────
    public List<Map<String, Object>> getSiteList(String spjangcd, String keyword) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT actcd, actnm
                FROM TB_E601
                WHERE spjangcd = :spjangcd
                """;

        if (keyword != null && !keyword.trim().isEmpty()) {
            sql += " AND actnm LIKE :keyword";
            param.addValue("keyword", "%" + keyword.trim() + "%");
        }

        sql += " ORDER BY actnm ASC";
        return this.sqlRunner.getRows(sql, param);
    }

    // ── 호기 목록 조회 (TB_E611) ─────────────────────────────
    public List<Map<String, Object>> getEqupList(String spjangcd, String actcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("actcd",    actcd);

        String sql = """
                SELECT equpcd, equpnm, actcd
                FROM TB_E611 WITH(NOLOCK)
                WHERE spjangcd = :spjangcd
                  AND actcd    = :actcd
                ORDER BY equpcd ASC
                """;

        return this.sqlRunner.getRows(sql, param);
    }

    // ── 고장처리결과 등록 (TB_E411 INSERT) ───────────────────
    public void saveComp(
            String custcd,
            String spjangcd,
            String compdate,
            String comptime,
            String recedate,
            String recenum,
            String recetime,
            String arrivdate,
            String arrivtime,
            String actcd,
            String actnm,
            String equpcd,
            String equpnm,
            String contremark,
            String gregicd,
            String remoremark,
            String regicd,
            String resuremark,
            String remocd,
            String resultcd,
            String faccd,
            String customer,
            String resucd,
            String remark,
            String actperid,
            String mgrperid,
            String perid,
            String filesvnm,
            String filepath) {

        String compnum = getNextCompnum(spjangcd, compdate);

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
        param.addValue("remoremark", remoremark);
        param.addValue("regicd",     regicd);
        param.addValue("resuremark", resuremark);
        param.addValue("remocd",     remocd);
        param.addValue("resultcd",   resultcd);
        param.addValue("faccd",      faccd);
        param.addValue("customer",   customer);
        param.addValue("resucd",     resucd);
        param.addValue("remark",     remark);
        // ★ 컬럼 의미 (PB 규격)
        //    perid    = 처리자  : 화면에서 선택한 처리자(actperid 파라미터), 'p' 제거
        //    actperid = 담당자  : 현장 마스터(TB_E601)의 점검자(정), 화면에서 변경 가능(mgrperid)
        //    inperid  = 등록자  : 로그인 사용자
        String processorRaw = (actperid != null) ? actperid.trim().replaceFirst("^p", "") : "";

        param.addValue("perid",      processorRaw);
        // ★ 담당자(actperid) = 현장 마스터(TB_E601)의 점검자(정)
        //   화면에서 선택/수정한 값이 오면 그 값을 우선 사용 (PB 와 동일하게 수동 변경 허용)
        param.addValue("actperid",   (mgrperid != null && !mgrperid.isBlank())
                ? mgrperid.trim().replaceFirst("^p", "")
                : getActManagerId(spjangcd, actcd));
        param.addValue("result",     "1");
        param.addValue("inperid",    perid);
        param.addValue("indate",     compdate);
        param.addValue("filesvnm",   filesvnm != null ? filesvnm : "");
        param.addValue("filepath",   filepath  != null ? filepath  : "");

        // ── PB 규격 부가 컬럼 (의미 확정된 것만) ──────────────
        //   store / gubun / addgubun / trouble / troublesu 는 의미 미상 + DB별 값이
        //   다를 수 있어 우선 미입력(NULL)으로 둔다
        param.addValue("divicd",     getPeridDivicd(spjangcd, processorRaw));
        param.addValue("cltcd",      getActCltcd(spjangcd, actcd));
        param.addValue("resutime",   calcMinutes(recedate, recetime, arrivdate, arrivtime));
        param.addValue("resulttime", calcMinutes(arrivdate, arrivtime, compdate, comptime));

        namedParameterJdbcTemplate.update("""
                INSERT INTO TB_E411
                    (custcd, spjangcd, compdate, compnum, comptime,
                     recedate, recenum, recetime,
                     arrivdate, arrivtime,
                     actcd, actnm, equpcd, equpnm,
                     contremark, gregicd,
                     remoremark, regicd,
                     resuremark, remocd,
                     resultcd, faccd,
                     customer, resucd,
                     remark, actperid, result,
                     perid, inperid, indate,
                     filesvnm, filepath,
                     divicd, cltcd, resutime, resulttime)
                VALUES
                    (:custcd, :spjangcd, :compdate, :compnum, :comptime,
                     :recedate, :recenum, :recetime,
                     :arrivdate, :arrivtime,
                     :actcd, :actnm, :equpcd, :equpnm,
                     :contremark, :gregicd,
                     :remoremark, :regicd,
                     :resuremark, :remocd,
                     :resultcd, :faccd,
                     :customer, :resucd,
                     :remark, :actperid, :result,
                     :perid, :inperid, :indate,
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
        headParam.addValue("perid",    processorRaw);   // ★ 업무일지 귀속 = 처리자 ('p' 제거)

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
        detailParam.addValue("perid",    processorRaw);   // ★ 업무일지 귀속 = 처리자 ('p' 제거)
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

    // ── 고장부위 조회 (TB_E013) ──────────────────────────────
    public List<Map<String, Object>> getGreginmList(String keyword) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("keyword", "%" + (keyword != null ? keyword : "") + "%");
        return this.sqlRunner.getRows("""
                SELECT gregicd, greginm FROM TB_E013
                WHERE ISNULL(greginm,'') LIKE :keyword AND useyn = '1'
                ORDER BY greginm
                """, param);
    }

    // ── 고장부위상세 조회 (TB_E014) ──────────────────────────
    public List<Map<String, Object>> getReginmList(String gregicd, String keyword) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("gregicd", gregicd);
        param.addValue("keyword", "%" + (keyword != null ? keyword : "") + "%");
        return this.sqlRunner.getRows("""
                SELECT a.regicd, a.reginm, a.gregicd FROM TB_E014 a
                LEFT JOIN TB_E013 b ON a.gregicd = b.gregicd
                WHERE ISNULL(b.gregicd,'') = :gregicd
                  AND ISNULL(a.reginm,'') LIKE :keyword
                ORDER BY a.reginm
                """, param);
    }

    // ── 고장요인 조회 (TB_E011) ──────────────────────────────
    public List<Map<String, Object>> getRemonmList(String keyword) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("keyword", "%" + (keyword != null ? keyword : "") + "%");
        return this.sqlRunner.getRows("""
                SELECT remocd, remonm FROM TB_E011
                WHERE ISNULL(remonm,'') LIKE :keyword AND useyn = '1'
                ORDER BY remonm
                """, param);
    }

    // ── 고장원인 조회 (TB_E019) ──────────────────────────────
    public List<Map<String, Object>> getFacnmList(String keyword) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("keyword", "%" + (keyword != null ? keyword : "") + "%");
        return this.sqlRunner.getRows("""
                SELECT faccd, facnm FROM TB_E019
                WHERE ISNULL(facnm,'') LIKE :keyword AND useyn = '1'
                ORDER BY faccd
                """, param);
    }

    // ── 처리내용 조회 (TB_E012) ──────────────────────────────
    public List<Map<String, Object>> getResunmList(String keyword) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("keyword", "%" + (keyword != null ? keyword : "") + "%");
        return this.sqlRunner.getRows("""
                SELECT resucd, resunm FROM TB_E012
                WHERE ISNULL(resunm,'') LIKE :keyword AND useyn = '1'
                ORDER BY resunm
                """, param);
    }

    // ── 처리결과 조회 (TB_E015) ──────────────────────────────
    public List<Map<String, Object>> getResultnmList(String keyword) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("keyword", "%" + (keyword != null ? keyword : "") + "%");
        return this.sqlRunner.getRows("""
                SELECT resultcd, resultnm FROM TB_E015
                WHERE ISNULL(resultnm,'') LIKE :keyword AND useyn = '1'
                ORDER BY resultcd
                """, param);
    }

    // ── 고장처리결과 수정 (TB_E411 UPDATE) ───────────────────
    public void updateComp(
            String spjangcd, String compdate, String compnum, String comptime,
            String recedate, String recetime, String arrivdate, String arrivtime,
            String actcd, String actnm, String equpcd, String equpnm,
            String contremark, String gregicd, String remoremark, String regicd,
            String resuremark, String remocd, String resultcd, String faccd,
            String customer, String resucd, String remark, String actperid, String mgrperid) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd",   spjangcd);
        param.addValue("compdate",   compdate);
        param.addValue("compnum",    compnum);
        param.addValue("comptime",   comptime);
        param.addValue("recedate",   recedate);
        param.addValue("recetime",   recetime);
        param.addValue("arrivdate",  arrivdate);
        param.addValue("arrivtime",  arrivtime);
        param.addValue("actcd",      actcd);
        param.addValue("actnm",      actnm);
        param.addValue("equpcd",     equpcd);
        param.addValue("equpnm",     equpnm);
        param.addValue("contremark", contremark);
        param.addValue("gregicd",    gregicd);
        param.addValue("remoremark", remoremark);
        param.addValue("regicd",     regicd);
        param.addValue("resuremark", resuremark);
        param.addValue("remocd",     remocd);
        param.addValue("resultcd",   resultcd);
        param.addValue("faccd",      faccd);
        param.addValue("customer",   customer);
        param.addValue("resucd",     resucd);
        param.addValue("remark",     remark);
        // ★ 처리자(perid) 갱신
        String processorRaw = (actperid != null) ? actperid.trim().replaceFirst("^p", "") : "";
        param.addValue("perid",      processorRaw);
        // ★ 담당자(actperid) - 화면에서 변경 가능 (PB 와 동일)
        param.addValue("actperid",   (mgrperid != null && !mgrperid.isBlank())
                ? mgrperid.trim().replaceFirst("^p", "")
                : getActManagerId(spjangcd, actcd));
        // ── PB 규격 부가 컬럼 (일시/처리자 변경 시 재계산) ──
        param.addValue("divicd",     getPeridDivicd(spjangcd, processorRaw));
        param.addValue("cltcd",      getActCltcd(spjangcd, actcd));
        param.addValue("resutime",   calcMinutes(recedate, recetime, arrivdate, arrivtime));
        param.addValue("resulttime", calcMinutes(arrivdate, arrivtime, compdate, comptime));

        namedParameterJdbcTemplate.update("""
                UPDATE TB_E411 SET
                    comptime   = :comptime,
                    recedate   = :recedate,
                    recetime   = :recetime,
                    arrivdate  = :arrivdate,
                    arrivtime  = :arrivtime,
                    actcd      = :actcd,
                    actnm      = :actnm,
                    equpcd     = :equpcd,
                    equpnm     = :equpnm,
                    contremark = :contremark,
                    gregicd    = :gregicd,
                    remoremark = :remoremark,
                    regicd     = :regicd,
                    resuremark = :resuremark,
                    remocd     = :remocd,
                    resultcd   = :resultcd,
                    faccd      = :faccd,
                    customer   = :customer,
                    resucd     = :resucd,
                    remark     = :remark,
                    perid      = :perid,
                    actperid   = :actperid,
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

    // ── 두 일시(yyyyMMdd + HHmm) 사이의 분 차이 ────────────────
    //   PB 규격: resutime = 접수→도착(대응시간), resulttime = 도착→완료(처리시간)
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

    // ── 현장 담당자 조회 (TB_E601.perid = 점검자(정)) ──────────
    //   PB 규격: 고장처리의 담당자(actperid)는 현장 마스터의 점검자(정)에서 유래.
    //   2025년 실데이터 검증: TB_E601.perid 일치 8,140 / 접수 perid 일치 6,119
    //   → 자동 바인드 후 화면에서 수동 변경 가능 (PB와 동일)
    public String getActManagerId(String spjangcd, String actcd) {
        if (actcd == null || actcd.isBlank()) return "";

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("actcd",    actcd);

        List<Map<String, Object>> rows = this.sqlRunner.getRows("""
                SELECT TOP 1 perid
                FROM TB_E601
                WHERE spjangcd = :spjangcd
                  AND actcd    = :actcd
                """, param);

        if (rows == null || rows.isEmpty() || rows.get(0).get("perid") == null) return "";
        return String.valueOf(rows.get(0).get("perid")).trim().replaceFirst("^p", "");
    }

    // ── 사번으로 사원명 조회 ───────────────────────────────────
    public String getPernmByPerid(String spjangcd, String peridRaw) {
        if (peridRaw == null || peridRaw.isBlank()) return "";

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("perid",    "p" + peridRaw.trim().replaceFirst("^p", ""));

        List<Map<String, Object>> rows = this.sqlRunner.getRows("""
                SELECT TOP 1 pernm FROM TB_JA001
                WHERE spjangcd = :spjangcd AND perid = :perid
                """, param);

        if (rows == null || rows.isEmpty() || rows.get(0).get("pernm") == null) return "";
        return String.valueOf(rows.get(0).get("pernm"));
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

    // ── compnum 채번 (001 ~ 999) ──────────────────────────────
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
