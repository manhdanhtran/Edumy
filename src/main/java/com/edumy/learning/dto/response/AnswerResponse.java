package com.edumy.learning.dto.response;

import java.time.Instant;

/** An answer of a question thread. {@code authorType} is the snapshot taken when it was written (BR-38). */
public record AnswerResponse(
        Long id,
        Author author,
        String authorType,
        String content,
        String status,
        Instant createdAt,
        Instant updatedAt) {

    /** {@code fullName} is null when the account is gone. */
    public record Author(Long id, String fullName) {
    }
}
