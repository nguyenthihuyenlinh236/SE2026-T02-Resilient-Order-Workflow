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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.orderworkflow.inventory.api.InventoryDtos.*;
import com.example.orderworkflow.inventory.service.InventoryException;
import com.example.orderworkflow.inventory.service.InventoryLifecycleService;
import com.example.orderworkflow.inventory.service.InventoryService;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true", "spring.sql.init.mode=never", "management.health.rabbit.enabled=false"})
class InventoryLifecycleIntegrationTests {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired InventoryService inventory;
    @Autowired InventoryLifecycleService lifecycle;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE inventory_operations, reservation_items, reservations, inventory_order_guards, inventory");
        for (String sku : List.of("A", "B")) {
            jdbc.update("INSERT INTO inventory(product_id, sku, store_id, product_name, on_hand_quantity) VALUES (?, ?, 'STORE', ?, 10)",
                    UUID.randomUUID(), sku, sku);
        }
    }

    @Test
    void releaseReturnsStockExactlyOnceAndReserveReplayPreservesOriginalOutcome() {
        var request = reserveRequest();
        var original = inventory.reserve(request, reserveKey(request));
        var result = release(request);
        assertThat(result.status()).isEqualTo("RELEASED");
        assertThat(result.releasedAt()).isNotNull();
        for (int i = 0; i < 10; i++) { assertThat(release(request)).isEqualTo(result); }
        assertThat(inventory.reservation(request.reservationId()).status()).isEqualTo("RELEASED");
        for (int i = 0; i < 10; i++) {
            assertThat(inventory.reserve(request, reserveKey(request))).isEqualTo(original);
        }
        assertStock(0);
        assertThat(lifecycle.operation(reserveKey(request)).responsePayload().get("status").asText()).isEqualTo("RESERVED");
        assertThat(lifecycle.operation(releaseKey(request)).responsePayload().get("status").asText()).isEqualTo("RELEASED");
        assertThat(count("inventory_operations")).isEqualTo(2);
    }

    @Test
    void releaseHttpResponseAndOperationQueryMatchPersistedResult() {
        var request = reserveRequest();
        inventory.reserve(request, reserveKey(request));
        var body = new ReleaseRequest(request.orderId(), request.reservationId(), "FAILED");
        var response = http.exchange(releasePath(request.reservationId()), HttpMethod.POST,
                entity(body, releaseKey(request)), ReleaseResult.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(release(request));
        var operation = http.getForEntity("/api/v1/inventory/operations?idempotencyKey=" + releaseKey(request), OperationResult.class);
        assertThat(operation.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(operation.getBody().operation()).isEqualTo("RELEASE");
        assertThat(operation.getBody().responseCode()).isEqualTo(200);
        assertThat(operation.getBody().completedAt()).isNotNull();
    }

    @Test
    void changedReleaseReasonIsConflictWithoutSecondStockReturn() {
        var request = reserveRequest();
        inventory.reserve(request, reserveKey(request));
        release(request);
        assertError(() -> lifecycle.release(request.reservationId(),
                new ReleaseRequest(request.orderId(), request.reservationId(), "DIFFERENT"), releaseKey(request)),
                409, "IDEMPOTENCY_KEY_CONFLICT");
        assertStock(0);
    }

    @Test
    void wrongOrderCannotReleaseOrCreateCancellationGuard() {
        var request = reserveRequest();
        inventory.reserve(request, reserveKey(request));
        UUID wrongOrder = UUID.randomUUID();
        assertError(() -> lifecycle.release(request.reservationId(),
                new ReleaseRequest(wrongOrder, request.reservationId(), "FAILED"),
                "inventory:release:" + wrongOrder + ":" + request.reservationId() + ":v1"),
                409, "RESERVATION_ORDER_MISMATCH");
        assertStock(2);
        assertThat(count("inventory_order_guards")).isEqualTo(1);
        assertThat(count("inventory_operations")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT cancellation_recorded FROM inventory_order_guards", Boolean.class)).isFalse();
    }

    @Test
    void missingReservationDoesNotPoisonKeyAndCanBeReleasedAfterReserveArrives() {
        var request = reserveRequest();
        assertError(() -> release(request), 404, "RESERVATION_NOT_FOUND");
        assertThat(count("inventory_order_guards")).isZero();
        assertThat(count("inventory_operations")).isZero();
        inventory.reserve(request, reserveKey(request));
        assertThat(release(request).status()).isEqualTo("RELEASED");
        assertStock(0);
    }

    @Test
    void rejectedReservationCannotBeReleased() {
        var base = reserveRequest();
        var request = new ReserveRequest(base.orderId(), base.reservationId(), "STORE", List.of(new Item("A", 11)));
        assertThat(inventory.reserve(request, reserveKey(request)).status()).isEqualTo("REJECTED");
        assertError(() -> release(request), 409, "RESERVATION_NOT_RESERVED");
        assertThat(inventory.reservation(request.reservationId()).status()).isEqualTo("REJECTED");
        assertThat(count("inventory_operations")).isEqualTo(1);
        assertStock(0);
    }

    @Test
    void cancelBeforeReservePreventsLateAllocationAndReplaysOriginalCancelSnapshot() {
        var request = reserveRequest();
        var cancelled = cancel(request.orderId());
        assertThat(cancelled.status()).isEqualTo("CANCELLATION_RECORDED");
        assertThat(cancelled.reservationStatus()).isEqualTo("NOT_CREATED");
        assertThat(cancelled.reservationId()).isNull();
        var late = inventory.reserve(request, reserveKey(request));
        assertThat(late.status()).isEqualTo("REJECTED");
        assertThat(late.rejectionReason()).isEqualTo("ORDER_CANCELLED");
        assertThat(inventory.reserve(request, reserveKey(request))).isEqualTo(late);
        assertThat(cancel(request.orderId())).isEqualTo(cancelled);
        assertStock(0);
    }

    @Test
    void cancelHttpEndpointValidatesPathAndPersistsOperation() {
        UUID order = UUID.randomUUID();
        var body = new CancelRequest(order, "EXPIRED");
        String path = "/api/v1/inventory/orders/" + order + "/cancel";
        var response = http.exchange(path, HttpMethod.POST, entity(body, cancelKey(order)), CancelResult.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().reservationStatus()).isEqualTo("NOT_CREATED");
        assertThat(response.getBody()).isEqualTo(cancel(order));
        assertThat(lifecycle.operation(cancelKey(order)).operation()).isEqualTo("CANCEL");
        assertThat(http.exchange("/api/v1/inventory/orders/" + UUID.randomUUID() + "/cancel", HttpMethod.POST,
                entity(body, cancelKey(order)), ApiError.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(count("inventory_order_guards")).isEqualTo(1);
    }

    @Test
    void cancelExistingReservationReturnsStockAndSubsequentReleaseDoesNotReturnAgain() {
        var request = reserveRequest();
        var original = inventory.reserve(request, reserveKey(request));
        var cancelled = cancel(request.orderId());
        assertThat(cancelled.reservationStatus()).isEqualTo("RELEASED");
        assertThat(cancelled.reservationId()).isEqualTo(request.reservationId());
        assertThat(release(request).status()).isEqualTo("RELEASED");
        assertThat(inventory.reserve(request, reserveKey(request))).isEqualTo(original);
        assertStock(0);
    }

    @Test
    void cancelAfterReleaseHasItsOwnHashAndRejectsChangedReason() {
        var request = reserveRequest();
        inventory.reserve(request, reserveKey(request));
        release(request);
        var cancelled = cancel(request.orderId());
        assertThat(cancel(request.orderId())).isEqualTo(cancelled);
        assertError(() -> lifecycle.cancel(request.orderId(), new CancelRequest(request.orderId(), "CHANGED"), cancelKey(request.orderId())),
                409, "IDEMPOTENCY_KEY_CONFLICT");
        assertThat(count("inventory_operations")).isEqualTo(3);
        assertStock(0);
    }

    @Test
    void cancelRejectedOrderDoesNotInventReleasedReservation() {
        var base = reserveRequest();
        var request = new ReserveRequest(base.orderId(), base.reservationId(), "STORE", List.of(new Item("MISSING", 1)));
        inventory.reserve(request, reserveKey(request));
        assertThat(cancel(request.orderId()).reservationStatus()).isEqualTo("REJECTED");
        assertStock(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "TOO_LONG"})
    void invalidReasonCannotMutateData(String reason) {
        var request = reserveRequest();
        inventory.reserve(request, reserveKey(request));
        String value = reason.equals("TOO_LONG") ? "x".repeat(256) : reason;
        var response = http.exchange(releasePath(request.reservationId()), HttpMethod.POST,
                entity(new ReleaseRequest(request.orderId(), request.reservationId(), value), releaseKey(request)), ApiError.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertStock(2);
    }

    @Test
    void wrongPathIdsAndKeyAre400AndMissingOperationIs404() {
        var request = reserveRequest();
        inventory.reserve(request, reserveKey(request));
        var body = new ReleaseRequest(request.orderId(), request.reservationId(), "FAILED");
        for (String key : new String[]{null, "release:" + request.orderId() + ":v1"}) {
            assertThat(http.exchange(releasePath(request.reservationId()), HttpMethod.POST, entity(body, key), ApiError.class)
                    .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(http.exchange(releasePath(UUID.randomUUID()), HttpMethod.POST, entity(body, releaseKey(request)), ApiError.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/v1/inventory/operations?idempotencyKey=missing", ApiError.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertStock(2);
    }

    @Test
    void concurrentDuplicateReleasesReturnStockOnlyOnce() throws Exception {
        var request = reserveRequest();
        inventory.reserve(request, reserveKey(request));
        var tasks = new ArrayList<Callable<Object>>();
        for (int i = 0; i < 12; i++) { tasks.add(() -> release(request)); }
        var results = concurrently(tasks);
        assertThat(results).allMatch(r -> r.equals(results.get(0)));
        assertStock(0);
        assertThat(count("inventory_operations")).isEqualTo(2);
    }

    @Test
    void concurrentReserveAndCancelAlwaysLeaveNoActiveReservation() throws Exception {
        var request = reserveRequest();
        concurrently(List.of(() -> inventory.reserve(request, reserveKey(request)), () -> cancel(request.orderId())));
        assertThat(inventory.reservation(request.reservationId()).status()).isIn("RELEASED", "REJECTED");
        assertStock(0);
    }

    @Test
    void inFlightReserveAndReleaseAreSafeRegardlessOfWinner() throws Exception {
        var request = reserveRequest();
        concurrently(List.of(() -> inventory.reserve(request, reserveKey(request)), () -> {
            try { return release(request); }
            catch (InventoryException e) {
                if (e.status() != 404) { throw e; }
                return "RESERVE_NOT_YET_COMMITTED";
            }
        }));
        // If Release arrived before Reserve, NOT_FOUND is retriable and not cached.
        release(request);
        assertThat(inventory.reservation(request.reservationId()).status()).isEqualTo("RELEASED");
        assertStock(0);
    }

    @Test
    void releaseAndCancelRaceDoNotReturnStockTwice() throws Exception {
        var request = reserveRequest();
        inventory.reserve(request, reserveKey(request));
        concurrently(List.of(() -> release(request), () -> cancel(request.orderId())));
        assertStock(0);
        assertThat(count("inventory_operations")).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RELEASE", "CANCEL"})
    void snapshotInsertFailureRollsBackResourceStockGuardAndAllowsRetry(String operation) {
        var request = reserveRequest();
        inventory.reserve(request, reserveKey(request));
        jdbc.execute("ALTER TABLE inventory_operations ADD CONSTRAINT test_snapshot_failure CHECK (operation <> '" + operation + "')");
        try {
            assertThatThrownBy(() -> {
                if (operation.equals("RELEASE")) { release(request); }
                else { cancel(request.orderId()); }
            }).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("test_snapshot_failure");
            assertStock(2);
            assertThat(inventory.reservation(request.reservationId()).status()).isEqualTo("RESERVED");
            assertThat(jdbc.queryForObject("SELECT cancellation_recorded FROM inventory_order_guards", Boolean.class)).isFalse();
            assertThat(jdbc.queryForObject("SELECT released_at FROM reservations", java.sql.Timestamp.class)).isNull();
            assertThat(count("inventory_operations")).isEqualTo(1);
        } finally { jdbc.execute("ALTER TABLE inventory_operations DROP CONSTRAINT test_snapshot_failure"); }
        if (operation.equals("RELEASE")) { release(request); }
        else { cancel(request.orderId()); }
        assertStock(0);
    }

    @Test
    void reserveSnapshotFailureRollsBackAllWrites() {
        var request = reserveRequest();
        jdbc.execute("ALTER TABLE inventory_operations ADD CONSTRAINT test_reserve_snapshot_failure CHECK (operation <> 'RESERVE')");
        try {
            assertThatThrownBy(() -> inventory.reserve(request, reserveKey(request))).isInstanceOf(DataIntegrityViolationException.class);
            assertStock(0);
            assertThat(count("reservations")).isZero();
            assertThat(count("inventory_order_guards")).isZero();
            assertThat(count("inventory_operations")).isZero();
        } finally { jdbc.execute("ALTER TABLE inventory_operations DROP CONSTRAINT test_reserve_snapshot_failure"); }
        assertThat(inventory.reserve(request, reserveKey(request)).status()).isEqualTo("RESERVED");
    }

    @Test
    void freshRepositoryReplaysPersistedResponsesAfterCallerLostReply() {
        var request = reserveRequest();
        var original = inventory.reserve(request, reserveKey(request));
        release(request);
        var fresh = new com.example.orderworkflow.inventory.repository.InventoryOperations(jdbc, mapper);
        String hash = jdbc.queryForObject("SELECT payload_hash FROM inventory_operations WHERE idempotency_key = ?",
                String.class, reserveKey(request));
        assertThat(fresh.replay(reserveKey(request), "RESERVE", hash, Reservation.class)).contains(original);
        assertThat(fresh.find(releaseKey(request))).isPresent();
        assertThat(inventory.reservation(request.reservationId()).status()).isEqualTo("RELEASED");
        assertStock(0);
    }

    @Test
    void v003BackfillsV002ReserveSnapshotWithoutChangingExistingHashOrStock() {
        String schema = "upgrade_v2";
        org.flywaydb.core.Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).target("2").load().migrate();
        var legacy = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword()));
        var request = reserveRequest();
        var original = inventory.reserve(request, reserveKey(request));
        String hash = jdbc.queryForObject("SELECT payload_hash FROM reservations WHERE order_id = ?", String.class, request.orderId());
        legacy.update("INSERT INTO inventory_order_guards(order_id) VALUES (?)", request.orderId());
        legacy.update("INSERT INTO reservations(reservation_id, order_id, reserve_key, payload_hash, status, store_id, created_at) "
                        + "VALUES (?, ?, ?, ?, 'RESERVED', 'STORE', ?)", request.reservationId(), request.orderId(),
                reserveKey(request), hash, java.sql.Timestamp.from(original.createdAt()));
        for (Item item : request.items()) {
            UUID product = UUID.randomUUID();
            legacy.update("INSERT INTO inventory(product_id, sku, store_id, product_name, on_hand_quantity, reserved_quantity) "
                    + "VALUES (?, ?, 'STORE', ?, 10, ?)", product, item.sku(), item.sku(), item.quantity());
            legacy.update("INSERT INTO reservation_items(reservation_id, sku, product_id, quantity) VALUES (?, ?, ?, ?)",
                    request.reservationId(), item.sku(), product, item.quantity());
        }
        org.flywaydb.core.Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).load().migrate();
        var migrated = new com.example.orderworkflow.inventory.repository.InventoryOperations(legacy, mapper);
        assertThat(migrated.replay(reserveKey(request), "RESERVE", hash, Reservation.class)).contains(original);
        assertThat(legacy.queryForObject("SELECT SUM(reserved_quantity) FROM inventory", Integer.class)).isEqualTo(5);
        assertThat(legacy.queryForObject("SELECT payload_hash FROM reservations", String.class)).isEqualTo(hash);
    }

    private ReserveRequest reserveRequest() {
        return new ReserveRequest(UUID.randomUUID(), UUID.randomUUID(), "STORE", List.of(new Item("A", 2), new Item("B", 3)));
    }
    private String reserveKey(ReserveRequest r) { return "reserve:" + r.orderId() + ":v1"; }
    private String releaseKey(ReserveRequest r) { return "inventory:release:" + r.orderId() + ":" + r.reservationId() + ":v1"; }
    private String cancelKey(UUID order) { return "inventory:cancel:" + order + ":v1"; }
    private String releasePath(UUID reservationId) { return "/api/v1/inventory/reservations/" + reservationId + "/release"; }
    private ReleaseResult release(ReserveRequest r) {
        return lifecycle.release(r.reservationId(), new ReleaseRequest(r.orderId(), r.reservationId(), "FAILED"), releaseKey(r));
    }
    private CancelResult cancel(UUID order) {
        return lifecycle.cancel(order, new CancelRequest(order, "EXPIRED"), cancelKey(order));
    }
    private void assertStock(int reservedA) {
        assertThat(inventory.stock("STORE", "A").reservedQuantity()).isEqualTo(reservedA);
        assertThat(inventory.stock("STORE", "B").reservedQuantity()).isEqualTo(reservedA == 0 ? 0 : 3);
        assertThat(inventory.stock("STORE", "A").onHandQuantity()).isEqualTo(10);
        assertThat(inventory.stock("STORE", "B").onHandQuantity()).isEqualTo(10);
    }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private HttpEntity<?> entity(Object body, String key) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) { headers.set("Idempotency-Key", key); }
        return new HttpEntity<>(body, headers);
    }
    private void assertError(Runnable action, int status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(InventoryException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.code()).isEqualTo(code);
        });
    }
    private List<Object> concurrently(List<Callable<Object>> tasks) throws Exception {
        var pool = Executors.newFixedThreadPool(tasks.size());
        var ready = new CountDownLatch(tasks.size());
        var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Object>>();
            for (var task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(15, TimeUnit.SECONDS)) { throw new IllegalStateException("Start timed out"); }
                    return task.call();
                }));
            }
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = new ArrayList<Object>();
            for (var future : futures) { results.add(future.get(30, TimeUnit.SECONDS)); }
            return results;
        } finally {
            start.countDown(); pool.shutdownNow(); pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }
}
