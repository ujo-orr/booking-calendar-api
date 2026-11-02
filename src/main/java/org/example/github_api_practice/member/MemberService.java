package org.example.github_api_practice.member;

import org.example.github_api_practice.global.error.ApiException;
import org.example.github_api_practice.global.error.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MemberService {

    private final MemberRepository memberRepository;
    private final TransactionTemplate loginTransaction;

    public MemberService(MemberRepository memberRepository, PlatformTransactionManager transactionManager) {
        this.memberRepository = memberRepository;
        this.loginTransaction = new TransactionTemplate(transactionManager);
        this.loginTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Member login(AuthProvider provider, String providerId, String email, String fullName) {
        validateProfile(provider, providerId, email, fullName);
        try {
            return loginTransaction.execute(status -> {
                Member member = memberRepository.findByProviderAndProviderId(provider, providerId)
                        .orElseGet(() -> memberRepository.saveAndFlush(
                                new Member(provider, providerId, email, fullName)));
                member.updateProfile(email, fullName);
                return member;
            });
        } catch (DataIntegrityViolationException exception) {
            // 실패한 트랜잭션 롤백 후 별도 트랜잭션에서 동시 가입 결과 조회
            return loginTransaction.execute(status -> {
                Member member = memberRepository.findByProviderAndProviderId(provider, providerId)
                        .orElseThrow(() -> exception);
                member.updateProfile(email, fullName);
                return member;
            });
        }
    }

    @Transactional(readOnly = true)
    public Member findByIdentity(AuthProvider provider, String providerId) {
        return memberRepository.findByProviderAndProviderId(provider, providerId)
                .orElseThrow(() -> new ApiException(ErrorCode.MEMBER_NOT_FOUND));
    }

    private void validateProfile(AuthProvider provider, String providerId, String email, String fullName) {
        if (provider == null || providerId == null || providerId.isBlank() || providerId.length() > 255
                || (email != null && email.length() > 320)
                || (fullName != null && fullName.length() > 255)) {
            throw new ApiException(ErrorCode.INVALID_OIDC_PROFILE);
        }
    }
}
