package com.privacypilot.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.privacypilot.domain.OutboxEmail;
import com.privacypilot.repository.OutboxEmailRepository;
import com.privacypilot.service.OutboxStore;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the outbox claim uses {@code SELECT ... FOR UPDATE SKIP LOCKED}
 * correctly: two workers claiming concurrently never grab the same row, and no
 * row is delivered twice as a result of claiming. Postgres-only behavior.
 */
class OutboxClaimSkipLockedIT extends AbstractPostgresIT {

    @Autowired private OutboxEmailRepository outbox;
    @Autowired private OutboxStore store;

    @Test
    void twoWorkersNeverClaimTheSameRow() throws Exception {
        outbox.deleteAll();
        int total = 40;
        for (int i = 0; i < total; i++) {
            outbox.save(new OutboxEmail("key-" + i, 1L, "from@x", "to@y", "s", "b"));
        }

        Callable<List<Long>> worker = () -> store.claim("w-" + Thread.currentThread().getId(),
                total, Duration.ofSeconds(120)).stream().map(OutboxEmail::getId).toList();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<List<Long>> f1 = pool.submit(worker);
        Future<List<Long>> f2 = pool.submit(worker);
        List<Long> claimed1 = f1.get();
        List<Long> claimed2 = f2.get();
        pool.shutdown();

        // No id appears in both workers' claims.
        Set<Long> intersection = ConcurrentHashMap.newKeySet();
        intersection.addAll(claimed1);
        intersection.retainAll(claimed2);
        assertThat(intersection).isEmpty();

        // Together they claim at most the available rows, with no duplicates overall.
        Set<Long> union = ConcurrentHashMap.newKeySet();
        union.addAll(claimed1);
        union.addAll(claimed2);
        assertThat(union).hasSize(claimed1.size() + claimed2.size());
        assertThat(union.size()).isLessThanOrEqualTo(total);
    }
}
