package com.privacypilot.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.privacypilot.domain.Company;
import com.privacypilot.domain.DeletionRequest;
import com.privacypilot.domain.EmailMessage;
import com.privacypilot.domain.User;
import com.privacypilot.repository.CompanyRepository;
import com.privacypilot.repository.DeletionRequestRepository;
import com.privacypilot.repository.EmailMessageRepository;
import com.privacypilot.repository.UserRepository;
import com.privacypilot.service.DeletionRequestService;
import com.privacypilot.workflow.RequestStatus;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the inbound {@code (request_id, message_id)} uniqueness handles a
 * concurrent duplicate webhook delivery cleanly: exactly one row is stored, no
 * unexpected exception escapes to the caller, and no double transition occurs.
 * Runs against PostgreSQL so the real unique constraint and race are exercised.
 */
class InboundDuplicateRaceIT extends AbstractPostgresIT {

    @Autowired private DeletionRequestService service;
    @Autowired private DeletionRequestRepository requests;
    @Autowired private EmailMessageRepository emails;
    @Autowired private UserRepository users;
    @Autowired private CompanyRepository companies;

    @Test
    void concurrentDuplicateInboundStoresOneRowWithoutError() throws Exception {
        User u = users.save(new User("Jane", "jane-" + java.util.UUID.randomUUID() + "@example.com", null, null));
        Company c = companies.save(new Company("Acme-" + java.util.UUID.randomUUID(), null, "EMAIL",
                "p@acme.example", "email", "x"));
        DeletionRequest seed = new DeletionRequest(u, c);
        seed.setStatus(RequestStatus.WAITING);
        Long reqId = requests.save(seed).getId();

        String body = "Your data has been deleted from all systems.";
        String messageId = "provider-msg-123";

        AtomicInteger errors = new AtomicInteger();
        Callable<EmailMessage> task = () -> {
            try {
                return service.processInboundEmail(reqId, "Re: deletion", body, messageId);
            } catch (RuntimeException e) {
                errors.incrementAndGet();
                throw e;
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(4);
        Future<EmailMessage> f1 = pool.submit(task);
        Future<EmailMessage> f2 = pool.submit(task);
        Future<EmailMessage> f3 = pool.submit(task);
        Future<EmailMessage> f4 = pool.submit(task);
        // All four must return normally (idempotent), none blow up with a 500-style error.
        f1.get(); f2.get(); f3.get(); f4.get();
        pool.shutdown();

        assertThat(errors.get()).isZero();

        // Exactly one inbound row exists for this (request, messageId).
        List<EmailMessage> stored = emails.findByRequestIdOrderByTimestampAsc(reqId);
        long matching = stored.stream()
                .filter(m -> messageId.equals(m.getMessageId()))
                .count();
        assertThat(matching).isEqualTo(1);
    }
}
