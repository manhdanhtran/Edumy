package com.edumy.learning.controller;

import com.edumy.learning.dto.response.CertificateResponse;
import com.edumy.learning.service.CertificateQueryService;
import com.edumy.shared.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
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
}
