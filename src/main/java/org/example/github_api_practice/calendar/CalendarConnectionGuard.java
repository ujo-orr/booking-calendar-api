package org.example.github_api_practice.calendar;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.github_api_practice.global.error.ErrorCode;
import org.example.github_api_practice.global.response.JsonResponseWriter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class CalendarConnectionGuard extends OncePerRequestFilter {
    private final CalendarTokenCipher cipher;
    private final JsonResponseWriter responses;

    public CalendarConnectionGuard(CalendarTokenCipher cipher, JsonResponseWriter responses) {
        this.cipher = cipher;
        this.responses = responses;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().equals(request.getContextPath() + "/oauth2/authorization/" + CalendarScopes.REGISTRATION_ID)) {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()
                    || !(authentication.getPrincipal() instanceof OidcUser)) {
                responses.writeError(response, ErrorCode.UNAUTHENTICATED);
                return;
            }
            if (!cipher.isConfigured()) {
                responses.writeError(response, ErrorCode.CALENDAR_NOT_CONFIGURED);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
