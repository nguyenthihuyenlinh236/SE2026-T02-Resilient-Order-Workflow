package com.example.orderworkflow.order.service;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClient;

/** Client for the documented public Inventory Reserve API (not the /internal projection). */
public final class InventoryClient {
    public record PublicItem(String sku, Integer quantity) { }
    public record PublicReserveRequest(String orderId, String reservationId, String storeId,
            List<PublicItem> items) { }
    public record PublicReservation(String reservationId, String orderId, String storeId, String reserveKey,
            String status, String rejectionReason, List<PublicItem> items, Instant createdAt) { }
    public record Outcome(String status, PublicReservation reservation, String errorCode, String errorMessage) {
        public boolean definitive() { return "RESERVED".equals(status) || "REJECTED".equals(status); }
    }

    private final RestClient http;
    private final ObjectMapper json;

    public InventoryClient(RestClient http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    public Outcome reserve(String idempotencyKey, String persistedPayload) {
        try {
            JsonNode snapshot = json.readTree(persistedPayload);
            PublicReserveRequest request = json.treeToValue(snapshot, PublicReserveRequest.class);
            if (request.orderId() == null || request.reservationId() == null || request.storeId() == null
                    || request.items() == null || !idempotencyKey.equals("reserve:" + request.orderId() + ":v1")) {
                return unknown("INVALID_SNAPSHOT", "Persisted Reserve intent does not match the public request contract");
            }
            PublicReservation response = http.post().uri("/api/v1/inventory/reservations")
                    .header("Idempotency-Key", idempotencyKey)
                    .body(snapshot)
                    .retrieve()
                    .body(PublicReservation.class);
            if (response == null || !request.orderId().equals(response.orderId())
                    || !request.reservationId().equals(response.reservationId())
                    || !request.storeId().equals(response.storeId())
                    || !idempotencyKey.equals(response.reserveKey())
                    || !request.items().equals(response.items())) {
                return unknown("RESPONSE_IDENTITY_MISMATCH", "Inventory response did not match the saved Reserve intent");
            }
            if ("RESERVED".equals(response.status()) || "REJECTED".equals(response.status())) {
                if ("RESERVED".equals(response.status()) && response.rejectionReason() != null
                        || "REJECTED".equals(response.status())
                                && (response.rejectionReason() == null || response.rejectionReason().isBlank())) {
                    return unknown("INVALID_PUBLIC_RESERVATION", "Inventory outcome did not satisfy the public Reservation contract");
                }
                return new Outcome(response.status(), response, null, null);
            }
            return unknown("UNRECOGNIZED_INVENTORY_STATUS", "Inventory returned no definitive Reserve outcome");
        } catch (Exception failure) {
            // A timeout, reset, HTTP error, or malformed response does not prove that Reserve failed.
            return unknown(failure.getClass().getSimpleName(), safeMessage(failure));
        }
    }

    private static Outcome unknown(String code, String message) {
        return new Outcome("UNKNOWN", null, code, message);
    }

    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null ? "Inventory Reserve outcome could not be verified" : message;
    }
}
