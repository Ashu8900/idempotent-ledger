package com.fintech.ledger.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    public enum Status {
        PENDING, PROCESSED, FAILED
    }

    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "webhook_url", nullable = false, length = 500)
    private String webhookUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "next_retry_at", nullable = false)
    private OffsetDateTime nextRetryAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected OutboxEvent() {}

    public OutboxEvent(String aggregateType, UUID aggregateId, String eventType, String payload, String webhookUrl) {
        this.id = UUID.randomUUID();
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.webhookUrl = webhookUrl;
        this.status = Status.PENDING;
        this.retryCount = 0;
        this.nextRetryAt = OffsetDateTime.now();
        this.createdAt = this.nextRetryAt;
    }

    public void markProcessed() {
        this.status = Status.PROCESSED;
    }

    public void scheduleRetry(int maxRetries) {
        this.retryCount++;
        if (this.retryCount >= maxRetries) {
            this.status = Status.FAILED;
        } else {
            // Exponential backoff: 2s, 4s, 8s, 16s...
            long delaySeconds = (long) Math.pow(2, this.retryCount);
            this.nextRetryAt = OffsetDateTime.now().plusSeconds(delaySeconds);
        }
    }

    public UUID getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public UUID getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public String getWebhookUrl() { return webhookUrl; }
    public Status getStatus() { return status; }
    public int getRetryCount() { return retryCount; }
}