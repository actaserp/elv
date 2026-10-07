package mes.app.AS.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class WebRequestService {

    @Autowired
    SqlRunner sqlRunner; // 사업체DB (@Primary = tenantSqlRunner)

    @Autowired
    NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    // ── 카운트 (금일수신/고장접수/콜백/당일처리) ─────────────
    public Map<String, Object> getCount(String spjangcd) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("today", java.time.LocalDate.now().toString().replace("-", ""));

        String sql = """
                SELECT
                    COUNT(*)                                                                    AS callcount,
                    SUM(CASE WHEN resultck IS NULL OR resultck <> '1' THEN 1 ELSE 0 END)        AS rececnt,
                    SUM(CASE WHEN resultck = '1'   THEN 1 ELSE 0 END)                           AS compcnt
                FROM TB_E401
                WHERE spjangcd = :spjangcd
                  AND recedate = :today
                """;

        Map<String, Object> row = sqlRunner.getRow(sql, param);
        row = (row == null) ? new java.util.HashMap<>() : new java.util.HashMap<>(row);

        // 콜백은 TB_E401 이 아니라 통화메모(TB_CALLMAIN)에 있다.
        // 한 쿼리로 합치면 TB_CALLMAIN 이 없는 사업체에서 나머지 세 건수까지 같이 죽으므로 따로 센다.
        row.put("callback", getCallbackCount());
        return row;
    }

    /**
     * 콜백 대기 건수.
     * aprjems(구 웹앱) GetCallBackList 와 같은 기준 — 깃발이 서 있고 예약시간이 있는 건이 '대기'다.
     * 날짜 조건이 없어서 처리 안 한 콜백은 계속 쌓인다. 그래야 목록 건수와 배지가 일치한다.
     * TB_CALLMAIN 이 없는 사업체도 있을 수 있어, 조회 실패는 0 으로 넘긴다.
     */
    private int getCallbackCount() {
        Map<String, Object> row = sqlRunner.getRow("""
                SELECT COUNT(*) AS cnt
                  FROM TB_CALLMAIN WITH(NOLOCK)
                 WHERE ISNULL(callbackflag,'') LIKE '%1%'
                   AND LEN(ISNULL(callbackflag,'')) > 0
                   AND LEN(ISNULL(callbacktime,'')) > 0
                """, new MapSqlParameterSource());

        if (row == null || row.get("cnt") == null) return 0;
        return ((Number) row.get("cnt")).intValue();
    }

    // ── 고장접수현황 카드 리스트 (TB_E401) ───────────────────
    public List<Map<String, Object>> getList(
            String spjangcd, String fromDate, String toDate, String actnm) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("fromDate", fromDate);
        param.addValue("toDate",   toDate);

        String sql = """
                SELECT
                    e.recedate,
                    e.recenum,
                    e.recetime,
                    e.hitchdate,
                    e.hitchhour,
                    e.actcd,
                    e.actnm,
                    e.equpcd,
                    e.equpnm,
                    e.reperid,
                    j2.pernm     AS repernm,
                    e.perid,
                    j.pernm,
                    e.contcd,
                    c.contnm,
                    e.contents,
                    e.remark,
                    e.resultck   AS status
                FROM TB_E401 e
                LEFT JOIN TB_JA001 j  ON j.perid    = 'p' + e.perid
                                     AND j.spjangcd  = e.spjangcd
                LEFT JOIN TB_JA001 j2 ON j2.perid   = 'p' + e.reperid
                                     AND j2.spjangcd = e.spjangcd
                LEFT JOIN TB_E010  c  ON c.contcd   = e.contcd
                WHERE e.spjangcd = :spjangcd
                  AND e.recedate BETWEEN :fromDate AND :toDate
                """;

        if (actnm != null && !actnm.isBlank()) {
            sql += " AND e.actnm LIKE :actnm";
            param.addValue("actnm", "%" + actnm.trim() + "%");
        }

        sql += " ORDER BY e.recedate DESC, e.recenum DESC";

        return sqlRunner.getRows(sql, param);
    }

    // ── 고장접수 저장 (TB_E401 INSERT / UPDATE) ───────────────
    /** @return 저장된 접수번호 (신규면 새로 채번한 번호) */
    public String save(String spjangcd, String custcd,
                     String recedate, String recenum, String recetime,
                     String hitchdate, String hitchhour,
                     String actcd, String actnm,
                     String equpcd, String equpnm,
                     String reperid, String perid,
                     String contcd, String contents, String remark) {

        String today = java.time.LocalDate.now().toString().replace("-", "");

        if (recenum == null || recenum.isBlank()) {
            // 신규 INSERT - recenum 채번
            recenum = getNextRecenum(spjangcd, recedate);

            // ★ perid/reperid 는 'p' 없는 사번으로 통일 (PB / 모바일 규칙)
            String peridRaw   = (perid   != null) ? perid.replaceFirst("^p", "")   : "";
            String reperidRaw = (reperid != null) ? reperid.replaceFirst("^p", "") : "";

            // ★ 통보자(perid)의 부서코드 조회 → divicd 저장 (PB 는 통보자 부서를 채움)
            String divicd = getPeridDivicd(spjangcd, peridRaw);

            MapSqlParameterSource param = new MapSqlParameterSource();
            param.addValue("custcd",    custcd);
            param.addValue("spjangcd",  spjangcd);
            param.addValue("recedate",  recedate);
            param.addValue("recenum",   recenum);
            param.addValue("recetime",  recetime);
            param.addValue("hitchdate", hitchdate);
            param.addValue("hitchhour", hitchhour);
            param.addValue("actcd",     actcd);
            param.addValue("actnm",     actnm);
            param.addValue("equpcd",    equpcd);
            param.addValue("equpnm",    equpnm);
            param.addValue("reperid",   reperidRaw);
            param.addValue("perid",     peridRaw);
            param.addValue("contcd",    contcd);
            param.addValue("contents",  contents);
            param.addValue("remark",    remark);
            param.addValue("divicd",    divicd);
            param.addValue("inperid",   peridRaw);
            param.addValue("indate",    today);

            // ── PB 규격 부가 컬럼 ────────────────────────────
            param.addValue("cltcd",     getActCltcd(spjangcd, actcd));           // 현장 거래처
            param.addValue("resultck",  "0");                                     // PB는 접수 시 '0'
            param.addValue("datetime",  toLocalDateTime(recedate,  recetime));    // 접수일시
            param.addValue("datetime2", toLocalDateTime(hitchdate, hitchhour));   // 고장일시

            String sql = """
                    INSERT INTO TB_E401
                        (custcd, spjangcd, recedate, recenum, recetime,
                         hitchdate, hitchhour,
                         actcd, actnm, equpcd, equpnm,
                         reperid, perid, divicd,
                         contcd, contents, remark,
                         inperid, indate,
                         cltcd, resultck, [datetime], [datetime2])
                    VALUES
                        (:custcd, :spjangcd, :recedate, :recenum, :recetime,
                         :hitchdate, :hitchhour,
                         :actcd, :actnm, :equpcd, :equpnm,
                         :reperid, :perid, :divicd,
                         :contcd, :contents, :remark,
                         :inperid, :indate,
                         :cltcd, :resultck, :datetime, :datetime2)
                    """;

            namedParameterJdbcTemplate.update(sql, param);

        } else {
            // 수정 UPDATE
            // ★ perid/reperid 'p' 제거 + 통보자 부서(divicd) 재조회
            String peridRaw   = (perid   != null) ? perid.replaceFirst("^p", "")   : "";
            String reperidRaw = (reperid != null) ? reperid.replaceFirst("^p", "") : "";
            String divicd     = getPeridDivicd(spjangcd, peridRaw);

            MapSqlParameterSource param = new MapSqlParameterSource();
            param.addValue("spjangcd",  spjangcd);
            param.addValue("recedate",  recedate);
            param.addValue("recenum",   recenum);
            param.addValue("recetime",  recetime);
            param.addValue("hitchdate", hitchdate);
            param.addValue("hitchhour", hitchhour);
            param.addValue("actcd",     actcd);
            param.addValue("actnm",     actnm);
            param.addValue("equpcd",    equpcd);
            param.addValue("equpnm",    equpnm);
            param.addValue("reperid",   reperidRaw);
            param.addValue("perid",     peridRaw);
            param.addValue("divicd",    divicd);
            param.addValue("contcd",    contcd);
            param.addValue("contents",  contents);
            param.addValue("remark",    remark);
            param.addValue("cltcd",     getActCltcd(spjangcd, actcd));
            param.addValue("datetime",  toLocalDateTime(recedate,  recetime));
            param.addValue("datetime2", toLocalDateTime(hitchdate, hitchhour));

            String sql = """
                    UPDATE TB_E401 SET
                        recetime  = :recetime,
                        hitchdate = :hitchdate,
                        hitchhour = :hitchhour,
                        actcd     = :actcd,
                        actnm     = :actnm,
                        equpcd    = :equpcd,
                        equpnm    = :equpnm,
                        reperid   = :reperid,
                        perid     = :perid,
                        divicd    = :divicd,
                        contcd    = :contcd,
                        contents  = :contents,
                        remark    = :remark,
                        cltcd     = :cltcd,
                        [datetime]  = :datetime,
                        [datetime2] = :datetime2
                    WHERE spjangcd = :spjangcd
                      AND recedate = :recedate
                      AND recenum  = :recenum
                    """;

            namedParameterJdbcTemplate.update(sql, param);
        }
        return recenum;
    }

    // ── 고장접수 삭제 (TB_E401 DELETE) ───────────────────────
    public void delete(String spjangcd, String recedate, String recenum) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("recedate", recedate);
        param.addValue("recenum",  recenum);

        namedParameterJdbcTemplate.update("""
                DELETE FROM TB_E401
                WHERE spjangcd = :spjangcd
                  AND recedate = :recedate
                  AND recenum  = :recenum
                """, param);
    }

    // ── 문자전송내역 조회 (TB_E401_SMS) ─────────────────────
    public List<Map<String, Object>> getSmsHistory(
            String spjangcd, String recedate, String recenum) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("recedate", recedate);
        param.addValue("recenum",  recenum);

        String sql = """
                SELECT
                    s.result,
                    s.recedate,
                    CONVERT(varchar(6), s.receipdate, 108) AS recetime,
                    s.pernm,
                    s.sms_tel,
                    s.flag,
                    s.sms_text
                FROM TB_E401_SMS s
                WHERE s.spjangcd = :spjangcd
                  AND s.recedate = :recedate
                  AND s.recenum  = :recenum
                ORDER BY s.receipdate DESC
                """;

        return sqlRunner.getRows(sql, param);
    }

    // ── 통화메모 목록 조회 (TB_CALLMAIN) ────────────────────
    public List<Map<String, Object>> getMemoList(
            String spjangcd, String srchDate, String callnm) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("srchDate", srchDate);
        param.addValue("today",    java.time.LocalDate.now().toString().replace("-", ""));
        param.addValue("callnm",   callnm != null ? callnm : "%");

        String sql = """
                SELECT
                    seq,
                    calldate,
                    calltime,
                    callnm,
                    callnum,
                    callmemo,
                    callbackflag,
                    callbacktime,
                    callbackmemo,
                    callendmemo
                FROM TB_CALLMAIN
                WHERE calldate BETWEEN :srchDate AND :today
                  AND callnm   LIKE :callnm
                ORDER BY calldate DESC, calltime DESC
                """;

        return sqlRunner.getRows(sql, param);
    }

    // ── 콜백리스트 (TB_CALLMAIN) ─────────────────────────────
    //    aprjems(구 웹앱) GetCallBackList 와 같은 기준. 깃발('1')이 서 있고 예약시간이 있는 건만 대기로 본다.
    //    날짜 조건 없음 — 처리할 때까지 남아 있는 목록이다.
    public List<Map<String, Object>> getCallbackList(String spjangcd, String keyword) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("keyword", (keyword != null && !keyword.isBlank()) ? "%" + keyword + "%" : "%");

        String sql = """
                SELECT seq,
                       calldate,
                       calltime,
                       callbacktime,
                       callnm,
                       callnum,
                       ISNULL(callbackmemo,'') AS callbackmemo,
                       ISNULL(callmemo,'')     AS callmemo
                  FROM TB_CALLMAIN WITH(NOLOCK)
                 WHERE ISNULL(callbackflag,'') LIKE '%1%'
                   AND LEN(ISNULL(callbackflag,'')) > 0
                   AND LEN(ISNULL(callbacktime,'')) > 0
                   AND (ISNULL(callnm,'') LIKE :keyword OR ISNULL(callnum,'') LIKE :keyword)
                 ORDER BY calldate DESC, callbacktime ASC, seq DESC
                """;

        return sqlRunner.getRows(sql, param);
    }

    // ── 콜백 완료 처리 — 깃발만 내린다 (aprjems 의 /wcallbackflag 와 동일) ──
    public int completeCallback(String spjangcd, String seq) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("seq", seq);

        return namedParameterJdbcTemplate.update("""
                UPDATE TB_CALLMAIN
                   SET callbackflag = '0'
                 WHERE seq = :seq
                """, param);
    }

    // ── 통화메모 저장 (TB_CALLMAIN INSERT / UPDATE) ──────────
    public void saveMemo(String spjangcd, String seq,
                         String calldate, String calltime,
                         String callnm, String callnum,
                         String callbackflag, String callbacktime, String callbackmemo,
                         String callmemo, String callendmemo, String pernm) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("seq",          seq);
        param.addValue("calldate",     calldate);
        param.addValue("calltime",     calltime);
        param.addValue("callnm",       callnm);
        param.addValue("callnum",      callnum);
        param.addValue("callbackflag", callbackflag);
        param.addValue("callbacktime", callbacktime);
        param.addValue("callbackmemo", callbackmemo);
        param.addValue("callmemo",     callmemo);
        param.addValue("callendmemo",  callendmemo);
        param.addValue("pernm",        pernm);
        param.addValue("regdate",      java.time.LocalDate.now().toString().replace("-", ""));

        if (seq == null || seq.isBlank()) {
            // 신규 INSERT - seq 채번 (yyyymm + 순번)
            String newSeq = getNextCallSeq(calldate.substring(0, 6));
            param.addValue("seq", newSeq);

            namedParameterJdbcTemplate.update("""
                    INSERT INTO TB_CALLMAIN
                        (seq, calldate, calltime, callnm, callnum,
                         callbackflag, callbacktime, callbackmemo,
                         callmemo, callendmemo, pernm, regdate)
                    VALUES
                        (:seq, :calldate, :calltime, :callnm, :callnum,
                         :callbackflag, :callbacktime, :callbackmemo,
                         :callmemo, :callendmemo, :pernm, :regdate)
                    """, param);
        } else {
            namedParameterJdbcTemplate.update("""
                    UPDATE TB_CALLMAIN SET
                        calldate     = :calldate,
                        calltime     = :calltime,
                        callnm       = :callnm,
                        callnum      = :callnum,
                        callbackflag = :callbackflag,
                        callbacktime = :callbacktime,
                        callbackmemo = :callbackmemo,
                        callmemo     = :callmemo,
                        callendmemo  = :callendmemo
                    WHERE seq = :seq
                    """, param);
        }
    }

    // ── 통화메모 삭제 (TB_CALLMAIN DELETE) ───────────────────
    public void deleteMemo(String spjangcd, String seq) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("seq", seq);

        namedParameterJdbcTemplate.update("""
                DELETE FROM TB_CALLMAIN
                WHERE seq = :seq
                """, param);
    }

    // ── seq 채번 (yyyymm 기준 MAX + 1) ───────────────────────
    private String getNextCallSeq(String yyyymm) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("yyyymm", yyyymm);

        String sql = """
                SELECT ISNULL(MAX(CAST(seq AS BIGINT)), 0) + 1
                FROM TB_CALLMAIN
                WHERE LEFT(seq, 6) = :yyyymm
                """;

        Long next = namedParameterJdbcTemplate.queryForObject(sql, param, Long.class);
        if (next == null) next = Long.parseLong(yyyymm + "0001");
        return String.valueOf(next);
    }

    // ── 팝업: 현장 검색 (TB_E601) ────────────────────────────
    public List<Map<String, Object>> popupActnm(String spjangcd, String actnm) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("actnm", (actnm != null && !actnm.isBlank()) ? "%" + actnm + "%" : "%");

        String sql = """
                SELECT actcd, actnm
                FROM TB_E601
                WHERE spjangcd = :spjangcd
                  AND actnm    LIKE :actnm
                ORDER BY actnm ASC
                """;

        return sqlRunner.getRows(sql, param);
    }

    // ── 팝업: 호기 검색 (TB_E611) ────────────────────────────
    public List<Map<String, Object>> popupEqupnm(String spjangcd, String actcd) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("actcd",    actcd);

        String sql = """
                SELECT equpcd, equpnm
                FROM TB_E611 WITH(NOLOCK)
                WHERE spjangcd = :spjangcd
                  AND actcd    = :actcd
                ORDER BY equpcd ASC
                """;

        return sqlRunner.getRows(sql, param);
    }

    // ── 팝업: 사원 검색 (TB_JA001 - 접수자/통보자 공통) ──────
    public List<Map<String, Object>> popupPernm(String spjangcd, String pernm) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("pernm", (pernm != null && !pernm.isBlank() && !pernm.equals("%"))
                ? "%" + pernm + "%" : "%");

        String sql = """
                SELECT perid, pernm, handphone
                FROM TB_JA001
                WHERE spjangcd = :spjangcd
                  AND rtclafi  = '001'
                  AND pernm    LIKE :pernm
                ORDER BY pernm ASC
                """;

        return sqlRunner.getRows(sql, param);
    }

    // ── 팝업: 고장내용 검색 (TB_E010) ────────────────────────
    public List<Map<String, Object>> popupContnm(String contnm) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("contnm", (contnm != null && !contnm.isBlank() && !contnm.equals("%"))
                ? "%" + contnm + "%" : "%");

        String sql = """
                SELECT contcd, contnm
                FROM TB_E010
                WHERE contnm LIKE :contnm
                ORDER BY contcd ASC
                """;

        return sqlRunner.getRows(sql, param);
    }

    // ── PushID 조회 (TB_JA001) ────────────────────────────────
    // 현재 6개 사업체 DB 에는 TB_JA001.pushid 컬럼이 없다.
    // 컬럼 없이 조회하면 SQL 오류가 나므로 먼저 컬럼 유무를 확인하고, 없으면 조용히 빈 값을 돌려준다.
    // (화면에서는 숨은 값으로만 쓰고 저장에는 들어가지 않는다)
    public String getPushId(String spjangcd, String pernm) {

        Map<String, Object> col = sqlRunner.getRow(
                "SELECT COL_LENGTH('TB_JA001', 'pushid') AS has_col", new MapSqlParameterSource());
        if (col == null || col.get("has_col") == null) return null;

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("pernm",    pernm);

        String sql = """
                SELECT TOP 1 pushid
                FROM TB_JA001
                WHERE spjangcd = :spjangcd
                  AND pernm    = :pernm
                """;

        Map<String, Object> row = sqlRunner.getRow(sql, param);
        return (row != null) ? (String) row.get("pushid") : null;
    }

    // ── 승강기번호 조회 (국가 승강기정보 공공 API) ────────────
    public String getElvInfo(String elvnum) throws Exception {
        String apikey  = "a0b009c35f320b2f60bd2ba0bfdc91cde87089876c80cad72fa563fd5463e3c0";
        String text    = java.net.URLEncoder.encode(elvnum, "UTF-8");
        String apiURL  = "https://apis.data.go.kr/B553664/ElevatorInformationService/getElevatorViewM"
                       + "?serviceKey=" + apikey
                       + "&elevator_no=" + text;

//        log.info("[getElvInfo] 호출 URL: {}", apiURL);

        java.net.URL url = new java.net.URL(apiURL);
        java.net.HttpURLConnection con = (java.net.HttpURLConnection) url.openConnection();
        con.setRequestProperty("Accept", "application/xml");
        con.setRequestMethod("GET");
        con.setConnectTimeout(5000);
        con.setReadTimeout(5000);

        int responseCode = con.getResponseCode();
//        log.info("[getElvInfo] 응답코드: {}", responseCode);

        java.io.BufferedReader br;
        if (responseCode == 200) {
            br = new java.io.BufferedReader(new java.io.InputStreamReader(con.getInputStream(), "UTF-8"));
        } else {
            br = new java.io.BufferedReader(new java.io.InputStreamReader(con.getErrorStream(), "UTF-8"));
        }
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();

//        log.info("[getElvInfo] 응답내용: {}", sb.toString());
        return sb.toString();
    }

    // ── 통보자(perid) 부서코드 조회 (TB_JA001.divicd) ────────
    //   TB_E401.divicd 에 통보자 부서를 저장하기 위함 (PB 규칙)
    private String getPeridDivicd(String spjangcd, String peridRaw) {
        if (peridRaw == null || peridRaw.isBlank()) return null;
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("perid", "p" + peridRaw);   // TB_JA001.perid = 'p'+사번
        String sql = """
                SELECT TOP 1 divicd
                FROM TB_JA001
                WHERE spjangcd = :spjangcd
                  AND perid    = :perid
                """;
        try {
            Map<String, Object> row = sqlRunner.getRow(sql, param);
            return (row != null) ? (String) row.get("divicd") : null;
        } catch (Exception e) {
            log.warn("getPeridDivicd 조회 실패 perid={}: {}", peridRaw, e.getMessage());
            return null;
        }
    }

    // ── 현장 거래처코드 조회 (TB_E601.cltcd) ─────────────────
    private String getActCltcd(String spjangcd, String actcd) {
        if (actcd == null || actcd.isBlank()) return null;
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("actcd",    actcd);
        try {
            Map<String, Object> row = sqlRunner.getRow("""
                    SELECT TOP 1 cltcd
                    FROM TB_E601
                    WHERE spjangcd = :spjangcd
                      AND actcd    = :actcd
                    """, param);
            return (row != null) ? (String) row.get("cltcd") : null;
        } catch (Exception e) {
            log.warn("getActCltcd 조회 실패 actcd={}: {}", actcd, e.getMessage());
            return null;
        }
    }

    // ── yyyyMMdd + HHmm → LocalDateTime ─────────────────────
    private java.time.LocalDateTime toLocalDateTime(String date, String time) {
        try {
            if (date == null || date.isBlank()) return null;
            String t = (time != null && time.length() >= 4) ? time.substring(0, 4) : "0000";
            return java.time.LocalDateTime.of(
                    Integer.parseInt(date.substring(0, 4)),
                    Integer.parseInt(date.substring(4, 6)),
                    Integer.parseInt(date.substring(6, 8)),
                    Integer.parseInt(t.substring(0, 2)),
                    Integer.parseInt(t.substring(2, 4)));
        } catch (Exception e) {
            return null;
        }
    }

    // ── recenum 채번 ──────────────────────────────────────────
    private String getNextRecenum(String spjangcd, String recedate) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("recedate", recedate);

        String sql = """
                SELECT ISNULL(MAX(CAST(recenum AS INT)), 0) + 1
                FROM TB_E401
                WHERE spjangcd = :spjangcd
                  AND recedate = :recedate
                """;

        Integer next = namedParameterJdbcTemplate.queryForObject(sql, param, Integer.class);
        if (next == null) next = 1;
        return String.format("%03d", next);
    }

    // ── 발신번호로 고객 찾기 (인터넷전화 수신 시 통화메모 자동입력용) ──
    //
    // 번호 형식이 소스마다 제각각이라(‘0100-6232-1692’ · ‘01006459991’ · ‘1877-9433’)
    // 양쪽 모두 숫자만 남겨서 비교한다. SQL Server 에 정규식이 없어 REPLACE 를 겹쳐 쓴다.
    //
    // 우선순위는 경기 실데이터 기준으로 정했다.
    //   1) 현장(TB_E601)     tel 768/911 · hp 285 — actcd 까지 확정돼 고장접수로 바로 이어진다
    //   2) 거래처(TB_XCLIENT) telnum 636/1178      — cltcd 확정
    //   3) 과거 통화이력(TB_CALLMAIN)              — 고유번호 9,223개 중 이름 있는 건 1,640개.
    //      수기 입력이라 정확도가 낮아 앞의 둘이 못 찾을 때만 쓴다.
    private static final String DIGITS = """
            REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(ISNULL(%s,''),'-',''),' ',''),'(',''),')',''),'.','')
            """;

    /// 전화가 울리는 동안 답이 와야 한다. 다섯 군데를 따로 물으면 왕복이 다섯 번이라
    /// 5~8초가 걸렸고, 그 사이 화면에는 번호만 떠서 비상통화인지 분간이 안 됐다.
    /// 그래서 우선순위를 붙여 한 번에 묻는다.
    ///
    /// 번호 비교는 두 가지를 같이 건다.
    ///   - IN (:cands) : '031-967-3884' 처럼 저장된 그대로. 색인을 탈 수 있다
    ///   - DIGITS      : 괄호·공백 등 별난 표기까지 잡는 그물. 색인은 못 탄다
    public Map<String, Object> findCallerInfo(String spjangcd, String callnum) {
        String digits = callnum == null ? "" : callnum.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return null;

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("num",   digits);
        param.addValue("cands", telCandidates(digits));

        long t0 = System.currentTimeMillis();

        // 전화번호부(TB_E601CALL)가 없는 사업체가 있을 수 있다. 그러면 질의 전체가
        // 실패하므로, 그 표를 뺀 질의로 한 번 더 묻는다.
        // getRows 는 오류면 null, 못 찾았으면 빈 목록이라 둘을 구분할 수 있다.
        List<Map<String, Object>> rows = sqlRunner.getRows(callerSql(true), param);
        if (rows == null) {
            log.warn("[CTI] 발신번호 조회 1차 실패 — 전화번호부(TB_E601CALL)를 빼고 다시 시도합니다.");
            rows = sqlRunner.getRows(callerSql(false), param);
            if (rows == null)
                log.error("[CTI] 발신번호 조회가 두 번 다 실패했습니다. 질의가 깨졌을 수 있습니다. num={}", digits);
        }

        long ms = System.currentTimeMillis() - t0;
        if (ms > 1000) log.warn("[CTI] 발신번호 조회가 {}ms 걸렸습니다. num={}", ms, digits);

        return (rows == null || rows.isEmpty()) ? null : rows.get(0);
    }

    /// '0319673884' → ['0319673884', '031-967-3884'] 처럼 저장돼 있을 법한 표기를 만든다.
    /// 예전 CTI(KTCID_SERCH)도 `in ('010-1234-5678','01012345678')` 로 둘 다 넣었다.
    private static List<String> telCandidates(String d) {
        List<String> out = new java.util.ArrayList<>();
        out.add(d);

        if (d.startsWith("02") && d.length() >= 9)
            out.add(d.substring(0, 2) + "-" + d.substring(2, d.length() - 4) + "-" + d.substring(d.length() - 4));
        else if (d.length() == 10 || d.length() == 11)
            out.add(d.substring(0, 3) + "-" + d.substring(3, d.length() - 4) + "-" + d.substring(d.length() - 4));
        else if (d.length() == 8)
            out.add(d.substring(0, 4) + "-" + d.substring(4));

        return out;
    }

    /// 다섯 군데를 우선순위(pri) 붙여 한 질의로 묶는다.
    /// withPhonebook=false 면 전화번호부만 빼고 나머지로 묶는다.
    private static String callerSql(boolean withPhonebook) {
        String sql = "SELECT TOP 1 source, actcd, actnm, callnm, equpnm, cltcd, cltnm, tel FROM (\n";
        sql += withPhonebook ? (PHONEBOOK + " UNION ALL\n") : "";
        sql += EMERGENCY + " UNION ALL\n" + SITE + " UNION ALL\n" + CLIENT + " UNION ALL\n" + CALLLOG;
        sql += "\n) t ORDER BY t.pri";
        return sql;
    }

    // ── 조회처 다섯 군데 ─────────────────────────────────────
    //    컬럼 구성(pri, source, actcd, actnm, callnm, equpnm, cltcd, cltnm, tel)은
    //    UNION 으로 묶이므로 어느 하나도 바꾸면 안 된다.

    /// 1) 전화번호부. 사람이 직접 등록한 표라 가장 정확해서 1순위다.
    ///    regflag 0현장 1거래처 2직원 3일반 4비상통화. 표시명은 actmail 이다(이름으로 쓰인다).
    ///    머리(a)와 몸통(b) 둘 다 tel 을 들고 있어 양쪽을 본다. 원본(KTCID_SERCH)은
    ///    actcd 로만 묶었는데 그러면 같은 현장의 엉뚱한 사람 이름이 붙는다 — seq 까지 맞춘다.
    private static final String PHONEBOOK = ("""
            SELECT 1 AS pri,
                   CASE ISNULL(a.regflag,'')
                        WHEN '0' THEN '현장'   WHEN '1' THEN '거래처'
                        WHEN '2' THEN '직원'   WHEN '4' THEN '비상통화'
                        ELSE '전화번호부' END AS source,
                   a.actcd,
                   ISNULL(e.actnm,'') AS actnm,
                   COALESCE(NULLIF(a.actmail,''), e.actnm, '') AS callnm,
                   '' AS equpnm,
                   '' AS cltcd, '' AS cltnm,
                   COALESCE(NULLIF(b.tel,''), a.tel, '') AS tel
              FROM TB_E601CALL a WITH(NOLOCK)
              JOIN TB_E601CALL_01 b WITH(NOLOCK)
                   ON b.spjangcd = a.spjangcd AND b.actcd = a.actcd AND b.seq = a.seq
              LEFT JOIN TB_E601 e WITH(NOLOCK)
                   ON e.spjangcd = a.spjangcd AND e.actcd = a.actcd
             WHERE a.spjangcd = :spjangcd
               AND (b.tel IN (:cands) OR a.tel IN (:cands)
                    OR __BTEL__ = :num OR __ATEL__ = :num)
            """).replace("__BTEL__", String.format(DIGITS, "b.tel").trim())
                .replace("__ATEL__", String.format(DIGITS, "a.tel").trim());

    /// 2) 승강기 비상통화. 안에 갇힌 사람이 거는 전화라 현장보다 먼저 본다.
    ///    emtelnum 은 호기마다가 아니라 현장에 하나씩 걸려 있다 — 한 현장의 호기
    ///    수십 개가 같은 번호를 쓰므로, 아무 호기나 집어 '201동' 이라 하면 틀린 말이 된다.
    ///    호기가 하나일 때만 호기를 밝힌다.
    private static final String EMERGENCY = ("""
            SELECT 2 AS pri, '비상통화' AS source, g.actcd,
                   ISNULL(e.actnm,'') AS actnm,
                   ISNULL(e.actnm,'') +
                     CASE WHEN g.cnt = 1 THEN '(' + g.equpnm + ' 비상통화)'
                          ELSE '(비상통화)' END AS callnm,
                   CASE WHEN g.cnt = 1 THEN g.equpnm
                        ELSE CAST(g.cnt AS varchar(10)) + '개 호기' END AS equpnm,
                   '' AS cltcd, '' AS cltnm, g.tel
              FROM (
                    SELECT m.actcd,
                           COUNT(*)                   AS cnt,
                           MIN(ISNULL(m.equpnm,''))   AS equpnm,
                           MIN(ISNULL(m.emtelnum,'')) AS tel
                      FROM TB_E611 m WITH(NOLOCK)
                     WHERE m.spjangcd = :spjangcd
                       AND (m.emtelnum IN (:cands) OR __EM__ = :num)
                     GROUP BY m.actcd
                   ) g
              LEFT JOIN TB_E601 e WITH(NOLOCK)
                     ON e.spjangcd = :spjangcd AND e.actcd = g.actcd
            """).replace("__EM__", String.format(DIGITS, "m.emtelnum").trim());

    /// 3) 현장. 고객명(callnm)은 현장명(actnm)이 아니라 ancltnm 을 쓴다 —
    ///    actnm 에는 '(실패)22.09년…' 같은 관리용 표기가 섞여 통화메모 고객명으로 부적절하다.
    ///    actnm 도 같이 내려보내 화면이 어느 현장인지 보여줄 수 있게 한다.
    private static final String SITE = ("""
            SELECT 3 AS pri, '현장' AS source, e.actcd, e.actnm,
                   ISNULL(e.ancltnm,'') AS callnm,
                   '' AS equpnm,
                   '' AS cltcd, '' AS cltnm, ISNULL(e.tel,'') AS tel
              FROM TB_E601 e WITH(NOLOCK)
             WHERE e.spjangcd = :spjangcd
               AND (e.tel IN (:cands) OR e.hp IN (:cands)
                    OR __TEL__ = :num OR __HP__ = :num)
            """).replace("__TEL__", String.format(DIGITS, "e.tel").trim())
                .replace("__HP__",  String.format(DIGITS, "e.hp").trim());

    /// 4) 거래처
    private static final String CLIENT = ("""
            SELECT 4 AS pri, '거래처' AS source, '' AS actcd, '' AS actnm,
                   x.cltnm AS callnm,
                   '' AS equpnm, x.cltcd, x.cltnm, ISNULL(x.telnum,'') AS tel
              FROM TB_XCLIENT x WITH(NOLOCK)
             WHERE (x.telnum IN (:cands) OR x.hptelnum IN (:cands) OR x.opertel IN (:cands)
                    OR __T1__ = :num OR __T2__ = :num OR __T3__ = :num)
            """).replace("__T1__", String.format(DIGITS, "x.telnum").trim())
                .replace("__T2__", String.format(DIGITS, "x.hptelnum").trim())
                .replace("__T3__", String.format(DIGITS, "x.opertel").trim());

    /// 5) 과거 통화이력 — 수기 입력이라 정확도가 낮아 마지막에 본다.
    ///    가장 최근 건을 쓰므로 ORDER BY 가 필요한데, UNION 안에는 넣을 수 없어
    ///    파생표로 한 겹 감쌌다.
    private static final String CALLLOG = ("""
            SELECT 5 AS pri, '통화이력' AS source, z.actcd, z.actnm, z.callnm,
                   '' AS equpnm, z.cltcd, z.cltnm, z.tel
              FROM (
                    SELECT TOP 1
                           ISNULL(c.actcd,'') AS actcd, ISNULL(c.actnm,'') AS actnm,
                           c.callnm,
                           ISNULL(c.cltcd,'') AS cltcd, ISNULL(c.cltnm,'') AS cltnm,
                           ISNULL(c.callnum,'') AS tel
                      FROM TB_CALLMAIN c WITH(NOLOCK)
                     WHERE (c.callnum IN (:cands) OR __NUM__ = :num)
                       AND ISNULL(c.callnm,'') <> ''
                     ORDER BY c.calldate DESC, c.calltime DESC
                   ) z
            """).replace("__NUM__", String.format(DIGITS, "c.callnum").trim());


    // ── 현장 수리내역 (전화 수신 카드의 [내역보기]) ─────────────
    //
    // 전화를 받은 사람이 "지난번에 뭐가 고장났었나"를 바로 봐야 해서,
    // 그 현장의 고장접수(TB_E401)와 처리결과(TB_E411)를 한 줄로 합쳐 보여준다.
    //
    // 처리가 안 끝난 건도 보여야 하므로 접수(E401)를 기준으로 두고
    // 처리(E411)를 왼쪽 조인한다. getCompList 는 처리 기준이라 미처리가 빠진다.
    public List<Map<String, Object>> getSiteHistory(String spjangcd, String actcd, int limit) {
        if (actcd == null || actcd.isBlank()) return List.of();

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("actcd",    actcd);
        param.addValue("limit",    limit <= 0 ? 50 : limit);

        String sql = """
                SELECT TOP (:limit)
                       a.recedate,
                       a.recenum,
                       ISNULL(a.recetime,'')   AS recetime,
                       ISNULL(a.equpnm,'')     AS equpnm,
                       ISNULL(ct.contnm,'')    AS contnm,
                       ISNULL(a.contents,'')   AS contents,
                       ISNULL(e.compdate,'')   AS compdate,
                       ISNULL(e.resuremark,'') AS resuremark,
                       ISNULL(ap.pernm,'')     AS comppernm,
                       CASE WHEN ISNULL(a.resultck,'') = '1' THEN '완료' ELSE '처리중' END AS state
                  FROM TB_E401 a WITH(NOLOCK)
                  LEFT JOIN TB_E010 ct ON ct.contcd   = a.contcd
                                      AND ct.spjangcd = a.spjangcd
                  LEFT JOIN TB_E411 e  WITH(NOLOCK)
                         ON e.spjangcd = a.spjangcd
                        AND e.recedate = a.recedate
                        AND e.recenum  = a.recenum
                        AND e.actcd    = a.actcd
                  LEFT JOIN TB_JA001 ap ON ap.perid    = 'p' + e.perid
                                       AND ap.spjangcd = e.spjangcd
                 WHERE a.spjangcd = :spjangcd
                   AND a.actcd    = :actcd
                 ORDER BY a.recedate DESC, a.recenum DESC
                """;
        return this.sqlRunner.getRows(sql, param);
    }
}
