package com.privacypilot.web.dto;

import com.privacypilot.domain.DeletionRequest;
import com.privacypilot.workflow.RequestStatus;
import java.time.Instant;

/**
 * Read model of a deletion request for the API/dashboard.
 */
public record RequestView(
        Long id,
        String companyName,
        String userName,
        RequestStatus status,
        String lastAction,
        String nextAction,
        String reviewReason,
        Instant createdAt,
        Instant updatedAt,
        Instant followUpDueAt,
        String requestBody) {

    public static RequestView from(DeletionRequest r) {
        return new RequestView(
                r.getId(),
                r.getCompany() != null ? r.getCompany().getName() : null,
                r.getUser() != null ? r.getUser().getName() : null,
                r.getStatus(),
                r.getLastAction(),
                r.getNextAction(),
                r.getReviewReason(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getFollowUpDueAt(),
                r.getRequestBody());
    }
}
