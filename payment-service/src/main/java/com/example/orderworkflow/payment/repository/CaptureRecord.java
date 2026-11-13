package com.example.orderworkflow.payment.repository;

import java.math.BigDecimal;
import java.time.Instant;

public record CaptureRecord(
        String paymentId,
        String orderId,
        String idempotencyKey,
        String payloadHash,
        BigDecimal amount,
        String currency,
        String status,
        Instant capturedAt) { }
