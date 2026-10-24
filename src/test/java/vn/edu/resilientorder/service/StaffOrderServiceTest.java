package vn.edu.resilientorder.service;

import jakarta.servlet.http.HttpServletRequest;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.resilientorder.api.OrderResponse;
import vn.edu.resilientorder.api.StaffOrderController.FulfillmentStatus;
import vn.edu.resilientorder.security.AccessAuditService;
import vn.edu.resilientorder.security.AccountRole;
import vn.edu.resilientorder.security.AuthenticatedUser;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class StaffOrderServiceTest {
    private JdbcTemplate jdbc;
    private StaffOrderService staffOrderService;
    private OrderResponse response;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:staff-orders-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE ALIAS CLOCK_TIMESTAMP FOR
                "vn.edu.resilientorder.service.StaffOrderServiceTest.clockTimestamp"
                """);
        jdbc.execute("""
                CREATE TABLE orders (
                    id UUID PRIMARY KEY,
                    store_id VARCHAR(100),
                    status VARCHAR(32),
                    fulfillment_status VARCHAR(32),
                    assigned_staff_id UUID,
                    created_at TIMESTAMP WITH TIME ZONE,
                    updated_at TIMESTAMP WITH TIME ZONE
                )
                """);
        OrderService orderService = mock(OrderService.class);
        when(orderService.readForStaff(any(UUID.class))).thenAnswer(invocation -> response);
        staffOrderService = new StaffOrderService(
                jdbc, orderService, mock(AccessAuditService.class), Duration.ofDays(26)
        );
        response = new OrderResponse(
                UUID.randomUUID(), "CONFIRMED", "RECEIVED", "COMPLETED",
                "SUCCEEDED", "RESERVED", "STORE-1",
                Instant.now(), Instant.now().plusSeconds(900),
                "/api/v1/orders/example", java.util.List.of()
        );
    }

    @Test
    void onlyOneStaffMemberCanClaimAnOrderAndFulfillmentMovesForward() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID firstStaffId = UUID.randomUUID();
        UUID secondStaffId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO orders (id, store_id, status, fulfillment_status, created_at)
                VALUES (?, 'STORE-1', 'CONFIRMED', 'RECEIVED', ?)
                """,
                orderId, Timestamp.from(Instant.now())
        );
        AuthenticatedUser firstStaff = staff(firstStaffId, "first");
        AuthenticatedUser secondStaff = staff(secondStaffId, "second");
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(() -> attemptClaim(
                    start, orderId, firstStaff
            ));
            Future<Object> second = executor.submit(() -> attemptClaim(
                    start, orderId, secondStaff
            ));
            start.countDown();
            Object firstResult = first.get();
            Object secondResult = second.get();

            assertEquals(1, (firstResult instanceof OrderResponse ? 1 : 0)
                    + (secondResult instanceof OrderResponse ? 1 : 0));
            assertInstanceOf(ResponseStatusException.class,
                    firstResult instanceof OrderResponse ? secondResult : firstResult);
        } finally {
            executor.shutdownNow();
        }

        UUID assignedStaffId = jdbc.queryForObject(
                "SELECT assigned_staff_id FROM orders WHERE id = ?", UUID.class, orderId
        );
        AuthenticatedUser assignedStaff = assignedStaffId.equals(firstStaffId) ? firstStaff : secondStaff;
        staffOrderService.updateFulfillment(
                orderId, FulfillmentStatus.PREPARING, assignedStaff, mock(HttpServletRequest.class)
        );
        staffOrderService.updateFulfillment(
                orderId, FulfillmentStatus.DELIVERED, assignedStaff, mock(HttpServletRequest.class)
        );

        assertEquals("DELIVERED", jdbc.queryForObject(
                "SELECT fulfillment_status FROM orders WHERE id = ?", String.class, orderId
        ));
        ResponseStatusException cannotReopen = org.junit.jupiter.api.Assertions.assertThrows(
                ResponseStatusException.class,
                () -> staffOrderService.updateFulfillment(
                        orderId, FulfillmentStatus.PREPARING, assignedStaff,
                        mock(HttpServletRequest.class)
                )
        );
        assertEquals(409, cannotReopen.getStatusCode().value());
    }

    private Object attemptClaim(CountDownLatch start, UUID orderId, AuthenticatedUser staff) throws Exception {
        start.await();
        try {
            return staffOrderService.claim(orderId, staff, mock(HttpServletRequest.class));
        } catch (ResponseStatusException exception) {
            return exception;
        }
    }

    private AuthenticatedUser staff(UUID id, String username) {
        return new AuthenticatedUser(id, username, AccountRole.STAFF, "STORE-1");
    }

    public static Timestamp clockTimestamp() {
        return Timestamp.from(Instant.now());
    }
}
