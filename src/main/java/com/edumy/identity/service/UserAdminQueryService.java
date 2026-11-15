package com.edumy.identity.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.edumy.identity.dto.response.AdminUserResponse;
import com.edumy.identity.entity.UserStatus;
import com.edumy.identity.mapper.UserMapper;
import com.edumy.identity.repository.UserRepository;
import com.edumy.identity.repository.UserRepository.AdminUserView;
import com.edumy.identity.repository.UserRoleRepository;
import com.edumy.identity.repository.UserRoleRepository.UserRoleCodeView;
import com.edumy.shared.jpa.CodeEnum;
import com.edumy.shared.security.RoleCode;
import com.edumy.shared.text.LikePattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin reads of user accounts (UC-A01). A page costs at most three statements whatever its size: the page, its count
 * (skipped by Spring Data when the first page is not full) and the active roles of the users of the page.
 */
@Service
@Transactional(readOnly = true)
public class UserAdminQueryService {

    /** UC-A01 states no order: newest accounts first, id as tie-breaker (stable paging). Client sort is ignored. */
    static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final UserRepository users;
    private final UserRoleRepository userRoles;
    private final UserMapper mapper;

    public UserAdminQueryService(UserRepository users, UserRoleRepository userRoles, UserMapper mapper) {
        this.users = users;
        this.userRoles = userRoles;
        this.mapper = mapper;
    }

    /**
     * UC-A01: search by part of the email or the full name ({@code q}; full name case- and accent-insensitive), filter
     * by account status and by active role; each row shows the verification flag and the active roles.
     *
     * @param q          trimmed search text, {@code null} = no filter
     * @param statusCode {@code active} / {@code locked}, {@code null} = no filter (validated by the request record)
     * @param roleCode   {@code student} / {@code instructor} / {@code admin}, {@code null} = no filter
     */
    public Page<AdminUserResponse> search(String q, String statusCode, String roleCode, Pageable pageable) {
        UserStatus status = statusCode == null ? null : CodeEnum.fromCode(UserStatus.class, statusCode);
        RoleCode role = roleCode == null ? null : CodeEnum.fromCode(RoleCode.class, roleCode);
        Pageable page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), NEWEST_FIRST);

        // MINOR-4 (P10): % and _ typed by the admin are literals, not wildcards
        Page<AdminUserView> found = users.searchForAdmin(q == null ? null : LikePattern.contains(q), status, role, page);
        Map<Long, List<RoleCode>> rolesByUser = found.isEmpty() ? Map.of()
                : userRoles.findActiveRoleCodesByUserIdIn(found.map(AdminUserView::getId).getContent()).stream()
                        .collect(Collectors.groupingBy(UserRoleCodeView::getUserId,
                                Collectors.mapping(UserRoleCodeView::getRoleCode, Collectors.toList())));
        return found.map(user -> mapper.toAdminUserResponse(user, rolesByUser.getOrDefault(user.getId(), List.of())));
    }
}
