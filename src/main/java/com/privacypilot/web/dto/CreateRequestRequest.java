package com.privacypilot.web.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request to create a new deletion request (DRAFT) for a user against a company.
 */
public record CreateRequestRequest(
        @NotNull Long userId,
        @NotNull Long companyId) {
}
