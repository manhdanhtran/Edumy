package com.edumy.shared.error;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.util.List;

import com.edumy.shared.web.TraceIdFilter;
import jakarta.persistence.PersistenceException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler(messages, new ConstraintViolationTranslator()))
                .addFilters(new TraceIdFilter()) // the handler reads traceId from the MDC only
                .build();
    }

    @Test
    void businessException_rendersProblemDetailWithCodeAndTraceId() throws Exception {
        MvcResult result = mvc.perform(get("/probe/not-editable"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://edumy.local/problems/course-not-editable"))
                .andExpect(jsonPath("$.title").value("Khóa học đang chờ duyệt, không thể chỉnh sửa"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.instance").value("/probe/not-editable"))
                .andExpect(jsonPath("$.code").value("COURSE_NOT_EDITABLE"))
                .andExpect(jsonPath("$.traceId").value(matchesPattern("[0-9a-f]{32}")))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andReturn();
        assertTraceIdInBodyEqualsHeader(result);
    }

    @Test
    void businessException_validIncomingTraceId_usedInBodyAndHeader() throws Exception {
        mvc.perform(get("/probe/not-editable").header(TraceIdFilter.HEADER, "spa-req-0001"))
                .andExpect(status().isConflict())
                .andExpect(header().string(TraceIdFilter.HEADER, "spa-req-0001"))
                .andExpect(jsonPath("$.traceId").value("spa-req-0001"));
    }

    @Test
    void unexpectedException_traceIdInBodyEqualsResponseHeader() throws Exception {
        assertTraceIdInBodyEqualsHeader(
                mvc.perform(get("/probe/boom")).andExpect(status().isInternalServerError()).andReturn());
    }

    @Test
    void submitChecklistFailed_listsFailedItemsInErrors_BR07() throws Exception {
        mvc.perform(get("/probe/checklist"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("COURSE_SUBMIT_CHECKLIST_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0].field").value("description"))
                .andExpect(jsonPath("$.errors[1].field").value("chapters"));
    }

    @Test
    void notFound_sameBodyForMissingAndNotOwned_NFR01() throws Exception {
        String missing = body("/probe/missing");
        String notMine = body("/probe/not-mine");
        // identical except per-request traceId and instance path
        org.assertj.core.api.Assertions.assertThat(strip(missing)).isEqualTo(strip(notMine));
        mvc.perform(get("/probe/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void invalidBody_returnsValidationFailedWithFieldErrors() throws Exception {
        mvc.perform(post("/probe/validate").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("title"));
    }

    @Test
    void malformedJson_returnsMalformedRequest() throws Exception {
        mvc.perform(post("/probe/validate").contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void unexpectedException_returnsInternalErrorWithoutLeakingMessage() throws Exception {
        mvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("Đã có lỗi xảy ra, vui lòng thử lại sau."));
    }

    @Test
    void unmappedCheckViolation_returns500WithoutLeakingTheConstraintName() throws Exception {
        String body = body("/probe/check");
        mvc.perform(get("/probe/check"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("Đã có lỗi xảy ra, vui lòng thử lại sau."))
                .andExpect(jsonPath("$.traceId").value(matchesPattern("[0-9a-f]{32}")));
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("ck_users_status").doesNotContain("Check constraint");
    }

    @Test
    void mappedTrigger_returnsItsErrorCodeAndStatus_BR02() throws Exception {
        mvc.perform(get("/probe/trigger"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("COURSE_CO_INSTRUCTOR_IS_MAIN"))
                .andExpect(jsonPath("$.type").value("https://edumy.local/problems/course-co-instructor-is-main"));
    }

    @Test
    void unmappedTrigger_returns500WithoutLeakingTheMessage() throws Exception {
        String body = body("/probe/trigger-unmapped");
        mvc.perform(get("/probe/trigger-unmapped"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("BR-99").doesNotContain("secret rule");
    }

    @Test
    void mappedUnique_returnsItsErrorCodeAndStatus() throws Exception {
        mvc.perform(get("/probe/known-unique"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_ITEM_DUPLICATE"))
                .andExpect(jsonPath("$.type").value("https://edumy.local/problems/cart-item-duplicate"));
    }

    @Test
    void unmappedUnique_returnsDataConflict() throws Exception {
        mvc.perform(get("/probe/unmapped-unique"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CONFLICT"));
    }

    @Test
    void fkMissingParent1452_returns404WithTheSameBodyAsAnyOther404_NFR01() throws Exception {
        mvc.perform(get("/probe/fk-missing-parent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        org.assertj.core.api.Assertions.assertThat(strip(body("/probe/fk-missing-parent")))
                .isEqualTo(strip(body("/probe/missing")))
                .doesNotContain("fk_");
    }

    @Test
    void fkParentStillReferenced1451_returnsDataConflict() throws Exception {
        mvc.perform(get("/probe/fk-referenced"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CONFLICT"));
    }

    @Test
    void dataAccessExceptionWithoutConstraintViolation_returnsInternalError() throws Exception {
        mvc.perform(get("/probe/timeout"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("Đã có lỗi xảy ra, vui lòng thử lại sau."));
    }

    private static void assertTraceIdInBodyEqualsHeader(MvcResult result) throws Exception {
        String header = result.getResponse().getHeader(TraceIdFilter.HEADER);
        org.assertj.core.api.Assertions.assertThat(header).matches("[0-9a-f]{32}");
        jsonPath("$.traceId").value(header).match(result);
    }

    private String body(String url) throws Exception {
        return mvc.perform(get(url)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String strip(String json) {
        return json.replaceAll("\"traceId\":\"[0-9a-f]+\"", "").replaceAll("\"instance\":\"[^\"]+\"", "");
    }

    record ProbeRequest(@NotBlank String title) {
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe/not-editable")
        void notEditable() {
            throw new BusinessException(ErrorCode.COURSE_NOT_EDITABLE);
        }

        @GetMapping("/probe/checklist")
        void checklist() {
            throw BusinessException.withErrors(ErrorCode.COURSE_SUBMIT_CHECKLIST_FAILED, List.of(
                    new FieldViolation("description", "Chưa có mô tả"),
                    new FieldViolation("chapters", "Cần ít nhất một chương")));
        }

        @GetMapping("/probe/missing")
        void missing() {
            throw BusinessException.notFound();
        }

        @GetMapping("/probe/not-mine")
        void notMine() {
            throw BusinessException.notFound();
        }

        @PostMapping("/probe/validate")
        void validate(@Valid @RequestBody ProbeRequest request) {
        }

        @GetMapping("/probe/check")
        void check() {
            SQLException root = new SQLException("Check constraint 'ck_users_status' is violated.", "HY000", 3819);
            throw new UncategorizedSQLException("StatementCallback", "INSERT INTO users ...", root);
        }

        @GetMapping("/probe/trigger")
        void trigger() {
            throw new PersistenceException("could not execute statement",
                    new SQLException("BR-02: co-instructor must differ from main instructor", "45000", 1644));
        }

        @GetMapping("/probe/trigger-unmapped")
        void triggerUnmapped() {
            throw new PersistenceException("could not execute statement",
                    new SQLException("BR-99: secret rule text", "45000", 1644));
        }

        @GetMapping("/probe/unmapped-unique")
        void unmappedUnique() {
            throw new UncategorizedSQLException("StatementCallback", "INSERT INTO roles ...",
                    new SQLException("Duplicate entry 'student' for key 'roles.uq_roles_code'", "23000", 1062));
        }

        @GetMapping("/probe/fk-missing-parent")
        void fkMissingParent() {
            throw new PersistenceException("could not execute statement", new SQLException(
                    "Cannot add or update a child row: a foreign key constraint fails (`edumy`.`user_roles`, "
                            + "CONSTRAINT `fk_user_roles_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`))",
                    "23000", 1452));
        }

        @GetMapping("/probe/fk-referenced")
        void fkReferenced() {
            throw new UncategorizedSQLException("StatementCallback", "DELETE FROM users ...", new SQLException(
                    "Cannot delete or update a parent row: a foreign key constraint fails (`edumy`.`user_roles`, "
                            + "CONSTRAINT `fk_user_roles_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`))",
                    "23000", 1451));
        }

        @GetMapping("/probe/known-unique")
        void knownUnique() {
            throw new UncategorizedSQLException("StatementCallback", "INSERT INTO cart_items ...",
                    new SQLException("Duplicate entry '7-9' for key 'cart_items.uq_cart_items_cart_course'",
                            "23000", 1062));
        }

        @GetMapping("/probe/timeout")
        void timeout() {
            throw new QueryTimeoutException("query timed out");
        }

        @GetMapping("/probe/boom")
        void boom() {
            throw new IllegalStateException("secret internals");
        }
    }
}
