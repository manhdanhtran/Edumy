package com.edumy.identity.mapper;

import java.util.List;

import com.edumy.identity.api.UserSummary;
import com.edumy.identity.dto.response.AdminUserResponse;
import com.edumy.identity.dto.response.MeResponse;
import com.edumy.identity.entity.User;
import com.edumy.identity.entity.UserStatus;
import com.edumy.identity.repository.UserRepository.AdminUserView;
import com.edumy.identity.repository.UserRepository.UserSummaryView;
import com.edumy.shared.security.RoleCode;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface UserMapper {

    /** {@code roles}: active role codes read from the database by the caller (BR-26), not the JWT claim. */
    MeResponse toMeResponse(User user, List<RoleCode> roles);

    /** UC-A01 row; {@code roles}: active role codes of this user, fetched for the whole page in one query. */
    AdminUserResponse toAdminUserResponse(AdminUserView user, List<RoleCode> roles);

    /** UC-A02…A05 answer: the changed account; {@code roles} = its active role codes read after the change. */
    AdminUserResponse toAdminUserResponse(User user, List<RoleCode> roles);

    /** {@code identity::api.UserQueryApi} record. */
    UserSummary toUserSummary(UserSummaryView user);

    /** Lower-case code, as in the JWT {@code roles} claim. */
    default String roleCode(RoleCode role) {
        return role.code();
    }

    /** Lower-case DB code ({@code active} / {@code locked}). */
    default String statusCode(UserStatus status) {
        return status.code();
    }
}
