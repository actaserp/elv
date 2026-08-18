package mes.app.util;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class RedisService {

    private final RedisTemplate<String, Object> redisTemplate;

    //localCache 조회
    @Getter
    private final Map<String, Object> localCache = new ConcurrentHashMap<>();

    public RedisService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // 저장
    public void setValues(String key, Object value){
        try{
            redisTemplate.opsForValue().set(key, value);
        }catch(Exception e){
            log.error("Redis 연결실패- 로컬 메모리 저장");
            localCache.put(key, value);
        }
    }

    // 저장, 만료시간 설정
    public void setValues(String key, Object value, long duration, TimeUnit unit){
        try{
            redisTemplate.opsForValue().set(key,value, duration,unit);
        }catch (Exception e){
            localCache.put(key, value);
        }
    }

    // 3. 데이터 조회 (GET)
    public Object getValues(String key) {
        try{
            return redisTemplate.opsForValue().get(key);
        }catch (Exception e){
            log.warn("Redis 조회실패 - 로컬 메모리에서 조회");
            return localCache.get(key);
        }
    }

    // 4. [핵심] API 카운트용 숫자 증가 (INCR)
    public Long incrementValue(String key) {

        try{
            Long count = redisTemplate.opsForValue().increment(key);

            if(count != null && count == 1){
                redisTemplate.expire(key, 35, TimeUnit.DAYS);
            }
            return count;
        }catch (Exception e){
            log.error("Redis INCR 실패 - 로컬 메모리 카운팅");
            return (Long) localCache.compute(key, (k, v) -> (v == null) ? 1L : (long) v + 1L);
        }
    }

    // hash 구조로 set
    public Long incrementHashValue(String key, String field){
        try{

            Long count = redisTemplate.opsForHash().increment(key, field, 1L);

            // Hash 전체 키에 대한 TTL입니다.
            if (count != null && count == 1) {
                redisTemplate.expire(key, 45, TimeUnit.DAYS);
            }
            return count;
        }catch(Exception e){
            log.error("Redis HINCRBY 실패 - Key: {}, Field: {}", key, field);
            // 로컬 캐시 처리 시 키와 필드를 조합해서 저장
            String localKey = key + ":" + field;
            return (Long) localCache.compute(localKey, (k, v) -> (v == null) ? 1L : (long) v + 1L);
        }
    }

    public Long incrementValue(String key, Long value){
        try{
            Long count = redisTemplate.opsForValue().increment(key, value);
            return  count;
        }catch(Exception e){
            log.error("Redis 연결 실패! 로컬 캐시에 임시 저장합니다. Key: {}, Value: {}", key, value);

            // 로컬 캐시에 합산 (기존 값이 없으면 value, 있으면 합산)
            // compute 메서드는 ConcurrentHashMap의 원자적 연산을 보장합니다.
            localCache.merge(key, value, (oldVal, newVal) -> (Long) oldVal + (Long) newVal);

            return -1L; // 에러 발생 신호로 -1 반환 (또는 적절한 값)
        }
    }

    // 5. 데이터 삭제 (DEL)
    public void deleteValues(String key) {
        try{
            redisTemplate.delete(key);
        }catch (Exception e){
            localCache.remove(key);
        }
    }


    /**
     * 패턴으로 Redis 키를 SCAN 후 값을 일괄 조회한다.
     *
     * <p>기존 {@code keys()} 방식은 운영 환경에서 블로킹·누락 문제가 있어
     * SCAN 커서 방식으로 교체했다. 값은 Long 으로 파싱해 Integer 오버플로우를 방지.
     */
    public Map<String, Long> getValuesByPattern(String pattern) {
        Map<String, Long> usageMap = new HashMap<>();
        try {
            org.springframework.data.redis.core.ScanOptions options =
                    org.springframework.data.redis.core.ScanOptions.scanOptions()
                            .match(pattern).count(500).build();

            List<String> keys = new ArrayList<>();
            redisTemplate.execute(
                    (org.springframework.data.redis.core.RedisCallback<Void>) conn -> {
                        try (org.springframework.data.redis.core.Cursor<byte[]> cursor =
                                     conn.scan(options)) {
                            while (cursor.hasNext()) {
                                keys.add(new String(cursor.next()));
                            }
                        } catch (Exception e) {
                            log.warn("[RedisService] SCAN 중 오류: {}", e.getMessage());
                        }
                        return null;
                    });

            if (keys.isEmpty()) return usageMap;

            // 파이프라인으로 값 일괄 조회
            List<Object> values = redisTemplate.opsForValue().multiGet(keys);
            if (values != null) {
                for (int i = 0; i < keys.size(); i++) {
                    Object val = values.get(i);
                    if (val != null) {
                        try {
                            usageMap.put(keys.get(i), Long.parseLong(val.toString()));
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[RedisService] 패턴 조회 실패 pattern={} : {}", pattern, e.getMessage());
            // 로컬 캐시 폴백
            String prefix = pattern.replace("*", "").replace("?", "");
            localCache.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(prefix))
                    .forEach(entry -> {
                        try {
                            usageMap.put(entry.getKey(),
                                    Long.parseLong(entry.getValue().toString()));
                        } catch (NumberFormatException ignored) {}
                    });
        }
        return usageMap;
    }

    //현재 레디스가 연결된 상태인지 확인
    public boolean isRedisAvailable(){
        try {
            String pong = redisTemplate.getConnectionFactory().getConnection().ping();
            return "PONG".equals(pong);
        }catch(Exception e){
            log.error("Redis 연결상태 확인 실패");
            return false;
        }
    }

    /** RedisTemplate 직접 접근 (삭제 등 고수준 작업용) */
    public RedisTemplate<String, Object> getRedisTemplate() {
        return redisTemplate;
    }
}
