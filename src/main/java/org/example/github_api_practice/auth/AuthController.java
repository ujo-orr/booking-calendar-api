package org.example.github_api_practice.auth;

import org.example.github_api_practice.auth.dto.CsrfResponse;
import org.example.github_api_practice.auth.dto.UserResponse;
import org.example.github_api_practice.global.response.ApiResponse;
import org.example.github_api_practice.member.AuthProvider;
import org.example.github_api_practice.member.MemberService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final MemberService memberService;

    public AuthController(MemberService memberService) {
        this.memberService = memberService;
    }

    @GetMapping("/api/me")
    public ApiResponse<UserResponse> me(@AuthenticationPrincipal OidcUser user) {
        return ApiResponse.success(UserResponse.from(
                memberService.findByIdentity(AuthProvider.GOOGLE, user.getSubject())));
    }

    @GetMapping("/api/csrf")
    public ApiResponse<CsrfResponse> csrf(CsrfToken csrfToken) {
        return ApiResponse.success(new CsrfResponse(csrfToken.getHeaderName(), csrfToken.getToken()));
    }
}
