package com.privacypilot.repository;

import com.privacypilot.domain.DeletionRequest;
import com.privacypilot.workflow.RequestStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeletionRequestRepository extends JpaRepository<DeletionRequest, Long> {

    List<DeletionRequest> findByStatus(RequestStatus status);

    /**
     * Requests that are still waiting and whose follow-up deadline has passed.
     * Used by the follow-up scheduler.
     */
    List<DeletionRequest> findByStatusAndFollowUpDueAtLessThanEqual(RequestStatus status, Instant now);
}
