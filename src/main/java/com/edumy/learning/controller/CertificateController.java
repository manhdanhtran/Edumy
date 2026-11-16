package com.edumy.learning.controller;

import com.edumy.learning.dto.response.CertificateResponse;
import com.edumy.learning.service.CertificateCatchUpService;
import com.edumy.learning.service.CertificateQueryService;
import com.edumy.shared.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
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
    private final CertificateCatchUpService catchUp;

    public CertificateController(CertificateQueryService queries, CertificateCatchUpService catchUp) {
        this.queries = queries;
        this.catchUp = catchUp;
    }

    /**
     * {@code page}, {@code size} (default 20, max 100); newest first ({@code sort} is ignored). UC-S20 step 1 / D-48:
     * a certificate missing at 100 % (a lesson was deleted) is issued first (ADR-009).
     */
    @GetMapping
    @Operation(summary = "UC-S20 Xem chứng chỉ của tôi (mã, tên khóa, ngày cấp; cấp bổ sung nếu đạt 100%)")
    public PagedModel<CertificateResponse> mine(@CurrentUserId Long userId, @ParameterObject Pageable pageable) {
        catchUp.issueMissing(userId, null);
        return new PagedModel<>(queries.listMine(userId, pageable));
    }

    /**
     * UC-S20 step 3 (P12): 200 + the PDF of the caller's certificate, rendered now from the stored snapshot (BR-40, never
     * stored, D-48), as an attachment {@code <code>.pdf}. Unknown, malformed or another student's code gives 404
     * {@code RESOURCE_NOT_FOUND} (identical, NFR-01); the PDF could not be produced → 500 {@code CERTIFICATE_PDF_FAILED},
     * nothing changed (E2).
     */
    @GetMapping("/{code}/pdf")
    @Operation(summary = "UC-S20 Tải PDF chứng chỉ (tạo từ dữ liệu chụp lúc cấp, không lưu tệp)")
    public ResponseEntity<byte[]> pdf(@PathVariable String code, @CurrentUserId Long userId) {        CertificateQueryService.CertificatePdf pdf = queries.renderMine(userId, code);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(pdf.certificateCode() + ".pdf").build().toString())
                .cacheControl(CacheControl.noStore())
                .body(pdf.content());
    }
}
