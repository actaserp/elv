package mes.config;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

@Slf4j
public class RoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        // 메인 DB 강제 플래그가 있으면 null 반환 → defaultTargetDataSource(mainDataSource) 사용
        if (TenantContext.isForceMainDb()) {
            log.debug("[RoutingDataSource] forceMainDb=true → mainDataSource");
            return null;
        }
        String dbKey = TenantContext.getDbKey();
        log.debug("[RoutingDataSource] dbKey={}", dbKey);
        return dbKey;
    }
}
