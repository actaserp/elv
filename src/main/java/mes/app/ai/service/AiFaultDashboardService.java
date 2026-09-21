package mes.app.ai.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * AI고장분석대시보드 — 반복고장 패턴분석 (기능정의서 「반복고장 패턴 분석」 v1.0)
 *
 * 반복고장 = 같은 호기(현장 actcd + 호기 equpcd)에서 같은 고장부위가 조회기간 안에 반복기준 이상 발생.
 * 원본은 고장처리 TB_E411, 기준일은 완료일자(compdate).
 *
 * 정의서는 야간 배치 집계 테이블을 제안했지만, 가장 큰 사업체(ZQ 약 9.8만 건)에서도
 * 전체기간 GROUP BY 가 30ms 안쪽이라 실시간으로 집계한다.
 *
 * 주의
 * - TB_E411.contcd 는 비어 있다. 고장내용은 접수 TB_E401 과 (recedate, recenum, actcd) 로 연결해 가져온다.
 * - 고장부위(gregicd)가 빈 과거 건은 고장내용 코드로 대신 묶는다 (정의서 3-1).
 */
@Service
public class AiFaultDashboardService {

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired
    SqlRunner sqlRunner;

    /** 조회 조건 공통 — 그룹 키는 'G'+고장부위코드 또는 'C'+고장내용코드 */
    private static final String BASE_CTE = """
            WITH base AS (
                SELECT e.actcd,
                       ISNULL(e.actnm, '')  AS actnm,
                       ISNULL(e.equpcd, '') AS equpcd,
                       ISNULL(e.equpnm, '') AS equpnm,
                       e.compdate,
                       e.compnum,
                       ISNULL(e.resultcd, '') AS resultcd,
                       CASE WHEN :groupBy = 'cont'             THEN 'C' + ISNULL(a.contcd, '')
                            WHEN ISNULL(e.gregicd, '') <> ''   THEN 'G' + e.gregicd
                            ELSE 'C' + ISNULL(a.contcd, '') END AS gkey,
                       e.spjangcd, e.recedate, e.recenum, e.comptime, e.gregicd, e.regicd, e.remocd, e.resucd,
                       e.perid, e.remoremark, e.resuremark, a.contcd, a.contents
                  FROM TB_E411 e WITH(NOLOCK)
                  LEFT JOIN TB_E401 a WITH(NOLOCK)
                         ON a.spjangcd = e.spjangcd
                        AND a.recedate = e.recedate
                        AND a.recenum  = e.recenum
                        AND a.actcd    = e.actcd
                 WHERE e.spjangcd = :spjangcd
                   AND LEN(e.compdate) = 8
                   AND e.compdate BETWEEN :fromDate AND :toDate
                   AND (:actnm = '' OR ISNULL(e.actnm, '') LIKE '%' + :actnm + '%')
            )
            """;

    public Map<String, Object> getSummary(String spjangcd, String fromDate, String toDate,
                                          String actnm, int threshold, String groupBy) {

        MapSqlParameterSource param = baseParam(spjangcd, fromDate, toDate, actnm, groupBy);

        // 최근 30일/90일은 오늘이 아니라 조회 종료일 기준 (과거 기간을 볼 때도 의미가 맞도록)
        LocalDate end = LocalDate.parse(toDate, YMD);
        if (end.isAfter(LocalDate.now())) end = LocalDate.now();
        param.addValue("d30", end.minusDays(29).format(YMD));
        param.addValue("d90", end.minusDays(89).format(YMD));

        String groupSql = BASE_CTE + """
                SELECT b.actcd,
                       MAX(b.actnm)  AS actnm,
                       b.equpcd,
                       MAX(b.equpnm) AS equpnm,
                       b.gkey,
                       COUNT(*)      AS cnt,
                       MAX(b.compdate) AS lastdate,
                       SUM(CASE WHEN b.compdate >= :d30 THEN 1 ELSE 0 END) AS cnt30,
                       SUM(CASE WHEN b.compdate >= :d90 THEN 1 ELSE 0 END) AS cnt90
                  FROM base b
                 GROUP BY b.actcd, b.equpcd, b.gkey
                """;
        List<Map<String, Object>> groups = sqlRunner.getRows(groupSql, param);
        if (groups == null) groups = List.of();

        String resultSql = BASE_CTE + """
                SELECT b.resultcd,
                       MAX(ISNULL(r.resultnm, '')) AS resultnm,
                       COUNT(*) AS cnt
                  FROM base b
                  LEFT JOIN TB_E015 r WITH(NOLOCK) ON r.spjangcd = b.spjangcd AND r.resultcd = b.resultcd
                 GROUP BY b.resultcd
                 ORDER BY cnt DESC
                """;
        List<Map<String, Object>> resultRows = sqlRunner.getRows(resultSql, param);
        if (resultRows == null) resultRows = List.of();

        Map<String, String> names = codeNames(spjangcd);

        long total = 0;
        Map<String, Long> partCount = new HashMap<>();
        List<Map<String, Object>> repeats = new ArrayList<>();

        for (Map<String, Object> g : groups) {
            int cnt = toInt(g.get("cnt"));
            String gkey = str(g.get("gkey"));
            total += cnt;
            partCount.merge(gkey, (long) cnt, Long::sum);

            if (cnt < threshold) continue;

            int cnt30 = toInt(g.get("cnt30"));
            int cnt90 = toInt(g.get("cnt90"));
            double raw = cnt * 0.6 + (cnt30 > 0 ? 0.3 : 0) + (cnt - threshold) * 0.1;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("actcd", str(g.get("actcd")));
            row.put("actnm", str(g.get("actnm")));
            row.put("equpcd", str(g.get("equpcd")));
            row.put("equpnm", str(g.get("equpnm")));
            row.put("gkey", gkey);
            row.put("partnm", names.getOrDefault(gkey, "(미분류)"));
            row.put("cnt", cnt);
            row.put("lastdate", str(g.get("lastdate")));
            row.put("cnt30", cnt30);
            row.put("cnt90", cnt90);
            row.put("raw", raw);
            // 예방점검 권장: 반복기준+2회 이상이거나 최근 90일 3회 이상 (정의서 3-2)
            row.put("recommend", (cnt >= threshold + 2 || cnt90 >= 3) ? "권장" : "관찰");
            repeats.add(row);
        }

        repeats.sort((a, b) -> {
            int c = Integer.compare(toInt(b.get("cnt")), toInt(a.get("cnt")));
            if (c != 0) return c;
            c = str(b.get("lastdate")).compareTo(str(a.get("lastdate")));
            if (c != 0) return c;
            return str(a.get("actnm")).compareTo(str(b.get("actnm")));
        });

        // 우선순위점수: 정의서 산정식 원점수를 조회 결과 최고값 대비 0~100 으로 환산
        double maxRaw = repeats.stream().mapToDouble(r -> (double) r.get("raw")).max().orElse(0);
        long repeatCnt = 0;
        for (Map<String, Object> r : repeats) {
            double raw = (double) r.remove("raw");
            r.put("score", maxRaw > 0 ? (int) Math.round(raw / maxRaw * 100) : 0);
            repeatCnt += toInt(r.get("cnt"));
        }

        List<Map<String, Object>> partDist = new ArrayList<>();
        partCount.forEach((k, v) -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("gkey", k);
            p.put("partnm", names.getOrDefault(k, "(미분류)"));
            p.put("cnt", v);
            partDist.add(p);
        });
        partDist.sort((a, b) -> Long.compare((long) b.get("cnt"), (long) a.get("cnt")));

        List<Map<String, Object>> resultRatio = new ArrayList<>();
        for (Map<String, Object> r : resultRows) {
            Map<String, Object> p = new LinkedHashMap<>();
            String nm = str(r.get("resultnm"));
            p.put("resultnm", nm.isEmpty() ? "(미입력)" : nm);
            p.put("cnt", toInt(r.get("cnt")));
            resultRatio.add(p);
        }

        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("total", total);
        kpi.put("repeatCnt", repeatCnt);
        kpi.put("repeatRate", total > 0 ? Math.round(repeatCnt * 1000.0 / total) / 10.0 : 0);
        kpi.put("repeatGroups", repeats.size());
        if (!repeats.isEmpty()) {
            Map<String, Object> top = repeats.get(0);
            kpi.put("topEqup", top.get("actnm") + " " + top.get("equpnm"));
            kpi.put("topPart", top.get("partnm"));
            kpi.put("topCnt", top.get("cnt"));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kpi", kpi);
        data.put("top10", repeats.subList(0, Math.min(10, repeats.size())));
        data.put("detail", repeats);
        data.put("partDist", partDist);
        data.put("resultRatio", resultRatio);
        return data;
    }

    /** 반복고장 한 줄(호기+부위)의 개별 처리이력 */
    public List<Map<String, Object>> getHistory(String spjangcd, String fromDate, String toDate,
                                                String groupBy, String actcd, String equpcd, String gkey) {
        MapSqlParameterSource param = baseParam(spjangcd, fromDate, toDate, "", groupBy);
        param.addValue("actcd", actcd);
        param.addValue("equpcd", equpcd == null ? "" : equpcd);
        param.addValue("gkey", gkey);

        String sql = BASE_CTE + """
                SELECT b.compdate, b.comptime, b.recedate, b.actnm, b.equpnm,
                       ISNULL(ct.contnm, '')  AS contnm,
                       CAST(b.contents AS varchar(2000)) AS contents,
                       ISNULL(gr.greginm, '') AS greginm,
                       ISNULL(eg.reginm, '')  AS reginm,
                       ISNULL(em.remonm, '')  AS remonm,
                       CAST(b.remoremark AS varchar(2000)) AS remoremark,
                       ISNULL(es.resunm, '')  AS resunm,
                       CAST(b.resuremark AS varchar(2000)) AS resuremark,
                       ISNULL(er.resultnm, '') AS resultnm,
                       ISNULL(p.pernm, '')    AS pernm
                  FROM base b
                  LEFT JOIN TB_E010 ct WITH(NOLOCK) ON ct.spjangcd = b.spjangcd AND ct.contcd   = b.contcd
                  LEFT JOIN TB_E013 gr WITH(NOLOCK) ON gr.spjangcd = b.spjangcd AND gr.gregicd  = b.gregicd
                  LEFT JOIN TB_E014 eg WITH(NOLOCK) ON eg.spjangcd = b.spjangcd AND eg.gregicd  = b.gregicd AND eg.regicd = b.regicd
                  LEFT JOIN TB_E011 em WITH(NOLOCK) ON em.spjangcd = b.spjangcd AND em.remocd   = b.remocd
                  LEFT JOIN TB_E012 es WITH(NOLOCK) ON es.spjangcd = b.spjangcd AND es.resucd   = b.resucd
                  LEFT JOIN TB_E015 er WITH(NOLOCK) ON er.spjangcd = b.spjangcd AND er.resultcd = b.resultcd
                  LEFT JOIN TB_JA001 p WITH(NOLOCK) ON p.spjangcd  = b.spjangcd AND p.perid     = 'p' + b.perid
                 WHERE b.actcd = :actcd
                   AND b.equpcd = :equpcd
                   AND b.gkey = :gkey
                 ORDER BY b.compdate DESC, b.compnum DESC
                """;
        return sqlRunner.getRows(sql, param);
    }

    private MapSqlParameterSource baseParam(String spjangcd, String fromDate, String toDate,
                                            String actnm, String groupBy) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("fromDate", fromDate);
        param.addValue("toDate", toDate);
        param.addValue("actnm", actnm == null ? "" : actnm.trim());
        param.addValue("groupBy", "cont".equals(groupBy) ? "cont" : "greg");
        return param;
    }

    /** 'G'+고장부위코드 / 'C'+고장내용코드 → 이름 */
    private Map<String, String> codeNames(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource("spjangcd", spjangcd);
        Map<String, String> names = new HashMap<>();
        for (Map<String, Object> r : nz(sqlRunner.getRows(
                "SELECT gregicd AS cd, greginm AS nm FROM TB_E013 WITH(NOLOCK) WHERE spjangcd = :spjangcd", param))) {
            names.put("G" + str(r.get("cd")), str(r.get("nm")));
        }
        for (Map<String, Object> r : nz(sqlRunner.getRows(
                "SELECT contcd AS cd, contnm AS nm FROM TB_E010 WITH(NOLOCK) WHERE spjangcd = :spjangcd", param))) {
            names.put("C" + str(r.get("cd")), str(r.get("nm")));
        }
        return names;
    }

    private static List<Map<String, Object>> nz(List<Map<String, Object>> rows) {
        return rows == null ? List.of() : rows;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        try {
            return o == null ? 0 : Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
