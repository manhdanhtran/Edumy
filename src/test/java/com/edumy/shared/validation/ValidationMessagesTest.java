package com.edumy.shared.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** P05-0: every built-in constraint message is overridden in Vietnamese (docs/ai/05 §5.2 "Validation messages"). */
class ValidationMessagesTest {

    /** The 22 built-in constraints of jakarta.validation.constraints (Bean Validation 3.x). */
    private static final List<String> BUILT_IN = List.of("AssertFalse", "AssertTrue", "DecimalMax", "DecimalMin",
            "Digits", "Email", "Future", "FutureOrPresent", "Max", "Min", "Negative", "NegativeOrZero", "NotBlank",
            "NotEmpty", "NotNull", "Null", "Past", "PastOrPresent", "Pattern", "Positive", "PositiveOrZero", "Size");

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void bundle_overridesEveryBuiltInConstraintKey() throws IOException {
        Properties bundle = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/ValidationMessages.properties")) {
            assertThat(in).isNotNull();
            bundle.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        for (String name : BUILT_IN) {
            assertThat(bundle.getProperty("jakarta.validation.constraints." + name + ".message"))
                    .as(name).isNotBlank();
        }
    }

    record Sample(@NotNull String notNull, @NotBlank String notBlank, @NotEmpty List<String> notEmpty,
                  @Size(max = 3) String size, @Min(1) Integer min, @Max(5) Integer max, @Positive Integer positive,
                  @Pattern(regexp = "[a-z]+") String pattern, @DecimalMax("10") Integer decimalMax) {
    }

    @Test
    void validator_rendersVietnameseTexts_withParameters() {
        var sample = new Sample(null, " ", List.of(), "abcd", 0, 6, 0, "ABC", 11);
        Map<String, String> messages = validator.validate(sample).stream()
                .collect(Collectors.toMap(v -> v.getPropertyPath().toString(), v -> v.getMessage()));

        assertThat(messages).containsEntry("notNull", "Không được để trống")
                .containsEntry("notBlank", "Không được để trống")
                .containsEntry("notEmpty", "Không được để trống")
                .containsEntry("size", "Độ dài phải từ 0 đến 3 ký tự")
                .containsEntry("min", "Giá trị tối thiểu là 1")
                .containsEntry("max", "Giá trị tối đa là 5")
                .containsEntry("positive", "Giá trị phải lớn hơn 0")
                .containsEntry("pattern", "Không đúng định dạng")
                .containsEntry("decimalMax", "Giá trị phải nhỏ hơn hoặc bằng 10");
    }
}
