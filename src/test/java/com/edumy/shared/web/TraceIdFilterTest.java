package com.edumy.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/anything");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final AtomicReference<String> mdcInChain = new AtomicReference<>();
    private final FilterChain recordingChain = (req, res) -> mdcInChain.set(MDC.get(TraceIdFilter.MDC_KEY));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void doFilter_validIncomingTraceId_keptInMdcAndResponseHeader() throws Exception {
        request.addHeader(TraceIdFilter.HEADER, "Client-Trace-0001");

        filter.doFilter(request, response, recordingChain);

        assertThat(mdcInChain.get()).isEqualTo("Client-Trace-0001");
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo("Client-Trace-0001");
    }

    @Test
    void doFilter_noIncomingTraceId_generates32HexId() throws Exception {
        filter.doFilter(request, response, recordingChain);

        assertThat(mdcInChain.get()).matches("[0-9a-f]{32}");
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo(mdcInChain.get());
    }

    @Test
    void doFilter_invalidIncomingTraceId_replaced() throws Exception {
        for (String invalid : new String[] {"short", "a".repeat(65), "bad id with spaces", "abc\r\nInjected: 1",
                "trace_id_underscore", ""}) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/x");
            req.addHeader(TraceIdFilter.HEADER, invalid);
            MockHttpServletResponse res = new MockHttpServletResponse();

            filter.doFilter(req, res, recordingChain);

            assertThat(res.getHeader(TraceIdFilter.HEADER)).as("replacement of [%s]", invalid)
                    .matches("[0-9a-f]{32}").isNotEqualTo(invalid);
            assertThat(mdcInChain.get()).isEqualTo(res.getHeader(TraceIdFilter.HEADER));
        }
    }

    @Test
    void doFilter_boundaryLengths_8and64Accepted() throws Exception {
        assertThat(TraceIdFilter.resolve("abcd-123")).isEqualTo("abcd-123");
        assertThat(TraceIdFilter.resolve("A".repeat(64))).isEqualTo("A".repeat(64));
        assertThat(TraceIdFilter.resolve("abcd-12")).isNotEqualTo("abcd-12");
    }

    @Test
    void doFilter_afterChain_mdcCleared() throws Exception {
        filter.doFilter(request, response, recordingChain);

        assertThat(mdcInChain.get()).isNotNull();
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void doFilter_chainThrows_mdcClearedInFinally() {
        FilterChain failing = (req, res) -> {
            mdcInChain.set(MDC.get(TraceIdFilter.MDC_KEY));
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, failing))
                .isInstanceOf(IllegalStateException.class);

        assertThat(mdcInChain.get()).isNotNull();
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void doFilter_errorDispatchOfSameRequest_reusesTraceId() throws Exception {
        filter.doFilter(request, response, recordingChain);
        String first = response.getHeader(TraceIdFilter.HEADER);

        request.setDispatcherType(DispatcherType.ERROR);
        filter.doFilter(request, response, recordingChain);

        assertThat(mdcInChain.get()).isEqualTo(first);
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo(first);
    }
}
