package com.edumy.shared.config;

import java.util.List;

import com.edumy.shared.security.CurrentUserIdArgumentResolver;
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
                .exposedHeaders(HttpHeaders.RETRY_AFTER) // 429 of the abuse limiters (docs/ai/04 §4.4)
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
