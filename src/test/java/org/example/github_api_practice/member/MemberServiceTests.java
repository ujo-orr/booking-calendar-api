package org.example.github_api_practice.member;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemberServiceTests {

    MemberRepository repository = mock(MemberRepository.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    MemberService service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        service = new MemberService(repository, transactionManager);
    }

    @Test
    void duplicateInsertIsRolledBackBeforeReadingWinner() {
        Member winner = new Member(AuthProvider.GOOGLE, "sub", "old@example.test", "이름");
        when(repository.findByProviderAndProviderId(AuthProvider.GOOGLE, "sub"))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(winner));
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate fixture"));

        Member result = service.login(AuthProvider.GOOGLE, "sub", "new@example.test", "새 이름");

        assertThat(result).isSameAs(winner);
        assertThat(result.getEmail()).isEqualTo("new@example.test");
        var order = inOrder(repository, transactionManager);
        order.verify(transactionManager).getTransaction(any());
        order.verify(repository).findByProviderAndProviderId(AuthProvider.GOOGLE, "sub");
        order.verify(repository).saveAndFlush(any());
        order.verify(transactionManager).rollback(any());
        order.verify(transactionManager).getTransaction(any());
        order.verify(repository).findByProviderAndProviderId(AuthProvider.GOOGLE, "sub");
        order.verify(transactionManager).commit(any());
    }

    @Test
    void unrelatedIntegrityFailureIsNotTreatedAsSuccessfulLogin() {
        var failure = new DataIntegrityViolationException("not a duplicate fixture");
        when(repository.findByProviderAndProviderId(AuthProvider.GOOGLE, "sub")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.login(AuthProvider.GOOGLE, "sub", null, null)).isSameAs(failure);
        verify(transactionManager, times(2)).rollback(any());
        verify(transactionManager, never()).commit(any());
    }
}
