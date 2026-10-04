package com.privacypilot.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.privacypilot.domain.Company;
import com.privacypilot.domain.DeletionRequest;
import com.privacypilot.domain.User;
import com.privacypilot.repository.DeletionRequestRepository;
import com.privacypilot.service.DeletionRequestService;
import com.privacypilot.workflow.RequestStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FollowUpSchedulerTest {

    @Mock private DeletionRequestRepository requests;
    @Mock private DeletionRequestService requestService;

    private DeletionRequest req(long id) {
        DeletionRequest r = new DeletionRequest(
                new User("U", "u@e.com", null, null),
                new Company("C", null, "EMAIL", "p@c.com", "email", "x"));
        r.setId(id);
        r.setStatus(RequestStatus.WAITING);
        return r;
    }

    @Test
    void processesEachDueRequest() {
        Instant now = Instant.now();
        when(requests.findByStatusAndFollowUpDueAtLessThanEqual(RequestStatus.WAITING, now))
                .thenReturn(List.of(req(1L), req(2L), req(3L)));

        FollowUpScheduler scheduler = new FollowUpScheduler(requests, requestService);
        int processed = scheduler.processDueFollowUps(now);

        assertThat(processed).isEqualTo(3);
        verify(requestService).followUp(1L);
        verify(requestService).followUp(2L);
        verify(requestService).followUp(3L);
    }

    @Test
    void noDueRequestsProcessesNothing() {
        Instant now = Instant.now();
        when(requests.findByStatusAndFollowUpDueAtLessThanEqual(RequestStatus.WAITING, now))
                .thenReturn(List.of());

        FollowUpScheduler scheduler = new FollowUpScheduler(requests, requestService);
        int processed = scheduler.processDueFollowUps(now);

        assertThat(processed).isZero();
        verify(requestService, times(0)).followUp(any());
    }

    @Test
    void oneFailureDoesNotStopOthers() {
        Instant now = Instant.now();
        when(requests.findByStatusAndFollowUpDueAtLessThanEqual(RequestStatus.WAITING, now))
                .thenReturn(List.of(req(1L), req(2L)));
        when(requestService.followUp(eq(1L))).thenThrow(new RuntimeException("boom"));

        FollowUpScheduler scheduler = new FollowUpScheduler(requests, requestService);
        int processed = scheduler.processDueFollowUps(now);

        // Request 1 failed, request 2 still processed.
        assertThat(processed).isEqualTo(1);
        verify(requestService).followUp(2L);
    }
}
