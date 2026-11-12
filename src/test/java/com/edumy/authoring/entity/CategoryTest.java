package com.edumy.authoring.entity;

import static com.edumy.authoring.entity.CourseTest.NOW;
import static com.edumy.authoring.entity.CourseTest.assertConflict;
import static com.edumy.authoring.entity.CourseTest.assertValidationFailed;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CategoryTest {

    @Test
    void create_validInput_activeTrimmedName_UCA06() {
        Category category = Category.create("  Lập trình web ", "lap-trinh-web", NOW);

        assertThat(category.getName()).isEqualTo("Lập trình web");
        assertThat(category.getSlug()).isEqualTo("lap-trinh-web");
        assertThat(category.isActive()).isTrue();
        assertThat(category.getCreatedAt()).isEqualTo(NOW);
        assertThat(category.getUpdatedAt()).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Web", "lap_trinh", "lap trinh", "a--b", "-a", "a-", "web\n"})
    void create_invalidSlug_rejected_ckCategoriesSlug(String slug) {
        assertValidationFailed(() -> Category.create("Web", slug, NOW), "slug");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void create_blankName_rejected(String name) {
        assertValidationFailed(() -> Category.create(name, "web", NOW), "name");
    }

    @Test
    void rename_validInput_nameAndSlugChanged_UCA07() {
        Category category = Category.create("Web", "web", NOW);

        category.rename(" Lập trình web ", "lap-trinh-web");

        assertThat(category.getName()).isEqualTo("Lập trình web");
        assertThat(category.getSlug()).isEqualTo("lap-trinh-web");
    }

    @Test
    void rename_invalidSlug_rejectedAndUnchanged() {
        Category category = Category.create("Web", "web", NOW);

        assertValidationFailed(() -> category.rename("Mới", "Mới"), "slug");
        assertThat(category.getName()).isEqualTo("Web");
        assertThat(category.getSlug()).isEqualTo("web");
    }

    @Test
    void rename_blankName_rejectedAndUnchanged() {
        Category category = Category.create("Web", "web", NOW);

        assertValidationFailed(() -> category.rename(" ", "moi"), "name");
        assertThat(category.getSlug()).isEqualTo("web");
    }

    @Test
    void deactivate_active_inactive_D21() {
        Category category = Category.create("Web", "web", NOW);

        category.deactivate();

        assertThat(category.isActive()).isFalse();
    }

    @Test
    void deactivate_inactive_rejected() {
        Category category = Category.create("Web", "web", NOW);
        category.deactivate();

        assertConflict(category::deactivate);
        assertThat(category.isActive()).isFalse();
    }

    @Test
    void activate_inactive_active() {
        Category category = Category.create("Web", "web", NOW);
        category.deactivate();

        category.activate();

        assertThat(category.isActive()).isTrue();
    }

    @Test
    void activate_active_rejected() {
        Category category = Category.create("Web", "web", NOW);

        assertConflict(category::activate);
        assertThat(category.isActive()).isTrue();
    }
}
