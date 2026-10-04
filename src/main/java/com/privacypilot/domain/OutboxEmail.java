package com.privacypilot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Transactional outbox row for an outbound email.
 *
 * <p>Writing this row is part of the same DB transaction that changes the
 * request's workflow state, so the "intent to send" is durable atomically with
 * the state change. A separate worker delivers the email <em>after</em> commit
 * and updates {@link #status}. This decouples the irreversible external side
 * effect (email) from the database transaction — a rollback can never leave the
 * DB out of sync with what the company actually received, and delivery can be
 * retried safely.
 *
 * <p>Three distinct layers, deliberately not conflated:
 * <ul>
 *   <li>{@link #idempotencyKey} (unique) — deduplicates creation of the outbox
 *       record itself: a given logical send (initial request, or the Nth
 *       follow-up) is enqueued at most once, so retries of the enclosing
 *       operation cannot create a second row.</li>
 *   <li>{@link #claimToken} — fences stale DB finalization between workers, so a
 *       worker whose lease expired cannot overwrite a reclaiming worker's
 *       outcome.</li>
 *   <li>(none) — there is no provider-level SMTP idempotency key, so external
 *       delivery itself is <b>at-least-once</b>: a crash between send and
 *       finalize can cause the same email to be sent again.</li>
 * </ul>
 */
@Entity
@Table(name = "outbox_emails",
        indexes = {@Index(name = "idx_outbox_status", columnList = "status")})
public class OutboxEmail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Deduplication key, e.g. "deletion-request-42-initial" or "...-followup-3". */
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 200)
    private String idempotencyKey;

    @Column(name = "request_id", nullable = false)
    private Long requestId;

    @Column(name = "recipient")
    private String recipient;

    @Column(name = "sender")
    private String sender;

    @Column(name = "subject")
    private String subject;

    @Column(length = 8000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    /** When this row was claimed into PROCESSING (start of the delivery lease). */
    @Column(name = "claimed_at")
    private Instant claimedAt;

    /** Identifier of the worker/instance that holds the lease (for diagnostics). */
    @Column(name = "claimed_by", length = 100)
    private String claimedBy;

    /**
     * Fencing token, regenerated on every claim attempt. Finalization
     * ({@code markSent}/{@code markFailure}) must match this token, so a worker
     * whose lease expired and whose row was reclaimed by another worker cannot
     * overwrite the new claimant's outcome with a stale call.
     */
    @Column(name = "claim_token", length = 40)
    private String claimToken;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "sent_at")
    private Instant sentAt;

    public OutboxEmail() {
    }

    public OutboxEmail(String idempotencyKey, Long requestId, String sender, String recipient,
                       String subject, String body) {
        this.idempotencyKey = idempotencyKey;
        this.requestId = requestId;
        this.sender = sender;
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Long getRequestId() {
        return requestId;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getSender() {
        return sender;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public void setStatus(OutboxStatus status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(Instant claimedAt) {
        this.claimedAt = claimedAt;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public void setClaimedBy(String claimedBy) {
        this.claimedBy = claimedBy;
    }

    public String getClaimToken() {
        return claimToken;
    }

    public void setClaimToken(String claimToken) {
        this.claimToken = claimToken;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }
}
