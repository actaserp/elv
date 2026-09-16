package mes.app.transaction.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 지급현황 — 파워빌더 '비용지급현황'(w_tb_ca642w_01) 과 같은 자료를 본다.
 *
 * 지급 전표는 TB_CA642 이고, 한 지급 건이 여러 매입 전표에 나뉘어 들어간다.
 * 그래서 파워빌더는 두 번 묶는다.
 *   1) 안쪽: 지급전표 + 매입전표(mijdate/mijnum) + 계좌 단위로 합쳐 결제수단 문구를 만든다
 *   2) 바깥쪽: 매입전표를 걷어내고 지급전표 + 계좌 단위로 다시 합친다 (매입전표는 MAX 로 하나만 표시)
 *
 * 지급액 = 현금 + 어음 + 수표 + 예금 + 카드 + 기타 + 선급금 − 할인(plamt)
 * 거래처명은 지급 전표에 없어서 매입(TB_CA640.mijcltnm)에서 가져온다.
 *
 * 예전 코드는 sports 의 tb_banktransit(ioflag='1') 을 읽었는데 사업체 DB 에는 그 테이블이 없다.
 */
@Slf4j
@Service
public class PaymentListService {
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

    /**
     * 지급현황 목록.
     *
     * @param cltcd  거래처코드. 빈 값이면 전체 (파워빌더는 '%')
     * @param remark 적요 부분일치. 빈 값이면 전체
     */
    public List<Map<String, Object>> getPaymentList(
            String spjangcd, String startDate, String endDate, String cltcd, String remark) {

        String custcd = getCustcd(spjangcd);
        if (custcd == null) return List.of();

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("custcd", custcd);
        param.addValue("spjangcd", spjangcd);
        param.addValue("stdate", startDate == null ? "" : startDate.replaceAll("-", ""));
        param.addValue("enddate", endDate == null ? "" : endDate.replaceAll("-", ""));
        param.addValue("cltcd", cltcd == null ? "" : cltcd.trim());
        param.addValue("remark", remark == null ? "" : remark.trim());

        // 결제계좌(bankcd)는 TB_AA040 의 bank(2자리) + bankcd 를 이어붙인 값이다. 예) '03B01'
        String sql = """
                SELECT
                    STUFF(STUFF(a.snddate, 5, 0, '-'), 8, 0, '-') AS snddate_fmt,
                    a.snddate,
                    a.sndnum,
                    MAX(a.mijdate) AS mijdate,
                    MAX(a.mijnum)  AS mijnum,
                    a.cltcd,
                    a.cltnm,
                    SUM(a.sndamt)  AS sndamt,
                    MAX(a.remark)  AS remark,
                    MAX(a.gubun)   AS gubun,
                    a.bankcd,
                    MAX(ISNULL(acc.banknm, '')) AS banknm,
                    MAX(ISNULL(acc.accnum, '')) AS accnum,
                    MAX(a.acc_spdate) AS acc_spdate,
                    MAX(a.acc_spnum)  AS acc_spnum,
                    MAX(a.acccd)   AS acccd,
                    SUM(a.hamt)    AS hamt,
                    SUM(a.bamt)    AS bamt,
                    SUM(a.gamt)    AS gamt,
                    SUM(a.sunamt)  AS sunamt
                  FROM (
                        SELECT a.snddate,
                               a.sndnum,
                               MAX(a.mijdate) AS mijdate,
                               MAX(a.mijnum)  AS mijnum,
                               MAX(b.mijcltcd) AS cltcd,
                               MAX(b.mijcltnm) AS cltnm,
                               SUM(ISNULL(a.hamt,0)) + SUM(ISNULL(a.eamt,0)) + SUM(ISNULL(a.samt,0))
                             + SUM(ISNULL(a.bamt,0)) + SUM(ISNULL(a.damt,0)) + SUM(ISNULL(a.gamt,0))
                             + SUM(ISNULL(a.sunamt,0)) - SUM(ISNULL(a.plamt,0)) AS sndamt,
                               MAX(a.remark) AS remark,
                               CASE WHEN SUM(a.hamt)   > 0 THEN '현금, '   ELSE '' END +
                               CASE WHEN SUM(a.bamt)   > 0 THEN '예금, '   ELSE '' END +
                               CASE WHEN SUM(a.eamt)   > 0 THEN '어음, '   ELSE '' END +
                               CASE WHEN SUM(a.samt)   > 0 THEN '수표, '   ELSE '' END +
                               CASE WHEN SUM(a.damt)   > 0 THEN '카드, '   ELSE '' END +
                               CASE WHEN SUM(a.sunamt) > 0 THEN '선급금, ' ELSE '' END +
                               CASE WHEN SUM(a.gamt)   > 0 THEN '기타'     ELSE '' END AS gubun,
                               a.bankcd,
                               MAX(a.acc_spdate) AS acc_spdate,
                               MAX(a.acc_spnum)  AS acc_spnum,
                               MAX(b.acccd)      AS acccd,
                               SUM(a.hamt)   AS hamt,
                               SUM(a.bamt)   AS bamt,
                               SUM(a.sunamt) AS sunamt,
                               SUM(a.eamt) + SUM(a.samt) + SUM(a.damt) + SUM(a.gamt) AS gamt
                          FROM TB_CA642 a WITH(NOLOCK)
                          LEFT OUTER JOIN TB_CA640 b WITH(NOLOCK)
                            ON  a.custcd   = b.custcd
                            AND a.spjangcd = b.spjangcd
                            AND a.cltcd    = b.mijcltcd
                            AND a.mijdate  = b.mijdate
                            AND a.mijnum   = b.mijnum
                         WHERE a.custcd   = :custcd
                           AND a.spjangcd = :spjangcd
                           AND a.snddate BETWEEN :stdate AND :enddate
                           AND (:cltcd  = '' OR a.cltcd LIKE :cltcd + '%')
                           AND (:remark = '' OR a.remark LIKE '%' + :remark + '%')
                         GROUP BY a.custcd, a.cltcd, a.snddate, a.sndnum, a.bankcd, a.mijdate, a.mijnum
                       ) a
                  LEFT JOIN TB_AA040 acc WITH(NOLOCK)
                    ON acc.custcd = :custcd AND acc.bank + acc.bankcd = a.bankcd
                 GROUP BY a.cltcd, a.cltnm, a.snddate, a.sndnum, a.bankcd
                 ORDER BY a.snddate, a.sndnum
                """;

        return sqlRunner.getRows(sql, param);
    }
}
