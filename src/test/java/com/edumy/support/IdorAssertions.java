package com.edumy.support;

import static com.edumy.support.ProblemAssertions.expectProblem;
import static com.edumy.support.ProblemAssertions.withoutTraceAndInstance;
import static org.assertj.core.api.Assertions.assertThat;

import com.edumy.shared.error.ErrorCode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * Anti-IDOR check (NFR-01, docs/ai/07): another user's resource and a resource that does not exist must both answer
 * 404 {@code RESOURCE_NOT_FOUND} with bodies that differ only in {@code traceId} and {@code instance}, so a caller
 * cannot tell "exists but not yours" from "does not exist".
 */
public final class IdorAssertions {

    private IdorAssertions() {
    }

    /**
     * Performs both requests (same caller, e.g. user B) and asserts the identical 404s. Use a different but similar
     * id for {@code missing} (e.g. {@code Long.MAX_VALUE}) and the same endpoint.
     */
    public static void assertSameNotFound(MockMvc mvc, RequestBuilder othersResource, RequestBuilder missing)
            throws Exception {
        String code = ErrorCode.RESOURCE_NOT_FOUND.name();
        MvcResult others = expectProblem(mvc.perform(othersResource), 404, code);
        MvcResult absent = expectProblem(mvc.perform(missing), 404, code);
        assertThat(withoutTraceAndInstance(others))
                .as("404 of another user's resource must equal the 404 of a missing one (NFR-01)")
                .isEqualTo(withoutTraceAndInstance(absent));
    }

    /**
     * 403/404 principle (ADR-008 §2): a <b>published</b> course is public, so a caller without the needed relation
     * gets 403 {@code ENROLLMENT_REQUIRED} — every request must answer it, with one and the same body (only
     * {@code traceId} / {@code instance} differ), so the answer tells nothing about the caller's other relations.
     */
    public static void assertEnrollmentRequired(MockMvc mvc, RequestBuilder... requests) throws Exception {
        String first = null;
        for (RequestBuilder request : requests) {
            String body = withoutTraceAndInstance(
                    expectProblem(mvc.perform(request), 403, ErrorCode.ENROLLMENT_REQUIRED.name()));
            if (first == null) {
                first = body;
            } else {
                assertThat(body).as("every 403 ENROLLMENT_REQUIRED carries the same body").isEqualTo(first);
            }
        }
    }
}
