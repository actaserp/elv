package mes.app.system;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantUserService;
import mes.app.system.service.TenantUsageService;
import mes.app.util.RedisService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 사업체(테넌트) 전용 사용량·정산 조회.
 *
 * <p>본사용({@code RealTimeUsageController})과 URL 을 분리했다.
 * <b>spjangcd 를 파라미터로 받지 않고 로그인 세션에서만 취득</b>하므로
 * 다른 사업장의 사용량을 조회할 수 없다.
 *
 * <p>※ 이 컨트롤러 자체는 과금 대상이 아니므로 {@code @ApiProduct} 를 붙이지 않는다.
 */
@Slf4j
@RestController
@RequestMapping("/api/tenant_usage")
public class TenantUsageController {

    private static final String KEY_PREFIX = "MES";

    @Autowired TenantUsageService tenantUsageService;
    @Autowired TenantUserService  tenantUserService;
    @Autowired RedisService       redisService;

    /**
     * 이번 달 실시간 사용량 + 상품별 청구 예정 금액.
     *
     * <pre>
     *   GET /api/tenant_usage/read                → 이번 달(Redis 실시간)
     *   GET /api/tenant_usage/read?yearMonth=202607 → 과거 월(api_log_entry)
     * </pre>
     */
    @GetMapping("/read")
    public AjaxResult read(
            @RequestParam(value = "yearMonth", required = false) String yearMonth,
            Authentication auth) {

        AjaxResult result = new AjaxResult();

        // ── 로그인 사용자의 사업장 (파라미터로 받지 않음) ──
        User user = (User) auth.getPrincipal();
        String spjangcd = tenantUserService.getSpjangcd(user.getUsername());
        if (spjangcd == null || spjangcd.isBlank()) {
            result.success = false;
            result.message = "사업장 정보를 확인할 수 없습니다.";
            return result;
        }

        String currentYm = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        boolean isCurrentMonth = (yearMonth == null || yearMonth.isBlank() || yearMonth.equals(currentYm));
        String targetYm = isCurrentMonth ? currentYm : yearMonth;

        // ── 상품별 사용량 ──
        Map<String, Long> usageByProduct = isCurrentMonth
                ? getRealtimeUsage(spjangcd, targetYm)
                : getHistoryUsage(spjangcd, targetYm);

        // ── 상품 목록 + 계약여부 ──
        List<Map<String, Object>> products = tenantUsageService.getProductListWithContract(spjangcd);

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal totalBill      = BigDecimal.ZERO;   // 청구 합계 (계약분만)
        BigDecimal totalBasePrice = BigDecimal.ZERO;   // 기본료 합계
        BigDecimal totalExtraAmt  = BigDecimal.ZERO;   // 초과금액 합계
        long       totalExtraCnt  = 0;                 // 초과 호출 합계

        for (Map<String, Object> p : products) {
            String productCd  = String.valueOf(p.get("product_cd"));
            boolean contracted = "1".equals(String.valueOf(p.get("contract_yn")));

            long usage = usageByProduct.getOrDefault(productCd, 0L);

            BigDecimal price     = toDecimal(p.get("price"));
            Integer    callLimit = toInteger(p.get("api_call_limit"));
            BigDecimal extraUnit = toDecimal(p.get("extra_unit_price"));

            // 초과 계산 — 한도가 설정된 상품만 (현재 P01)
            long       overCnt = 0;
            BigDecimal overAmt = BigDecimal.ZERO;
            if (callLimit != null && callLimit > 0 && usage > callLimit) {
                overCnt = usage - callLimit;
                overAmt = extraUnit.multiply(BigDecimal.valueOf(overCnt));
            }

            // 미계약 상품은 사용량만 보여주고 금액은 0
            BigDecimal bill = contracted ? price.add(overAmt) : BigDecimal.ZERO;

            if (contracted) {
                totalBill      = totalBill.add(bill);
                totalBasePrice = totalBasePrice.add(price);
                totalExtraAmt  = totalExtraAmt.add(overAmt);
                totalExtraCnt += overCnt;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("product_cd",     productCd);
            row.put("product_nm",     p.get("product_nm"));
            row.put("remark",         p.get("remark"));
            row.put("contract_yn",    contracted ? "1" : "0");
            row.put("contract_nm",    contracted ? "계약" : "미계약");
            row.put("price",          contracted ? price : BigDecimal.ZERO);
            row.put("api_call_limit", callLimit);
            row.put("usage_cnt",      usage);
            row.put("over_cnt",       overCnt);
            row.put("over_amt",       overAmt);
            row.put("bill",           bill);
            row.put("is_excess",      overCnt > 0);
            rows.add(row);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("spjangcd",    spjangcd);
        summary.put("spjangnm",    tenantUsageService.getSpjangNm(spjangcd));
        summary.put("year_month",  targetYm);
        summary.put("is_realtime", isCurrentMonth);
        summary.put("base_price",  totalBasePrice);
        summary.put("over_cnt",    totalExtraCnt);
        summary.put("over_amt",    totalExtraAmt);
        summary.put("total_bill",  totalBill);
        summary.put("total_vat",   totalBill.multiply(new BigDecimal("1.1")));

        Map<String, Object> data = new HashMap<>();
        data.put("summary", summary);
        data.put("items",   rows);

        result.data    = data;
        result.success = true;
        return result;
    }

    // ── 이번 달: Redis 실시간 집계 ────────────────────────────
    private Map<String, Long> getRealtimeUsage(String spjangcd, String yearMonth) {
        Map<String, Long> map = new HashMap<>();

        if (!redisService.isRedisAvailable()) {
            log.warn("[TenantUsage] Redis 미연결 - 실시간 사용량 조회 불가");
            return map;
        }

        // 신형식: MES:{사업장}:{상품}:{yyyyMMdd}
        String pattern = KEY_PREFIX + ":" + spjangcd + ":*:" + yearMonth + "*";
        Map<String, Integer> values = redisService.getValuesByPattern(pattern);
        if (values != null) {
            for (Map.Entry<String, Integer> e : values.entrySet()) {
                String[] parts = e.getKey().split(":");
                if (parts.length < 4 || e.getValue() == null) continue;
                map.merge(parts[2], e.getValue().longValue(), Long::sum);
            }
        }

        // 구형식: MES:{사업장}:{yyyyMMdd} → 상품 도입 이전 데이터이므로 P01 로 간주
        String legacyPattern = KEY_PREFIX + ":" + spjangcd + ":" + yearMonth + "*";
        Map<String, Integer> legacy = redisService.getValuesByPattern(legacyPattern);
        if (legacy != null) {
            for (Map.Entry<String, Integer> e : legacy.entrySet()) {
                String[] parts = e.getKey().split(":");
                if (parts.length != 3 || e.getValue() == null) continue;
                map.merge("P01", e.getValue().longValue(), Long::sum);
            }
        }
        return map;
    }

    // ── 과거 월: api_log_entry ────────────────────────────────
    private Map<String, Long> getHistoryUsage(String spjangcd, String yearMonth) {
        Map<String, Long> map = new HashMap<>();
        List<Map<String, Object>> rows = tenantUsageService.getUsageHistory(spjangcd, yearMonth);
        if (rows == null) return map;

        for (Map<String, Object> row : rows) {
            String productCd = String.valueOf(row.get("product_cd"));
            Object cnt = row.get("total_count");
            if (cnt != null) {
                map.merge(productCd, ((Number) cnt).longValue(), Long::sum);
            }
        }
        return map;
    }

    private BigDecimal toDecimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        try { return new BigDecimal(String.valueOf(v)); }
        catch (Exception e) { return BigDecimal.ZERO; }
    }

    private Integer toInteger(Object v) {
        if (v == null) return null;
        try { return Integer.parseInt(String.valueOf(v)); }
        catch (Exception e) { return null; }
    }
}
