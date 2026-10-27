package vn.edu.resilientorder.service;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetentionWorkerTest {
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:retention-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE ALIAS CLOCK_TIMESTAMP FOR
                "vn.edu.resilientorder.security.AuthenticationServiceTest.clockTimestamp"
                """);
        jdbc.execute("""
                CREATE TABLE orders (
                    id UUID PRIMARY KEY,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    status VARCHAR(32) NOT NULL
                )
                """);
        jdbc.execute("CREATE TABLE order_items (order_id UUID)");
        jdbc.execute("CREATE TABLE order_sagas (order_id UUID)");
        jdbc.execute("CREATE TABLE reservations (order_id UUID)");
        jdbc.execute("CREATE TABLE payments (order_id UUID)");
        jdbc.execute("CREATE TABLE outbox_events (id UUID PRIMARY KEY, aggregate_id UUID)");
        jdbc.execute("CREATE TABLE notification_deliveries (event_id UUID, order_id UUID)");
        jdbc.execute("CREATE TABLE security_audit_log (created_at TIMESTAMP WITH TIME ZONE)");
        jdbc.execute("""
                CREATE TABLE security_alerts (
                    id BIGINT PRIMARY KEY,
                    account_id UUID NOT NULL,
                    session_id VARCHAR(100) NOT NULL,
                    alert_type VARCHAR(100) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    acknowledged_at TIMESTAMP WITH TIME ZONE
                )
                """);
        jdbc.execute("CREATE TABLE session_denial_counts (session_id VARCHAR(100), account_id UUID)");
        jdbc.execute("CREATE TABLE SPRING_SESSION (SESSION_ID CHAR(36))");
    }

    @Test
    void deletesExpiredTerminalOrdersAndPreservesRecentOrUnfinishedOrders() {
        UUID oldOrder = UUID.randomUUID();
        UUID recentOrder = UUID.randomUUID();
        UUID unfinishedOrder = UUID.randomUUID();
        Instant now = Instant.now();
        insertOrder(oldOrder, now.minusSeconds(29L * 24 * 60 * 60), "EXPIRED");
        insertOrder(recentOrder, now.minusSeconds(27L * 24 * 60 * 60), "CONFIRMED");
        insertOrder(unfinishedOrder, now.minusSeconds(29L * 24 * 60 * 60), "PENDING");
        UUID eventId = UUID.randomUUID();
        jdbc.update("INSERT INTO outbox_events (id, aggregate_id) VALUES (?, ?)", eventId, oldOrder);
        jdbc.update(
                "INSERT INTO notification_deliveries (event_id, order_id) VALUES (?, ?)",
                eventId, oldOrder
        );
        jdbc.update("INSERT INTO order_items (order_id) VALUES (?)", oldOrder);
        jdbc.update("INSERT INTO order_sagas (order_id) VALUES (?)", oldOrder);
        jdbc.update("INSERT INTO reservations (order_id) VALUES (?)", oldOrder);
        jdbc.update("INSERT INTO payments (order_id) VALUES (?)", oldOrder);
        jdbc.update("INSERT INTO security_audit_log (created_at) VALUES (?)", Timestamp.from(now));
        jdbc.update(
                """
                INSERT INTO security_alerts
                    (id, account_id, session_id, alert_type, created_at, acknowledged_at)
                VALUES (1, ?, 'old-session', 'ACCESS_DENIED_THRESHOLD', ?, ?)
                """,
                UUID.randomUUID(),
                Timestamp.from(now.minusSeconds(29L * 24 * 60 * 60)),
                Timestamp.from(now.minusSeconds(28L * 24 * 60 * 60))
        );
        jdbc.update(
                """
                INSERT INTO security_alerts
                    (id, account_id, session_id, alert_type, created_at)
                VALUES (2, ?, 'pending-session', 'ACCESS_DENIED_THRESHOLD', ?)
                """,
                UUID.randomUUID(),
                Timestamp.from(now.minusSeconds(29L * 24 * 60 * 60))
        );

        new RetentionWorker(jdbc, java.time.Duration.ofDays(28)).purgeExpiredRecords();

        assertEquals(0, count("orders", oldOrder));
        assertEquals(1, count("orders", recentOrder));
        assertEquals(1, count("orders", unfinishedOrder));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ?", Integer.class, oldOrder
        ));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_deliveries WHERE order_id = ?", Integer.class, oldOrder
        ));
        assertEquals(0, count("order_items", oldOrder));
        assertEquals(0, count("order_sagas", oldOrder));
        assertEquals(0, count("reservations", oldOrder));
        assertEquals(0, count("payments", oldOrder));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM security_alerts WHERE id = 1", Integer.class
        ));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM security_alerts WHERE id = 2", Integer.class
        ));
    }

    private void insertOrder(UUID id, Instant createdAt, String status) {
        jdbc.update(
                "INSERT INTO orders (id, created_at, status) VALUES (?, ?, ?)",
                id, Timestamp.from(createdAt), status
        );
    }

    private int count(String table, UUID orderId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE "
                        + (table.equals("orders") ? "id" : "order_id") + " = ?",
                Integer.class,
                orderId
        );
    }
}
