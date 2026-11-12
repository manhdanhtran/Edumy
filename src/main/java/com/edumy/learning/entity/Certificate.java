package com.edumy.learning.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

import com.edumy.shared.jpa.JpaEntities;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * {@code certificates} (EDU-DDD-001 §5.20): issued once per enrollment completed at 100 % (BR-39,
 * uq_certificates_enrollment), free or paid. Permanent snapshot of student name, course title and instructor name
 * (BR-40): every column is written once, nothing changes afterwards (no update method, all columns
 * {@code updatable = false}). The code {@code EDU-XXXX-XXXX-XXXX} (ck_certificates_code, uq_certificates_code) and the
 * snapshot values are generated / read by the service, so invalid ones are programming errors
 * ({@link IllegalArgumentException}). The PDF is rendered on demand, never stored (D-48).
 */
@Entity
@Table(name = "certificates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Certificate {

    /** Same as ck_certificates_code ({@code matches()} anchors both ends like the DB's {@code \z}). */
    public static final Pattern CODE = Pattern.compile("EDU-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}");

    static final int CODE_MAX_LENGTH = 19;
    static final int NAME_MAX_LENGTH = 100;
    static final int COURSE_TITLE_MAX_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false, updatable = false)
    private Enrollment enrollment;

    /** VARCHAR(19) for an 18-character format (DDD kept, docs/ai/03 "DDD inconsistencies"). */
    @Column(name = "certificate_code", nullable = false, length = CODE_MAX_LENGTH, updatable = false)
    private String certificateCode;

    @Column(name = "student_name", nullable = false, length = NAME_MAX_LENGTH, updatable = false)
    private String studentName;

    @Column(name = "course_title", nullable = false, length = COURSE_TITLE_MAX_LENGTH, updatable = false)
    private String courseTitle;

    @Column(name = "instructor_name", nullable = false, length = NAME_MAX_LENGTH, updatable = false)
    private String instructorName;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    /** BR-39: certificate of {@code enrollment} with the names as they are now (BR-40 snapshot). */
    public static Certificate issue(Enrollment enrollment, String code, String studentName, String courseTitle,
                                    String instructorName, Instant now) {
        Certificate certificate = new Certificate();
        certificate.enrollment = Objects.requireNonNull(enrollment, "enrollment");
        certificate.certificateCode = requireCode(code);
        certificate.studentName = requireSnapshot(studentName, "studentName", NAME_MAX_LENGTH);
        certificate.courseTitle = requireSnapshot(courseTitle, "courseTitle", COURSE_TITLE_MAX_LENGTH);
        certificate.instructorName = requireSnapshot(instructorName, "instructorName", NAME_MAX_LENGTH);
        certificate.issuedAt = Objects.requireNonNull(now, "now");
        return certificate;
    }

    public static boolean isValidCode(String code) {
        return code != null && CODE.matcher(code).matches();
    }

    private static String requireCode(String code) {
        if (!isValidCode(code)) {
            throw new IllegalArgumentException("certificate code must match EDU-XXXX-XXXX-XXXX (ck_certificates_code)");
        }
        return code;
    }

    private static String requireSnapshot(String value, String name, int maxLength) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > maxLength) {
            throw new IllegalArgumentException(name + " must be 1.." + maxLength + " characters (BR-40 snapshot)");
        }
        return trimmed;
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || JpaEntities.effectiveClass(this) != JpaEntities.effectiveClass(o)) {
            return false;
        }
        Long id = getId();
        return id != null && id.equals(((Certificate) o).getId());
    }

    @Override
    public final int hashCode() {
        return JpaEntities.effectiveClass(this).hashCode();
    }

    @Override
    public String toString() {
        return "Certificate[id=" + getId() + ", code=" + certificateCode + "]";
    }
}
