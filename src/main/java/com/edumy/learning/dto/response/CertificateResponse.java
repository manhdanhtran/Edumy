package com.edumy.learning.dto.response;

import java.time.Instant;

/** UC-S20 row: the snapshot taken when the certificate was issued (BR-40). */
public record CertificateResponse(
        Long id,
        String certificateCode,
        Long enrollmentId,
        Long courseId,
        String courseTitle,
        String studentName,
        String instructorName,
        Instant issuedAt) {
}
