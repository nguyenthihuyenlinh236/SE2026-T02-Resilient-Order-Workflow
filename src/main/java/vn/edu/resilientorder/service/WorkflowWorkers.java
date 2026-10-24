package vn.edu.resilientorder.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Component
public class WorkflowWorkers {
    private static final Logger logger = LoggerFactory.getLogger(WorkflowWorkers.class);

    private final JdbcTemplate jdbc;
    private final SagaOrchestrator orchestrator;

    public WorkflowWorkers(JdbcTemplate jdbc, SagaOrchestrator orchestrator) {
        this.jdbc = jdbc;
        this.orchestrator = orchestrator;
    }

    @Scheduled(fixedDelayString = "${app.workers.saga-delay:1000}")
    public void processSagas() {
        List<UUID> orderIds = jdbc.query(
                """
                SELECT s.order_id
                FROM order_sagas s
                JOIN orders o ON o.id = s.order_id
                WHERE s.status IN ('RUNNING', 'RECONCILING')
                  AND o.status = 'PENDING'
                ORDER BY s.updated_at
                LIMIT 50
                """,
                (rs, rowNum) -> rs.getObject("order_id", UUID.class)
        );
        for (UUID orderId : orderIds) {
            try {
                orchestrator.process(orderId);
            } catch (RuntimeException exception) {
                logger.error("Saga processing failed for order {}", orderId, exception);
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.workers.expiration-delay:5000}")
    public void expireOrders() {
        List<UUID> orderIds = jdbc.query(
                """
                SELECT o.id
                FROM orders o
                WHERE o.status = 'PENDING'
                  AND o.expire_at <= clock_timestamp()
                  AND NOT EXISTS (
                      SELECT 1 FROM payments p
                      WHERE p.order_id = o.id AND p.capture_status = 'UNKNOWN'
                  )
                ORDER BY o.expire_at
                LIMIT 50
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class)
        );
        for (UUID orderId : orderIds) {
            try {
                orchestrator.expire(orderId);
            } catch (RuntimeException exception) {
                logger.error("Order expiration failed for order {}", orderId, exception);
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.workers.outbox-delay:1000}")
    @Transactional
    public void publishOutbox() {
        List<OutboxRow> events = jdbc.query(
                """
                SELECT id, aggregate_id, event_type
                FROM outbox_events
                WHERE published_at IS NULL
                ORDER BY created_at
                LIMIT 50
                FOR UPDATE SKIP LOCKED
                """,
                (rs, rowNum) -> new OutboxRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("event_type")
                )
        );
        for (OutboxRow event : events) {
            if (!"OrderCreated".equals(event.eventType())) {
                jdbc.update(
                        """
                        INSERT INTO notification_deliveries
                            (event_id, order_id, notification_type, delivered_at)
                        VALUES (?, ?, ?, clock_timestamp())
                        ON CONFLICT (event_id) DO NOTHING
                        """,
                        event.id(), event.orderId(), event.eventType()
                );
            }
            jdbc.update(
                    "UPDATE outbox_events SET published_at = clock_timestamp() WHERE id = ?",
                    event.id()
            );
            logger.info("Published {} for order {}", event.eventType(), event.orderId());
        }
    }

    private record OutboxRow(UUID id, UUID orderId, String eventType) {
    }
}
