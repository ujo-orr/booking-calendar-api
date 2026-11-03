package org.example.github_api_practice.calendar;

import org.example.github_api_practice.auth.MockGoogleServer;
import org.example.github_api_practice.member.AuthProvider;
import org.example.github_api_practice.member.Member;
import org.example.github_api_practice.member.MemberRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CalendarOAuthIntegrationTests {
    static final MockGoogleServer google = new MockGoogleServer();
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired CalendarConnectionRepository connections;
    @MockitoSpyBean CalendarTokenCipher cipher;
    @MockitoSpyBean CalendarConnectionStore connectionStore;
    MockHttpSession session;
    Long memberId;

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry properties) {
        properties.add("spring.security.oauth2.client.provider.google.token-uri", () -> google.url("/token"));
        properties.add("spring.security.oauth2.client.provider.google.jwk-set-uri", () -> google.url("/jwks"));
        properties.add("spring.security.oauth2.client.provider.google.user-info-uri", () -> google.url("/userinfo"));
    }

    @BeforeEach
    void setUp() {
        members.deleteAllInBatch();
        google.reset();
        google.tokenScope = "openid profile email " + CalendarScopes.EVENTS_OWNED;
        google.refreshToken = "synthetic-calendar-refresh";
        memberId = members.saveAndFlush(new Member(AuthProvider.GOOGLE, "google-flow-sub", null, null)).getId();
        Instant now = Instant.now();
        var token = new OidcIdToken("original-session-token", now, now.plusSeconds(3600), Map.of("sub", "google-flow-sub"));
        var principal = new DefaultOidcUser(List.of(), token);
        var authentication = new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
        session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(authentication));
    }

    @AfterEach
    void cleanup() {
        members.deleteAllInBatch();
    }

    @AfterAll
    static void stop() {
        google.close();
    }

    @Test
    void anonymousCannotStartCalendarAuthorization() throws Exception {
        mvc.perform(get("/oauth2/authorization/google-calendar"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.data.code").value("UNAUTHENTICATED"));
    }

    @Test
    void calendarConsentRequestsOfflineMinimalOwnedScopeAndLoginHint() throws Exception {
        var parameters = begin();
        assertThat(parameters.get("scope").split(" "))
                .containsExactlyInAnyOrder("openid", "profile", "email", CalendarScopes.EVENTS_OWNED);
        assertThat(parameters).containsEntry("access_type", "offline").containsEntry("prompt", "consent")
                .containsEntry("include_granted_scopes", "true").containsEntry("login_hint", "google-flow-sub");
        assertThat(parameters.get("redirect_uri")).isEqualTo("http://localhost/login/oauth2/code/google-calendar");
    }

    @Test
    void callbackStoresEncryptedTokensAndRestoresOriginalLogin() throws Exception {
        callback(begin()).andExpect(status().isFound()).andExpect(redirectedUrl("/api/calendar/connection"));
        var saved = connections.findById(memberId).orElseThrow();
        assertThat(saved.getEncryptedAccessToken()).doesNotContain("synthetic-access-token");
        assertThat(cipher.decrypt(saved.getEncryptedRefreshToken(), memberId + ":refresh"))
                .isEqualTo("synthetic-calendar-refresh");
        assertOriginalLogin();
        mvc.perform(get("/api/calendar/connection").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("CONNECTED"));
    }

    @Test
    void callbackWithAnotherGoogleAccountDoesNotLinkOrRegisterIt() throws Exception {
        var parameters = begin();
        google.subject = "another-google-account";
        google.userInfoSubject = "another-google-account";
        callback(parameters).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.code").value("CALENDAR_ACCOUNT_MISMATCH"));
        assertThat(connections.count()).isZero();
        assertThat(members.count()).isEqualTo(1);
        assertOriginalLogin();
    }

    @Test
    void deniedConsentPreservesExistingLogin() throws Exception {
        var parameters = begin();
        mvc.perform(get("/login/oauth2/code/google-calendar").session(session)
                        .param("state", parameters.get("state")).param("error", "access_denied"))
                .andExpect(status().isBadRequest());
        assertThat(connections.count()).isZero();
        assertOriginalLogin();
    }

    @Test
    void forgedStateDoesNotExchangeTokensAndPreservesLogin() throws Exception {
        begin();
        mvc.perform(get("/login/oauth2/code/google-calendar").session(session)
                        .param("state", "forged").param("code", "synthetic-code"))
                .andExpect(status().isBadRequest());
        assertThat(google.lastTokenForm).isNull();
        assertThat(connections.count()).isZero();
        assertOriginalLogin();
    }

    @Test
    void invalidNonceDoesNotLinkAccountAndPreservesLogin() throws Exception {
        var parameters = begin();
        google.nonce = "wrong-nonce";
        callback(parameters).andExpect(status().isBadRequest());
        assertThat(connections.count()).isZero();
        assertOriginalLogin();
    }

    @Test
    void partialConsentWithoutCalendarScopeIsRejected() throws Exception {
        var parameters = begin();
        google.tokenScope = "openid profile email";
        callback(parameters).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.code").value("CALENDAR_PERMISSION_REQUIRED"));
        assertThat(connections.count()).isZero();
        assertOriginalLogin();
    }

    @Test
    void firstConsentMayOmitRefreshTokenAndReconnectDoesNotEraseExistingOne() throws Exception {
        google.refreshToken = null;
        callback(begin()).andExpect(status().isFound());
        assertThat(connections.findById(memberId).orElseThrow().getEncryptedRefreshToken()).isNull();
        google.refreshToken = "first-refresh-fixture";
        callback(begin()).andExpect(status().isFound());
        google.refreshToken = null;
        callback(begin()).andExpect(status().isFound());
        assertThat(cipher.decrypt(connections.findById(memberId).orElseThrow().getEncryptedRefreshToken(), memberId + ":refresh"))
                .isEqualTo("first-refresh-fixture");
    }

    @Test
    void applicationLogoutDoesNotDeleteGrantedCalendarConnection() throws Exception {
        callback(begin()).andExpect(status().isFound());
        mvc.perform(post("/api/logout").session(session).with(csrf())).andExpect(status().isOk());
        assertThat(session.isInvalid()).isTrue();
        assertThat(connections.existsById(memberId)).isTrue();
    }

    @Test
    void missingEncryptionKeyRejectsConsentStartWithoutLosingLogin() throws Exception {
        doReturn(false).when(cipher).isConfigured();
        mvc.perform(get("/oauth2/authorization/google-calendar").session(session))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.code").value("CALENDAR_NOT_CONFIGURED"));
        assertOriginalLogin();
    }

    @Test
    void credentialStorageFailurePreservesOriginalLoginAndReturnsSafeError() throws Exception {
        doThrow(new DataAccessResourceFailureException("private-storage-fixture"))
                .when(connectionStore).connect(any(), any());
        callback(begin()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.code").value("CALENDAR_CONNECTION_UNAVAILABLE"));
        assertThat(connections.count()).isZero();
        assertOriginalLogin();
    }

    private Map<String, String> begin() throws Exception {
        var response = mvc.perform(get("/oauth2/authorization/google-calendar").session(session))
                .andExpect(status().isFound()).andReturn().getResponse();
        var uri = URI.create(response.getRedirectedUrl());
        Map<String, String> parameters = Arrays.stream(uri.getRawQuery().split("&"))
                .map(value -> value.split("=", 2)).collect(Collectors.toMap(value -> decode(value[0]), value -> decode(value[1])));
        google.nonce = parameters.get("nonce");
        return parameters;
    }

    private ResultActions callback(Map<String, String> parameters) throws Exception {
        return mvc.perform(get("/login/oauth2/code/google-calendar").session(session)
                .with(request -> {
                    request.setRequestedSessionId(session.getId());
                    request.setRequestedSessionIdValid(true);
                    request.setRequestedSessionIdFromCookie(true);
                    return request;
                }).param("state", parameters.get("state")).param("code", "synthetic-code"));
    }

    private void assertOriginalLogin() throws Exception {
        var context = (SecurityContext) session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(((OAuth2AuthenticationToken) context.getAuthentication()).getAuthorizedClientRegistrationId()).isEqualTo("google");
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subject").value("google-flow-sub"));
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
