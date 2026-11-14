package com.edumy.learning.controller;

import com.edumy.learning.dto.response.CertificateResponse;
import com.edumy.learning.service.CertificateQueryService;
import com.edumy.shared.security.CurrentUserId;
import com.edumy.shared.web.StubSupport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's certificates (UC-S20), scoped by the caller's id. The PDF route is a stub of P07. */
@RestController
@RequestMapping("/api/v1/me/certificates")
@Tag(name = "learning")
public class CertificateController {

    private final CertificateQueryService queries;

    public CertificateController(CertificateQueryService queries) {
        this.queries = queries;
    }

    /** {@code page}, {@code size} (default 20, max 100); newest first ({@code sort} is ignored). */
    @GetMapping
    @Operation(summary = "UC-S20 Xem chứng chỉ của tôi (mã, tên khóa, ngày cấp)")
    public PagedModel<CertificateResponse> mine(@CurrentUserId Long userId, @ParameterObject Pageable pageable) {
        return new PagedModel<>(queries.listMine(userId, pageable));
    }

    /**
     * Phase-1 stub, header {@code X-Edumy-Stub: true}: 200 + a static sample PDF (attachment named after the code) for
     * the caller's own certificate; nothing is written. Unknown, malformed or another student's code gives 404
     * {@code RESOURCE_NOT_FOUND} (identical).
     */
    @GetMapping("/{code}/pdf")
    @Operation(summary = "UC-S20 Tải PDF chứng chỉ (STUB-P1: PDF mẫu tĩnh, kiểm tra quyền sở hữu thật)",
            description = "Phase-1 stub: returns a static sample PDF (X-Edumy-Stub: true); the certificate must be the caller's.")
    public ResponseEntity<byte[]> pdf(@PathVariable String code, @CurrentUserId Long userId,
                                      HttpServletResponse response) {
        StubSupport.mark(response);
        // STUB-P1: replace with CertificatePdfRenderer rendering the stored snapshot (template Q-11) — UC-S20, BR-39, BR-40
        CertificateQueryService.CertificatePdf pdf = queries.renderMine(userId, code);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(pdf.certificateCode() + ".pdf").build().toString())
                .cacheControl(CacheControl.noStore())
                .body(pdf.content());
    }
}
