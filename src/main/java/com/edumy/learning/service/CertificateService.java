package com.edumy.learning.service;

import com.edumy.authoring.api.CourseQueryApi;
import com.edumy.authoring.api.CourseSummary;
import com.edumy.identity.api.UserQueryApi;
import com.edumy.identity.api.UserSummary;
import com.edumy.learning.api.CertificateIssued;
import com.edumy.learning.entity.Certificate;
import com.edumy.learning.entity.Enrollment;
import com.edumy.learning.repository.CertificateRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
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
    private final CourseQueryApi courses;
    private final UserQueryApi users;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public CertificateService(CertificateRepository certificates, CourseQueryApi courses, UserQueryApi users,
                              ApplicationEventPublisher events, Clock clock) {
        this.certificates = certificates;
        this.courses = courses;
        this.users = users;
        this.events = events;
        this.clock = clock;
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
