package com.edumy.catalog.service;

import com.edumy.catalog.dto.response.CertificateVerificationResponse;
import com.edumy.catalog.repository.CertificateLookupRepository;
import com.edumy.shared.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-G14 public certificate verification (D-47). The per-IP rate limit (E2, NFR-03) is applied before this service by
 * {@code identity.security.CertificateVerifyRateLimiter} in the security filter chain.
 */
@Service
@Transactional(readOnly = true)
public class CertificateVerificationService {

    private final CertificateLookupRepository certificates;

    public CertificateVerificationService(CertificateLookupRepository certificates) {
        this.certificates = certificates;
    }

    /**
     * The snapshot stored at issue time (BR-40). Unknown code → 404 {@code RESOURCE_NOT_FOUND}, which discloses
     * nothing else (UC-G14 E1).
     */
    public CertificateVerificationResponse verify(String code) {
        return certificates.findByCode(code.strip()).orElseThrow(BusinessException::notFound);
    }
}
