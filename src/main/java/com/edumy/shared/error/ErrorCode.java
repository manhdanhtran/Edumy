package com.edumy.shared.error;

import org.springframework.http.HttpStatus;

/**
 * API error codes (docs/ai/05-api-contract.md §5.2). Title/detail texts live in messages_vi.properties under
 * {@code error.<CODE>.title} / {@code error.<CODE>.detail}.
 * <p>Status principle: 403 = no permission · 409 = current data state forbids it · 422 = valid input that breaks a
 * business rule. "Not found" and "not yours" both use {@link #RESOURCE_NOT_FOUND} with an identical body (NFR-01).
 */
public enum ErrorCode {

    // Generic
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(HttpStatus.FORBIDDEN),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    DATA_CONFLICT(HttpStatus.CONFLICT),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),

    // identity
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    AUTH_LOGIN_TEMPORARILY_BLOCKED(HttpStatus.TOO_MANY_REQUESTS),
    AUTH_ACCOUNT_LOCKED(HttpStatus.FORBIDDEN),                       // Q1: correct password, status locked
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT),                   // UC-G05 E1; also uq_users_email
    ACCOUNT_TOKEN_INVALID(HttpStatus.UNPROCESSABLE_CONTENT),         // UC-G06 / UC-G08 E1: never says which (BR-34)
    TOKEN_RESEND_TOO_SOON(HttpStatus.TOO_MANY_REQUESTS),             // D-03: 60 s between codes
    PASSWORD_INCORRECT(HttpStatus.UNPROCESSABLE_CONTENT),            // UC-G12: wrong current password
    PASSWORD_SAME_AS_OLD(HttpStatus.UNPROCESSABLE_CONTENT),          // UC-G12: new = current
    INSTRUCTOR_ROLE_REVOKED(HttpStatus.FORBIDDEN),                   // UC-G13 E2, D-04
    ADMIN_LAST_ACTIVE(HttpStatus.CONFLICT),
    ADMIN_LOCK_FORBIDDEN(HttpStatus.FORBIDDEN),                      // UC-A02, BR-36: target is the caller or an admin
    ACCOUNT_ALREADY_LOCKED(HttpStatus.CONFLICT),                     // UC-A02: target not active
    ACCOUNT_NOT_LOCKED(HttpStatus.CONFLICT),                         // UC-A03: target not locked
    INSTRUCTOR_ROLE_ALREADY_ACTIVE(HttpStatus.CONFLICT),             // UC-A04 E1
    INSTRUCTOR_ROLE_NOT_ACTIVE(HttpStatus.CONFLICT),                 // UC-A05 E1

    // authoring
    COURSE_NOT_EDITABLE(HttpStatus.CONFLICT),
    COURSE_SUBMIT_CHECKLIST_FAILED(HttpStatus.UNPROCESSABLE_CONTENT),
    COURSE_CO_INSTRUCTOR_IS_MAIN(HttpStatus.UNPROCESSABLE_CONTENT),   // BR-02 (trigger)
    COURSE_DELETE_FORBIDDEN(HttpStatus.CONFLICT),                     // BR-19 (trigger)
    LESSON_COURSE_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT),         // BR-24, BR-37 (triggers)
    COURSE_CATEGORY_UNAVAILABLE(HttpStatus.UNPROCESSABLE_CONTENT),    // UC-I01 E / UC-I03: category unknown or inactive
    CATEGORY_ALREADY_EXISTS(HttpStatus.CONFLICT),                     // UC-A06/A07 E: uq_categories_name / _slug
    COURSE_LAST_LESSON_DELETE_FORBIDDEN(HttpStatus.CONFLICT),         // UC-I07/I11 E, D-14: published course keeps a lesson
    COURSE_CO_INSTRUCTOR_NOT_ELIGIBLE(HttpStatus.UNPROCESSABLE_CONTENT), // UC-I13 E1: no account or no active GV role
    COURSE_CO_INSTRUCTOR_ALREADY_INVITED(HttpStatus.CONFLICT),        // UC-I13 E2: pending / accepted; uq_cci_course_instructor
    INVITATION_NOT_PENDING(HttpStatus.CONFLICT),                      // UC-I14/I15 E: invitation revoked or already answered
    COURSE_MAIN_INSTRUCTOR_LEAVE_FORBIDDEN(HttpStatus.FORBIDDEN),     // UC-I20, RBAC §3.3, BR-02: main never leaves
    COURSE_INVALID_TRANSITION(HttpStatus.CONFLICT),                   // UC-I17 pre, UC-A10/A11 E1, A12/A16 pre: wrong status

    // commerce
    CART_COURSE_ALREADY_OWNED(HttpStatus.CONFLICT),
    CART_OWN_COURSE(HttpStatus.UNPROCESSABLE_CONTENT),
    CART_COURSE_FREE(HttpStatus.UNPROCESSABLE_CONTENT),               // UC-S01 E3, BR-09: free courses are enrolled, not carted
    CART_ITEM_DUPLICATE(HttpStatus.CONFLICT),                         // uq_cart_items_cart_course (BR-11)

    // learning
    ENROLLMENT_REQUIRED(HttpStatus.FORBIDDEN),
    REVIEW_HIDDEN_BY_ADMIN(HttpStatus.CONFLICT),
    QA_REPLY_NOT_ALLOWED(HttpStatus.FORBIDDEN);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    /** Problem type URI suffix, e.g. COURSE_NOT_EDITABLE → course-not-editable. */
    public String slug() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
