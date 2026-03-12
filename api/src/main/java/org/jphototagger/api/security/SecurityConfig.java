package org.jphototagger.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;

@Configuration
@EnableWebSecurity
@EnableTransactionManagement(order = 0)
public class SecurityConfig implements WebMvcConfigurer {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final RlsInterceptor rlsInterceptor;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;
    private final boolean cookieSecure;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          RateLimitFilter rateLimitFilter,
                          RlsInterceptor rlsInterceptor,
                          OAuth2SuccessHandler oAuth2SuccessHandler,
                          @Value("${app.cookie-secure:true}") boolean cookieSecure) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.rlsInterceptor = rlsInterceptor;
        this.oAuth2SuccessHandler = oAuth2SuccessHandler;
        this.cookieSecure = cookieSecure;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // IF_REQUIRED (not STATELESS): OAuth2 authorization code flow needs session
            // for the redirect dance. JWT filter still handles API auth without sessions.
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .csrf(csrf -> {
                var csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
                csrfRepo.setCookieCustomizer(c -> c.sameSite("Strict").secure(cookieSecure));
                csrf.csrfTokenRepository(csrfRepo)
                .csrfTokenRequestHandler(spaCsrfTokenRequestHandler())
                .ignoringRequestMatchers("/auth/refresh",
                        "/login/oauth2/code/*");
            })
            // CSP, HSTS, and Permissions-Policy are managed exclusively by nginx
            // to avoid duplicate/conflicting headers. See nginx.prod.conf.
            .headers(headers -> headers
                .httpStrictTransportSecurity(hsts -> hsts.disable()))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setContentType("application/json");
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.getWriter().write("{\"error\":\"Unauthorized\",\"status\":401}");
                }))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/auth/**", "/actuator/health", "/csrf").permitAll()
                .anyRequest().authenticated())
            .oauth2Login(oauth2 -> oauth2
                .successHandler(oAuth2SuccessHandler))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class)
            .addFilterAfter(new CsrfCookieFilter(), RateLimitFilter.class);

        return http.build();
    }

    /**
     * SPA-friendly CSRF token request handler. Uses XorCsrfTokenRequestAttributeHandler
     * as delegate to handle the X-XSRF-TOKEN header from SPAs, while extending
     * CsrfTokenRequestAttributeHandler to properly resolve the token value.
     */
    private static CsrfTokenRequestAttributeHandler spaCsrfTokenRequestHandler() {
        CsrfTokenRequestAttributeHandler delegate = new XorCsrfTokenRequestAttributeHandler();
        // XorCsrfTokenRequestAttributeHandler resolves BREACH-protected tokens
        // but we need plain handler for attribute setting
        delegate.setCsrfRequestAttributeName(null);
        return delegate;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rlsInterceptor);
    }

    /**
     * Filter that eagerly loads the CsrfToken so the CSRF cookie is set on every response,
     * including the login response. This is necessary for SPAs that need the CSRF token
     * before making any state-changing requests.
     */
    private static class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                         FilterChain filterChain) throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                // Force the token to be loaded so the cookie is set
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }
}
