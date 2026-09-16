package mes.app.transaction.service;


import mes.app.util.UtilClass;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 매입현황 — 파워빌더 '비용발생현황'(w_tb_ca640w) 과 같은 자료를 본다.
 *
 * ERP 에서 매입은 '비용' 이라고 부른다. 헤드가 TB_CA640, 상세가 TB_CA641 이고
 * 화면 한 줄은 상세 한 줄이다. (경기 2026년 헤드 225건의 금액이 상세 합계와 모두 일치한다)
 *
 * 예전 코드는 sports 의 tb_invoicement/tb_invoicedetail 을 읽었는데 사업체 DB 에는 그 테이블이 없다.
 *
 * 파워빌더 원본의 조회조건은 기간·매입구분(gubun)·부서(divicd)·공제구분(taxreclafi)·
 * 거래처·비용항목(artcd)·bhflag 이며, 값이 '%' 면 전체를 뜻한다. 웹에서는 빈 값이 전체다.
 */
@Service
public class PurchaseService {


    @Autowired
    SqlRunner sqlRunner;

    /** spjangcd 로 custcd 조회 (파워빌더의 as_custcd) */
    public String getCustcd(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        Map<String, Object> row = sqlRunner.getRow(
                "SELECT custcd FROM tb_xa012 WHERE spjangcd = :spjangcd", param);
        if (row == null || row.get("custcd") == null) return null;
        return String.valueOf(row.get("custcd")).trim();
    }

    private MapSqlParameterSource commonParam(Map<String, Object> parameter, String custcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd", custcd);
        param.addValue("spjangcd", UtilClass.getStringSafe(parameter.get("spjangcd")));
        param.addValue("searchfrdate", UtilClass.getStringSafe(parameter.get("searchfrdate")));
        param.addValue("searchtodate", UtilClass.getStringSafe(parameter.get("searchtodate")));
        // 파워빌더는 거래처를 코드/상호 부분일치로 찾는다. 화면 팝업으로 고르면 코드가 그대로 들어온다.
        param.addValue("cltcd",      UtilClass.getStringSafe(parameter.get("cltcd")));
        param.addValue("gubun",      UtilClass.getStringSafe(parameter.get("gubun")));
        param.addValue("divicd",     UtilClass.getStringSafe(parameter.get("divicd")));
        param.addValue("artcd",      UtilClass.getStringSafe(parameter.get("artcd")));
        param.addValue("taxreclafi", UtilClass.getStringSafe(parameter.get("taxreclafi")));
        param.addValue("bhflag",     UtilClass.getStringSafe(parameter.get("bhflag")));
        return param;
    }

    /** 파워빌더와 동일한 조회조건. 빈 값이면 해당 조건을 걸지 않는다. */
    private static final String WHERE_FILTER = """
             AND (:gubun      = '' OR a.gubun      = :gubun)
             AND (:divicd     = '' OR a.divicd     = :divicd)
             AND (:taxreclafi = '' OR a.taxreclafi = :taxreclafi)
             AND (:artcd      = '' OR a.artcd      = :artcd)
             AND (:bhflag     = '' OR a.bhflag     = :bhflag)
             AND (:cltcd      = '' OR a.mijcltcd LIKE '%' + :cltcd + '%' OR a.mijcltnm LIKE '%' + :cltcd + '%')
            """;

    /** 매입 상세 목록 (파워빌더 화면 그리드) */
    public List<Map<String, Object>> getList(Map<String, Object> parameter) {
        String custcd = getCustcd(UtilClass.getStringSafe(parameter.get("spjangcd")));
        if (custcd == null) return List.of();

        String sql = """
                SELECT
                    STUFF(STUFF(b.mijdate, 5, 0, '-'), 8, 0, '-') AS misdate,
                    a.mijdate      AS mijdate,
                    a.mijnum       AS mijnum,
                    b.seq          AS seq,
                    ISNULL(g.com_cnam, '') AS misgubun,
                    a.gubun        AS gubuncd,
                    a.cltcd        AS companycode,
                    ISNULL(a.mijcltnm, '') AS companyname,
                    ISNULL(b.remark, '')   AS itemnm,
                    ISNULL(b.size, '')     AS spec,
                    ISNULL(b.qty, 0)       AS qty,
                    ISNULL(b.uamt, 0)      AS uamt,
                    ISNULL(b.samt, 0)      AS supplycost,
                    ISNULL(b.tamt, 0)      AS taxtotal,
                    ISNULL(b.mijamt, 0)    AS totalamt,
                    ISNULL(art.artnm, '')  AS artnm,
                    ISNULL(e.divinm, '')   AS divinm,
                    ISNULL(a.taxreclafi, '') AS taxreclafi,
                    ISNULL(tx.nm, '')      AS taxrenm,
                    ISNULL(a.bhflag, '')   AS bhflag
                  FROM TB_CA640 a WITH(NOLOCK)
                  JOIN TB_CA641 b WITH(NOLOCK)
                    ON  a.custcd   = b.custcd
                    AND a.spjangcd = b.spjangcd
                    AND a.mijdate  = b.mijdate
                    AND a.mijnum   = b.mijnum
                  LEFT JOIN TB_JC002 e WITH(NOLOCK)
                    ON  a.custcd = e.custcd AND a.spjangcd = e.spjangcd AND a.divicd = e.divicd
                  LEFT JOIN TB_CA510 g WITH(NOLOCK)
                    ON  g.com_cls = '113' AND g.com_code = a.gubun
                  OUTER APPLY (SELECT TOP 1 artnm FROM TB_CA648 WITH(NOLOCK)
                                WHERE TB_CA648.custcd = a.custcd
                                  AND TB_CA648.spjangcd = a.spjangcd
                                  AND TB_CA648.artcd = a.artcd) art
                  OUTER APPLY (SELECT TOP 1 nm FROM TB_IZ903 WITH(NOLOCK)
                                WHERE TB_IZ903.cd = a.taxreclafi) tx
                 WHERE a.custcd   = :custcd
                   AND a.spjangcd = :spjangcd
                   AND a.mijdate BETWEEN :searchfrdate AND :searchtodate
                """
                + WHERE_FILTER
                + " ORDER BY a.mijdate, a.mijnum, b.seq ";

        return sqlRunner.getRows(sql, commonParam(parameter, custcd));
    }

    /** 집계현황 탭 — 거래처별 매수·공급가액·세액 */
    public List<Map<String, Object>> getList2(Map<String, Object> parameter) {
        String custcd = getCustcd(UtilClass.getStringSafe(parameter.get("spjangcd")));
        if (custcd == null) return List.of();

        // 사업자번호가 없는 거래처(보험료 등)도 한 줄로 합쳐지지 않도록 거래처코드로 묶는다.
        String sql = """
                SELECT
                    a.cltcd                       AS cltcd,
                    ISNULL(x.saupnum, '')         AS saupnum,
                    MAX(ISNULL(a.mijcltnm, ''))   AS clientName,
                    COUNT(DISTINCT a.mijdate + a.mijnum) AS cnt,
                    SUM(ISNULL(b.samt, 0))        AS supplycost,
                    SUM(ISNULL(b.tamt, 0))        AS taxtotal,
                    SUM(ISNULL(b.mijamt, 0))      AS totalamt
                  FROM TB_CA640 a WITH(NOLOCK)
                  JOIN TB_CA641 b WITH(NOLOCK)
                    ON  a.custcd   = b.custcd
                    AND a.spjangcd = b.spjangcd
                    AND a.mijdate  = b.mijdate
                    AND a.mijnum   = b.mijnum
                  LEFT JOIN TB_XCLIENT x WITH(NOLOCK)
                    ON  x.custcd = a.custcd AND x.cltcd = a.cltcd
                 WHERE a.custcd   = :custcd
                   AND a.spjangcd = :spjangcd
                   AND a.mijdate BETWEEN :searchfrdate AND :searchtodate
                """
                + WHERE_FILTER
                + """
                 GROUP BY a.cltcd, ISNULL(x.saupnum, '')
                 ORDER BY SUM(ISNULL(b.samt, 0)) DESC
                """;

        return sqlRunner.getRows(sql, commonParam(parameter, custcd));
    }
}
