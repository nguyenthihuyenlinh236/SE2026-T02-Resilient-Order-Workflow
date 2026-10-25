package vn.edu.resilientorder.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class SagaOrchestrator {
    private final JdbcTemplate jdbc;
    private final OrderService orderService;
    private final CaptureStatus simulatedPaymentOutcome;

    public SagaOrchestrator(
            JdbcTemplate jdbc,
            OrderService orderService,
            @Value("${app.payment.simulated-outcome:SUCCEEDED}") String simulatedPaymentOutcome
    ) {
        this.jdbc = jdbc;
        this.orderService = orderService;
        this.simulatedPaymentOutcome = CaptureStatus.fromConfiguration(simulatedPaymentOutcome);
    }

    @Transactional
    public void process(UUID orderId) {
        if (!lockPendingOrder(orderId)) {
            return;
        }

        String reservationStatus = reservationStatus(orderId);
        if (reservationStatus == null) {
            if (!isBeforeExpiration(orderId)) {
                return;
            }
            reservationStatus = reserveInventory(orderId);
        }
        if ("REJECTED".equals(reservationStatus)) {
            if (claim(orderId, "COMPENSATING")) {
                completeFailure(orderId);
            }
            return;
        }
        if (!"RESERVED".equals(reservationStatus)) {
            return;
        }

        CaptureStatus paymentStatus = capturePayment(orderId);
        if (paymentStatus == CaptureStatus.NOT_STARTED) {
            return;
        }
        if (paymentStatus == CaptureStatus.UNKNOWN) {
            jdbc.update(
                    """
                    UPDATE order_sagas
                    SET status = 'RECONCILING', current_step = 'RECONCILE_PAYMENT',
                        attempts = attempts + 1, updated_at = clock_timestamp()
                    WHERE order_id = ? AND status IN ('RUNNING', 'RECONCILING')
                    """,
                    orderId
            );
            return;
        }
        if (paymentStatus == CaptureStatus.FAILED) {
            if (claim(orderId, "COMPENSATING")) {
                releaseReservation(orderId);
                completeFailure(orderId);
            }
            return;
        }

        int confirmed = jdbc.update(
                """
                UPDATE orders
                SET status = 'CONFIRMED', updated_at = clock_timestamp()
                WHERE id = ? AND status = 'PENDING' AND clock_timestamp() < expire_at
                """,
                orderId
        );
        if (confirmed == 1) {
            jdbc.update(
                    """
                    UPDATE order_sagas
                    SET status = 'COMPLETED', current_step = 'COMPLETED',
                        updated_at = clock_timestamp()
                    WHERE order_id = ?
                    """,
                    orderId
            );
            orderService.addOutboxEvent(orderId, "OrderConfirmed", databaseNow());
            return;
        }

        if (!"PENDING".equals(orderStatus(orderId))) {
            refundIfCaptured(orderId);
        }
    }

    @Transactional
    public void expire(UUID orderId) {
        int claimed = jdbc.update(
                """
                UPDATE orders o
                SET status = 'EXPIRING', updated_at = clock_timestamp()
                WHERE o.id = ? AND o.status = 'PENDING'
                  AND o.expire_at <= clock_timestamp()
                  AND NOT EXISTS (
                      SELECT 1 FROM payments p
                      WHERE p.order_id = o.id AND p.capture_status = 'UNKNOWN'
                  )
                """,
                orderId
        );
        if (claimed != 1) {
            return;
        }
        jdbc.update(
                """
                UPDATE order_sagas
                SET status = 'COMPENSATING', current_step = 'EXPIRATION_COMPENSATION',
                    updated_at = clock_timestamp()
                WHERE order_id = ?
                """,
                orderId
        );

        releaseReservation(orderId);
        refundIfCaptured(orderId);

        int expired = jdbc.update(
                """
                UPDATE orders
                SET status = 'EXPIRED', updated_at = clock_timestamp()
                WHERE id = ? AND status = 'EXPIRING'
                """,
                orderId
        );
        if (expired == 1) {
            jdbc.update(
                    """
                    UPDATE order_sagas
                    SET status = 'COMPLETED', current_step = 'COMPLETED',
                        updated_at = clock_timestamp()
                    WHERE order_id = ?
                    """,
                    orderId
            );
            orderService.addOutboxEvent(orderId, "OrderExpired", databaseNow());
        }
    }

    private String reserveInventory(UUID orderId) {
        List<ReservationRow> existing = jdbc.query(
                "SELECT status FROM reservations WHERE order_id = ?",
                (rs, rowNum) -> new ReservationRow(rs.getString("status")),
                orderId
        );
        if (!existing.isEmpty()) {
            return existing.get(0).status();
        }

        List<InventoryLine> items = jdbc.query(
                "SELECT sku, quantity FROM order_items WHERE order_id = ? ORDER BY sku",
                (rs, rowNum) -> new InventoryLine(rs.getString("sku"), rs.getInt("quantity")),
                orderId
        );
        boolean available = true;
        for (InventoryLine item : items) {
            List<Integer> stock = jdbc.query(
                    "SELECT on_hand - reserved FROM inventory_stock WHERE sku = ? FOR UPDATE",
                    (rs, rowNum) -> rs.getInt(1),
                    item.sku()
            );
            if (stock.isEmpty() || stock.get(0) < item.quantity()) {
                available = false;
            }
        }

        String status = available ? "RESERVED" : "REJECTED";
        Instant now = databaseNow();
        jdbc.update(
                """
                INSERT INTO reservations (id, order_id, idempotency_key, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), orderId, "reserve:" + orderId + ":v1",
                status, Timestamp.from(now), Timestamp.from(now)
        );
        if (available) {
            for (InventoryLine item : items) {
                jdbc.update(
                        "UPDATE inventory_stock SET reserved = reserved + ? WHERE sku = ?",
                        item.quantity(), item.sku()
                );
            }
        }
        jdbc.update(
                """
                UPDATE order_sagas
                SET current_step = ?, attempts = attempts + 1, updated_at = clock_timestamp()
                WHERE order_id = ?
                """,
                available ? "CAPTURE_PAYMENT" : "RESERVE_REJECTED", orderId
        );
        return status;
    }

    private CaptureStatus capturePayment(UUID orderId) {
        List<PaymentRow> existing = jdbc.query(
                "SELECT id, capture_status FROM payments WHERE order_id = ?",
                (rs, rowNum) -> new PaymentRow(
                        rs.getObject("id", UUID.class), CaptureStatus.fromDatabase(rs.getString("capture_status"))
                ),
                orderId
        );
        Instant now = databaseNow();
        if (existing.isEmpty()) {
            if (!isBeforeExpiration(orderId)) {
                return CaptureStatus.NOT_STARTED;
            }
            UUID paymentId = UUID.randomUUID();
            jdbc.update(
                    """
                    INSERT INTO payments
                        (id, order_id, capture_key, capture_status, refund_status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'NOT_REQUIRED', ?, ?)
                    """,
                    paymentId, orderId, "payment:capture:" + orderId + ":v1",
                    simulatedPaymentOutcome.name(), Timestamp.from(now), Timestamp.from(now)
            );
            return simulatedPaymentOutcome;
        }

        PaymentRow payment = existing.get(0);
        if (payment.status() == CaptureStatus.UNKNOWN && simulatedPaymentOutcome != CaptureStatus.UNKNOWN) {
            jdbc.update(
                    "UPDATE payments SET capture_status = ?, updated_at = ? WHERE id = ? AND capture_status = 'UNKNOWN'",
                    simulatedPaymentOutcome.name(), Timestamp.from(now), payment.id()
            );
            return simulatedPaymentOutcome;
        }
        return payment.status();
    }

    private void releaseReservation(UUID orderId) {
        List<ReservationRow> reservations = jdbc.query(
                "SELECT status FROM reservations WHERE order_id = ? FOR UPDATE",
                (rs, rowNum) -> new ReservationRow(rs.getString("status")),
                orderId
        );
        if (reservations.isEmpty() || !"RESERVED".equals(reservations.get(0).status())) {
            return;
        }

        List<InventoryLine> items = jdbc.query(
                "SELECT sku, quantity FROM order_items WHERE order_id = ?",
                (rs, rowNum) -> new InventoryLine(rs.getString("sku"), rs.getInt("quantity")),
                orderId
        );
        for (InventoryLine item : items) {
            int updated = jdbc.update(
                    "UPDATE inventory_stock SET reserved = reserved - ? WHERE sku = ? AND reserved >= ?",
                    item.quantity(), item.sku(), item.quantity()
            );
            if (updated != 1) {
                throw new IllegalStateException("Inventory reservation invariant violated for SKU " + item.sku());
            }
        }
        jdbc.update(
                "UPDATE reservations SET status = 'RELEASED', updated_at = clock_timestamp() WHERE order_id = ? AND status = 'RESERVED'",
                orderId
        );
    }

    private void refundIfCaptured(UUID orderId) {
        List<UUID> paymentIds = jdbc.query(
                """
                SELECT id
                FROM payments
                WHERE order_id = ? AND capture_status = 'SUCCEEDED'
                  AND refund_status IN ('NOT_REQUIRED', 'NOT_STARTED', 'UNKNOWN')
                FOR UPDATE
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                orderId
        );
        for (UUID paymentId : paymentIds) {
            jdbc.update(
                    """
                    UPDATE payments
                    SET refund_key = ?, refund_status = 'SUCCEEDED', updated_at = clock_timestamp()
                    WHERE id = ? AND capture_status = 'SUCCEEDED'
                      AND refund_status IN ('NOT_REQUIRED', 'NOT_STARTED', 'UNKNOWN')
                    """,
                    "payment:refund:" + orderId + ":" + paymentId + ":v1", paymentId
            );
        }
    }

    private void completeFailure(UUID orderId) {
        int failed = jdbc.update(
                """
                UPDATE orders SET status = 'FAILED', updated_at = clock_timestamp()
                WHERE id = ? AND status = 'COMPENSATING'
                """,
                orderId
        );
        if (failed == 1) {
            jdbc.update(
                    """
                    UPDATE order_sagas
                    SET status = 'COMPLETED', current_step = 'COMPLETED',
                        updated_at = clock_timestamp()
                    WHERE order_id = ?
                    """,
                    orderId
            );
            orderService.addOutboxEvent(orderId, "OrderFailed", databaseNow());
        }
    }

    private boolean claim(UUID orderId, String status) {
        return jdbc.update(
                """
                UPDATE orders SET status = ?, updated_at = clock_timestamp()
                WHERE id = ? AND status = 'PENDING'
                """,
                status, orderId
        ) == 1;
    }

    private String orderStatus(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private String reservationStatus(UUID orderId) {
        List<ReservationRow> reservations = jdbc.query(
                "SELECT status FROM reservations WHERE order_id = ?",
                (rs, rowNum) -> new ReservationRow(rs.getString("status")),
                orderId
        );
        return reservations.isEmpty() ? null : reservations.get(0).status();
    }

    private boolean lockPendingOrder(UUID orderId) {
        return !jdbc.query(
                """
                SELECT id
                FROM orders WHERE id = ? AND status = 'PENDING'
                FOR UPDATE
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                orderId
        ).isEmpty();
    }

    private boolean isBeforeExpiration(UUID orderId) {
        return !jdbc.query(
                """
                SELECT id FROM orders
                WHERE id = ? AND status = 'PENDING' AND clock_timestamp() < expire_at
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                orderId
        ).isEmpty();
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    private record InventoryLine(String sku, int quantity) {
    }

    private record ReservationRow(String status) {
    }

    private record PaymentRow(UUID id, CaptureStatus status) {
    }

    private enum CaptureStatus {
        SUCCEEDED,
        FAILED,
        UNKNOWN,
        NOT_STARTED;

        private static CaptureStatus fromConfiguration(String value) {
            return switch (value.toUpperCase(Locale.ROOT)) {
                case "SUCCEEDED" -> SUCCEEDED;
                case "FAILED" -> FAILED;
                case "UNKNOWN" -> UNKNOWN;
                default -> throw new IllegalArgumentException(
                        "app.payment.simulated-outcome must be SUCCEEDED, FAILED or UNKNOWN."
                );
            };
        }

        private static CaptureStatus fromDatabase(String value) {
            return switch (value) {
                case "SUCCEEDED" -> SUCCEEDED;
                case "FAILED" -> FAILED;
                case "UNKNOWN" -> UNKNOWN;
                default -> throw new IllegalStateException("Unsupported payment capture status: " + value);
            };
        }
    }
}
