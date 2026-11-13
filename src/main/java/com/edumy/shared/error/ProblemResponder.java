package com.edumy.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Writes a {@link BusinessException} as ProblemDetail from code that runs outside Spring MVC: the Spring Security
 * {@code AuthenticationEntryPoint} / {@code AccessDeniedHandler} and servlet filters (e.g. rate limiters). It hands the
 * exception to MVC's {@link HandlerExceptionResolver}, so {@link GlobalExceptionHandler} renders it exactly like an
 * error thrown by a controller: same shape, Vietnamese texts, {@code instance}, {@code traceId} from the MDC and
 * {@code application/problem+json}. A {@code BusinessException} is never an {@code AccessDeniedException} or
 * {@code AuthenticationException}, so {@link GlobalExceptionHandler} never re-throws it (no loop back into Spring
 * Security).
 */
@Component
public class ProblemResponder {

    private final HandlerExceptionResolver resolver;

    public ProblemResponder(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    public void respond(HttpServletRequest request, HttpServletResponse response, ErrorCode code) {
        respond(request, response, new BusinessException(code));
    }

    public void respond(HttpServletRequest request, HttpServletResponse response, BusinessException exception) {
        if (resolver.resolveException(request, response, null, exception) == null) {
            throw new IllegalStateException("No exception resolver rendered " + exception.getCode());
        }
    }
}
