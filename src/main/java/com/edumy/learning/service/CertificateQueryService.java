package com.edumy.learning.service;

import com.edumy.learning.dto.response.CertificateResponse;
import com.edumy.learning.entity.Certificate;
import com.edumy.learning.integration.CertificatePdfData;
import com.edumy.learning.integration.CertificatePdfRenderer;
import com.edumy.learning.integration.CertificateRenderingException;
import com.edumy.learning.mapper.CertificateMapper;
import com.edumy.learning.repository.CertificateRepository;
import com.edumy.shared.config.EdumyProperties;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-S20 list of the caller's certificates and their PDF (P12, ADR-013). Not in docs/ai/01 §1.5, named per §1.7.
 * Statements: page + count; PDF: one.
 */
@Service
@Transactional(readOnly = true)
public class CertificateQueryService {

    private static final Logger log = LoggerFactory.getLogger(CertificateQueryService.class);

    private final CertificateRepository certificates;
    private final CertificateMapper mapper;
    private final CertificatePdfRenderer renderer;
    private final String verifyBase;

    public CertificateQueryService(CertificateRepository certificates, CertificateMapper mapper,
                                   CertificatePdfRenderer renderer, EdumyProperties properties) {
        this.certificates = certificates;
        this.mapper = mapper;
        this.renderer = renderer;
        this.verifyBase = properties.frontend().baseUrl().replaceAll("/+$", "") + "/certificates/";
    }

    /** A certificate PDF: the public code (for the file name) and the bytes. */
    public record CertificatePdf(String certificateCode, byte[] content) {
    }

    /**
     * UC-S20 step 3: the PDF of one of the caller's certificates, rendered from the stored snapshot (BR-40, D-48), no
     * writes. A malformed code (no database access), an unknown code and another student's code give the same
     * 404 {@code RESOURCE_NOT_FOUND}. One statement.
     */
    public CertificatePdf renderMine(Long studentId, String code) {
        if (code == null || !Certificate.CODE.matcher(code).matches()) {
            throw BusinessException.notFound();
        }
        CertificateResponse certificate = certificates.findViewByCodeAndStudent(code, studentId)
                .map(mapper::toResponse)
                .orElseThrow(BusinessException::notFound);
        CertificatePdfData data = new CertificatePdfData(certificate.certificateCode(), certificate.studentName(),
                certificate.courseTitle(), certificate.instructorName(), certificate.issuedAt(),
                verifyBase + certificate.certificateCode());
        try {
            return new CertificatePdf(certificate.certificateCode(), renderer.render(data));
        } catch (CertificateRenderingException e) { // UC-S20 E2: nothing changes, the student may retry
            log.error("Certificate PDF not produced: certificateCode={}", certificate.certificateCode(), e);
            throw new BusinessException(ErrorCode.CERTIFICATE_PDF_FAILED);
        }
    }

    /** Newest first; {@code sort} is ignored. */
    public Page<CertificateResponse> listMine(Long studentId, Pageable pageable) {
        return certificates.findByStudent(studentId, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()))
                .map(mapper::toResponse);
    }
}
