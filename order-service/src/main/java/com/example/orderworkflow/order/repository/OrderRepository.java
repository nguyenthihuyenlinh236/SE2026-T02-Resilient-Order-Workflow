package com.example.orderworkflow.order.repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.orderworkflow.order.repository.OrderRecords.CatalogProduct;
import com.example.orderworkflow.order.repository.OrderRecords.IdempotencyRecord;
import com.example.orderworkflow.order.repository.OrderRecords.OrderRecord;

@Repository
public class OrderRepository {
    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean claimIdempotencyKey(String clientId, String key, String payloadHash, String requestPayload) {
        return jdbc.update("""
                INSERT INTO idempotency_records(scope, idempotency_key, operation, payload_hash, request_payload)
                VALUES (?, ?, 'CREATE_ORDER', ?, CAST(? AS jsonb)) ON CONFLICT DO NOTHING
                """, clientId, key, payloadHash, requestPayload) == 1;
    }

    public Optional<IdempotencyRecord> findIdempotency(String clientId, String key) {
        return jdbc.query("""
                SELECT payload_hash, order_id, response_payload::text FROM idempotency_records
                WHERE scope = ? AND idempotency_key = ?
                """, (rs, row) -> new IdempotencyRecord(rs.getString(1), rs.getString(2), rs.getString(3)),
                clientId, key)
                .stream().findFirst();
    }

    public List<CatalogProduct> lockCatalog(String storeId, List<String> skus) {
        String placeholders = String.join(",", java.util.Collections.nCopies(skus.size(), "?"));
        List<Object> parameters = new ArrayList<>();
        parameters.add(storeId);
        parameters.addAll(skus);
        return jdbc.query("SELECT sku, unit_price, currency, active FROM product_catalog "
                        + "WHERE store_id = ? AND sku IN (" + placeholders + ") ORDER BY sku FOR SHARE",
                (rs, row) -> new CatalogProduct(rs.getString("sku"), rs.getBigDecimal("unit_price"),
                        rs.getString("currency"), rs.getBoolean("active")), parameters.toArray());
    }

    public Instant databaseTime() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    public void insertOrder(String orderId, String customerId, String clientId, String storeId, BigDecimal total,
            String currency, String reservationId, String correlationId, Instant createdAt, Instant expireAt) {
        jdbc.update("""
                INSERT INTO orders(order_id, customer_id, client_id, store_id, status, total_amount, currency,
                                   reservation_id, correlation_id, created_at, updated_at, expire_at)
                VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?)
                """, orderId, customerId, clientId, storeId, total, currency, reservationId, correlationId,
                Timestamp.from(createdAt), Timestamp.from(createdAt), Timestamp.from(expireAt));
    }

    public void insertItems(String orderId, Map<String, Integer> quantities,
            Map<String, CatalogProduct> products) {
        for (var entry : quantities.entrySet()) {
            jdbc.update("INSERT INTO order_items(order_id, sku, quantity, unit_price) VALUES (?, ?, ?, ?)",
                    orderId, entry.getKey(), entry.getValue(), products.get(entry.getKey()).unitPrice());
        }
    }

    public void completeIdempotency(String clientId, String key, String orderId, String responsePayload) {
        int changed = jdbc.update("""
                UPDATE idempotency_records
                SET status = 'COMPLETED', response_code = 202, response_payload = CAST(? AS jsonb),
                    resource_id = ?, order_id = ?, updated_at = clock_timestamp(),
                    completed_at = clock_timestamp()
                WHERE scope = ? AND idempotency_key = ? AND status = 'IN_PROGRESS'
                """, responsePayload, orderId, orderId, clientId, key);
        if (changed != 1) {
            throw new IllegalStateException("Idempotency result could not be completed");
        }
    }

    public Optional<OrderRecord> findOrder(String orderId) {
        return jdbc.query("""
                SELECT order_id, status, created_at, expire_at, correlation_id
                FROM orders WHERE order_id = ?
                """, (rs, row) -> new OrderRecord(rs.getString("order_id"), rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expire_at").toInstant(),
                        rs.getString("correlation_id")), orderId).stream().findFirst();
    }
}
