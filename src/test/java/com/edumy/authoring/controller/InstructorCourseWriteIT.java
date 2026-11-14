package com.edumy.authoring.controller;

import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import com.edumy.authoring.api.CourseContentChanged;
import com.edumy.authoring.repository.CourseRepository;
import com.edumy.shared.error.ConstraintViolationTranslator;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.CourseFixtures;
import com.edumy.support.IdorAssertions;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.SqlStatementCounter;
import com.edumy.support.TestUsers;
import com.edumy.support.TestUsers.TestUser;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Course information writes of the instructor (UC-I01 create, UC-I03 update, UC-I04 delete) through the real filter
 * chain and MySQL: BR-26 (active instructor role in the DB), BR-06 (main instructor only, others 404), BR-07 price,
 * BR-33 (pending_review read-only), D-14 ({@link CourseContentChanged} for published / hidden courses), BR-19 (no
 * delete once published — service pre-check and the V5 trigger), unique slug with numeric suffix, IDOR (NFR-01).
 */
@MySqlSpringBootTest
@RecordApplicationEvents
class InstructorCourseWriteIT {

    static final String COURSES = "/api/v1/instructor/courses";

    @Autowired
    MockMvc mvc;
    @Autowired
    TestUsers users;
    @Autowired
    JwtTestTokens tokens;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ApplicationEvents events;
    @Autowired
    SqlStatementCounter sql;
    @Autowired
    CourseRepository courseRepository;
    @Autowired
    ConstraintViolationTranslator translator;
    @Autowired
    TransactionTemplate transactions;

    CourseFixtures fixtures;
    Session instructor;
    long category;
    String unique;

    @BeforeEach
    void setUp() {
        fixtures = new CourseFixtures(jdbc);
        instructor = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        category = fixtures.category(true);
        unique = UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void cleanUp() {
        fixtures.deleteAll();
        users.deleteAll();
    }

    // ------------------------------------------------------------------------------------------------ UC-I01 create

    @Test
    void create_draftOfCaller_slugFromTitle_201_UCI01() throws Exception {
        MvcResult result = mvc.perform(json(post(COURSES), instructor, """
                        {"title": "  Lập trình Java cơ bản %s  ", "description": "Mô tả", \
                        "thumbnailUrl": "https://cdn.edumy.vn/a.png", "categoryId": %d, "price": 499000}"""
                        .formatted(unique, category)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Lập trình Java cơ bản " + unique))
                .andExpect(jsonPath("$.slug").value("lap-trinh-java-co-ban-" + unique))
                .andExpect(jsonPath("$.description").value("Mô tả"))
                .andExpect(jsonPath("$.thumbnailUrl").value("https://cdn.edumy.vn/a.png"))
                .andExpect(jsonPath("$.price").value(499000))
                .andExpect(jsonPath("$.isFree").value(false))
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.relation").value("main"))
                .andExpect(jsonPath("$.categoryId").value(category))
                .andExpect(jsonPath("$.categoryActive").value(true))
                .andExpect(jsonPath("$.mainInstructor.id").value(instructor.userId()))
                .andExpect(jsonPath("$.coInstructors").isEmpty())
                .andExpect(jsonPath("$.chapters").isEmpty())
                .andExpect(jsonPath("$.publishedAt").value(nullValue()))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andReturn();
        long id = idOf(result);

        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo(COURSES + "/" + id);
        Map<String, Object> row = jdbc.sql("SELECT main_instructor_id, title_normalized FROM courses WHERE id = ?")
                .param(id).query().singleRow();
        assertThat(((Number) row.get("main_instructor_id")).longValue()).isEqualTo(instructor.userId());
        assertThat(row.get("title_normalized")).isEqualTo("lap trinh java co ban " + unique);
        assertThat(events.stream(CourseContentChanged.class)).isEmpty();
    }

    @Test
    void create_slugTaken_lowestFreeNumericSuffix_UCI01() throws Exception {
        String body = "{\"title\": \"Đồ họa %s\", \"categoryId\": %d, \"price\": 0}".formatted(unique, category);

        assertThat(slugOf(createOk(body))).isEqualTo("do-hoa-" + unique);
        assertThat(slugOf(createOk(body))).isEqualTo("do-hoa-" + unique + "-2");
        assertThat(slugOf(createOk(body))).isEqualTo("do-hoa-" + unique + "-3");
    }

    @Test
    void create_titleWithoutLetterOrDigit_fallbackSlug_UCI01() throws Exception {
        String slug = slugOf(createOk("{\"title\": \"!!! ???\", \"categoryId\": %d, \"price\": 0}".formatted(category)));

        assertThat(slug).matches("khoa-hoc(-[0-9]+)?");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 10_000, 15_000})
    void create_validPrice_201_BR07(long price) throws Exception {
        createOk("{\"title\": \"Giá %d\", \"categoryId\": %d, \"price\": %d}".formatted(price, category, price));
    }

    @ParameterizedTest
    @ValueSource(longs = {9_000, 10_500, -1_000})
    void create_invalidPrice_400ValidationFailed_BR07(long price) throws Exception {
        MvcResult result = expectField(mvc.perform(json(post(COURSES), instructor,
                "{\"title\": \"Giá sai\", \"categoryId\": %d, \"price\": %d}".formatted(category, price))), "price");
        jsonPath("$.errors[0].message")
                .value("Giá phải bằng 0 (miễn phí) hoặc từ 10.000 đ trở lên và là bội số của 1.000 đ").match(result);
    }

    @Test
    void create_missingOrTooLongFields_400() throws Exception {
        expectField(mvc.perform(json(post(COURSES), instructor, "{\"categoryId\": %d, \"price\": 0}"
                .formatted(category))), "title");
        expectField(mvc.perform(json(post(COURSES), instructor, "{\"title\": \"%s\", \"categoryId\": %d, \"price\": 0}"
                .formatted("a".repeat(201), category))), "title");
        expectField(mvc.perform(json(post(COURSES), instructor, "{\"title\": \"T\", \"price\": 0}")), "categoryId");
        expectField(mvc.perform(json(post(COURSES), instructor, "{\"title\": \"T\", \"categoryId\": %d}"
                .formatted(category))), "price");
        expectField(mvc.perform(json(post(COURSES), instructor,
                "{\"title\": \"T\", \"categoryId\": %d, \"price\": 0, \"thumbnailUrl\": \"ftp://x\"}"
                        .formatted(category))), "thumbnailUrl");
    }

    @Test
    void create_inactiveOrUnknownCategory_422CourseCategoryUnavailable_UCI01() throws Exception {
        long inactive = fixtures.category(false);

        expectProblem(mvc.perform(json(post(COURSES), instructor,
                "{\"title\": \"T\", \"categoryId\": %d, \"price\": 0}".formatted(inactive))),
                422, "COURSE_CATEGORY_UNAVAILABLE");
        expectProblem(mvc.perform(json(post(COURSES), instructor,
                "{\"title\": \"T\", \"categoryId\": %d, \"price\": 0}".formatted(Long.MAX_VALUE))),
                422, "COURSE_CATEGORY_UNAVAILABLE");
        assertThat(coursesOf(instructor.userId())).isZero();
    }

    @Test
    void create_revokedInstructorWithStaleJwt_403_BR26() throws Exception {
        TestUser revoked = users.create(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        users.revokeRole(revoked.id(), RoleCode.INSTRUCTOR);
        Session session = tokens.session(revoked); // the JWT still claims instructor

        expectProblem(mvc.perform(json(post(COURSES), session,
                "{\"title\": \"T\", \"categoryId\": %d, \"price\": 0}".formatted(category))), 403, "ACCESS_DENIED");
        assertThat(coursesOf(revoked.id())).isZero();
    }

    @Test
    void create_studentOnly_403_withoutToken_401() throws Exception {
        Session student = tokens.newUserSession(RoleCode.STUDENT);
        String body = "{\"title\": \"T\", \"categoryId\": %d, \"price\": 0}".formatted(category);

        expectProblem(mvc.perform(json(post(COURSES), student, body)), 403, "ACCESS_DENIED");
        expectProblem(mvc.perform(post(COURSES).contentType(MediaType.APPLICATION_JSON).content(body)),
                401, "UNAUTHENTICATED");
    }

    // ------------------------------------------------------------------------------------------------ UC-I03 update

    @Test
    void update_draft_onlySentFieldsChange_slugKept_noEvent_UCI03() throws Exception {
        long id = createCourse("Khóa gốc " + unique, 0);
        String slug = jdbc.sql("SELECT slug FROM courses WHERE id = ?").param(id).query(String.class).single();

        sql.reset();
        mvc.perform(json(patch(COURSES + "/" + id), instructor, "{\"title\": \"  Tiêu đề Mới %s \"}".formatted(unique)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.title").value("Tiêu đề Mới " + unique))
                .andExpect(jsonPath("$.slug").value(slug))
                .andExpect(jsonPath("$.description").value("Mô tả gốc"))
                .andExpect(jsonPath("$.price").value(0))
                .andExpect(jsonPath("$.status").value("draft"));
        assertThat(sql.statements()).as("the course row is read with a row lock")
                .anyMatch(statement -> statement.toLowerCase().contains("for update"));

        assertThat(jdbc.sql("SELECT title_normalized FROM courses WHERE id = ?").param(id).query(String.class).single())
                .isEqualTo("tieu de moi " + unique);
        assertThat(events.stream(CourseContentChanged.class)).isEmpty();
    }

    @Test
    void update_everyField_emptyStringClears_UCI03() throws Exception {
        long id = createCourse("Khóa " + unique, 0);
        long otherCategory = fixtures.category(true);

        mvc.perform(json(patch(COURSES + "/" + id), instructor, """
                        {"description": "", "thumbnailUrl": "http://cdn.edumy.vn/b.png", "categoryId": %d, \
                        "price": 1000000}""".formatted(otherCategory)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value(nullValue()))
                .andExpect(jsonPath("$.thumbnailUrl").value("http://cdn.edumy.vn/b.png"))
                .andExpect(jsonPath("$.categoryId").value(otherCategory))
                .andExpect(jsonPath("$.price").value(1000000))
                .andExpect(jsonPath("$.isFree").value(false));
    }

    @Test
    void update_published_effectiveAtOnce_courseContentChanged_D14() throws Exception {
        long id = createCourse("Công khai " + unique, 0);
        fixtures.setStatus(id, "published", true);
        String slug = jdbc.sql("SELECT slug FROM courses WHERE id = ?").param(id).query(String.class).single();

        mvc.perform(json(patch(COURSES + "/" + id), instructor, "{\"price\": 199000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("published"))
                .andExpect(jsonPath("$.price").value(199000));

        assertThat(events.stream(CourseContentChanged.class)).containsExactly(new CourseContentChanged(id, slug));
    }

    @Test
    void update_hidden_courseContentChanged_D14() throws Exception {
        long id = createCourse("Bị ẩn " + unique, 0);
        fixtures.setStatus(id, "hidden", true);

        mvc.perform(json(patch(COURSES + "/" + id), instructor, "{\"description\": \"Mô tả mới\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("hidden"));

        assertThat(events.stream(CourseContentChanged.class)).extracting(CourseContentChanged::courseId)
                .containsExactly(id);
    }

    @Test
    void update_rejected_staysRejected_noEvent_UCI03() throws Exception {
        long id = createCourse("Bị từ chối " + unique, 0);
        fixtures.setStatus(id, "rejected", false);

        mvc.perform(json(patch(COURSES + "/" + id), instructor, "{\"title\": \"Sửa lại " + unique + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("rejected"))
                .andExpect(jsonPath("$.title").value("Sửa lại " + unique));

        assertThat(events.stream(CourseContentChanged.class)).isEmpty();
    }

    @Test
    void update_pendingReview_409CourseNotEditable_nothingChanges_BR33() throws Exception {
        long id = createCourse("Chờ duyệt " + unique, 0);
        fixtures.markPendingReview(id);

        expectProblem(mvc.perform(json(patch(COURSES + "/" + id), instructor, "{\"title\": \"Không được\"}")),
                409, "COURSE_NOT_EDITABLE");
        expectProblem(mvc.perform(json(patch(COURSES + "/" + id), instructor, "{}")), 409, "COURSE_NOT_EDITABLE");

        assertThat(jdbc.sql("SELECT title FROM courses WHERE id = ?").param(id).query(String.class).single())
                .isEqualTo("Chờ duyệt " + unique);
    }

    @Test
    void update_newCategoryInactive_422_keepingCurrentInactiveCategory_ok_UCI03() throws Exception {
        long id = createCourse("Danh mục " + unique, 0);
        long inactive = fixtures.category(false);

        expectProblem(mvc.perform(json(patch(COURSES + "/" + id), instructor,
                "{\"categoryId\": %d}".formatted(inactive))), 422, "COURSE_CATEGORY_UNAVAILABLE");

        // the current category is deactivated afterwards: the course keeps it and stays editable (D-21)
        jdbc.sql("UPDATE categories SET is_active = FALSE WHERE id = ?").param(category).update();
        mvc.perform(json(patch(COURSES + "/" + id), instructor,
                        "{\"categoryId\": %d, \"title\": \"Vẫn sửa được\"}".formatted(category)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(category))
                .andExpect(jsonPath("$.categoryActive").value(false));
    }

    @Test
    void update_invalidInput_400() throws Exception {
        long id = createCourse("Hợp lệ " + unique, 0);

        expectField(mvc.perform(json(patch(COURSES + "/" + id), instructor, "{\"title\": \"   \"}")), "title");
        expectField(mvc.perform(json(patch(COURSES + "/" + id), instructor, "{\"price\": 9000}")), "price");
        expectField(mvc.perform(json(patch(COURSES + "/" + id), instructor, "{\"price\": 10500}")), "price");
    }

    @Test
    void update_byAcceptedCoInstructor_404_BR06() throws Exception {
        long id = createCourse("Đồng GV " + unique, 0);
        Session co = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        fixtures.coInstructor(id, co.userId(), instructor.userId(), "accepted");

        expectProblem(mvc.perform(json(patch(COURSES + "/" + id), co, "{\"title\": \"Chiếm quyền\"}")),
                404, "RESOURCE_NOT_FOUND");
        expectProblem(mvc.perform(delete(COURSES + "/" + id).header(HttpHeaders.AUTHORIZATION, co.bearer())),
                404, "RESOURCE_NOT_FOUND");
        assertThat(jdbc.sql("SELECT title FROM courses WHERE id = ?").param(id).query(String.class).single())
                .isEqualTo("Đồng GV " + unique);
    }

    @Test
    void updateAndDelete_otherInstructorsCourse_404IdenticalToMissing_NFR01() throws Exception {
        long id = createCourse("Của A " + unique, 0);
        Session other = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);

        IdorAssertions.assertSameNotFound(mvc,
                json(patch(COURSES + "/" + id), other, "{\"title\": \"X\"}"),
                json(patch(COURSES + "/" + Long.MAX_VALUE), other, "{\"title\": \"X\"}"));
        IdorAssertions.assertSameNotFound(mvc,
                delete(COURSES + "/" + id).header(HttpHeaders.AUTHORIZATION, other.bearer()),
                delete(COURSES + "/" + Long.MAX_VALUE).header(HttpHeaders.AUTHORIZATION, other.bearer()));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM courses WHERE id = ?").param(id).query(Long.class).single())
                .isOne();
    }

    @Test
    void updateAndDelete_revokedMainInstructor_403_BR26() throws Exception {
        // inserted directly: no request of this user before the revoke, so the active-roles cache is still cold
        long id = fixtures.draftCourse(instructor.userId(), category);
        users.revokeRole(instructor.userId(), RoleCode.INSTRUCTOR);
        Session stale = tokens.session(instructor.user());

        expectProblem(mvc.perform(json(patch(COURSES + "/" + id), stale, "{\"title\": \"X\"}")), 403, "ACCESS_DENIED");
        expectProblem(mvc.perform(delete(COURSES + "/" + id).header(HttpHeaders.AUTHORIZATION, stale.bearer())),
                403, "ACCESS_DENIED");
    }

    // ------------------------------------------------------------------------------------------------ UC-I04 delete

    @Test
    void delete_draft_204_chaptersLessonsInvitationsGo_UCI04() throws Exception {
        long id = createCourse("Xóa nháp " + unique, 0);
        long chapter = fixtures.chapter(id);
        long lesson = fixtures.lesson(chapter);
        Session co = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        fixtures.coInstructor(id, co.userId(), instructor.userId(), "pending");

        sql.reset();
        mvc.perform(delete(COURSES + "/" + id).header(HttpHeaders.AUTHORIZATION, instructor.bearer()))
                .andExpect(status().isNoContent());
        assertThat(sql.statements()).anyMatch(statement -> statement.toLowerCase().contains("for update"));

        assertThat(count("courses", "id", id)).isZero();
        assertThat(count("chapters", "id", chapter)).isZero();
        assertThat(count("lessons", "id", lesson)).isZero();
        assertThat(count("course_co_instructors", "course_id", id)).isZero();
    }

    @Test
    void delete_rejectedNeverPublished_204_UCI04() throws Exception {
        long id = createCourse("Từ chối " + unique, 0);
        fixtures.setStatus(id, "rejected", false);

        mvc.perform(delete(COURSES + "/" + id).header(HttpHeaders.AUTHORIZATION, instructor.bearer()))
                .andExpect(status().isNoContent());
        assertThat(count("courses", "id", id)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"published", "hidden", "rejected", "draft"})
    void delete_publishedOnce_409CourseDeleteForbidden_BR19(String statusNow) throws Exception {
        long id = createCourse("Đã công khai " + unique, 0);
        fixtures.setStatus(id, statusNow, true); // published_at set, whatever the status now

        expectProblem(mvc.perform(delete(COURSES + "/" + id).header(HttpHeaders.AUTHORIZATION, instructor.bearer())),
                409, "COURSE_DELETE_FORBIDDEN");
        assertThat(count("courses", "id", id)).isOne();
    }

    @Test
    void delete_pendingReview_409CourseNotEditable_BR33() throws Exception {
        long id = createCourse("Chờ duyệt " + unique, 0);
        fixtures.markPendingReview(id);

        expectProblem(mvc.perform(delete(COURSES + "/" + id).header(HttpHeaders.AUTHORIZATION, instructor.bearer())),
                409, "COURSE_NOT_EDITABLE");
        assertThat(count("courses", "id", id)).isOne();
    }

    /** The second line of defence: the V5 trigger refuses the DELETE and the translator maps it to the same code. */
    @Test
    void delete_publishedOnce_bypassingThePreCheck_triggerMapsToCourseDeleteForbidden_BR19() throws Exception {
        long id = createCourse("Trigger " + unique, 0);
        fixtures.setStatus(id, "hidden", true);

        Throwable thrown = catchThrowable(() -> transactions.executeWithoutResult(tx -> {
            courseRepository.deleteById(id);
            courseRepository.flush();
        }));

        assertThat(thrown).isNotNull();
        assertThat(translator.translate(thrown)).get()
                .extracting(ConstraintViolationTranslator.TranslatedViolation::code)
                .isEqualTo(ErrorCode.COURSE_DELETE_FORBIDDEN);
        assertThat(count("courses", "id", id)).isOne();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** A draft course of {@link #instructor} created through the API (description "Mô tả gốc"). */
    private long createCourse(String title, long price) throws Exception {
        return idOf(createOk("{\"title\": \"%s\", \"description\": \"Mô tả gốc\", \"categoryId\": %d, \"price\": %d}"
                .formatted(title, category, price)));
    }

    private MvcResult createOk(String body) throws Exception {
        MvcResult result = mvc.perform(json(post(COURSES), instructor, body))
                .andExpect(status().isCreated())
                .andReturn();
        idOf(result);
        return result;
    }

    private long idOf(MvcResult result) throws Exception {
        long id = ((Number) JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id"))
                .longValue();
        fixtures.trackCourse(id);
        return id;
    }

    private static String slugOf(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.slug");
    }

    private long coursesOf(Long userId) {
        return jdbc.sql("SELECT COUNT(*) FROM courses WHERE main_instructor_id = ?").param(userId)
                .query(Long.class).single();
    }

    private long count(String table, String column, long value) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?").param(value)
                .query(Long.class).single();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Session caller,
                                                      String body) {
        return request.header(HttpHeaders.AUTHORIZATION, caller.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static MvcResult expectField(ResultActions actions, String field) throws Exception {
        MvcResult result = expectProblem(actions, 400, "VALIDATION_FAILED");
        jsonPath("$.errors[*].field", hasItem(field)).match(result);
        return result;
    }
}
