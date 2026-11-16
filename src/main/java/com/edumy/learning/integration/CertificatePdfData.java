package com.edumy.learning.integration;

import java.time.Instant;
import java.util.Objects;

/**
 * What a certificate PDF shows (UC-S20 step 3, BR-40): the snapshot stored when the certificate was issued — student
 * name, course title, main instructor — plus the issue time, the code and the public verification link
 * ({@code {edumy.frontend.base-url}/certificates/{code}}, UC-G14). Never re-read from the live user / course rows.
 */
public record CertificatePdfData(String certificateCode, String studentName, String courseTitle,
                                 String instructorName, Instant issuedAt, String verifyUrl) {

    public CertificatePdfData {
        Objects.requireNonNull(certificateCode, "certificateCode");
        Objects.requireNonNull(studentName, "studentName");
        Objects.requireNonNull(courseTitle, "courseTitle");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(verifyUrl, "verifyUrl");
    }
}
