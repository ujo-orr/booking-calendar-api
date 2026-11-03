package org.example.github_api_practice;

import org.example.github_api_practice.auth.GoogleOidcUserService;
import org.example.github_api_practice.calendar.CalendarAuthorizationRequestResolver;
import org.example.github_api_practice.calendar.CalendarConnectionGuard;
import org.example.github_api_practice.calendar.CalendarOAuthHandlers;
import org.example.github_api_practice.calendar.CalendarTokenCipher;
import org.example.github_api_practice.global.error.ErrorCode;
import org.example.github_api_practice.global.response.ApiResponse;
import org.example.github_api_practice.global.response.JsonResponseWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public OidcUserService oidcUserService() {
        return new OidcUserService();
    }

    @Bean
    public OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, GoogleOidcUserService googleOidcUserService,
                                                   JsonResponseWriter responseWriter,
                                                   OAuth2AuthorizedClientRepository authorizedClients,
                                                   CalendarAuthorizationRequestResolver authorizationRequests,
                                                   CalendarOAuthHandlers oauthHandlers,
                                                   CalendarTokenCipher tokenCipher) throws Exception {
        http.addFilterBefore(new CalendarConnectionGuard(tokenCipher, responseWriter),
                OAuth2AuthorizationRequestRedirectFilter.class);
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health", "/api/csrf", "/login", "/error",
                                "/oauth2/**", "/login/oauth2/**").permitAll()
                        .anyRequest().authenticated())
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                responseWriter.writeError(response, ErrorCode.UNAUTHENTICATED))
                        .accessDeniedHandler((request, response, exception) ->
                                responseWriter.writeError(response, ErrorCode.ACCESS_DENIED)))
                .oauth2Login(oauth -> oauth
                        .authorizedClientRepository(authorizedClients)
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(authorizationRequests))
                        .userInfoEndpoint(userInfo -> userInfo.oidcUserService(googleOidcUserService))
                        .successHandler(oauthHandlers)
                        .failureHandler(oauthHandlers))
                .logout(logout -> logout
                        .logoutUrl("/api/logout")
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) ->
                                responseWriter.write(response, HttpStatus.OK.value(), ApiResponse.success(null))));
        return http.build();
    }
}
