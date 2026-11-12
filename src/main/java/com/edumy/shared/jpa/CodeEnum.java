package com.edumy.shared.jpa;

/**
 * Enum stored in a {@code VARCHAR + CHECK IN (...)} column by its lowercase DB code (docs/ai/03 §3.1),
 * e.g. {@code CourseStatus.PENDING_REVIEW.code() == "pending_review"}.
 */
public interface CodeEnum {

    String code();

    static <E extends Enum<E> & CodeEnum> E fromCode(Class<E> type, String code) {
        for (E constant : type.getEnumConstants()) {
            if (constant.code().equals(code)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("Unknown " + type.getSimpleName() + " code: " + code);
    }
}
