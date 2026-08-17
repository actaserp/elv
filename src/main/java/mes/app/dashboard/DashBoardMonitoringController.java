package mes.app.dashboard;


import lombok.extern.slf4j.Slf4j;
import mes.app.naverCloud.Enum.NcpMetric;
import mes.app.naverCloud.dto.NetworkChartDto;
import mes.app.naverCloud.service.NcpMonitoringService;
import mes.app.naverCloud.strategy.MonthlyRange;
import mes.app.naverCloud.strategy.RealTimeRange;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/monitoring")
@Slf4j
public class DashBoardMonitoringController {

    @Value("${ncp_api_instanceNo}")
    private String instanceNo;

    @Autowired
    NcpMonitoringService ncpMonitoringService;

    @Autowired
    mes.app.system.service.TenantUsageService tenantUsageService;

    @Autowired
    mes.app.util.RedisService redisService;

    @Autowired
    @Qualifier("asyncExecutor")
    ThreadPoolTaskExecutor asyncExecutors;


    @GetMapping("/read")
    public AjaxResult GetDataList(@RequestParam String monthlyStartDate,
                                  @RequestParam String monthlyStartDate2,
                                  @RequestParam(defaultValue = "1") int pageNumber,
                                  @RequestParam(defaultValue = "10") int pageSize
                                  ){

        // CPU와 RAM 메트릭을 '실시간(30분)' 정책으로 묶어서 단일 시간 요청
        CompletableFuture<Map<String, Double>> resourceSummary = CompletableFuture.supplyAsync(() ->
                        ncpMonitoringService.fetchAverages(
                                List.of(NcpMetric.avg_cpu_used_rto, NcpMetric.mem_usert),
                                instanceNo,
                                new RealTimeRange()
                        ), asyncExecutors
                );

        //네트워크 대시보드 데이터
        CompletableFuture<NetworkChartDto> trafficHistory = CompletableFuture.supplyAsync(() ->
                ncpMonitoringService.fetchTrafficHistory(
                        List.of(NcpMetric.avg_snd_bps, NcpMetric.avg_rcv_bps), "127900112"
                        ,new MonthlyRange()
                ), asyncExecutors
        );

        //월별 가입현황 대시보드 데이터

        CompletableFuture<List<Map<String, Object>>> montlyList = CompletableFuture.supplyAsync(() ->
                ncpMonitoringService.getMontlyRegisterList(monthlyStartDate, pageNumber, pageSize)
                ,asyncExecutors
        );

        //CompletableFuture<List<Map<String, Object>>> montlyList = null

        //api 콜 횟수 (고객사별) 대시보드 데이터

        CompletableFuture<List<Map<String, Object>>> apiCntListBySpjangcd = CompletableFuture.supplyAsync(() ->
                ncpMonitoringService.getApiCntListBySpjangcd(pageNumber, pageSize, monthlyStartDate2)
                ,asyncExecutors
        );

        Map<String, Object> dataList = new HashMap<>();
        try{
            //dataList.put("resource", null);
            dataList.put("resource", resourceSummary.join());
            //dataList.put("traffic", null);
            dataList.put("traffic", trafficHistory.join());
            dataList.put("monthly", montlyList.join());
            dataList.put("apiCntList", apiCntListBySpjangcd.join());
        }catch (Exception e){
            log.error("데이터 조립 중 에러 발생", e);
        }
        return AjaxResult.success(null, dataList);
    }

    //월별 가입현황 (페이징)
    //@GetMapping("/monthly_read")
    @GetMapping("/pages/monthly")
    public AjaxResult getMontlyList(@RequestParam String monthlyStartDate,
                                    @RequestParam(defaultValue = "1") int pageNumber,
                                    @RequestParam(defaultValue = "10") int pageSize
                                    ){
        List<Map<String, Object>> data = ncpMonitoringService.getMontlyRegisterList(
                monthlyStartDate, pageNumber, pageSize
        );


        return AjaxResult.success(null, data);
    }

    //실시간 사용량 및 정산현황 (페이징)
    //@GetMapping("/api_count_list")
    @GetMapping("/pages/usage")
    public AjaxResult getApiCntList(
            @RequestParam String monthlyStartDate2,
            @RequestParam(defaultValue = "1") int pageNumber,
            @RequestParam(defaultValue = "10") int pageSize
    ){
        List<Map<String, Object>> data = ncpMonitoringService.getApiCntListBySpjangcd(pageNumber, pageSize, monthlyStartDate2);

        return AjaxResult.success(null, data);
    }

    /**
     * 본사 관리자 전용 — 특정 사업장의 상품별 상세 사용량.
     * adminUsageGrid 더블클릭 시 팝업에서 호출.
     * spjangcd 를 파라미터로 직접 받는다 (본사 관리자만 접근 가능한 API).
     */
    @GetMapping("/detail")
    public AjaxResult getDetail(
            @RequestParam String spjangcd,
            @RequestParam(required = false) String yearMonth
    ) {
        try {
            String currentYm = java.time.LocalDate.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
            boolean isCurrentMonth = (yearMonth == null || yearMonth.isBlank()
                    || yearMonth.replace("-", "").equals(currentYm));
            String targetYm = isCurrentMonth ? currentYm : yearMonth.replace("-", "");

            // 상품별 사용량 집계
            java.util.Map<String, Long> usageByProduct = new java.util.HashMap<>();
            if (isCurrentMonth) {
                String pattern = "MES:" + spjangcd + ":*:" + targetYm + "??";
                java.util.Map<String, Long> values = redisService.getValuesByPattern(pattern);
                for (java.util.Map.Entry<String, Long> e : values.entrySet()) {
                    String[] parts = e.getKey().split(":");
                    if (parts.length >= 4) usageByProduct.merge(parts[2], e.getValue(), Long::sum);
                }
            } else {
                java.util.List<java.util.Map<String, Object>> rows =
                        tenantUsageService.getUsageHistory(spjangcd, targetYm);
                for (java.util.Map<String, Object> row : rows) {
                    String pcd = String.valueOf(row.get("product_cd"));
                    Object cnt = row.get("total_count");
                    if (cnt != null) usageByProduct.merge(pcd, ((Number) cnt).longValue(), Long::sum);
                }
            }

            // 상품 목록 + 계약여부
            java.util.List<java.util.Map<String, Object>> products =
                    tenantUsageService.getProductListWithContract(spjangcd);

            java.util.List<java.util.Map<String, Object>> items = new java.util.ArrayList<>();
            java.math.BigDecimal totalBill = java.math.BigDecimal.ZERO;

            for (java.util.Map<String, Object> p : products) {
                String pcd = String.valueOf(p.get("product_cd"));
                boolean contracted = "1".equals(String.valueOf(p.get("contract_yn")));
                long usage = usageByProduct.getOrDefault(pcd, 0L);

                java.math.BigDecimal price = toBD(p.get("price"));
                Integer limit = toInt(p.get("api_call_limit"));
                java.math.BigDecimal extraUnit = toBD(p.get("extra_unit_price"));

                long overCnt = 0;
                java.math.BigDecimal overAmt = java.math.BigDecimal.ZERO;
                if (limit != null && limit > 0 && usage > limit) {
                    overCnt = usage - limit;
                    overAmt = extraUnit.multiply(java.math.BigDecimal.valueOf(overCnt));
                }
                java.math.BigDecimal bill = contracted ? price.add(overAmt) : java.math.BigDecimal.ZERO;
                if (contracted) totalBill = totalBill.add(bill);

                java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("product_cd",     pcd);
                row.put("product_nm",     p.get("product_nm"));
                row.put("contract_yn",    contracted ? "1" : "0");
                row.put("contract_nm",    contracted ? "계약" : "미계약");
                row.put("price",          contracted ? price : java.math.BigDecimal.ZERO);
                row.put("api_call_limit", limit);
                row.put("usage_cnt",      usage);
                row.put("over_cnt",       overCnt);
                row.put("over_amt",       overAmt);
                row.put("bill",           bill);
                items.add(row);
            }

            java.util.Map<String, Object> data = new java.util.HashMap<>();
            data.put("items",      items);
            data.put("total_bill", totalBill);
            data.put("spjangcd",   spjangcd);
            data.put("spjangnm",   tenantUsageService.getSpjangNm(spjangcd));
            data.put("year_month", targetYm);

            return AjaxResult.success(null, data);
        } catch (Exception e) {
            AjaxResult err = new AjaxResult();
            err.success = false;
            err.message = "상세 조회 실패: " + e.getMessage();
            return err;
        }
    }

    private java.math.BigDecimal toBD(Object v) {
        if (v == null) return java.math.BigDecimal.ZERO;
        try { return new java.math.BigDecimal(String.valueOf(v)); }
        catch (Exception e) { return java.math.BigDecimal.ZERO; }
    }
    private Integer toInt(Object v) {
        if (v == null) return null;
        try { return Integer.parseInt(String.valueOf(v)); }
        catch (Exception e) { return null; }
    }

    @GetMapping("/local_cache/save")
    public AjaxResult localCacheSetRDB(){

        ncpMonitoringService.redisDataSync();
        //ncpMonitoringService.syncCacheToDb();

        return AjaxResult.success(null, null);
    }
}
