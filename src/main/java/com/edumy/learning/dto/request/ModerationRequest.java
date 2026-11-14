package com.edumy.learning.dto.request;

import jakarta.validation.constraints.Size;

/**
 * Optional body of the admin hide / restore endpoints (UC-A15, A17, A19). The SRS asks for no reason and the DDD has no
 * column for it (the audit is {@code moderated_by} / {@code moderated_at}), so the note is not stored: it is only
 * written to the log with the admin id. The body may be omitted altogether.
 */
public record ModerationRequest(@Size(max = 500) String reason) {
}
