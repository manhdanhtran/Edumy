package com.edumy.learning.dto.request;

/**
 * Optional body of UC-S19 {@code POST /questions/{id}/resolve}: {@code resolved} absent or {@code true} marks the
 * question resolved, {@code false} takes the mark back ("bỏ đánh dấu đã giải quyết", D-39).
 */
public record ResolveQuestionRequest(Boolean resolved) {

    public boolean markResolved() {
        return !Boolean.FALSE.equals(resolved);
    }
}
