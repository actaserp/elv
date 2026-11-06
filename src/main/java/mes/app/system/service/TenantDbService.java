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
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class TenantDbService {

    @Autowired
    @Qualifier("mainSqlRunner")
    SqlRunner sqlRunner;

    @Autowired
    TenantDataSourceManager tenantDataSourceManager;

    /**
     * 중간보고 사용여부 캐시.
     * 사업체가 7곳뿐이고 설정이 거의 바뀌지 않으므로 통째로 담아 둔다.
     * 관리 화면에서 저장하면 {@link #save} 가 비우므로 재로그인 없이 바로 반영된다.
     */
    private final Map<String, Boolean> midReportCache = new ConcurrentHashMap<>();

    /** 연차 산정기준 — 회계연도(매년 1/1 일괄 발생) */
    public static final String ANNUAL_BASIS_FISCAL = "F";
    /** 연차 산정기준 — 입사일(사원별 입사 기념일 발생) */
    public static final String ANNUAL_BASIS_ENTRY  = "E";

    /** 연차 산정기준 캐시. midReportCache 와 같은 이유로 통째로 담아 둔다 */
    private final Map<String, String> annualBasisCache = new ConcurrentHashMap<>();

    /**
     * 이 사업체가 중간보고를 쓰는가. tb_tenant_db.use_mid_report = 'N' 이면 끈다.
     * 컬럼이 비어 있으면 'Y' 로 본다 — 중간보고는 이미 배포돼 전 사업체가 쓰던 기능이라
     * 설정이 없다고 꺼지면 안 된다(cti_lines 와 같은 COALESCE 패턴).
     */
    public boolean isMidReportEnabled(String spjangcd) {
        if (spjangcd == null || spjangcd.isBlank()) return true;

        Boolean cached = midReportCache.get(spjangcd);
        if (cached != null) return cached;

        boolean enabled = true;
        try {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("spjangcd", spjangcd);
            Map<String, Object> row = sqlRunner.getRow("""
                    SELECT COALESCE(use_mid_report, 'Y') AS use_mid_report
                    FROM tb_tenant_db
                    WHERE spjangcd = :spjangcd AND db_alias = 'main'
                    """, p);
            if (row != null) {
                enabled = !"N".equals(row.get("use_mid_report"));
            }
        } catch (Exception e) {
            // 컬럼이 아직 없거나 조회가 실패하면 기존 동작(사용)을 유지한다
            log.warn("중간보고 사용여부 조회 실패 - 사용으로 간주합니다. spjangcd={}", spjangcd, e);
            return true;
        }

        midReportCache.put(spjangcd, enabled);
        return enabled;
    }

    /**
     * 이 사업체의 연차 산정기준. tb_tenant_db.annual_basis.
     *
     *   'F' 회계연도 — 매년 1/1 에 일괄 발생 (지금 모든 사업체의 동작)
     *   'E' 입사일   — 사원별 입사 기념일에 발생
     *
     * 컬럼이 비어 있으면 'F' 로 본다 — 연차는 이미 5개 사업체가 회계연도로 쓰고 있어
     * 설정이 없다고 기준이 바뀌면 안 된다(use_mid_report 와 같은 COALESCE 패턴).
     *
     * 이 값은 연차를 '생성'할 때만 쓴다. 조회는 TB_PB209 의 startdate·enddate 를
     * 따르므로, 사업체가 중간에 기준을 바꿔도 과거 발생행의 숫자는 깨지지 않는다.
     */
    public String getAnnualBasis(String spjangcd) {
        if (spjangcd == null || spjangcd.isBlank()) return ANNUAL_BASIS_FISCAL;

        String cached = annualBasisCache.get(spjangcd);
        if (cached != null) return cached;

        String basis = ANNUAL_BASIS_FISCAL;
        try {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("spjangcd", spjangcd);
            Map<String, Object> row = sqlRunner.getRow("""
                    SELECT COALESCE(annual_basis, 'F') AS annual_basis
                    FROM tb_tenant_db
                    WHERE spjangcd = :spjangcd AND db_alias = 'main'
                    """, p);
            if (row != null && ANNUAL_BASIS_ENTRY.equals(row.get("annual_basis"))) {
                basis = ANNUAL_BASIS_ENTRY;
            }
        } catch (Exception e) {
            // 컬럼이 아직 없거나 조회가 실패하면 기존 동작(회계연도)을 유지한다
            log.warn("연차 산정기준 조회 실패 - 회계연도로 간주합니다. spjangcd={}", spjangcd, e);
            return ANNUAL_BASIS_FISCAL;
        }

        annualBasisCache.put(spjangcd, basis);
        return basis;
    }

    /** 입사일 기준을 쓰는 사업체인가 */
    public boolean isEntryDateBasis(String spjangcd) {
        return ANNUAL_BASIS_ENTRY.equals(getAnnualBasis(spjangcd));
    }
    public List<Map<String, Object>> getList(String keyword) {
        MapSqlParameterSource param = new MapSqlParameterSource();

        String sql = """
                SELECT t.id, t.spjangcd, x.spjangnm, t.db_alias, t.db_type, t.db_url, t.db_username, t.db_password, t.pool_size, t.custcd,
                       COALESCE(t.cti_lines, '') AS cti_lines,
                       COALESCE(t.use_mid_report, 'Y') AS use_mid_report,
                       COALESCE(t.annual_basis, 'F')   AS annual_basis
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

        // 중간보고 사용여부 — 'N' 이라고 분명히 보내지 않으면 'Y'.
        // 기존 사업체는 컬럼이 'Y' 이므로 화면을 거치지 않는 저장에도 영향이 없다.
        Object midFlag      = param.get("use_mid_report");
        String useMidReport = (midFlag != null && "N".equalsIgnoreCase(String.valueOf(midFlag).trim()))
                              ? "N" : "Y";

        // 연차 산정기준 — 'E'(입사일) 라고 분명히 보내지 않으면 'F'(회계연도).
        // 지금 돌아가는 사업체는 모두 회계연도라, 화면을 거치지 않는 저장에도 영향이 없다.
        Object basisFlag   = param.get("annual_basis");
        String annualBasis = (basisFlag != null
                              && ANNUAL_BASIS_ENTRY.equalsIgnoreCase(String.valueOf(basisFlag).trim()))
                             ? ANNUAL_BASIS_ENTRY : ANNUAL_BASIS_FISCAL;

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("spjangcd",    spjangcd);
        p.addValue("db_alias",    dbAlias);
        p.addValue("db_type",     dbType);
        p.addValue("db_url",      dbUrl);
        p.addValue("db_username", dbUsername);
        p.addValue("pool_size",   poolSize);
        p.addValue("custcd",      custcd);
        p.addValue("cti_lines",   ctiLines);
        p.addValue("use_mid_report", useMidReport);
        p.addValue("annual_basis",   annualBasis);

        if (id == null || id.isBlank()) {
            if (dbPassword == null || dbPassword.isBlank()) {
                throw new IllegalArgumentException("신규 등록 시 비밀번호는 필수입니다.");
            }
            p.addValue("db_password", dbPassword);
            sqlRunner.execute("""
                    INSERT INTO tb_tenant_db (spjangcd, db_alias, db_type, db_url, db_username, db_password, pool_size, custcd, cti_lines, use_mid_report, annual_basis)
                    VALUES (:spjangcd, :db_alias, :db_type, :db_url, :db_username, :db_password, :pool_size, :custcd, :cti_lines, :use_mid_report, :annual_basis)
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
                        cti_lines=:cti_lines, use_mid_report=:use_mid_report,
                        annual_basis=:annual_basis
                    WHERE id=:id
                    """, p);

            // 비밀번호는 넣었을 때만 바꾼다(비워 두면 쓰던 것을 유지)
            if (dbPassword != null && !dbPassword.isBlank()) {
                p.addValue("db_password", dbPassword);
                sqlRunner.execute("UPDATE tb_tenant_db SET db_password=:db_password WHERE id=:id", p);
            }
        }

        // 중간보고 사용여부가 바뀌었을 수 있으니 캐시를 비운다 (재로그인 없이 반영된다)
        midReportCache.remove(spjangcd);
        annualBasisCache.remove(spjangcd);

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
     * 이 사업체에 KT 회선번호가 등록돼 있는가.
     *
     * 없으면 에이전트가 아무리 잘 돌아도 서버가 그 PC 를 어느 사업체로 보낼지 몰라
     * 전화가 화면에 뜨지 않는다. 화면이 '프로그램이 꺼져 있다'고 엉뚱하게 말하지
     * 않도록, 눌리기 전에 이걸 먼저 본다.
     */
    public boolean hasLine(String site) {
        if (site == null || site.isBlank()) return false;

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("site", site);

        Map<String, Object> row = sqlRunner.getRow("""
                SELECT 1 AS ok FROM tb_tenant_db
                 WHERE spjangcd = :site AND COALESCE(cti_lines, '') <> ''
                 LIMIT 1
                """, p);

        return row != null;
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

        // tb_xa012 에는 db_key 가 없다. 거기 spjangcd 는 사업체 코드(GO·AN·DJ…) 자체이고,
        // 사업체 DB 안에서 쓰는 사업장코드는 다른 값이다 (GO → ZZ).
        //
        // 그 값을 들고 있는 건 auth_user 다. 화면도 user.getSpjangcd() 로 같은 값을 써서
        // 사업체 DB 를 조회한다 — 통화기록도 같은 값으로 넣어야 짝이 맞는다.
        //
        // 한 사업체의 사용자가 서로 다른 값을 들고 있을 수 있어 가장 많은 쪽을 택한다.
        Map<String, Object> row = sqlRunner.getRow("""
                SELECT spjangcd
                  FROM auth_user
                 WHERE db_key = :site AND COALESCE(spjangcd, '') <> ''
                 GROUP BY spjangcd
                 ORDER BY COUNT(*) DESC
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
