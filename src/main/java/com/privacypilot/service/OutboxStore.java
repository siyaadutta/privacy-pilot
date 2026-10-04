package com.privacypilot.service;

import com.privacypilot.domain.OutboxEmail;
import com.privacypilot.repository.OutboxEmailRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional boundary for outbox claim/finalize operations.
 *
 * <p>Deliberately separate from {@link OutboxDeliveryWorker} so each DB operation
 * runs in its own short transaction and the SMTP call in the worker happens
 * <em>between</em> transactions with no lock held. (Self-invocation of
 * {@code @Transactional} methods would not create new transactions.)
 *
 * <p><b>Fencing.</b> Each claim stamps a fresh {@code claim_token}. Finalization
 * is a conditional {@code UPDATE ... WHERE id = ? AND status = 'PROCESSING' AND
 * claim_token = ?}. If a worker's lease expired and another worker reclaimed the
 * row (new token), the original worker's stale finalize matches zero rows and is
 * a no-op — it cannot overwrite the new claimant's outcome.
 */
@Component
public class OutboxStore {

    private final OutboxEmailRepository outbox;

    public OutboxStore(OutboxEmailRepository outbox) {
        this.outbox = outbox;
    }

    /**
     * Claim a batch atomically (PENDING or lease-expired PROCESSING → PROCESSING)
     * with a fresh fencing token stamped on every claimed row. Runs in
     * REQUIRES_NEW so the claim commits and releases row locks before the caller
     * performs any external I/O. Each returned row carries the token via
     * {@link OutboxEmail#getClaimToken()}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEmail> claim(String worker, int batchSize, Duration leaseDuration) {
        Instant now = Instant.now();
        Instant leaseCutoff = now.minus(leaseDuration);
        String token = UUID.randomUUID().toString();
        return outbox.claimBatch(worker, token, now, leaseCutoff, batchSize);
    }

    /**
     * Fenced finalize as SENT.
     * @return true if this call still owned the claim (row updated), false if the
     *         claim was lost to another worker (no-op).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markSent(Long id, String claimToken) {
        return outbox.markSentFenced(id, claimToken, Instant.now()) > 0;
    }

    /**
     * Fenced finalize after a failed send (→ PENDING for retry, or FAILED at the
     * attempt cap).
     * @return true if this call still owned the claim, false if it was lost.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailure(Long id, String claimToken, int maxAttempts, String error) {
        return outbox.markFailureFenced(id, claimToken, maxAttempts, error) > 0;
    }
}
