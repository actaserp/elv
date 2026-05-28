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

    // ── 업무일지 목록 조회 (TB_E038) ──────────────────────────
    // year + month 기준으로 해당 월 전체 조회
    public List<Map<String, Object>> getList(
            String year,
            String month,
            String pernm,
            String spjangcd) {

        // yyyyMM01 ~ yyyyMM말일 계산
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
                    e.custcd,
                    e.spjangcd,
                    e.rptdate,
                    e.perid,
                    e.rptnum,
                    j.pernm,
                    pz.RSPNM    AS clanm,
                    jc.divinm,
                    b.businm,
                    e.actnm,
                    e.frtime,
                    e.totime,
                    e.equpcd,
                    m.equpnm,
                    e.remark,
                    e.filesvnm,
                    e.filepath
                FROM TB_E038 e
                LEFT JOIN TB_JA001 j  ON j.perid    = 'p' + e.perid
                                     AND j.spjangcd  = e.spjangcd
                LEFT JOIN TB_E611  m  ON m.equpcd   = e.equpcd
                LEFT JOIN TB_JC002 jc ON j.divicd   = jc.divicd
                LEFT JOIN TB_PZ001 pz ON j.rspcd    = pz.RSPCD
                LEFT JOIN TB_E021  b  ON b.custcd   = e.custcd
                                     AND b.spjangcd  = e.spjangcd
                                     AND b.busicd    = e.wkcd
                WHERE e.spjangcd = :spjangcd
                  AND e.rptdate BETWEEN :startDate AND :endDate
                """;

        if (pernm != null && !pernm.isBlank()) {
            sql += " AND j.pernm LIKE :pernm";
            param.addValue("pernm", "%" + pernm.trim() + "%");
        }

        sql += " ORDER BY e.rptdate DESC, j.pernm ASC, e.rptnum ASC";

        return sqlRunner.getRows(sql, param);
    }

    // ── 업무일지 삭제 ─────────────────────────────────────────
    // 1. TB_E038 해당 건 filesvnm 조회 → NCP 파일 삭제
    // 2. TB_E038 DELETE
    // 3. TB_E037 HEAD: 해당 날짜에 잔여 TB_E038 없으면 DELETE
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
