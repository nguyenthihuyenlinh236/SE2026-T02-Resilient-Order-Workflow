package com.example.orderworkflow.order.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.orderworkflow.order.repository.OrderStateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class OrderStateMachine {
    public enum Transition { APPLIED, REJECTED }

    private final OrderStateRepository repository;
    private final ObjectMapper json;

    public OrderStateMachine(OrderStateRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    @Transactional
    public void recordReservation(String orderId, String status, boolean verified, String unknownOperation) {
        require(status, "NOT_CREATED", "RESERVED", "RELEASED", "REJECTED", "UNKNOWN");
        if (("UNKNOWN".equals(status)) != (unknownOperation != null))
            throw new IllegalArgumentException("UNKNOWN reservation requires its operation");
        if (repository.saveReservation(orderId, status, verified, unknownOperation) != 1)
            throw new IllegalStateException("Reservation result is stale or Order is terminal");
    }

    @Transactional
    public void recordCapture(String orderId, String resultOrderId, String paymentId, String status,
            boolean verified, BigDecimal amount, String currency) {
        require(status, "NOT_STARTED", "IN_PROGRESS", "SUCCEEDED", "FAILED", "UNKNOWN");
        if (verified && "SUCCEEDED".equals(status)
                && (paymentId == null || amount == null || currency == null))
            throw new IllegalArgumentException("Verified capture requires payment, amount and currency");
        if (repository.saveCapture(orderId, resultOrderId, paymentId, status, verified, amount, currency) != 1)
            throw new IllegalStateException("Capture result is mismatched or stale");
    }

    @Transactional
    public void recordRefund(String orderId, String refundId, String status, boolean verified) {
        require(status, "NOT_REQUIRED", "NOT_STARTED", "IN_PROGRESS", "SUCCEEDED", "FAILED", "UNKNOWN");
        if (repository.saveRefund(orderId, refundId, status, verified) != 1)
            throw new IllegalStateException("Refund result is stale");
    }

    @Transactional
    public Transition confirm(String orderId) { return terminal(orderId, "OrderConfirmed", repository.confirm(orderId)); }

    @Transactional
    public Transition startCompensation(String orderId) {
        if (repository.startCompensation(orderId) == 0) return rejected(orderId);
        repository.updateSagaBranch(orderId, "COMPENSATING", "FAILURE");
        return Transition.APPLIED;
    }

    @Transactional
    public Transition compensationFailed(String orderId) {
        if (repository.markCompensationFailed(orderId) == 0) return rejected(orderId);
        repository.updateSagaBranch(orderId, "COMPENSATION_FAILED", "FAILURE");
        return Transition.APPLIED;
    }

    @Transactional
    public Transition retryCompensation(String orderId) {
        if (repository.retryCompensation(orderId) == 0) return rejected(orderId);
        repository.updateSagaBranch(orderId, "COMPENSATING", "FAILURE");
        return Transition.APPLIED;
    }

    @Transactional
    public Transition fail(String orderId) { return terminal(orderId, "OrderFailed", repository.fail(orderId)); }

    @Transactional
    public UUID claimExpired(String orderId) {
        UUID token = UUID.randomUUID();
        if (repository.claimExpired(orderId, token, Duration.ofSeconds(30)) == 0) {
            rejected(orderId);
            return null;
        }
        repository.updateSagaBranch(orderId, "COMPENSATING", "EXPIRATION");
        return token;
    }

    @Transactional
    public UUID takeOverExpired(String orderId, UUID oldToken) {
        UUID token = UUID.randomUUID();
        return repository.takeOverExpired(orderId, oldToken, token, Duration.ofSeconds(30)) == 1 ? token : null;
    }

    @Transactional
    public Transition expire(String orderId, UUID token) {
        return terminal(orderId, "OrderExpired", repository.expire(orderId, token));
    }

    private Transition terminal(String orderId, String eventType, int changed) {
        if (changed == 0) return rejected(orderId);
        repository.completeSaga(orderId);
        String payload;
        try { payload = json.writeValueAsString(Map.of("orderId", orderId, "eventType", eventType)); }
        catch (Exception exception) { throw new IllegalStateException("Outbox payload could not be serialized", exception); }
        repository.insertOutbox(UUID.randomUUID(), eventType, orderId, payload, sha256(payload));
        return Transition.APPLIED;
    }

    private Transition rejected(String orderId) {
        repository.status(orderId).orElseThrow(() -> new IllegalArgumentException("Order does not exist"));
        return Transition.REJECTED;
    }

    private static void require(String value, String... allowed) {
        for (String item : allowed) if (item.equals(value)) return;
        throw new IllegalArgumentException("Unsupported state: " + value);
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
