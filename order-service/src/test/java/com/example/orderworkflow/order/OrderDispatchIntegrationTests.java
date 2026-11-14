package com.example.orderworkflow.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.orderworkflow.order.api.OrderDtos.OrderResponse;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true", "spring.sql.init.mode=never",
        "management.health.rabbit.enabled=false", "order.ttl=15m", "order.worker.enabled=true",
        "order.worker.poll-interval=50", "order.worker.lease=10s", "order.worker.retry-delay=250ms",
        "inventory.connect-timeout=1s", "inventory.read-timeout=2s"})
class OrderDispatchIntegrationTests {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final InventoryFixture INVENTORY = new InventoryFixture();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("inventory.base-url", INVENTORY::baseUrl);
    }

    @AfterAll
    static void closeFixture() { INVENTORY.close(); }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void resetDatabase() {
        INVENTORY.reset();
        jdbc.execute("TRUNCATE saga_attempts,saga_steps,sagas,outbox_events,order_items,idempotency_records,orders,product_catalog");
        jdbc.update("INSERT INTO product_catalog(store_id,sku,product_name,unit_price,currency,active) "
                + "VALUES ('STORE-001','SKU-001','Product 1',125000.00,'VND',true)");
    }

    @Test
    void postDispatchesPersistedReserveIntentToInventory() throws Exception {
        assertThat(INVENTORY.awaitReady(Duration.ofSeconds(2))).as("Inventory fixture readiness").isTrue();
        var response = create("key-dispatch");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        OrderResponse order = response.getBody();
        assertThat(order.status()).isEqualTo("PENDING");
        var saved = jdbc.queryForMap("SELECT operation,status,idempotency_key,request_payload::text payload "
                + "FROM saga_steps");
        assertThat(saved.get("operation")).isEqualTo("RESERVE");
        assertThat(saved.get("idempotency_key")).isEqualTo("reserve:" + order.orderId() + ":v1");
        JsonNode snapshot = json.readTree((String) saved.get("payload"));
        assertThat(snapshot.path("orderId").asText()).isEqualTo(order.orderId());

        assertThat(INVENTORY.awaitReserve(Duration.ofSeconds(5)))
                .as("Ready Inventory fixture receives persisted Reserve intent; captured=%s", INVENTORY.request.get())
                .isTrue();
        var captured = INVENTORY.request.get();
        assertThat(captured.method()).isEqualTo("POST");
        assertThat(captured.path()).isEqualTo("/api/v1/inventory/reservations");
        assertThat(captured.headers().getFirst("Idempotency-Key")).isEqualTo(saved.get("idempotency_key"));
        assertThat(captured.body()).isEqualTo(snapshot);

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        String stepStatus;
        do {
            stepStatus = jdbc.queryForObject("SELECT status FROM saga_steps", String.class);
            if ("SUCCEEDED".equals(stepStatus)) break;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        assertThat(stepStatus).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT reservation_status FROM orders", String.class)).isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("SELECT reservation_verified FROM orders", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM orders", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM sagas", String.class)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM saga_steps", Integer.class)).isEqualTo(1);
    }

    @Test
    void verifiedRejectionCompletesGuardedFailureBranch() throws Exception {
        INVENTORY.outcome = "REJECTED";
        var response = create("key-rejected");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        OrderResponse order = response.getBody();
        assertThat(order).isNotNull();
        assertThat(INVENTORY.awaitReserve(Duration.ofSeconds(5))).isTrue();
        awaitStepStatus("FAILED");
        assertThat(jdbc.queryForObject("SELECT reservation_status FROM orders", String.class)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT reservation_verified FROM orders", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM orders", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT failure_class FROM saga_steps", String.class)).isEqualTo("BUSINESS");
        assertThat(jdbc.queryForObject("SELECT status FROM sagas", String.class)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type='OrderFailed'", Integer.class)).isOne();
        assertThat(http.getForObject("/api/v1/orders/" + order.orderId(), OrderResponse.class).status())
                .isEqualTo("FAILED");
    }

    @Test
    void lostReserveResponseRemainsUnknownAndDoesNotFailOrder() throws Exception {
        INVENTORY.outcome = "DROP";
        var response = create("key-unknown");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        OrderResponse order = response.getBody();
        assertThat(order).isNotNull();
        assertThat(INVENTORY.awaitReserve(Duration.ofSeconds(5))).isTrue();
        awaitStepStatus("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT reservation_status FROM orders", String.class)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT reservation_verified FROM orders", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM orders", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT failure_class FROM saga_steps", String.class)).isEqualTo("UNKNOWN");
    }

    private org.springframework.http.ResponseEntity<OrderResponse> create(String key) {
        var body = Map.of("customerId", "CUSTOMER-001", "storeId", "STORE-001",
                "items", List.of(Map.of("sku", "SKU-001", "quantity", 2)));
        var headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        headers.set("X-Client-Id", "CLIENT-001");
        headers.set("X-Correlation-Id", "corr-dispatch");
        return http.postForEntity("/api/v1/orders", new org.springframework.http.HttpEntity<>(body, headers),
                OrderResponse.class);
    }

    private void awaitStepStatus(String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        String status;
        do {
            status = jdbc.queryForObject("SELECT status FROM saga_steps", String.class);
            if (expected.equals(status)) return;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        assertThat(status).isEqualTo(expected);
    }

    private record Captured(String method, String path, HttpHeaders headers, JsonNode body) { }

    private static final class InventoryFixture implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final CountDownLatch ready = new CountDownLatch(1);
        private volatile CountDownLatch reserve = new CountDownLatch(1);
        private final AtomicReference<Captured> request = new AtomicReference<>();
        private final AtomicInteger requests = new AtomicInteger();
        private final ObjectMapper json = new ObjectMapper();
        private volatile String outcome = "RESERVED";

        InventoryFixture() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.setExecutor(executor);
                server.createContext("/", this::handle);
                server.start();
                var probe = (java.net.HttpURLConnection) new java.net.URL(baseUrl() + "/__ready").openConnection();
                probe.setConnectTimeout(500);
                probe.setReadTimeout(500);
                if (probe.getResponseCode() != 200) throw new IllegalStateException("Inventory fixture is not ready");
            } catch (IOException failure) {
                executor.shutdownNow();
                throw new IllegalStateException("Could not start Inventory fixture", failure);
            }
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        boolean awaitReady(Duration timeout) throws InterruptedException { return ready.await(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        boolean awaitReserve(Duration timeout) throws InterruptedException { return reserve.await(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        void reset() { request.set(null); requests.set(0); reserve = new CountDownLatch(1); outcome = "RESERVED"; }

        private void handle(HttpExchange exchange) throws IOException {
            if ("GET".equals(exchange.getRequestMethod()) && "/__ready".equals(exchange.getRequestURI().getPath())) {
                ready.countDown();
                respond(exchange, 200, "ready");
                return;
            }
            JsonNode requestBody = json.readTree(exchange.getRequestBody().readAllBytes());
            var headers = new HttpHeaders();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, List.copyOf(values)));
            request.set(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers, requestBody));
            requests.incrementAndGet();
            reserve.countDown();
            if ("DROP".equals(outcome)) {
                exchange.close();
                return;
            }
            var response = new LinkedHashMap<String, Object>();
            response.put("reservationId", requestBody.path("reservationId").asText());
            response.put("orderId", requestBody.path("orderId").asText());
            response.put("storeId", requestBody.path("storeId").asText());
            response.put("reserveKey", exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            response.put("status", outcome);
            response.put("rejectionReason", "REJECTED".equals(outcome) ? "INSUFFICIENT_STOCK" : null);
            response.put("items", requestBody.path("items"));
            response.put("createdAt", "2026-11-14T00:00:00Z");
            respond(exchange, 200, json.writeValueAsString(response));
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
        }

        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
