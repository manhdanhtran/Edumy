package com.edumy.learning.service;

import com.edumy.learning.dto.response.CertificateResponse;
import com.edumy.learning.entity.Certificate;
import com.edumy.learning.integration.CertificatePdfRenderer;
import com.edumy.learning.mapper.CertificateMapper;
import com.edumy.learning.repository.CertificateRepository;
import com.edumy.shared.error.BusinessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-S20 list of the caller's certificates (the issue-on-read catch-up of step 1 is not implemented, see progress.md; the PDF is
 * a stub of P07). Not in docs/ai/01 §1.5, named per §1.7. Statements: page + count.
 */
@Service
@Transactional(readOnly = true)
public class CertificateQueryService {

    private final CertificateRepository certificates;
    private final CertificateMapper mapper;
    private final CertificatePdfRenderer renderer;

    public CertificateQueryService(CertificateRepository certificates, CertificateMapper mapper,
                                   CertificatePdfRenderer renderer) {
        this.certificates = certificates;
        this.mapper = mapper;
        this.renderer = renderer;
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
        return new CertificatePdf(certificate.certificateCode(), renderer.render(certificate));
    }

    /** Newest first; {@code sort} is ignored. */
    public Page<CertificateResponse> listMine(Long studentId, Pageable pageable) {
        return certificates.findByStudent(studentId, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()))
                .map(mapper::toResponse);
    }
}
