package com.example.orderworkflow.order;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/** Independent, fixed fixtures; no production DTOs or query helpers are used. */
final class OrderSearchFixtures {
    static final Instant BASE_TIME = Instant.parse("2026-11-01T10:00:00Z");
    static final List<String> ORDERED_IDS = List.of("ORD-008", "ORD-007", "ORD-006", "ORD-005",
            "ORD-004", "ORD-003", "ORD-002", "ORD-001", "ORD-000");

    private OrderSearchFixtures() { }

    static void reset(JdbcTemplate jdbc) {
        jdbc.execute("TRUNCATE order_items, idempotency_records, orders, product_catalog");
        jdbc.update("""
                INSERT INTO product_catalog(store_id, sku, product_name, unit_price, currency, active)
                VALUES ('STORE-001', 'SKU-001', 'VT005 fixture product', 100.00, 'VND', true)
                """);
        // Insertion order deliberately differs from the required result order.
        insert(jdbc, "ORD-003", "CUSTOMER-A", "COMPENSATING", BASE_TIME);
        insert(jdbc, "ORD-000", "CUSTOMER-B", "EXPIRED", BASE_TIME.minusSeconds(2));
        insert(jdbc, "ORD-008", "CUSTOMER-A", "PENDING", BASE_TIME.plusSeconds(3));
        insert(jdbc, "ORD-004", "CUSTOMER-B", "EXPIRING", BASE_TIME.plusSeconds(1));
        insert(jdbc, "ORD-001", "CUSTOMER-A", "FAILED", BASE_TIME.minusSeconds(1));
        insert(jdbc, "ORD-006", "CUSTOMER-A", "CONFIRMED", BASE_TIME.plusSeconds(2));
        insert(jdbc, "ORD-002", "CUSTOMER-B", "COMPENSATION_FAILED", BASE_TIME);
        insert(jdbc, "ORD-007", "CUSTOMER-B", "PENDING", BASE_TIME.plusSeconds(3));
        insert(jdbc, "ORD-005", "CUSTOMER-A", "PENDING", BASE_TIME.plusSeconds(2));
    }

    static void insert(JdbcTemplate jdbc, String id, String customer, String status, Instant createdAt) {
        jdbc.update("""
                INSERT INTO orders(order_id, customer_id, client_id, store_id, status, total_amount,
                    currency, reservation_id, correlation_id, created_at, updated_at, expire_at)
                VALUES (?, ?, 'CLIENT-FIXTURE', 'STORE-001', ?, 100.00, 'VND', ?, ?, ?, ?, ?)
                """, id, customer, status, "RES-" + id, "corr-" + id,
                Timestamp.from(createdAt), Timestamp.from(createdAt), Timestamp.from(createdAt.plusSeconds(900)));
    }

    static HttpEntity<?> createRequest(String clientId, String key, int quantity) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Client-Id", clientId);
        headers.set("Idempotency-Key", key);
        headers.set("X-Correlation-Id", "corr-vt005-contract");
        var body = Map.of("customerId", "CUSTOMER-A", "storeId", "STORE-001",
                "items", List.of(Map.of("sku", "SKU-001", "quantity", quantity)));
        return new HttpEntity<>(body, headers);
    }

    static Map<String, List<Map<String, Object>>> snapshot(JdbcTemplate jdbc) {
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        result.put("orders", jdbc.queryForList("SELECT * FROM orders ORDER BY order_id"));
        result.put("items", jdbc.queryForList("SELECT * FROM order_items ORDER BY order_id, sku"));
        result.put("idempotency", jdbc.queryForList("SELECT * FROM idempotency_records ORDER BY scope, idempotency_key"));
        result.put("catalog", jdbc.queryForList("SELECT * FROM product_catalog ORDER BY store_id, sku"));
        return result;
    }
}
