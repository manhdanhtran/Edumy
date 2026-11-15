package com.edumy.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

/**
 * P10 structure review SC-M3: the SPA runs on another origin, so every response header the contract promises it must
 * be in {@code Access-Control-Expose-Headers}, or cross-origin JavaScript reads {@code null}.
 */
class WebConfigCorsTest {

    /** Exposes the configurations Spring would register (the getter is protected). */
    static final class InspectableRegistry extends CorsRegistry {
        Map<String, CorsConfiguration> configurations() {
            return getCorsConfigurations();
        }
    }

    @Test
    void exposesRetryAfterLocationContentDispositionTraceIdAndStubMarker() {
        EdumyProperties properties = new EdumyProperties(null,
                new EdumyProperties.Cors(List.of("http://localhost:5173")), null, null, null, null);
        InspectableRegistry registry = new InspectableRegistry();

        new WebConfig(properties).addCorsMappings(registry);

        CorsConfiguration api = registry.configurations().get("/api/**");
        assertThat(api.getAllowedOrigins()).containsExactly("http://localhost:5173");
        assertThat(api.getAllowCredentials()).isTrue();
        assertThat(api.getExposedHeaders()).containsExactlyInAnyOrder(
                "Retry-After", "Location", "Content-Disposition", "X-Trace-Id", "X-Edumy-Stub");
    }

    @Test
    void noAllowedOrigin_noCorsMapping() {
        EdumyProperties properties = new EdumyProperties(null, new EdumyProperties.Cors(List.of()), null, null, null,
                null);
        InspectableRegistry registry = new InspectableRegistry();

        new WebConfig(properties).addCorsMappings(registry);

        assertThat(registry.configurations()).isEmpty();
    }
}
