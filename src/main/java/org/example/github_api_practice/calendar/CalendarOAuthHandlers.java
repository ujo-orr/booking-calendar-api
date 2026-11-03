package org.example.github_api_practice.calendar;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.github_api_practice.global.error.ApiException;
import org.example.github_api_practice.global.error.ErrorCode;
import org.example.github_api_practice.global.response.JsonResponseWriter;
import org.example.github_api_practice.member.AuthProvider;
import org.example.github_api_practice.member.MemberService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class CalendarOAuthHandlers implements AuthenticationSuccessHandler, AuthenticationFailureHandler {
    private final OAuth2AuthorizedClientRepository clients;
    private final CalendarConnectionStore connections;
    private final MemberService members;
    private final JsonResponseWriter responses;

    public CalendarOAuthHandlers(OAuth2AuthorizedClientRepository clients, CalendarConnectionStore connections,
                                 MemberService members, JsonResponseWriter responses) {
        this.clients = clients;
        this.connections = connections;
        this.members = members;
        this.responses = responses;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        var oauth = (OAuth2AuthenticationToken) authentication;
        if (!CalendarScopes.REGISTRATION_ID.equals(oauth.getAuthorizedClientRegistrationId())) {
            response.sendRedirect(request.getContextPath() + "/api/me");
            return;
        }
        CalendarConnectionAttempt attempt = takeAttempt(request);
        ErrorCode error = null;
        try {
            if (attempt == null || !attempt.state().equals(request.getParameter("state"))) {
                throw new ApiException(ErrorCode.CALENDAR_CONNECTION_FAILED);
            }
            var originalUser = (OidcUser) attempt.originalAuthentication().getPrincipal();
            var connectedUser = (OidcUser) authentication.getPrincipal();
            if (!originalUser.getSubject().equals(connectedUser.getSubject())) {
                throw new ApiException(ErrorCode.CALENDAR_ACCOUNT_MISMATCH);
            }
            var client = clients.loadAuthorizedClient(CalendarScopes.REGISTRATION_ID, authentication, request);
            if (client == null) {
                throw new ApiException(ErrorCode.CALENDAR_CONNECTION_FAILED);
            }
            connections.connect(members.findByIdentity(AuthProvider.GOOGLE, originalUser.getSubject()).getId(), client);
        } catch (ApiException exception) {
            error = exception.getErrorCode();
        } catch (CalendarException exception) {
            error = ErrorCode.CALENDAR_NOT_CONFIGURED;
        } catch (RuntimeException exception) {
            error = ErrorCode.CALENDAR_CONNECTION_UNAVAILABLE;
        } finally {
            clients.removeAuthorizedClient(CalendarScopes.REGISTRATION_ID, authentication, request, response);
            restore(request, response, attempt == null ? null : attempt.originalAuthentication());
        }
        if (error == null) {
            response.sendRedirect(request.getContextPath() + "/api/calendar/connection");
        } else {
            responses.writeError(response, error);
        }
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        CalendarConnectionAttempt attempt = takeAttempt(request);
        if (attempt != null) {
            restore(request, response, attempt.originalAuthentication());
            responses.writeError(response, ErrorCode.CALENDAR_CONNECTION_FAILED);
            return;
        }
        ErrorCode code = exception instanceof OAuth2AuthenticationException oauthException
                && "member_storage_unavailable".equals(oauthException.getError().getErrorCode())
                ? ErrorCode.DATABASE_UNAVAILABLE : ErrorCode.OAUTH_LOGIN_FAILED;
        responses.writeError(response, code);
    }

    private CalendarConnectionAttempt takeAttempt(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) {
            return null;
        }
        var attempt = (CalendarConnectionAttempt) session.getAttribute(CalendarConnectionAttempt.SESSION_ATTRIBUTE);
        session.removeAttribute(CalendarConnectionAttempt.SESSION_ATTRIBUTE);
        return attempt;
    }

    private void restore(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context, request, response);
    }
}
