package com.edumy.learning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.Instant;
import java.util.Optional;

import com.edumy.learning.dto.response.CertificateResponse;
import com.edumy.learning.integration.CertificatePdfData;
import com.edumy.learning.integration.CertificatePdfRenderer;
import com.edumy.learning.integration.CertificateRenderingException;
import com.edumy.learning.mapper.CertificateMapper;
import com.edumy.learning.repository.CertificateRepository;
import com.edumy.shared.config.EdumyProperties;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** UC-S20 step 3 / E2 (P12): what the renderer receives, and a rendering failure answered as a ProblemDetail. */
class CertificateQueryServiceTest {

    static final String CODE = "EDU-AAAA-BBBB-CCCC";
    static final CertificateResponse RESPONSE = new CertificateResponse(5L, CODE, 9L, 7L, "Khóa học",
            "Nguyễn Thị Ánh", "Lê Văn Đạt", Instant.parse("2026-11-10T02:00:00Z"));

    final CertificateRepository repository = mock(CertificateRepository.class);
    final CertificateMapper mapper = mock(CertificateMapper.class);
    final CertificatePdfRenderer renderer = mock(CertificatePdfRenderer.class);
    final CertificateQueryService service = new CertificateQueryService(repository, mapper, renderer,
            new EdumyProperties(null, null, null, null, new EdumyProperties.Frontend("https://app.edumy.vn/"),
                    new EdumyProperties.Mail("no-reply@edumy.test")));

    void stored() {
        CertificateRepository.StudentCertificateView view = mock(CertificateRepository.StudentCertificateView.class);
        given(repository.findViewByCodeAndStudent(CODE, 3L)).willReturn(Optional.of(view));
        given(mapper.toResponse(view)).willReturn(RESPONSE);
    }

    @Test
    void rendersTheSnapshotWithTheVerificationLinkOfTheSpa() {
        stored();
        given(renderer.render(any())).willReturn(new byte[] {'%', 'P', 'D', 'F'});

        CertificateQueryService.CertificatePdf pdf = service.renderMine(3L, CODE);

        ArgumentCaptor<CertificatePdfData> data = ArgumentCaptor.forClass(CertificatePdfData.class);
        org.mockito.Mockito.verify(renderer).render(data.capture());
        assertThat(data.getValue()).isEqualTo(new CertificatePdfData(CODE, "Nguyễn Thị Ánh", "Khóa học",
                "Lê Văn Đạt", RESPONSE.issuedAt(), "https://app.edumy.vn/certificates/" + CODE));
        assertThat(pdf.certificateCode()).isEqualTo(CODE);
    }

    @Test
    void rendererFails_problemDetailCode_nothingWritten_UCS20E2() {
        stored();
        given(renderer.render(any())).willThrow(new CertificateRenderingException("boom", new IllegalStateException()));

        assertThatThrownBy(() -> service.renderMine(3L, CODE))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.CERTIFICATE_PDF_FAILED));
        org.mockito.Mockito.verify(repository).findViewByCodeAndStudent(CODE, 3L); // a read, no write method exists
        verifyNoMoreInteractions(repository);
    }
}
