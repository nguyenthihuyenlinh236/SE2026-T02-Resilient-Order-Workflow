package vn.edu.resilientorder.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.resilientorder.api.OrderResponse;
import vn.edu.resilientorder.api.StaffOrderController.FulfillmentStatus;
import vn.edu.resilientorder.security.AccessAuditService;
import vn.edu.resilientorder.security.AuthenticatedUser;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;

@Service
public class StaffOrderService {
    private final JdbcTemplate jdbc;
    private final OrderService orderService;
    private final AccessAuditService accessAuditService;
    private final Duration retentionPeriod;

    public StaffOrderService(
            JdbcTemplate jdbc,
            OrderService orderService,
            AccessAuditService accessAuditService,
            @Value("${app.order.retention:26d}") Duration retentionPeriod
    ) {
        this.jdbc = jdbc;
        this.orderService = orderService;
        this.accessAuditService = accessAuditService;
        this.retentionPeriod = retentionPeriod;
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> listForStore(AuthenticatedUser staff) {
        Instant retentionCutoff = jdbc.queryForObject(
                "SELECT clock_timestamp()", Timestamp.class
        ).toInstant().minus(retentionPeriod);
        List<UUID> orderIds = jdbc.query(
                """
                SELECT id FROM orders
                WHERE store_id = ? AND created_at > ?
                ORDER BY created_at
                LIMIT 100
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                staff.storeId(), Timestamp.from(retentionCutoff)
        );
        return orderIds.stream().map(orderService::readForStaff).toList();
    }

    @Transactional
    public OrderResponse claim(UUID orderId, AuthenticatedUser staff, HttpServletRequest request) {
        List<String> stores = jdbc.query(
                "SELECT store_id FROM orders WHERE id = ?",
                (rs, rowNum) -> rs.getString("store_id"),
                orderId
        );
        if (stores.isEmpty() || !staff.storeId().equals(stores.get(0))) {
            accessAuditService.recordDenied(staff, request, orderId.toString(), "STAFF_ORDER_ACCESS_DENIED");
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found.");
        }

        int claimed = jdbc.update(
                """
                UPDATE orders
                SET assigned_staff_id = ?, updated_at = clock_timestamp()
                WHERE id = ? AND store_id = ? AND status = 'CONFIRMED'
                  AND fulfillment_status = 'RECEIVED' AND assigned_staff_id IS NULL
                """,
                staff.id(), orderId, staff.storeId()
        );
        if (claimed != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Order has already been claimed or is not ready."
            );
        }
        return orderService.readForStaff(orderId);
    }

    @Transactional
    public OrderResponse updateFulfillment(
            UUID orderId,
            FulfillmentStatus nextStatus,
            AuthenticatedUser staff,
            HttpServletRequest request
    ) {
        List<StaffOrderRow> rows = jdbc.query(
                "SELECT store_id, assigned_staff_id, fulfillment_status FROM orders WHERE id = ?",
                (rs, rowNum) -> new StaffOrderRow(
                        rs.getString("store_id"),
                        rs.getObject("assigned_staff_id", UUID.class),
                        rs.getString("fulfillment_status")
                ),
                orderId
        );
        if (rows.isEmpty() || !staff.storeId().equals(rows.get(0).storeId())) {
            accessAuditService.recordDenied(staff, request, orderId.toString(), "STAFF_ORDER_ACCESS_DENIED");
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found.");
        }
        if (!staff.id().equals(rows.get(0).assignedStaffId())) {
            accessAuditService.recordDenied(staff, request, orderId.toString(), "STAFF_ORDER_NOT_ASSIGNED");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Order is assigned to another staff member.");
        }

        String currentStatus = rows.get(0).fulfillmentStatus();
        if (!isValidTransition(currentStatus, nextStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid fulfillment status transition.");
        }
        int updated = jdbc.update(
                """
                UPDATE orders SET fulfillment_status = ?, updated_at = clock_timestamp()
                WHERE id = ? AND store_id = ? AND assigned_staff_id = ? AND fulfillment_status = ?
                """,
                nextStatus.name(), orderId, staff.storeId(), staff.id(), currentStatus
        );
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order status changed concurrently.");
        }
        return orderService.readForStaff(orderId);
    }

    private boolean isValidTransition(String currentStatus, FulfillmentStatus nextStatus) {
        return switch (currentStatus) {
            case "RECEIVED" -> nextStatus == FulfillmentStatus.PREPARING;
            case "PREPARING" -> nextStatus == FulfillmentStatus.DELIVERED;
            default -> false;
        };
    }

    private record StaffOrderRow(String storeId, UUID assignedStaffId, String fulfillmentStatus) {
    }
}
