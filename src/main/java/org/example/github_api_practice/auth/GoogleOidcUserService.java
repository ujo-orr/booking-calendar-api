package org.example.github_api_practice.auth;

import org.example.github_api_practice.global.error.ApiException;
import org.example.github_api_practice.member.AuthProvider;
import org.example.github_api_practice.member.MemberService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class GoogleOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private static final Logger log = LoggerFactory.getLogger(GoogleOidcUserService.class);
    private final OidcUserService delegate;
    private final MemberService memberService;

    public GoogleOidcUserService(OidcUserService delegate, MemberService memberService) {
        this.delegate = delegate;
        this.memberService = memberService;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        if (!"google".equals(request.getClientRegistration().getRegistrationId())) {
            throw new OAuth2AuthenticationException(new OAuth2Error("unsupported_provider"));
        }
        OidcUser user = delegate.loadUser(request);
        try {
            memberService.login(AuthProvider.GOOGLE, user.getSubject(), user.getEmail(), user.getFullName());
        } catch (ApiException exception) {
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_user_info"),
                    "Google 사용자 정보가 유효하지 않습니다.");
        } catch (DataAccessException | TransactionException exception) {
            log.error("OIDC 회원 저장 실패: {}", exception.getClass().getSimpleName());
            throw new OAuth2AuthenticationException(new OAuth2Error("member_storage_unavailable"),
                    "회원 저장 처리에 실패했습니다.");
        }
        return user;
    }
}
