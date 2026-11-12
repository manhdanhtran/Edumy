package com.edumy.shared.jpa;

import jakarta.persistence.AttributeConverter;

/**
 * Base JPA converter for {@link CodeEnum}. Each enum gets a one-line subclass in its module's {@code entity/} folder:
 * <pre>{@code
 * @Converter(autoApply = true)
 * public class CourseStatusConverter extends CodeEnumConverter<CourseStatus> {
 *     public CourseStatusConverter() { super(CourseStatus.class); }
 * }
 * }</pre>
 */
public abstract class CodeEnumConverter<E extends Enum<E> & CodeEnum> implements AttributeConverter<E, String> {

    private final Class<E> type;

    protected CodeEnumConverter(Class<E> type) {
        this.type = type;
    }

    @Override
    public String convertToDatabaseColumn(E attribute) {
        return attribute == null ? null : attribute.code();
    }

    @Override
    public E convertToEntityAttribute(String dbData) {
        return dbData == null ? null : CodeEnum.fromCode(type, dbData);
    }
}
