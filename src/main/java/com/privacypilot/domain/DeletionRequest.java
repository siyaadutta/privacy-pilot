package com.privacypilot.domain;

import com.privacypilot.workflow.RequestStatus;
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
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * A single deletion request made by a user against a company.
 *
 * <p>The {@link #status} field is driven by the workflow state machine; it is
 * never set to an arbitrary value by controllers.
 */
@Entity
@Table(name = "deletion_requests")
public class DeletionRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Optimistic-lock version. Two concurrent transitions that both read the
     * same WAITING state will collide on commit: the second to commit fails with
     * an OptimisticLockException instead of silently overwriting the first. This
     * closes the read-then-write race the state machine alone cannot prevent.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private RequestStatus status = RequestStatus.DRAFT;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "last_action", length = 500)
    private String lastAction;

    @Column(name = "next_action", length = 500)
    private String nextAction;

    @Column(name = "follow_up_due_at")
    private Instant followUpDueAt;

    /** Number of follow-ups already enqueued; used to build stable idempotency keys. */
    @Column(name = "follow_up_count", nullable = false)
    private int followUpCount = 0;

    /** The generated, user-editable request text. */
    @Column(name = "request_body", length = 8000)
    private String requestBody;

    /**
     * Human-readable reason the request is awaiting user action (e.g. "AI
     * uncertain about a vague reply" vs "company requested identity
     * verification"). Kept distinct from the workflow status so the audit trail
     * never conflates genuine AI uncertainty with an actual verification demand.
     */
    @Column(name = "review_reason", length = 500)
    private String reviewReason;

    public DeletionRequest() {
    }

    public DeletionRequest(User user, Company company) {
        this.user = user;
        this.company = company;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getVersion() {
        return version;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Company getCompany() {
        return company;
    }

    public void setCompany(Company company) {
        this.company = company;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public void setStatus(RequestStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getLastAction() {
        return lastAction;
    }

    public void setLastAction(String lastAction) {
        this.lastAction = lastAction;
    }

    public String getNextAction() {
        return nextAction;
    }

    public void setNextAction(String nextAction) {
        this.nextAction = nextAction;
    }

    public Instant getFollowUpDueAt() {
        return followUpDueAt;
    }

    public void setFollowUpDueAt(Instant followUpDueAt) {
        this.followUpDueAt = followUpDueAt;
    }

    public int getFollowUpCount() {
        return followUpCount;
    }

    public void setFollowUpCount(int followUpCount) {
        this.followUpCount = followUpCount;
    }

    public String getRequestBody() {
        return requestBody;
    }

    public void setRequestBody(String requestBody) {
        this.requestBody = requestBody;
    }

    public String getReviewReason() {
        return reviewReason;
    }

    public void setReviewReason(String reviewReason) {
        this.reviewReason = reviewReason;
    }
}
