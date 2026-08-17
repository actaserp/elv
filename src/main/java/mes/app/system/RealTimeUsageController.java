package mes.app.system;


import lombok.extern.slf4j.Slf4j;
import mes.app.system.service.RealTimeUsageService;
import mes.app.util.RedisService;
import mes.domain.model.AjaxResult;
import org.apache.poi.hpsf.Decimal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/realtime")
@Slf4j
public class RealTimeUsageController {

    @Autowired
    RedisService redisService;

    @Autowired
    RealTimeUsageService realTimeUsageService;

    //TODO: redis 데이터를 스케줄러로 얼마주기로 삭제하는지 확인필요 : runApiUsageMigration
    @GetMapping("/read")
    public AjaxResult getList(@RequestParam String spjangcd){

        String currentMonth = LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyyMM"));

        DecimalFormat df = new DecimalFormat("###,###");
        /// [A] 이번 달 (실시간)
        // Redis 데이터 — 신 패턴: MES:{사업장}:{상품}:{yyyyMMdd}
        String myPattern = "MES:" + spjangcd + ":*:" + currentMonth + "??";
        Map<String, Long> redisData = redisService.getValuesByPattern(myPattern);
        //RDB 데이터
        List<Map<String, Object>> currentUsage = realTimeUsageService.getUsageList(spjangcd);
        //이번 달 총 사용량 합산
        long totalRealTimeCnt = redisData.values().stream().mapToLong(Long::longValue).sum();
        // 결과 조립
        for (Map<String, Object> item : currentUsage) {
            processBillingRow(item, totalRealTimeCnt, df);
        }

        //// [B] 과거 이력 (RDB)
        List<Map<String, Object>> historyUsage = realTimeUsageService.getApiUsageHistory(spjangcd);
        for(Map<String, Object> item : historyUsage){
            long historyTotal = ((Number) item.get("total_count")).longValue();
            processBillingRow(item, historyTotal, df);
        }
        currentUsage.addAll(historyUsage);

        //만약 localCache 값이 있다면 redis가 비정상 종료된것 -> 데이터를 안내려줌 (에러가 났다는걸 명시적으로 표시한다.)
        // 1. 현재 레디스 연결이 끊겼거나
        // 2. 장애 상황에서 로컬 캐시에 쌓인 데이터가 아직 이관되지 않았다면
        // 사용자에게 부정확한 데이터를 보여주지 않기 위해 null 처리

        //만약 redis가 끊어진것 같으면 /api/monitoring/local_cache/save 를 get으로 호출해서 로컬캐시 -> redis로 데이터 이관
        if (!redisService.isRedisAvailable()) {
            log.warn("[SaaS] Redis 장애 감지 또는 미이관 데이터 존재로 인해 사용량 조회를 차단합니다.");
            currentUsage = null;
        }

        return AjaxResult.success(null, currentUsage);
    }


    private void processBillingRow(Map<String, Object> item, long totalUsage, DecimalFormat df) {
        // 1. 원본 데이터 추출 (타입 안정성 확보)
        long apiCallLimit = (long) Double.parseDouble(String.valueOf(item.getOrDefault("api_call_limit", 0)));
        long basePrice    = (long) Double.parseDouble(String.valueOf(item.getOrDefault("price", 0)));
        long extraUnitPrice = (long) Double.parseDouble(String.valueOf(item.getOrDefault("extra_api_unit_price", 0)));

        // 2. 비즈니스 로직 계산
        long overApiCnt = Math.max(0, totalUsage - apiCallLimit);
        long overApiAmt = overApiCnt * extraUnitPrice;
        long totalBill  = basePrice + overApiAmt;

        // 3. 포맷팅 및 결과 조립
        item.put("totalRealTimeCnt", totalUsage + "건");
        item.put("api_call_limit",   apiCallLimit + "건");
        item.put("over_api_cnt",     overApiCnt + "건");
        item.put("over_api_amt",     overApiAmt);
        item.put("bill",             df.format(totalBill) + "원");
        item.put("bill_raw",         totalBill);
    }
}
