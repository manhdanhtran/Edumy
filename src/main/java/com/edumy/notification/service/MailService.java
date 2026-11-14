package com.edumy.notification.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.edumy.authoring.api.CourseQueryApi;
import com.edumy.authoring.api.CourseSummary;
import com.edumy.identity.api.UserQueryApi;
import com.edumy.identity.api.UserSummary;
import com.edumy.notification.integration.MailGateway;
import org.springframework.stereotype.Service;

/**
 * Composes a mail ({@link MailComposer}) and hands it to the {@link MailGateway}. No own database access, so no
 * transaction; mails of events carrying ids only read the data through the owners' {@code api/} (docs/ai/01 §1.8).
 * Exceptions propagate to the caller (the mail listeners), which log and swallow them (BR-20).
 */
@Service
public class MailService {

    private final MailComposer composer;
    private final MailGateway gateway;
    private final UserQueryApi users;
    private final CourseQueryApi courses;

    public MailService(MailComposer composer, MailGateway gateway, UserQueryApi users, CourseQueryApi courses) {
        this.composer = composer;
        this.gateway = gateway;
        this.users = users;
        this.courses = courses;
    }

    /** UC-G05 / UC-G06: verification link (24 h code). */
    public void sendEmailVerification(String to, String fullName, String rawCode) {
        gateway.send(composer.emailVerification(to, fullName, rawCode));
    }

    /** UC-G08: password-reset link (30 min code). */
    public void sendPasswordReset(String to, String fullName, String rawCode) {
        gateway.send(composer.passwordReset(to, fullName, rawCode));
    }

    /** UC-G08 step 6 / UC-G12: "your password was changed" notice. */
    public void sendPasswordChanged(String to, String fullName, Instant changedAt) {
        gateway.send(composer.passwordChanged(to, fullName, changedAt));
    }

    /**
     * UC-I13 step 5: invitation to co-author a course, mailed to the invitee (link to the SPA invitations page). Names,
     * email and course title are read now through {@code identity::api} / {@code authoring::api} (two statements); if
     * one of them is gone (e.g. the draft course was deleted right after the invitation) nothing is sent and an
     * {@link IllegalStateException} without personal data reaches the listener, which logs it.
     */
    public void sendCoInstructorInvited(Long courseId, Long instructorId, Long invitedBy) {
        Map<Long, UserSummary> people = users.findSummaries(List.of(instructorId, invitedBy));
        UserSummary invitee = people.get(instructorId);
        UserSummary inviter = people.get(invitedBy);
        CourseSummary course = courses.findSummaries(List.of(courseId)).get(courseId);
        if (invitee == null || inviter == null || course == null) {
            throw new IllegalStateException("Invitation mail: invitee, inviter or course no longer exists");
        }
        gateway.send(composer.coInstructorInvited(invitee.email(), invitee.fullName(), inviter.fullName(),
                course.title()));
    }

    /**
     * UC-I21 step 5 / D-42: "a course instructor answered your question" to the asker. Names, email and course title /
     * slug are read now through {@code identity::api} / {@code authoring::api}; if the asker, the instructor or the
     * course is gone nothing is sent and an {@link IllegalStateException} without personal data reaches the listener.
     */
    public void sendQuestionAnswered(Long courseId, Long questionId, String questionTitle, Long askerId,
                                     Long instructorId) {
        Map<Long, UserSummary> people = users.findSummaries(List.of(askerId, instructorId));
        UserSummary asker = people.get(askerId);
        UserSummary instructor = people.get(instructorId);
        CourseSummary course = courses.findSummaries(List.of(courseId)).get(courseId);
        if (asker == null || instructor == null || course == null) {
            throw new IllegalStateException("Answer mail: asker, instructor or course no longer exists");
        }
        gateway.send(composer.questionAnswered(asker.email(), asker.fullName(), instructor.fullName(), questionTitle,
                course.title(), course.slug(), questionId));
    }

    /** UC-A10 step 5: "your course is published" to the main instructor (title, slug, main instructor read now). */
    public void sendCourseApproved(Long courseId) {
        Recipient r = mainInstructorOf(courseId);
        gateway.send(composer.courseApproved(r.user().email(), r.user().fullName(), r.course().title(),
                r.course().slug()));
    }

    /** UC-A11 / D-16: the rejection reason to the main instructor. */
    public void sendCourseRejected(Long courseId, String reason) {
        Recipient r = mainInstructorOf(courseId);
        gateway.send(composer.courseRejected(r.user().email(), r.user().fullName(), r.course().title(), courseId,
                reason));
    }

    /** UC-A12 step 6: the hiding reason to the main instructor. */
    public void sendCourseHidden(Long courseId, String reason) {
        Recipient r = mainInstructorOf(courseId);
        gateway.send(composer.courseHidden(r.user().email(), r.user().fullName(), r.course().title(), courseId,
                reason));
    }

    /**
     * The course and its main instructor (two statements, through the owners' {@code api/}); a course or user gone
     * since → {@link IllegalStateException} without personal data (logged by the listener).
     */
    private Recipient mainInstructorOf(Long courseId) {
        CourseSummary course = courses.findSummaries(List.of(courseId)).get(courseId);
        if (course == null) {
            throw new IllegalStateException("Review mail: course no longer exists");
        }
        UserSummary main = users.findSummaries(List.of(course.mainInstructorId())).get(course.mainInstructorId());
        if (main == null) {
            throw new IllegalStateException("Review mail: main instructor no longer exists");
        }
        return new Recipient(course, main);
    }

    private record Recipient(CourseSummary course, UserSummary user) {
    }
}
