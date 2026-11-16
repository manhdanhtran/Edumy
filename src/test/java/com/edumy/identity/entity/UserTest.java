package com.edumy.identity.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class UserTest {

    static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");
    static final String HASH = "$2a$12$abcdefghijklmnopqrstuvabcdefghijklmnopqrstuvwxyz01234";

    static User newUser() {
        return User.register("student@example.com", HASH, "Nguyễn Văn A", NOW);
    }

    @Test
    void register_validInput_activeUnverifiedWithCreatedAt() {
        User user = newUser();

        assertThat(user.getEmail()).isEqualTo("student@example.com");
        assertThat(user.getPasswordHash()).isEqualTo(HASH);
        assertThat(user.getFullName()).isEqualTo("Nguyễn Văn A");
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.isActive()).isTrue();
        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.getCreatedAt()).isEqualTo(NOW);
        assertThat(user.getId()).isNull();
        assertThat(user.getUpdatedAt()).isNull();
    }

    @Test
    void register_unnormalizedEmail_storesTrimmedLowerCase() {
        User user = User.register("  Student.ONE@Example.COM ", HASH, "A", NOW);

        assertThat(user.getEmail()).isEqualTo("student.one@example.com");
    }

    @Test
    void register_fullNameWithSurroundingSpaces_storedTrimmed() {
        assertThat(User.register("a@example.com", HASH, "  Trần Thị B  ", NOW).getFullName()).isEqualTo("Trần Thị B");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void register_blankFullName_rejected(String fullName) {
        assertValidationFailed(() -> User.register("a@example.com", HASH, fullName, NOW), "fullName");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void register_blankEmail_rejected(String email) {
        assertValidationFailed(() -> User.register(email, HASH, "A", NOW), "email");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void register_blankPasswordHash_rejected(String passwordHash) {
        assertThatIllegalArgumentException().isThrownBy(() -> User.register("a@example.com", passwordHash, "A", NOW));
    }

    @Test
    void register_nullNow_rejected() {
        assertThatThrownBy(() -> User.register("a@example.com", HASH, "A", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void normalizeEmail_mixedCaseAndSpaces_trimmedLowerCase() {
        assertThat(User.normalizeEmail(" ABC@EXAMPLE.com\t")).isEqualTo("abc@example.com");
    }

    @Test
    void verifyEmail_unverified_verified() {
        User user = newUser();

        user.verifyEmail();

        assertThat(user.isEmailVerified()).isTrue();
    }

    @Test
    void verifyEmail_alreadyVerified_rejected() {
        User user = newUser();
        user.verifyEmail();

        assertConflict(user::verifyEmail);
        assertThat(user.isEmailVerified()).isTrue();
    }

    @Test
    void changePasswordHash_newHash_replaced() {
        User user = newUser();

        user.changePasswordHash("$2a$12$new");

        assertThat(user.getPasswordHash()).isEqualTo("$2a$12$new");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void changePasswordHash_blank_rejectedAndUnchanged(String newHash) {
        User user = newUser();

        assertThatIllegalArgumentException().isThrownBy(() -> user.changePasswordHash(newHash));
        assertThat(user.getPasswordHash()).isEqualTo(HASH);
    }

    @Test
    void updateFullName_validName_storedTrimmed() {
        User user = newUser();

        user.updateFullName(" Lê Văn C ");

        assertThat(user.getFullName()).isEqualTo("Lê Văn C");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void updateFullName_blank_rejectedAndUnchanged(String newName) {
        User user = newUser();

        assertValidationFailed(() -> user.updateFullName(newName), "fullName");
        assertThat(user.getFullName()).isEqualTo("Nguyễn Văn A");
    }

    @Test
    void lock_active_locked_UCA02() {
        User user = newUser();

        user.lock();

        assertThat(user.getStatus()).isEqualTo(UserStatus.LOCKED);
        assertThat(user.isActive()).isFalse();
    }

    @Test
    void lock_alreadyLocked_rejected() {
        User user = newUser();
        user.lock();

        assertConflict(user::lock);
        assertThat(user.getStatus()).isEqualTo(UserStatus.LOCKED);
    }

    @Test
    void unlock_locked_active_UCA03() {
        User user = newUser();
        user.lock();

        user.unlock();

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void unlock_active_rejected() {
        User user = newUser();

        assertConflict(user::unlock);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void equals_transientInstances_notEqualButSelfEqual() {
        User a = newUser();
        User b = newUser();

        assertThat(a).isEqualTo(a).isNotEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(ErrorCode.DATA_CONFLICT));
    }

    static void assertValidationFailed(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String field) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(e.getErrors()).singleElement().satisfies(v -> assertThat(v.field()).isEqualTo(field));
        });
    }
}
