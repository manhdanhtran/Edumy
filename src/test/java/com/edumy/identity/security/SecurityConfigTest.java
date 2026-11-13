package com.edumy.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class SecurityConfigTest {

    @Test
    void passwordEncoder_bcryptCost12_NFR02() {
        PasswordEncoder encoder = new SecurityConfig().passwordEncoder();

        String hash = encoder.encode("Passw0rd123");

        assertThat(hash).startsWith("$2a$12$");
        assertThat(encoder.matches("Passw0rd123", hash)).isTrue();
        assertThat(encoder.matches("Passw0rd124", hash)).isFalse();
    }
}
