package com.example.orderworkflow.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.orderworkflow.order.api.OrderDtos.ErrorResponse;
import com.example.orderworkflow.order.api.OrderDtos.OrderResponse;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true", "spring.sql.init.mode=never",
        "management.health.rabbit.enabled=false", "order.ttl=15m"})
class OrderIntegrationTests {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_order_item_trigger ON order_items");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_order_item_insert()");
        jdbc.execute("TRUNCATE order_items, idempotency_records, orders, product_catalog");
        jdbc.update("""
                INSERT INTO product_catalog(store_id, sku, product_name, unit_price, currency, active)
                VALUES ('STORE-001','SKU-001','Product 1',125000.00,'VND',true),
                       ('STORE-001','SKU-002','Product 2',75000.00,'VND',true),
                       ('STORE-001','INACTIVE','Inactive',50000.00,'VND',false),
                       ('STORE-002','SKU-001','Other store product',1.00,'VND',true)
                """);
    }

    @Test
    void createsPendingOrderWithDatabaseTtl() {
        var result = create("key-create", request(items(item("SKU-001", 2))));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().status()).isEqualTo("PENDING");
        assertThat(result.getBody().correlationId()).isEqualTo("corr-test");
        assertThat(Duration.between(result.getBody().createdAt(), result.getBody().expireAt()))
                .isEqualTo(Duration.ofMinutes(15));
        assertThat(count("orders")).isOne();
    }

    @Test
    void storesNormalizedItemsPricesAndTotal() {
        var body = request(items(item("SKU-002", 1), item("SKU-001", 1), item("SKU-001", 2)));
        var created = create("key-pricing", body).getBody();

        assertThat(created).isNotNull();
        assertThat(jdbc.queryForObject("SELECT total_amount FROM orders WHERE order_id = ?",
                BigDecimal.class, created.orderId())).isEqualByComparingTo("450000.00");
        var rows = jdbc.queryForList("""
                SELECT sku, quantity, unit_price FROM order_items WHERE order_id = ? ORDER BY sku
                """, created.orderId());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("sku")).isEqualTo("SKU-001");
        assertThat(rows.get(0).get("quantity")).isEqualTo(3);
        assertThat((BigDecimal) rows.get(0).get("unit_price")).isEqualByComparingTo("125000.00");
    }

    @Test
    void getsExistingOrderAndReportsMissingOrInvalidIdentifiers() {
        var created = create("key-get", request(items(item("SKU-001", 1)))).getBody();

        var found = http.getForEntity("/api/v1/orders/{id}", OrderResponse.class, created.orderId());
        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(found.getBody()).isEqualTo(created);

        var missing = http.getForEntity("/api/v1/orders/ORD-missing", ErrorResponse.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody().error().code()).isEqualTo("ORDER_NOT_FOUND");

        var invalid = http.getForEntity("/api/v1/orders/bad%20id", ErrorResponse.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody().error().code()).isEqualTo("INVALID_IDENTIFIER");
    }

    @Test
    void sameKeyAndNormalizedPayloadReplaysOriginalOrderWithoutExtendingTtl() {
        var first = create("key-replay", request(items(item("SKU-001", 1), item("SKU-001", 2))));
        jdbc.update("UPDATE product_catalog SET unit_price = 999999.00 WHERE store_id='STORE-001' AND sku='SKU-001'");
        jdbc.update("UPDATE orders SET status = 'CONFIRMED' WHERE order_id = ?", first.getBody().orderId());
        var replay = create("key-replay", request(items(item("SKU-001", 3))));

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(replay.getBody().status()).isEqualTo("PENDING");
        assertThat(http.getForEntity("/api/v1/orders/{id}", OrderResponse.class,
                first.getBody().orderId()).getBody().status()).isEqualTo("CONFIRMED");
        assertThat(count("orders")).isOne();
        assertThat(count("order_items")).isOne();
    }

    @Test
    void sameKeyWithDifferentNormalizedPayloadIsConflict() {
        create("key-conflict", request(items(item("SKU-001", 1))));
        var conflict = createError("key-conflict", request(items(item("SKU-001", 2))));

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().error().code()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(count("orders")).isOne();
    }

    @Test
    void sameKeyIsScopedByTechnicalClientIdNotCustomerId() {
        var body = request(items(item("SKU-001", 1)));
        var firstHeaders = headers("shared-key");
        var secondHeaders = headers("shared-key");
        secondHeaders.set("X-Client-Id", "CLIENT-002");

        var first = http.exchange("/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>(body, firstHeaders), OrderResponse.class);
        var second = http.exchange("/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>(body, secondHeaders), OrderResponse.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getBody().orderId()).isNotEqualTo(first.getBody().orderId());
        assertThat(count("orders")).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT client_id FROM orders ORDER BY client_id", String.class))
                .containsExactly("CLIENT-001", "CLIENT-002");
    }

    @Test
    void concurrentRequestsWithSameKeyCreateOneOrder() throws Exception {
        int requests = 10;
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(requests);
        try {
            List<Future<OrderResponse>> futures = new ArrayList<>();
            for (int index = 0; index < requests; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    var response = create("key-concurrent", request(items(item("SKU-001", 1))));
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
                    return response.getBody();
                }));
            }
            start.countDown();
            var ids = new HashSet<String>();
            for (var future : futures) {
                ids.add(future.get().orderId());
            }
            assertThat(ids).hasSize(1);
            assertThat(count("orders")).isOne();
            assertThat(count("idempotency_records")).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void malformedRequestsAndUnavailableCatalogAreRejectedWithoutWrites() {
        List<String> invalidJson = List.of(
                "{}",
                "{\"customerId\":\"CUSTOMER-001\",\"storeId\":\"STORE-001\",\"items\":[]}",
                jsonWithItems("[{\"sku\":\"SKU-001\",\"quantity\":0}]"),
                jsonWithItems("[{\"sku\":\"SKU-001\",\"quantity\":1.5}]"),
                jsonWithItems("[{\"sku\":\"SKU-001\",\"quantity\":\"1\"}]"),
                jsonWithItems("[{\"sku\":\"SKU-001\",\"quantity\":1,\"price\":1}]"));
        int index = 0;
        for (String json : invalidJson) {
            var response = rawCreate("invalid-" + index++, json, true);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(rawCreate(null, jsonWithItems("[{\"sku\":\"SKU-001\",\"quantity\":1}]"), false)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(createError("missing-product", request(items(item("MISSING", 1)))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(createError("inactive-product", request(items(item("INACTIVE", 1)))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(count("orders")).isZero();
        assertThat(count("idempotency_records")).isZero();
    }

    @Test
    void customerStoreAndSkuRejectNonStringJsonScalars() {
        List<String> invalidJson = List.of(
                "{\"customerId\":123,\"storeId\":\"STORE-001\","
                        + "\"items\":[{\"sku\":\"SKU-001\",\"quantity\":1}]}",
                "{\"customerId\":true,\"storeId\":\"STORE-001\","
                        + "\"items\":[{\"sku\":\"SKU-001\",\"quantity\":1}]}",
                "{\"customerId\":\"CUSTOMER-001\",\"storeId\":123,"
                        + "\"items\":[{\"sku\":\"SKU-001\",\"quantity\":1}]}",
                "{\"customerId\":\"CUSTOMER-001\",\"storeId\":false,"
                        + "\"items\":[{\"sku\":\"SKU-001\",\"quantity\":1}]}",
                "{\"customerId\":\"CUSTOMER-001\",\"storeId\":\"STORE-001\","
                        + "\"items\":[{\"sku\":123,\"quantity\":1}]}",
                "{\"customerId\":\"CUSTOMER-001\",\"storeId\":\"STORE-001\","
                        + "\"items\":[{\"sku\":true,\"quantity\":1}]}");

        int index = 0;
        for (String json : invalidJson) {
            var response = rawCreate("strict-string-" + index++, json, true);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(count("orders")).isZero();
        assertThat(count("idempotency_records")).isZero();
    }

    @Test
    void itemInsertFailureRollsBackOrderAndIdempotencyClaim() {
        jdbc.execute("""
                CREATE FUNCTION fail_order_item_insert() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected item failure'; END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_order_item_trigger BEFORE INSERT ON order_items
                FOR EACH ROW EXECUTE FUNCTION fail_order_item_insert()
                """);

        var failed = createError("key-rollback", request(items(item("SKU-001", 1))));

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(count("orders")).isZero();
        assertThat(count("order_items")).isZero();
        assertThat(count("idempotency_records")).isZero();
    }

    private org.springframework.http.ResponseEntity<OrderResponse> create(String key, Map<String, Object> body) {
        return http.exchange("/api/v1/orders", HttpMethod.POST, new HttpEntity<>(body, headers(key)),
                OrderResponse.class);
    }

    private org.springframework.http.ResponseEntity<ErrorResponse> createError(String key, Map<String, Object> body) {
        return http.exchange("/api/v1/orders", HttpMethod.POST, new HttpEntity<>(body, headers(key)),
                ErrorResponse.class);
    }

    private org.springframework.http.ResponseEntity<ErrorResponse> rawCreate(String key, String body,
            boolean includeCorrelation) {
        var headers = headers(key);
        if (!includeCorrelation) {
            headers.remove("X-Correlation-Id");
        }
        return http.exchange("/api/v1/orders", HttpMethod.POST, new HttpEntity<>(body, headers),
                ErrorResponse.class);
    }

    private static HttpHeaders headers(String key) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) headers.set("Idempotency-Key", key);
        headers.set("X-Client-Id", "CLIENT-001");
        headers.set("X-Correlation-Id", "corr-test");
        return headers;
    }

    private static Map<String, Object> request(List<Map<String, Object>> items) {
        return Map.of("customerId", "CUSTOMER-001", "storeId", "STORE-001", "items", items);
    }

    @SafeVarargs
    private static List<Map<String, Object>> items(Map<String, Object>... items) {
        return List.of(items);
    }

    private static Map<String, Object> item(String sku, int quantity) {
        return Map.of("sku", sku, "quantity", quantity);
    }

    private static String jsonWithItems(String items) {
        return "{\"customerId\":\"CUSTOMER-001\",\"storeId\":\"STORE-001\",\"items\":" + items + "}";
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
