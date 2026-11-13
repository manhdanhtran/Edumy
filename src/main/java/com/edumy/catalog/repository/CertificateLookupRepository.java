package com.edumy.catalog.repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import com.edumy.catalog.dto.response.CertificateVerificationResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Catalog read of {@code certificates} for the public verification page (UC-G14, ADR-003): SELECT only, the snapshot
 * columns stored at issue time (BR-40), nothing joined.
 */
@Repository
public class CertificateLookupRepository {

    private final JdbcClient jdbc;

    public CertificateLookupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The certificate with code {@code code} ({@code uq_certificates_code}). */
    public Optional<CertificateVerificationResponse> findByCode(String code) {
        return jdbc.sql("""
                        SELECT certificate_code, student_name, course_title, instructor_name, issued_at
                        FROM certificates
                        WHERE certificate_code = :code""")
                .param("code", code)
                .query((rs, n) -> new CertificateVerificationResponse(rs.getString("certificate_code"),
                        rs.getString("student_name"), rs.getString("course_title"), rs.getString("instructor_name"),
                        // DATETIME(6) holds UTC (docs/ai/03 §3.1)
                        rs.getObject("issued_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)))
                .optional();
    }
}
