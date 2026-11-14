package com.edumy.learning.repository;

import java.time.Instant;
import java.util.Optional;

import com.edumy.learning.entity.Certificate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code certificates}. The public verification page reads through the catalog read model (ADR-003,
 * {@code CertificateLookupRepository}); {@link #findByCertificateCode} serves the learning side.
 */
public interface CertificateRepository extends JpaRepository<Certificate, Long> {

    /** BR-39: issued once per enrollment (pre-check; uq_certificates_enrollment still decides). */
    Optional<Certificate> findByEnrollmentId(Long enrollmentId);

    /** Lookup by the public code (D-47 verification). */
    Optional<Certificate> findByCertificateCode(String certificateCode);

    /** S20 {@code GET /me/certificates/{code}/pdf}: the certificate only if it belongs to the student (NFR-01). */
    Optional<Certificate> findByCertificateCodeAndEnrollmentStudentId(String certificateCode, Long studentId);

    /** Code generation: pre-check (uq_certificates_code still decides). */
    boolean existsByCertificateCode(String certificateCode);

    /** UC-S20 {@code GET /me/certificates}: certificates of a student, newest first. Pass an unsorted pageable. */
    @Query(value = """
            select c.id as id, e.id as enrollmentId, e.courseId as courseId, c.certificateCode as certificateCode,
                   c.studentName as studentName, c.courseTitle as courseTitle, c.instructorName as instructorName,
                   c.issuedAt as issuedAt
            from Certificate c join c.enrollment e
            where e.studentId = :studentId
            order by c.issuedAt desc, c.id desc
            """,
            countQuery = "select count(c) from Certificate c where c.enrollment.studentId = :studentId")
    Page<StudentCertificateView> findByStudent(@Param("studentId") Long studentId, Pageable pageable);

    /** UC-S20 {@code GET /me/certificates/{code}/pdf}: the snapshot row of that code, only if it is the student's (one statement). */
    @Query("""
            select c.id as id, e.id as enrollmentId, e.courseId as courseId, c.certificateCode as certificateCode,
                   c.studentName as studentName, c.courseTitle as courseTitle, c.instructorName as instructorName,
                   c.issuedAt as issuedAt
            from Certificate c join c.enrollment e
            where c.certificateCode = :code and e.studentId = :studentId
            """)
    Optional<StudentCertificateView> findViewByCodeAndStudent(@Param("code") String code,
                                                              @Param("studentId") Long studentId);

    /** Row of "my certificates" (UC-S20). */
    interface StudentCertificateView {
        Long getId();

        Long getEnrollmentId();

        Long getCourseId();

        String getCertificateCode();

        String getStudentName();

        String getCourseTitle();

        String getInstructorName();

        Instant getIssuedAt();
    }

    /** UC-S08: certificate code of each of these enrollments (one row per enrollment that has one), one statement. */
    @Query("select c.enrollment.id as enrollmentId, c.certificateCode as certificateCode from Certificate c "
            + "where c.enrollment.id in :enrollmentIds")
    java.util.List<EnrollmentCertificateView> findByEnrollmentIdIn(
            @Param("enrollmentIds") java.util.Collection<Long> enrollmentIds);

    interface EnrollmentCertificateView {
        Long getEnrollmentId();

        String getCertificateCode();
    }
}
