package mes.app.Scheduler.SchedulerService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ApiUsageService {

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * ★ 본사 DB(PostgreSQL) 전용 러너.
     *   product / tenant_product / api_log_entry / tb_xa012 는 모두 본사 DB 에만 존재한다.
     *   일반 SqlRunner 를 쓰면 접속 계정에 따라 사업체 DB(SQL Server) 로 라우팅되어
     *   "개체 이름 'product'이(가) 유효하지 않습니다" 오류가 발생한다.
     */
    private final SqlRunner sqlRunner;

    public ApiUsageService(RedisTemplate<String, Object> redisTemplate,
                           @Qualifier("mainSqlRunner") SqlRunner sqlRunner) {
        this.redisTemplate = redisTemplate;
        this.sqlRunner = sqlRunner;
    }

    /**
     * 전월 API 호출량을 Redis → api_log_entry 로 이관한다.
     *
     * <p>Redis 키 형식 2가지를 모두 처리한다.
     * <ul>
     *   <li>신형식 {@code MES:{사업장}:{상품}:{yyyyMMdd}} → 해당 상품으로 집계</li>
     *   <li>구형식 {@code MES:{사업장}:{yyyyMMdd}} → P01 로 간주 (상품 도입 이전 데이터)</li>
     * </ul>
     *
     * <p>요금제는 {@code tenant_product + product} 기준이며,
     * 계약 정보가 없으면 {@code product} 마스터 값을 사용한다.
     * 요금제 값은 "스냅샷"으로 저장되어 이후 요금이 바뀌어도 과거 청구 정합성이 유지된다.
     */
    @Transactional
    public void migrateMonthlyApiUsage() {
        log.info("==== 전월 API 호출 집계 시작 ====");

        String lastMonthPattern = LocalDate.now().minusMonths(1).format(DateTimeFormatter.ofPattern("yyyyMM"));

        // 신형식(MES:사업장:상품:날짜) / 구형식(MES:사업장:날짜) 모두 스캔
        List<String> searchPatterns = List.of(
                "MES:*:*:" + lastMonthPattern + "*",   // 신형식
                "MES:*:" + lastMonthPattern + "*"      // 구형식
        );
        log.info("[1] 스캔 패턴: {}", searchPatterns);

        // 상품 마스터 (요금 스냅샷용)
        Map<String, Map<String, Object>> productMap = getProductMap();

        // 사업장명
        Map<String, String> spjangNameMap = getSpjangNameMap();

        List<MapSqlParameterSource> batchList = new ArrayList<>();
        Set<String> keysToDelete = new HashSet<>();

        for (String pattern : searchPatterns) {
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(1000).build();

            redisTemplate.execute((RedisCallback<Void>) connection -> {
                try (Cursor<byte[]> cursor = connection.scan(options)) {
                    while (cursor.hasNext()) {
                        String key = new String(cursor.next());

                        // 이미 처리한 키는 건너뜀 (패턴이 겹칠 수 있음)
                        if (keysToDelete.contains(key)) continue;

                        String[] parts = key.split(":");

                        String spjangcd;
                        String productCd;
                        String dateStr;

                        if (parts.length == 4) {
                            // 신형식: MES:사업장:상품:날짜
                            spjangcd  = parts[1];
                            productCd = parts[2];
                            dateStr   = parts[3];
                        } else if (parts.length == 3) {
                            // 구형식: MES:사업장:날짜  → 상품 도입 이전이므로 P01 로 간주
                            spjangcd  = parts[1];
                            productCd = "P01";
                            dateStr   = parts[2];
                        } else {
                            log.warn("[경고] 키 형식이 맞지 않음: {}", key);
                            continue;
                        }

                        // 날짜 자리가 실제 날짜가 아니면 skip (패턴 오매칭 방지)
                        if (!dateStr.matches("\\d{8}")) {
                            continue;
                        }

                        Object val = redisTemplate.opsForValue().get(key);
                        long count = (val != null) ? Long.parseLong(val.toString()) : 0;

                        Map<String, Object> product = productMap.get(productCd);
                        String productNm = (product != null)
                                ? String.valueOf(product.getOrDefault("product_nm", productCd))
                                : "알수없음(" + productCd + ")";

                        Object priceObj = (product != null) ? product.get("price") : null;
                        BigDecimal price = (priceObj != null) ? new BigDecimal(String.valueOf(priceObj)) : BigDecimal.ZERO;

                        Object limitObj = (product != null) ? product.get("api_call_limit") : null;
                        int limit = (limitObj != null) ? Integer.parseInt(String.valueOf(limitObj)) : 0;

                        Object extraObj = (product != null) ? product.get("extra_unit_price") : null;
                        BigDecimal extraUnitPrice = (extraObj != null) ? new BigDecimal(String.valueOf(extraObj)) : BigDecimal.ZERO;

                        String spjangnm = spjangNameMap.getOrDefault(spjangcd, "알수없음");

                        LocalDate rowDate = LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyyMMdd"));
                        batchList.add(new MapSqlParameterSource()
                                .addValue("stat_day", Date.valueOf(rowDate))
                                .addValue("spjangcd", spjangcd)
                                .addValue("product_cd", productCd)
                                .addValue("spjangnm", spjangnm)
                                .addValue("bill_plan_name", productNm)
                                .addValue("price", price)
                                .addValue("api_call_limit", limit)
                                .addValue("extra_api_unit_price", extraUnitPrice)
                                .addValue("total_count", count)
                        );
                        keysToDelete.add(key);
                    }
                } catch (Exception e) {
                    log.error("[에러] SCAN 처리 중 예외 발생 pattern={}", pattern, e);
                }
                return null;
            });
        }

        if (batchList.isEmpty()) {
            log.warn("[종료] 이관할 데이터가 없습니다. (Redis에 해당 월 키가 없을 수 있음)");
            return;
        }

        String sql = """
                INSERT INTO api_log_entry (
                    stat_day, spjangcd, product_cd, spjangnm, bill_plan_name,
                    total_count, price, api_call_limit, extra_api_unit_price
                )
                VALUES (
                    :stat_day, :spjangcd, :product_cd, :spjangnm, :bill_plan_name,
                    :total_count, :price, :api_call_limit, :extra_api_unit_price
                )
                ON CONFLICT (stat_day, spjangcd, product_cd)
                DO UPDATE SET
                    total_count          = EXCLUDED.total_count,
                    spjangnm             = EXCLUDED.spjangnm,
                    bill_plan_name       = EXCLUDED.bill_plan_name,
                    price                = EXCLUDED.price,
                    api_call_limit       = EXCLUDED.api_call_limit,
                    extra_api_unit_price = EXCLUDED.extra_api_unit_price
        """;

        SqlParameterSource[] batchArgs = batchList.toArray(new SqlParameterSource[0]);
        int[] result = sqlRunner.batchUpdate(sql, batchArgs);
        log.info("[2] DB 이관 완료 행 수: {}", result.length);

        if (!keysToDelete.isEmpty()) {
            redisTemplate.delete(keysToDelete);
            log.info("[3] Redis 키 {}건 삭제 완료. {} 월분 이관 종료", keysToDelete.size(), lastMonthPattern);
        }
    }

    /** 상품 마스터 (요금 스냅샷용) */
    private Map<String, Map<String, Object>> getProductMap() {
        String sql = """
                SELECT product_cd, product_nm, price, api_call_limit, extra_unit_price
                  FROM product
                 WHERE useyn = '1'
                """;
        List<Map<String, Object>> rows = sqlRunner.getRows(sql, new MapSqlParameterSource());
        if (rows == null || rows.isEmpty()) {
            log.warn("[경고] product 마스터 조회 결과가 없습니다. DDL 실행 여부를 확인하세요.");
            return new HashMap<>();
        }
        return rows.stream().collect(Collectors.toMap(
                m -> String.valueOf(m.get("product_cd")),
                m -> m,
                (oldVal, newVal) -> oldVal
        ));
    }

    /** 사업장코드 → 사업장명 */
    private Map<String, String> getSpjangNameMap() {
        String sql = """
                SELECT spjangcd, spjangnm
                  FROM tb_xa012
                 WHERE state = 'O'
                """;
        List<Map<String, Object>> rows = sqlRunner.getRows(sql, new MapSqlParameterSource());
        Map<String, String> map = new HashMap<>();
        if (rows == null) return map;
        for (Map<String, Object> row : rows) {
            map.put(String.valueOf(row.get("spjangcd")),
                    String.valueOf(row.getOrDefault("spjangnm", "")));
        }
        return map;
    }
}
