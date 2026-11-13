package com.edumy.catalog.controller;

import com.edumy.catalog.dto.response.CertificateVerificationResponse;
import com.edumy.catalog.service.CertificateVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public certificate verification (UC-G14, D-47). No login needed; 30 requests/min per client IP, then 429
 * {@code RATE_LIMITED} with {@code Retry-After} (NFR-03, docs/ai/04 §4.4, applied in the security filter chain).
 */
@RestController
@RequestMapping("/api/v1/catalog/certificates")
@Tag(name = "catalog")
public class CertificateVerificationController {

    private final CertificateVerificationService verification;

    public CertificateVerificationController(CertificateVerificationService verification) {
        this.verification = verification;
    }

    @GetMapping("/{code}")
    @Operation(summary = "UC-G14 Xác minh chứng chỉ theo mã (dữ liệu lưu khi cấp; giới hạn tần suất)")
    public CertificateVerificationResponse verify(@PathVariable String code) {
        return verification.verify(code);
    }
}
