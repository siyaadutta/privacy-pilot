package com.privacypilot.service;

import com.privacypilot.ai.ClassificationResult;
import com.privacypilot.domain.DeletionRequest;
import com.privacypilot.domain.EmailDirection;
import com.privacypilot.domain.EmailMessage;
import com.privacypilot.repository.EmailMessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists an inbound email row in its own transaction so the
 * {@code (request_id, message_id)} uniqueness race can be resolved cleanly.
 *
 * <p>If two duplicate webhook deliveries race past the fast-path dedupe check,
 * both attempt the INSERT; the DB unique constraint lets exactly one win. The
 * loser catches {@link DataIntegrityViolationException} <em>inside this nested
 * transaction</em> (so only this transaction is marked rollback-only, not the
 * caller's), then re-reads and returns the winner's row with {@code created=false}.
 * The caller thus never sees a 500 and never applies a second workflow transition.
 */
@Component
public class InboundEmailWriter {

    private static final Logger log = LoggerFactory.getLogger(InboundEmailWriter.class);

    private final EmailMessageRepository emails;
    private final ObjectMapper mapper;

    public InboundEmailWriter(EmailMessageRepository emails, ObjectMapper mapper) {
        this.emails = emails;
        this.mapper = mapper;
    }

    /**
     * Insert the inbound row in a nested transaction and return it.
     *
     * <p>On a concurrent-duplicate {@code (request_id, message_id)} race the flush
     * throws {@link DataIntegrityViolationException}; we let it propagate so Spring
     * rolls back <em>this</em> nested transaction cleanly. The caller catches it
     * (its own transaction is unaffected) and re-reads the winner's row. We
     * deliberately do not catch-and-read here — reading in a transaction already
     * marked rollback-only triggers "don't flush after an exception".
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EmailMessage persist(DeletionRequest req, String subject, String body,
                                String messageId, ClassificationResult result) {
        EmailMessage inbound = new EmailMessage(req, EmailDirection.INBOUND, subject, body);
        inbound.setMessageId(messageId);
        inbound.setClassification(result.classification());
        inbound.setClassificationConfidence(result.confidence());
        inbound.setRawLlmOutput(result.rawOutput());
        inbound.setExtractedInfo(writeExtractedInfo(result));
        return emails.saveAndFlush(inbound);
    }

    private String writeExtractedInfo(ClassificationResult r) {
        try {
            var node = mapper.createObjectNode();
            node.put("classification", r.classification().name());
            node.put("confidence", r.confidence());
            node.put("ambiguous", r.ambiguous());
            node.put("source", r.source());
            if (r.referenceNumber() != null) node.put("referenceNumber", r.referenceNumber());
            if (r.deadline() != null) node.put("deadline", r.deadline());
            if (r.requiredAction() != null) node.put("requiredAction", r.requiredAction());
            if (r.rejectionReason() != null) node.put("rejectionReason", r.rejectionReason());
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }
}
