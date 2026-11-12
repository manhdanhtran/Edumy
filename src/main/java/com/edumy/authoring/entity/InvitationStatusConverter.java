package com.edumy.authoring.entity;

import com.edumy.shared.jpa.CodeEnumConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class InvitationStatusConverter extends CodeEnumConverter<InvitationStatus> {

    public InvitationStatusConverter() {
        super(InvitationStatus.class);
    }
}
