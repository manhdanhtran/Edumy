package com.edumy.learning.integration;

/**
 * Port that produces the PDF of a certificate from its stored snapshot (UC-S20 step 3, BR-40). The file is rendered on
 * demand and never stored (D-48). The layout is behind this interface so the official template (Q-11, still open) can
 * replace {@link OpenPdfCertificateRenderer} without touching the service.
 */
public interface CertificatePdfRenderer {

    /**
     * The bytes of a valid PDF document for {@code certificate}.
     *
     * @throws CertificateRenderingException the PDF could not be produced (UC-S20 E2)
     */
    byte[] render(CertificatePdfData certificate);
}
