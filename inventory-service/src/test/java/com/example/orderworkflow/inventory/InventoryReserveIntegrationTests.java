package com.example.orderworkflow.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.orderworkflow.inventory.api.InventoryDtos.*;
import com.example.orderworkflow.inventory.service.InventoryService;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true", "spring.sql.init.mode=never", "management.health.rabbit.enabled=false"})
class InventoryReserveIntegrationTests {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired InventoryService service;

    @BeforeEach
    void seedIsolatedDatabase() {
        // Only this throwaway container is connected; never truncate the demo DB.
        jdbc.execute("TRUNCATE reservation_items, reservations, inventory_order_guards, inventory");
        stock("STORE-A", "ITEM", 8);
        stock("STORE-A", "EMPTY", 0);
        stock("STORE-B", "ITEM", 20);
    }

    @Test
    void reservesCallerIdAndQueriesByIdOrderAndKey() {
        var request = request("STORE-A", "ITEM", 2);
        var response = post(request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var reservation = response.getBody();
        assertThat(reservation).isNotNull();
        assertThat(reservation.reservationId()).isEqualTo(request.reservationId());
        assertThat(reservation.orderId()).isEqualTo(request.orderId());
        assertThat(reservation.storeId()).isEqualTo("STORE-A");
        assertThat(reservation.status()).isEqualTo("RESERVED");
        assertThat(reservation.items()).containsExactly(new Item("ITEM", 2));
        assertThat(reservation.createdAt()).isNotNull();
        for (String selector : List.of("/" + reservation.reservationId(), "?orderId=" + request.orderId(),
                "?reserveKey=" + key(request), "?idempotencyKey=" + key(request))) {
            var found = http.getForEntity("/api/v1/inventory/reservations" + selector, Reservation.class);
            assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(found.getBody()).isEqualTo(reservation);
        }
        assertThat(service.stock("STORE-A", "ITEM").availableQuantity()).isEqualTo(6);
        assertThat(service.stock("STORE-B", "ITEM").availableQuantity()).isEqualTo(20);
    }

    @Test
    void insufficientStockAndUnknownSkuPersistRejectionWithoutAllocation() {
        for (String sku : List.of("EMPTY", "UNKNOWN")) {
            var request = request("STORE-A", sku, 1);
            var response = post(request);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().status()).isEqualTo("REJECTED");
            assertThat(response.getBody().rejectionReason()).isEqualTo(
                    sku.equals("EMPTY") ? "INSUFFICIENT_STOCK" : "PRODUCT_NOT_FOUND");
            assertThat(service.lookup(null, key(request))).isEqualTo(response.getBody());
            assertThat(post(request).getBody()).isEqualTo(response.getBody());
        }
        assertThat(service.stock("STORE-A", "ITEM").reservedQuantity()).isZero();
    }

    @Test
    void multiItemRejectionDoesNotAllocatePartialStock() {
        var request = new ReserveRequest(UUID.randomUUID(), UUID.randomUUID(), "STORE-A",
                List.of(new Item("ITEM", 2), new Item("EMPTY", 1)));
        assertThat(post(request).getBody().status()).isEqualTo("REJECTED");
        assertThat(service.stock("STORE-A", "ITEM").reservedQuantity()).isZero();
    }

    @Test
    void duplicateItemsAreCombinedAndEquivalentReorderedPayloadReplays() {
        var request = new ReserveRequest(UUID.randomUUID(), UUID.randomUUID(), "STORE-A",
                List.of(new Item("ITEM", 1), new Item("ITEM", 2)));
        var first = post(request).getBody();
        var combined = new ReserveRequest(request.orderId(), request.reservationId(), request.storeId(),
                List.of(new Item("ITEM", 3)));
        assertThat(post(combined).getBody()).isEqualTo(first);
        assertThat(service.stock("STORE-A", "ITEM").reservedQuantity()).isEqualTo(3);
    }

    @Test
    void replayDoesNotAllocateAndChangedPayloadIsConflict() {
        var request = request("STORE-A", "ITEM", 2);
        var first = post(request).getBody();
        for (int i = 0; i < 10; i++) { assertThat(post(request).getBody()).isEqualTo(first); }
        for (var changed : List.of(
                new ReserveRequest(request.orderId(), request.reservationId(), "STORE-A", List.of(new Item("ITEM", 3))),
                new ReserveRequest(request.orderId(), UUID.randomUUID(), "STORE-A", request.items()),
                new ReserveRequest(request.orderId(), request.reservationId(), "STORE-B", request.items()))) {
            var response = http.exchange("/api/v1/inventory/reservations", HttpMethod.POST,
                    entity(changed, key(request)), ApiError.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().code()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        }
        assertThat(service.stock("STORE-A", "ITEM").reservedQuantity()).isEqualTo(2);
        assertThat(service.stock("STORE-B", "ITEM").reservedQuantity()).isZero();
    }

    @Test
    void reservationIdCannotBeReusedForAnotherOrderAndStockRollsBack() {
        var first = request("STORE-A", "ITEM", 2);
        post(first);
        var second = new ReserveRequest(UUID.randomUUID(), first.reservationId(), "STORE-A", first.items());
        var response = http.exchange("/api/v1/inventory/reservations", HttpMethod.POST,
                entity(second, key(second)), ApiError.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("RESERVATION_ID_CONFLICT");
        assertThat(service.stock("STORE-A", "ITEM").reservedQuantity()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservations", Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"sku\":\"ITEM\",\"quantity\":0}", "{\"sku\":\"ITEM\",\"quantity\":-1}",
            "{\"sku\":\"ITEM\",\"quantity\":1.5}", "{\"sku\":\"ITEM\"}",
            "{\"sku\":\" \",\"quantity\":1}", "{\"productId\":\"00000000-0000-4000-8000-000000000001\",\"quantity\":1}",
            "null"})
    void invalidItemReturns400WithoutWrites(String item) {
        var request = request("STORE-A", "ITEM", 1);
        String json = "{\"orderId\":\"" + request.orderId() + "\",\"reservationId\":\"" + request.reservationId()
                + "\",\"storeId\":\"STORE-A\",\"items\":[" + item + "]}";
        assertBadRequest(json, key(request));
    }

    @Test
    void missingCommandFieldsHeaderAndWrongKeyAre400() {
        UUID id = UUID.randomUUID();
        for (String json : List.of("{}",
                "{\"orderId\":\"bad-uuid\",\"items\":[]}",
                "{\"orderId\":\"" + id + "\",\"storeId\":\"STORE-A\",\"items\":[{\"sku\":\"ITEM\",\"quantity\":1}]}")) {
            assertBadRequest(json, "reserve:" + id + ":v1");
        }
        var request = request("STORE-A", "ITEM", 1);
        for (String key : new String[]{null, "wrong-key"}) {
            assertBadRequest(request, key);
        }
    }

    @Test
    void lookupNotFoundAndInvalidSelectorsHaveCorrectStatus() {
        assertThat(http.getForEntity("/api/v1/inventory/reservations/" + UUID.randomUUID(), ApiError.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/api/v1/inventory/reservations", ApiError.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/v1/inventory/reservations?orderId=" + UUID.randomUUID()
                        + "&reserveKey=key", ApiError.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/v1/inventory/stock?storeId=STORE-A&sku=ITEM", Stock.class)
                .getBody().availableQuantity()).isEqualTo(8);
    }

    @Test
    void upgradesV001DataWithoutResettingStockOrReservations() {
        String schema = "legacy_upgrade";
        var flyway = org.flywaydb.core.Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).target("1").load();
        flyway.migrate();
        var legacy = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword()));
        UUID productId = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID orderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        legacy.update("INSERT INTO inventory(product_id, product_name, on_hand_quantity, reserved_quantity) VALUES (?, 'Legacy', 10, 2)", productId);
        legacy.update("INSERT INTO inventory_order_guards(order_id) VALUES (?)", orderId);
        legacy.update("INSERT INTO reservations(reservation_id, order_id, reserve_key, payload_hash, status) VALUES (?, ?, ?, ?, 'RESERVED')",
                reservationId, orderId, "reserve:" + orderId + ":v1", "a".repeat(64));
        legacy.update("INSERT INTO reservation_items(reservation_id, product_id, quantity) VALUES (?, ?, 2)", reservationId, productId);
        org.flywaydb.core.Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).load().migrate();
        assertThat(legacy.queryForObject("SELECT sku FROM inventory", String.class)).isEqualTo("DEMO-ITEM");
        assertThat(legacy.queryForObject("SELECT reserved_quantity FROM inventory", Integer.class)).isEqualTo(2);
        assertThat(legacy.queryForObject("SELECT store_id FROM reservations", String.class)).isEqualTo("DEMO-STORE");
        assertThat(legacy.queryForObject("SELECT sku FROM reservation_items", String.class)).isEqualTo("DEMO-ITEM");
        assertThat(legacy.queryForObject("SELECT reservation_id FROM reservations", UUID.class)).isEqualTo(reservationId);
    }

    @Test
    void concurrentOrdersDoNotOversell() throws Exception {
        var tasks = new ArrayList<Callable<Reservation>>();
        for (int i = 0; i < 12; i++) {
            var request = request("STORE-A", "ITEM", 2);
            tasks.add(() -> service.reserve(request, key(request)));
        }
        var results = concurrently(tasks);
        assertThat(results.stream().filter(r -> r.status().equals("RESERVED")).count()).isEqualTo(4);
        assertThat(results.stream().filter(r -> r.status().equals("REJECTED")).count()).isEqualTo(8);
        assertThat(service.stock("STORE-A", "ITEM").availableQuantity()).isZero();
        assertThat(service.stock("STORE-A", "ITEM").reservedQuantity()).isEqualTo(8);
    }

    @Test
    void concurrentDuplicatesHaveOneReservationAndOneStockEffect() throws Exception {
        var request = request("STORE-A", "ITEM", 2);
        var tasks = new ArrayList<Callable<Reservation>>();
        for (int i = 0; i < 12; i++) { tasks.add(() -> service.reserve(request, key(request))); }
        assertThat(concurrently(tasks)).extracting(Reservation::reservationId).containsOnly(request.reservationId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservations", Integer.class)).isEqualTo(1);
        assertThat(service.stock("STORE-A", "ITEM").reservedQuantity()).isEqualTo(2);
    }

    private List<Reservation> concurrently(List<Callable<Reservation>> tasks) throws Exception {
        var pool = Executors.newFixedThreadPool(tasks.size());
        var ready = new CountDownLatch(tasks.size());
        var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Reservation>>();
            for (var task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(15, TimeUnit.SECONDS)) { throw new IllegalStateException("Start timed out"); }
                    return task.call();
                }));
            }
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = new ArrayList<Reservation>();
            for (var future : futures) { results.add(future.get(30, TimeUnit.SECONDS)); }
            return results;
        } finally {
            start.countDown();
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private void assertBadRequest(Object body, String key) {
        var response = http.exchange("/api/v1/inventory/reservations", HttpMethod.POST, entity(body, key), ApiError.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("INVALID_REQUEST");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservations", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_order_guards", Integer.class)).isZero();
        assertThat(service.stock("STORE-A", "ITEM").reservedQuantity()).isZero();
    }

    private ResponseEntity<Reservation> post(ReserveRequest request) {
        return http.exchange("/api/v1/inventory/reservations", HttpMethod.POST, entity(request, key(request)), Reservation.class);
    }

    private HttpEntity<?> entity(Object body, String key) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) { headers.set("Idempotency-Key", key); }
        return new HttpEntity<>(body, headers);
    }

    private ReserveRequest request(String store, String sku, int quantity) {
        return new ReserveRequest(UUID.randomUUID(), UUID.randomUUID(), store, List.of(new Item(sku, quantity)));
    }

    private String key(ReserveRequest request) { return "reserve:" + request.orderId() + ":v1"; }

    private void stock(String store, String sku, int quantity) {
        jdbc.update("INSERT INTO inventory(product_id, sku, store_id, product_name, on_hand_quantity) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), sku, store, sku, quantity);
    }
}
