package com.privacypilot.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.privacypilot.domain.Company;
import com.privacypilot.domain.DeletionRequest;
import com.privacypilot.domain.User;
import com.privacypilot.repository.CompanyRepository;
import com.privacypilot.repository.DeletionRequestRepository;
import com.privacypilot.repository.UserRepository;
import com.privacypilot.workflow.RequestStatus;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the {@code @Version} optimistic lock actually detects a lost update:
 * two concurrent transactions both read the same WAITING request and attempt
 * different transitions; exactly one commits, the other fails with an
 * optimistic-lock exception. This is the real concurrency guarantee the state
 * machine alone cannot provide — verified against PostgreSQL, not mocks.
 */
class OptimisticLockConcurrencyIT extends AbstractPostgresIT {

    @Autowired private DeletionRequestRepository requests;
    @Autowired private UserRepository users;
    @Autowired private CompanyRepository companies;
    @Autowired private TransactionTemplate txTemplate;

    @Test
    void concurrentTransitionsFromSameStateConflict() throws Exception {
        User u = users.save(new User("Jane", "jane-" + java.util.UUID.randomUUID() + "@example.com", null, null));
        Company c = companies.save(new Company("Acme-" + java.util.UUID.randomUUID(), null, "EMAIL",
                "p@acme.example", "email", "x"));
        DeletionRequest seed = new DeletionRequest(u, c);
        seed.setStatus(RequestStatus.WAITING);
        Long id = requests.save(seed).getId();

        CyclicBarrier bothLoaded = new CyclicBarrier(2);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Runnable a = transitionTask(id, RequestStatus.COMPLETED, bothLoaded, successes, conflicts);
        Runnable b = transitionTask(id, RequestStatus.REJECTED, bothLoaded, successes, conflicts);
        Future<?> fa = pool.submit(a);
        Future<?> fb = pool.submit(b);
        fa.get();
        fb.get();
        pool.shutdown();

        // Exactly one transaction wins; the other observes an optimistic-lock conflict.
        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);

        RequestStatus finalStatus = requests.findById(id).orElseThrow().getStatus();
        assertThat(finalStatus).isIn(RequestStatus.COMPLETED, RequestStatus.REJECTED);
    }

    private Runnable transitionTask(Long id, RequestStatus target, CyclicBarrier barrier,
                                    AtomicInteger successes, AtomicInteger conflicts) {
        return () -> {
            try {
                txTemplate.executeWithoutResult(status -> {
                    DeletionRequest r = requests.findById(id).orElseThrow();
                    // Both threads read version=N here.
                    await(barrier);
                    r.setStatus(target);
                    requests.saveAndFlush(r); // second flush hits the version conflict
                });
                successes.incrementAndGet();
            } catch (OptimisticLockingFailureException e) {
                conflicts.incrementAndGet();
            }
        };
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
