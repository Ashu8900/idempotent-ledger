package com.fintech.ledger.worker;

import com.fintech.ledger.domain.OutboxEvent;
import com.fintech.ledger.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

@Component
public class OutboxWebhookRelayWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWebhookRelayWorker.class);
    private static final int BATCH_SIZE = 20;
    private static final int MAX_RETRIES = 5;

    private final OutboxEventRepository outboxRepository;
    private final RestClient restClient;

    public OutboxWebhookRelayWorker(OutboxEventRepository outboxRepository) {
        this.outboxRepository = outboxRepository;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));

        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    @Scheduled(fixedDelay = 3000)
    @Transactional
    public void processPendingWebhooks() {
        List<OutboxEvent> batch = outboxRepository.findBatchForProcessing(BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }

        for (OutboxEvent event : batch) {
            try {
                restClient.post()
                        .uri(event.getWebhookUrl())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Event-Id", event.getId().toString())
                        .header("X-Event-Type", event.getEventType())
                        .body(event.getPayload())
                        .retrieve()
                        .toBodilessEntity();

                event.markProcessed();
                log.info("Delivered outbox webhook event={} to {}", event.getId(), event.getWebhookUrl());
            } catch (Exception ex) {
                event.scheduleRetry(MAX_RETRIES);
                log.warn("Webhook delivery failed for event={} (attempt {}/{}), status={}: {}",
                        event.getId(), event.getRetryCount(), MAX_RETRIES, event.getStatus(), ex.getMessage());
            }
        }

        outboxRepository.saveAll(batch);
    }
}