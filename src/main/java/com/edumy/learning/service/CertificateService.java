package com.edumy.learning.service;

import com.edumy.authoring.api.CourseQueryApi;
import com.edumy.authoring.api.CourseSummary;
import com.edumy.authoring.api.LessonQueryApi;
import com.edumy.identity.api.UserQueryApi;
import com.edumy.identity.api.UserSummary;
import com.edumy.learning.api.CertificateIssued;
import com.edumy.learning.entity.Certificate;
import com.edumy.learning.entity.Enrollment;
import com.edumy.learning.mapper.EnrollmentMapper;
import com.edumy.learning.repository.CertificateRepository;
import com.edumy.learning.repository.EnrollmentRepository;
import com.edumy.learning.repository.EnrollmentRepository.CertificateCandidateView;
import com.edumy.learning.repository.LessonProgressRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Certificate issue (BR-39, BR-40, D-45, D-46): once per enrollment at 100 % progress, free or paid, with a permanent
 * snapshot of the student's name, the course title and the main instructor's name. Exactly-once is guaranteed by the
 * caller holding the enrollment row lock ({@code findByStudentIdAndCourseIdForUpdate}) while it checks and issues;
 * {@code uq_certificates_enrollment} stays the safety net.
 */
@Service
public class CertificateService {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_ATTEMPTS = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CertificateRepository certificates;
    private final EnrollmentRepository enrollments;
    private final LessonProgressRepository progresses;
    private final CourseQueryApi courses;
    private final LessonQueryApi lessons;
    private final UserQueryApi users;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public CertificateService(CertificateRepository certificates, EnrollmentRepository enrollments,
                              LessonProgressRepository progresses, CourseQueryApi courses, LessonQueryApi lessons,
                              UserQueryApi users, ApplicationEventPublisher events, Clock clock) {
        this.certificates = certificates;
        this.enrollments = enrollments;
        this.progresses = progresses;
        this.courses = courses;
        this.lessons = lessons;
        this.users = users;
        this.events = events;
        this.clock = clock;
    }

    /**
     * D-48 / UC-S20 step 1 / UC-S11 "cấp chứng chỉ bổ sung nếu đạt 100% mà chưa có" (ADR-009): issues the missing
     * certificate of every enrollment of {@code studentId} — only that student's, one course when {@code courseId} is
     * set — that is at 100 % of the lessons existing now without a certificate (100 % reached because a lesson was
     * deleted, BR-39). A write behind a GET, idempotent: each candidate's enrollment row is locked
     * ({@code FOR UPDATE}, READ COMMITTED — the same lock order as UC-S10) and re-counted before {@link #issueIfAbsent},
     * so two concurrent reads issue one certificate; {@code uq_certificates_enrollment} stays the safety net (the
     * caller treats its violation as "issued by the other request"). Returns how many were issued.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int issueMissing(Long studentId, Long courseId) {
        List<CertificateCandidateView> candidates = enrollments.findCertificateCandidates(studentId, courseId);
        if (candidates.isEmpty()) {
            return 0;
        }
        Map<Long, Integer> totals = lessons.countLessons(
                candidates.stream().map(CertificateCandidateView::getCourseId).collect(Collectors.toSet()));
        int issued = 0;
        for (CertificateCandidateView candidate : candidates) {
            int total = totals.getOrDefault(candidate.getCourseId(), 0);
            if (total == 0 || candidate.getCompletedLessonCount() < total) {
                continue;
            }
            Enrollment enrollment = enrollments.findByStudentIdAndCourseIdForUpdate(studentId, candidate.getCourseId())
                    .orElseThrow(() -> new IllegalStateException("enrollment vanished"));
            // re-checked under the lock: another request may have issued it, or a lesson may have been added
            long done = progresses.countByEnrollmentIdAndCompletedTrue(enrollment.getId());
            long now = lessons.countLessons(Set.of(candidate.getCourseId())).getOrDefault(candidate.getCourseId(), 0);
            if (EnrollmentMapper.percent(done, now) == 100 && issueIfAbsent(enrollment).isPresent()) {
                issued++;
            }
        }
        return issued;
    }

    /**
     * Issues the certificate of {@code enrollment} unless it has one: returns the new certificate, or empty when it
     * already existed (nothing written, no event). The caller has verified 100 % and holds the enrollment lock. Must
     * run inside the caller's transaction ({@code MANDATORY}): the certificate commits with the progress row.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Certificate> issueIfAbsent(Enrollment enrollment) {
        if (certificates.findByEnrollmentId(enrollment.getId()).isPresent()) {
            return Optional.empty();
        }
        CourseSummary course = courses.findSummaries(List.of(enrollment.getCourseId())).get(enrollment.getCourseId());
        if (course == null) {
            throw new IllegalStateException("course " + enrollment.getCourseId() + " of enrollment vanished");
        }
        var names = users.findSummaries(List.of(enrollment.getStudentId(), course.mainInstructorId()));
        Certificate certificate = Certificate.issue(enrollment, newCode(), name(names.get(enrollment.getStudentId())),
                course.title(), name(names.get(course.mainInstructorId())), clock.instant());
        certificates.saveAndFlush(certificate);
        events.publishEvent(new CertificateIssued(certificate.getId(), enrollment.getId(), enrollment.getStudentId(),
                enrollment.getCourseId()));
        return Optional.of(certificate);
    }

    /** A code not yet used (pre-check; 36^12 values, so a collision is practically impossible). */
    private String newCode() {
        for (int attempt = 0; attempt < CODE_ATTEMPTS; attempt++) {
            String code = "EDU-" + block() + "-" + block() + "-" + block();
            if (!certificates.existsByCertificateCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("no free certificate code after " + CODE_ATTEMPTS + " attempts");
    }

    private static String block() {
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String name(UserSummary user) {
        if (user == null) {
            throw new IllegalStateException("account of a certificate snapshot is missing");
        }
        return user.fullName();
    }
}
