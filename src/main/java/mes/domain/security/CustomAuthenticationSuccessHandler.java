package mes.domain.security;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.domain.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import mes.domain.model.AjaxResult;
import mes.domain.services.AccountService;

import javax.sql.DataSource;

@Slf4j
@Component
public class CustomAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

	@Autowired
	AccountService accountService;

	@Autowired
	@Qualifier("mainDataSource")
	DataSource mainDataSource;

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException, ServletException {

		User user = (User) authentication.getPrincipal();
		String dbKey = user.getSpjangcd(); // 메인 DB tb_xa012.spjangcd (DB 라우팅 키)

		// 1. 테넌트 DB의 사업장 목록 조회
		List<Map<String, Object>> spjangList = loadTenantSpjangList(dbKey);

		// 2. 세션에 저장
		HttpSession session = request.getSession(true);
		session.setAttribute("db_key", dbKey);

		if (spjangList.size() == 1) {
			// 사업장이 1개면 자동 선택
			String tenantSpjangcd = (String) spjangList.get(0).get("spjangcd");
			session.setAttribute("spjangcd", tenantSpjangcd);
			TenantContext.set(tenantSpjangcd);
		} else if (spjangList.size() > 1) {
			// 복수 사업장 – 프론트엔드에서 선택 필요 (spjangcd 미설정)
			log.info("사업장 복수 선택 필요: dbKey={}, count={}", dbKey, spjangList.size());
		}
		TenantContext.setDbKey(dbKey);

		// 3. 로그인 로그 저장
		this.accountService.saveLoginLog("login", authentication, request);

		// 4. 응답
		ObjectMapper mapper = new ObjectMapper();
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.message = "OK";

		if (spjangList.size() > 1) {
			// 복수 사업장일 때 목록 반환
			result.data = spjangList;
		} else {
			result.data = "OK";
		}

		response.setCharacterEncoding("UTF-8");
		response.setStatus(HttpServletResponse.SC_OK);
		response.getWriter().print(mapper.writeValueAsString(result));
		response.getWriter().flush();

		TenantContext.clear();
	}

	/** host:port/dbname + db_type → JDBC URL 조립 */
	private String buildJdbcUrl(String url, String dbType) {
		if (url.startsWith("jdbc:")) return url;
		String[] parts = url.split("/", 2);
		String hostPort = parts[0];
		String dbName   = parts.length > 1 ? parts[1] : "";
		if ("mssql".equalsIgnoreCase(dbType)) {
			return "jdbc:sqlserver://" + hostPort + ";databaseName=" + dbName
					+ ";encrypt=false;trustServerCertificate=false";
		} else {
			return "jdbc:postgresql://" + hostPort + "/" + dbName;
		}
	}

	/**
	 * 메인 DB의 tb_xa012에서 해당 dbKey에 연결된 테넌트 DB의 사업장 목록을 조회합니다.
	 * dbKey → db_url/db_username/db_password → 테넌트 DB 접속 → tb_xa012 조회
	 */
	private List<Map<String, Object>> loadTenantSpjangList(String dbKey) {
		try {
			JdbcTemplate mainJdbc = new JdbcTemplate(mainDataSource);
			Map<String, Object> tenantDbInfo = mainJdbc.queryForMap(
					"SELECT db_url, db_username, db_password, db_type FROM tb_xa012 WHERE spjangcd = ?", dbKey);

			if (tenantDbInfo == null) return List.of();

			String dbUrl      = (String) tenantDbInfo.get("db_url");
			String dbUsername = (String) tenantDbInfo.get("db_username");
			String dbPassword = (String) tenantDbInfo.get("db_password");
			String dbType     = (String) tenantDbInfo.get("db_type");

			if (dbUrl == null || dbUrl.isBlank()) {
				// 테넌트 DB 없음 → 메인 DB 자체 사용 (spjangcd = dbKey)
				return List.of(Map.of("spjangcd", dbKey, "spjangnm", dbKey));
			}

			String jdbcUrl = buildJdbcUrl(dbUrl, dbType);

			// 테넌트 DB에서 사업장 목록 조회
			com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
			config.setJdbcUrl(jdbcUrl);
			config.setUsername(dbUsername);
			config.setPassword(dbPassword);
			config.setMaximumPoolSize(2);
			config.setConnectionTimeout(5000);
			try (com.zaxxer.hikari.HikariDataSource ds = new com.zaxxer.hikari.HikariDataSource(config)) {
				JdbcTemplate tenantJdbc = new JdbcTemplate(ds);
				return tenantJdbc.queryForList("SELECT spjangcd, spjangnm FROM tb_xa012 ORDER BY spjangcd");
			}
		} catch (Exception e) {
			log.error("테넌트 사업장 목록 조회 실패: dbKey={}", dbKey, e);
			return List.of();
		}
	}
}
