package com.edumy.learning.mapper;

import com.edumy.learning.dto.response.ReviewResponse;
import com.edumy.learning.entity.CourseReview;
import org.mapstruct.Mapper;

/** Review → response. The course comes from the enrollment (BR-25). */
@Mapper(componentModel = "spring")
public interface ReviewMapper {

    default ReviewResponse toResponse(CourseReview review) {
        return new ReviewResponse(review.getId(), review.getEnrollment().getCourseId(), review.getRating(),
                review.getComment(), review.getVisibilityStatus().code(), review.getCreatedAt(),
                review.getUpdatedAt());
    }
}
