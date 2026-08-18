package mes.app.system.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 사업체(테넌트) 전용 사용량 조회 서비스.
 *
 * <p><b>본사 DB(PostgreSQL) 고정.</b>
 * product / tenant_product / api_log_entry 는 본사 DB 에만 존재하므로
 * 테넌트 라우팅되는 일반 SqlRunner 를 쓰면 사업체 DB(SQL Server)로 가서 조회에 실패한다.
 */
@Slf4j
@Service
public class TenantUsageService {

    private final SqlRunner mainSqlRunner;

    public TenantUsageService(@Qualifier("mainSqlRunner") SqlRunner mainSqlRunner) {
        this.mainSqlRunner = mainSqlRunner;
    }

    /**
     * 상품 마스터 전체 + 해당 사업장의 계약 여부.
     *
     * <p>사업체 화면은 계약분/미계약분을 모두 노출하므로 product 를 기준으로
     * tenant_product 를 LEFT JOIN 한다.
     */
    public List<Map<String, Object>> getProductListWithContract(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);

        String sql = """
                SELECT p.product_cd,
                       p.product_nm,
                       p.price,
                       p.api_call_limit,
                       p.extra_unit_price,
                       p.remark,
                       p.sort_order,
                       CASE WHEN tp.spjangcd IS NULL THEN '0' ELSE '1' END AS contract_yn,
                       tp.start_ym
                  FROM product p
                  LEFT JOIN tenant_product tp
                         ON tp.product_cd = p.product_cd
                        AND tp.spjangcd   = :spjangcd
                        AND tp.end_ym IS NULL
                 WHERE p.useyn = '1'
                 ORDER BY p.sort_order
                """;
        return mainSqlRunner.getRows(sql, param);
    }

    /**
     * 과거 월별 확정 사용량 (api_log_entry).
     *
     * <p>이관 시 일자 단위로 적재되므로 월 단위로 합산한다.
     *
     * @param yearMonth yyyyMM (null 이면 최근 12개월)
     */
    public List<Map<String, Object>> getUsageHistory(String spjangcd, String yearMonth) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        param.addValue("yearMonth", yearMonth);

        String sql = """
                SELECT TO_CHAR(a.stat_day, 'YYYY-MM')        AS stat_month,
                       a.spjangcd,
                       a.spjangnm,
                       a.product_cd,
                       MAX(a.bill_plan_name)                 AS product_nm,
                       MAX(a.price)                          AS price,
                       MAX(a.api_call_limit)                 AS api_call_limit,
                       MAX(a.extra_api_unit_price)           AS extra_unit_price,
                       SUM(a.total_count)                    AS total_count
                  FROM api_log_entry a
                 WHERE a.spjangcd = :spjangcd
                   AND (:yearMonth IS NULL
                        OR TO_CHAR(a.stat_day, 'YYYYMM') = :yearMonth)
                 GROUP BY TO_CHAR(a.stat_day, 'YYYY-MM'), a.spjangcd, a.spjangnm, a.product_cd
                 ORDER BY 1 DESC, a.product_cd
                """;
        return mainSqlRunner.getRows(sql, param);
    }

    /** 사업장명 */
    public String getSpjangNm(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        try {
            Map<String, Object> row = mainSqlRunner.getRow("""
                    SELECT spjangnm FROM tb_xa012 WHERE spjangcd = :spjangcd
                    """, param);
            return (row != null && row.get("spjangnm") != null)
                    ? String.valueOf(row.get("spjangnm")) : "";
        } catch (Exception e) {
            log.warn("[TenantUsage] 사업장명 조회 실패 spjangcd={}", spjangcd);
            return "";
        }
    }

    /**
     * 사업장의 등급(bill_plans) 정보 조회.
     * api_call_limit, extra_api_unit_price 는 등급 단위로만 관리한다.
     */
    public Map<String, Object> getBillPlanBySpjangcd(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);
        String sql = """
                SELECT b.id,
                       b.name          AS plan_name,
                       b.api_call_limit,
                       b.extra_api_unit_price,
                       b.price         AS plan_price
                  FROM tb_xa012 a
                  LEFT JOIN bill_plans b ON a.bill_plans_id = b.id
                 WHERE a.spjangcd = :spjangcd
                """;
        try {
            return mainSqlRunner.getRow(sql, param);
        } catch (Exception e) {
            log.warn("[TenantUsage] 등급 조회 실패 spjangcd={}", spjangcd);
            return null;
        }
    }
}
