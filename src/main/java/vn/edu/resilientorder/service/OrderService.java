package vn.edu.resilientorder.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.resilientorder.api.CreateOrderRequest;
import vn.edu.resilientorder.api.OrderResponse;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class OrderService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public OrderService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            @Value("${app.order.ttl:15m}") Duration ttl
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    @Transactional
    public OrderResponse create(String idempotencyKey, CreateOrderRequest request) {
        List<CreateOrderRequest.OrderLineRequest> items = request.items().stream()
                .map(item -> new CreateOrderRequest.OrderLineRequest(item.sku().trim(), item.quantity()))
                .sorted(Comparator.comparing(CreateOrderRequest.OrderLineRequest::sku))
                .toList();
        if (items.stream().map(CreateOrderRequest.OrderLineRequest::sku).distinct().count() != items.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each SKU may appear only once.");
        }

        String requestHash = hash(items);
        UUID orderId = UUID.randomUUID();
        Instant createdAt = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        Instant expireAt = createdAt.plus(ttl);
        List<UUID> inserted = jdbc.query(
                """
                INSERT INTO orders (id, idempotency_key, request_hash, status, created_at, expire_at, updated_at)
                VALUES (?, ?, ?, 'PENDING', ?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                RETURNING id
                """,
                (resultSet, rowNum) -> resultSet.getObject("id", UUID.class),
                orderId, idempotencyKey, requestHash, Timestamp.from(createdAt),
                Timestamp.from(expireAt), Timestamp.from(createdAt)
        );
        if (inserted.isEmpty()) {
            ExistingOrder existing = jdbc.queryForObject(
                    "SELECT id, request_hash FROM orders WHERE idempotency_key = ?",
                    (rs, rowNum) -> new ExistingOrder(
                            rs.getObject("id", UUID.class), rs.getString("request_hash")
                    ),
                    idempotencyKey
            );
            if (existing == null) {
                throw new IllegalStateException("Idempotency conflict row disappeared before it could be read.");
            }
            if (!requestHash.equals(existing.requestHash())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT: key was used with a different payload."
                );
            }
            return get(existing.id());
        }

        for (CreateOrderRequest.OrderLineRequest item : items) {
            jdbc.update(
                    "INSERT INTO order_items (order_id, sku, quantity) VALUES (?, ?, ?)",
                    orderId, item.sku(), item.quantity()
            );
        }
        jdbc.update(
                """
                INSERT INTO order_sagas (order_id, status, current_step, updated_at)
                VALUES (?, 'RUNNING', 'RESERVE_INVENTORY', ?)
                """,
                orderId, Timestamp.from(createdAt)
        );
        addOutboxEvent(orderId, "OrderCreated", createdAt);
        return get(orderId);
    }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID orderId) {
        List<OrderRow> rows = jdbc.query(
                """
                SELECT o.id, o.status, o.created_at, o.expire_at,
                       s.status AS saga_status,
                       p.capture_status AS payment_status,
                       r.status AS reservation_status
                FROM orders o
                LEFT JOIN order_sagas s ON s.order_id = o.id
                LEFT JOIN payments p ON p.order_id = o.id
                LEFT JOIN reservations r ON r.order_id = o.id
                WHERE o.id = ?
                """,
                (rs, rowNum) -> new OrderRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expire_at").toInstant(),
                        rs.getString("saga_status"),
                        rs.getString("payment_status"),
                        rs.getString("reservation_status")
                ),
                orderId
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found.");
        }
        List<OrderResponse.OrderItemResponse> items = jdbc.query(
                "SELECT sku, quantity FROM order_items WHERE order_id = ? ORDER BY sku",
                (rs, rowNum) -> new OrderResponse.OrderItemResponse(
                        rs.getString("sku"), rs.getInt("quantity")
                ),
                orderId
        );
        OrderRow order = rows.get(0);
        return new OrderResponse(
                order.id(), order.status(), order.sagaStatus(), order.paymentStatus(),
                order.reservationStatus(), order.createdAt(), order.expireAt(),
                "/api/v1/orders/" + order.id(), items
        );
    }

    void addOutboxEvent(UUID orderId, String eventType, Instant occurredAt) {
        try {
            String payload = objectMapper.writeValueAsString(
                    new OrderEvent(orderId, eventType, occurredAt)
            );
            jdbc.update(
                    """
                    INSERT INTO outbox_events (id, aggregate_id, event_type, payload, created_at)
                    VALUES (?, ?, ?, CAST(? AS JSONB), ?)
                    """,
                    UUID.randomUUID(), orderId, eventType, payload, Timestamp.from(occurredAt)
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize order event.", exception);
        }
    }

    private String hash(List<CreateOrderRequest.OrderLineRequest> items) {
        try {
            byte[] serialized = objectMapper.writeValueAsBytes(items);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(serialized)
            );
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to calculate request fingerprint.", exception);
        }
    }

    private record OrderRow(
            UUID id,
            String status,
            Instant createdAt,
            Instant expireAt,
            String sagaStatus,
            String paymentStatus,
            String reservationStatus
    ) {
    }

    private record ExistingOrder(UUID id, String requestHash) {
    }

    private record OrderEvent(UUID orderId, String eventType, Instant occurredAt) {
    }
}
