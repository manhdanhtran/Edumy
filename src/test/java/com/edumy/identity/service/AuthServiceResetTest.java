package com.edumy.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;

import com.edumy.identity.entity.TokenType;
import com.edumy.identity.repository.RoleRepository;
import com.edumy.identity.repository.UserRepository;
import com.edumy.identity.repository.UserRoleRepository;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** P13: a reset with a wrong, used or expired code is refused before BCrypt (no hash spent, nothing written). */
class AuthServiceResetTest {

    final AccountTokenService accountTokens = mock(AccountTokenService.class);
    final PasswordEncoder encoder = mock(PasswordEncoder.class);
    final TransactionTemplate transactions = mock(TransactionTemplate.class);
    final AuthService service = new AuthService(mock(UserRepository.class), mock(RoleRepository.class),
            mock(UserRoleRepository.class), accountTokens, mock(SessionService.class), encoder,
            mock(ApplicationEventPublisher.class), transactions, Clock.systemUTC());

    @Test
    void unusableCode_422BeforeAnyHash_noTransaction() {
        given(accountTokens.isUsable("wrong-code", TokenType.PASSWORD_RESET)).willReturn(false);

        assertThatThrownBy(() -> service.resetPassword("wrong-code", "MatKhauMoi123"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ACCOUNT_TOKEN_INVALID));
        verify(encoder, never()).encode(anyString());
        verifyNoInteractions(transactions);
    }

    @Test
    void usableCode_hashedThenConsumedInTheTransaction() {
        given(accountTokens.isUsable("good-code", TokenType.PASSWORD_RESET)).willReturn(true);
        given(encoder.encode("MatKhauMoi123")).willReturn("$2a$12$hash");

        service.resetPassword("good-code", "MatKhauMoi123");

        verify(encoder).encode("MatKhauMoi123");
        verify(transactions).executeWithoutResult(org.mockito.ArgumentMatchers.any());
    }
}
