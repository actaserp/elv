package mes.app.system;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
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
        //   1) TenantContext.get()  : 세션의 spjangcd (SQL 필터용 사업장코드)
        //   2) user.getDbKey()      : 로그인 시 선택한 테넌트
        //   3) TenantUserService    : 사업체DB 사원정보 경유 (본사계정은 조회 안 될 수 있음)
        User user = (User) auth.getPrincipal();

        // ── 사업장코드 취득 ──────────────────────────────────────
        // auth_user.spjangcd 는 본사 DB 소속을 나타내며 항상 'ZZ' 로 저장됨.
        // 실제 사업체 구분은 dbKey(=테넌트 사업장코드)를 사용해야 한다.
        // 우선순위: dbKey → TenantContext → tenantUserService
        String spjangcd = user.getDbKey();

        if (spjangcd == null || spjangcd.isBlank()) {
            spjangcd = TenantContext.get();
        }
        if (spjangcd == null || spjangcd.isBlank()) {
            spjangcd = tenantUserService.getSpjangcd(user.getUsername());
        }

        if (spjangcd == null || spjangcd.isBlank()) {
            log.warn("[TenantUsage] 사업장 확인 불가 username={}", user.getUsername());
            result.success = false;
            result.message = "사업장 정보를 확인할 수 없습니다.";
            return result;
        }
        log.debug("[TenantUsage] spjangcd={}", spjangcd);

        String currentYm = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        boolean isCurrentMonth = (yearMonth == null || yearMonth.isBlank() || yearMonth.equals(currentYm));
        String targetYm = isCurrentMonth ? currentYm : yearMonth;

        // ── 등급(bill_plans) 한도 조회 ──
        Map<String, Object> billPlan = tenantUsageService.getBillPlanBySpjangcd(spjangcd);
        long   planApiLimit  = billPlan != null && billPlan.get("api_call_limit") != null
                ? ((Number) billPlan.get("api_call_limit")).longValue() : 0L;
        BigDecimal extraUnit = billPlan != null && billPlan.get("extra_api_unit_price") != null
                ? new BigDecimal(String.valueOf(billPlan.get("extra_api_unit_price"))) : BigDecimal.ZERO;
        String planName = billPlan != null ? String.valueOf(billPlan.getOrDefault("plan_name", "")) : "";

        // ── 상품별 사용량 ──
        Map<String, Long> usageByProduct = isCurrentMonth
                ? getRealtimeUsage(spjangcd, targetYm)
                : getHistoryUsage(spjangcd, targetYm);

        // ── 전체 사용량 합산 (초과 판단용) ──
        long totalUsageCnt = usageByProduct.values().stream().mapToLong(Long::longValue).sum();

        // ── 등급 기준 초과 계산 (추후 도입 예정 — 현재 비활성화) ──
        // long totalOverCnt = planApiLimit > 0 ? Math.max(0L, totalUsageCnt - planApiLimit) : 0L;
        // BigDecimal totalOverAmt = extraUnit.multiply(BigDecimal.valueOf(totalOverCnt));
        long totalOverCnt = 0L;
        BigDecimal totalOverAmt = BigDecimal.ZERO;

        // ── 상품 목록 + 계약여부 ──
        List<Map<String, Object>> products = tenantUsageService.getProductListWithContract(spjangcd);

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal totalBill      = BigDecimal.ZERO;
        BigDecimal totalBasePrice = BigDecimal.ZERO;

        for (Map<String, Object> p : products) {
            String  productCd  = String.valueOf(p.get("product_cd"));
            boolean contracted = "1".equals(String.valueOf(p.get("contract_yn")));
            long    usage      = usageByProduct.getOrDefault(productCd, 0L);
            BigDecimal price   = toDecimal(p.get("price"));
            BigDecimal bill    = contracted ? price : BigDecimal.ZERO;

            if (contracted) {
                totalBill      = totalBill.add(bill);
                totalBasePrice = totalBasePrice.add(price);
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("product_cd",  productCd);
            row.put("product_nm",  p.get("product_nm"));
            row.put("remark",      p.get("remark"));
            row.put("contract_yn", contracted ? "1" : "0");
            row.put("contract_nm", contracted ? "계약" : "미계약");
            row.put("price",       contracted ? price : BigDecimal.ZERO);
            row.put("usage_cnt",   usage);
            row.put("bill",        bill);
            rows.add(row);
        }

        // 초과 금액은 추후 도입 예정 — 현재는 기본요금 합산만 청구
        // BigDecimal finalBill = totalBill.add(totalOverAmt);
        BigDecimal finalBill = totalBill;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("spjangcd",       spjangcd);
        summary.put("spjangnm",       tenantUsageService.getSpjangNm(spjangcd));
        summary.put("year_month",     targetYm);
        summary.put("is_realtime",    isCurrentMonth);
        summary.put("plan_name",      planName);
        summary.put("plan_api_limit", planApiLimit);
        summary.put("total_usage",    totalUsageCnt);
        summary.put("base_price",     totalBasePrice);
        summary.put("over_cnt",       totalOverCnt);
        summary.put("over_amt",       totalOverAmt);
        summary.put("total_bill",     finalBill);
        // summary.put("total_vat",   finalBill.multiply(new BigDecimal("1.1")));  // 추후 도입 예정

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
        // yyyyMM + ?? 로 날짜 8자리를 정확히 매칭 (MES:DJ:P01:20260801 ~ 20260831)
        String pattern = KEY_PREFIX + ":" + spjangcd + ":*:" + yearMonth + "??";
        Map<String, Long> values = redisService.getValuesByPattern(pattern);
        for (Map.Entry<String, Long> e : values.entrySet()) {
            String[] parts = e.getKey().split(":");
            if (parts.length < 4 || e.getValue() == null) continue;
            map.merge(parts[2], e.getValue(), Long::sum);
        }

        // 구형식: MES:{사업장}:{yyyyMMdd} → 상품 도입 이전 데이터이므로 P01 로 간주
        String legacyPattern = KEY_PREFIX + ":" + spjangcd + ":" + yearMonth + "??";
        Map<String, Long> legacy = redisService.getValuesByPattern(legacyPattern);
        for (Map.Entry<String, Long> e : legacy.entrySet()) {
            String[] parts = e.getKey().split(":");
            if (parts.length != 3 || e.getValue() == null) continue;
            map.merge("P01", e.getValue(), Long::sum);
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
