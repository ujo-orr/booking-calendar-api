package org.example.github_api_practice.auth;

import org.example.github_api_practice.global.error.ApiException;
import org.example.github_api_practice.global.error.ErrorCode;
import org.example.github_api_practice.member.AuthProvider;
import org.example.github_api_practice.member.MemberService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class GoogleOidcUserServiceTests {

    OidcUserService delegate = mock(OidcUserService.class);
    MemberService members = mock(MemberService.class);
    GoogleOidcUserService service = new GoogleOidcUserService(delegate, members);

    @Test
    void validatedClaimsArePassedToMemberService() {
        OidcUserRequest request = request("google");
        OidcUser user = mock(OidcUser.class);
        when(user.getSubject()).thenReturn("stable-sub");
        when(user.getEmail()).thenReturn("member@example.test");
        when(user.getFullName()).thenReturn("회원");
        when(delegate.loadUser(request)).thenReturn(user);

        assertThat(service.loadUser(request)).isSameAs(user);
        verify(members).login(AuthProvider.GOOGLE, "stable-sub", "member@example.test", "회원");
    }

    @Test
    void unsupportedRegistrationDoesNotLoadOrPersistUser() {
        assertThatThrownBy(() -> service.loadUser(request("other")))
                .isInstanceOf(OAuth2AuthenticationException.class);
        verifyNoInteractions(delegate, members);
    }

    @Test
    void invalidClaimsDoNotProduceSuccessfulAuthentication() {
        OidcUserRequest request = request("google");
        OidcUser user = mock(OidcUser.class);
        when(delegate.loadUser(request)).thenReturn(user);
        when(members.login(AuthProvider.GOOGLE, null, null, null))
                .thenThrow(new ApiException(ErrorCode.INVALID_OIDC_PROFILE));

        assertThatThrownBy(() -> service.loadUser(request))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class,
                        exception -> assertThat(exception.getError().getErrorCode()).isEqualTo("invalid_user_info"));
    }

    @Test
    void providerUserInfoFailureNeverRegistersMember() {
        OidcUserRequest request = request("google");
        when(delegate.loadUser(request)).thenThrow(new OAuth2AuthenticationException("invalid_user_info_response"));

        assertThatThrownBy(() -> service.loadUser(request)).isInstanceOf(OAuth2AuthenticationException.class);
        verifyNoInteractions(members);
    }

    private OidcUserRequest request(String registrationId) {
        OidcUserRequest request = mock(OidcUserRequest.class);
        ClientRegistration registration = mock(ClientRegistration.class);
        when(request.getClientRegistration()).thenReturn(registration);
        when(registration.getRegistrationId()).thenReturn(registrationId);
        return request;
    }
}
