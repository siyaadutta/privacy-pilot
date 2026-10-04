package com.privacypilot.repository;

import com.privacypilot.domain.OutboxEmail;
import com.privacypilot.domain.OutboxStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEmailRepository extends JpaRepository<OutboxEmail, Long> {

    boolean existsByIdempotencyKey(String idempotencyKey);

    Optional<OutboxEmail> findByIdempotencyKey(String idempotencyKey);

    List<OutboxEmail> findByStatusOrderByCreatedAtAsc(OutboxStatus status, Pageable pageable);

    /**
     * Atomically claim up to {@code batchSize} deliverable rows for the given
     * worker, and return them.
     *
     * <p>A row is claimable if it is {@code PENDING}, or if it is {@code PROCESSING}
     * but its lease has expired (i.e. {@code claimed_at <= :leaseCutoff}) — the
     * latter recovers rows whose worker crashed mid-delivery.
     *
     * <p>Concurrency: the inner {@code SELECT ... FOR UPDATE SKIP LOCKED} takes
     * row locks and skips rows already locked by another instance, so two workers
     * never claim the same row. The claim flips status to {@code PROCESSING} and
     * stamps the lease inside one short transaction; the actual email send happens
     * <em>after</em> this transaction commits, so no DB lock is held across SMTP.
     *
     * <p>This is a Postgres-specific native query (SKIP LOCKED). It is exercised by
     * the Testcontainers integration tests, not by the H2 unit tests.
     */
    @Modifying
    @Query(value = """
            UPDATE outbox_emails
               SET status = 'PROCESSING',
                   claimed_at = :now,
                   claimed_by = :worker,
                   claim_token = :token,
                   attempts = attempts + 1
             WHERE id IN (
                   SELECT id FROM outbox_emails
                    WHERE status = 'PENDING'
                       OR (status = 'PROCESSING' AND claimed_at <= :leaseCutoff)
                    ORDER BY created_at ASC
                    FOR UPDATE SKIP LOCKED
                    LIMIT :batchSize
             )
            RETURNING *
            """, nativeQuery = true)
    List<OutboxEmail> claimBatch(@Param("worker") String worker,
                                 @Param("token") String token,
                                 @Param("now") Instant now,
                                 @Param("leaseCutoff") Instant leaseCutoff,
                                 @Param("batchSize") int batchSize);

    /**
     * Atomically finalize a claimed row as SENT, fenced by the claim token.
     * Returns the number of rows updated: 1 if this call still owns the claim,
     * 0 if the claim was lost (row reclaimed/finalized by another worker).
     */
    @Modifying
    @Query(value = """
            UPDATE outbox_emails
               SET status = 'SENT', sent_at = :now, last_error = NULL,
                   claimed_at = NULL, claimed_by = NULL, claim_token = NULL
             WHERE id = :id AND status = 'PROCESSING' AND claim_token = :token
            """, nativeQuery = true)
    int markSentFenced(@Param("id") Long id, @Param("token") String token, @Param("now") Instant now);

    /**
     * Atomically finalize a claimed row after a failed send, fenced by the claim
     * token: back to PENDING for retry, or FAILED if the attempt cap is reached.
     * Returns rows updated (0 if the claim was lost to another worker).
     */
    @Modifying
    @Query(value = """
            UPDATE outbox_emails
               SET status = CASE WHEN attempts >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
                   last_error = :error,
                   claimed_at = NULL, claimed_by = NULL, claim_token = NULL
             WHERE id = :id AND status = 'PROCESSING' AND claim_token = :token
            """, nativeQuery = true)
    int markFailureFenced(@Param("id") Long id, @Param("token") String token,
                          @Param("maxAttempts") int maxAttempts, @Param("error") String error);
}
