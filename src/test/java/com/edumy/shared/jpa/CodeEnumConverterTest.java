package com.edumy.shared.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.edumy.shared.security.RoleCode;
import org.junit.jupiter.api.Test;

class CodeEnumConverterTest {

    static class RoleCodeConverter extends CodeEnumConverter<RoleCode> {
        RoleCodeConverter() {
            super(RoleCode.class);
        }
    }

    private final RoleCodeConverter converter = new RoleCodeConverter();

    @Test
    void storesLowercaseDbCode() {
        assertThat(converter.convertToDatabaseColumn(RoleCode.INSTRUCTOR)).isEqualTo("instructor");
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void readsDbCode() {
        assertThat(converter.convertToEntityAttribute("admin")).isEqualTo(RoleCode.ADMIN);
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void unknownCode_fails() {
        assertThatIllegalArgumentException().isThrownBy(() -> converter.convertToEntityAttribute("ADMIN"));
    }
}
