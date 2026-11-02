package org.example.github_api_practice.auth;

import org.example.github_api_practice.member.AuthProvider;
import org.example.github_api_practice.member.Member;
import org.example.github_api_practice.member.MemberRepository;
import org.example.github_api_practice.member.MemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiIntegrationTests {

    @Autowired
    MockMvc mvc;
    @Autowired
    MemberRepository repository;
    @Autowired
    JsonMapper jsonMapper;
    @MockitoSpyBean
    MemberService memberService;

    @BeforeEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    void healthRemainsPublicWithOriginalBody() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"status":"UP"}
                        """));
    }

    @Test
    void anonymousMeReturnsJson401EvenForBrowserAcceptHeader() throws Exception {
        mvc.perform(get("/api/me").accept("text/html"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("UNAUTHENTICATED"))
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void authenticatedMeReadsStoredMemberInsteadOfStaleClaims() throws Exception {
        Member member = memberService.login(AuthProvider.GOOGLE, "sub-123", "stored@example.test", "DB 이름");
        mvc.perform(get("/api/me").with(oidcLogin().idToken(token -> token
                        .subject("sub-123").claim("email", "stale@example.test").claim("name", "이전 이름"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.memberId").value(member.getId()))
                .andExpect(jsonPath("$.data.provider").value("GOOGLE"))
                .andExpect(jsonPath("$.data.subject").value("sub-123"))
                .andExpect(jsonPath("$.data.email").value("stored@example.test"))
                .andExpect(jsonPath("$.data.fullName").value("DB 이름"))
                .andExpect(content().string(not(containsString("idToken"))))
                .andExpect(content().string(not(containsString("accessToken"))));
    }

    @Test
    void authenticatedSubjectWithoutMemberReturns404() throws Exception {
        mvc.perform(get("/api/me").with(oidcLogin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("MEMBER_NOT_FOUND"));
    }

    @Test
    void databaseFailureIsSanitized() throws Exception {
        doThrow(new DataAccessResourceFailureException("sensitive SQL fixture"))
                .when(memberService).findByIdentity(AuthProvider.GOOGLE, "sub-123");
        mvc.perform(get("/api/me").with(oidcLogin().idToken(token -> token.subject("sub-123"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.code").value("DATABASE_UNAVAILABLE"))
                .andExpect(content().string(not(containsString("sensitive"))));
    }

    @Test
    void transactionCreationFailureReturns503() throws Exception {
        doThrow(new CannotCreateTransactionException("private connection fixture"))
                .when(memberService).findByIdentity(AuthProvider.GOOGLE, "sub-123");
        mvc.perform(get("/api/me").with(oidcLogin().idToken(token -> token.subject("sub-123"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.code").value("DATABASE_UNAVAILABLE"))
                .andExpect(content().string(not(containsString("private connection fixture"))));
    }

    @Test
    void logoutWithoutCsrfDoesNotInvalidateSession() throws Exception {
        MockHttpSession session = authenticatedSession();
        mvc.perform(post("/api/logout").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.code").value("ACCESS_DENIED"));
        assertThat(session.isInvalid()).isFalse();
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk());
    }

    @Test
    void logoutWithInvalidCsrfIsRejected() throws Exception {
        MockHttpSession session = authenticatedSession();
        mvc.perform(post("/api/logout").session(session).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void realCsrfTokenEnablesLogoutAndSessionIsInvalidated() throws Exception {
        MockHttpSession session = authenticatedSession();
        var response = mvc.perform(get("/api/csrf").session(session))
                .andExpect(status().isOk()).andReturn().getResponse();
        var data = jsonMapper.readTree(response.getContentAsString()).get("data");
        mvc.perform(post("/api/logout").session(session)
                        .header(data.get("headerName").asString(), data.get("token").asString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(cookie().maxAge("JSESSIONID", 0));
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void getLogoutDoesNotLogOut() throws Exception {
        MockHttpSession session = authenticatedSession();
        mvc.perform(get("/api/logout").session(session)).andExpect(status().isNotFound());
        assertThat(session.isInvalid()).isFalse();
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk());
    }

    @Test
    void csrfTokenIsAvailableBeforeLogin() throws Exception {
        mvc.perform(get("/api/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.headerName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    @Test
    void authenticatedUnknownRoutePreserves404() throws Exception {
        mvc.perform(get("/api/unknown").with(oidcLogin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("HTTP_404"));
    }

    @Test
    void unsupportedMethodPreserves405() throws Exception {
        mvc.perform(post("/api/me").with(oidcLogin()).with(csrf()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.data.code").value("HTTP_405"));
    }

    private MockHttpSession authenticatedSession() {
        memberService.login(AuthProvider.GOOGLE, "session-sub", "member@example.test", "회원");
        Instant now = Instant.now();
        var token = new OidcIdToken("synthetic-id-token", now, now.plusSeconds(300), Map.of("sub", "session-sub"));
        var user = new DefaultOidcUser(List.of(), token);
        var authentication = new OAuth2AuthenticationToken(user, user.getAuthorities(), "google");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(authentication));
        return session;
    }
}
