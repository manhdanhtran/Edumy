package com.edumy.authoring.service;

/**
 * UC-I03 input of {@link CourseService#update} with PATCH semantics: a {@code null} field is left unchanged;
 * {@code ""} clears the description / thumbnail.
 */
public record UpdateCourseCommand(String title, String description, String thumbnailUrl, Long categoryId,
                                  Long price) {

    /** No field was sent: nothing changes. */
    public boolean isEmpty() {
        return title == null && description == null && thumbnailUrl == null && categoryId == null && price == null;
    }
}
