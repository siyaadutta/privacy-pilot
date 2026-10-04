package com.privacypilot.service;

import com.privacypilot.domain.OutboxEmail;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Transactional-outbox delivery worker (claim / send / mark).
 *
 * <p><b>Ordering and transactions.</b> Each pass runs in three phases with
 * distinct transaction boundaries so that <em>no database lock is ever held while
 * calling SMTP</em>:
 * <ol>
 *   <li><b>Claim</b> (short txn, {@code OutboxStore.claim}): atomically flips a
 *       batch of deliverable rows to {@code PROCESSING} using
 *       {@code SELECT ... FOR UPDATE SKIP LOCKED}. Two instances therefore never
 *       claim the same row. The transaction commits and releases locks.</li>
 *   <li><b>Send</b> (no txn): calls {@link EmailSender} for each claimed row.</li>
 *   <li><b>Mark</b> (short txn per row): {@code markSent} or {@code markFailure}.</li>
 * </ol>
 *
 * <p><b>Delivery guarantee: at-least-once, not exactly-once.</b> If the process
 * crashes after SMTP accepted the message but before {@code markSent} commits, the
 * row stays {@code PROCESSING}; once its lease expires it is re-claimed and
 * <em>re-sent</em>. This is unavoidable without provider-side idempotency: an
 * outbox alone cannot make a database commit and an external send atomic. The
 * crash window is narrowed to "sent but not yet marked", and duplicates are capped
 * by {@code MAX_ATTEMPTS}.
 *
 * <p><b>Stale-claim overwrites are prevented by fencing.</b> Each claim stamps a
 * fresh {@code claim_token}; finalization is a conditional update that must match
 * that token. So if worker A's lease expires and worker B reclaims and finalizes a
 * row, A's late finalize matches zero rows and does nothing — it can never
 * overwrite B's outcome. The remaining honest limitation is therefore purely a
 * possible <em>duplicate SEND to the mail provider</em>, not a possible
 * <em>incorrect state overwrite</em>. True de-duplication of the send itself would
 * require passing a stable idempotency key to a provider that honors one
 * ({@code JavaMailSender}/SMTP does not).
 */
@Component
@ConditionalOnProperty(name = "privacypilot.outbox.worker-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxDeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxDeliveryWorker.class);
    private static final int BATCH_SIZE = 25;
    private static final int MAX_ATTEMPTS = 5;

    private final OutboxStore store;
    private final EmailSender emailSender;
    private final String workerId;
    private final Duration leaseDuration;

    public OutboxDeliveryWorker(OutboxStore store, EmailSender emailSender,
                                @Value("${privacypilot.outbox.lease-seconds:120}") long leaseSeconds) {
        this.store = store;
        this.emailSender = emailSender;
        this.leaseDuration = Duration.ofSeconds(leaseSeconds);
        // Stable-ish per-instance id for diagnostics (pid@host + random suffix).
        this.workerId = ManagementFactory.getRuntimeMXBean().getName()
                + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Scheduled(fixedDelayString = "${privacypilot.outbox.poll-ms:5000}")
    public void deliverPending() {
        int delivered = drainOnce();
        if (delivered > 0) {
            log.info("Outbox worker {} delivered {} email(s).", workerId, delivered);
        }
    }

    /**
     * Run one claim/send/mark pass. Returns the number of rows marked SENT.
     * Not {@code @Transactional}: it orchestrates the phased transactions above.
     */
    public int drainOnce() {
        List<OutboxEmail> claimed = store.claim(workerId, BATCH_SIZE, leaseDuration);
        int sent = 0;
        for (OutboxEmail row : claimed) {
            String token = row.getClaimToken();
            boolean ok;
            try {
                ok = emailSender.send(row.getSender(), row.getRecipient(),
                        row.getSubject(), row.getBody());
            } catch (RuntimeException e) {
                // Defensive: EmailSender already swallows failures, but never let a
                // send exception abort the whole batch or leave the row unmarked.
                ok = false;
            }
            if (ok) {
                boolean owned = store.markSent(row.getId(), token);
                if (owned) {
                    sent++;
                } else {
                    log.warn("Outbox row {} claim lost (token {}) before markSent; another worker "
                            + "finalized it. No-op.", row.getId(), token);
                }
            } else {
                boolean owned = store.markFailure(row.getId(), token, MAX_ATTEMPTS,
                        "Delivery attempt " + row.getAttempts() + " failed.");
                if (!owned) {
                    log.warn("Outbox row {} claim lost (token {}) before markFailure; another worker "
                            + "finalized it. No-op.", row.getId(), token);
                } else if (row.getAttempts() >= MAX_ATTEMPTS) {
                    log.warn("Outbox row {} (key={}) FAILED after {} attempts.",
                            row.getId(), row.getIdempotencyKey(), row.getAttempts());
                }
            }
        }
        return sent;
    }
}
