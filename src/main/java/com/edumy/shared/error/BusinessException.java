package com.edumy.shared.error;

import java.util.List;

/**
 * Thrown by services for expected business failures. Rendered as RFC 9457 ProblemDetail by
 * {@link GlobalExceptionHandler}. {@code args} fill the {0}, {1}… placeholders of {@code error.<CODE>.detail};
 * pass ids as {@code String} to avoid locale number formatting ("1.000").
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode code;
    private final transient Object[] args;
    private final List<FieldViolation> errors;

    public BusinessException(ErrorCode code, Object... args) {
        this(code, List.of(), args);
    }

    private BusinessException(ErrorCode code, List<FieldViolation> errors, Object... args) {
        super(code.name());
        this.code = code;
        this.args = args == null ? new Object[0] : args.clone();
        this.errors = List.copyOf(errors);
    }

    /** For rule violations that list several failed items, e.g. the BR-07 submit checklist. */
    public static BusinessException withErrors(ErrorCode code, List<FieldViolation> errors, Object... args) {
        return new BusinessException(code, errors, args);
    }

    public static BusinessException notFound() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    public ErrorCode getCode() {
        return code;
    }

    public Object[] getArgs() {
        return args.clone();
    }

    public List<FieldViolation> getErrors() {
        return errors;
    }
}
