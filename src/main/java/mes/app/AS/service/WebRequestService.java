package mes.app.AS.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
                    e.resultck   AS status,
                    ISNULL(e.troubledate, '')   AS troubledate,
                    ISNULL(e.troubletime, '')   AS troubletime,
                    ISNULL(e.troublesu,   0)    AS troublesu
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
                     String contcd, String contents, String remark,
                     String troubledate, String troubletime, String troublesu) {

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

            // ── 갇힘사고 (PB 고장접수등록의 '갇힘사고일자 / 시간·사람수') ──
            addTroubleParams(param, troubledate, troubletime, troublesu);

            String sql = """
                    INSERT INTO TB_E401
                        (custcd, spjangcd, recedate, recenum, recetime,
                         hitchdate, hitchhour,
                         actcd, actnm, equpcd, equpnm,
                         reperid, perid, divicd,
                         contcd, contents, remark,
                         inperid, indate,
                         cltcd, resultck, [datetime], [datetime2],
                         troubledate, troubletime, troublesu)
                    VALUES
                        (:custcd, :spjangcd, :recedate, :recenum, :recetime,
                         :hitchdate, :hitchhour,
                         :actcd, :actnm, :equpcd, :equpnm,
                         :reperid, :perid, :divicd,
                         :contcd, :contents, :remark,
                         :inperid, :indate,
                         :cltcd, :resultck, :datetime, :datetime2,
                         :troubledate, :troubletime, :troublesu)
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
            addTroubleParams(param, troubledate, troubletime, troublesu);

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
                        [datetime2] = :datetime2,
                        troubledate = :troubledate,
                        troubletime = :troubletime,
                        troublesu   = :troublesu
                    WHERE spjangcd = :spjangcd
                      AND recedate = :recedate
                      AND recenum  = :recenum
                    """;

            namedParameterJdbcTemplate.update(sql, param);
        }
        return recenum;
    }

    /** 갇힘사고 3개 값 바인딩 — 규칙은 TroubleInput 참고 */
    private void addTroubleParams(org.springframework.jdbc.core.namedparam.MapSqlParameterSource param,
                                  String troubledate, String troubletime, String troublesu) {
        TroubleInput.bind(param, troubledate, troubletime, troublesu);
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

        if (rows == null || rows.isEmpty()) return null;
        return mergeSite(rows);
    }

    /// 1순위 줄을 쓰되, 거기에 현장이 없으면 뒤쪽 줄에서 보충한다.
    ///
    /// 전화번호부의 actcd 는 현장 코드가 아니라서(실측 확인) 전화번호부로 걸리면
    /// 현장이 비는데, 같은 번호가 현장 표에도 있으면 그쪽에서 현장을 얻을 수 있다.
    /// 이미 한 질의로 다 받아 왔으므로 추가 조회가 없다.
    ///
    /// 단, 한 번호에 현장이 여럿인 경우가 있다 — 관리업체 한 곳이 건물 여러 채를
    /// 맡으면 같은 번호가 현장 서넛에 걸린다. 그때는 어느 현장인지 단정할 수 없으므로
    /// 붙이지 않는다. 틀린 현장으로 고장접수가 들어가는 것보다 비는 편이 낫다.
    private static Map<String, Object> mergeSite(List<Map<String, Object>> rows) {
        Map<String, Object> best = rows.get(0);
        String bestPri = text(best.get("pri"));

        // 같은 순위에 이름이 여럿이면 누구인지 모르는 것이다.
        // 경기 실측: 010-6358-4436 한 번호에 전화번호부 등록이 6건(고양일고등학교·
        // 당산한강아파트·광명e편한세상…) 서로 무관한 곳들이다. 아무거나 집어 보여주면
        // 받는 사람이 엉뚱한 고객 이름으로 인사하게 된다. 모르면 모른다고 해야 한다.
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            if (!bestPri.equals(text(r.get("pri")))) continue;
            String nm = text(r.get("callnm"));
            if (!nm.isEmpty()) names.add(nm);
        }
        if (names.size() > 1) {
            // 목록을 띄우는 건 '누구인지 모른다' 는 뜻이라 흔하면 안 된다.
            // 왜 모르는지 알 수 있도록 어느 순위에서 몇 개가 나왔는지 남긴다.
            StringBuilder dbg = new StringBuilder();
            for (Map<String, Object> r : rows)
                dbg.append("\n    pri=").append(text(r.get("pri")))
                   .append(" source=").append(text(r.get("source")))
                   .append(" callnm=").append(text(r.get("callnm")))
                   .append(" actcd=").append(text(r.get("actcd")));
            log.warn("[CTI] 이름이 여럿이라 목록으로 넘깁니다. 1순위={} 이름수={} 전체={}줄{}",
                     bestPri, names.size(), rows.size(), dbg);

            // 고를 수 있게 후보를 다 넘긴다. 순위가 낮은 줄(현장 등)도 함께 넘기는데,
            // 현장이 붙은 후보를 고르면 화면에서 [내역보기] 까지 쓸 수 있기 때문이다.
            List<Map<String, Object>> cands = new java.util.ArrayList<>();
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();

            for (Map<String, Object> r : rows) {
                String nm = text(r.get("callnm"));
                if (nm.isEmpty()) continue;
                // 구분(현장/거래처/…)이 다르면 따로 보여준다. 이름이 같아도 받는 사람에게는
                // 현장으로 등록된 건지 거래처로 등록된 건지가 정보다.
                if (!seen.add(text(r.get("source")) + "|" + nm + "|" + text(r.get("actcd")))) continue;

                Map<String, Object> c = new java.util.LinkedHashMap<>(r);
                c.remove("pri");
                cands.add(c);
            }

            best.put("ambiguous",  true);
            best.put("candidates", cands);
            best.put("callnm", "");
            best.put("actcd",  "");   // 고르기 전에는 현장도 정해지지 않는다
            best.put("actnm",  "");
            best.remove("pri");
            return best;
        }

        if (!text(best.get("actcd")).isEmpty()) { best.remove("pri"); return best; }

        String only = null;
        for (Map<String, Object> r : rows) {
            String actcd = text(r.get("actcd"));
            if (actcd.isEmpty()) continue;
            if (only == null) only = actcd;
            else if (!only.equals(actcd)) { best.remove("pri"); return best; }   // 여러 현장 — 단정하지 않는다
        }
        if (only == null) { best.remove("pri"); return best; }

        best.put("actcd", only);
        for (Map<String, Object> r : rows) {
            if (!only.equals(text(r.get("actcd")))) continue;
            best.put("actnm", text(r.get("actnm")));
            break;
        }
        best.remove("pri");
        return best;
    }

    private static String text(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    /// '0319673884' → ['0319673884', '031-967-3884'] 처럼 저장돼 있을 법한 표기를 만든다.
    /// 예전 CTI(KTCID_SERCH)도 `in ('010-1234-5678','01012345678')` 로 둘 다 넣었다.
    private static List<String> telCandidates(String d) {
        List<String> out = new java.util.ArrayList<>();
        out.add(d);

        // 지역번호 / 가운데 / 뒤 4자리로 쪼갠다
        String area = null;
        if (d.startsWith("02") && d.length() >= 9)      area = d.substring(0, 2);
        else if (d.length() == 10 || d.length() == 11)  area = d.substring(0, 3);

        if (area != null) {
            String mid  = d.substring(area.length(), d.length() - 4);
            String last = d.substring(d.length() - 4);

            out.add(area + "-" + mid + "-" + last);     // 031-962-9578
            out.add("(" + area + ")" + mid + "-" + last); // (02)2664-6874 — 경기 자료에 이 형식이 많다
        } else if (d.length() == 8) {
            out.add(d.substring(0, 4) + "-" + d.substring(4));   // 1877-9433
        }

        return out;
    }

    /// 다섯 군데를 우선순위(pri) 붙여 한 질의로 묶는다.
    /// withPhonebook=false 면 전화번호부만 빼고 나머지로 묶는다.
    private static String callerSql(boolean withPhonebook) {
        // TOP 1 이 아니라 여러 줄을 받는다 — 1순위 줄에 현장이 없을 때
        // 뒤쪽 줄에서 보충하기 위해서다(mergeSite). 추가 조회는 없다.
        String sql = "SELECT TOP 20 pri, source, actcd, actnm, callnm, equpnm, cltcd, cltnm, tel FROM (\n";
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
                   -- 전화번호부의 actcd 는 현장 코드가 아니다. 자체 번호 체계라
                   -- TB_E601 과 맞지 않는다(경기 실측: regflag='0' 인 줄까지 0건).
                   -- 그래도 맞는 사업체가 있을 수 있어 조인은 남겨 두고,
                   -- 실제로 맞을 때만 넘긴다 — 화면이 [현장 적용]·[내역보기] 를 헛되이 띄우면 안 된다.
                   CASE WHEN e.actcd IS NULL THEN '' ELSE a.actcd END AS actcd,
                   ISNULL(e.actnm,'') AS actnm,
                   COALESCE(NULLIF(a.actmail,''), e.actnm, '') AS callnm,
                   '' AS equpnm,
                   '' AS cltcd, '' AS cltnm,
                   COALESCE(NULLIF(b.tel,''), a.tel, '') AS tel
              FROM TB_E601CALL a WITH(NOLOCK)
              JOIN TB_E601CALL_01 b WITH(NOLOCK)
                   ON b.custcd   = a.custcd   AND b.spjangcd = a.spjangcd
                  AND b.actcd    = a.actcd    AND b.seq      = a.seq
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


    /// 사원 연락처 — 문자전송의 기본 수신자(통보자)를 채우는 데 쓴다.
    /// 화면은 perid 를 'p' 없이 들고 있고(사원 팝업에서 떼어 넣는다) TB_JA001 은 'p' 를 붙여 쓴다.
    public Map<String, Object> getPersonTel(String spjangcd, String perid) {
        if (perid == null || perid.isBlank()) return null;

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("spjangcd", spjangcd);
        p.addValue("perid", "p" + perid.trim().replaceFirst("^p", ""));

        return sqlRunner.getRow("""
                SELECT TOP 1 pernm, ISNULL(handphone,'') AS handphone
                  FROM TB_JA001 WITH(NOLOCK)
                 WHERE spjangcd = :spjangcd AND perid = :perid
                """, p);
    }

    // ── 보수현장조회 ───────────────────────────────────────────
    //
    // 현장 선택 팝업(popupActnm)은 현장명으로만 찾는다. 전화를 받는 자리에서는
    // 상대가 "○○아파트 3호기" 라거나 번호·주소만 말하는 경우가 많아 그것으로도
    // 찾을 수 있어야 한다. 그래서 현장명·호기명·전화번호·주소를 한 칸으로 훑는다.
    //
    // 호기명으로 찾으면 한 현장에 여러 호기가 걸리므로 현장 단위로 묶는다.
    // 호기까지 고르는 일은 기존 호기 팝업이 한다(현장을 정하면 그 현장 것만 나온다).
    public List<Map<String, Object>> searchSite(String spjangcd, String keyword) {
        // 넘어온 글자 그대로 각 칸에 견준다. 번호도 칸 하나일 뿐이다.
        // 띄어쓰기로 쪼개거나 번호를 따로 다루면 왜 이 현장이 나왔는지 설명이 안 된다.
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("keyword", keyword == null ? "" : keyword.trim());

        String sql = ("""
                SELECT TOP 200
                       e.actcd,
                       e.actnm,
                       ISNULL(e.ancltnm,'')  AS ancltnm,
                       ISNULL(j.pernm,'')    AS pernm,
                       ISNULL(e.tel,'')      AS tel,
                       ISNULL(e.hp,'')       AS hp,
                       ISNULL(e.address,'') + ISNULL(e.address2,'') AS address,
                       (SELECT COUNT(*) FROM TB_E611 m WITH(NOLOCK)
                         WHERE m.spjangcd = e.spjangcd AND m.actcd = e.actcd) AS equpcnt
                  FROM TB_E601 e WITH(NOLOCK)
                  LEFT JOIN TB_JA001 j WITH(NOLOCK)
                         ON j.perid = 'p' + e.perid AND j.spjangcd = e.spjangcd
                 WHERE e.spjangcd = :spjangcd
                   AND (:keyword = ''
                        OR e.actnm               LIKE '%' + :keyword + '%'
                        OR ISNULL(e.ancltnm,'')  LIKE '%' + :keyword + '%'
                        OR ISNULL(e.address,'')  LIKE '%' + :keyword + '%'
                        OR ISNULL(e.address2,'') LIKE '%' + :keyword + '%'
                        OR ISNULL(e.tel,'')      LIKE '%' + :keyword + '%'
                        OR ISNULL(e.hp,'')       LIKE '%' + :keyword + '%'
                        OR EXISTS (SELECT 1 FROM TB_E611 m WITH(NOLOCK)
                                    WHERE m.spjangcd = e.spjangcd AND m.actcd = e.actcd
                                      AND ISNULL(m.equpnm,'') LIKE '%' + :keyword + '%'))
                 ORDER BY e.actnm
                """);

        return this.sqlRunner.getRows(sql, param);
    }

    // ── 전화번호부 (TB_E601CALL + TB_E601CALL_01) ───────────────
    //
    // 머리(TB_E601CALL)에 이름·비고, 몸통(TB_E601CALL_01)에 번호가 들어간다.
    // actcd 는 현장 코드처럼 생겼지만 전화번호부 자체의 열쇠다 —
    // 현장에서 끌어온 건 실제 현장 코드이고, 여기서 새로 넣은 건 30000001 부터 매긴다.
    //
    // 원본(aprjems)의 조회·수정·삭제에는 spjangcd 조건이 없다. 사업체가 섞이면
    // 남의 번호를 보거나 지우게 되므로 전부 넣었다.

    public List<Map<String, Object>> getPhoneBookList(String spjangcd, String keyword) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("keyword", keyword == null ? "" : keyword.trim());

        // custcd 까지 내려보낸다. 같은 (actcd, seq) 가 두 벌 있는 자료가 확인돼
        // 수정·삭제 때 열쇠를 온전히 들고 가야 엉뚱한 줄을 건드리지 않는다.
        String sql = """
                SELECT a.custcd, a.spjangcd, a.actcd, a.seq,
                       ISNULL(a.actmail,'') AS actmail,
                       ISNULL(b.tel,'')     AS tel,
                       ISNULL(a.remark,'')  AS remark,
                       ISNULL(a.regflag,'') AS regflag,
                       CASE ISNULL(a.regflag,'')
                            WHEN '0' THEN '현장'   WHEN '1' THEN '거래처'
                            WHEN '2' THEN '직원'   WHEN '4' THEN '비상통화'
                            ELSE '일반' END AS regnm,
                       ISNULL(e.actnm,'')   AS actnm
                  FROM TB_E601CALL a WITH(NOLOCK)
                  JOIN TB_E601CALL_01 b WITH(NOLOCK)
                       ON b.custcd   = a.custcd
                      AND b.spjangcd = a.spjangcd
                      AND b.actcd    = a.actcd
                      AND b.seq      = a.seq
                  LEFT JOIN TB_E601 e WITH(NOLOCK)
                       ON e.spjangcd = a.spjangcd AND e.actcd = a.actcd
                 WHERE a.spjangcd = :spjangcd
                   AND (:keyword = ''
                        OR ISNULL(a.actmail,'') LIKE '%' + :keyword + '%'
                        OR ISNULL(b.tel,'')     LIKE '%' + :keyword + '%')
                 ORDER BY a.actmail
                """;
        return this.sqlRunner.getRows(sql, param);
    }

    /// seq 가 비어 있으면 새로 넣고, 있으면 고친다.
    /// 새 번호의 actcd 는 30000001 부터 하나씩 올린다(원본과 같은 방식).
    @Transactional
    public void savePhoneBook(String custcd, String spjangcd, String actcd, String seq,
                              String actmail, String tel, String remark, String regflag) {

        String today = new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date());

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("custcd",   custcd);
        p.addValue("spjangcd", spjangcd);
        p.addValue("actmail",  actmail);
        p.addValue("tel",      tel);
        p.addValue("remark",   remark);
        p.addValue("today",    today);

        if (seq == null || seq.isBlank()) {
            String flag = (regflag == null || regflag.isBlank()) ? "3" : regflag.trim();

            p.addValue("regflag", flag);
            p.addValue("actcd",   nextPhoneBookActcd(spjangcd, flag));
            p.addValue("seq",     "01");

            this.namedParameterJdbcTemplate.update("""
                    INSERT INTO TB_E601CALL
                           (custcd, spjangcd, actcd, seq, tel, actmail, remark, regdate, regflag)
                    VALUES (:custcd, :spjangcd, :actcd, :seq, :tel, :actmail, :remark, :today, :regflag)
                    """, p);
            this.namedParameterJdbcTemplate.update("""
                    INSERT INTO TB_E601CALL_01
                           (custcd, spjangcd, actcd, seq, tel, indate)
                    VALUES (:custcd, :spjangcd, :actcd, :seq, :tel, :today)
                    """, p);
            return;
        }

        p.addValue("actcd", actcd);
        p.addValue("seq",   seq);

        this.namedParameterJdbcTemplate.update("""
                UPDATE TB_E601CALL
                   SET tel = :tel, actmail = :actmail, remark = :remark, regdate = :today
                 WHERE custcd = :custcd AND spjangcd = :spjangcd
                   AND actcd  = :actcd  AND seq      = :seq
                """, p);
        this.namedParameterJdbcTemplate.update("""
                UPDATE TB_E601CALL_01
                   SET tel = :tel, indate = :today
                 WHERE custcd = :custcd AND spjangcd = :spjangcd
                   AND actcd  = :actcd  AND seq      = :seq
                """, p);
    }

    /// 원본은 `delete ... where actcd not in (select actcd from TB_E601CALL_01)` 로
    /// 사업체 구분 없이 홀로 남은 머리를 몽땅 지운다. 지울 줄만 지우도록 바꿨다.
    @Transactional
    public void deletePhoneBook(String custcd, String spjangcd, String actcd, String seq) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("custcd",   custcd);
        p.addValue("spjangcd", spjangcd);
        p.addValue("actcd",    actcd);
        p.addValue("seq",      seq);

        String where = " WHERE custcd = :custcd AND spjangcd = :spjangcd"
                     + "   AND actcd  = :actcd  AND seq      = :seq";

        this.namedParameterJdbcTemplate.update("DELETE FROM TB_E601CALL_01" + where, p);
        this.namedParameterJdbcTemplate.update("DELETE FROM TB_E601CALL"    + where, p);
    }

    /// actcd 첫 자리가 곧 regflag 다 — 현장 0xxxxxxx · 거래처 1xxxxxxx · 직원 2xxxxxxx · 일반 3xxxxxxx.
    /// 경기 실측(현장 1,598 · 거래처 1,374 · 직원 111건)에서 예외 없이 지켜지는 규칙이라
    /// 새로 넣는 줄도 고른 구분에 맞는 번호대에서 채번한다.
    private String nextPhoneBookActcd(String spjangcd, String regflag) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("spjangcd", spjangcd);
        p.addValue("prefix",   regflag + "%");

        Map<String, Object> row = sqlRunner.getRow("""
                SELECT MAX(actcd) AS maxcd FROM TB_E601CALL WITH(NOLOCK)
                 WHERE spjangcd = :spjangcd AND actcd LIKE :prefix AND LEN(actcd) = 8
                """, p);

        String first = regflag + "0000001";   // 비어 있으면 30000001 꼴로 시작한다
        String max = (row == null || row.get("maxcd") == null) ? null : row.get("maxcd").toString().trim();
        if (max == null || max.isEmpty()) return first;

        try {
            String next = String.valueOf(Long.parseLong(max) + 1);
            // 번호대를 넘어서면(예: 09999999 → 10000000) 남의 구분을 침범한다
            return next.length() == 8 && next.startsWith(regflag) ? next : first;
        } catch (NumberFormatException e) { return first; }
    }

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
