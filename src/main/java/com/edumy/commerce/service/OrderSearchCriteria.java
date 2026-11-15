package com.edumy.commerce.service;

import java.time.Instant;

/**
 * UC-A13 filters for {@link OrderQueryService#search} (SC-M4: services take criteria, not HTTP records). Every field is
 * optional ({@code null} = no filter); {@code status} is an {@code orders.status} code already validated by the
 * controller; {@code createdFrom} inclusive, {@code createdTo} exclusive.
 */
public record OrderSearchCriteria(String orderCode, String email, String status, Instant createdFrom,
                                  Instant createdTo) {
}
