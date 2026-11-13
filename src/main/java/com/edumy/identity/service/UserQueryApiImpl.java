package com.edumy.identity.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.edumy.identity.api.UserQueryApi;
import com.edumy.identity.api.UserSummary;
import com.edumy.identity.mapper.UserMapper;
import com.edumy.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link UserQueryApi}: one SELECT of the few public columns, no entity load. */
@Service
@Transactional(readOnly = true)
class UserQueryApiImpl implements UserQueryApi {

    private final UserRepository users;
    private final UserMapper mapper;

    UserQueryApiImpl(UserRepository users, UserMapper mapper) {
        this.users = users;
        this.mapper = mapper;
    }

    @Override
    public Optional<UserSummary> findSummary(Long id) {
        return id == null ? Optional.empty() : Optional.ofNullable(findSummaries(List.of(id)).get(id));
    }

    @Override
    public Map<Long, UserSummary> findSummaries(Collection<Long> ids) {
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return users.findSummariesByIdIn(distinct).stream()
                .map(mapper::toUserSummary)
                .collect(Collectors.toUnmodifiableMap(UserSummary::id, Function.identity()));
    }
}
