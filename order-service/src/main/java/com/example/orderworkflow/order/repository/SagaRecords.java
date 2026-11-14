package com.example.orderworkflow.order.repository;

import java.time.Instant;
import java.util.UUID;

public final class SagaRecords {
    private SagaRecords() { }

    public record Saga(UUID sagaId, String orderId, String status, String branch,
            String currentOperation, String lastError, Instant createdAt, Instant updatedAt,
            Instant completedAt, long version) { }

    public record SagaStep(UUID stepId, UUID sagaId, String operation, UUID commandId, String correlationId,
            String idempotencyKey, String requestPayload, String payloadHash, String resultPayload, String status, int attemptCount,
            Instant nextAttemptAt, Instant unknownSince, int queryCount, int backgroundQueryCount,
            Instant nextQueryAt, UUID claimToken, Instant claimedAt, Instant leaseUntil,
            String lastError, Instant createdAt, Instant updatedAt, Instant completedAt) { }

    public record Attempt(UUID attemptId, UUID stepId, String kind, int sequenceNo,
            Instant startedAt, Instant finishedAt, String outcome, String errorCode, String errorMessage) { }
}
