package org.example.github_api_practice.calendar;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

@Component
public class CalendarAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {
    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    public CalendarAuthorizationRequestResolver(ClientRegistrationRepository registrations) {
        delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return customize(request, delegate.resolve(request),
                request.getRequestURI().equals(request.getContextPath() + "/oauth2/authorization/" + CalendarScopes.REGISTRATION_ID));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
        return customize(request, delegate.resolve(request, registrationId), CalendarScopes.REGISTRATION_ID.equals(registrationId));
    }

    private OAuth2AuthorizationRequest customize(HttpServletRequest request, OAuth2AuthorizationRequest authorization,
                                                  boolean calendar) {
        if (authorization == null) {
            return null;
        }
        if (!calendar) {
            if (request.getSession(false) != null) {
                request.getSession().removeAttribute(CalendarConnectionAttempt.SESSION_ATTRIBUTE);
            }
            return authorization;
        }
        var original = SecurityContextHolder.getContext().getAuthentication();
        if (original == null || !(original.getPrincipal() instanceof OidcUser user)) {
            return null;
        }
        request.getSession().setAttribute(CalendarConnectionAttempt.SESSION_ATTRIBUTE,
                new CalendarConnectionAttempt(authorization.getState(), original));
        return OAuth2AuthorizationRequest.from(authorization).additionalParameters(parameters -> {
            parameters.put("access_type", "offline");
            parameters.put("prompt", "consent");
            parameters.put("include_granted_scopes", "true");
            parameters.put("login_hint", user.getSubject());
        }).build();
    }
}
