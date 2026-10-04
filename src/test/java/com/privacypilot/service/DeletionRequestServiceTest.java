package com.privacypilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.privacypilot.ai.ClassificationResult;
import com.privacypilot.ai.RequestGeneratorService;
import com.privacypilot.ai.ResponseClassifierService;
import com.privacypilot.domain.Company;
import com.privacypilot.domain.DeletionRequest;
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
import com.privacypilot.workflow.Classification;
import com.privacypilot.workflow.RequestStatus;
import com.privacypilot.workflow.WorkflowStateMachine;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeletionRequestServiceTest {

    @Mock private DeletionRequestRepository requests;
    @Mock private UserRepository users;
    @Mock private CompanyRepository companies;
    @Mock private EmailMessageRepository emails;
    @Mock private RequestGeneratorService generator;
    @Mock private ResponseClassifierService classifier;
    @Mock private OutboxEmailRepository outbox;
    @Mock private InboundEmailWriter inboundWriter;

    private final WorkflowStateMachine stateMachine = new WorkflowStateMachine();
    private final ClassifierProperties classifierProps = new ClassifierProperties();
    private final FollowUpProperties followUpProps = new FollowUpProperties();
    private final ObjectMapper mapper = new ObjectMapper();

    private DeletionRequestService service;
    private DeletionRequest waitingRequest;

    @BeforeEach
    void setup() {
        classifierProps.setConfidenceThreshold(0.6);
        service = new DeletionRequestService(requests, users, companies, emails, stateMachine,
                generator, classifier, classifierProps, followUpProps, outbox, inboundWriter, mapper);

        User user = new User("Jane", "jane@example.com", null, null);
        Company company = new Company("Acme", null, "EMAIL", "privacy@acme.example", "email", "x");
        waitingRequest = new DeletionRequest(user, company);
        waitingRequest.setId(1L);
        waitingRequest.setStatus(RequestStatus.WAITING);

        when(requests.findById(1L)).thenReturn(Optional.of(waitingRequest));
        when(requests.save(any(DeletionRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        when(emails.save(any(EmailMessage.class))).thenAnswer(inv -> {
            EmailMessage m = inv.getArgument(0);
            m.setId(99L);
            return m;
        });
        // By default the writer creates a new inbound row echoing the classification.
        when(inboundWriter.persist(any(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    DeletionRequest r = inv.getArgument(0);
                    ClassificationResult res = inv.getArgument(4);
                    EmailMessage m = new EmailMessage(r, com.privacypilot.domain.EmailDirection.INBOUND,
                            inv.getArgument(1), inv.getArgument(2));
                    m.setId(99L);
                    m.setMessageId(inv.getArgument(3));
                    m.setClassification(res.classification());
                    m.setClassificationConfidence(res.confidence());
                    m.setRawLlmOutput(res.rawOutput());
                    m.setExtractedInfo("{\"confidence\":" + res.confidence() + "}");
                    return m;
                });
    }

    @Test
    void lowConfidenceReplyRoutesToHumanReview() {
        when(classifier.classify(anyString())).thenReturn(new ClassificationResult(
                Classification.COMPLETED, 0.40, false, null, null, null, null, "{raw}", "LLM"));

        service.processInboundEmail(1L, "Re: deletion", "ambiguous body", "m1");

        assertThat(waitingRequest.getStatus()).isEqualTo(RequestStatus.USER_ACTION_REQUIRED);
        assertThat(waitingRequest.getNextAction()).contains("human review");
        // Uncertainty must NOT be mislabeled as an identity-verification request.
        assertThat(waitingRequest.getReviewReason()).doesNotContainIgnoringCase("verification");
        assertThat(waitingRequest.getReviewReason()).containsIgnoringCase("confidence");
    }

    @Test
    void ambiguousReplySetsAmbiguityReasonNotVerification() {
        when(classifier.classify(anyString())).thenReturn(new ClassificationResult(
                Classification.UNKNOWN, 0.30, true, null, null, null, null, "{raw}", "LLM"));

        service.processInboundEmail(1L, "?", "vague", "m1b");

        assertThat(waitingRequest.getStatus()).isEqualTo(RequestStatus.USER_ACTION_REQUIRED);
        assertThat(waitingRequest.getReviewReason()).containsIgnoringCase("ambiguous");
        assertThat(waitingRequest.getReviewReason()).doesNotContainIgnoringCase("verification");
    }

    @Test
    void genuineVerificationReplyRecordsVerificationReason() {
        when(classifier.classify(anyString())).thenReturn(new ClassificationResult(
                Classification.VERIFICATION_REQUIRED, 0.90, false, null, null, "Send ID", null, "{raw}", "LLM"));

        service.processInboundEmail(1L, "Verify", "please confirm your identity", "mv");

        assertThat(waitingRequest.getStatus()).isEqualTo(RequestStatus.USER_ACTION_REQUIRED);
        // A real verification demand SHOULD say so — this is the distinction the
        // audit-trail fix preserves.
        assertThat(waitingRequest.getReviewReason()).containsIgnoringCase("verification");
    }

    @Test
    void ambiguousFlagRoutesToHumanReviewEvenWithHighConfidence() {
        when(classifier.classify(anyString())).thenReturn(new ClassificationResult(
                Classification.COMPLETED, 0.95, true, null, null, null, null, "{raw}", "LLM"));

        service.processInboundEmail(1L, "Re: deletion", "conflicting body", "m2");

        assertThat(waitingRequest.getStatus()).isEqualTo(RequestStatus.USER_ACTION_REQUIRED);
    }

    @Test
    void confidentCompletionCompletesRequest() {
        when(classifier.classify(anyString())).thenReturn(new ClassificationResult(
                Classification.COMPLETED, 0.92, false, "REF1", null, null, null, "{raw}", "LLM"));

        service.processInboundEmail(1L, "Done", "your data was deleted", "m3");

        assertThat(waitingRequest.getStatus()).isEqualTo(RequestStatus.COMPLETED);
    }

    @Test
    void confidentRejectionRejectsRequest() {
        when(classifier.classify(anyString())).thenReturn(new ClassificationResult(
                Classification.REJECTED, 0.88, false, null, null, null, "legal hold", "{raw}", "LLM"));

        service.processInboundEmail(1L, "No", "we cannot delete", "m4");

        assertThat(waitingRequest.getStatus()).isEqualTo(RequestStatus.REJECTED);
    }

    @Test
    void rawOutputAndConfidenceStoredOnEmailRow() {
        when(classifier.classify(anyString())).thenReturn(new ClassificationResult(
                Classification.UNKNOWN, 0.30, true, null, null, null, null, "RAW_LLM_TEXT", "LLM"));

        EmailMessage saved = service.processInboundEmail(1L, "?", "huh", "m5");

        assertThat(saved.getRawLlmOutput()).isEqualTo("RAW_LLM_TEXT");
        assertThat(saved.getClassificationConfidence()).isEqualTo(0.30);
        assertThat(saved.getClassification()).isEqualTo(Classification.UNKNOWN);
        assertThat(saved.getExtractedInfo()).contains("confidence");
    }

    // --- Outbox / idempotency / concurrency safety ---

    @Test
    void sendEnqueuesOutboxRowInsteadOfSendingInline() {
        DeletionRequest approved = new DeletionRequest(
                new User("Jane", "jane@example.com", null, null),
                new Company("Acme", null, "EMAIL", "privacy@acme.example", "email", "x"));
        approved.setId(2L);
        approved.setStatus(RequestStatus.APPROVED);
        approved.setRequestBody("Please delete my data.");
        when(requests.findById(2L)).thenReturn(Optional.of(approved));
        when(outbox.existsByIdempotencyKey("deletion-request-2-initial")).thenReturn(false);

        service.send(2L);

        // A durable outbox row is written with the deterministic idempotency key;
        // no email is sent inline inside the transaction.
        ArgumentCaptor<OutboxEmail> captor = ArgumentCaptor.forClass(OutboxEmail.class);
        verify(outbox).save(captor.capture());
        assertThat(captor.getValue().getIdempotencyKey()).isEqualTo("deletion-request-2-initial");
        assertThat(captor.getValue().getRecipient()).isEqualTo("privacy@acme.example");
        assertThat(approved.getStatus()).isEqualTo(RequestStatus.WAITING);
    }

    @Test
    void sendDoesNotDoubleEnqueueWhenKeyAlreadyExists() {
        DeletionRequest approved = new DeletionRequest(
                new User("Jane", "jane@example.com", null, null),
                new Company("Acme", null, "EMAIL", "privacy@acme.example", "email", "x"));
        approved.setId(3L);
        approved.setStatus(RequestStatus.APPROVED);
        when(requests.findById(3L)).thenReturn(Optional.of(approved));
        when(outbox.existsByIdempotencyKey("deletion-request-3-initial")).thenReturn(true);

        service.send(3L);

        // Retry-safety: key already present -> no new outbox row.
        verify(outbox, never()).save(any(OutboxEmail.class));
    }

    @Test
    void followUpEnqueuesNumberedIdempotencyKey() {
        when(outbox.existsByIdempotencyKey(anyString())).thenReturn(false);

        service.followUp(1L); // waitingRequest starts at WAITING, followUpCount 0

        ArgumentCaptor<OutboxEmail> captor = ArgumentCaptor.forClass(OutboxEmail.class);
        verify(outbox).save(captor.capture());
        assertThat(captor.getValue().getIdempotencyKey()).isEqualTo("deletion-request-1-followup-1");
        assertThat(waitingRequest.getFollowUpCount()).isEqualTo(1);
        assertThat(waitingRequest.getStatus()).isEqualTo(RequestStatus.WAITING);
    }

    @Test
    void duplicateInboundMessageIsIdempotentNoSecondTransition() {
        EmailMessage already = new EmailMessage(waitingRequest, com.privacypilot.domain.EmailDirection.INBOUND,
                "dup", "body");
        already.setId(500L);
        when(emails.findByRequestIdAndMessageId(1L, "dup-1")).thenReturn(Optional.of(already));

        EmailMessage result = service.processInboundEmail(1L, "dup", "body", "dup-1");

        // Returns the existing row; classifier never invoked; status unchanged.
        assertThat(result.getId()).isEqualTo(500L);
        verify(classifier, never()).classify(anyString());
        assertThat(waitingRequest.getStatus()).isEqualTo(RequestStatus.WAITING);
    }
}
