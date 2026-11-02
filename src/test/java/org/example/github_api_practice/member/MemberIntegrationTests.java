package org.example.github_api_practice.member;

import org.example.github_api_practice.global.error.ApiException;
import org.example.github_api_practice.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class MemberIntegrationTests {

    @Autowired
    MemberService memberService;

    @Autowired
    MemberRepository memberRepository;

    @BeforeEach
    void cleanUp() {
        memberRepository.deleteAll();
    }

    @Test
    void firstLoginRegistersMember() {
        Member member = memberService.login(AuthProvider.GOOGLE, "google-123", "first@example.test", "첫 회원");

        Member saved = memberRepository.findById(member.getId()).orElseThrow();
        assertThat(saved.getProvider()).isEqualTo(AuthProvider.GOOGLE);
        assertThat(saved.getProviderId()).isEqualTo("google-123");
        assertThat(saved.getEmail()).isEqualTo("first@example.test");
        assertThat(saved.getFullName()).isEqualTo("첫 회원");
        assertThat(memberRepository.count()).isEqualTo(1);
    }

    @Test
    void returningMemberKeepsIdentityAndUpdatesProfile() {
        Member first = memberService.login(AuthProvider.GOOGLE, "stable-sub", "old@example.test", "이전 이름");
        Member again = memberService.login(AuthProvider.GOOGLE, "stable-sub", "new@example.test", "새 이름");

        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(memberRepository.count()).isEqualTo(1);
        Member saved = memberRepository.findById(first.getId()).orElseThrow();
        assertThat(saved.getEmail()).isEqualTo("new@example.test");
        assertThat(saved.getFullName()).isEqualTo("새 이름");
    }

    @Test
    void equalEmailsDoNotMergeDifferentSubjects() {
        Member first = memberService.login(AuthProvider.GOOGLE, "subject-1", "same@example.test", "회원");
        Member second = memberService.login(AuthProvider.GOOGLE, "subject-2", "same@example.test", "회원");

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(memberRepository.count()).isEqualTo(2);
    }

    @Test
    void subjectComparisonIsCaseSensitive() {
        Member first = memberService.login(AuthProvider.GOOGLE, "Subject", null, null);
        Member second = memberService.login(AuthProvider.GOOGLE, "subject", null, null);

        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    void optionalProfileClaimsMayBeAbsent() {
        Member member = memberService.login(AuthProvider.GOOGLE, "minimal-sub", null, null);

        assertThat(member.getId()).isNotNull();
        assertThat(member.getEmail()).isNull();
        assertThat(member.getFullName()).isNull();
    }

    @Test
    void databaseRejectsDuplicateProviderAndSubject() {
        memberRepository.saveAndFlush(new Member(AuthProvider.GOOGLE, "duplicate-sub", "a@example.test", "회원"));

        assertThatThrownBy(() -> memberRepository.saveAndFlush(
                new Member(AuthProvider.GOOGLE, "duplicate-sub", "b@example.test", "다른 이름")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(memberRepository.count()).isEqualTo(1);
    }

    @Test
    void databaseRejectsNullIdentity() {
        assertThatThrownBy(() -> memberRepository.saveAndFlush(new Member(AuthProvider.GOOGLE, null, null, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "  "})
    void invalidSubjectIsRejected(String subject) {
        assertThatThrownBy(() -> memberService.login(AuthProvider.GOOGLE, subject, null, null))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_OIDC_PROFILE));
        assertThat(memberRepository.count()).isZero();
    }

    @Test
    void oversizedProfileIsRejectedBeforeWriting() {
        assertThatThrownBy(() -> memberService.login(AuthProvider.GOOGLE, "x".repeat(256), null, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> memberService.login(AuthProvider.GOOGLE, "sub", "x".repeat(321), null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> memberService.login(AuthProvider.GOOGLE, "sub", null, "x".repeat(256)))
                .isInstanceOf(ApiException.class);
        assertThat(memberRepository.count()).isZero();
    }

    @Test
    void missingMemberIsReported() {
        assertThatThrownBy(() -> memberService.findByIdentity(AuthProvider.GOOGLE, "absent"))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.MEMBER_NOT_FOUND));
    }

    @Test
    void simultaneousFirstLoginsReturnOneMember() throws Exception {
        int requestCount = 8;
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(requestCount)) {
            var results = new ArrayList<Future<Long>>();
            for (int i = 0; i < requestCount; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("동시 로그인 시작 대기 시간 초과");
                    }
                    return memberService.login(AuthProvider.GOOGLE, "concurrent-sub", "same@example.test", "회원").getId();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var ids = new ArrayList<Long>();
            for (Future<Long> result : results) {
                ids.add(result.get(15, TimeUnit.SECONDS));
            }
            assertThat(ids).hasSize(requestCount).doesNotContainNull().containsOnly(ids.getFirst());
        }
        assertThat(memberRepository.count()).isEqualTo(1);
    }
}
