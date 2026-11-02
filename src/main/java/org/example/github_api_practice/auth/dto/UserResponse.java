package org.example.github_api_practice.auth.dto;

import org.example.github_api_practice.member.AuthProvider;
import org.example.github_api_practice.member.Member;

public record UserResponse(Long memberId, AuthProvider provider, String subject, String email, String fullName) {

    public static UserResponse from(Member member) {
        return new UserResponse(member.getId(), member.getProvider(), member.getProviderId(),
                member.getEmail(), member.getFullName());
    }
}
