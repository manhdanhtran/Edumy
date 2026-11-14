package com.edumy.authoring.service;

import java.time.Clock;
import java.util.HashSet;
import java.util.Set;

import com.edumy.authoring.api.CourseContentChanged;
import com.edumy.authoring.entity.Category;
import com.edumy.authoring.entity.Course;
import com.edumy.authoring.entity.CourseStatus;
import com.edumy.authoring.repository.CategoryRepository;
import com.edumy.authoring.repository.CourseRepository;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.text.SlugGenerator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Course information writes of the instructor: UC-I01 create, UC-I03 update, UC-I04 delete. Who may call was decided
 * by {@code @courseAccess} before (BR-26 active instructor role in the database; BR-06 main instructor for update and
 * delete, anyone else 404). Updates and deletes lock the course row ({@code SELECT … FOR UPDATE}) so a concurrent
 * submit / approve cannot interleave with the state checks.
 */
@Service
public class CourseService {

    /** Room for a numeric suffix within {@code courses.slug VARCHAR(220)} (the title has ≤200 characters). */
    static final int SLUG_BASE_MAX_LENGTH = 200;
    /** Slug of a title without any letter or digit. */
    static final String FALLBACK_SLUG = "khoa-hoc";

    private final CourseRepository courses;
    private final CategoryRepository categories;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public CourseService(CourseRepository courses, CategoryRepository categories, ApplicationEventPublisher events,
                         Clock clock) {
        this.courses = courses;
        this.categories = categories;
        this.events = events;
        this.clock = clock;
    }

    /**
     * UC-I01: new {@code draft} course whose main instructor is the caller (BR-02). The category must exist and be
     * active (else 422 {@code COURSE_CATEGORY_UNAVAILABLE}); the slug comes from the title and gets the lowest free
     * numeric suffix ({@code -2}, {@code -3}…) when taken ("slug trùng → tự thêm hậu tố"). Two concurrent creates
     * picking the same slug: the second gets 409 {@code DATA_CONFLICT} from {@code uq_courses_slug}. Returns the id.
     */
    @Transactional
    public Long create(Long instructorId, CreateCourseCommand command) {
        Category category = activeCategory(command.categoryId());
        Course course = Course.create(instructorId, category, command.title(), uniqueSlug(command.title()),
                command.description(), command.thumbnailUrl(), command.price(), clock.instant());
        courses.saveAndFlush(course);
        return course.getId();
    }

    /**
     * UC-I03 with PATCH semantics. BR-33: a {@code pending_review} course → 409 {@code COURSE_NOT_EDITABLE} (even for
     * an empty body). A new category must be active; keeping the current one (even if it was deactivated since) is
     * allowed. The slug never changes. D-14: a published or hidden course changes at once without review (a new price
     * applies to later orders only — orders keep their snapshot) and {@link CourseContentChanged} is published for the
     * catalog; a rejected course stays rejected until it is resubmitted.
     */
    @Transactional
    public void update(Long courseId, UpdateCourseCommand command) {
        Course course = courses.findByIdForUpdate(courseId).orElseThrow(BusinessException::notFound);
        course.assertEditable(); // BR-33: pending_review courses are read-only
        if (command.isEmpty()) {
            return;
        }
        Category category = course.getCategory();
        if (command.categoryId() != null && !command.categoryId().equals(category.getId())) {
            category = activeCategory(command.categoryId());
        }
        course.updateInfo(category,
                command.title() != null ? command.title() : course.getTitle(),
                command.description() != null ? command.description() : course.getDescription(),
                command.thumbnailUrl() != null ? command.thumbnailUrl() : course.getThumbnailUrl());
        if (command.price() != null) {
            course.changePrice(command.price());
        }
        courses.flush();
        if (course.getStatus() == CourseStatus.PUBLISHED || course.getStatus() == CourseStatus.HIDDEN) {
            events.publishEvent(new CourseContentChanged(course.getId(), course.getSlug())); // D-14
        }
    }

    /**
     * UC-I04. BR-19: a course published once ({@code published_at} set, whatever its status now) → 409
     * {@code COURSE_DELETE_FORBIDDEN} (the SPA tells the instructor to ask an admin to hide it); the V5 trigger
     * {@code trg_courses_prevent_delete_published_bd} gives the same code if this check were ever bypassed. A
     * {@code pending_review} course → 409 {@code COURSE_NOT_EDITABLE} (BR-33). Chapters, lessons and invitations go
     * with it (ON DELETE CASCADE).
     */
    @Transactional
    public void delete(Long courseId) {
        Course course = courses.findByIdForUpdate(courseId).orElseThrow(BusinessException::notFound);
        if (course.getPublishedAt() != null) {
            throw new BusinessException(ErrorCode.COURSE_DELETE_FORBIDDEN); // BR-19
        }
        course.assertEditable(); // BR-33
        if (!course.isDeletable()) {
            throw new BusinessException(ErrorCode.COURSE_DELETE_FORBIDDEN);
        }
        courses.delete(course);
        courses.flush();
    }

    private Category activeCategory(Long categoryId) {
        return categories.findById(categoryId)
                .filter(Category::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.COURSE_CATEGORY_UNAVAILABLE));
    }

    /** {@code base}, else {@code base-n} with the lowest free n ≥ 2 — one statement, deterministic. */
    private String uniqueSlug(String title) {
        String slugged = SlugGenerator.slugify(title, SLUG_BASE_MAX_LENGTH);
        String base = slugged.isEmpty() ? FALLBACK_SLUG : slugged;
        Set<String> taken = new HashSet<>(courses.findSlugsWithBase(base));
        if (!taken.contains(base)) {
            return base;
        }
        int suffix = 2;
        while (taken.contains(base + "-" + suffix)) {
            suffix++;
        }
        return base + "-" + suffix;
    }
}
