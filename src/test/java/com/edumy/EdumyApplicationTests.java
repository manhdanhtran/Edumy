package com.edumy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import com.edumy.shared.error.ErrorCode;
import com.edumy.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class EdumyApplicationTests {

    @Autowired
    MessageSource messageSource;

    @Test
    void contextLoads() {
    }

    @Test
    void everyErrorCodeHasVietnameseTitleAndDetail() {
        Locale vi = Locale.forLanguageTag("vi");
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(messageSource.getMessage("error." + code.name() + ".title", null, null, vi))
                    .as("title of %s", code).isNotBlank();
            assertThat(messageSource.getMessage("error." + code.name() + ".detail", null, null, vi))
                    .as("detail of %s", code).isNotBlank();
        }
    }
}
