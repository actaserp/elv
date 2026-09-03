package mes.domain.security;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

public class CustomAccessDeniedHandler implements AccessDeniedHandler {

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			org.springframework.security.access.AccessDeniedException accessDeniedException)
			throws IOException, ServletException {

		// ── 세션이 끊긴 요청은 403(권한없음) 이 아니라 401 로 내려 로그인을 유도한다 ──
		// CsrfFilter 는 AnonymousAuthenticationFilter·ExceptionTranslationFilter 보다 앞이라
		// 토큰이 안 맞으면 이 핸들러를 직접 부른다. 그래서 Spring 이 원래 하던
		// "익명 사용자의 접근거부는 401" 처리가 건너뛰어져 저장(POST) 실패가 403 으로 나왔다.
		//
		// CsrfException 만으로 판단하면 로그인한 사용자에게 위조요청이 왔을 때도 로그아웃되므로
		// '로그인 안 된 상태' 를 같이 확인한다. 세션이 없으면 이 시점 Authentication 은 null 이다.
		// 진짜 권한 부족(/setup 등)은 아래 기존 처리 그대로 403 이다.
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		boolean notLoggedIn = (auth == null || auth instanceof AnonymousAuthenticationToken);

		if (accessDeniedException instanceof CsrfException && notLoggedIn) {
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED");
			return;
		}

        String requestedWithHeader = request.getHeader("X-Requested-With");
        
        // ajax 요청이면 status 만 리턴
        if("XMLHttpRequest".equals(requestedWithHeader)) {
        	response.sendError(HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN");        	
        	
        }else {
        	response.sendRedirect(request.getContextPath() + "/errors/403");
        }
	}
}