package mes.config;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
//import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import mes.domain.security.AjaxAwareLoginUrlAuthenticationEntryPoint;

import mes.domain.security.CustomAccessDeniedHandler;
import mes.domain.security.CustomAuthenticationFailureHandler;
import mes.domain.security.CustomAuthenticationManager;
import mes.domain.security.CustomAuthenticationSuccessHandler;
import mes.domain.security.TenantWebAuthenticationDetailsSource;
import org.springframework.beans.factory.annotation.Value;


@Configuration
@ComponentScan("mes.domain.security")
public class SecurityConfiguration {
	
	@Autowired
	private CustomAuthenticationSuccessHandler customAuthenticationSuccessHandler;

	@Autowired
	private CustomAuthenticationFailureHandler customAuthenticationFailureHandler;

	@Autowired
	private TenantWebAuthenticationDetailsSource tenantWebAuthenticationDetailsSource;

    @Value("${server.servlet.session.cookie.name}")
    private String sessionCookieName;
		
	@Bean(name="authenticationManager")	
	CustomAuthenticationManager authenticationManager() {
		CustomAuthenticationManager authenticationManager = new CustomAuthenticationManager();
		return authenticationManager;
	}

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        // 1. 헤더 설정
        http
                .headers(headers -> headers
                        .frameOptions(frame -> frame.sameOrigin())
                        .contentTypeOptions(ct -> {})
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000)
                        )
                        .referrerPolicy(referrer -> referrer
                                .policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                        )
                );

        // 2. CSRF 예외 경로 (기존 pda 및 기타 추가)
        http
                .csrf(csrf -> csrf
                        .ignoringAntMatchers(
                                "/login", "/postLogin",
                                "/api/files/upload/**",
                                "/popbill/webhook",
                                "/pda/**",
                                // CTI 에이전트(윈도우)는 브라우저가 아니라 CSRF 토큰을 가질 수 없다.
                                // 대신 X-Cti-Secret 헤더로 인증한다.
                                "/api/AS/cti/event", "/api/AS/cti/agent-online", "/api/AS/cti/agent-offline", "/api/AS/cti/agent-poll"
                        )
                );

        // 3. 권한 설정 (기존 소스의 모든 permitAll 경로 통합)
        http
                .authorizeRequests(auth -> auth
                        .antMatchers(
                                // 기본 페이지 및 인증
                                "/login", "/logout", "/postLogin", "/intro", "/error", "/alive", "/bill_plan_read", "/product_read", "/grade_read", "/biz/save",
                                // 모바일 관련 경로 추가
                                // 예전에는 "/api/mobile_main/**" 를 통째로 열어 두었다. 그 결과 세션이 끊긴 뒤에도
                                // 요청이 컨트롤러까지 들어와 익명 Authentication 으로 500 이 났고(ClassCastException),
                                // 화면은 401 을 받지 못해 로그인 화면으로 돌아가지 못한 채
                                // 기본값("생산부 홍길동 사원", "오전 09:00")이 그대로 남았다. 그래서 인증을 다시 건다.
                                // 이 경로의 API 는 모두 로그인 후 화면에서만 호출된다(2026-10-01 확인).
                                // 전송 못한 출퇴근 진단 로그는 화면이 localStorage 에 보관했다가 재로그인 후 다시 보낸다.
                                "/mlogin",
                                // 정적 리소스
                                "/resource/**", "/img/**", "/images/**", "/js/**", "/css/**",
                                "/assets_mobile/**", "/font/**", "/robots.txt", "/favicon.ico",
                                // PDA 관련
                                "/pda/login", "/pda/app/version/**", "/pda/**",
                                // API 및 외부 연동
                                "/useridchk/**", "/user-auth/**", "/user-auth/save",
                                "/popbill/webhook", "/api/transaction/input/**",
                                "/api/das_device", "/authentication/**", "/api/common/**",
                                "/api/files/download",
                                // CTI 에이전트 전용 — 공유 시크릿(X-Cti-Secret)으로 컨트롤러에서 직접 검증한다.
                                // 시크릿이 설정돼 있지 않으면 컨트롤러가 503 으로 막는다.
                                "/api/AS/cti/event", "/api/AS/cti/agent-online", "/api/AS/cti/agent-offline", "/api/AS/cti/agent-poll"
                        ).permitAll()
                        .antMatchers("/setup").hasAuthority("admin")
                        .anyRequest().authenticated()
                );

        // 4. 로그인 설정
        http
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/postLogin")
                        .authenticationDetailsSource(tenantWebAuthenticationDetailsSource)
                        .successHandler(customAuthenticationSuccessHandler)
                        .failureHandler(customAuthenticationFailureHandler)
                        .permitAll()
                );

        // 5. 로그아웃 설정
        http
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login")
                        .invalidateHttpSession(true)
                        .deleteCookies(sessionCookieName)
                        .clearAuthentication(true)
                        .permitAll()
                );

        // 6. 예외 처리 및 기타 (기존 소스의 AjaxAware 및 AccessDenied 적용)
        http.httpBasic(httpBasic -> httpBasic.disable());

        http.exceptionHandling(exception -> exception
                .accessDeniedHandler(new CustomAccessDeniedHandler())
                .authenticationEntryPoint(new AjaxAwareLoginUrlAuthenticationEntryPoint("/login"))
        );

        return http.build();
    }
}

