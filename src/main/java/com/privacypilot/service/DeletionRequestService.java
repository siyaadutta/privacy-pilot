package com.privacypilot.service;

import com.privacypilot.ai.ClassificationResult;
import com.privacypilot.ai.RequestGeneratorService;
import com.privacypilot.ai.ResponseClassifierService;
import com.privacypilot.domain.Company;
import com.privacypilot.domain.DeletionRequest;
import com.privacypilot.domain.EmailDirection;
import com.privacypilot.domain.EmailMessage;
import com.privacypilot.domain.OutboxEmail;
import com.privacypilot.domain.User;
import com.privacypilot.llm.ClassifierProperties;
import com.privacypilot.repository.CompanyRepository;
import com.privacypilot.repository.DeletionRequestRepository;
import com.privacypilot.repository.EmailMessageRepository;
import com.privacypilot.repository.OutboxEmailRepository;
import com.privacypilot.repository.UserRepository;
import com.privacypilot.scheduler.FollowUpProperties;
import com.privacypilot.web.NotFoundException;
import com.privacypilot.workflow.Classification;
import com.privacypilot.workflow.RequestStatus;
import com.privacypilot.workflow.WorkflowStateMachine;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the deletion-request lifecycle. All status changes go through the
 * {@link WorkflowStateMachine} so illegal jumps are rejected centrally.
 *
 * <p>Confidence-based fallback (the project's key design decision) lives in
 * {@link #processInboundEmail}: when the classifier's confidence is below the
 * configured threshold or the reply is flagged ambiguous, the request is routed
 * to {@code USER_ACTION_REQUIRED} instead of being forced into a category.
 *
 * <p>Correctness under failure/retry/concurrency:
 * <ul>
 *   <li><b>Outbox:</b> outbound email is never sent inside the DB transaction.
 *       {@link #send} and {@link #followUp} persist a workflow-state change plus a
 *       durable {@link OutboxEmail} (with a unique idempotency key) atomically; an
 *       out-of-band worker delivers after commit. A rollback therefore cannot
 *       leave the DB disagreeing with what the company received, and a retry
 *       cannot enqueue a duplicate send.</li>
 *   <li><b>Optimistic locking:</b> {@code DeletionRequest} carries a
 *       {@code @Version}, so two concurrent transitions from the same observed
 *       state collide on commit instead of silently overwriting each other.</li>
 *   <li><b>Inbound idempotency:</b> {@link #processInboundEmail} is a no-op if a
 *       message with the same {@code (requestId, messageId)} was already
 *       processed.</li>
 * </ul>
 */
@Service
public class DeletionRequestService {

    private static final Logger log = LoggerFactory.getLogger(DeletionRequestService.class);

    private final DeletionRequestRepository requests;
    private final UserRepository users;
    private final CompanyRepository companies;
    private final EmailMessageRepository emails;
    private final WorkflowStateMachine stateMachine;
    private final RequestGeneratorService generator;
    private final ResponseClassifierService classifier;
    private final ClassifierProperties classifierProps;
    private final FollowUpProperties followUpProps;
    private final OutboxEmailRepository outbox;
    private final InboundEmailWriter inboundWriter;
    private final ObjectMapper mapper;

    public DeletionRequestService(DeletionRequestRepository requests,
                                  UserRepository users,
                                  CompanyRepository companies,
                                  EmailMessageRepository emails,
                                  WorkflowStateMachine stateMachine,
                                  RequestGeneratorService generator,
                                  ResponseClassifierService classifier,
                                  ClassifierProperties classifierProps,
                                  FollowUpProperties followUpProps,
                                  OutboxEmailRepository outbox,
                                  InboundEmailWriter inboundWriter,
                                  ObjectMapper mapper) {
        this.requests = requests;
        this.users = users;
        this.companies = companies;
        this.emails = emails;
        this.stateMachine = stateMachine;
        this.generator = generator;
        this.classifier = classifier;
        this.classifierProps = classifierProps;
        this.followUpProps = followUpProps;
        this.outbox = outbox;
        this.inboundWriter = inboundWriter;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<DeletionRequest> list() {
        return requests.findAll();
    }

    @Transactional(readOnly = true)
    public DeletionRequest get(Long id) {
        return requests.findById(id)
                .orElseThrow(() -> new NotFoundException("Deletion request " + id + " not found"));
    }

    /**
     * Create a DRAFT request and generate its editable body (AI function #2).
     */
    @Transactional
    public DeletionRequest create(Long userId, Long companyId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("User " + userId + " not found"));
        Company company = companies.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company " + companyId + " not found"));

        DeletionRequest req = new DeletionRequest(user, company);
        req.setStatus(RequestStatus.DRAFT);
        req.setRequestBody(generator.generate(user, company));
        req.setLastAction("Draft created");
        req.setNextAction("Review and approve the request");
        touch(req);
        return requests.save(req);
    }

    /** DRAFT -> APPROVED (human approval, required before sending). */
    @Transactional
    public DeletionRequest approve(Long id) {
        DeletionRequest req = get(id);
        transition(req, RequestStatus.APPROVED, "Request approved by user",
                "Send the request to the company");
        return requests.save(req);
    }

    /** APPROVED -> SENT (enqueues the email via the outbox, then moves into WAITING). */
    @Transactional
    public DeletionRequest send(Long id) {
        DeletionRequest req = get(id);
        transition(req, RequestStatus.SENT, "Request sent to company", "Await company response");

        // Record the outbound message and enqueue delivery in the SAME transaction.
        // Nothing is actually emailed here; the outbox worker delivers after commit.
        String subject = "Request for Deletion of Personal Data";
        emails.save(new EmailMessage(req, EmailDirection.OUTBOUND, subject, req.getRequestBody()));
        enqueueEmail("deletion-request-" + req.getId() + "-initial", req, subject, req.getRequestBody());

        // Move straight into WAITING and arm the follow-up clock.
        transition(req, RequestStatus.WAITING, "Awaiting company response",
                "Monitor inbox; follow up if no reply");
        req.setFollowUpDueAt(Instant.now().plus(followUpProps.getWaitWindowHours(), ChronoUnit.HOURS));
        return requests.save(req);
    }

    /**
     * Manually trigger a follow-up (WAITING/FOLLOW_UP_DUE -> FOLLOW_UP_SENT -> WAITING).
     */
    @Transactional
    public DeletionRequest followUp(Long id) {
        DeletionRequest req = get(id);
        if (req.getStatus() == RequestStatus.WAITING) {
            transition(req, RequestStatus.FOLLOW_UP_DUE, "Follow-up due", "Send follow-up");
        }
        if (req.getStatus() == RequestStatus.FOLLOW_UP_DUE) {
            transition(req, RequestStatus.FOLLOW_UP_SENT, "Follow-up sent", "Await company response");
            String subject = "Follow-up: Request for Deletion of Personal Data";
            emails.save(new EmailMessage(req, EmailDirection.OUTBOUND, subject, req.getRequestBody()));
            // Idempotency key incorporates the follow-up count so each distinct
            // follow-up is enqueued exactly once, but a retry of THIS follow-up
            // (same count) cannot enqueue a duplicate.
            int followUpNumber = req.getFollowUpCount() + 1;
            req.setFollowUpCount(followUpNumber);
            enqueueEmail("deletion-request-" + req.getId() + "-followup-" + followUpNumber,
                    req, subject, req.getRequestBody());
            transition(req, RequestStatus.WAITING, "Awaiting company response after follow-up",
                    "Monitor inbox");
            req.setFollowUpDueAt(Instant.now().plus(followUpProps.getWaitWindowHours(), ChronoUnit.HOURS));
        }
        return requests.save(req);
    }

    /**
     * Enqueue an outbound email into the transactional outbox, guarded by its
     * idempotency key so a retry cannot create a duplicate send.
     */
    private void enqueueEmail(String idempotencyKey, DeletionRequest req, String subject, String body) {
        if (outbox.existsByIdempotencyKey(idempotencyKey)) {
            log.info("Outbox already has key {}; skipping duplicate enqueue.", idempotencyKey);
            return;
        }
        outbox.save(new OutboxEmail(idempotencyKey, req.getId(),
                followUpProps.getSenderEmail(), req.getCompany().getPrivacyEmail(), subject, body));
    }

    /**
     * Process an inbound company reply (AI function #3 + confidence-based routing).
     *
     * <p>The classifier returns a category, a confidence, and an ambiguity flag.
     * If confidence is below the threshold or the reply is ambiguous, we do NOT
     * force a category — the request goes to USER_ACTION_REQUIRED and the raw
     * classifier output + confidence are stored on the email row for human review.
     *
     * <p>Idempotent: if a message with the same {@code (requestId, messageId)}
     * was already processed (duplicate webhook/redelivery), the existing row is
     * returned and no second transition occurs.
     */
    @Transactional
    public EmailMessage processInboundEmail(Long requestId, String subject, String body, String messageId) {
        DeletionRequest req = get(requestId);

        // Fast-path dedupe for the common (sequential) redelivery case.
        if (messageId != null && !messageId.isBlank()) {
            var existing = emails.findByRequestIdAndMessageId(requestId, messageId);
            if (existing.isPresent()) {
                log.info("Duplicate inbound message for request {}; already processed.", requestId);
                return existing.get();
            }
        }

        ClassificationResult result = classifier.classify(body);

        // Persist in a nested transaction so a concurrent-duplicate unique-constraint
        // race is resolved to an idempotent success instead of a 500. If we did not
        // create the row (a concurrent duplicate won), do not transition again.
        // Persist in a nested transaction so a concurrent-duplicate unique-constraint
        // race rolls back only that nested transaction. If it loses the race we
        // re-read the winner's row here (this outer transaction stays clean) and do
        // not transition again.
        EmailMessage inbound;
        try {
            inbound = inboundWriter.persist(req, subject, body, messageId, result);
        } catch (DataIntegrityViolationException dup) {
            log.info("Concurrent duplicate inbound message for request {}; returning existing row.",
                    requestId);
            return emails.findByRequestIdAndMessageId(requestId, messageId).orElseThrow(() -> dup);
        }

        boolean lowConfidence = result.confidence() < classifierProps.getConfidenceThreshold();
        boolean routeToHuman = result.ambiguous()
                || lowConfidence
                || result.classification() == Classification.UNKNOWN;

        // The request must be in WAITING to interpret a reply; if not, record only.
        if (req.getStatus() != RequestStatus.WAITING) {
            log.info("Inbound email for request {} in status {} recorded without transition.",
                    requestId, req.getStatus());
            return inbound;
        }

        if (routeToHuman) {
            // Genuine uncertainty routes directly WAITING -> USER_ACTION_REQUIRED
            // with an accurate reviewReason; it is never relabeled as a
            // verification request.
            routeToUserAction(req, result);
        } else {
            applyConfidentClassification(req, result);
        }
        requests.save(req);
        return inbound;
    }

    private void routeToUserAction(DeletionRequest req, ClassificationResult result) {
        // Genuine AI uncertainty uses the direct WAITING -> USER_ACTION_REQUIRED
        // edge. We deliberately do NOT route through VERIFICATION_REQUIRED, which
        // would falsely imply the company asked for identity verification.
        String reason = uncertaintyReason(result);
        req.setReviewReason(reason);
        transition(req, RequestStatus.USER_ACTION_REQUIRED,
                "Company reply flagged for human review (confidence " + fmt(result.confidence()) + ")",
                "AI uncertain — needs human review");
    }

    /** Explain, for the audit trail, why the classifier was not trusted. */
    private String uncertaintyReason(ClassificationResult result) {
        if (result.ambiguous()) {
            return "AI flagged the reply as ambiguous or conflicting.";
        }
        if (result.classification() == Classification.UNKNOWN) {
            return "AI could not confidently place the reply in any category.";
        }
        return "AI confidence (" + fmt(result.confidence()) + ") below threshold ("
                + fmt(classifierProps.getConfidenceThreshold()) + ") for provisional class "
                + result.classification() + ".";
    }

    private void applyConfidentClassification(DeletionRequest req, ClassificationResult result) {
        switch (result.classification()) {
            case COMPLETED -> transition(req, RequestStatus.COMPLETED,
                    "Company confirmed deletion", "None — request complete");
            case REJECTED -> transition(req, RequestStatus.REJECTED,
                    "Company rejected the request", "Review rejection reason");
            case VERIFICATION_REQUIRED -> {
                req.setReviewReason("Company requested identity verification.");
                transition(req, RequestStatus.VERIFICATION_REQUIRED,
                        "Company requires identity verification", "Provide verification");
                transition(req, RequestStatus.USER_ACTION_REQUIRED,
                        "Verification requested", "Provide requested verification");
            }
            case MORE_INFORMATION_REQUIRED -> {
                req.setReviewReason("Company requested additional information.");
                transition(req, RequestStatus.MORE_INFORMATION_REQUIRED,
                        "Company requires more information", "Provide more information");
                transition(req, RequestStatus.USER_ACTION_REQUIRED,
                        "More information requested", "Provide requested information");
            }
            case RECEIVED -> {
                // Acknowledgement only — stay in WAITING, refresh the follow-up clock.
                req.setLastAction("Company acknowledged receipt");
                req.setNextAction("Await outcome");
                req.setFollowUpDueAt(Instant.now()
                        .plus(followUpProps.getWaitWindowHours(), ChronoUnit.HOURS));
                touch(req);
            }
            case UNKNOWN -> routeToUserAction(req, result); // defensive; normally handled above
        }
    }

    /** Apply a validated transition and update bookkeeping fields. */
    private void transition(DeletionRequest req, RequestStatus to, String lastAction, String nextAction) {
        stateMachine.validateTransition(req.getStatus(), to);
        req.setStatus(to);
        req.setLastAction(lastAction);
        req.setNextAction(nextAction);
        touch(req);
    }

    private void touch(DeletionRequest req) {
        req.setUpdatedAt(Instant.now());
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }
}
