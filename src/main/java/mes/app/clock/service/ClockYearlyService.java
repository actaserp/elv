package mes.app.clock.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.system.service.TenantDbService;
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
@Slf4j
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

    /** 사업체별 연차 산정기준(tb_tenant_db.annual_basis)을 읽는다 */
    @Autowired
    TenantDbService tenantDbService;

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
                  /* 사용일수 — 발생행의 기간(startdate~enddate) 안에 든 확정 신청만 센다.
                     PB 가 만든 옛 발생행처럼 기간이 비어 있거나, 발생행이 아예 없는 사원은
                     해당 연도 전체로 둬서 예전 동작(달력연도)을 그대로 유지한다.
                     기간으로 자르면 산정기준이 입사일이어도 이 쿼리는 손대지 않아도 맞는다 */
                  OUTER APPLY (
                        SELECT SUM(ISNULL(v.daynum, 0)) AS daynum
                          FROM TB_PB204 v
                          JOIN tb_pb210 w ON w.spjangcd = v.SPJANGCD AND w.workcd = v.workcd AND w.yearflag = '1'
                         WHERE v.SPJANGCD = e.spjangcd
                           AND ISNULL(v.fixflag, '') = '1'
                           AND v.reqdate >= ISNULL(NULLIF(g.startdate, ''), :year + '0101')
                           AND v.reqdate <= ISNULL(NULLIF(g.enddate,   ''), :year + '1231')
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
                /* 사용내역을 자를 기간. 발생행의 startdate~enddate 를 쓴다.
                   PB 가 만든 옛 행처럼 비어 있거나 발생행이 없으면 연도 전체로 둔다.
                   gen 이 0행이어도 집계라 1행이 나오므로 기간은 항상 정해진다 */
                period AS (
                    SELECT ISNULL(MIN(NULLIF(g.startdate, '')), :year + '0101') AS startdate,
                           ISNULL(MAX(NULLIF(g.enddate,   '')), :year + '1231') AS enddate
                      FROM gen g
                ),
                uses AS (
                    SELECT v.reqdate, v.frdate, v.todate, v.daynum, v.workcd, w.worknm, w.yearflag, v.id AS vid,
                           SUM(CASE WHEN w.yearflag = '1' THEN ISNULL(v.daynum,0) ELSE 0 END)
                               OVER (ORDER BY v.reqdate, v.id ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS cum_daynum
                      FROM TB_PB204 v
                      JOIN tb_pb210 w ON w.spjangcd = v.SPJANGCD AND w.workcd = v.workcd
                      JOIN emp e ON e.spjangcd = v.SPJANGCD
                      CROSS JOIN period pd
                     WHERE ISNULL(v.fixflag, '') = '1'
                       AND v.reqdate >= pd.startdate
                       AND v.reqdate <= pd.enddate
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
     * 연차·월차 생성(미리계산). 한 번에 근속 1년 이상(연차)과 1년 미만(월차)을 모두 만든다.
     *
     * 산정기준은 사업체 설정(tb_tenant_db.annual_basis)을 따른다.
     *   'F' 회계연도 — 매년 1/1 에 일괄 발생. 기간은 정산년도 1/1~12/31.
     *   'E' 입사일   — 사원별 입사 기념일에 발생. 기간은 그 기념일부터 1년.
     *
     * 이월은 직전 연도 잔여를 전액 넘긴다 ({@link #prevRemains}).
     *
     * @param dbKey 사업체 식별자(tb_tenant_db.spjangcd). 사업장코드('ZZ')가 아니다 —
     *              둘을 혼동하면 설정을 못 찾아 조용히 회계연도로 돌아간다
     */
    /**
     * @param carryFlag 이월 여부. '0' 이면 이월하지 않고(전원 0), 그 밖의 값이면 직전 연도 잔여를
     *                  전액 가져온다. 생성 팝업에서 매번 고른다.
     *                  <b>이월하지 않으면 미사용분은 소멸한다.</b> 근로기준법 제60조 ⑦ 에 따라
     *                  소멸한 연차는 수당 지급 대상이므로, 정산 절차가 있을 때만 쓸 선택지다.
     */
    public List<Map<String, Object>> createAll(String year, String spjangcd, String dbKey, String baseYm,
                                               String name, String rtclafi, String createFlag,
                                               String carryFlag) {
        return build(year, spjangcd, dbKey, baseYm, name, rtclafi, createFlag, carryFlag);
    }

    private List<Map<String, Object>> build(String year, String spjangcd, String dbKey, String baseYm,
                                            String name, String rtclafi, String createFlag,
                                            String carryFlag) {

        int settleYear = Integer.parseInt(year);
        int[] base = parseYm(baseYm, settleYear);
        int baseYear = base[0], baseMonth = base[1];

        boolean entryBasis = tenantDbService.isEntryDateBasis(dbKey);

        /* 직전 연도 잔여 = 이월액. 발생행이 없는 사원은 키가 없어 0 이 된다.
           '0' 을 고르면 아예 읽지 않는다 — 전원 이월 0 이 되므로 쿼리를 돌릴 이유가 없다. */
        boolean carryOver = !"0".equals(carryFlag);
        Map<String, BigDecimal> carry = carryOver ? prevRemains(settleYear, spjangcd) : Map.of();
        int negatives = 0;

        // 월차 개근 판정용 결근 기록. 기록이 없는 사원은 키가 없어 전부 개근으로 본다
        Map<String, java.util.TreeSet<String>> absents = absentDays(settleYear, spjangcd);
        long droppedMonths = 0;
        int extraPersons = 0;      // 회계연도 k==1 에서 월차 잔여분을 더한 인원
        long extraTotal = 0;       // 그 합계 일수

        List<Map<String, Object>> targets = getTargets(year, spjangcd, name, rtclafi);
        List<Map<String, Object>> rows = new ArrayList<>();

        boolean regenerate = "1".equals(createFlag);

        for (Map<String, Object> t : targets) {
            String entdate = str(t.get("entdate"));
            if (entdate.length() < 4) continue;

            int entYear;
            try { entYear = Integer.parseInt(entdate.substring(0, 4)); } catch (Exception e) { continue; }

            /* 완성 계속근로연수 k — 근로기준법 제60조 ④ 의 가산(15 + (k-1)/2)이 붙는 시점 기준.
               <b>두 기준이 같은 식을 쓴다.</b> 입사년도만 보고 k = 정산년 − 입사년.

               2026-11-09 이전에는 회계연도 기준만 −1 을 더 뺐다(k = 정산년 − 입사년 − 1).
               1월 1일 시점에는 아직 1년을 못 채웠다는 계산이었는데, 그 결과
                 · 입사 첫 해는 k < 0 이 되어 발생행이 아예 만들어지지 않았고(월차 0일),
                 · 가산일수 도달이 법정보다 항상 1년 늦었다(3년차 16일이 4년째에 붙었다).
               둘 다 근로기준법 미달이다. 회계연도 기준은 법에 있는 제도가 아니라 행정해석이
               허용하는 편의 방식이고, 조건은 "근로자에게 법정보다 불리하지 않을 것" 하나다.
               유리하게 주는 것은 허용되므로 −1 을 뺐다.

               기준 차이는 이제 <b>기간(startdate~enddate)만</b> 남는다.
               회계연도는 1/1~12/31, 입사일은 기념일~1년 뒤 전날. 발생일수는 같다. */
            int k = settleYear - entYear;

            if (k < 0) continue;                 // 아직 대상이 아니다
            boolean monthly = (k == 0);          // 1년을 못 채웠으면 월차

            BigDecimal holinum;
            long months;
            long extraMonths = 0;                // 회계연도 기준에서 가산한 1년 미만 월차 잔여분
            if (monthly) {
                /* 1년 미만 월차 — 채운 1개월마다 1일. 단 근로기준법 제60조 ② 는 "1개월 개근"
                   을 조건으로 두므로, 결근이 기록된 달은 빼고 센다.

                   회계연도 기준은 이 행의 기간이 그 해 12/31 로 끝나므로, 기준일자가 다음 해로
                   넘어가 있어도 12월까지만 센다. 기간 밖의 발생분을 이 행에 넣지 않기 위해서다.
                   입사일 기준은 기간이 1년 미만 구간 전체를 덮으므로 기준일자를 그대로 쓴다. */
                int cutYear = baseYear, cutMonth = baseMonth;
                if (!entryBasis && (baseYear > settleYear)) {
                    cutYear = settleYear;
                    cutMonth = 12;
                }
                long worked = monthsWorked(entdate, cutYear, cutMonth);
                months = perfectMonths(entdate, worked, absents.get(str(t.get("perid"))));
                droppedMonths += (worked - months);
                holinum = monthlyDays(months);
            } else {
                months = (long) k * 12;
                holinum = tenureDays(months);

                /* 회계연도 기준에서 1년이 되는 해(k == 1) — 연차와 월차 구간이 한 해에 걸친다.
                   1년 미만 월차는 입사일부터 1년 되는 날까지 최대 11일인데, 입사 첫 해(k == 0)
                   행에는 그 해 12/31 까지의 분만 들어간다. 나머지는 이 해에 발생하므로 더한다.
                   더하지 않으면 월차 11일이 보장되지 않는다(제60조 ②).

                   예) 2024-06-24 입사 → 첫 해 6일(7/23~12/23) + 이 해 5일(1/23~5/23) = 11일

                   입사일 기준은 k == 0 행의 기간이 1년 미만 구간 전체를 덮으므로 여기서 더하지
                   않는다. 그 행을 다시 생성하면 11일까지 올라간다. */
                if (!entryBasis && k == 1) {
                    java.util.TreeSet<String> ab = absents.get(str(t.get("perid")));
                    long all = perfectMonths(entdate, MONTHLY_MAX_DAYS, ab);
                    // 입사 첫 해 12/31 까지 채운 개월수 = 그 해 12월을 기준월로 둔 것과 같다
                    long first = perfectMonths(entdate, monthsWorked(entdate, entYear, 12), ab);
                    extraMonths = Math.max(0, all - first);
                    if (extraMonths > 0) {
                        holinum = holinum.add(monthlyDays(extraMonths));
                        extraPersons++;
                        extraTotal += extraMonths;
                    }
                }
            }
            if (holinum.compareTo(BigDecimal.ZERO) <= 0) continue;

            /* 이월 — 직전 연도 잔여를 전액 넘긴다 (한도 없음).
               잔여가 음수인 사람(발생분보다 많이 쓴 경우)은 0 으로 둔다. 선사용분을 다음 해
               연차에서 깎는 것은 근로자에게 불이익이라, 정책 결정 없이 자동으로 하지 않는다.
               대신 몇 명인지 로그에 남긴다 — PB 원본은 음수 이월을 그대로 넣었다(한양 2021년 -16). */
            BigDecimal iwolnum = carry.getOrDefault(carryKey(str(t.get("custcd")), str(t.get("perid"))),
                                                    BigDecimal.ZERO);
            if (iwolnum.signum() < 0) {
                negatives++;
                iwolnum = BigDecimal.ZERO;
            }

            String[] period = periodOf(entryBasis, entdate, settleYear);

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
            row.put("months", months);
            row.put("tenure", k);                 // 완성 계속근로연수
            row.put("extramonths", extraMonths);  // 회계연도 k==1 에서 가산한 월차 잔여분(일수와 같다)
            row.put("startdate", period[0]);
            row.put("enddate", period[1]);
            row.put("monthly", monthly ? "1" : "0");
            row.put("exists", t.get("already"));
            rows.add(row);
        }

        // '2' 신규만 등록 → 이미 발생행이 있는 사원은 목록에서 뺀다
        if (!regenerate) {
            rows.removeIf(r -> {
                Object already = r.get("exists");
                return already != null && !"0".equals(String.valueOf(already));
            });
        }

        if (extraPersons > 0) {
            log.info("1년이 되는 해라 1년 미만 월차 잔여분을 더한 사원 {}명, 합계 {}일. spjangcd={} year={}",
                    extraPersons, extraTotal, spjangcd, year);
        }
        if (!carryOver) {
            log.info("이월하지 않기로 선택되어 전원 이월 0 으로 생성합니다. spjangcd={} year={}", spjangcd, year);
        }
        if (negatives > 0) {
            log.warn("직전 연도 잔여가 음수인 사원 {}명은 이월을 0 으로 두었습니다. spjangcd={} year={}",
                    negatives, spjangcd, year);
        }
        if (droppedMonths > 0) {
            log.info("결근이 기록되어 월차에서 제외한 개월수 합계 {}. spjangcd={} year={}",
                    droppedMonths, spjangcd, year);
        }

        int no = 1;
        for (Map<String, Object> r : rows) {
            r.remove("exists");
            r.put("rownum", no++);
        }
        return rows;
    }

    /**
     * 직전 연도 잔여 — 전액 이월(iwolnum)로 넘길 금액. 키는 {@link #carryKey}.
     *
     * 직전 기간은 <b>직전 연도(hyear = 정산년 − 1)의 발생행</b>이다.
     * "가장 최근 발생행"을 찾지 않는 것은 일부러다 — 연차생성을 수년간 안 돌린 사업체에서는
     * 몇 해 전 잔여가 한꺼번에 들어와 숫자가 폭발한다(경기 2024년 477일, 한양 2021년 330일이
     * 그대로 올라온다). 공백 연도가 있으면 이월은 0 이고, 연속 운영부터 쌓인다.
     *
     * 잔여 = 이월 + 발생 + 조정 − 사용. 사용은 그 발생행의 기간(startdate~enddate)으로 자른다.
     * 입사일 기준이면 그 기간이 달력연도를 넘어가므로, 기준이 무엇이든 이 쿼리 하나로 맞는다.
     * 기간이 비어 있는 옛 PB 행은 그 해 전체로 본다 — getYearlyList 와 같은 규칙이다.
     *
     * 2026년 생성 시점에는 7개 사업체 모두 2025년 발생행이 0건이라 전원 이월 0 이다.
     * 즉 지금 돌아가는 사업체의 숫자는 이 기능으로 변하지 않고, 2027년 생성부터 붙는다.
     * (2026-11-05 실측 — 2027년 예상 이월: 동방 468일/25명, 삼정 555.5일/42명, LRT 1051일/70명)
     */
    private Map<String, BigDecimal> prevRemains(int settleYear, String spjangcd) {

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("spjangcd", spjangcd);
        p.addValue("prevyear", String.valueOf(settleYear - 1));

        String sql = """
                SELECT g.custcd, g.perid,
                       ISNULL(g.iwolnum,0) + ISNULL(g.holinum,0) + ISNULL(g.monthnum,0)
                           - ISNULL(u.daynum,0) AS restnum
                  FROM TB_PB209 g
                  LEFT JOIN person pr ON pr.Code = g.perid AND pr.spjangcd = g.spjangcd
                  OUTER APPLY (
                        SELECT SUM(ISNULL(v.daynum, 0)) AS daynum
                          FROM TB_PB204 v
                          JOIN tb_pb210 w ON w.spjangcd = v.SPJANGCD AND w.workcd = v.workcd
                                         AND w.yearflag = '1'
                         WHERE v.SPJANGCD = g.spjangcd
                           AND ISNULL(v.fixflag, '') = '1'
                           AND v.reqdate >= ISNULL(NULLIF(g.startdate, ''), g.hyear + '0101')
                           AND v.reqdate <= ISNULL(NULLIF(g.enddate,   ''), g.hyear + '1231')
                           AND ( v.perid = g.perid
                              OR (pr.id IS NOT NULL AND TRY_CAST(v.perid AS INT) = pr.id) )
                  ) u
                 WHERE g.spjangcd = :spjangcd
                   AND g.hyear    = :prevyear
                   AND g.hactcd   = '08'
                   AND g.hseq     = '00'
                """;

        Map<String, BigDecimal> out = new HashMap<>();
        try {
            for (Map<String, Object> r : nvl(this.sqlRunner.getRows(sql, p))) {
                out.put(carryKey(str(r.get("custcd")), str(r.get("perid"))), dec(r.get("restnum")));
            }
        } catch (Exception e) {
            // 이월을 못 읽어도 생성 자체는 되게 한다. 조용히 0 이 되면 안 되므로 로그는 남긴다
            log.error("직전 연도 잔여를 읽지 못해 이월을 0 으로 둡니다. spjangcd={} prevyear={}",
                    spjangcd, settleYear - 1, e);
        }
        return out;
    }

    /** TB_PB209 PK 중 사원을 가리키는 부분. custcd 가 여러 개인 사업체가 있어 함께 묶는다 */
    private static String carryKey(String custcd, String perid) {
        return custcd + "|" + perid;
    }

    /**
     * 결근이 기록된 날짜. 1년 미만 월차의 개근 판정에 쓴다.
     * 키는 TB_JA001.perid('p001' 형식), 값은 yyyyMMdd 를 정렬해 담은 집합.
     *
     * 결근은 일근태 tb_pb201.abtime 에 들어간다(numeric(5,2), 반일 결근 0.5 가능).
     * 날짜는 workym(yyyyMM) + workday(dd) 를 붙여 만든다 — 한 컬럼이 아니다.
     * tb_pb201.perid 는 person.id(숫자)지만 PB 가 만든 과거 행이 있을 수 있어 두 형식 모두 맞춘다.
     *
     * <b>기록이 없는 달은 개근으로 본다.</b> 반대로 두면(기록 없음 = 결근) 근태를 아직 쓰지
     * 않는 사업체에서 월차가 전원 0 일이 되어 버린다. 2026-11-06 기준 7개 사업체 모두
     * 결근 기록이 0 건이고 일근태 자체가 거의 쌓이지 않아, 지금은 이 판정이 결과를 바꾸지 않는다.
     * 근태를 제대로 쓰기 시작하는 사업체부터 자동으로 반영된다.
     *
     * 월차 대상은 근속 1년 미만이라 직전 연도 1월부터 보면 충분하다.
     */
    private Map<String, java.util.TreeSet<String>> absentDays(int settleYear, String spjangcd) {

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("spjangcd", spjangcd);
        p.addValue("fromym", (settleYear - 1) + "01");

        String sql = """
                SELECT j.perid                     AS perid,
                       t.workym + t.workday        AS absday
                  FROM tb_pb201 t
                  JOIN TB_JA001 j ON j.spjangcd = t.spjangcd
                  LEFT JOIN person pr ON pr.Code = j.perid AND pr.spjangcd = j.spjangcd
                 WHERE t.spjangcd = :spjangcd
                   AND ISNULL(t.abtime, 0) > 0
                   AND t.workym >= :fromym
                   AND ( t.perid = j.perid
                      OR (pr.id IS NOT NULL AND TRY_CAST(t.perid AS INT) = pr.id) )
                """;

        Map<String, java.util.TreeSet<String>> out = new HashMap<>();
        try {
            for (Map<String, Object> r : nvl(this.sqlRunner.getRows(sql, p))) {
                String perid = str(r.get("perid"));
                String day = str(r.get("absday"));
                if (perid.isEmpty() || day.length() != 8) continue;
                out.computeIfAbsent(perid, k -> new java.util.TreeSet<>()).add(day);
            }
        } catch (Exception e) {
            // 결근을 못 읽어도 생성은 되게 한다. 전부 개근으로 보는 쪽이 안전하다
            log.error("결근 기록을 읽지 못해 월차를 전부 개근으로 계산합니다. spjangcd={}", spjangcd, e);
        }
        return out;
    }

    /**
     * 채운 개월 중 개근한 개월 수. 근로기준법 제60조 ② "1개월 개근 시 1일" 의 판정이다.
     *
     * 판정 단위가 <b>입사일 기준 1개월</b>이라는 점이 중요하다. 3월 3일 입사자의 첫 달은
     * 3/3~4/2 이고 둘째 달은 4/3~5/2 다. 근태는 달력 월(workym)로 쌓이므로 경계가 맞지 않아,
     * 날짜까지 조립해 구간별로 본다. 말일 보정은 LocalDate.plusMonths 가 처리한다
     * (1/31 의 다음 달 경계는 2/28 또는 2/29).
     *
     * 결근 기록이 없으면 채운 개월수를 그대로 돌려준다 — 지금 모든 사업체가 이 경우다.
     * 입사일이 깨져 있으면 판정하지 않는다(깎지 않는다).
     */
    private long perfectMonths(String entdate, long months, java.util.TreeSet<String> absents) {

        if (months <= 0) return 0;
        if (absents == null || absents.isEmpty()) return months;

        LocalDate ent = parseDate(entdate);
        if (ent == null) {
            log.warn("입사일을 해석할 수 없어 개근 판정을 건너뜁니다. entdate={}", entdate);
            return months;
        }

        long paid = 0;
        for (long i = 1; i <= months; i++) {
            LocalDate from = ent.plusMonths(i - 1);
            LocalDate to = ent.plusMonths(i).minusDays(1);
            // yyyyMMdd 는 사전순이 날짜순이라 문자열 범위로 자른다. to 는 포함이므로 inclusive
            if (absents.subSet(ymd(from), true, ymd(to), true).isEmpty()) paid++;
        }
        return paid;
    }

    /** yyyyMMdd → LocalDate. 형식이 틀리거나 없는 날짜(2/30 등)면 null */
    private static LocalDate parseDate(String yyyymmdd) {
        if (yyyymmdd == null || yyyymmdd.length() != 8) return null;
        try {
            return LocalDate.of(Integer.parseInt(yyyymmdd.substring(0, 4)),
                                Integer.parseInt(yyyymmdd.substring(4, 6)),
                                Integer.parseInt(yyyymmdd.substring(6, 8)));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 발생분이 유효한 기간. 조회·집계(getYearlyList / getYearlyDetail / AnnualLeaveSql)가
     * 이 기간을 보고 사용일수를 센다. 그래서 기준이 무엇이든 조회 쪽은 손대지 않아도 맞는다.
     *
     * 회계연도 : 정산년도 1/1 ~ 12/31
     * 입사일   : 정산년도의 입사 기념일 ~ 다음해 기념일 전날 (달력연도를 넘어간다)
     *
     * 2월 29일 입사자는 평년에 그 날이 없으므로 2월 28일로 당긴다.
     */
    private String[] periodOf(boolean entryBasis, String entdate, int settleYear) {

        if (!entryBasis || entdate == null || entdate.length() != 8) {
            return new String[]{settleYear + "0101", settleYear + "1231"};
        }
        try {
            int em = Integer.parseInt(entdate.substring(4, 6));
            int ed = Integer.parseInt(entdate.substring(6, 8));
            if (em < 1 || em > 12 || ed < 1 || ed > 31) {
                return new String[]{settleYear + "0101", settleYear + "1231"};
            }
            LocalDate from = safeDate(settleYear, em, ed);
            LocalDate to = safeDate(settleYear + 1, em, ed).minusDays(1);
            return new String[]{ymd(from), ymd(to)};
        } catch (Exception e) {
            // 입사일이 깨져 있으면 기준을 적용할 수 없다 — 연도 전체로 둬서 최소한 조회는 되게 한다
            log.warn("입사일을 해석할 수 없어 연도 전체로 둡니다. entdate={}", entdate);
            return new String[]{settleYear + "0101", settleYear + "1231"};
        }
    }

    /** 그 해에 없는 날짜(2/29 등)는 그 달 말일로 당긴다 */
    private static LocalDate safeDate(int y, int m, int d) {
        int last = java.time.YearMonth.of(y, m).lengthOfMonth();
        return LocalDate.of(y, m, Math.min(d, last));
    }

    private static String ymd(LocalDate d) {
        return String.format("%04d%02d%02d", d.getYear(), d.getMonthValue(), d.getDayOfMonth());
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
     * 발생행 저장. createFlag '1' = 이미 있어도 다시 계산, '2' = 신규만 등록.
     *
     * '1' 은 예전에 DELETE 후 INSERT 였다. 그래서 다시 생성할 때마다
     * 손으로 넣은 조정일수(monthnum)와 그 이력(remark)이 함께 날아갔다.
     * 지금은 UPDATE 로 발생일수와 기간만 바꾸고 조정일수·이력은 건드리지 않는다.
     * 잔여는 그 조정일수를 포함해 다시 계산한다.
     *
     * remark 를 SELECT·UPDATE 하지 않는 것은 일부러다 — 남양·주원은 그 컬럼이 없어서
     * 건드리는 순간 생성 자체가 터진다.
     *
     * 기간(startdate·enddate)은 사원마다 다를 수 있어 행에서 받는다. 입사일 기준이면
     * 입사 기념일부터 1년이고, 회계연도 기준이면 전원 1/1~12/31 이다.
     *
     * 이월(ewolnum)도 행에서 받는다. 기본값은 직전 연도 잔여 전액이고, 생성 미리보기 화면에서
     * 손으로 고친 값이 있으면 그것이 들어온다. 다시 생성하면 직전 연도에서 다시 계산되므로
     * 몇 번 돌려도 같은 결과가 된다 — 이월은 올해 행이 아니라 작년 행을 보고 만든다.
     */
    public int saveGenerated(List<Map<String, Object>> rows, String year, String spjangcd, String createFlag) {

        if (rows == null || rows.isEmpty()) return 0;

        boolean regenerate = "1".equals(createFlag);
        int saved = 0;

        for (Map<String, Object> row : rows) {
            String perid = str(row.get("perid"));
            if (perid.isEmpty()) continue;

            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("custcd", str(row.get("custcd")));
            p.addValue("spjangcd", spjangcd);
            p.addValue("hyear", year);
            p.addValue("perid", perid);
            p.addValue("hactcd", HACTCD_YEAR);
            p.addValue("hseq", HSEQ_BASE);

            Map<String, Object> cur = this.sqlRunner.getRow("""
                    SELECT monthnum FROM TB_PB209
                     WHERE custcd = :custcd AND spjangcd = :spjangcd AND hyear = :hyear
                       AND hactcd = :hactcd AND hseq = :hseq AND perid = :perid
                    """, p);

            if (cur != null && !regenerate) continue;   // '2' 신규만 등록

            BigDecimal iwolnum = dec(row.get("ewolnum"));
            BigDecimal holinum = dec(row.get("holinum"));
            // 이미 있는 행이면 그 조정일수를 그대로 쓴다. 새 행이면 0 으로 시작한다.
            BigDecimal monthnum = (cur != null) ? dec(cur.get("monthnum")) : dec(row.get("monthnum"));

            String startdate = str(row.get("startdate"));
            String enddate = str(row.get("enddate"));
            if (startdate.length() != 8) startdate = year + "0101";
            if (enddate.length() != 8) enddate = year + "1231";

            p.addValue("iwolnum", iwolnum);
            p.addValue("holinum", holinum);
            p.addValue("monthnum", monthnum);
            p.addValue("restnum", iwolnum.add(holinum).add(monthnum));
            p.addValue("hfdate", year + "0000");
            p.addValue("htdate", year + "0000");
            p.addValue("startdate", startdate);
            p.addValue("enddate", enddate);

            if (cur != null) {
                this.sqlRunner.execute("""
                        UPDATE TB_PB209
                           SET hfdate = :hfdate, htdate = :htdate,
                               startdate = :startdate, enddate = :enddate,
                               iwolnum = :iwolnum, holinum = :holinum, restnum = :restnum
                         WHERE custcd = :custcd AND spjangcd = :spjangcd AND hyear = :hyear
                           AND hactcd = :hactcd AND hseq = :hseq AND perid = :perid
                        """, p);
            } else {
                this.sqlRunner.execute("""
                        INSERT INTO TB_PB209
                               (custcd, spjangcd, hyear, hactcd, hseq, perid,
                                hfdate, htdate, daynum, hflag, startdate, enddate,
                                iwolnum, holinum, monthnum, restnum)
                        VALUES (:custcd, :spjangcd, :hyear, :hactcd, :hseq, :perid,
                                :hfdate, :htdate, 0, '1', :startdate, :enddate,
                                :iwolnum, :holinum, :monthnum, :restnum)
                        """, p);
            }
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
     * 1년 미만 근로자 월차 — 개근한 1개월마다 1일, 최대 11일.
     *
     * PB 원본은 정산년도 1월 1일부터 기준일까지의 경과월수를 써서 (llMonth / 12) * 15 로 계산했다.
     * 입사월을 보지 않아 연말 입사자도 10일 이상이 나오므로, 입사일 기준으로 바꿨다. (2026-10-01)
     *
     * 넘겨받는 값은 {@link #perfectMonths} 를 거친 <b>개근 개월수</b>다. 채운 개월수가 아니다.
     *
     * 이 값은 매월 1 씩 늘어난다. 그래서 신입이 들어오면 매월(또는 두세 달마다) 연차생성을
     * 다시 돌려야 숫자가 따라온다. 그때 생성방식은 반드시 '1'(기존 생성분까지 다시 계산)
     * 이어야 한다 — '2'(신규만)는 이미 발생행이 있는 사원을 건너뛰어 월차가 멈춘다.
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
