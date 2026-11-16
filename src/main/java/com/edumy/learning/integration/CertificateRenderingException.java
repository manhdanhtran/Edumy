package com.edumy.learning.integration;

/** The certificate PDF could not be produced (UC-S20 E2); the service answers a ProblemDetail, nothing is changed. */
public class CertificateRenderingException extends RuntimeException {

    public CertificateRenderingException(String message, Throwable cause) {
        super(message, cause);
    }
}
