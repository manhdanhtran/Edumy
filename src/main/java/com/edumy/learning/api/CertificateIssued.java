package com.edumy.learning.api;

import java.util.Objects;

/**
 * BR-39: the certificate of an enrollment was issued (UC-S10 step 4), published inside the issuing transaction.
 * After commit it is only logged (NFR-08: ids, never the certificate code or any name).
 */
public record CertificateIssued(Long certificateId, Long enrollmentId, Long studentId, Long courseId) {

    public CertificateIssued {
        Objects.requireNonNull(certificateId, "certificateId");
        Objects.requireNonNull(enrollmentId, "enrollmentId");
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(courseId, "courseId");
    }
}
