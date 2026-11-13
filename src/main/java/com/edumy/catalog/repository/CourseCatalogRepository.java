package com.edumy.catalog.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.edumy.catalog.dto.response.CourseCardResponse;
import com.edumy.catalog.dto.response.CourseDetailResponse;
import com.edumy.catalog.dto.response.CourseDetailResponse.Chapter;
import com.edumy.catalog.dto.response.CourseDetailResponse.Instructor;
import com.edumy.catalog.dto.response.CourseDetailResponse.Lesson;
import com.edumy.catalog.dto.response.LessonPreviewResponse;
import com.edumy.catalog.dto.response.ReviewItemResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Catalog read model of courses (ADR-003, docs/ai/01 §1.12): hand-written SELECTs over the tables of authoring
 * (courses, categories, course_co_instructors, chapters, lessons), identity (users, user_roles, roles), learning
 * (enrollments, course_reviews) and commerce (carts, cart_items), mapped straight into response records. SELECT only.
 * <p>Visibility in SQL: only {@code published} courses are listed (D-08); a course page is also found when
 * {@code hidden} (the service decides who may open it, UC-G04); ratings and reviews count only {@code visible} reviews.
 * Paged queries return their total with {@code COUNT(*) OVER ()} in the same statement, so a page is one statement
 * whatever its size; a separate count runs only for an empty page past the first one.
 */
@Repository
public class CourseCatalogRepository {

    /** UC-G03 sort orders (D-09); each ends with a unique tie-breaker so paging is stable. */
    public enum CourseSort {
        NEWEST("c.published_at DESC, c.id DESC"),
        // average shown (one decimal) first, courses without a visible review last (NULL sorts last in DESC)
        RATING("rt.avg_rating DESC, rating_count DESC, c.published_at DESC, c.id DESC"),
        PRICE_ASC("c.price ASC, c.published_at DESC, c.id DESC"),
        PRICE_DESC("c.price DESC, c.published_at DESC, c.id DESC");

        private final String orderBy;

        CourseSort(String orderBy) {
            this.orderBy = orderBy;
        }
    }

    /** UC-G03 price filter: free = price 0, paid = price > 0 (isFree is derived, never stored). */
    public enum PriceFilter {
        FREE, PAID
    }

    /**
     * Filters of the course list. {@code keyword}: already normalized with {@code VietnameseNormalizer} (it is matched
     * against {@code title_normalized}), {@code null} = no keyword; {@code categoryId} / {@code price} {@code null} =
     * no filter.
     */
    public record CourseCriteria(String keyword, Long categoryId, PriceFilter price) {
    }

    /** A course page found by slug: id and whether it is {@code hidden} (otherwise {@code published}). */
    public record CourseRef(Long id, boolean hidden) {
    }

    /**
     * How a signed-in user relates to a course: {@code owned} = has an enrollment; {@code inCart} = in the user's cart;
     * {@code instructor} = main or accepted co-instructor holding an active instructor role ("course instructor",
     * SRS §1.4, BR-26); {@code admin} = holds the active admin role.
     */
    public record ViewerRelation(boolean owned, boolean inCart, boolean instructor, boolean admin) {
    }

    private static final String RATINGS = """
            LEFT JOIN (SELECT e.course_id, ROUND(AVG(r.rating), 1) AS avg_rating, COUNT(*) AS rating_count
                       FROM course_reviews r
                       JOIN enrollments e ON e.id = r.enrollment_id
                       WHERE r.visibility_status = 'visible'
                       GROUP BY e.course_id) rt ON rt.course_id = c.id
            """;

    private static final String CARD_COLUMNS = """
            c.id, c.slug, c.title, c.thumbnail_url, c.price, c.category_id, cat.name AS category_name,
            u.id AS instructor_id, u.full_name AS instructor_name, rt.avg_rating,
            COALESCE(rt.rating_count, 0) AS rating_count, c.published_at
            """;

    private static final String CARD_FROM = """
            FROM courses c
            JOIN categories cat ON cat.id = c.category_id
            JOIN users u ON u.id = c.main_instructor_id
            """ + RATINGS;

    private static final String ACTIVE_INSTRUCTOR_ROLE = """
            EXISTS (SELECT 1 FROM user_roles ur JOIN roles ro ON ro.id = ur.role_id
                    WHERE ur.user_id = %s AND ro.code = 'instructor' AND ur.is_active)""";

    /** The LIKE escape character used for user keywords (independent of the NO_BACKSLASH_ESCAPES SQL mode). */
    private static final char LIKE_ESCAPE = '!';

    private final JdbcClient jdbc;

    public CourseCatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------------------------------------------- list

    /** UC-G01–G03: one page of published course cards matching {@code criteria}, in {@code sort} order. */
    public Page<CourseCardResponse> search(CourseCriteria criteria, CourseSort sort, Pageable pageable) {
        StringBuilder where = new StringBuilder(" WHERE c.status = 'published'");
        Map<String, Object> params = new LinkedHashMap<>();
        if (criteria.keyword() != null) {
            where.append(" AND c.title_normalized LIKE :pattern ESCAPE '").append(LIKE_ESCAPE).append('\'');
            params.put("pattern", likeContains(criteria.keyword()));
        }
        if (criteria.categoryId() != null) {
            where.append(" AND c.category_id = :categoryId");
            params.put("categoryId", criteria.categoryId());
        }
        if (criteria.price() == PriceFilter.FREE) {
            where.append(" AND c.price = 0");
        } else if (criteria.price() == PriceFilter.PAID) {
            where.append(" AND c.price > 0");
        }

        String sql = "SELECT " + CARD_COLUMNS + ", COUNT(*) OVER () AS total_rows " + CARD_FROM + where
                + " ORDER BY " + sort.orderBy + " LIMIT :limit OFFSET :offset";
        List<Counted<CourseCardResponse>> rows = jdbc.sql(sql)
                .params(params)
                .param("limit", pageable.getPageSize())
                .param("offset", pageable.getOffset())
                .query((rs, n) -> new Counted<>(card(rs), rs.getLong("total_rows")))
                .list();
        long total = total(rows, pageable,
                () -> jdbc.sql("SELECT COUNT(*) FROM courses c" + where).params(params).query(Long.class).single());
        return new PageImpl<>(rows.stream().map(Counted::item).toList(), pageable, total);
    }

    /** UC-G02 "no result → newest courses": the {@code limit} most recently published courses. */
    public List<CourseCardResponse> newest(int limit) {
        return jdbc.sql("SELECT " + CARD_COLUMNS + CARD_FROM + " WHERE c.status = 'published' ORDER BY "
                        + CourseSort.NEWEST.orderBy + " LIMIT :limit")
                .param("limit", limit)
                .query((rs, n) -> card(rs))
                .list();
    }

    // -------------------------------------------------------------------------------------------------- detail

    /**
     * UC-G04: the course page by slug without the viewer context ({@code viewer = null}), when the course is
     * {@code published} or {@code hidden}; three statements (course, co-instructors, outline).
     */
    public Optional<CourseDetailResponse> findDetail(String slug) {
        Optional<Header> header = jdbc.sql("SELECT " + CARD_COLUMNS + """
                        , c.description, c.status, c.updated_at
                        """ + CARD_FROM + " WHERE c.slug = :slug AND c.status IN ('published', 'hidden')")
                .param("slug", slug)
                .query((rs, n) -> new Header(card(rs), rs.getLong("instructor_id"), rs.getString("description"),
                        rs.getString("status"), instant(rs, "updated_at")))
                .optional();
        return header.map(h -> {
            List<Instructor> coInstructors = coInstructors(h.card().id());
            List<Chapter> chapters = outline(h.card().id());
            int totalLessons = chapters.stream().mapToInt(ch -> ch.lessons().size()).sum();
            long totalSeconds = chapters.stream().flatMap(ch -> ch.lessons().stream())
                    .mapToLong(l -> l.durationSeconds() == null ? 0 : l.durationSeconds()).sum();
            CourseCardResponse card = h.card();
            return new CourseDetailResponse(card.id(), card.slug(), card.title(), h.description(), card.thumbnailUrl(),
                    card.price(), card.isFree(), h.status(), card.categoryId(), card.categoryName(),
                    new Instructor(h.mainInstructorId(), card.instructorName()), coInstructors, card.averageRating(),
                    card.ratingCount(), totalLessons, totalSeconds, card.publishedAt(), h.updatedAt(), chapters, null);
        });
    }

    /**
     * Accepted co-instructors still holding an active instructor role ("course instructor", SRS §1.4), in the order
     * they accepted.
     */
    private List<Instructor> coInstructors(Long courseId) {
        return jdbc.sql("""
                        SELECT u.id, u.full_name
                        FROM course_co_instructors cci
                        JOIN users u ON u.id = cci.instructor_id
                        WHERE cci.course_id = :courseId AND cci.invitation_status = 'accepted' AND\s"""
                        + ACTIVE_INSTRUCTOR_ROLE.formatted("u.id") + """

                        ORDER BY cci.responded_at, cci.id""")
                .param("courseId", courseId)
                .query((rs, n) -> new Instructor(rs.getLong("id"), rs.getString("full_name")))
                .list();
    }

    /** Chapters → lessons in their order, one statement; lesson content is never selected. */
    private List<Chapter> outline(Long courseId) {
        return jdbc.sql("""
                        SELECT ch.id AS chapter_id, ch.title AS chapter_title,
                               l.id AS lesson_id, l.title AS lesson_title, l.content_type, l.duration_seconds,
                               l.is_preview
                        FROM chapters ch
                        LEFT JOIN lessons l ON l.chapter_id = ch.id
                        WHERE ch.course_id = :courseId
                        ORDER BY ch.sort_order, ch.id, l.sort_order, l.id""")
                .param("courseId", courseId)
                .query(rs -> {
                    Map<Long, String> titles = new LinkedHashMap<>();
                    Map<Long, List<Lesson>> lessons = new LinkedHashMap<>();
                    while (rs.next()) {
                        long chapterId = rs.getLong("chapter_id");
                        titles.putIfAbsent(chapterId, rs.getString("chapter_title"));
                        List<Lesson> ofChapter = lessons.computeIfAbsent(chapterId, id -> new ArrayList<>());
                        long lessonId = rs.getLong("lesson_id");
                        if (!rs.wasNull()) {
                            ofChapter.add(new Lesson(lessonId, rs.getString("lesson_title"),
                                    rs.getString("content_type"), rs.getObject("duration_seconds", Integer.class),
                                    rs.getBoolean("is_preview")));
                        }
                    }
                    List<Chapter> chapters = new ArrayList<>();
                    titles.forEach((id, title) -> chapters.add(new Chapter(id, title, lessons.get(id))));
                    return chapters;
                });
    }

    // ------------------------------------------------------------------------------------------ viewer, reviews

    /** The course behind a page slug when it is {@code published} or {@code hidden}. */
    public Optional<CourseRef> findCourseRef(String slug) {
        return jdbc.sql("SELECT id, status FROM courses WHERE slug = :slug AND status IN ('published', 'hidden')")
                .param("slug", slug)
                .query((rs, n) -> new CourseRef(rs.getLong("id"), "hidden".equals(rs.getString("status"))))
                .optional();
    }

    /** D-12: how user {@code userId} relates to course {@code courseId}, one statement. */
    public ViewerRelation relation(Long courseId, Long userId) {
        return jdbc.sql("""
                        SELECT
                          EXISTS (SELECT 1 FROM enrollments e
                                  WHERE e.student_id = :userId AND e.course_id = :courseId) AS owned,
                          EXISTS (SELECT 1 FROM cart_items ci JOIN carts ca ON ca.id = ci.cart_id
                                  WHERE ca.student_id = :userId AND ci.course_id = :courseId) AS in_cart,
                          (EXISTS (SELECT 1 FROM courses c WHERE c.id = :courseId AND c.main_instructor_id = :userId)
                           OR EXISTS (SELECT 1 FROM course_co_instructors cci
                                      WHERE cci.course_id = :courseId AND cci.instructor_id = :userId
                                        AND cci.invitation_status = 'accepted'))
                          AND\s""" + ACTIVE_INSTRUCTOR_ROLE.formatted(":userId") + """
                         AS instructor,
                          EXISTS (SELECT 1 FROM user_roles ur JOIN roles ro ON ro.id = ur.role_id
                                  WHERE ur.user_id = :userId AND ro.code = 'admin' AND ur.is_active) AS admin""")
                .param("courseId", courseId)
                .param("userId", userId)
                .query((rs, n) -> new ViewerRelation(rs.getBoolean("owned"), rs.getBoolean("in_cart"),
                        rs.getBoolean("instructor"), rs.getBoolean("admin")))
                .single();
    }

    /** UC-G04 step 3: the visible reviews of a course, newest first, with the reviewer's name. */
    public Page<ReviewItemResponse> visibleReviews(Long courseId, Pageable pageable) {
        String from = """
                 FROM course_reviews r
                JOIN enrollments e ON e.id = r.enrollment_id
                JOIN users u ON u.id = e.student_id
                WHERE e.course_id = :courseId AND r.visibility_status = 'visible'""";
        List<Counted<ReviewItemResponse>> rows = jdbc.sql("""
                        SELECT r.id, r.rating, r.comment, u.full_name, r.created_at, r.updated_at,
                               COUNT(*) OVER () AS total_rows""" + from + """

                        ORDER BY r.created_at DESC, r.id DESC
                        LIMIT :limit OFFSET :offset""")
                .param("courseId", courseId)
                .param("limit", pageable.getPageSize())
                .param("offset", pageable.getOffset())
                .query((rs, n) -> new Counted<>(new ReviewItemResponse(rs.getLong("id"), rs.getInt("rating"),
                        rs.getString("comment"), rs.getString("full_name"), instant(rs, "created_at"),
                        instant(rs, "updated_at")), rs.getLong("total_rows")))
                .list();
        long total = total(rows, pageable, () -> jdbc.sql("SELECT COUNT(*)" + from)
                .param("courseId", courseId).query(Long.class).single());
        return new PageImpl<>(rows.stream().map(Counted::item).toList(), pageable, total);
    }

    // -------------------------------------------------------------------------------------------------- preview

    /** UC-G11: lesson {@code lessonId} when it is a preview lesson of the published course {@code slug}. */
    public Optional<LessonPreviewResponse> findPreview(String slug, Long lessonId) {
        return jdbc.sql("""
                        SELECT l.id, c.slug, c.title AS course_title, ch.title AS chapter_title, l.title,
                               l.content_type, l.video_url, l.article_content, l.duration_seconds
                        FROM lessons l
                        JOIN chapters ch ON ch.id = l.chapter_id
                        JOIN courses c ON c.id = ch.course_id
                        WHERE l.id = :lessonId AND c.slug = :slug AND c.status = 'published' AND l.is_preview""")
                .param("lessonId", lessonId)
                .param("slug", slug)
                .query((rs, n) -> new LessonPreviewResponse(rs.getLong("id"), rs.getString("slug"),
                        rs.getString("course_title"), rs.getString("chapter_title"), rs.getString("title"),
                        rs.getString("content_type"), rs.getString("video_url"), rs.getString("article_content"),
                        rs.getObject("duration_seconds", Integer.class)))
                .optional();
    }

    // -------------------------------------------------------------------------------------------------- helpers

    /** {@code %keyword%} with the LIKE wildcards of the user's keyword escaped (a literal {@code %} or {@code _}). */
    static String likeContains(String keyword) {
        String e = String.valueOf(LIKE_ESCAPE);
        String escaped = keyword.replace(e, e + e).replace("%", e + "%").replace("_", e + "_");
        return "%" + escaped + "%";
    }

    private static CourseCardResponse card(ResultSet rs) throws SQLException {
        long price = rs.getLong("price");
        BigDecimal average = rs.getBigDecimal("avg_rating");
        return new CourseCardResponse(rs.getLong("id"), rs.getString("slug"), rs.getString("title"),
                rs.getString("thumbnail_url"), price, price == 0, rs.getLong("category_id"),
                rs.getString("category_name"), rs.getString("instructor_name"),
                average == null ? null : average.doubleValue(), rs.getLong("rating_count"),
                instant(rs, "published_at"));
    }

    /** DATETIME(6) columns hold UTC (docs/ai/03 §3.1). */
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    /** Total of a paged query: from the window count when the page has rows, else 0 (first page) or a COUNT. */
    private static long total(List<? extends Counted<?>> rows, Pageable pageable, CountQuery count) {
        if (!rows.isEmpty()) {
            return rows.getFirst().total();
        }
        return pageable.getOffset() == 0 ? 0 : count.run();
    }

    @FunctionalInterface
    private interface CountQuery {
        long run();
    }

    private record Counted<T>(T item, long total) {
    }

    private record Header(CourseCardResponse card, long mainInstructorId, String description, String status,
                          Instant updatedAt) {
    }
}
