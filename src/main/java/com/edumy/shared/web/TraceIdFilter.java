package com.edumy.shared.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a trace id (docs/prompts/P04 item 8): keeps a well-formed incoming {@value #HEADER}, otherwise
 * generates one; puts it in the MDC under {@value #MDC_KEY} (log pattern {@code %X{traceId}}) and in the response
 * header {@value #HEADER}. Runs first, before the Spring Security filter chain, so 401/403 ProblemDetails written by
 * the security handlers carry the same {@code traceId} as the header. The MDC entry is always removed in
 * {@code finally} (pooled threads). Error and async dispatches of the same request reuse its trace id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    static final String ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = request.getAttribute(ATTRIBUTE) instanceof String existing
                ? existing
                : resolve(request.getHeader(HEADER));
        request.setAttribute(ATTRIBUTE, traceId);
        response.setHeader(HEADER, traceId);
        MDC.put(MDC_KEY, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    /** The incoming id when it is 8–64 letters, digits or dashes (no log injection); otherwise a new 32-hex id. */
    static String resolve(String incoming) {
        if (incoming != null && VALID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
