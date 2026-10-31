package com.example.orderworkflow.inventory.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.orderworkflow.inventory.api.InventoryDtos.*;
import com.example.orderworkflow.inventory.repository.InventoryOperations;
import com.example.orderworkflow.inventory.repository.InventoryRepository;

@Service
public class InventoryLifecycleService {
    private final InventoryRepository repository;
    private final InventoryOperations operations;

    public InventoryLifecycleService(InventoryRepository repository, InventoryOperations operations) {
        this.repository = repository;
        this.operations = operations;
    }

    @Transactional
    public ReleaseResult release(UUID pathId, ReleaseRequest request, String key) {
        if (request == null || request.orderId() == null || request.reservationId() == null
                || !request.reservationId().equals(pathId)) { throw invalid("Order and matching Reservation IDs are required"); }
        validateReason(request.reason());
        if (!("inventory:release:" + request.orderId() + ":" + request.reservationId() + ":v1").equals(key)) {
            throw invalid("Invalid Release idempotency key");
        }
        String hash = hash(List.of("RELEASE", request.orderId(), request.reservationId(), request.reason()));
        repository.lockOrder(request.orderId());
        var saved = operations.replay(key, "RELEASE", hash, ReleaseResult.class);
        if (saved.isPresent()) { return saved.get(); }
        var reservation = repository.byId(request.reservationId()).orElseThrow(() ->
                new InventoryException(404, "RESERVATION_NOT_FOUND", "Reservation is not yet known; query Reserve or cancel the Order"));
        if (!reservation.orderId().equals(request.orderId())) {
            throw new InventoryException(409, "RESERVATION_ORDER_MISMATCH", "Reservation belongs to another Order");
        }
        if (reservation.status().equals("REJECTED")) {
            throw new InventoryException(409, "RESERVATION_NOT_RESERVED", "Rejected Reservation cannot be released");
        }
        Instant releasedAt = reservation.status().equals("RELEASED")
                ? repository.releasedAt(reservation.reservationId()) : repository.release(reservation);
        if (releasedAt == null) {
            throw new InventoryException(409, "RELEASE_HISTORY_UNAVAILABLE", "Legacy release timestamp cannot be verified");
        }
        repository.cancelOrder(request.orderId());
        var result = new ReleaseResult(request.orderId(), request.reservationId(), key, "RELEASED", releasedAt);
        operations.save(request.orderId(), key, "RELEASE", hash, result);
        return result;
    }

    @Transactional
    public CancelResult cancel(UUID pathId, CancelRequest request, String key) {
        if (request == null || request.orderId() == null || !request.orderId().equals(pathId)) {
            throw invalid("Matching Order ID is required");
        }
        validateReason(request.reason());
        if (!("inventory:cancel:" + request.orderId() + ":v1").equals(key)) {
            throw invalid("Invalid Cancel idempotency key");
        }
        String hash = hash(List.of("CANCEL", request.orderId(), request.reason()));
        repository.lockOrder(request.orderId());
        var saved = operations.replay(key, "CANCEL", hash, CancelResult.class);
        if (saved.isPresent()) { return saved.get(); }
        var reservation = repository.byOrder(request.orderId());
        String status = "NOT_CREATED";
        UUID reservationId = null;
        if (reservation.isPresent()) {
            var found = reservation.get();
            reservationId = found.reservationId();
            status = found.status();
            if (status.equals("RESERVED")) {
                repository.release(found);
                status = "RELEASED";
            }
        }
        var cancelledAt = repository.cancelOrder(request.orderId());
        var result = new CancelResult(request.orderId(), key, "CANCELLATION_RECORDED", reservationId, status, cancelledAt);
        operations.save(request.orderId(), key, "CANCEL", hash, result);
        return result;
    }

    @Transactional(readOnly = true)
    public OperationResult operation(String key) {
        if (key == null || key.isBlank() || key.length() > 255) { throw invalid("A valid idempotencyKey is required"); }
        return operations.find(key).orElseThrow(() -> new InventoryException(404, "OPERATION_NOT_FOUND",
                "No completed command found; an in-flight command may still complete"));
    }

    private static void validateReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 255) { throw invalid("reason must contain 1 to 255 characters"); }
    }

    private static InventoryException invalid(String message) { return new InventoryException(400, "INVALID_REQUEST", message); }

    private static String hash(List<?> payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(new ObjectMapper().writeValueAsBytes(payload)));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
