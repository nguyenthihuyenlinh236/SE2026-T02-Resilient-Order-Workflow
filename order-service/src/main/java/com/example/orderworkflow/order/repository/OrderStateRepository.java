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
        int changed = jdbc.update("UPDATE orders SET reservation_status=?,reservation_verified=?,unknown_operation=?,"
                + "updated_at=clock_timestamp(),version=version+1 WHERE order_id=? "
                + "AND status NOT IN ('CONFIRMED','FAILED','EXPIRED') "
                + "AND (NOT reservation_verified "
                + "OR (reservation_status=? AND reservation_verified=? AND unknown_operation IS NOT DISTINCT FROM ?) "
                + "OR (reservation_status='RESERVED' AND ?='RELEASED' AND ?))",
                status, verified, unknownOperation, orderId, status, verified, unknownOperation, status, verified);
        if (changed == 1) return 1;
        return jdbc.queryForObject("SELECT count(*) FROM orders WHERE order_id=? AND reservation_status=? "
                + "AND reservation_verified=? AND unknown_operation IS NOT DISTINCT FROM ?",
                Integer.class, orderId, status, verified, unknownOperation);
    }

    public int saveCapture(String orderId, String resultOrderId, String paymentId, String status,
            boolean verified, BigDecimal amount, String currency) {
        if (!orderId.equals(resultOrderId)) return 0;
        int changed = jdbc.update("UPDATE orders SET payment_id=?,capture_status=?,capture_verified=?,captured_amount=?,"
                + "captured_currency=?,updated_at=clock_timestamp(),version=version+1 WHERE order_id=? "
                + "AND (NOT (capture_verified AND capture_status IN ('SUCCEEDED','FAILED')) "
                + "OR (payment_id IS NOT DISTINCT FROM ? AND capture_status=? AND capture_verified=? "
                + "AND captured_amount IS NOT DISTINCT FROM ? AND captured_currency IS NOT DISTINCT FROM ?))",
                paymentId, status, verified, amount, currency, orderId, paymentId, status, verified, amount, currency);
        if (changed == 1) return 1;
        return jdbc.queryForObject("SELECT count(*) FROM orders WHERE order_id=? AND payment_id IS NOT DISTINCT FROM ? "
                + "AND capture_status=? AND capture_verified=? AND captured_amount IS NOT DISTINCT FROM ? "
                + "AND captured_currency IS NOT DISTINCT FROM ?", Integer.class,
                orderId, paymentId, status, verified, amount, currency);
    }

    public int saveRefund(String orderId, String refundId, String status, boolean verified) {
        int changed = jdbc.update("UPDATE orders SET refund_id=?,refund_status=?,refund_verified=?,"
                + "updated_at=clock_timestamp(),version=version+1 WHERE order_id=? "
                + "AND (NOT (refund_verified AND refund_status IN ('SUCCEEDED','FAILED')) "
                + "OR (refund_id IS NOT DISTINCT FROM ? AND refund_status=? AND refund_verified=?))",
                refundId, status, verified, orderId, refundId, status, verified);
        if (changed == 1) return 1;
        return jdbc.queryForObject("SELECT count(*) FROM orders WHERE order_id=? AND refund_id IS NOT DISTINCT FROM ? "
                + "AND refund_status=? AND refund_verified=?", Integer.class, orderId, refundId, status, verified);
    }

    public int confirm(String orderId) {
        return jdbc.update("UPDATE orders SET status='CONFIRMED',confirmed_at=clock_timestamp(),"
                + "terminal_at=clock_timestamp(),updated_at=clock_timestamp(),version=version+1 "
                + "WHERE order_id=? AND status='PENDING' AND expire_at>clock_timestamp() "
                + "AND reservation_status='RESERVED' AND reservation_verified "
                + "AND capture_status='SUCCEEDED' AND capture_verified AND payment_id IS NOT NULL "
                + "AND captured_amount=total_amount AND captured_currency=currency", orderId);
    }

    public int startCompensation(String orderId, String reasonCode) {
        return jdbc.update("UPDATE orders SET status='COMPENSATING',failure_reason_code=?,"
                + "updated_at=clock_timestamp(),version=version+1 WHERE order_id=? AND status='PENDING' "
                + "AND ((?='INVENTORY_REJECTED' AND reservation_status='REJECTED' AND reservation_verified) "
                + "OR (?='PAYMENT_FAILED' AND capture_status='FAILED' AND capture_verified))",
                reasonCode, orderId, reasonCode, reasonCode);
    }

    public int markCompensationFailed(String orderId) {
        return jdbc.update("UPDATE orders SET status=CASE WHEN status='COMPENSATING' THEN 'COMPENSATION_FAILED' "
                + "ELSE status END,updated_at=clock_timestamp(),version=version+1 "
                + "WHERE order_id=? AND status IN ('COMPENSATING','EXPIRING','EXPIRED') AND EXISTS (SELECT 1 FROM sagas s "
                + "JOIN saga_steps st ON st.saga_id=s.saga_id WHERE s.order_id=orders.order_id "
                + "AND s.status='COMPENSATING' AND st.operation IN ('RELEASE','REFUND') "
                + "AND st.status='IN_PROGRESS' AND st.failure_class='TRANSIENT' "
                + "AND st.attempt_count>=5 AND st.last_error IS NOT NULL AND st.next_attempt_at IS NOT NULL)", orderId);
    }

    public int retryCompensation(String orderId) {
        return jdbc.update("UPDATE orders SET status=CASE WHEN status='COMPENSATION_FAILED' THEN 'COMPENSATING' "
                + "ELSE status END,updated_at=clock_timestamp(),version=version+1 "
                + "WHERE order_id=? AND status IN ('COMPENSATION_FAILED','EXPIRING','EXPIRED') AND EXISTS (SELECT 1 FROM sagas s "
                + "JOIN saga_steps st ON st.saga_id=s.saga_id WHERE s.order_id=orders.order_id "
                + "AND s.status='COMPENSATION_FAILED' AND st.operation IN ('RELEASE','REFUND') "
                + "AND st.status='IN_PROGRESS' AND st.failure_class='TRANSIENT' "
                + "AND st.attempt_count>=5 AND st.last_error IS NOT NULL "
                + "AND st.next_attempt_at<=clock_timestamp())", orderId);
    }

    public int fail(String orderId) {
        return jdbc.update("UPDATE orders SET status='FAILED',failed_at=clock_timestamp(),terminal_at=clock_timestamp(),"
                + "updated_at=clock_timestamp(),version=version+1 WHERE order_id=? AND status='COMPENSATING' "
                + "AND failure_reason_code IS NOT NULL "
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

    public void updateSagaStatus(String orderId, String status) {
        int changed = jdbc.update("UPDATE sagas SET status=?,updated_at=clock_timestamp(),version=version+1 "
                + "WHERE order_id=? AND status<>'COMPLETED'", status, orderId);
        if (changed != 1) throw new IllegalStateException("Saga status could not be updated");
    }

    public EventData eventData(String orderId, String timestampColumn) {
        if (!List.of("confirmed_at", "failed_at", "expired_at").contains(timestampColumn))
            throw new IllegalArgumentException("Unsupported event timestamp");
        return jdbc.queryForObject("SELECT o.correlation_id,o.failure_reason_code,o." + timestampColumn
                + ",(SELECT st.command_id FROM sagas s JOIN saga_steps st ON st.saga_id=s.saga_id "
                + "WHERE s.order_id=o.order_id ORDER BY st.updated_at DESC,st.created_at DESC LIMIT 1) command_id "
                + "FROM orders o WHERE o.order_id=?", (rs, n) -> new EventData(rs.getString("correlation_id"),
                        rs.getString("failure_reason_code"), rs.getTimestamp(timestampColumn).toInstant(),
                        rs.getObject("command_id", UUID.class)), orderId);
    }

    public void insertOutbox(UUID eventId, String type, String orderId, UUID commandId,
            String correlationId, java.time.Instant occurredAt, String payload, String hash) {
        jdbc.update("INSERT INTO outbox_events(event_id,event_type,aggregate_id,command_id,correlation_id,"
                + "occurred_at,payload,payload_hash) VALUES (?,?,?,?,?,?,CAST(? AS jsonb),?)",
                eventId, type, orderId, commandId, correlationId,
                java.sql.Timestamp.from(occurredAt), payload, hash);
    }

    public void reopenForLateCapture(String orderId) {
        jdbc.update("UPDATE sagas s SET status='COMPENSATING',branch='EXPIRATION',current_operation='REFUND',"
                + "completed_at=NULL,updated_at=clock_timestamp(),version=s.version+1 FROM orders o "
                + "WHERE s.order_id=o.order_id AND o.order_id=? AND o.status='EXPIRED' "
                + "AND o.capture_status='SUCCEEDED' AND o.capture_verified "
                + "AND NOT (o.refund_status='SUCCEEDED' AND o.refund_verified) AND s.status='COMPLETED'", orderId);
        jdbc.update("WITH source AS (SELECT s.saga_id,o.order_id,o.payment_id,o.correlation_id,gen_random_uuid() command_id "
                + "FROM sagas s JOIN orders o ON o.order_id=s.order_id WHERE o.order_id=? AND o.status='EXPIRED' "
                + "AND o.capture_status='SUCCEEDED' AND o.capture_verified AND s.status='COMPENSATING'), prepared AS ("
                + "SELECT source.*,jsonb_build_object('commandId',command_id::text,'orderId',order_id,"
                + "'reason','LATE_CAPTURE','correlationId',correlation_id) body FROM source) "
                + "INSERT INTO saga_steps(step_id,saga_id,operation,command_id,correlation_id,idempotency_key,"
                + "saga_payload,request_payload,payload_hash,status) SELECT gen_random_uuid(),saga_id,'REFUND',command_id,"
                + "correlation_id,'payment:refund:'||order_id||':'||payment_id||':v1',"
                + "jsonb_build_object('schemaVersion',1,'paymentRequest',body),body,"
                + "encode(sha256(convert_to(body::text,'UTF8')),'hex'),'NOT_STARTED' FROM prepared "
                + "ON CONFLICT (saga_id,operation) DO NOTHING", orderId);
    }

    public void completeLateRefund(String orderId) {
        jdbc.update("UPDATE saga_steps st SET status='SUCCEEDED',failure_class='NONE',last_error=NULL,"
                + "completed_at=COALESCE(st.completed_at,clock_timestamp()),updated_at=clock_timestamp() FROM sagas s,orders o "
                + "WHERE st.saga_id=s.saga_id AND s.order_id=o.order_id AND o.order_id=? AND o.status='EXPIRED' "
                + "AND st.operation='REFUND' AND o.refund_status='SUCCEEDED' AND o.refund_verified ", orderId);
        jdbc.update("UPDATE sagas s SET status='COMPLETED',current_operation=NULL,completed_at=clock_timestamp(),"
                + "updated_at=clock_timestamp(),version=s.version+1 FROM orders o WHERE s.order_id=o.order_id "
                + "AND o.order_id=? AND o.status='EXPIRED' AND o.refund_status='SUCCEEDED' AND o.refund_verified "
                + "AND s.status<>'COMPLETED'", orderId);
    }

    public record EventData(String correlationId, String reasonCode, java.time.Instant occurredAt, UUID commandId) { }
}
