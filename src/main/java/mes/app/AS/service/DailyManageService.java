package mes.app.AS.service;

import mes.app.files.NcpObjectStorageService;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class DailyManageService {

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
            String spjangcd) {

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
            sql += " AND j.pernm LIKE :pernm";
            param.addValue("pernm", "%" + pernm.trim() + "%");
        }

        sql += """
                GROUP BY
                    h.custcd, h.spjangcd, h.rptdate, h.perid,
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
                    b.businm,
                    e.actnm,
                    e.frtime,
                    e.totime,
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

    // ── 업무일지 삭제 ─────────────────────────────────────────
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
}
