package mes.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class TenantDataSourceManager {

    @Autowired
    @Qualifier("mainDataSource")
    private DataSource mainDataSource;

    @Autowired
    private RoutingDataSource routingDataSource;

    @PostConstruct
    public void init() {
        loadTenantDataSources();
    }

    public void loadTenantDataSources() {
        JdbcTemplate jdbc = new JdbcTemplate(mainDataSource);

        String sql = """
                SELECT spjangcd, db_url, db_username, db_password, db_type
                FROM tb_xa012
                WHERE db_url IS NOT NULL AND db_url <> ''
                """;

        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(sql);
        } catch (Exception e) {
            log.error("tb_xa012 테넌트 DB 정보 로드 실패", e);
            return;
        }

        Map<Object, Object> targets = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String spjangcd   = (String) row.get("spjangcd");
            String dbUrl      = (String) row.get("db_url");
            String dbUsername = (String) row.get("db_username");
            String dbPassword = (String) row.get("db_password");
            String dbType     = (String) row.get("db_type"); // mssql / postgresql

            if (dbUrl == null || dbUrl.isBlank()) continue;

            log.info("테넌트 DB 연결 시도: spjangcd={}, db_url=[{}], db_type=[{}]", spjangcd, dbUrl, dbType);

            try {
                DataSource ds = createDataSource(dbUrl, dbUsername, dbPassword, dbType);
                targets.put(spjangcd, ds);
                log.info("테넌트 DataSource 로드 완료: spjangcd={}, url={}", spjangcd, dbUrl);
            } catch (Exception e) {
                log.error("테넌트 DataSource 생성 실패: spjangcd={}", spjangcd, e);
            }
        }

        routingDataSource.setTargetDataSources(targets);
        routingDataSource.afterPropertiesSet();
        log.info("총 {}개 테넌트 DataSource 등록 완료", targets.size());
    }

    private DataSource createDataSource(String url, String username, String password, String dbType) {
        String jdbcUrl = buildJdbcUrl(url, dbType);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setMaxLifetime(1800000);
        config.setIdleTimeout(600000);
        config.setConnectionTimeout(10000);
        return new HikariDataSource(config);
    }

    /**
     * db_url (host:port/dbname 형식) + db_type 으로 JDBC URL 조립
     * - mssql  → jdbc:sqlserver://host:port;databaseName=dbname;encrypt=false;trustServerCertificate=false
     * - 그 외  → jdbc:postgresql://host:port/dbname
     */
    private String buildJdbcUrl(String url, String dbType) {
        if (url.startsWith("jdbc:")) return url; // 이미 완전한 JDBC URL이면 그대로 사용

        // host:port/dbname 파싱
        String[] hostAndDb = url.split("/", 2);
        String hostPort = hostAndDb[0];
        String dbName   = hostAndDb.length > 1 ? hostAndDb[1] : "";

        if ("mssql".equalsIgnoreCase(dbType)) {
            return "jdbc:sqlserver://" + hostPort + ";databaseName=" + dbName
                    + ";encrypt=false;trustServerCertificate=false";
        } else {
            return "jdbc:postgresql://" + hostPort + "/" + dbName;
        }
    }
}
