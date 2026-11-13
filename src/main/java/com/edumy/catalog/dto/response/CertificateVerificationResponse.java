package com.edumy.catalog.dto.response;

import java.time.Instant;

/**
 * UC-G14 public certificate check: exactly the data stored when the certificate was issued (BR-40 snapshot): student
 * name, course title, main instructor name, issue time and code. Nothing else about the student is disclosed.
 */
public record CertificateVerificationResponse(
        String certificateCode,
        String studentName,
        String courseTitle,
        String instructorName,
        Instant issuedAt) {
}
