package com.edumy.learning.controller;

import static com.edumy.support.IdorAssertions.assertSameNotFound;
import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edumy.shared.security.RoleCode;
import com.edumy.support.MySqlSeedSpringBootTest;
import com.edumy.support.SeedUserTokens;
import com.edumy.support.SqlStatementCounter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * UC-S18 course Q&amp;A, thread and UC-I22 instructor list on the dev seed (read-only). Seed: questions 1, 2 (course 1,
 * asker 6, answered by instructors 2 and 3), 3 (course 3, asker 9, unanswered), 4 (course 1, asker 14, hidden),
 * 5 (course 5, asker 7, deleted), 6 (course 9, asker 9, instructor answer 6 visible, student answer 7 hidden).
 */
@MySqlSeedSpringBootTest
class QuestionReadsIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    SeedUserTokens tokens;
    @Autowired
    SqlStatementCounter sql;

    private MockHttpServletRequestBuilder req(long userId, String path) {
        return get(path).header(HttpHeaders.AUTHORIZATION, tokens.bearer(userId));
    }

    private ResultActions as(long userId, String path) throws Exception {
        return mvc.perform(req(userId, path));
    }

    // ---- course list

    @Test
    void courseList_visibleQuestionsNewestFirst_withDerivedFlags_UCS18() throws Exception {
        as(6, "/api/v1/courses/1/questions")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].id", contains(2, 1)))
                .andExpect(jsonPath("$.content[0].courseId").value(1))
                .andExpect(jsonPath("$.content[0].lessonId").value(8))
                .andExpect(jsonPath("$.content[0].title").value("Lỗi Communications link failure khi kết nối MySQL"))
                .andExpect(jsonPath("$.content[0].resolved").value(false))
                .andExpect(jsonPath("$.content[0].status").value("visible"))
                .andExpect(jsonPath("$.content[0].asker.id").value(6))
                .andExpect(jsonPath("$.content[0].asker.fullName").value("Đỗ Thị Hương"))
                .andExpect(jsonPath("$.content[0].answerCount").value(2)) // answer 5 is deleted
                .andExpect(jsonPath("$.content[0].instructorReplied").value(true))
                .andExpect(jsonPath("$.content[0].createdAt").value("2026-10-28T21:00:00Z"))
                .andExpect(jsonPath("$.content[1].resolved").value(true))
                .andExpect(jsonPath("$.content[1].answerCount").value(2))
                .andExpect(jsonPath("$.content[1].lessonId").value(5));
    }

    @Test
    void courseList_unansweredQuestion_hasNoInstructorReply() throws Exception {
        as(6, "/api/v1/courses/3/questions")
                .andExpect(jsonPath("$.content[*].id", contains(3)))
                .andExpect(jsonPath("$.content[0].answerCount").value(0))
                .andExpect(jsonPath("$.content[0].instructorReplied").value(false))
                .andExpect(jsonPath("$.content[0].lessonId").value(nullValue()));
    }

    @Test
    void courseList_hiddenQuestion_onlyForItsAskerAndAdmin_BR41() throws Exception {
        as(6, "/api/v1/courses/1/questions").andExpect(jsonPath("$.content[*].id", contains(2, 1)));
        as(2, "/api/v1/courses/1/questions").andExpect(jsonPath("$.content[*].id", contains(2, 1)));
        as(14, "/api/v1/courses/1/questions")
                .andExpect(jsonPath("$.content[*].id", contains(2, 4, 1)))
                .andExpect(jsonPath("$.content[1].status").value("hidden"));
        as(SeedUserTokens.ADMIN, "/api/v1/courses/1/questions").andExpect(jsonPath("$.content[*].id", contains(2, 4, 1)));
    }

    @Test
    void courseList_deletedQuestion_listedForNobody() throws Exception {
        as(7, "/api/v1/courses/5/questions").andExpect(jsonPath("$.content", empty()));
        as(SeedUserTokens.ADMIN, "/api/v1/courses/5/questions").andExpect(jsonPath("$.content", empty()));
        as(3, "/api/v1/courses/5/questions").andExpect(jsonPath("$.content", empty()));
    }

    @Test
    void courseList_filters_lessonMineUnansweredKeyword_UCS18() throws Exception {
        as(6, "/api/v1/courses/1/questions?lessonId=5").andExpect(jsonPath("$.content[*].id", contains(1)));
        as(14, "/api/v1/courses/1/questions?mine=true").andExpect(jsonPath("$.content[*].id", contains(4)));
        as(4, "/api/v1/courses/1/questions?mine=true").andExpect(jsonPath("$.content", empty()));
        as(6, "/api/v1/courses/1/questions?unanswered=true").andExpect(jsonPath("$.content", empty()));
        as(6, "/api/v1/courses/3/questions?unanswered=true").andExpect(jsonPath("$.content[*].id", contains(3)));
        as(6, "/api/v1/courses/3/questions?q=react 19").andExpect(jsonPath("$.content[*].id", contains(3)));
        as(6, "/api/v1/courses/3/questions?q=vue").andExpect(jsonPath("$.content", empty()));
        as(6, "/api/v1/courses/3/questions?q=100%25").andExpect(jsonPath("$.content", empty())); // % is literal
        // P10 MINOR-4: a bare wildcard never lists every question (course 3 has visible questions)
        as(6, "/api/v1/courses/3/questions").andExpect(jsonPath("$.content", org.hamcrest.Matchers.not(empty())));
        as(6, "/api/v1/courses/3/questions?q=%25").andExpect(jsonPath("$.content", empty()));
        as(6, "/api/v1/courses/3/questions?q=_").andExpect(jsonPath("$.content", empty()));
    }

    @Test
    void courseList_idor_notEnrolled_404IdenticalToUnknown() throws Exception {
        assertSameNotFound(mvc, req(8, "/api/v1/courses/1/questions"),
                req(8, "/api/v1/courses/" + Long.MAX_VALUE + "/questions"));
        as(SeedUserTokens.ADMIN, "/api/v1/courses/12/questions").andExpect(status().isOk());
        as(2, "/api/v1/courses/1/questions").andExpect(status().isOk()); // instructor of the course
        as(2, "/api/v1/courses/5/questions").andExpect(status().isNotFound());
    }

    @Test
    void courseList_withoutToken_401() throws Exception {
        expectProblem(mvc.perform(get("/api/v1/courses/1/questions")), 401, "UNAUTHENTICATED");
    }

    @Test
    void courseList_statementCount_constantAcrossPageSizes() throws Exception {
        as(6, "/api/v1/courses/1/questions").andExpect(status().isOk()); // warm-up

        sql.reset();
        as(6, "/api/v1/courses/1/questions?size=1").andExpect(jsonPath("$.content", hasSize(1)));
        int small = sql.count();
        assertThat(small).as(sql.statements().toString()).isEqualTo(4); // enrolled + page + count + names

        sql.reset();
        as(6, "/api/v1/courses/1/questions?size=2").andExpect(jsonPath("$.content", hasSize(2)));
        assertThat(sql.count()).as(sql.statements().toString()).isEqualTo(4);
    }

    // ---- thread

    @Test
    void thread_questionWithAnswers_deletedAnswerLeftOut_UCS18() throws Exception {
        as(6, "/api/v1/questions/2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.courseId").value(1))
                .andExpect(jsonPath("$.lessonId").value(8))
                .andExpect(jsonPath("$.content").value(
                        "Em chạy ứng dụng thì gặp lỗi Communications link failure, MySQL đã bật bằng Docker. Em cần kiểm tra gì ạ?"))
                .andExpect(jsonPath("$.status").value("visible"))
                .andExpect(jsonPath("$.asker.fullName").value("Đỗ Thị Hương"))
                .andExpect(jsonPath("$.instructorReplied").value(true))
                .andExpect(jsonPath("$.answers[*].id", contains(3, 4)))
                .andExpect(jsonPath("$.answers[0].author.id").value(3))
                .andExpect(jsonPath("$.answers[0].author.fullName").value("Lê Thị Mai"))
                .andExpect(jsonPath("$.answers[0].authorType").value("instructor"))
                .andExpect(jsonPath("$.answers[1].authorType").value("student"))
                .andExpect(jsonPath("$.answers[1].author.fullName").value("Đỗ Thị Hương"));
    }

    @Test
    void thread_unanswered_hasNoAnswers() throws Exception {
        as(9, "/api/v1/questions/3")
                .andExpect(jsonPath("$.answers", empty()))
                .andExpect(jsonPath("$.instructorReplied").value(false));
    }

    @Test
    void thread_hiddenAnswer_onlyForItsAuthorAndAdmin() throws Exception {
        as(9, "/api/v1/questions/6").andExpect(jsonPath("$.answers[*].id", contains(6, 7)))
                .andExpect(jsonPath("$.answers[1].status").value("hidden"));
        as(SeedUserTokens.ADMIN, "/api/v1/questions/6").andExpect(jsonPath("$.answers[*].id", contains(6, 7)));
        as(15, "/api/v1/questions/6").andExpect(jsonPath("$.answers[*].id", contains(6)));
        as(4, "/api/v1/questions/6").andExpect(jsonPath("$.answers[*].id", contains(6)));
    }

    @Test
    void thread_hiddenQuestion_askerAndAdminOnly_others404() throws Exception {
        as(14, "/api/v1/questions/4").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("hidden"));
        as(SeedUserTokens.ADMIN, "/api/v1/questions/4").andExpect(status().isOk());
        assertSameNotFound(mvc, req(6, "/api/v1/questions/4"), req(6, "/api/v1/questions/" + Long.MAX_VALUE));
        assertSameNotFound(mvc, req(2, "/api/v1/questions/4"), req(2, "/api/v1/questions/" + Long.MAX_VALUE));
    }

    @Test
    void thread_deletedQuestion_404ForEveryone() throws Exception {
        for (long user : new long[] {7, 3, SeedUserTokens.ADMIN}) {
            assertSameNotFound(mvc, req(user, "/api/v1/questions/5"),
                    req(user, "/api/v1/questions/" + Long.MAX_VALUE));
        }
    }

    @Test
    void thread_idor_studentOfAnotherCourse_404IdenticalToUnknown() throws Exception {
        assertSameNotFound(mvc, req(8, "/api/v1/questions/1"), req(8, "/api/v1/questions/" + Long.MAX_VALUE));
        as(3, "/api/v1/questions/1").andExpect(status().isOk()); // accepted co-instructor
        as(4, "/api/v1/questions/1").andExpect(status().isOk()); // student 4 is enrolled in course 1 (D-12)
        as(15, "/api/v1/questions/1").andExpect(status().isNotFound());
    }

    @Test
    void thread_withoutToken_401() throws Exception {
        expectProblem(mvc.perform(get("/api/v1/questions/1")), 401, "UNAUTHENTICATED");
    }

    @Test
    void thread_statementCount_independentOfAnswerCount() throws Exception {
        as(6, "/api/v1/questions/1").andExpect(status().isOk()); // warm-up
        sql.reset();
        as(6, "/api/v1/questions/1").andExpect(jsonPath("$.answers", hasSize(2)));
        int two = sql.count();
        sql.reset();
        as(6, "/api/v1/questions/2").andExpect(jsonPath("$.answers", hasSize(2)));
        assertThat(sql.count()).as(sql.statements().toString()).isEqualTo(two);
        sql.reset();
        as(9, "/api/v1/questions/3").andExpect(jsonPath("$.answers", hasSize(0)));
        // no answers: the names query is still one statement for the asker
        assertThat(sql.count()).as(sql.statements().toString()).isEqualTo(two);
    }

    // ---- UC-I22 instructor list

    @Test
    void instructorList_questionsOfInstructedCourses_UCI22() throws Exception {
        as(2, "/api/v1/instructor/questions")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(2, 3, 1)))
                .andExpect(jsonPath("$.content[1].courseId").value(3))
                .andExpect(jsonPath("$.content[1].courseTitle")
                        .value("ReactJS thực chiến: Xây dựng website thương mại điện tử"))
                .andExpect(jsonPath("$.content[1].asker.fullName").value("Đặng Quốc Việt"))
                .andExpect(jsonPath("$.content[1].instructorReplied").value(false));
        as(3, "/api/v1/instructor/questions") // courses 5, 6, 7, 12, 13 and co-instructor of 1; 5 is deleted
                .andExpect(jsonPath("$.content[*].id", contains(2, 1)));
        as(4, "/api/v1/instructor/questions").andExpect(jsonPath("$.content[*].id", contains(6)));
    }

    @Test
    void instructorList_filters_unansweredAndCourse_UCI22() throws Exception {
        as(2, "/api/v1/instructor/questions?status=unanswered").andExpect(jsonPath("$.content[*].id", contains(3)));
        as(2, "/api/v1/instructor/questions?courseId=1").andExpect(jsonPath("$.content[*].id", contains(2, 1)));
        as(2, "/api/v1/instructor/questions?courseId=1&status=unanswered").andExpect(jsonPath("$.content", empty()));
        as(2, "/api/v1/instructor/questions?courseId=2").andExpect(jsonPath("$.content", empty()));
    }

    @Test
    void instructorList_courseNotInstructed_404IdenticalToUnknown() throws Exception {
        assertSameNotFound(mvc, req(2, "/api/v1/instructor/questions?courseId=5"),
                req(2, "/api/v1/instructor/questions?courseId=" + Long.MAX_VALUE));
    }

    @Test
    void instructorList_badStatus_400() throws Exception {
        expectProblem(as(2, "/api/v1/instructor/questions?status=answered"), 400, "VALIDATION_FAILED");
    }

    @Test
    void instructorList_noActiveInstructorRole_403() throws Exception {
        expectProblem(as(6, "/api/v1/instructor/questions"), 403, "ACCESS_DENIED");
        expectProblem(mvc.perform(get("/api/v1/instructor/questions").header(HttpHeaders.AUTHORIZATION,
                tokens.bearer(SeedUserTokens.REVOKED_INSTRUCTOR, RoleCode.STUDENT, RoleCode.INSTRUCTOR))),
                403, "ACCESS_DENIED");
    }

    @Test
    void instructorList_withoutToken_401() throws Exception {
        expectProblem(mvc.perform(get("/api/v1/instructor/questions")), 401, "UNAUTHENTICATED");
    }

    @Test
    void instructorList_statementCount_constantAcrossPageSizes() throws Exception {
        as(2, "/api/v1/instructor/questions").andExpect(status().isOk()); // warm-up

        sql.reset();
        as(2, "/api/v1/instructor/questions?size=1").andExpect(jsonPath("$.content", hasSize(1)));
        // instructed course ids + page + count + names + courses
        assertThat(sql.count()).as(sql.statements().toString()).isEqualTo(5);

        sql.reset();
        as(2, "/api/v1/instructor/questions?size=3").andExpect(jsonPath("$.content", hasSize(3)));
        assertThat(sql.count()).as(sql.statements().toString()).isEqualTo(5);
    }
}
