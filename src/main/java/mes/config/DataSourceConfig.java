package mes.config;

import java.util.HashMap;
import java.util.List;

import javax.sql.DataSource;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.app.interceptor.TenantSqlInspector;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@EnableJpaRepositories(basePackages = "mes.domain.repository", entityManagerFactoryRef = "entityManagerFactory", transactionManagerRef = "transactionManagerCustom")
@Configuration
public class DataSourceConfig {

    // ──────────────────────────────────────────────────────────────────────────
    // 1. Main DataSource (메인 DB – 인증/JPA 엔티티 전용, 항상 메인 DB)
    // ──────────────────────────────────────────────────────────────────────────

    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    @Bean(name = "mainDataSource")
    @Primary
    DataSource mainDataSource() {
        return DataSourceBuilder.create().build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 2. Routing DataSource (SqlRunner 전용 – 테넌트 DB 라우팅)
    //    dbKey 없으면 mainDataSource 로 fallback
    // ──────────────────────────────────────────────────────────────────────────

    @Bean
    RoutingDataSource routingDataSource(@Qualifier("mainDataSource") DataSource mainDataSource) {
        RoutingDataSource routingDataSource = new RoutingDataSource();
        routingDataSource.setDefaultTargetDataSource(mainDataSource);
        routingDataSource.setTargetDataSources(new HashMap<>());
        routingDataSource.afterPropertiesSet();
        return routingDataSource;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 3. JdbcTemplate / NamedParameterJdbcTemplate → routingDataSource 사용
    // ──────────────────────────────────────────────────────────────────────────

    @Bean("jdbcTemplate")
    JdbcTemplate jdbcTemplate(RoutingDataSource routingDataSource) {
        return new JdbcTemplate(routingDataSource) {
            @Override
            public <T> T queryForObject(String sql, RowMapper<T> rowMapper) {
                validateTenantIsolation(sql);
                return super.queryForObject(sql, rowMapper);
            }

            @Override
            public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
                validateTenantIsolation(sql);
                return super.query(sql, rowMapper);
            }

            @Override
            public <T> List<T> query(String sql, Object[] args, RowMapper<T> rowMapper) {
                validateTenantIsolation(sql);
                return super.query(sql, args, rowMapper);
            }

            @Override
            public int update(String sql, Object... args) {
                validateTenantIsolation(sql);
                return super.update(sql, args);
            }

            @Override
            public void execute(String sql) {
                validateTenantIsolation(sql);
                super.execute(sql);
            }
        };
    }

    @Bean("namedParameterJdbcTemplate")
    @Primary
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(@Qualifier("jdbcTemplate") JdbcTemplate jdbcTemplate) {
        return new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 4. JPA → mainDataSource 고정 (라우팅 없이 항상 메인 DB)
    // ──────────────────────────────────────────────────────────────────────────

    @Bean(name = "entityManagerFactory")
    LocalContainerEntityManagerFactoryBean entityManagerFactory(@Qualifier("mainDataSource") DataSource mainDataSource) {
        LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(mainDataSource);
        emf.setPackagesToScan(new String[]{"mes.domain.entity"});
        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        emf.setJpaVendorAdapter(vendorAdapter);
        HashMap<String, Object> properties = new HashMap<>();
        properties.put("hibernate.session_factory.statement_inspector", new TenantSqlInspector());
        properties.put("hibernate.ddl-auto", "validate");
        properties.put("hibernate.format_sql", true);
        properties.put("hibernate.show-sql", true);
        properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        emf.setJpaPropertyMap(properties);
        return emf;
    }

    @Bean
    @Primary
    PlatformTransactionManager transactionManagerCustom(@Qualifier("mainDataSource") DataSource mainDataSource) {
        JpaTransactionManager transactionManager = new JpaTransactionManager();
        transactionManager.setEntityManagerFactory(this.entityManagerFactory(mainDataSource).getObject());
        return transactionManager;
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 5. MyBatis → mainDataSource (로그/공통 mapper 용)
    // ──────────────────────────────────────────────────────────────────────────

    @Bean
    SqlSessionFactory sqlSessionFactory(@Qualifier("mainDataSource") DataSource mainDataSource) throws Exception {
        final SqlSessionFactoryBean sessionFactory = new SqlSessionFactoryBean();
        sessionFactory.setDataSource(mainDataSource);
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        sessionFactory.setMapperLocations(resolver.getResources("mapper/*.xml"));
        return sessionFactory.getObject();
    }

    @Bean
    SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) throws Exception {
        return new SqlSessionTemplate(sqlSessionFactory);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 공통 검증 로직
    // ──────────────────────────────────────────────────────────────────────────

    private void validateTenantIsolation(String sql) {
        // 메인 DB 라우팅이 강제된 경우 검증 생략
        if (TenantContext.isForceMainDb()) return;

        String tenantId = TenantContext.get();
        String lowSql = sql.toLowerCase();

        if (lowSql.contains("tb_user") || lowSql.contains("menu_use_log") || lowSql.contains("sys_common_code")) {
            return;
        }

        if (tenantId == null) {
            throw new SecurityException("인증된 테넌트 정보가 없습니다. DB 접근이 차단되었습니다.");
        }

        if (!lowSql.contains("spjangcd")) {
            log.error("!!! 격리 정책 위반 감지 !!! 실행 쿼리: {}", sql);
            throw new SecurityException("데이터 격리 정책 위반: 쿼리에 spjangcd 조건이 누락되었습니다.");
        }
    }
}
