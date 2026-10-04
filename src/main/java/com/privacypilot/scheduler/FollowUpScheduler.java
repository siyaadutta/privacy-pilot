package com.privacypilot.scheduler;

import com.privacypilot.domain.DeletionRequest;
import com.privacypilot.repository.DeletionRequestRepository;
import com.privacypilot.service.DeletionRequestService;
import com.privacypilot.workflow.RequestStatus;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically scans for WAITING requests whose follow-up deadline has passed
 * and sends an automatic follow-up for each.
 */
@Component
public class FollowUpScheduler {

    private static final Logger log = LoggerFactory.getLogger(FollowUpScheduler.class);

    private final DeletionRequestRepository requests;
    private final DeletionRequestService requestService;

    public FollowUpScheduler(DeletionRequestRepository requests,
                             DeletionRequestService requestService) {
        this.requests = requests;
        this.requestService = requestService;
    }

    @Scheduled(cron = "${privacypilot.followup.scan-cron}")
    public void scan() {
        int count = processDueFollowUps(Instant.now());
        if (count > 0) {
            log.info("Follow-up scheduler processed {} due request(s).", count);
        }
    }

    /**
     * Find WAITING requests whose follow-up is due at or before {@code now} and
     * trigger a follow-up for each. Extracted from the cron method so the logic
     * is unit-testable without the scheduler.
     *
     * @return number of requests followed up
     */
    public int processDueFollowUps(Instant now) {
        List<DeletionRequest> due =
                requests.findByStatusAndFollowUpDueAtLessThanEqual(RequestStatus.WAITING, now);
        int processed = 0;
        for (DeletionRequest req : due) {
            try {
                requestService.followUp(req.getId());
                processed++;
            } catch (Exception e) {
                log.warn("Follow-up failed for request {}: {}", req.getId(), e.toString());
            }
        }
        return processed;
    }
}
