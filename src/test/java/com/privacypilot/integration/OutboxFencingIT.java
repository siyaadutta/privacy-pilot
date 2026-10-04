package com.privacypilot.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.privacypilot.domain.OutboxEmail;
import com.privacypilot.domain.OutboxStatus;
import com.privacypilot.repository.OutboxEmailRepository;
import com.privacypilot.service.OutboxStore;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the fenced conditional finalize: once a row's claim_token has been
 * superseded by a newer claim, a stale claim_token can no longer finalize it.
 * This verifies the compare-and-set predicate on the DB row directly (via a
 * manually-expired lease), not a live concurrent race where the original
 * worker is still mid-send when its lease expires — that scenario is
 * structurally untestable for its DB effect, since fencing only protects the
 * database, not an SMTP call already in flight. See OutboxDeliveryWorker's
 * javadoc: this system provides at-least-once delivery, not exactly-once.
 */
class OutboxFencingIT extends AbstractPostgresIT {

    @Autowired private OutboxEmailRepository outbox;
    @Autowired private OutboxStore store;

    @Test
    void staleWorkerCannotOverwriteReclaimedRow() {
        outbox.deleteAll();
        outbox.save(new OutboxEmail("fence-key-1", 1L, "from@x", "to@y", "s", "b"));

        // Worker A claims with a normal lease and captures its token.
        List<OutboxEmail> claimedByA = store.claim("worker-A", 10, Duration.ofSeconds(120));
        assertThat(claimedByA).hasSize(1);
        OutboxEmail rowA = claimedByA.get(0);
        String tokenA = rowA.getClaimToken();
        Long id = rowA.getId();
        assertThat(tokenA).isNotBlank();

        // Simulate A's lease expiring: rewind claimed_at far into the past.
        OutboxEmail persisted = outbox.findById(id).orElseThrow();
        persisted.setClaimedAt(java.time.Instant.now().minus(Duration.ofHours(1)));
        outbox.saveAndFlush(persisted);

        // Worker B reclaims the now-lease-expired row (fresh token) and marks SENT.
        List<OutboxEmail> claimedByB = store.claim("worker-B", 10, Duration.ofSeconds(120));
        assertThat(claimedByB).hasSize(1);
        OutboxEmail rowB = claimedByB.get(0);
        String tokenB = rowB.getClaimToken();
        assertThat(tokenB).isNotEqualTo(tokenA);
        boolean bOwned = store.markSent(id, tokenB);
        assertThat(bOwned).isTrue();

        // Worker A's stale finalize calls must be no-ops (claim lost to B).
        boolean aSentStale = store.markSent(id, tokenA);
        boolean aFailStale = store.markFailure(id, tokenA, 5, "stale failure from A");
        assertThat(aSentStale).isFalse();
        assertThat(aFailStale).isFalse();

        // Row remains SENT (B's outcome), not overwritten by A.
        OutboxEmail finalRow = outbox.findById(id).orElseThrow();
        assertThat(finalRow.getStatus()).isEqualTo(OutboxStatus.SENT);
    }
}
