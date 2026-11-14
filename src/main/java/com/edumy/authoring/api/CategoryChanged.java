package com.edumy.authoring.api;

import java.util.Objects;

/**
 * A category was created, renamed, (de)activated or deleted (UC-A06…A08). Catalog evicts its cache of active
 * categories ({@code catalog.categories}, docs/ai/05 §5.3) after commit. Ids only.
 */
public record CategoryChanged(Long categoryId) {

    public CategoryChanged {
        Objects.requireNonNull(categoryId, "categoryId");
    }
}
