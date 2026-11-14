package com.edumy.authoring.service;

/** UC-I01 input of {@link CourseService#create}: already validated (Bean Validation on the HTTP request). */
public record CreateCourseCommand(String title, String description, String thumbnailUrl, Long categoryId,
                                  long price) {
}
