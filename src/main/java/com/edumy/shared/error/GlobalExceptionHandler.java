package com.edumy.shared.error;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.PersistenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every error as RFC 9457 ProblemDetail with extra properties {@code code}, {@code traceId} and, when
 * present, {@code errors} (docs/ai/05-api-contract.md §5.2). Titles/details are Vietnamese, from messages_vi.properties.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    static final String TYPE_BASE = "https://edumy.local/problems/";
    private static final Locale VI = Locale.forLanguageTag("vi");
    private static final Object[] NO_ARGS = new Object[0];
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MessageSource messages;
    private final ConstraintViolationTranslator translator;

    public GlobalExceptionHandler(MessageSource messages, ConstraintViolationTranslator translator) {
        this.messages = messages;
        this.translator = translator;
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<Object> handleBusiness(BusinessException ex, WebRequest request) {
        return build(ex.getCode(), ex.getCode().status(), ex.getArgs(), ex.getErrors(), request, traceId());
    }

    /**
     * JPA/Hibernate failures (PersistenceException) and Spring JDBC failures (DataAccessException) both end here: the
     * translator decides by MySQL vendor code on the root SQLException (a constraint violation may surface as either
     * family, or as an UncategorizedSQLException that is not a DataIntegrityViolationException, e.g. CHECK 3819).
     */
    @ExceptionHandler({DataAccessException.class, PersistenceException.class})
    ResponseEntity<Object> handlePersistence(Exception ex, WebRequest request) throws Exception {
        var translated = translator.translate(ex);
        if (translated.isPresent()) {
            ErrorCode code = translated.get().code();
            return build(code, code.status(), NO_ARGS, List.of(), request, traceId());
        }
        if (ex instanceof DataIntegrityViolationException) {
            String traceId = traceId();
            log.warn("Untranslated data integrity violation, traceId={}", traceId, ex);
            return build(ErrorCode.DATA_CONFLICT, ErrorCode.DATA_CONFLICT.status(), NO_ARGS, List.of(), request,
                    traceId);
        }
        return handleUnexpected(ex, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) throws Exception {
        if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
            throw ex; // let Spring Security's ExceptionTranslationFilter decide between 401 and 403
        }
        String traceId = traceId();
        log.error("Unhandled exception, traceId={}", traceId, ex);
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.status(), NO_ARGS, List.of(), request, traceId);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> errors.add(new FieldViolation(fe.getField(), fe.getDefaultMessage())));
        ex.getBindingResult().getGlobalErrors()
                .forEach(ge -> errors.add(new FieldViolation(ge.getObjectName(), ge.getDefaultMessage())));
        return build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.status(), NO_ARGS, errors, request,
                traceId());
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String param = result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                String field = error instanceof FieldError fe ? fe.getField() : param;
                errors.add(new FieldViolation(field, error.getDefaultMessage()));
            }
        });
        return build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.status(), NO_ARGS, errors, request,
                traceId());
    }

    /** Every other Spring MVC exception (404 no handler, 405, 415, unreadable body…): keep status, add our shape. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (request instanceof ServletWebRequest swr && swr.getResponse() != null && swr.getResponse().isCommitted()) {
            return null;
        }
        ErrorCode code = statusCode.value() == 404 ? ErrorCode.RESOURCE_NOT_FOUND
                : statusCode.is5xxServerError() ? ErrorCode.INTERNAL_ERROR
                : ErrorCode.MALFORMED_REQUEST;
        ProblemDetail problem = problem(code, statusCode, NO_ARGS, List.of(), request, traceId());
        return ResponseEntity.status(statusCode).headers(headers).body(problem);
    }

    private ResponseEntity<Object> build(ErrorCode code, HttpStatusCode status, Object[] args,
            List<FieldViolation> errors, WebRequest request, String traceId) {
        return ResponseEntity.status(status).body(problem(code, status, args, errors, request, traceId));
    }

    private ProblemDetail problem(ErrorCode code, HttpStatusCode status, Object[] args,
            List<FieldViolation> errors, WebRequest request, String traceId) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(TYPE_BASE + code.slug()));
        problem.setTitle(messages.getMessage("error." + code.name() + ".title", null, code.name(), VI));
        problem.setDetail(messages.getMessage("error." + code.name() + ".detail", args, null, VI));
        if (request instanceof ServletWebRequest swr) {
            problem.setInstance(URI.create(swr.getRequest().getRequestURI()));
        }
        problem.setProperty("code", code.name());
        problem.setProperty("traceId", traceId);
        if (!errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
        return problem;
    }

    private static String traceId() {
        String fromMdc = MDC.get("traceId");
        return fromMdc != null ? fromMdc : UUID.randomUUID().toString().replace("-", "");
    }
}
