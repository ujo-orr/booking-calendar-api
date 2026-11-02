package org.example.github_api_practice;

import org.example.github_api_practice.auth.GoogleOidcUserService;
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
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
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
    public SecurityFilterChain securityFilterChain(HttpSecurity http, GoogleOidcUserService googleOidcUserService,
                                                   JsonResponseWriter responseWriter) throws Exception {
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
                        .authorizedClientRepository(new HttpSessionOAuth2AuthorizedClientRepository())
                        .userInfoEndpoint(userInfo -> userInfo.oidcUserService(googleOidcUserService))
                        .defaultSuccessUrl("/api/me", true)
                        .failureHandler((request, response, exception) -> {
                            ErrorCode errorCode = exception instanceof OAuth2AuthenticationException oauthException
                                    && "member_storage_unavailable".equals(oauthException.getError().getErrorCode())
                                    ? ErrorCode.DATABASE_UNAVAILABLE : ErrorCode.OAUTH_LOGIN_FAILED;
                            responseWriter.writeError(response, errorCode);
                        }))
                .logout(logout -> logout
                        .logoutUrl("/api/logout")
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) ->
                                responseWriter.write(response, HttpStatus.OK.value(), ApiResponse.success(null))));
        return http.build();
    }
}
