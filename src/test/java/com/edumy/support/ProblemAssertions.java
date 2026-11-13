package com.edumy.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import com.edumy.shared.web.TraceIdFilter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** MockMvc checks of the RFC 9457 error shape (docs/ai/05 §5.2). */
public final class ProblemAssertions {

    private ProblemAssertions() {
    }

    /** Status, problem+json, {@code code}, and {@code traceId} equal to the {@code X-Trace-Id} response header. */
    public static MvcResult expectProblem(ResultActions actions, int status, String code) throws Exception {
        MvcResult result = actions
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andReturn();
        String traceId = result.getResponse().getHeader(TraceIdFilter.HEADER);
        assertThat(traceId).isNotBlank();
        jsonPath("$.traceId").value(traceId).match(result);
        return result;
    }

    /** The response body without {@code traceId} and {@code instance}, to compare two answers for equality. */
    public static String withoutTraceAndInstance(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("\"traceId\":\"[^\"]*\"", "")
                .replaceAll("\"instance\":\"[^\"]*\"", "");
    }
}
