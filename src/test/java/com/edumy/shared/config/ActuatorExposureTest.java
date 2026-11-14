package com.edumy.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * docs/ai/04 §4.5 + docs/ai/05 §5.3: Actuator exposes only {@code health,info} by default; the cache statistics
 * ({@code caches}, {@code metrics}) are exposed in the {@code dev} profile only. Light test: reads the YAML files
 * (no Spring context). Who may call them: {@code JwtSecurityIT#actuatorOtherThanHealthInfo_*} (admin only).
 */
class ActuatorExposureTest {

    private static final String KEY = "management.endpoints.web.exposure.include";

    @Test
    void defaultProfile_exposesOnlyHealthAndInfo() {
        assertThat(exposed("application.yml")).containsExactly("health", "info");
    }

    @Test
    void devProfile_alsoExposesCachesAndMetrics() {
        assertThat(exposed("application-dev.yml")).contains("health", "info", "caches", "metrics");
    }

    @Test
    void testProfile_addsNoExposure() {
        Properties test = load("application-test.yml");
        assertThat(test.getProperty(KEY)).isNull();
    }

    private static List<String> exposed(String file) {
        String value = load(file).getProperty(KEY);
        assertThat(value).as(KEY + " in " + file).isNotBlank();
        return Arrays.stream(value.split(",")).map(String::trim).toList();
    }

    private static Properties load(String file) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(file));
        Properties properties = yaml.getObject();
        assertThat(properties).as(file).isNotNull();
        return properties;
    }
}
