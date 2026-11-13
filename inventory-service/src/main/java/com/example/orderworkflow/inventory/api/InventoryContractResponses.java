package com.example.orderworkflow.inventory.api;

import java.time.Instant;
import com.example.orderworkflow.inventory.api.InventoryDtos.Reservation;

/** HTTP projections only: persisted command snapshots remain immutable. */
public final class InventoryContractResponses {
    private InventoryContractResponses() { }

    public record ReserveResponse(String reservationId, String orderId, String status,
            Instant reservedAt, String reasonCode) { }

    public record ReservationResponse(String reservationId, String orderId, String status,
            String idempotencyKey, String reasonCode) { }

    public record ReleaseResponse(String reservationId, String orderId, String status, Instant releasedAt) { }

    public static ReserveResponse reserve(Reservation result) {
        boolean rejected = result.status().equals("REJECTED");
        return new ReserveResponse(rejected ? null : result.reservationId(), result.orderId(), result.status(),
                rejected ? null : result.createdAt(), reason(result));
    }

    public static ReservationResponse reservation(Reservation result) {
        return new ReservationResponse(result.status().equals("REJECTED") ? null : result.reservationId(),
                result.orderId(), result.status(), result.reserveKey(), reason(result));
    }

    private static String reason(Reservation result) {
        if (!result.status().equals("REJECTED")) { return null; }
        return switch (result.rejectionReason()) {
            case "INSUFFICIENT_STOCK" -> "INVENTORY_NOT_AVAILABLE";
            case "PRODUCT_NOT_FOUND" -> "PRODUCT_NOT_FOUND";
            case "ORDER_CANCELLED" -> "ORDER_ALREADY_RELEASED";
            default -> throw new IllegalStateException("Unsupported Inventory rejection reason");
        };
    }
}
