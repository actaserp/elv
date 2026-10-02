package mes.app.clock.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 연차관리 — PB w_pb209 / wf_createyear() 이식.
 *
 * TB_PB209 구조 (PB 원본)
 *   PK  : custcd + spjangcd + hyear + hseq + perid
 *   hactcd '08' = 연차
 *   hseq  '00'  = 발생행 (iwolnum 이월 + holinum 발생 + monthnum = restnum 잔여)
 *   hseq  '01'~ = 사용내역행 (PB 에서 휴가확정 시 쌓임)
 *   perid       = 'p001' 형식 (TB_JA001.perid 와 동일)
 *
 * 사용내역은 웹에서는 TB_PB204(휴가신청, fixflag='1') 를 원장으로 본다.
 * TB_PB204.perid 는 PB 가 등록한 행은 'p001', 웹이 등록한 행은 person.id(숫자) 라서 두 형식 모두 매칭한다.
 */
@Service
public class ClockYearlyService {

    /** 연차 휴가구분 코드 */
    private static final String HACTCD_YEAR = "08";
    /** 발생행 순번 */
    private static final String HSEQ_BASE = "00";
    /** 1년 미만 월차 상한 (근로기준법 제60조 제2항) */
    private static final int MONTHLY_MAX_DAYS = 11;
    /** TB_PB209.remark 길이 — 넘치면 오래된 이력부터 지운다 */
    private static final int REMARK_MAX = 255;
    /** 조정일수 입력 허용 범위 (monthnum decimal(5,1)) */
    private static final BigDecimal ADJUST_LIMIT = new BigDecimal("999.9");

    @Autowired
    SqlRunner sqlRunner;

    // ------------------------------------------------------------------ 조회

    /**
     * 연차 현황 목록.
     *
     * @param year   정산년도 (yyyy)
     * @param name   사원명 (부분일치)
     * @param rtclafi 재직구분 (TB_JA001.rtclafi, 001 재직 / 002 퇴사). 빈값이면 재직자만
     */
    public List<Map<String, Object>> getYearlyList(String year, String name, String spjangcd, String rtclafi) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("year", year);
        dicParam.addValue("spjangcd", spjangcd);

        StringBuilder sql = new StringBuilder(employeeCte(dicParam, rtclafi, name));
        sql.append("""
                SELECT ROW_NUMBER() OVER (ORDER BY e.prtseq, e.perid) AS rownum,
                       e.personid                              AS id,
                       e.perid,
                       e.pernm                                  AS person_name,
                       e.RSPNM,
                       e.entdate                                AS rtdate,
                       ISNULL(g.iwolnum, 0)                     AS ewolnum,
                       ISNULL(g.holinum, 0)                     AS holinum,
                       ISNULL(g.monthnum, 0)                    AS monthnum,
                       ISNULL(u.daynum, 0)                      AS daynum,
                       CASE WHEN g.perid IS NULL THEN NULL
                            ELSE ISNULL(g.iwolnum,0) + ISNULL(g.holinum,0) + ISNULL(g.monthnum,0) - ISNULL(u.daynum,0)
                       END                                      AS restnum,
                       g.startdate,
                       g.enddate
                  FROM emp e
                  LEFT JOIN TB_PB209 g
                         ON g.spjangcd = e.spjangcd
                        AND g.custcd   = e.custcd
                        AND g.hyear    = :year
                        AND g.hactcd   = '08'
                        AND g.hseq     = '00'
                        AND g.perid    = e.perid
                  OUTER APPLY (
                        SELECT SUM(ISNULL(v.daynum, 0)) AS daynum
                          FROM TB_PB204 v
                          JOIN tb_pb210 w ON w.spjangcd = v.SPJANGCD AND w.workcd = v.workcd AND w.yearflag = '1'
                         WHERE v.SPJANGCD = e.spjangcd
                           AND ISNULL(v.fixflag, '') = '1'
                           AND LEFT(v.reqdate, 4) = :year
                           AND ( v.perid = e.perid
                              OR (e.personid IS NOT NULL AND TRY_CAST(v.perid AS INT) = e.personid) )
                  ) u
                 ORDER BY e.prtseq, e.perid
                """);

        return nvl(this.sqlRunner.getRows(sql.toString(), dicParam));
    }

    /** 사원 1명의 연차 발생·사용 내역 */
    public List<Map<String, Object>> getYearlyDetail(Integer id, String year, String spjangcd) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("id", id);
        dicParam.addValue("year", year);
        dicParam.addValue("spjangcd", spjangcd);

        String sql = """
                WITH emp AS (
                    SELECT j.custcd, j.spjangcd, j.perid, p.id AS personid
                      FROM TB_JA001 j
                      JOIN person p ON p.Code = j.perid AND p.spjangcd = j.spjangcd
                     WHERE j.spjangcd = :spjangcd AND p.id = :id
                ),
                gen AS (
                    SELECT g.perid, g.iwolnum, g.holinum, g.monthnum,
                           ISNULL(g.iwolnum,0) + ISNULL(g.holinum,0) + ISNULL(g.monthnum,0) AS basenum,
                           g.startdate, g.enddate, g.remark
                      FROM TB_PB209 g
                      JOIN emp e ON e.spjangcd = g.spjangcd AND e.custcd = g.custcd AND e.perid = g.perid
                     WHERE g.hyear = :year AND g.hactcd = '08' AND g.hseq = '00'
                ),
                uses AS (
                    SELECT v.reqdate, v.frdate, v.todate, v.daynum, v.workcd, w.worknm, w.yearflag, v.id AS vid,
                           SUM(CASE WHEN w.yearflag = '1' THEN ISNULL(v.daynum,0) ELSE 0 END)
                               OVER (ORDER BY v.reqdate, v.id ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS cum_daynum
                      FROM TB_PB204 v
                      JOIN tb_pb210 w ON w.spjangcd = v.SPJANGCD AND w.workcd = v.workcd
                      JOIN emp e ON e.spjangcd = v.SPJANGCD
                     WHERE ISNULL(v.fixflag, '') = '1'
                       AND LEFT(v.reqdate, 4) = :year
                       AND ( v.perid = e.perid OR TRY_CAST(v.perid AS INT) = e.personid )
                ),
                unioned AS (
                    SELECT CAST(:year AS VARCHAR(4)) + '0000' AS reqdate,
                           g.startdate AS frdate, g.enddate AS todate,
                           CAST(NULL AS DECIMAL(5,1)) AS daynum,
                           CAST(NULL AS VARCHAR(2))   AS workcd,
                           N'생성' AS worknm,
                           g.iwolnum AS ewolnum, g.holinum, g.monthnum,
                           g.basenum AS restnum,
                           g.remark,
                           0 AS ord
                      FROM gen g
                    UNION ALL
                    SELECT u.reqdate, u.frdate, u.todate, u.daynum, u.workcd, u.worknm,
                           CAST(0 AS DECIMAL(5,1)) AS ewolnum,
                           CAST(0 AS DECIMAL(5,1)) AS holinum,
                           CAST(0 AS DECIMAL(5,1)) AS monthnum,
                           CASE WHEN u.yearflag = '1'
                                THEN (SELECT MAX(basenum) FROM gen) - u.cum_daynum
                                ELSE NULL END AS restnum,
                           CAST(NULL AS VARCHAR(255)) AS remark,
                           1 AS ord
                      FROM uses u
                )
                SELECT ROW_NUMBER() OVER (ORDER BY ord, reqdate) - 1 AS rownum, *
                  FROM unioned
                 ORDER BY ord, reqdate
                """;

        return nvl(this.sqlRunner.getRows(sql, dicParam));
    }

    // ------------------------------------------------------- 생성(미리계산)

    /**
     * 연차생성 — 근속 1년 이상자(정산년도 기준 입사년도 &lt;= 정산년도-2).
     * PB wf_createyear('0') 의 llMonth &gt;= 12 분기.
     */
    public List<Map<String, Object>> createYearly(String year, String spjangcd, String baseYm,
                                                  String name, String rtclafi, String createFlag) {
        return build(year, spjangcd, baseYm, name, rtclafi, createFlag, false);
    }

    /**
     * 월차생성 — 근속 1년 미만자(입사년도 = 정산년도-1).
     * PB wf_createyear('0') 의 llMonth &lt; 12 분기. 기준일자까지 경과한 개월수에 비례한다.
     */
    public List<Map<String, Object>> createMonthly(String year, String spjangcd, String baseYm,
                                                   String name, String rtclafi, String createFlag) {
        return build(year, spjangcd, baseYm, name, rtclafi, createFlag, true);
    }

    private List<Map<String, Object>> build(String year, String spjangcd, String baseYm,
                                            String name, String rtclafi, String createFlag,
                                            boolean monthlyMode) {

        int settleYear = Integer.parseInt(year);
        int[] base = parseYm(baseYm, settleYear);
        int baseYear = base[0], baseMonth = base[1];

        List<Map<String, Object>> targets = getTargets(year, spjangcd, name, rtclafi);
        List<Map<String, Object>> rows = new ArrayList<>();

        boolean deleteFirst = "1".equals(createFlag);

        for (Map<String, Object> t : targets) {
            String entdate = str(t.get("entdate"));
            if (entdate.length() < 4) continue;

            int entYear;
            try { entYear = Integer.parseInt(entdate.substring(0, 4)); } catch (Exception e) { continue; }

            // PB : if ls_stddate(입사 다음해 1/1) > ls_today(정산년 1/1) then continue
            //      → 정산년도 및 그 이후 입사자는 대상 아님
            if (entYear >= settleYear) continue;

            // PB : DATEDIFF(MONTH, 입사다음해 1/1, 정산년 1/1) — 두 값 모두 1월 1일이라 12의 배수가 된다
            long months = (long) (settleYear - (entYear + 1)) * 12;
            boolean underOneYear = months < 12;

            if (underOneYear != monthlyMode) continue;

            BigDecimal holinum;
            long workedMonths = 0;
            if (monthlyMode) {
                // 입사일로부터 기준월 말일까지 채운 개월수 1개월당 1일 (1년 미만 월차, 최대 11일)
                workedMonths = monthsWorked(entdate, baseYear, baseMonth);
                holinum = monthlyDays(workedMonths);
            } else {
                holinum = tenureDays(months);
            }
            if (holinum.compareTo(BigDecimal.ZERO) <= 0) continue;

            // PB : 이월 SELECT 가 주석처리되어 있어 이월은 항상 0
            BigDecimal iwolnum = BigDecimal.ZERO;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", t.get("personid"));
            row.put("custcd", t.get("custcd"));
            row.put("spjangcd", t.get("spjangcd"));
            row.put("perid", t.get("perid"));
            row.put("person_name", t.get("pernm"));
            row.put("RSPNM", t.get("RSPNM"));
            row.put("rtdate", entdate);
            row.put("ewolnum", iwolnum);
            row.put("holinum", holinum);
            row.put("monthnum", BigDecimal.ZERO);
            row.put("daynum", BigDecimal.ZERO);
            row.put("restnum", iwolnum.add(holinum));
            row.put("months", monthlyMode ? workedMonths : months);
            row.put("exists", t.get("already"));
            rows.add(row);
        }

        // PB : '2' 신규만 등록 → 이미 발생행이 있으면 건너뛴다
        if (!deleteFirst) {
            rows.removeIf(r -> {
                Object already = r.get("exists");
                return already != null && !"0".equals(String.valueOf(already));
            });
        }

        int no = 1;
        for (Map<String, Object> r : rows) {
            r.remove("exists");
            r.put("rownum", no++);
        }
        return rows;
    }

    /** 생성 대상자 (재직자 + 입사일 보유) */
    private List<Map<String, Object>> getTargets(String year, String spjangcd, String name, String rtclafi) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("year", year);
        dicParam.addValue("spjangcd", spjangcd);

        String sql = employeeCte(dicParam, rtclafi, name) + """
                SELECT e.custcd, e.spjangcd, e.perid, e.pernm, e.entdate, e.RSPNM, e.personid, e.prtseq,
                       CASE WHEN EXISTS (
                            SELECT 1 FROM TB_PB209 g
                             WHERE g.spjangcd = e.spjangcd AND g.custcd = e.custcd
                               AND g.hyear = :year AND g.hactcd = '08' AND g.hseq = '00'
                               AND g.perid = e.perid
                       ) THEN 1 ELSE 0 END AS already
                  FROM emp e
                 WHERE ISNULL(e.entdate, '') <> ''
                 ORDER BY e.prtseq, e.perid
                """;

        return nvl(this.sqlRunner.getRows(sql, dicParam));
    }

    // ------------------------------------------------------------------ 저장

    /**
     * 발생행 저장. createFlag '1' = 삭제 후 등록, '2' = 신규만 등록.
     * PB 는 hseq 조건 없이 삭제해 사용내역행까지 지우지만, 웹에서는 발생행(hseq='00')만 지운다.
     */
    public int saveGenerated(List<Map<String, Object>> rows, String year, String spjangcd, String createFlag) {

        if (rows == null || rows.isEmpty()) return 0;

        boolean deleteFirst = "1".equals(createFlag);
        int saved = 0;

        for (Map<String, Object> row : rows) {
            String perid = str(row.get("perid"));
            if (perid.isEmpty()) continue;
            String custcd = str(row.get("custcd"));

            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("custcd", custcd);
            p.addValue("spjangcd", spjangcd);
            p.addValue("hyear", year);
            p.addValue("perid", perid);
            p.addValue("hactcd", HACTCD_YEAR);
            p.addValue("hseq", HSEQ_BASE);

            boolean exists = this.sqlRunner.queryForCount("""
                    SELECT COUNT(*) FROM TB_PB209
                     WHERE custcd = :custcd AND spjangcd = :spjangcd AND hyear = :hyear
                       AND hactcd = :hactcd AND hseq = :hseq AND perid = :perid
                    """, p) > 0;

            if (exists) {
                if (!deleteFirst) continue;   // '2' 신규만 등록
                this.sqlRunner.execute("""
                        DELETE FROM TB_PB209
                         WHERE custcd = :custcd AND spjangcd = :spjangcd AND hyear = :hyear
                           AND hactcd = :hactcd AND hseq = :hseq AND perid = :perid
                        """, p);
            }

            p.addValue("iwolnum", dec(row.get("ewolnum")));
            p.addValue("holinum", dec(row.get("holinum")));
            p.addValue("monthnum", dec(row.get("monthnum")));
            p.addValue("restnum", dec(row.get("restnum")));
            p.addValue("hfdate", year + "0000");
            p.addValue("htdate", year + "0000");
            p.addValue("startdate", year + "0101");
            p.addValue("enddate", year + "1231");

            this.sqlRunner.execute("""
                    INSERT INTO TB_PB209
                           (custcd, spjangcd, hyear, hactcd, hseq, perid,
                            hfdate, htdate, daynum, hflag, startdate, enddate,
                            iwolnum, holinum, monthnum, restnum)
                    VALUES (:custcd, :spjangcd, :hyear, :hactcd, :hseq, :perid,
                            :hfdate, :htdate, 0, '1', :startdate, :enddate,
                            :iwolnum, :holinum, :monthnum, :restnum)
                    """, p);
            saved++;
        }
        return saved;
    }

    // ------------------------------------------------------------ 조정일수

    /**
     * 조정일수(monthnum) 저장. 발생행(hseq='00')의 monthnum 만 바꾸고 잔여를 다시 계산한다.
     *
     * 잔여를 직접 받지 않는 이유 — 화면의 잔여는
     *   이월 + 발생 + 조정 − 사용(TB_PB204)
     * 으로 늘 계산되기 때문에, 잔여를 적어 넣어도 휴가가 한 건만 들어오면 덮인다.
     * 그래서 입력은 구성요소인 조정일수에 받는다.
     * monthnum 은 PB ue_save 의 restnum = iwolnum + holinum + monthnum 식에도 들어 있어
     * PB 화면에서 봐도 숫자가 어긋나지 않는다.
     *
     * @param actor 이력에 남길 작업자 표시명
     */
    public Map<String, Object> saveAdjust(List<Map<String, Object>> rows, String year,
                                          String spjangcd, String actor) {

        int saved = 0;
        List<String> notGenerated = new ArrayList<>();   // 발생행이 없어 저장 못한 사원
        List<String> outOfRange = new ArrayList<>();     // 허용 범위를 넘은 입력

        if (rows != null) {
            for (Map<String, Object> row : rows) {
                String perid = str(row.get("perid"));
                if (perid.isEmpty()) continue;
                String pernm = str(row.get("person_name"));

                BigDecimal after = dec(row.get("monthnum")).setScale(1, java.math.RoundingMode.HALF_UP);
                if (after.abs().compareTo(ADJUST_LIMIT) > 0) {
                    outOfRange.add(pernm.isEmpty() ? perid : pernm);
                    continue;
                }

                MapSqlParameterSource p = new MapSqlParameterSource();
                p.addValue("spjangcd", spjangcd);
                p.addValue("hyear", year);
                p.addValue("hactcd", HACTCD_YEAR);
                p.addValue("hseq", HSEQ_BASE);
                p.addValue("perid", perid);

                Map<String, Object> cur = this.sqlRunner.getRow("""
                        SELECT custcd, iwolnum, holinum, monthnum, remark
                          FROM TB_PB209
                         WHERE spjangcd = :spjangcd AND hyear = :hyear
                           AND hactcd = :hactcd AND hseq = :hseq AND perid = :perid
                        """, p);

                // 발생행이 없으면 조정만 따로 만들지 않는다 — 연차생성을 먼저 해야 한다
                if (cur == null) {
                    notGenerated.add(pernm.isEmpty() ? perid : pernm);
                    continue;
                }

                BigDecimal before = dec(cur.get("monthnum")).setScale(1, java.math.RoundingMode.HALF_UP);
                if (before.compareTo(after) == 0) continue;   // 바뀐 게 없다

                p.addValue("custcd", str(cur.get("custcd")));
                p.addValue("monthnum", after);
                p.addValue("restnum", dec(cur.get("iwolnum")).add(dec(cur.get("holinum"))).add(after));
                p.addValue("remark", appendHistory(str(cur.get("remark")), actor, before, after));

                this.sqlRunner.execute("""
                        UPDATE TB_PB209
                           SET monthnum = :monthnum, restnum = :restnum, remark = :remark
                         WHERE custcd = :custcd AND spjangcd = :spjangcd AND hyear = :hyear
                           AND hactcd = :hactcd AND hseq = :hseq AND perid = :perid
                        """, p);
                saved++;
            }
        }

        Map<String, Object> out = new HashMap<>();
        out.put("saved", saved);
        out.put("notGenerated", notGenerated);
        out.put("outOfRange", outOfRange);
        return out;
    }

    /** remark 에 조정 이력을 덧붙인다. 255자를 넘으면 오래된 것부터 버린다 */
    private String appendHistory(String old, String actor, BigDecimal before, BigDecimal after) {
        String who = (actor == null || actor.isBlank()) ? "-" : actor.trim();
        String entry = "[" + LocalDate.now() + " " + who + "] 조정 " + plain(before) + "→" + plain(after);

        String merged = (old == null || old.isBlank()) ? entry : old.trim() + " / " + entry;
        while (merged.length() > REMARK_MAX) {
            int cut = merged.indexOf(" / ");
            if (cut < 0) {                                   // 한 건만으로도 넘치면 뒤에서 자른다
                merged = merged.substring(merged.length() - REMARK_MAX);
                break;
            }
            merged = merged.substring(cut + 3);
        }
        return merged;
    }

    /** 2.0 → 2, -2.5 → -2.5 */
    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    // --------------------------------------------------------------- 내부

    /** 사원 공통 CTE. rtclafi 가 비면 재직자('001')만 */
    private String employeeCte(MapSqlParameterSource dicParam, String rtclafi, String name) {

        StringBuilder sb = new StringBuilder("""
                WITH emp AS (
                    SELECT j.custcd, j.spjangcd, j.perid, j.pernm, j.entdate, j.rtclafi,
                           ISNULL(j.prtseq, 9999) AS prtseq,
                           pz.RSPNM, p.id AS personid
                      FROM TB_JA001 j
                      LEFT JOIN person   p  ON p.Code   = j.perid AND p.spjangcd = j.spjangcd
                      LEFT JOIN TB_PZ001 pz ON pz.rspcd = j.rspcd
                     WHERE j.spjangcd = :spjangcd
                """);

        if (rtclafi != null && !rtclafi.isBlank()) {
            sb.append("       AND j.rtclafi = :rtclafi\n");
            dicParam.addValue("rtclafi", rtclafi);
        } else {
            sb.append("       AND j.rtclafi = '001'\n");
        }
        if (name != null && !name.isBlank()) {
            sb.append("       AND j.pernm LIKE '%' + :name + '%'\n");
            dicParam.addValue("name", name.trim());
        }
        sb.append(")\n");
        return sb.toString();
    }

    /**
     * PB 근속월수별 연차일수.
     * CHOOSE CASE llMonth : &lt;=24 15, &lt;=48 16, &lt;=72 17, &lt;=96 18, &lt;=120 19,
     *                       &lt;=144 20, &lt;=168 21, &lt;=192 22, &lt;=216 23, &lt;=240 24, ELSE 25
     */
    private BigDecimal tenureDays(long months) {
        int d;
        if      (months <= 24)  d = 15;
        else if (months <= 48)  d = 16;
        else if (months <= 72)  d = 17;
        else if (months <= 96)  d = 18;
        else if (months <= 120) d = 19;
        else if (months <= 144) d = 20;
        else if (months <= 168) d = 21;
        else if (months <= 192) d = 22;
        else if (months <= 216) d = 23;
        else if (months <= 240) d = 24;
        else                    d = 25;
        return BigDecimal.valueOf(d).setScale(1);
    }

    /**
     * 1년 미만 근로자 월차 — 입사일로부터 채운 1개월마다 1일, 최대 11일.
     *
     * PB 원본은 정산년도 1월 1일부터 기준일까지의 경과월수를 써서 (llMonth / 12) * 15 로 계산했다.
     * 입사월을 보지 않아 연말 입사자도 10일 이상이 나오므로, 입사일 기준으로 바꿨다. (2026-10-01)
     */
    private BigDecimal monthlyDays(long workedMonths) {
        if (workedMonths <= 0) return BigDecimal.ZERO.setScale(1);
        long d = Math.min(workedMonths, MONTHLY_MAX_DAYS);
        return BigDecimal.valueOf(d).setScale(1);
    }

    /** 입사일(yyyyMMdd) 로부터 기준월 말일까지 채운 개월수 */
    private long monthsWorked(String entdate, int baseYear, int baseMonth) {
        if (entdate == null || entdate.length() != 8) return 0;
        int ey, em, ed;
        try {
            ey = Integer.parseInt(entdate.substring(0, 4));
            em = Integer.parseInt(entdate.substring(4, 6));
            ed = Integer.parseInt(entdate.substring(6, 8));
        } catch (Exception e) {
            return 0;
        }
        if (em < 1 || em > 12) return 0;

        int lastDay = java.time.YearMonth.of(baseYear, baseMonth).lengthOfMonth();
        long m = (long) (baseYear - ey) * 12 + (baseMonth - em);
        if (lastDay < ed) m--;          // 기준월 말일이 입사일(일)에 못 미치면 한 달 덜 찬 것
        return Math.max(m, 0);
    }

    /** 'yyyy-MM' / 'yyyyMM' 기준일자 → {년, 월}. 비면 정산년도 12월 */
    private int[] parseYm(String baseYm, int settleYear) {
        String s = baseYm == null ? "" : baseYm.replace("-", "").trim();
        if (s.length() >= 6) {
            try {
                return new int[]{Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(4, 6))};
            } catch (Exception ignore) { /* fall through */ }
        }
        return new int[]{settleYear, 12};
    }

    private static BigDecimal dec(Object v) {
        if (v == null) return BigDecimal.ZERO;
        try { return new BigDecimal(String.valueOf(v)); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static List<Map<String, Object>> nvl(List<Map<String, Object>> rows) {
        return rows == null ? new ArrayList<>() : rows;
    }
}
