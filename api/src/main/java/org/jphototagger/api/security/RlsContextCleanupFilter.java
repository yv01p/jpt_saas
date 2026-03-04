package org.jphototagger.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter registered at highest precedence that guarantees RlsContext
 * cleanup in a finally block, regardless of where in the filter chain an error occurs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RlsContextCleanupFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            RlsContext.clear();
        }
    }
}
