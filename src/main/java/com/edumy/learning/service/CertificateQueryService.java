package com.edumy.learning.service;

import com.edumy.learning.dto.response.CertificateResponse;
import com.edumy.learning.mapper.CertificateMapper;
import com.edumy.learning.repository.CertificateRepository;
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

    public CertificateQueryService(CertificateRepository certificates, CertificateMapper mapper) {
        this.certificates = certificates;
        this.mapper = mapper;
    }

    /** Newest first; {@code sort} is ignored. */
    public Page<CertificateResponse> listMine(Long studentId, Pageable pageable) {
        return certificates.findByStudent(studentId, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()))
                .map(mapper::toResponse);
    }
}
