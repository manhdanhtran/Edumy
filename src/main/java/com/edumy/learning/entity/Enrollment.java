package com.edumy.learning.entity;

import java.time.Instant;
import java.util.Objects;

import com.edumy.shared.jpa.JpaEntities;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * {@code enrollments} (EDU-DDD-001 §5.15): lifetime access of a student to a course (BR-17, D-31). No status and no
 * transition: a row is written once and never changed or deleted (BR-28). A free enrollment has no order, a purchase
 * points to its paid order (ck_enrollments_source_order, BR-14). One row per student and course
 * (uq_enrollments_student_course); the duplicate-safe insert of T2 is
 * {@code EnrollmentRepository.insertPurchaseIfAbsent} (docs/ai/03 §3.2). BR-09 (free course), BR-14 (paid order of
 * this student with a line for the course) and BR-32 (never one's own course) are service concerns.
 */
@Entity
@Table(name = "enrollments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Enrollment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Student (identity): plain id. */
    @Column(name = "student_id", nullable = false, updatable = false)
    private Long studentId;

    /** Course (authoring): plain id. Access stays when the course is hidden later (BR-31). */
    @Column(name = "course_id", nullable = false, updatable = false)
    private Long courseId;

    /** Paid order (commerce) of a purchase; null for a free enrollment. */
    @Column(name = "order_id", updatable = false)
    private Long orderId;

    @Column(name = "enroll_source", nullable = false, length = 20, updatable = false)
    private EnrollmentSource enrollSource;

    @Column(name = "enrolled_at", nullable = false, updatable = false)
    private Instant enrolledAt;

    /** UC-S15: free enrollment (BR-09), no order. */
    public static Enrollment free(Long studentId, Long courseId, Instant now) {
        return create(studentId, courseId, null, EnrollmentSource.FREE, now);
    }

    /** T2 (UC-S05 step 7) / UC-A18: enrollment granted by the paid order {@code orderId} (BR-14). */
    public static Enrollment purchase(Long studentId, Long courseId, Long orderId, Instant now) {
        return create(studentId, courseId, Objects.requireNonNull(orderId, "orderId"), EnrollmentSource.PURCHASE, now);
    }

    private static Enrollment create(Long studentId, Long courseId, Long orderId, EnrollmentSource source,
                                     Instant now) {
        Enrollment enrollment = new Enrollment();
        enrollment.studentId = Objects.requireNonNull(studentId, "studentId");
        enrollment.courseId = Objects.requireNonNull(courseId, "courseId");
        enrollment.orderId = orderId;
        enrollment.enrollSource = source;
        enrollment.enrolledAt = Objects.requireNonNull(now, "now");
        return enrollment;
    }

    public boolean isOwnedBy(Long userId) {
        return studentId.equals(userId);
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
        return id != null && id.equals(((Enrollment) o).getId());
    }

    @Override
    public final int hashCode() {
        return JpaEntities.effectiveClass(this).hashCode();
    }

    @Override
    public String toString() {
        return "Enrollment[id=" + getId() + ", studentId=" + studentId + ", courseId=" + courseId + ", source="
                + enrollSource + "]";
    }
}
