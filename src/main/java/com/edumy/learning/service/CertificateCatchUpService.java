package com.edumy.learning.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * D-48 catch-up in front of the two reads that show it (UC-S20 step 1 {@code GET /me/certificates}, UC-S11
 * {@code GET /learn/courses/{id}}; ADR-009). Deliberately <b>not</b> transactional: it runs
 * {@link CertificateService#issueMissing} in its own transaction and, if a concurrent request issued the same
 * certificate first and {@code uq_certificates_enrollment} fires (only possible if a writer ever skips the enrollment
 * row lock), swallows that one failure — the certificate exists, the read that follows shows it — instead of turning
 * a GET into a 409 / 500.
 */
@Service
public class CertificateCatchUpService {

    private static final Logger log = LoggerFactory.getLogger(CertificateCatchUpService.class);

    private final CertificateService certificates;

    public CertificateCatchUpService(CertificateService certificates) {
        this.certificates = certificates;
    }

    /** Issues the missing certificates of the caller (one course when {@code courseId} is set). */
    public void issueMissing(Long studentId, Long courseId) {
        try {
            certificates.issueMissing(studentId, courseId);
        } catch (DataIntegrityViolationException e) {
            // uq_certificates_enrollment: issued by a concurrent request; read it back with the normal read
            log.info("Certificate catch-up lost a race, already issued: userId={}, courseId={}", studentId, courseId);
        }
    }
}
