package com.edumy.learning.listener;

import com.edumy.learning.api.CertificateIssued;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Logging reaction to {@link CertificateIssued} (docs/ai/01 §1.10, NFR-08): after the issuing transaction committed,
 * writes the ids only (no code, no names).
 */
@Component
public class CertificateIssuedListener {

    private static final Logger log = LoggerFactory.getLogger(CertificateIssuedListener.class);

    @TransactionalEventListener
    public void on(CertificateIssued event) {
        log.info("Certificate issued: certificateId={}, enrollmentId={}, userId={}, courseId={}",
                event.certificateId(), event.enrollmentId(), event.studentId(), event.courseId());
    }
}
