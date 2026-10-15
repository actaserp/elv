package mes.app.system.service;

import lombok.extern.slf4j.Slf4j;
import mes.config.TenantDataSourceManager;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class TenantDbService {

    @Autowired
    @Qualifier("mainSqlRunner")
    SqlRunner sqlRunner;

    @Autowired
    TenantDataSourceManager tenantDataSourceManager;

    public List<Map<String, Object>> getList(String keyword) {
        MapSqlParameterSource param = new MapSqlParameterSource();

        String sql = """
                SELECT t.id, t.spjangcd, x.spjangnm, t.db_alias, t.db_type, t.db_url, t.db_username, t.db_password, t.pool_size, t.custcd,
                       COALESCE(t.cti_lines, '') AS cti_lines
                FROM tb_tenant_db t
                LEFT JOIN tb_xa012 x ON x.spjangcd = t.spjangcd
                WHERE 1=1
                """;

        if (keyword != null && !keyword.isBlank()) {
            sql += " AND spjangcd ILIKE :keyword";
            param.addValue("keyword", "%" + keyword + "%");
        }

        sql += " ORDER BY id";

        List<Map<String, Object>> rows = sqlRunner.getRows(sql, param);

        Set<String> connectedKeys = tenantDataSourceManager.getConnectedKeys();

        for (Map<String, Object> row : rows) {
            String spjangcd = (String) row.get("spjangcd");
            String dbAlias  = (String) row.get("db_alias");
            String routingKey = "main".equalsIgnoreCase(dbAlias) ? spjangcd : spjangcd + ":" + dbAlias;
            row.put("connected", connectedKeys.contains(routingKey) ? "연결됨" : "실패");
        }

        return rows;
    }

    /**
     * 저장 후 해당 DataSource만 개별 재연결.
     * @return Map { connected, errorMsg, totalConnected, totalFailed }
     */
    public Map<String, Object> save(Map<String, Object> param) {
        String id          = param.get("id") != null ? param.get("id").toString() : null;
        String spjangcd    = (String) param.get("spjangcd");
        String dbAlias     = param.getOrDefault("db_alias", "main").toString();
        String dbType      = param.getOrDefault("db_type", "postgresql").toString();
        String dbUrl       = (String) param.get("db_url");
        String dbUsername  = (String) param.get("db_username");
        String dbPassword  = (String) param.get("db_password");
        int    poolSize    = param.get("pool_size") != null ? ((Number) param.get("pool_size")).intValue() : 5;
        String custcd      = extractCustcd(dbUrl);

        // KT 회선번호 — 전화가 오면 어느 사업체 화면에 띄울지 가리는 열쇠.
        // 숫자만 남기고 쉼표로 잇는다(입력은 010-1234-5678 처럼 해도 된다).
        String ctiLines    = normalizeLines((String) param.get("cti_lines"));

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("spjangcd",    spjangcd);
        p.addValue("db_alias",    dbAlias);
        p.addValue("db_type",     dbType);
        p.addValue("db_url",      dbUrl);
        p.addValue("db_username", dbUsername);
        p.addValue("pool_size",   poolSize);
        p.addValue("custcd",      custcd);
        p.addValue("cti_lines",   ctiLines);

        if (id == null || id.isBlank()) {
            if (dbPassword == null || dbPassword.isBlank()) {
                throw new IllegalArgumentException("신규 등록 시 비밀번호는 필수입니다.");
            }
            p.addValue("db_password", dbPassword);
            sqlRunner.execute("""
                    INSERT INTO tb_tenant_db (spjangcd, db_alias, db_type, db_url, db_username, db_password, pool_size, custcd, cti_lines)
                    VALUES (:spjangcd, :db_alias, :db_type, :db_url, :db_username, :db_password, :pool_size, :custcd, :cti_lines)
                    """, p);
        } else {
            p.addValue("id", Long.parseLong(id));

            // 비밀번호를 뺀 항목은 언제나 저장한다.
            // 예전에는 이 UPDATE 가 비밀번호 블록 안에만 있어서, 비밀번호를 비워 두면
            // 회선번호만 고쳐도 아무것도 저장되지 않았다.
            sqlRunner.execute("""
                    UPDATE tb_tenant_db
                    SET db_alias=:db_alias, db_type=:db_type, db_url=:db_url,
                        db_username=:db_username, pool_size=:pool_size, custcd=:custcd,
                        cti_lines=:cti_lines
                    WHERE id=:id
                    """, p);

            // 비밀번호는 넣었을 때만 바꾼다(비워 두면 쓰던 것을 유지)
            if (dbPassword != null && !dbPassword.isBlank()) {
                p.addValue("db_password", dbPassword);
                sqlRunner.execute("UPDATE tb_tenant_db SET db_password=:db_password WHERE id=:id", p);
            }
        }

        // 해당 DataSource만 개별 재연결 (다른 테넌트 영향 없음)
        String connectError = tenantDataSourceManager.addOrReplaceTenantDataSource(
                spjangcd, dbAlias, dbUrl, dbUsername,
                dbPassword != null ? dbPassword : getStoredPassword(id),
                dbType, poolSize);

        return buildStats(connectError);
    }

    // ── KT 회선번호 ──────────────────────────────────────────

    /**
     * 입력한 회선번호를 '숫자만, 쉼표로 이음' 꼴로 다듬는다.
     * 010-1234-5678, (02)123-4567 처럼 적어도 받는다 — 견줄 때 형식이 같아야 한다.
     */
    static String normalizeLines(String raw) {
        if (raw == null || raw.isBlank()) return null;

        var out = new java.util.LinkedHashSet<String>();
        for (String one : raw.split("[,;\\n]")) {
            String digits = one.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) out.add(digits);
        }
        return out.isEmpty() ? null : String.join(",", out);
    }

    /**
     * 회선번호로 사업체(db_key)를 찾는다. 전화가 올 때마다 쓰므로 가볍게 둔다.
     * 못 찾으면 null — 부른 쪽이 로그를 남기고 넘어간다.
     */
    public String findSiteByLine(String line) {
        String digits = line == null ? "" : line.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return null;

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("line", digits);

        // 쉼표로 이어 둔 목록이라 통째로 LIKE 하면 0269595020 이 10269595020 에도 걸린다.
        // 양끝에 쉼표를 둘러 토막 단위로 견준다.
        Map<String, Object> row = sqlRunner.getRow("""
                SELECT spjangcd FROM tb_tenant_db
                 WHERE cti_lines IS NOT NULL
                   AND ',' || REPLACE(cti_lines, ' ', '') || ',' LIKE '%,' || :line || ',%'
                 LIMIT 1
                """, p);

        return row == null ? null : (String) row.get("spjangcd");
    }

    /**
     * 사업체(db_key) 안에서 쓰는 사업장코드. 사업체 DB 의 조회 조건에 들어간다.
     * 예: db_key 'GO' → spjangcd 'ZZ'
     *
     * 전화가 올 때마다 찾으면 본사 DB 를 매번 두드리게 되므로 한 번 찾은 건 기억해 둔다.
     * 못 찾으면 null — 부른 쪽이 고객명 조회를 건너뛴다.
     */
    private final Map<String, String> innerSpjangcdCache = new java.util.concurrent.ConcurrentHashMap<>();

    public String findInnerSpjangcd(String site) {
        if (site == null || site.isBlank()) return null;

        String cached = innerSpjangcdCache.get(site);
        if (cached != null) return cached;

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("site", site);

        Map<String, Object> row = sqlRunner.getRow("""
                SELECT spjangcd FROM tb_xa012
                 WHERE db_key = :site AND COALESCE(spjangcd, '') <> ''
                 LIMIT 1
                """, p);

        if (row == null || row.get("spjangcd") == null) {
            log.warn("[CTI] 사업장코드를 찾지 못했습니다. db_key={} — 고객명 없이 알림만 보냅니다", site);
            return null;
        }

        String value = row.get("spjangcd").toString().trim();
        innerSpjangcdCache.put(site, value);
        return value;
    }

    /** 특정 row만 재연결 */
    public Map<String, Object> reconnectOne(long id) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", id);
        Map<String, Object> row = sqlRunner.getRow("""
                SELECT spjangcd, db_alias, db_type, db_url, db_username, db_password, pool_size
                FROM tb_tenant_db WHERE id = :id
                """, p);

        if (row == null) throw new IllegalArgumentException("해당 레코드를 찾을 수 없습니다: " + id);

        String connectError = tenantDataSourceManager.addOrReplaceTenantDataSource(
                (String) row.get("spjangcd"),
                (String) row.get("db_alias"),
                (String) row.get("db_url"),
                (String) row.get("db_username"),
                (String) row.get("db_password"),
                (String) row.get("db_type"),
                row.get("pool_size") != null ? ((Number) row.get("pool_size")).intValue() : 5
        );

        return buildStats(connectError);
    }

    /** 전체 재로드 */
    public Map<String, Object> reloadAll() {
        tenantDataSourceManager.loadTenantDataSources();
        return buildStats(null);
    }

    private Map<String, Object> buildStats(String connectError) {
        int totalConnected = tenantDataSourceManager.getConnectedKeys().size();
        int totalFailed    = tenantDataSourceManager.getFailedKeys().size();
        return Map.of(
                "connected",      connectError == null,
                "errorMsg",       connectError != null ? connectError : "",
                "totalConnected", totalConnected,
                "totalFailed",    totalFailed
        );
    }

    private String getStoredPassword(String id) {
        if (id == null || id.isBlank()) return null;
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", Long.parseLong(id));
        Map<String, Object> row = sqlRunner.getRow("SELECT db_password FROM tb_tenant_db WHERE id=:id", p);
        return row != null ? (String) row.get("db_password") : null;
    }

    private String extractCustcd(String dbUrl) {
        if (dbUrl == null || dbUrl.isBlank()) return null;
        if (dbUrl.startsWith("jdbc:")) {
            int lastSlash = dbUrl.lastIndexOf('/');
            if (lastSlash >= 0) return dbUrl.substring(lastSlash + 1).split("[?;]")[0];
            return null;
        }
        if (dbUrl.contains("/")) return dbUrl.split("/", 2)[1].split("[?;]")[0];
        String[] parts = dbUrl.split(":");
        return parts.length >= 3 ? parts[parts.length - 1] : null;
    }
}
