package vn.edu.resilientorder.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class RetentionWorker {
    private static final Logger logger = LoggerFactory.getLogger(RetentionWorker.class);

    private final JdbcTemplate jdbc;
    private final Duration retentionPeriod;

    public RetentionWorker(
            JdbcTemplate jdbc,
            @Value("${app.order.retention:26d}") Duration retentionPeriod
    ) {
        if (retentionPeriod.isNegative() || retentionPeriod.isZero()) {
            throw new IllegalArgumentException("Order retention period must be positive.");
        }
        this.jdbc = jdbc;
        this.retentionPeriod = retentionPeriod;
    }

    @Scheduled(fixedDelayString = "${app.workers.retention-delay:3600000}")
    @Transactional
    public void purgeExpiredRecords() {
        Instant cutoff = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class)
                .toInstant()
                .minus(retentionPeriod);
        List<UUID> expiredOrderIds = jdbc.query(
                """
                SELECT id FROM orders
                WHERE created_at <= ?
                  AND status IN ('CONFIRMED', 'FAILED', 'EXPIRED')
                ORDER BY created_at
                LIMIT 100
                FOR UPDATE SKIP LOCKED
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                Timestamp.from(cutoff)
        );

        for (UUID orderId : expiredOrderIds) {
            jdbc.update(
                    "DELETE FROM notification_deliveries WHERE order_id = ?",
                    orderId
            );
            jdbc.update("DELETE FROM outbox_events WHERE aggregate_id = ?", orderId);
            jdbc.update("DELETE FROM payments WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM reservations WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM order_items WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM order_sagas WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM orders WHERE id = ?", orderId);
        }

        jdbc.update(
                "DELETE FROM security_audit_log WHERE created_at <= ?",
                Timestamp.from(cutoff)
        );
        jdbc.update(
                """
                DELETE FROM security_alerts
                WHERE acknowledged_at IS NOT NULL AND created_at <= ?
                """,
                Timestamp.from(cutoff)
        );
        jdbc.update(
                """
                DELETE FROM session_denial_counts c
                WHERE NOT EXISTS (
                    SELECT 1 FROM SPRING_SESSION s WHERE s.SESSION_ID = c.session_id
                )
                """
        );

        Integer overdueUnfinished = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM orders
                WHERE created_at <= ? AND status NOT IN ('CONFIRMED', 'FAILED', 'EXPIRED')
                """,
                Integer.class,
                Timestamp.from(cutoff)
        );
        if (overdueUnfinished > 0) {
            logger.error(
                    "{} unfinished orders are past the {}-day retention boundary and require reconciliation.",
                    overdueUnfinished,
                    retentionPeriod.toDays()
            );
        }
        if (!expiredOrderIds.isEmpty()) {
            logger.info("Purged {} terminal orders past the retention boundary.", expiredOrderIds.size());
        }
    }
}
