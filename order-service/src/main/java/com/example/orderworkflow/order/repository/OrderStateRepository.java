package com.example.orderworkflow.order.repository;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderStateRepository {
    private final JdbcTemplate jdbc;

    public OrderStateRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public int saveReservation(String orderId, String status, boolean verified, String unknownOperation) {
        return jdbc.update("UPDATE orders SET reservation_status=?,reservation_verified=?,unknown_operation=?,"
                + "updated_at=clock_timestamp(),version=version+1 WHERE order_id=? "
                + "AND status NOT IN ('CONFIRMED','FAILED','EXPIRED') "
                + "AND (NOT reservation_verified OR (reservation_status='RESERVED' AND ?='RELEASED' AND ?))",
                status, verified, unknownOperation, orderId, status, verified);
    }

    public int saveCapture(String orderId, String resultOrderId, String paymentId, String status,
            boolean verified, BigDecimal amount, String currency) {
        if (!orderId.equals(resultOrderId)) return 0;
        return jdbc.update("UPDATE orders SET payment_id=?,capture_status=?,capture_verified=?,captured_amount=?,"
                + "captured_currency=?,updated_at=clock_timestamp(),version=version+1 WHERE order_id=? "
                + "AND NOT (capture_verified AND capture_status IN ('SUCCEEDED','FAILED'))",
                paymentId, status, verified, amount, currency, orderId);
    }

    public int saveRefund(String orderId, String refundId, String status, boolean verified) {
        return jdbc.update("UPDATE orders SET refund_id=?,refund_status=?,refund_verified=?,"
                + "updated_at=clock_timestamp(),version=version+1 WHERE order_id=? "
                + "AND NOT (refund_verified AND refund_status IN ('SUCCEEDED','FAILED'))", refundId, status, verified, orderId);
    }

    public int confirm(String orderId) {
        return jdbc.update("UPDATE orders SET status='CONFIRMED',confirmed_at=clock_timestamp(),"
                + "terminal_at=clock_timestamp(),updated_at=clock_timestamp(),version=version+1 "
                + "WHERE order_id=? AND status='PENDING' AND expire_at>clock_timestamp() "
                + "AND reservation_status='RESERVED' AND reservation_verified "
                + "AND capture_status='SUCCEEDED' AND capture_verified AND payment_id IS NOT NULL "
                + "AND captured_amount=total_amount AND captured_currency=currency", orderId);
    }

    public int startCompensation(String orderId) {
        return jdbc.update("UPDATE orders SET status='COMPENSATING',updated_at=clock_timestamp(),version=version+1 "
                + "WHERE order_id=? AND status='PENDING' AND ((reservation_status='REJECTED' AND reservation_verified) "
                + "OR (capture_status='FAILED' AND capture_verified))", orderId);
    }

    public int markCompensationFailed(String orderId) {
        return jdbc.update("UPDATE orders SET status=CASE WHEN status='COMPENSATING' THEN 'COMPENSATION_FAILED' "
                + "ELSE status END,updated_at=clock_timestamp(),version=version+1 "
                + "WHERE order_id=? AND status IN ('COMPENSATING','EXPIRING') AND EXISTS (SELECT 1 FROM sagas s "
                + "JOIN saga_steps st ON st.saga_id=s.saga_id WHERE s.order_id=orders.order_id "
                + "AND st.operation IN ('RELEASE','REFUND') AND st.status IN ('FAILED','UNKNOWN') "
                + "AND st.attempt_count>=5)", orderId);
    }

    public int retryCompensation(String orderId) {
        return jdbc.update("UPDATE orders SET status=CASE WHEN status='COMPENSATION_FAILED' THEN 'COMPENSATING' "
                + "ELSE status END,updated_at=clock_timestamp(),version=version+1 "
                + "WHERE order_id=? AND status IN ('COMPENSATION_FAILED','EXPIRING') AND EXISTS (SELECT 1 FROM sagas s "
                + "JOIN saga_steps st ON st.saga_id=s.saga_id WHERE s.order_id=orders.order_id "
                + "AND st.operation IN ('RELEASE','REFUND') AND st.status IN ('FAILED','UNKNOWN') "
                + "AND st.attempt_count>=5 AND st.next_attempt_at<=clock_timestamp())", orderId);
    }

    public int fail(String orderId) {
        return jdbc.update("UPDATE orders SET status='FAILED',failed_at=clock_timestamp(),terminal_at=clock_timestamp(),"
                + "updated_at=clock_timestamp(),version=version+1 WHERE order_id=? AND status='COMPENSATING' "
                + "AND reservation_verified AND reservation_status IN ('RELEASED','NOT_CREATED','REJECTED') "
                + "AND capture_status NOT IN ('UNKNOWN','IN_PROGRESS') AND refund_status NOT IN ('UNKNOWN','IN_PROGRESS') "
                + "AND (capture_status<>'SUCCEEDED' OR (refund_status='SUCCEEDED' AND refund_verified))", orderId);
    }

    public int claimExpired(String orderId, UUID token, Duration lease) {
        return jdbc.update("UPDATE orders SET status='EXPIRING',claim_token=?,claimed_at=clock_timestamp(),"
                + "lease_until=clock_timestamp()+(? * interval '1 millisecond'),updated_at=clock_timestamp(),version=version+1 "
                + "WHERE order_id=? AND status='PENDING' AND expire_at<=clock_timestamp() AND capture_status<>'UNKNOWN'",
                token, lease.toMillis(), orderId);
    }

    public int takeOverExpired(String orderId, UUID oldToken, UUID newToken, Duration lease) {
        return jdbc.update("UPDATE orders SET claim_token=?,claimed_at=clock_timestamp(),"
                + "lease_until=clock_timestamp()+(? * interval '1 millisecond'),updated_at=clock_timestamp(),version=version+1 "
                + "WHERE order_id=? AND status='EXPIRING' AND claim_token=? AND lease_until<=clock_timestamp()",
                newToken, lease.toMillis(), orderId, oldToken);
    }

    public int expire(String orderId, UUID token) {
        return jdbc.update("UPDATE orders SET status='EXPIRED',expired_at=clock_timestamp(),terminal_at=clock_timestamp(),"
                + "updated_at=clock_timestamp(),version=version+1 WHERE order_id=? AND status='EXPIRING' "
                + "AND claim_token=? AND lease_until>clock_timestamp() "
                + "AND capture_status NOT IN ('UNKNOWN','IN_PROGRESS') "
                + "AND refund_status NOT IN ('UNKNOWN','IN_PROGRESS') "
                + "AND reservation_verified AND reservation_status IN ('RELEASED','NOT_CREATED','REJECTED') "
                + "AND (capture_status<>'SUCCEEDED' OR (refund_status='SUCCEEDED' AND refund_verified))", orderId, token);
    }

    public Optional<String> status(String orderId) {
        return jdbc.query("SELECT status FROM orders WHERE order_id=?", (rs, n) -> rs.getString(1), orderId)
                .stream().findFirst();
    }

    public List<String> findPendingExpired(int limit) {
        return jdbc.query("SELECT order_id FROM orders WHERE status='PENDING' AND expire_at<=clock_timestamp() "
                + "AND capture_status<>'UNKNOWN' ORDER BY expire_at,order_id LIMIT ?", (rs, n) -> rs.getString(1), limit);
    }

    public List<String> findExpiredClaims(int limit) {
        return jdbc.query("SELECT order_id FROM orders WHERE status='EXPIRING' AND lease_until<=clock_timestamp() "
                + "ORDER BY lease_until,order_id LIMIT ?", (rs, n) -> rs.getString(1), limit);
    }

    public void completeSaga(String orderId) {
        int changed = jdbc.update("UPDATE sagas SET status='COMPLETED',current_operation=NULL,completed_at=clock_timestamp(),"
                + "updated_at=clock_timestamp(),version=version+1 WHERE order_id=? AND status<>'COMPLETED'", orderId);
        if (changed != 1) throw new IllegalStateException("Saga could not be completed");
    }

    public void updateSagaBranch(String orderId, String status, String branch) {
        int changed = jdbc.update("UPDATE sagas SET status=?,branch=?,updated_at=clock_timestamp(),version=version+1 "
                + "WHERE order_id=? AND status<>'COMPLETED'", status, branch, orderId);
        if (changed != 1) throw new IllegalStateException("Saga branch could not be updated");
    }

    public void insertOutbox(UUID eventId, String type, String orderId, String payload, String hash) {
        jdbc.update("INSERT INTO outbox_events(event_id,event_type,aggregate_id,payload,payload_hash) "
                + "VALUES (?,?,?,CAST(? AS jsonb),?)", eventId, type, orderId, payload, hash);
    }
}
