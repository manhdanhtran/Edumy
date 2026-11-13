package com.edumy.identity.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Opaque secrets of the identity module (docs/ai/04 §4.1/§4.2): 32 {@link SecureRandom} bytes as base64url (no
 * padding, 43 characters), stored only as their SHA-256 lower-case hex (64 characters). Used for refresh tokens and,
 * from P04-c, the emailed verification / reset codes.
 */
final class SecureTokens {

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private SecureTokens() {
    }

    /** A new 256-bit random token, base64url. Never log it (NFR-08). */
    static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return BASE64URL.encodeToString(bytes);
    }

    /** SHA-256 of the UTF-8 bytes of {@code rawToken}, lower-case hex: the only form stored in the database. */
    static String sha256Hex(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e); // every JVM must provide it
        }
    }
}
