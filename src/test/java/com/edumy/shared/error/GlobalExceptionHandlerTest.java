package com.edumy.shared.error;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

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
                .setControllerAdvice(new GlobalExceptionHandler(messages, new ConstraintViolationTranslator(
                        // test-only mapping to prove "known key -> its ErrorCode"; production map is empty for now
                        Map.of("uq_carts_student", ErrorCode.CART_COURSE_ALREADY_OWNED))))
                .build();
    }

    @Test
    void businessException_rendersProblemDetailWithCodeAndTraceId() throws Exception {
        mvc.perform(get("/probe/not-editable"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://edumy.local/problems/course-not-editable"))
                .andExpect(jsonPath("$.title").value("Khóa học đang chờ duyệt, không thể chỉnh sửa"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.instance").value("/probe/not-editable"))
                .andExpect(jsonPath("$.code").value("COURSE_NOT_EDITABLE"))
                .andExpect(jsonPath("$.traceId").value(matchesPattern("[0-9a-f]{32}")))
                .andExpect(jsonPath("$.errors").doesNotExist());
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
    void checkViolationAsUncategorizedSql_returnsDataConflictNot500() throws Exception {
        mvc.perform(get("/probe/check"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("DATA_CONFLICT"))
                .andExpect(jsonPath("$.traceId").value(matchesPattern("[0-9a-f]{32}")));
    }

    @Test
    void triggerSignalInPersistenceException_returnsDataConflict() throws Exception {
        mvc.perform(get("/probe/trigger"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CONFLICT"));
    }

    @Test
    void knownConstraintKey_returnsItsErrorCodeAndStatus() throws Exception {
        mvc.perform(get("/probe/known-unique"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_COURSE_ALREADY_OWNED"))
                .andExpect(jsonPath("$.type").value("https://edumy.local/problems/cart-course-already-owned"));
    }

    @Test
    void dataAccessExceptionWithoutConstraintViolation_returnsInternalError() throws Exception {
        mvc.perform(get("/probe/timeout"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("Đã có lỗi xảy ra, vui lòng thử lại sau."));
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

        @GetMapping("/probe/known-unique")
        void knownUnique() {
            throw new UncategorizedSQLException("StatementCallback", "INSERT INTO carts ...",
                    new SQLException("Duplicate entry '7' for key 'carts.uq_carts_student'", "23000", 1062));
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
