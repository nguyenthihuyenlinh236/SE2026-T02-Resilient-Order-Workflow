package com.example.orderworkflow.order.repository;

import java.math.BigDecimal;
import java.time.Instant;

public final class OrderRecords {
    private OrderRecords() { }

    public record CatalogProduct(String sku, BigDecimal unitPrice, String currency, boolean active) { }
    public record OrderRecord(String orderId, String status, Instant createdAt,
            Instant expireAt, String correlationId) { }
    public record IdempotencyRecord(String payloadHash, String orderId) { }
}
