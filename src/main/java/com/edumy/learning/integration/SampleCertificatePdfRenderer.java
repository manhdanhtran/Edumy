package com.edumy.learning.integration;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import com.edumy.learning.dto.response.CertificateResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Phase-1 stand-in: every certificate gets the same static sample PDF from the classpath
 * ({@value #SAMPLE}, docs/ai/01 §1.2); the snapshot is ignored. Loaded once at start-up, so a missing file fails fast.
 */
@Component
public class SampleCertificatePdfRenderer implements CertificatePdfRenderer {

    static final String SAMPLE = "static/sample/certificate-sample.pdf";

    private final byte[] sample;

    public SampleCertificatePdfRenderer() {
        try (InputStream in = new ClassPathResource(SAMPLE).getInputStream()) {
            this.sample = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Missing classpath sample " + SAMPLE, e);
        }
    }

    @Override
    public byte[] render(CertificateResponse certificate) {
        // STUB-P1: replace with a real renderer (template Q-11) — UC-S20, BR-39, BR-40
        return sample.clone();
    }
}
