package com.example.orderworkflow.payment.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CaptureRepository {
    private final JdbcTemplate jdbc;

    public CaptureRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insert(String paymentId, String orderId, String key, String payloadHash,
            BigDecimal amount, String currency, String status, UUID mockTransactionId) {
        Instant completedAt = "SUCCEEDED".equals(status) ? Instant.now() : null;
        int changed = jdbc.update("""
                INSERT INTO captures(payment_id, order_id, idempotency_key, payload_hash, amount, currency,
                                     status, mock_transaction_id, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, paymentId, orderId, key, payloadHash, amount, currency, status,
                mockTransactionId, completedAt == null ? null : Timestamp.from(completedAt));
        return changed == 1;
    }

    public Optional<CaptureRecord> findByPaymentId(String paymentId) {
        return find("payment_id", paymentId);
    }

    public Optional<CaptureRecord> findByIdempotencyKey(String key) {
        return find("idempotency_key", key);
    }

    public Optional<CaptureRecord> findByOrderId(String orderId) {
        return find("order_id", orderId);
    }

    private Optional<CaptureRecord> find(String column, String value) {
        // Column names are selected only by the fixed methods above, never from request input.
        return jdbc.query("SELECT * FROM captures WHERE " + column + " = ?",
                (rs, rowNum) -> map(rs), value).stream().findFirst();
    }

    private static CaptureRecord map(ResultSet rs) throws SQLException {
        var completed = rs.getTimestamp("completed_at");
        return new CaptureRecord(
                rs.getString("payment_id"),
                rs.getString("order_id"),
                rs.getString("idempotency_key"),
                rs.getString("payload_hash"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("status"),
                completed == null ? null : completed.toInstant());
    }
}
