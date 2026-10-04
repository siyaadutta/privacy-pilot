package com.privacypilot.domain;

import com.privacypilot.workflow.Classification;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * An email message associated with a deletion request (either sent or received).
 *
 * <p>For inbound messages that were classified, the classifier's raw JSON output
 * and confidence are stored so a human reviewer can see exactly why a message was
 * flagged for review.
 *
 * <p>The unique constraint on {@code (request_id, message_id)} is the database
 * backstop for inbound idempotency: a mail provider that redelivers the same
 * message cannot create two rows for the same {@code (request, message_id)}.
 * (Postgres treats NULL message_ids as distinct, so outbound/id-less rows are
 * unaffected.)
 */
@Entity
@Table(name = "email_messages",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_email_request_message",
                columnNames = {"request_id", "message_id"}))
public class EmailMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private DeletionRequest request;

    @Column(name = "message_id")
    private String messageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EmailDirection direction;

    private String subject;

    @Column(length = 8000)
    private String body;

    @Column(nullable = false)
    private Instant timestamp = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private Classification classification;

    /** Extracted structured info from the classifier, stored as JSON text. */
    @Column(name = "extracted_info", length = 4000)
    private String extractedInfo;

    /** Raw LLM output, retained for auditability especially when flagged for review. */
    @Column(name = "raw_llm_output", length = 8000)
    private String rawLlmOutput;

    /** Classifier confidence 0..1 for inbound classified messages. */
    @Column(name = "classification_confidence")
    private Double classificationConfidence;

    public EmailMessage() {
    }

    public EmailMessage(DeletionRequest request, EmailDirection direction, String subject, String body) {
        this.request = request;
        this.direction = direction;
        this.subject = subject;
        this.body = body;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public DeletionRequest getRequest() {
        return request;
    }

    public void setRequest(DeletionRequest request) {
        this.request = request;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public EmailDirection getDirection() {
        return direction;
    }

    public void setDirection(EmailDirection direction) {
        this.direction = direction;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public Classification getClassification() {
        return classification;
    }

    public void setClassification(Classification classification) {
        this.classification = classification;
    }

    public String getExtractedInfo() {
        return extractedInfo;
    }

    public void setExtractedInfo(String extractedInfo) {
        this.extractedInfo = extractedInfo;
    }

    public String getRawLlmOutput() {
        return rawLlmOutput;
    }

    public void setRawLlmOutput(String rawLlmOutput) {
        this.rawLlmOutput = rawLlmOutput;
    }

    public Double getClassificationConfidence() {
        return classificationConfidence;
    }

    public void setClassificationConfidence(Double classificationConfidence) {
        this.classificationConfidence = classificationConfidence;
    }
}
