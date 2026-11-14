package com.edumy.learning.integration;

import com.edumy.learning.dto.response.CertificateResponse;

/**
 * Port that produces the PDF of a certificate from its stored snapshot (UC-S20 step 3, BR-40). The file is rendered on
 * demand and never stored (D-48).
 */
public interface CertificatePdfRenderer {

    /** The bytes of a valid PDF document for {@code certificate}; fails with an unchecked exception when it cannot be produced. */
    byte[] render(CertificateResponse certificate);
}
