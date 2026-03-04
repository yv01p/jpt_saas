package org.jphototagger.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

/**
 * Spring HandlerInterceptor that extracts the authenticated user ID
 * from SecurityContext and stores it in RlsContext (ThreadLocal).
 * Clears the ThreadLocal in afterCompletion as belt-and-suspenders
 * (primary cleanup is in RlsContextCleanupFilter).
 */
@Component
public class RlsInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                              Object handler) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof UUID userId) {
            RlsContext.setCurrentUserId(userId);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                 Object handler, Exception ex) {
        RlsContext.clear();
    }
}
