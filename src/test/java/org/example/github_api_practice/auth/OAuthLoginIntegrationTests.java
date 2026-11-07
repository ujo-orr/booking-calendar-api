package org.example.github_api_practice.auth;

import org.example.github_api_practice.member.AuthProvider;
import org.example.github_api_practice.member.MemberRepository;
import org.example.github_api_practice.member.MemberService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.CannotCreateTransactionException;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class OAuthLoginIntegrationTests {

    static final MockGoogleServer google = new MockGoogleServer();

    @Autowired
    MockMvc mvc;
    @Autowired
    MemberRepository repository;
    @MockitoSpyBean
    MemberService memberService;

    @DynamicPropertySource
    static void mockGoogleEndpoints(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.provider.google.token-uri", () -> google.url("/token"));
        registry.add("spring.security.oauth2.client.provider.google.jwk-set-uri", () -> google.url("/jwks"));
        registry.add("spring.security.oauth2.client.provider.google.user-info-uri", () -> google.url("/userinfo"));
    }

    @BeforeEach
    void setUp() {
        google.reset();
        repository.deleteAll();
    }

    @AfterAll
    static void closeMockServer() {
        google.close();
    }

    @Test
    void loginRedirectRequestsOnlyIdentityScopes() throws Exception {
        var login = beginLogin();
        assertThat(login.parameters().get("scope").split(" "))
                .containsExactlyInAnyOrder("openid", "profile", "email");
        assertThat(login.parameters()).doesNotContainKeys("access_type", "prompt");
        assertThat(login.parameters().get("redirect_uri"))
                .isEqualTo("http://localhost/login/oauth2/code/google");
        assertThat(repository.count()).isZero();
    }

    @Test
    void callbackRegistersMemberSavesSessionAndReusesMemberOnRelogin() throws Exception {
        LoginAttempt first = beginLogin();
        String originalSessionId = first.session().getId();
        callback(first).andExpect(status().isFound()).andExpect(redirectedUrl("/api/me"));
        assertThat(first.session().getId()).isNotEqualTo(originalSessionId);
        assertThat(google.lastTokenForm).contains("grant_type=authorization_code", "code=synthetic-code");
        var member = repository.findByProviderAndProviderId(AuthProvider.GOOGLE, "google-flow-sub").orElseThrow();
        mvc.perform(get("/api/me").session(first.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(member.getId()))
                .andExpect(jsonPath("$.data.email").value("google@example.test"))
                .andExpect(jsonPath("$.data.fullName").value("구글 테스트 회원"))
                .andExpect(content().string(not(containsString("synthetic-access-token"))));

        google.email = "changed@example.test";
        google.fullName = "변경된 이름";
        LoginAttempt again = beginLogin();
        callback(again).andExpect(status().isFound());
        assertThat(repository.count()).isEqualTo(1);
        mvc.perform(get("/api/me").session(again.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(member.getId()))
                .andExpect(jsonPath("$.data.email").value("changed@example.test"));
    }

    @Test
    void deniedConsentReturnsSafeErrorWithoutAuthentication(CapturedOutput output) throws Exception {
        LoginAttempt login = beginLogin();
        mvc.perform(get("/login/oauth2/code/google").session(login.session())
                        .param("state", login.parameters().get("state"))
                        .param("error", "access_denied").param("error_description", "private-provider-detail"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.code").value("OAUTH_LOGIN_FAILED"))
                .andExpect(content().string(not(containsString("private-provider-detail"))));
        assertNotLoggedIn(login);
        assertThat(output.getOut()).contains("OAuth2 authentication failed: flow=login, error=access_denied")
                .doesNotContain("private-provider-detail", "synthetic-code");
    }

    @Test
    void callbackWithoutOriginalSessionLogsCauseWithoutLeakingCallback(CapturedOutput output) throws Exception {
        mvc.perform(get("/login/oauth2/code/google")
                        .param("state", "private-state").param("code", "private-authorization-code"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.code").value("OAUTH_LOGIN_FAILED"));
        assertThat(google.lastTokenForm).isNull();
        assertThat(output.getOut()).contains("error=authorization_request_not_found", "sessionPresent=false")
                .doesNotContain("private-state", "private-authorization-code");
    }

    @Test
    void providerErrorCodeCannotInjectSecretsIntoLogs(CapturedOutput output) throws Exception {
        LoginAttempt login = beginLogin();
        mvc.perform(get("/login/oauth2/code/google").session(login.session())
                        .param("state", login.parameters().get("state"))
                        .param("error", "private-provider-error").param("error_description", "private-detail"))
                .andExpect(status().isUnauthorized());
        assertThat(output.getOut()).contains("error=unrecognized_oauth_error")
                .doesNotContain("private-provider-error", "private-detail");
        assertNotLoggedIn(login);
    }

    @Test
    void mismatchedStateDoesNotExchangeCodeOrRegisterMember() throws Exception {
        LoginAttempt login = beginLogin();
        mvc.perform(get("/login/oauth2/code/google").session(login.session())
                        .param("state", "forged-state").param("code", "synthetic-code"))
                .andExpect(status().isUnauthorized());
        assertThat(google.lastTokenForm).isNull();
        assertNotLoggedIn(login);
    }

    @Test
    void invalidNonceIsRejectedBeforeRegisteringMember() throws Exception {
        LoginAttempt login = beginLogin();
        google.nonce = "forged-nonce";
        callback(login).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.code").value("OAUTH_LOGIN_FAILED"));
        assertNotLoggedIn(login);
    }

    @Test
    void expiredIdTokenIsRejectedBeforeRegisteringMember() throws Exception {
        LoginAttempt login = beginLogin();
        google.expired = true;
        callback(login).andExpect(status().isUnauthorized());
        assertNotLoggedIn(login);
    }

    @Test
    void userInfoSubjectMustMatchIdTokenSubject() throws Exception {
        LoginAttempt login = beginLogin();
        google.userInfoSubject = "other-google-account";
        callback(login).andExpect(status().isUnauthorized());
        assertNotLoggedIn(login);
    }

    @Test
    void tokenExchangeFailureDoesNotCreateMemberOrSessionAuthentication(CapturedOutput output) throws Exception {
        LoginAttempt login = beginLogin();
        google.tokenStatus = 400;
        callback(login).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.code").value("OAUTH_LOGIN_FAILED"));
        assertNotLoggedIn(login);
        assertThat(output.getOut()).contains("OAuth2 authentication failed: flow=login, error=invalid_grant")
                .doesNotContain("synthetic-code", "synthetic-access-token");
    }

    @Test
    void databaseFailureDuringLoginReturns503WithoutAuthentication() throws Exception {
        doThrow(new DataAccessResourceFailureException("private database fixture"))
                .when(memberService).login(AuthProvider.GOOGLE, "google-flow-sub", "google@example.test", "구글 테스트 회원");
        LoginAttempt login = beginLogin();
        callback(login).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.code").value("DATABASE_UNAVAILABLE"))
                .andExpect(content().string(not(containsString("private database fixture"))));
        assertNotLoggedIn(login);
    }

    @Test
    void transactionCreationFailureDuringLoginReturns503() throws Exception {
        doThrow(new CannotCreateTransactionException("private connection fixture"))
                .when(memberService).login(AuthProvider.GOOGLE, "google-flow-sub", "google@example.test", "구글 테스트 회원");
        LoginAttempt login = beginLogin();
        callback(login).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.code").value("DATABASE_UNAVAILABLE"));
        assertNotLoggedIn(login);
    }

    private LoginAttempt beginLogin() throws Exception {
        var result = mvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().isFound()).andReturn();
        var uri = URI.create(result.getResponse().getRedirectedUrl());
        assertThat(uri.getHost()).isEqualTo("accounts.google.com");
        Map<String, String> parameters = Arrays.stream(uri.getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> decode(pair[0]), pair -> decode(pair[1])));
        google.nonce = parameters.get("nonce");
        return new LoginAttempt((MockHttpSession) result.getRequest().getSession(false), parameters);
    }

    private ResultActions callback(LoginAttempt login) throws Exception {
        return mvc.perform(get("/login/oauth2/code/google").session(login.session())
                .with(request -> {
                    request.setRequestedSessionId(login.session().getId());
                    request.setRequestedSessionIdValid(true);
                    request.setRequestedSessionIdFromCookie(true);
                    return request;
                })
                .param("state", login.parameters().get("state")).param("code", "synthetic-code"));
    }

    private void assertNotLoggedIn(LoginAttempt login) throws Exception {
        assertThat(repository.count()).isZero();
        mvc.perform(get("/api/me").session(login.session())).andExpect(status().isUnauthorized());
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    record LoginAttempt(MockHttpSession session, Map<String, String> parameters) {
    }
}
