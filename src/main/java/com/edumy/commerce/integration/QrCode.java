package com.edumy.commerce.integration;

import java.util.Objects;

/** QR payload returned by the provider (stored in {@code payment_transactions.qr_data}, shown while pending). */
public record QrCode(String qrData) {

    public QrCode {
        Objects.requireNonNull(qrData, "qrData");
    }
}
