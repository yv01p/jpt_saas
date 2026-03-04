package org.jphototagger.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
    private final RlsInterceptor rlsInterceptor;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          RlsInterceptor rlsInterceptor) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.rlsInterceptor = rlsInterceptor;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(spaCsrfTokenRequestHandler())
                .ignoringRequestMatchers("/auth/login", "/auth/register"))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setContentType("application/json");
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.getWriter().write("{\"error\":\"Unauthorized\",\"status\":401}");
                }))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/auth/**", "/actuator/health").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(new CsrfCookieFilter(), jwtAuthenticationFilter.getClass());

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
        return new BCryptPasswordEncoder();
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
