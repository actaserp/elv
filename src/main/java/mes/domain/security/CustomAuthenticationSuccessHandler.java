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
import mes.config.RoutingDataSource;
import mes.config.TenantDataSourceManager;
import mes.domain.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import mes.domain.model.AjaxResult;
import mes.domain.services.AccountService;

@Slf4j
@Component
public class CustomAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

	@Autowired
	AccountService accountService;

	@Autowired
	RoutingDataSource routingDataSource;

	@Autowired
	TenantDataSourceManager tenantDataSourceManager;

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException, ServletException {

		User user = (User) authentication.getPrincipal();
		String spjangcd = user.getSpjangcd(); // CustomAuthenticationManager에서 세팅됨

		// 테넌트 DB 라우팅 키 설정
		TenantContext.setDbKey(spjangcd);

		// 테넌트 DB에서 사업장 목록 조회 (단일 DB이므로 대부분 1개)
		List<Map<String, Object>> spjangList = loadSpjangList(spjangcd);

		// 세션 저장
		HttpSession session = request.getSession(true);
		session.setAttribute("db_key", spjangcd);
		session.setAttribute("spjangcd_login", spjangcd); // 로그인 URL 복원용
		session.setAttribute("is_superuser", Boolean.TRUE.equals(user.getSuperUser()));

		if (spjangList.size() == 1) {
			String tenantSpjangcd = (String) spjangList.get(0).get("spjangcd");
			session.setAttribute("spjangcd", tenantSpjangcd);
			TenantContext.set(tenantSpjangcd);
		} else if (spjangList.size() > 1) {
			log.info("사업장 복수 선택 필요: spjangcd={}, count={}", spjangcd, spjangList.size());
		}

		// 로그인 로그
		this.accountService.saveLoginLog("login", authentication, request);

		// 응답
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.message = "OK";
		result.data = spjangList.size() > 1 ? spjangList : "OK";

		response.setCharacterEncoding("UTF-8");
		response.setStatus(HttpServletResponse.SC_OK);
		response.getWriter().print(new ObjectMapper().writeValueAsString(result));
		response.getWriter().flush();

		TenantContext.clear();
	}

	/**
	 * 테넌트 DB의 tb_xa012에서 사업장 목록 조회
	 * (이미 RoutingDataSource에 spjangcd로 등록된 DB에서 직접 조회)
	 */
	private List<Map<String, Object>> loadSpjangList(String spjangcd) {
		try {
			TenantContext.setDbKey(spjangcd);
			JdbcTemplate tenantJdbc = new JdbcTemplate(routingDataSource);
			return tenantJdbc.queryForList("SELECT spjangcd, spjangnm FROM tb_xa012 ORDER BY spjangcd");
		} catch (Exception e) {
			log.error("사업장 목록 조회 실패: spjangcd={}", spjangcd, e);
			return List.of(Map.of("spjangcd", spjangcd, "spjangnm", spjangcd));
		}
	}
}
