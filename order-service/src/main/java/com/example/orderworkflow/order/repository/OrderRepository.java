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

    public boolean claimIdempotencyKey(String key, String payloadHash) {
        return jdbc.update("""
                INSERT INTO order_idempotency(idempotency_key, payload_hash)
                VALUES (?, ?) ON CONFLICT DO NOTHING
                """, key, payloadHash) == 1;
    }

    public Optional<IdempotencyRecord> findIdempotency(String key) {
        return jdbc.query("SELECT payload_hash, order_id FROM order_idempotency WHERE idempotency_key = ?",
                (rs, row) -> new IdempotencyRecord(rs.getString(1), rs.getString(2)), key)
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

    public void insertOrder(String orderId, String customerId, String storeId, BigDecimal total,
            String currency, String reservationId, String correlationId, Instant createdAt, Instant expireAt) {
        jdbc.update("""
                INSERT INTO orders(order_id, customer_id, store_id, status, total_amount, currency,
                                   reservation_id, correlation_id, created_at, updated_at, expire_at)
                VALUES (?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?)
                """, orderId, customerId, storeId, total, currency, reservationId, correlationId,
                Timestamp.from(createdAt), Timestamp.from(createdAt), Timestamp.from(expireAt));
    }

    public void insertItems(String orderId, Map<String, Integer> quantities,
            Map<String, CatalogProduct> products) {
        for (var entry : quantities.entrySet()) {
            jdbc.update("INSERT INTO order_items(order_id, sku, quantity, unit_price) VALUES (?, ?, ?, ?)",
                    orderId, entry.getKey(), entry.getValue(), products.get(entry.getKey()).unitPrice());
        }
    }

    public void completeIdempotency(String key, String orderId) {
        int changed = jdbc.update("""
                UPDATE order_idempotency SET order_id = ?, completed_at = clock_timestamp()
                WHERE idempotency_key = ? AND order_id IS NULL
                """, orderId, key);
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
