package vn.edu.resilientorder.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID orderId,
        String status,
        String fulfillmentStatus,
        String sagaStatus,
        String paymentStatus,
        String reservationStatus,
        String storeId,
        Instant createdAt,
        Instant expireAt,
        String statusUrl,
        List<OrderItemResponse> items
) {
    public record OrderItemResponse(String sku, int quantity) {
    }
}
