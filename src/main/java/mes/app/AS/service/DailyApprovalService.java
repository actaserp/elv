package mes.app.AS.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class DailyApprovalService {

    @Autowired
    SqlRunner sqlRunner;

    @Autowired
    NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    /** perid에서 앞의 'p' 제거 유틸 (tb_e080.appperid/repoperid는 순수 사번) */
    public String stripP(String perid) {
        return (perid != null && perid.startsWith("p")) ? perid.substring(1) : perid;
    }

    // ════════════════════════════════════════════════════════
    //  결재라인 등록 (approval_line_input)
    // ════════════════════════════════════════════════════════

    public List<Map<String, Object>> getApprovalLineList(String perid, String spjangcd, String comcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("perid",    stripP(perid));
        param.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT e.no, e.perid, e.kcperid,
                       j.pernm    AS kcpernm,
                       e.papercd,
                       c.com_cnam AS papernm,
                       sc.com_cnam AS gubunnm,
                       e.seq
                FROM TB_E064 e
                LEFT JOIN TB_JA001 j  ON j.spjangcd = e.spjangcd AND j.perid = 'p' + e.kcperid
                LEFT JOIN TB_CA510 c  ON c.com_cls = '620' AND c.com_code = e.papercd AND c.com_code <> '00'
                LEFT JOIN TB_CA510 sc ON sc.com_cls = '621' AND sc.com_code = e.gubun
                WHERE e.perid = :perid AND e.spjangcd = :spjangcd
                """;
        if (comcd != null && !comcd.isBlank()) {
            sql += " AND e.papercd = :comcd";
            param.addValue("comcd", comcd);
        }
        sql += " ORDER BY e.seq ASC";
        return sqlRunner.getRows(sql, param);
    }

    public Map<String, Object> getApprovalLineDetail(String no, String papercd, String perid, String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("no",       no);
        param.addValue("papercd",  papercd);
        param.addValue("perid",    stripP(perid));
        param.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT e.no, e.kcperid,
                       j.pernm    AS kcpernm,
                       e.gubun,
                       sc.com_cnam AS gubunnm,
                       e.seq, e.papercd, e.perid
                FROM TB_E064 e
                LEFT JOIN TB_JA001 j  ON j.spjangcd = e.spjangcd AND j.perid = 'p' + e.kcperid
                LEFT JOIN TB_CA510 sc ON sc.com_cls = '621' AND sc.com_code = e.gubun
                WHERE e.no = :no AND e.perid = :perid AND e.papercd = :papercd AND e.spjangcd = :spjangcd
                """;
        return sqlRunner.getRow(sql, param);
    }

    public void saveApprovalLine(String spjangcd, String custcd, String perid,
                                 String papercd, String kcperid, String gubun,
                                 String seq, String no, String indate) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("custcd",   custcd);
        param.addValue("perid",    stripP(perid));
        param.addValue("papercd",  papercd);
        param.addValue("kcperid",  stripP(kcperid));
        param.addValue("gubun",    gubun);
        param.addValue("seq",      seq);
        param.addValue("no",       no);
        param.addValue("indate",   indate);

        String checkSql = "SELECT COUNT(*) AS cnt FROM TB_E063 WHERE spjangcd=:spjangcd AND papercd=:papercd AND perid=:perid";
        List<Map<String, Object>> checkResult = sqlRunner.getRows(checkSql, param);
        int count = ((Number) checkResult.get(0).get("cnt")).intValue();
        if (count == 0) {
            namedParameterJdbcTemplate.update(
                "INSERT INTO TB_E063 (custcd,spjangcd,papercd,perid) VALUES (:custcd,:spjangcd,:papercd,:perid)", param);
        }
        namedParameterJdbcTemplate.update(
            "INSERT INTO TB_E064 (custcd,spjangcd,papercd,no,perid,seq,kcperid,gubun,indate) VALUES (:custcd,:spjangcd,:papercd,:no,:perid,:seq,:kcperid,:gubun,:indate)", param);
    }

    public void deleteApprovalLine(String perid, String papercd, String no, String kcperid, String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("perid",    stripP(perid));
        param.addValue("papercd",  papercd);
        param.addValue("no",       no);
        param.addValue("kcperid",  stripP(kcperid));
        param.addValue("spjangcd", spjangcd);

        namedParameterJdbcTemplate.update(
            "DELETE FROM TB_E064 WHERE perid=:perid AND papercd=:papercd AND no=:no AND kcperid=:kcperid AND spjangcd=:spjangcd", param);

        List<Map<String, Object>> countResult = sqlRunner.getRows(
            "SELECT COUNT(*) AS cnt FROM TB_E064 WHERE perid=:perid AND papercd=:papercd AND no=:no AND spjangcd=:spjangcd", param);
        int remaining = ((Number) countResult.get(0).get("cnt")).intValue();
        if (remaining == 0) {
            namedParameterJdbcTemplate.update(
                "DELETE FROM TB_E063 WHERE perid=:perid AND papercd=:papercd AND spjangcd=:spjangcd", param);
        }
    }

    public String getNextNo(String spjangcd, String perid, String papercd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("perid",    stripP(perid));
        param.addValue("papercd",  papercd);
        List<Map<String, Object>> result = sqlRunner.getRows(
            "SELECT ISNULL(MAX(CAST(no AS INT)),0)+1 AS nextno FROM TB_E064 WHERE spjangcd=:spjangcd AND perid=:perid AND papercd=:papercd", param);
        if (result != null && !result.isEmpty() && result.get(0).get("nextno") != null)
            return String.valueOf(result.get(0).get("nextno"));
        return "1";
    }

    // ════════════════════════════════════════════════════════
    //  결재라인 현황 (approval_line_list)
    // ════════════════════════════════════════════════════════

    public List<Map<String, Object>> getApprovalLinePersonList(String spjangcd, String comcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        String sql = """
                SELECT e.perid, j.pernm, e.papercd, c.com_cnam AS papernm
                FROM TB_E063 e
                LEFT JOIN TB_CA510 c ON c.com_cls='620' AND c.com_code=e.papercd AND c.com_code<>'00'
                LEFT JOIN TB_JA001 j ON j.spjangcd=e.spjangcd AND j.perid='p'+e.perid
                WHERE e.spjangcd = :spjangcd
                """;
        if (comcd != null && !comcd.isBlank()) {
            sql += " AND e.papercd = :comcd";
            param.addValue("comcd", comcd);
        }
        return sqlRunner.getRows(sql, param);
    }

    public List<Map<String, Object>> getApprovalLineDetail2(String spjangcd, String perid, String comcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("perid",    stripP(perid));
        param.addValue("papercd",  comcd);
        String sql = """
                SELECT e.no, e.kcperid AS kcpersonid, j.pernm AS kcpernm,
                       c.com_cnam AS gubunnm, e.seq, e.remark
                FROM TB_E064 e
                LEFT JOIN TB_JA001 j ON j.spjangcd=e.spjangcd AND j.perid='p'+e.kcperid
                LEFT JOIN TB_CA510 c ON c.com_cls='621' AND c.com_code=e.gubun AND c.com_code<>'00'
                WHERE e.spjangcd=:spjangcd AND e.perid=:perid AND e.papercd=:papercd
                ORDER BY CAST(e.seq AS INT) ASC
                """;
        return sqlRunner.getRows(sql, param);
    }

    // ════════════════════════════════════════════════════════
    //  결재 할 내역 (pending_approvals)
    // ════════════════════════════════════════════════════════

    public List<Map<String, Object>> getPendingApprovalList(String spjangcd, String perid,
                                                             String startDate, String endDate,
                                                             String searchPayment, String searchText) {
        // tb_e080.appperid는 'p' 없는 순수 사번 (예: '000', '001')
        String purePerid = stripP(perid);
        log.info("[결재 할 내역] spjangcd={}, perid(원본)={}, purePerid={}", spjangcd, perid, purePerid);

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd",  spjangcd);
        param.addValue("perid",     purePerid);
        param.addValue("startDate", startDate);
        param.addValue("endDate",   endDate);

        StringBuilder sql = new StringBuilder("""
                SELECT e.appnum, e.appperid, e.repoperid,
                       j.pernm     AS repopernm,
                       e.appgubun,
                       sc.com_cnam AS appgubun_display,
                       e.repodate, e.appdate, e.title, e.remark,
                       e.papercd,
                       ca.com_cnam AS papercd_name,
                       e.flag
                FROM tb_e080 e
                LEFT JOIN TB_JA001 j  ON j.perid='p'+e.repoperid AND j.spjangcd=e.spjangcd
                LEFT JOIN TB_CA510 sc ON sc.com_cls='621' AND sc.com_code=e.appgubun
                LEFT JOIN TB_CA510 ca ON ca.com_cls='620' AND ca.com_code=e.papercd
                WHERE e.spjangcd=:spjangcd AND e.appperid=:perid AND e.flag='1'
                  AND e.repodate BETWEEN :startDate AND :endDate
                """);

        if (searchPayment != null && !searchPayment.isBlank() && !"all".equals(searchPayment)) {
            sql.append(" AND e.appgubun = :searchPayment");
            param.addValue("searchPayment", searchPayment);
        }
        if (searchText != null && !searchText.isBlank()) {
            sql.append(" AND e.title LIKE :searchText");
            param.addValue("searchText", "%" + searchText + "%");
        }
        sql.append(" ORDER BY e.repodate DESC");

        List<Map<String, Object>> rows = sqlRunner.getRows(sql.toString(), param);
        log.info("[결재 할 내역] 조회결과 {}건", rows.size());
        return rows;
    }

    public List<Map<String, Object>> getApprovalCount(String spjangcd, String perid,
                                                       String startDate, String endDate) {
        String purePerid = stripP(perid);
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd",  spjangcd);
        param.addValue("perid",     purePerid);
        param.addValue("startDate", startDate);
        param.addValue("endDate",   endDate);

        String sql = """
                SELECT
                    (SELECT COUNT(*) FROM tb_e080 WHERE spjangcd=:spjangcd AND appperid=:perid AND appgubun='001' AND flag='1' AND repodate BETWEEN :startDate AND :endDate) AS appgubun1,
                    (SELECT COUNT(*) FROM tb_e080 WHERE spjangcd=:spjangcd AND appperid=:perid AND appgubun='101' AND flag='1' AND repodate BETWEEN :startDate AND :endDate) AS appgubun2,
                    (SELECT COUNT(*) FROM tb_e080 WHERE spjangcd=:spjangcd AND appperid=:perid AND appgubun='131' AND flag='1' AND repodate BETWEEN :startDate AND :endDate) AS appgubun3,
                    (SELECT COUNT(*) FROM tb_e080 WHERE spjangcd=:spjangcd AND appperid=:perid AND appgubun='201' AND flag='1' AND repodate BETWEEN :startDate AND :endDate) AS appgubun4
                FROM (SELECT 1 AS dummy) d
                """;
        return sqlRunner.getRows(sql, param);
    }

    public Map<String, Boolean> getApprovalInfo(String appnum, String perid) {
        String purePerid = stripP(perid);
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("appnum", appnum);
        param.addValue("perid",  purePerid);

        Map<String, Object> canRow = sqlRunner.getRow(
            "SELECT COUNT(*) AS cnt FROM tb_e080 WHERE appnum=:appnum AND appperid=:perid AND flag='1'", param);
        boolean canApprove = canRow != null && ((Number) canRow.get("cnt")).intValue() > 0;

        Map<String, Object> appRow = sqlRunner.getRow(
            "SELECT COUNT(*) AS cnt FROM tb_e080 WHERE appnum=:appnum AND appperid=:perid AND appdate IS NOT NULL AND appgubun!='001'", param);
        boolean isApproved = appRow != null && ((Number) appRow.get("cnt")).intValue() > 0;

        boolean canCancel = false;
        if (isApproved) {
            Map<String, Object> seqRow = sqlRunner.getRow(
                "SELECT seq FROM tb_e080 WHERE appnum=:appnum AND appperid=:perid", param);
            if (seqRow != null && seqRow.get("seq") != null) {
                String mySeq = String.valueOf(seqRow.get("seq"));
                MapSqlParameterSource p2 = new MapSqlParameterSource();
                p2.addValue("appnum", appnum);
                p2.addValue("mySeq",  mySeq);
                Map<String, Object> afterRow = sqlRunner.getRow(
                    "SELECT COUNT(*) AS cnt FROM tb_e080 WHERE appnum=:appnum AND seq>:mySeq AND appgubun='101'", p2);
                canCancel = afterRow == null || ((Number) afterRow.get("cnt")).intValue() == 0;
            }
        }

        Map<String, Boolean> result = new HashMap<>();
        result.put("canApprove", canApprove);
        result.put("isApproved", isApproved);
        result.put("canCancel",  canCancel);
        return result;
    }

    public boolean changeApprovalState(String appnum, String spjangcd, String perid,
                                        String action, String remark) {
        String purePerid = stripP(perid);
        Map<String, String> actionMap = Map.of(
            "approve", "101", "reject", "131", "hold", "201", "cancel", "001");
        String stateCode = actionMap.get(action);
        if (stateCode == null) return false;

        String today = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("appnum",    appnum);
        param.addValue("spjangcd",  spjangcd);
        param.addValue("perid",     purePerid);
        param.addValue("stateCode", stateCode);
        param.addValue("remark",    remark);
        param.addValue("today",     today);

        StringBuilder updateE080 = new StringBuilder(
            "UPDATE tb_e080 SET appgubun=:stateCode, remark=:remark, ");
        updateE080.append("001".equals(stateCode) ? "appdate=NULL " : "appdate=:today ");
        updateE080.append("WHERE appnum=:appnum AND spjangcd=:spjangcd AND appperid=:perid");
        int affected = namedParameterJdbcTemplate.update(updateE080.toString(), param);

        namedParameterJdbcTemplate.update(
            "UPDATE TB_E037 SET appgubun=:stateCode WHERE appnum=:appnum AND spjangcd=:spjangcd", param);

        // 결재 진행 순서는 tb_e080.appgubun 으로 판단한다 (파워빌더와 동일 규약).
        // flag 는 파워빌더가 전 행을 '1' 로 넣는 별개 용도의 컬럼이므로 여기서 건드리지 않는다.
        // (이전 updateNextFlag 는 flag 를 순번 게이트로 가정했는데, 실제 데이터에 flag='0' 행이
        //  하나도 없어 승인은 무동작, 승인취소는 앞 결재자의 flag 를 '0' 으로 만들어 목록에서
        //  문서를 사라지게 만들었다. seq 를 Number 로 캐스팅해 ClassCastException 도 발생했다.)
        return affected > 0;
    }

    // ════════════════════════════════════════════════════════
    //  결재 목록 (approval_history)
    // ════════════════════════════════════════════════════════

    public List<Map<String, Object>> getApprovalHistoryList(String spjangcd, String perid,
                                                             String startDate, String endDate,
                                                             String searchPayment, String searchText) {
        String purePerid = stripP(perid);
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd",  spjangcd);
        param.addValue("perid",     purePerid);
        param.addValue("startDate", startDate);
        param.addValue("endDate",   endDate);

        StringBuilder sql = new StringBuilder("""
                SELECT e.appnum, e.appperid, e.repoperid,
                       e.appgubun,
                       sc.com_cnam AS appgubun_display,
                       e.repodate, e.appdate, e.title, e.remark,
                       e.papercd,
                       ca.com_cnam AS papercd_name
                FROM tb_e080 e
                LEFT JOIN TB_CA510 sc ON sc.com_cls='621' AND sc.com_code=e.appgubun
                LEFT JOIN TB_CA510 ca ON ca.com_cls='620' AND ca.com_code=e.papercd
                WHERE e.spjangcd=:spjangcd AND e.repoperid=:perid AND e.flag='1'
                  AND e.repodate BETWEEN :startDate AND :endDate
                """);

        if (searchPayment != null && !searchPayment.isBlank() && !"all".equals(searchPayment)) {
            sql.append(" AND e.appgubun = :searchPayment");
            param.addValue("searchPayment", searchPayment);
        }
        if (searchText != null && !searchText.isBlank()) {
            sql.append(" AND e.title LIKE :searchText");
            param.addValue("searchText", "%" + searchText + "%");
        }
        sql.append(" ORDER BY e.repodate DESC");
        return sqlRunner.getRows(sql.toString(), param);
    }

    public List<Map<String, Object>> getApprovalLineByAppnum(String spjangcd, String appnum) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("appnum",   appnum);
        String sql = """
                SELECT e.appnum, e.seq, e.appperid,
                       j.pernm    AS apppernm,
                       jc.divinm,
                       pz.RSPNM   AS rspnm,
                       e.appgubun,
                       sc.com_cnam AS appgubun_display,
                       e.appdate, e.remark
                FROM tb_e080 e
                LEFT JOIN TB_JA001 j  ON j.perid='p'+e.appperid AND j.spjangcd=e.spjangcd
                LEFT JOIN TB_JC002 jc ON jc.divicd=j.divicd AND jc.spjangcd=j.spjangcd
                LEFT JOIN TB_PZ001 pz ON pz.RSPCD=j.rspcd
                LEFT JOIN TB_CA510 sc ON sc.com_cls='621' AND sc.com_code=e.appgubun
                WHERE e.spjangcd=:spjangcd AND e.appnum=:appnum
                ORDER BY e.seq ASC
                """;
        return sqlRunner.getRows(sql, param);
    }

    // ════════════════════════════════════════════════════════
    //  결재 할 내역 — 업무일지 상세 조회
    // ════════════════════════════════════════════════════════

    /** appnum으로 업무일지 헤드 + 상세 조회 */
    public Map<String, Object> getDailyDetailByAppnum(String spjangcd, String appnum) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("appnum",   appnum);

        // 1) tb_e080에서 repoperid, repodate 가져오기
        String e080Sql = """
                SELECT TOP 1 repoperid, repodate
                FROM tb_e080
                WHERE spjangcd = :spjangcd AND appnum = :appnum
                """;
        Map<String, Object> e080Row = sqlRunner.getRow(e080Sql, param);
        if (e080Row == null) return null;

        String repoperid = (String) e080Row.get("repoperid");
        String repodate  = String.valueOf(e080Row.get("repodate"));

        MapSqlParameterSource param2 = new MapSqlParameterSource();
        param2.addValue("spjangcd",  spjangcd);
        param2.addValue("perid",     repoperid);
        param2.addValue("rptdate",   repodate);

        // 2) TB_E037 헤드 + 사원 정보
        String headSql = """
                SELECT
                    h.rptdate,
                    h.perid,
                    h.appgubun,
                    j.pernm,
                    pz.RSPNM  AS rspnm,
                    jc.divinm
                FROM TB_E037 h
                LEFT JOIN TB_JA001 j  ON j.perid    = 'p' + h.perid AND j.spjangcd = h.spjangcd
                LEFT JOIN TB_JC002 jc ON j.divicd   = jc.divicd AND jc.spjangcd = j.spjangcd
                LEFT JOIN TB_PZ001 pz ON j.rspcd    = pz.RSPCD
                WHERE h.spjangcd = :spjangcd
                  AND h.perid    = :perid
                  AND h.rptdate  = :rptdate
                """;
        Map<String, Object> head = sqlRunner.getRow(headSql, param2);

        // 3) TB_E038 상세 목록
        String detailSql = """
                SELECT
                    e.rptnum,
                    e.wkcd,
                    b.businm,
                    e.actcd,
                    e.actnm,
                    e.frtime,
                    e.totime,
                    e.equpcd,
                    m.equpnm,
                    e.remark
                FROM TB_E038 e
                LEFT JOIN TB_E021 b ON b.custcd = e.custcd AND b.spjangcd = e.spjangcd AND b.busicd = e.wkcd
                LEFT JOIN TB_E611 m ON m.equpcd = e.equpcd AND m.actcd = e.actcd AND m.spjangcd = e.spjangcd
                WHERE e.spjangcd = :spjangcd
                  AND e.perid    = :perid
                  AND e.rptdate  = :rptdate
                ORDER BY e.rptnum ASC
                """;
        List<Map<String, Object>> details = sqlRunner.getRows(detailSql, param2);

        Map<String, Object> result = new java.util.HashMap<>();
        result.put("head",    head);
        result.put("details", details);
        return result;
    }

    // ════════════════════════════════════════════════════════
    //  공용
    // ════════════════════════════════════════════════════════

    public String getCustcd(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        Map<String, Object> row = sqlRunner.getRow("SELECT custcd FROM tb_xa012 WHERE spjangcd=:spjangcd", param);
        return row != null ? String.valueOf(row.getOrDefault("custcd", "")) : "";
    }
}
