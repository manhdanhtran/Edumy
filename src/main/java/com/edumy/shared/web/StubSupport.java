package com.edumy.shared.web;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Phase-1 stub marker (ADR-005, docs/ai/06): every {@code // STUB-P1:} endpoint calls {@link #mark} so the front end
 * can see {@code X-Edumy-Stub: true}. Stubs validate input and check authorization but never write to the database.
 */
public final class StubSupport {

    public static final String HEADER = "X-Edumy-Stub";

    private StubSupport() {
    }

    public static void mark(HttpServletResponse response) {
        response.setHeader(HEADER, "true");
    }
}
