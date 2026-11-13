package com.edumy.learning.mapper;

import com.edumy.learning.dto.response.CertificateResponse;
import com.edumy.learning.repository.CertificateRepository.StudentCertificateView;
import org.mapstruct.Mapper;

/** Certificates → UC-S20 rows. */
@Mapper(componentModel = "spring")
public interface CertificateMapper {

    CertificateResponse toResponse(StudentCertificateView certificate);
}
