package mes.app.system;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.app.Scheduler.SchedulerService.ApiUsageService;
import mes.app.util.RedisService;
import mes.domain.model.AjaxResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * [개발 확인용 - 배포 전 삭제할 것]
 *
 * Redis 에 실제로 적재된 API 사용량 카운터를 브라우저에서 바로 확인하기 위한 임시 컨트롤러.
 * redis-cli / RedisInsight 접속 없이 확인하려는 목적.
 *
 * 사용법:
 *   http://localhost:8034/api/redis_debug/keys
 *   http://localhost:8034/api/redis_debug/keys?pattern=MES:ZZ:*
 *
 * ※ @ApiProduct 를 붙이지 않았으므로 이 API 자체는 사용량에 집계되지 않는다.
 */
@Slf4j
@RestController
@RequestMapping("/api/redis_debug")
@RequiredArgsConstructor
public class RedisDebugController {

    private final RedisService redisService;
    private final ApiUsageService apiUsageService;

    /**
     * [개발 확인용] 월 이관 스케줄러를 수동 실행한다.
     *
     * <p><b>주의: 실제로 Redis 키를 삭제하고 api_log_entry 에 INSERT 한다.</b>
     * 스케줄러(매월 1일 03시)와 동일한 동작이며, 대상은 "지난달" 키다.
     *
     * <pre>
     *   http://localhost:8034/elv/api/redis_debug/run_migration
     * </pre>
     */
    @GetMapping("/run_migration")
    public AjaxResult runMigration() {
        AjaxResult result = new AjaxResult();
        Map<String, Object> data = new LinkedHashMap<>();

        long start = System.currentTimeMillis();
        try {
            apiUsageService.migrateMonthlyApiUsage();

            data.put("결과", "실행 완료");
            data.put("소요시간(ms)", System.currentTimeMillis() - start);
            data.put("안내", "서버 로그에서 이관 건수를 확인하세요. "
                          + "이관 대상은 '지난달' 키이며, 없으면 '이관할 데이터가 없습니다' 로그가 남습니다.");
            result.data = data;
            result.success = true;

        } catch (Exception e) {
            log.error("[RedisDebug] 수동 이관 실패", e);
            data.put("결과", "실패");
            data.put("오류", e.getClass().getSimpleName() + " : " + e.getMessage());
            result.data = data;
            result.success = false;
        }
        return result;
    }

    /**
     * [개발 확인용] MES:* 키 전체 삭제.
     * 목 데이터 제거 후 실제 데이터만 남기기 위해 사용.
     *
     * <pre>
     *   http://localhost:8034/elv/api/redis_debug/clear
     * </pre>
     */
    @GetMapping("/clear")
    public AjaxResult clear() {
        AjaxResult result = new AjaxResult();
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate =
                    redisService.getRedisTemplate();
            java.util.Set<String> keys = redisTemplate.keys("MES:*");
            long deleted = 0;
            if (keys != null && !keys.isEmpty()) {
                deleted = redisTemplate.delete(keys);
            }
            data.put("삭제된키수", deleted);
            data.put("결과", "MES:* 키 전체 삭제 완료. 이후 실제 API 호출이 쌓이기 시작합니다.");
            result.data = data;
            result.success = true;
        } catch (Exception e) {
            log.error("[RedisDebug] 키 삭제 오류", e);
            data.put("오류", e.getMessage());
            result.data = data;
            result.success = false;
        }
        return result;
    }

    @GetMapping("/keys")
    public AjaxResult keys(
            @RequestParam(value = "pattern", required = false, defaultValue = "MES:*") String pattern) {

        AjaxResult result = new AjaxResult();
        Map<String, Object> data = new LinkedHashMap<>();

        try {
            boolean available = redisService.isRedisAvailable();
            data.put("redis연결", available ? "정상" : "연결안됨");
            data.put("조회패턴", pattern);

            if (!available) {
                data.put("안내", "Redis 연결이 되지 않았습니다. 접속 정보(환경변수)를 확인하세요.");
                result.data = data;
                result.success = false;
                return result;
            }

            Map<String, Long> values = redisService.getValuesByPattern(pattern);

            if (values == null || values.isEmpty()) {
                data.put("키개수", 0);
                data.put("안내", "적재된 키가 없습니다. 화면을 몇 번 사용한 뒤 다시 조회해보세요.");
                result.data = data;
                result.success = true;
                return result;
            }

            // 키 이름순 정렬해서 보기 좋게
            Map<String, Long> sorted = new TreeMap<>(values);
            data.put("키개수", sorted.size());
            data.put("키목록", sorted);

            // 상품코드별 합계 (키 형식: MES:{사업장}:{상품}:{yyyyMMdd})
            Map<String, Long> byProduct = new TreeMap<>();
            Map<String, Long> bySpjang  = new TreeMap<>();
            for (Map.Entry<String, Long> e : sorted.entrySet()) {
                String[] parts = e.getKey().split(":");
                if (e.getValue() == null) continue;
                long cnt = e.getValue();

                if (parts.length >= 4) {
                    // 신규 형식 (상품 축 포함)
                    bySpjang.merge(parts[1], cnt, Long::sum);
                    byProduct.merge(parts[2], cnt, Long::sum);
                } else if (parts.length == 3) {
                    // 구 형식 (상품 축 없음) — 필터가 아직 동작 중이면 여기 잡힘
                    bySpjang.merge(parts[1], cnt, Long::sum);
                    byProduct.merge("(구형식-상품없음)", cnt, Long::sum);
                }
            }
            data.put("사업장별합계", bySpjang);
            data.put("상품별합계", byProduct);

            result.data = data;
            result.success = true;

        } catch (Exception e) {
            log.error("[RedisDebug] 조회 오류", e);
            data.put("오류", e.getMessage());
            result.data = data;
            result.success = false;
        }
        return result;
    }
}
