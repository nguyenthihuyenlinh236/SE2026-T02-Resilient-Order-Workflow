package vn.edu.resilientorder.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.resilientorder.security.AccessAuditService;
import vn.edu.resilientorder.security.AccountRole;
import vn.edu.resilientorder.security.AuthenticatedUser;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceAccessTest {
    private JdbcTemplate jdbc;
    private AccessAuditService accessAuditService;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:order-access-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE ALIAS CLOCK_TIMESTAMP FOR
                "vn.edu.resilientorder.security.AuthenticationServiceTest.clockTimestamp"
                """);
        jdbc.execute("""
                CREATE TABLE orders (
                    id UUID PRIMARY KEY,
                    status VARCHAR(32),
                    fulfillment_status VARCHAR(32),
                    store_id VARCHAR(100),
                    assigned_staff_id UUID,
                    customer_id UUID,
                    created_at TIMESTAMP WITH TIME ZONE,
                    expire_at TIMESTAMP WITH TIME ZONE
                )
                """);
        jdbc.execute("CREATE TABLE order_sagas (order_id UUID, status VARCHAR(32))");
        jdbc.execute("CREATE TABLE payments (order_id UUID, capture_status VARCHAR(32))");
        jdbc.execute("CREATE TABLE reservations (order_id UUID, status VARCHAR(32))");
        jdbc.execute("CREATE TABLE order_items (order_id UUID, sku VARCHAR(100), quantity INTEGER)");
        accessAuditService = mock(AccessAuditService.class);
        orderService = new OrderService(
                jdbc, new ObjectMapper(), accessAuditService, Duration.ofMinutes(15), Duration.ofDays(26)
        );
    }

    @Test
    void crossAccountAndUnknownOrderReturnTheSameNotFoundResponseAndAreAudited() {
        UUID ownerId = UUID.randomUUID();
        UUID orderId = insertOrder(ownerId, Instant.now().minusSeconds(60));
        UUID attackerId = UUID.randomUUID();
        AuthenticatedUser attacker = new AuthenticatedUser(
                attackerId, "attacker", AccountRole.CUSTOMER, null
        );
        HttpServletRequest request = mock(HttpServletRequest.class);

        ResponseStatusException crossAccount = assertThrows(
                ResponseStatusException.class,
                () -> orderService.get(orderId, attacker, request)
        );
        ResponseStatusException unknownOrder = assertThrows(
                ResponseStatusException.class,
                () -> orderService.get(UUID.randomUUID(), attacker, request)
        );

        assertEquals(crossAccount.getStatusCode(), unknownOrder.getStatusCode());
        assertEquals(crossAccount.getReason(), unknownOrder.getReason());
        assertEquals("Order not found.", crossAccount.getReason());
        verify(accessAuditService, times(2)).recordDenied(
                eq(attacker), eq(request), org.mockito.ArgumentMatchers.anyString(),
                eq("ORDER_ACCESS_DENIED")
        );
    }

    @Test
    void customerCanReadOnlyRecentOwnOrdersAndExpiredOrdersReturnGone() {
        UUID customerId = UUID.randomUUID();
        UUID recentId = insertOrder(customerId, Instant.now().minus(Duration.ofDays(26)).plusSeconds(60));
        UUID expiredId = insertOrder(customerId, Instant.now().minus(Duration.ofDays(26)).minusSeconds(60));
        jdbc.update("INSERT INTO order_items (order_id, sku, quantity) VALUES (?, 'SKU-1', 2)", recentId);
        AuthenticatedUser customer = new AuthenticatedUser(
                customerId, "customer", AccountRole.CUSTOMER, null
        );
        when(accessAuditService.canAccessOrder(customer, customerId, "DEMO-STORE")).thenReturn(true);

        assertEquals(customerId, jdbc.queryForObject(
                "SELECT customer_id FROM orders WHERE id = ?", UUID.class, recentId
        ));
        assertEquals(0, jdbc.queryForObject(
                "SELECT CASE WHEN created_at <= clock_timestamp() - INTERVAL '26' DAY "
                        + "THEN 1 ELSE 0 END FROM orders WHERE id = ?",
                Integer.class, recentId
        ));
        assertEquals(1, orderService.listForCustomer(customerId, 20).size());
        assertEquals(recentId, orderService.get(recentId, customer, mock(HttpServletRequest.class)).orderId());
        ResponseStatusException expired = assertThrows(
                ResponseStatusException.class,
                () -> orderService.get(expiredId, customer, mock(HttpServletRequest.class))
        );
        assertEquals(410, expired.getStatusCode().value());
        assertTrue(expired.getReason().contains("26-day retention"));
    }

    private UUID insertOrder(UUID customerId, Instant createdAt) {
        UUID orderId = UUID.randomUUID();
        Timestamp created = Timestamp.from(createdAt);
        jdbc.update(
                """
                INSERT INTO orders
                    (id, status, fulfillment_status, store_id, customer_id, created_at, expire_at)
                VALUES (?, 'CONFIRMED', 'RECEIVED', 'DEMO-STORE', ?, ?, ?)
                """,
                orderId, customerId, created, Timestamp.from(createdAt.plus(Duration.ofMinutes(15)))
        );
        jdbc.update("INSERT INTO order_sagas (order_id, status) VALUES (?, 'COMPLETED')", orderId);
        return orderId;
    }
}
