package com.edumy.learning.service;

import com.edumy.authoring.api.LessonQueryApi;
import com.edumy.learning.dto.response.ProgressResponse;
import com.edumy.learning.entity.Certificate;
import com.edumy.learning.entity.Enrollment;
import com.edumy.learning.entity.LessonProgress;
import com.edumy.learning.mapper.EnrollmentMapper;
import com.edumy.learning.repository.CertificateRepository;
import com.edumy.learning.repository.EnrollmentRepository;
import com.edumy.learning.repository.LessonProgressRepository;
import com.edumy.shared.error.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Progress writes: UC-S10 manual completion toggle (D-33) with the certificate issue at 100 % (BR-39), and the D-34
 * lesson visit ("Học tiếp"). Access ({@code @enrollmentAccess}) is decided before; the student id always comes from the
 * JWT. Both writes take the enrollment row {@code FOR UPDATE} first and run in READ COMMITTED: concurrent requests of one
 * enrollment queue up and the second one counts the first one's committed completions (under REPEATABLE READ its
 * snapshot, taken by the first read, would miss them and the last two completions of two tabs could both see 99 %).
 */
@Service
public class ProgressService {

    private static final Logger log = LoggerFactory.getLogger(ProgressService.class);

    private final EnrollmentRepository enrollments;
    private final LessonProgressRepository progresses;
    private final CertificateRepository certificates;
    private final CertificateService certificateService;
    private final LessonQueryApi lessons;
    private final EnrollmentMapper mapper;
    private final OwnCourseGuard ownCourse;
    private final Clock clock;

    public ProgressService(EnrollmentRepository enrollments, LessonProgressRepository progresses,
                           CertificateRepository certificates, CertificateService certificateService,
                           LessonQueryApi lessons, EnrollmentMapper mapper, OwnCourseGuard ownCourse, Clock clock) {
        this.enrollments = enrollments;
        this.progresses = progresses;
        this.certificates = certificates;
        this.certificateService = certificateService;
        this.lessons = lessons;
        this.ownCourse = ownCourse;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * UC-S10: mark the lesson done / not done (idempotent: a repeat changes nothing and keeps {@code completed_at}),
     * recompute the progress (D-33) and, when a completion reaches 100 % and the enrollment has no certificate, issue
     * it (BR-39). Unmarking never touches an issued certificate (A1, BR-40), so complete → unmark → complete issues
     * nothing new. A lesson of a course the caller is not enrolled in is a 404, like an unknown lesson.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProgressResponse setCompletion(Long studentId, Long lessonId, boolean completed) {
        Long courseId = courseIdOf(lessonId);
        Enrollment enrollment = enrollments.findByStudentIdAndCourseIdForUpdate(studentId, courseId)
                .orElseThrow(BusinessException::notFound);
        ownCourse.requireNotInstructor(studentId, courseId); // BR-32 / D-32: an instructor of the course has no progress
        Instant now = clock.instant();
        LessonProgress progress = progresses.findByEnrollmentIdAndLessonId(enrollment.getId(), lessonId).orElse(null);
        if (completed) {
            if (progress == null) {
                progress = LessonProgress.start(enrollment, lessonId, now);
            }
            progress.complete(now);
            progresses.saveAndFlush(progress);
        } else if (progress != null) {
            progress.uncomplete();
            progresses.saveAndFlush(progress);
        }
        long done = progresses.countByEnrollmentIdAndCompletedTrue(enrollment.getId());
        long total = lessons.countLessons(Set.of(courseId)).getOrDefault(courseId, 0);
        int percent = EnrollmentMapper.percent(done, total);
        Optional<Certificate> issued = completed && percent == 100
                ? certificateService.issueIfAbsent(enrollment) : Optional.empty();
        String code = issued.map(Certificate::getCertificateCode)
                .or(() -> certificates.findByEnrollmentId(enrollment.getId()).map(Certificate::getCertificateCode))
                .orElse(null);
        log.info("Lesson completion set: userId={}, lessonId={}, completed={}, progress={}%, certificateIssued={}",
                studentId, lessonId, completed, percent, issued.isPresent());
        return mapper.toProgress(lessonId, progress == null ? null : progress.getCompletedAt(),
                done, total, percent, code, issued.isPresent());
    }

    /**
     * D-34: the student opened the lesson now: its progress row is created (not completed) or its
     * {@code last_accessed_at} moves; completion is untouched. Instructors and admins read content without an
     * enrollment and without progress (D-32): for them this is a no-op.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void visit(Long studentId, Long lessonId) {
        Long courseId = courseIdOf(lessonId);
        Optional<Enrollment> enrollment = enrollments.findByStudentIdAndCourseIdForUpdate(studentId, courseId);
        if (enrollment.isEmpty() || ownCourse.isInstructor(studentId, courseId)) {
            return; // D-32: instructors (also an enrolled one, BR-32) and admins read without progress
        }
        Instant now = clock.instant();
        LessonProgress progress = progresses.findByEnrollmentIdAndLessonId(enrollment.get().getId(), lessonId)
                .orElse(null);
        if (progress == null) {
            progress = LessonProgress.start(enrollment.get(), lessonId, now);
        } else {
            progress.visit(now);
        }
        progresses.saveAndFlush(progress);
    }

    private Long courseIdOf(Long lessonId) {
        return lessons.findCourseId(lessonId).orElseThrow(BusinessException::notFound);
    }
}
