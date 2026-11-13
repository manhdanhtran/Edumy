package com.edumy.support;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints guarded by each access bean, so tests can prove the whole chain {@code @PreAuthorize → access
 * bean → BusinessException / false → GlobalExceptionHandler / AccessDeniedHandler → ProblemDetail} before the real
 * course / learning controllers exist (P05+). Every endpoint answers {@code 200 "ok"} when access is granted.
 * <p>Registered only through the {@code @Import} of {@link MySqlSpringBootTest}: {@code @TestComponent} keeps it out of
 * the component scan of every test context, and {@code @Profile("test")} keeps it out of a local run of
 * {@code TestEdumyApplication} (whose scan includes test classes). The path is not public, so the filter chain
 * requires a valid access token first.
 */
@TestComponent
@Profile("test")
@RestController
@RequestMapping(AccessProbeController.BASE)
public class AccessProbeController {

    public static final String BASE = "/api/v1/test-probe";
    static final String OK = "ok";

    // ------------------------------------------------------------------------------------------------- authoring

    @GetMapping("/courses/{courseId}/main")
    @PreAuthorize("@courseAccess.isMain(#courseId)")
    public String courseMain(@PathVariable Long courseId) {
        return OK;
    }

    @GetMapping("/courses/{courseId}/instructor")
    @PreAuthorize("@courseAccess.isInstructor(#courseId)")
    public String courseInstructor(@PathVariable Long courseId) {
        return OK;
    }

    @GetMapping("/courses/{courseId}/edit")
    @PreAuthorize("@courseAccess.canEditContent(#courseId)")
    public String courseEdit(@PathVariable Long courseId) {
        return OK;
    }

    @GetMapping("/chapters/{chapterId}/edit")
    @PreAuthorize("@chapterAccess.canEditContent(#chapterId)")
    public String chapterEdit(@PathVariable Long chapterId) {
        return OK;
    }

    @GetMapping("/lessons/{lessonId}/edit")
    @PreAuthorize("@lessonAccess.canEditContent(#lessonId)")
    public String lessonEdit(@PathVariable Long lessonId) {
        return OK;
    }

    // -------------------------------------------------------------------------------------------------- learning

    @GetMapping("/courses/{courseId}/enrolled")
    @PreAuthorize("@enrollmentAccess.isEnrolled(#courseId)")
    public String enrolled(@PathVariable Long courseId) {
        return OK;
    }

    @GetMapping("/courses/{courseId}/content")
    @PreAuthorize("@enrollmentAccess.canViewContent(#courseId)")
    public String content(@PathVariable Long courseId) {
        return OK;
    }

    @GetMapping("/lessons/{lessonId}/content")
    @PreAuthorize("@enrollmentAccess.canViewLesson(#lessonId)")
    public String lessonContent(@PathVariable Long lessonId) {
        return OK;
    }

    @GetMapping("/questions/{questionId}/reply")
    @PreAuthorize("@qaAccess.canReply(#questionId)")
    public String reply(@PathVariable Long questionId) {
        return OK;
    }

    @GetMapping("/questions/{questionId}/author")
    @PreAuthorize("@qaAccess.isQuestionAuthor(#questionId)")
    public String questionAuthor(@PathVariable Long questionId) {
        return OK;
    }

    @GetMapping("/answers/{answerId}/author")
    @PreAuthorize("@qaAccess.isAnswerAuthor(#answerId)")
    public String answerAuthor(@PathVariable Long answerId) {
        return OK;
    }
}
