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
                    0                                                                            AS callback,
                    SUM(CASE WHEN resultck = '1'   THEN 1 ELSE 0 END)                           AS compcnt
                FROM TB_E401
                WHERE spjangcd = :spjangcd
                  AND recedate = :today
                """;

        return sqlRunner.getRow(sql, param);
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
    public void save(String spjangcd, String custcd,
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
    public String getPushId(String spjangcd, String pernm) {

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
}
