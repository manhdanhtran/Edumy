package com.edumy.shared.config;

import java.util.List;

import com.edumy.shared.security.CurrentUserIdArgumentResolver;
import com.edumy.shared.web.TraceIdFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;
import org.springframework.http.HttpHeaders;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

/**
 * MVC setup: CORS allow-list (docs/ai/04 §4.5), stable page JSON (docs/ai/05 §5.1), {@code @CurrentUserId}.
 * Spring Security must enable {@code http.cors()} for this CORS mapping to apply (P04).
 */
@Configuration(proxyBeanMethods = false)
@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
class WebConfig implements WebMvcConfigurer {

    static final int MAX_PAGE_SIZE = 100;

    private final EdumyProperties properties;

    WebConfig(EdumyProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = properties.cors().allowedOrigins();
        if (origins.isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(origins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowCredentials(true) // refresh cookie edumy_rt
                // readable by the SPA on another origin (P10 SC-M3): 429 Retry-After (docs/ai/04 §4.4), Location of
                // every 201, the certificate PDF file name and the trace id (no stub marker since P13: no stub left)
                .exposedHeaders(HttpHeaders.RETRY_AFTER, HttpHeaders.LOCATION, HttpHeaders.CONTENT_DISPOSITION,
                        TraceIdFilter.HEADER)
                .maxAge(3600);
    }

    /**
     * docs/ai/05 §5.1: {@code size} default 20 (Spring Data default), max {@value #MAX_PAGE_SIZE} (larger values are
     * clamped). A customizer, because {@code @EnableSpringDataWebSupport} above makes Boot's
     * {@code spring.data.web.pageable.*} properties ineffective.
     */
    @Bean
    PageableHandlerMethodArgumentResolverCustomizer pageableLimits() {
        return resolver -> resolver.setMaxPageSize(MAX_PAGE_SIZE);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserIdArgumentResolver());
    }
}
