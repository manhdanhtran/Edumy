package com.edumy.learning.mapper;

import com.edumy.identity.api.UserSummary;
import com.edumy.learning.dto.response.AnswerResponse;
import com.edumy.learning.dto.response.QuestionSummaryResponse;
import com.edumy.learning.dto.response.QuestionThreadResponse;
import com.edumy.learning.entity.CourseQuestion;
import com.edumy.learning.entity.QuestionAnswer;
import com.edumy.learning.repository.CourseQuestionRepository.QuestionSummaryView;
import java.util.List;
import org.mapstruct.Mapper;

/** Questions and answers → responses. Names come from {@code identity::api} (null = account gone). */
@Mapper(componentModel = "spring")
public interface QaMapper {

    /** List row; {@code courseTitle} null on the course list. */
    default QuestionSummaryResponse toSummary(QuestionSummaryView q, UserSummary asker, String courseTitle) {
        return new QuestionSummaryResponse(q.getId(), q.getCourseId(), courseTitle, q.getLessonId(), q.getTitle(),
                q.isResolved(), q.getVisibilityStatus().code(), asker(q.getAskerId(), asker), q.getAnswerCount(),
                q.isInstructorReplied(), q.getCreatedAt());
    }

    /** Thread; {@code instructorReplied} is computed from the answers shown (visible instructor answer, D-39). */
    default QuestionThreadResponse toThread(CourseQuestion q, UserSummary asker, List<AnswerResponse> answers,
                                            boolean instructorReplied) {
        return new QuestionThreadResponse(q.getId(), q.getEnrollment().getCourseId(), q.getLessonId(), q.getTitle(),
                q.getContent(), q.isResolved(), q.getVisibilityStatus().code(),
                asker(q.getEnrollment().getStudentId(), asker), instructorReplied, q.getCreatedAt(),
                q.getUpdatedAt(), answers);
    }

    default AnswerResponse toAnswer(QuestionAnswer a, UserSummary author) {
        return new AnswerResponse(a.getId(),
                new AnswerResponse.Author(a.getAuthorId(), author == null ? null : author.fullName()),
                a.getAuthorType().code(), a.getContent(), a.getVisibilityStatus().code(), a.getCreatedAt(),
                a.getUpdatedAt());
    }

    private static QuestionSummaryResponse.Asker asker(Long id, UserSummary user) {
        return new QuestionSummaryResponse.Asker(id, user == null ? null : user.fullName());
    }
}
