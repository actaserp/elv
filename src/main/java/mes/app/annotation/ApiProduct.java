package mes.app.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * API 사용량 집계 대상 상품을 지정한다.
 *
 * <p>컨트롤러 클래스(또는 개별 메서드)에 부착하면 해당 API 호출이
 * Redis 카운터 {@code MES:{사업장}:{상품코드}:{yyyyMMdd}} 에 집계된다.
 *
 * <p><b>어노테이션이 없는 API 는 집계하지 않는다.</b>
 * (MES 본체 기능 등 과금 대상이 아닌 API 는 부착하지 않으면 됨)
 *
 * <p>상품코드는 {@code product} 테이블 기준:
 * <ul>
 *   <li>P01 - 엘리베이터 유지보수 통합관리 (API 5만건 포함 / 초과 4원)</li>
 *   <li>P02 - 모바일 현장업무</li>
 *   <li>P03 - 기사 운영·차량운행 관리</li>
 *   <li>P04 - AI 기반 고장분석 (미구현)</li>
 *   <li>P05 - 계산서·은행거래·미수관리</li>
 * </ul>
 *
 * <pre>
 * &#64;ApiProduct(ApiProduct.P01)
 * &#64;RestController
 * &#64;RequestMapping("/api/AS/web_handle")
 * public class WebHandleController { ... }
 * </pre>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiProduct {

    String P01 = "P01";
    String P02 = "P02";
    String P03 = "P03";
    String P04 = "P04";
    String P05 = "P05";

    /** 상품코드 (product.product_cd) */
    String value();
}
