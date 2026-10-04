package com.privacypilot.domain;

/**
 * Delivery lifecycle of an outbound email in the transactional outbox.
 */
public enum OutboxStatus {
    /** Persisted intent; not yet claimed by a worker. */
    PENDING,
    /** Claimed by a worker and being delivered (lease held until marked terminal). */
    PROCESSING,
    /** Successfully dispatched to the mail provider. */
    SENT,
    /** Delivery failed after exhausting retries; needs operator attention. */
    FAILED
}
