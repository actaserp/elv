package mes.app.clock.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ClockYearlyService {

    @Autowired
    SqlRunner sqlRunner;

    //read
    public List<Map<String, Object>> getYearlyList(String year,String name, String spjangcd,String startdate,String rtflag) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("year", year);
        dicParam.addValue("spjangcd", spjangcd);
        dicParam.addValue("startdate", startdate);

        String sql = """
                SELECT
                       ROW_NUMBER() OVER (ORDER BY p.jik_id) AS rownum,
                       p.id,
                       p.[Name] AS person_name,
                       s.[Value] AS jik_id,
                       p.rtdate AS rtdate,
                       (COALESCE(tb209.ewolnum, 0) + COALESCE(tb209.holinum, 0) - COALESCE(tb204.daynum, 0)) AS restnum,
                       tb209.ewolnum,
                       tb209.holinum,
                       tb204.daynum,
                       pz.RSPNM
                   FROM person p
                   LEFT JOIN (
                       SELECT [Code], [Value]
                       FROM sys_code
                       WHERE [CodeType] = 'jik_type'
                   ) s ON s.[Code] = p.jik_id
                   LEFT JOIN (
                       SELECT
                           TRY_CAST(perid AS INT) AS perid,
                           SUM(daynum) AS daynum
                       FROM tb_pb204
                       WHERE fixflag = '1'
                         AND TRY_CAST(perid AS INT) IS NOT NULL
                       GROUP BY TRY_CAST(perid AS INT)
                   ) tb204
                   ON p.id = tb204.perid
                
                   LEFT JOIN (
                       SELECT t.*
                       FROM tb_pb209 t
                       INNER JOIN (
                           SELECT
                               TRY_CAST(perid AS INT) AS perid,
                               MAX(reqdate) AS max_reqdate
                           FROM tb_pb209
                           WHERE TRY_CAST(perid AS INT) IS NOT NULL
                           GROUP BY TRY_CAST(perid AS INT)
                       ) latest
                           ON TRY_CAST(t.perid AS INT) = latest.perid
                          AND t.reqdate = latest.max_reqdate
                   ) tb209
                   ON p.id = TRY_CAST(tb209.perid AS INT)
                   LEFT JOIN auth_user au ON au.personid = p.id
                   left join tb_xusers u on u.userid =au.username and au.last_name =u.pernm
                   LEFT JOIN tb_ja001 j  ON j.perid = CONCAT('p', u.perid)
                   LEFT JOIN tb_jc002 jc ON j.divicd = jc.divicd
                   LEFT JOIN tb_pz001 pz  ON j.rspcd = pz.RSPCD
                   WHERE 1 = 1
                """;

        if (rtflag != null && !rtflag.isEmpty()) {
            sql += " and rtflag = :rtflag ";
            dicParam.addValue("rtflag",  rtflag);
        }

        if (name != null && !name.isEmpty()) {
            sql += " AND p.[Name] LIKE '%' + :name + '%' ";
            dicParam.addValue("name", "%" + name + "%");
        }
        sql  +="""
            GROUP BY
                p.id, s.[Value], tb209.ewolnum, tb209.holinum, tb204.daynum, p.[Name], p.rtdate, p.jik_id, pz.RSPNM
            ORDER BY p.jik_id
            """;

        List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);

        return items;
    }

    //연차생성
    public List<Map<String, Object>> YearlyCreate(String year,String spjangcd,String startdate, String name) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("year", year);
        dicParam.addValue("spjangcd", spjangcd);
        dicParam.addValue("startdate", startdate);

        String personStr = (name!= null) ? name : "";
        dicParam.addValue("name", personStr);

        String sql = """
                WITH base AS (
                    SELECT
                        p.spjangcd,
                        p.[Name] AS person_name,
                        CONVERT(DATE, p.rtdate, 112) AS rtdate,
                        p.id,
                        CONVERT(DATE, CAST(CAST(:year AS INT) - 1 AS VARCHAR) + '-12-31', 120) AS end_date,
                        CONVERT(DATE, :startdate, 112) AS start_date,
                        ROW_NUMBER() OVER (ORDER BY CONVERT(DATE, p.rtdate, 112)) AS rownum,
                        p.jik_id
                    FROM person p
                    WHERE CONVERT(DATE, p.rtdate, 112) <= CONVERT(DATE, CAST(:year AS VARCHAR) + '1231', 112)
                ),
                month_calc AS (
                    SELECT *,
                        CASE
                            WHEN end_date >= rtdate THEN
                                DATEDIFF(month, rtdate, end_date)
                            ELSE 0
                        END AS llMonth
                    FROM base
                ),
                holiday_calc AS (
                    SELECT *,
                        CASE
                            WHEN llMonth < 12  THEN 0
                            WHEN llMonth = 12  THEN 1
                            WHEN llMonth <= 24  THEN 15
                            WHEN llMonth <= 48  THEN 16
                            WHEN llMonth <= 72  THEN 17
                            WHEN llMonth <= 96  THEN 18
                            WHEN llMonth <= 120 THEN 19
                            WHEN llMonth <= 144 THEN 20
                            WHEN llMonth <= 168 THEN 21
                            WHEN llMonth <= 192 THEN 22
                            WHEN llMonth <= 216 THEN 23
                            WHEN llMonth <= 240 THEN 24
                            ELSE FLOOR((llMonth / 12.0) * 15)
                        END AS llHoliynum
                    FROM month_calc
                )
                SELECT
                    h.spjangcd,
                    h.id,
                    h.person_name,
                    h.rtdate,
                    h.end_date,
                    h.llMonth,
                    h.llHoliynum AS holinum,
                    COALESCE(l.cnt, 0) AS ewolnum,
                    (h.llHoliynum + COALESCE(l.cnt, 0)) AS restnum,
                    h.rownum,
                    s.[Value] AS jik_id
                FROM holiday_calc h
                LEFT JOIN (
                    SELECT [Code], [Value]
                    FROM sys_code
                    WHERE [CodeType] = 'jik_type'
                ) s ON s.[Code] = h.jik_id
                OUTER APPLY (                          -- ← LATERAL → OUTER APPLY
                    SELECT TOP 1 restnum AS cnt        -- ← LIMIT 1 → TOP 1
                    FROM TB_PB209
                    WHERE spjangcd = 'ZZ'
                      AND perid = h.id
                      AND CAST(LEFT(reqdate, 4) AS INT) = CAST(:year AS INT) - 1
                    ORDER BY reqdate DESC
                ) l
                WHERE h.spjangcd = :spjangcd
                ORDER BY h.rtdate
                """;

        List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);

        return items;
    }


    // 월차생성
    public List<Map<String, Object>> MonthlyCreate(String year, String spjangcd, String startdate, String name) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("year", year);
        dicParam.addValue("spjangcd", spjangcd);
        dicParam.addValue("startdate", startdate);

        String personStr = (name != null) ? name : "";
        dicParam.addValue("name", personStr);

        String sql = """
            WITH base AS (
                SELECT
                    p.id AS personid,
                    p.spjangcd,
                    p.[Name] AS person_name,
                    CONVERT(DATE, p.rtdate, 112) AS rtdate,
                    CASE
                        WHEN FORMAT(CAST(GETDATE() AS DATE), 'yyyyMM') = :startdate
                            THEN CAST(GETDATE() AS DATE)
                        ELSE DATEADD(day, -1,
                                DATEADD(month, 1,
                                    DATEADD(month, DATEDIFF(month, 0, CONVERT(DATE, :startdate + '01', 112)), 0)
                                )
                             )
                    END AS nowdate
                FROM person p
                WHERE CONVERT(DATE, p.rtdate, 112) <= CONVERT(DATE, '20251231', 112)
            ),
            month_list AS (
                SELECT CAST(MIN(CONVERT(DATE, p.rtdate, 112)) AS DATE) AS month_start
                FROM person p
                UNION ALL
                SELECT CAST(DATEADD(month, 1, month_start) AS DATE)
                FROM month_list
                WHERE month_start < DATEADD(month, DATEDIFF(month, 0, CONVERT(DATE, :startdate + '01', 112)), 0)
            ),
            attendance_summary AS (
                SELECT
                    pb.perid,
                    pb.workym AS ym,
                    MAX(COALESCE(pb.jitime, 0) + COALESCE(pb.jotime, 0) + COALESCE(pb.abtime, 0)) AS bad_record
                FROM tb_pb203 pb
                GROUP BY pb.perid, pb.workym
            ),
            valid_months AS (
                SELECT
                    b.personid,
                    COUNT(*) AS valid_month_count
                FROM base b
                CROSS JOIN month_list ml
                LEFT JOIN attendance_summary a
                    ON a.perid = b.personid
                    AND a.ym = FORMAT(ml.month_start, 'yyyyMM')
                WHERE (a.bad_record IS NULL OR a.bad_record = 0)
                  AND ml.month_start >= b.rtdate
                  AND ml.month_start <= b.nowdate
                GROUP BY b.personid
            ),
            leave_used AS (
                SELECT
                    perid,
                    COALESCE(SUM(daynum), 0) AS used_days
                FROM tb_pb204
                WHERE LEFT(CAST(reqdate AS VARCHAR), 4) = CAST(CAST(:year AS INT) - 1 AS VARCHAR)
                GROUP BY perid
            ),
            final_calc AS (
                SELECT
                    b.spjangcd,
                    b.personid,
                    b.person_name,
                    b.rtdate,
                    b.nowdate,
                    COALESCE(vm.valid_month_count, 0) AS valid_month_count,
                    CASE WHEN COALESCE(vm.valid_month_count, 0) < 15
                         THEN COALESCE(vm.valid_month_count, 0)
                         ELSE 15
                    END AS holinum,
                    COALESCE(lu.used_days, 0) AS daynum,
                    CASE WHEN COALESCE(vm.valid_month_count, 0) < 15
                         THEN COALESCE(vm.valid_month_count, 0)
                         ELSE 15
                    END - COALESCE(lu.used_days, 0) AS restnum,
                    DATEDIFF(month, b.rtdate, b.nowdate) AS duration_months
                FROM base b
                LEFT JOIN valid_months vm ON b.personid = vm.personid
                LEFT JOIN leave_used lu ON b.personid = lu.perid
            )
            SELECT
                f.spjangcd,
                f.personid AS id,
                f.person_name,
                f.rtdate,
                f.nowdate,
                f.valid_month_count AS no_month_count,
                f.holinum,
                f.daynum,
                f.restnum
            FROM final_calc f
            WHERE f.spjangcd = 'ZZ'
              AND f.duration_months < 12
            ORDER BY f.personid
            OPTION (MAXRECURSION 1000)
            """;

        List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
        return items;
    }


    public List<Map<String, Object>> getYearlyDetail(Integer id, String year) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("id", id);
        dicParam.addValue("year", year);

        String sql = """
            WITH latest_pb209 AS (
                SELECT
                    tb.reqdate,
                    tb.perid,
                    tb.ewolnum,
                    tb.holinum,
                    tb.restnum
                FROM tb_pb209 tb
                JOIN (
                    SELECT perid, MAX(reqdate) AS max_reqdate
                    FROM tb_pb209
                    WHERE perid = :id
                      AND LEFT(CAST(reqdate AS VARCHAR), 4) = :year
                    GROUP BY perid
                ) latest ON tb.perid = latest.perid AND tb.reqdate = latest.max_reqdate
            ),
            pb204_with_running_total AS (
                SELECT
                    t.reqdate,
                    t.perid,
                    t.frdate,
                    t.todate,
                    t.daynum,
                    t.workcd,
                    w.worknm,
                    SUM(COALESCE(t.daynum, 0)) OVER (
                        ORDER BY t.reqdate
                        ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                    ) AS cum_daynum
                FROM tb_pb204 t
                LEFT JOIN tb_pb210 w ON t.workcd = w.workcd
                WHERE t.perid = :id
                  AND t.fixflag = '1'
                  AND LEFT(CAST(t.reqdate AS VARCHAR), 4) = :year
            ),
            unioned_data AS (
                SELECT
                    reqdate,
                    perid,
                    NULL AS frdate,
                    NULL AS todate,
                    NULL AS daynum,
                    NULL AS workcd,
                    N'생성' AS worknm,
                    ewolnum,
                    holinum,
                    restnum
                FROM latest_pb209

                UNION ALL

                SELECT
                    p.reqdate,
                    p.perid,
                    p.frdate,
                    p.todate,
                    p.daynum,
                    p.workcd,
                    p.worknm,
                    0.00 AS ewolnum,
                    0.00 AS holinum,
                    (l.restnum - p.cum_daynum) AS restnum
                FROM pb204_with_running_total p
                CROSS JOIN latest_pb209 l
            )
            SELECT
                ROW_NUMBER() OVER (ORDER BY reqdate) - 1 AS rownum,
                *
            FROM unioned_data
            ORDER BY reqdate
            """;

        List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
        return items;
    }


}
