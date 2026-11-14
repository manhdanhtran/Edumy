package com.edumy.learning.dto.request;

import jakarta.validation.constraints.NotNull;

/** UC-S10: {@code PUT /learn/lessons/{id}/completion}; {@code true} = mark done, {@code false} = unmark (A1). */
public record CompletionRequest(@NotNull Boolean completed) {
}
