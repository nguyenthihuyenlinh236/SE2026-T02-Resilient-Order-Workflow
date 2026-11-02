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
        // Model an internal caller that consistently forwards its Saga correlation ID.
        var client = http.getRestTemplate();
        if (client.getInterceptors().stream().noneMatch(interceptor -> interceptor instanceof DefaultTraceHeader)) {
            client.getInterceptors().add(new DefaultTraceHeader());
        }
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

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/inventory"})
    void reservationRoutesSupportReserveLookupReleaseAndReplay(String prefix) {
        var request = reserveRequest();
        var reserved = http.exchange(prefix + "/reservations", HttpMethod.POST,
                entity(request, reserveKey(request)), Reservation.class);
        assertThat(reserved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reserved.getBody().status()).isEqualTo("RESERVED");
        var found = http.getForEntity(prefix + "/reservations/by-order/" + request.orderId(), Reservation.class);
        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(found.getBody()).isEqualTo(reserved.getBody());
        var body = new ReleaseRequest(request.orderId(), request.reservationId(), "FAILED");
        var released = http.exchange(prefix + "/reservations/" + request.reservationId() + "/release",
                HttpMethod.POST, entity(body, releaseKey(request)), ReleaseResult.class);
        assertThat(released.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(released.getBody().status()).isEqualTo("RELEASED");
        var replay = http.exchange(prefix + "/reservations", HttpMethod.POST,
                entity(request, reserveKey(request)), Reservation.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(reserved.getBody());
        var current = http.getForEntity(prefix + "/reservations/by-order/" + request.orderId(), Reservation.class);
        assertThat(current.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(current.getBody().status()).isEqualTo("RELEASED");
        assertStock(0);
        assertThat(count("inventory_operations")).isEqualTo(2);
    }

    @Test
    void internalLookupReturns404ForUnknownOrder() {
        var response = http.getForEntity("/internal/v1/reservations/by-order/" + UUID.randomUUID().toString(), ContractErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().error().code()).isEqualTo("RESERVATION_NOT_FOUND");
        assertThat(response.getBody().error().retryable()).isFalse();
        assertThat(response.getBody().error().details()).isEmpty();
        assertThat(response.getBody().error().correlationId()).isEqualTo("corr-001");
        assertThat(response.getHeaders().getFirst("X-Correlation-Id"))
                .isEqualTo(response.getBody().error().correlationId());
    }

    @Test
    void internalValidationErrorHasFieldDetailsAndPreservesCorrelationId() {
        var request = reserveRequest();
        var invalid = new ReserveRequest(request.orderId(), request.reservationId(), "", request.items());
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", reserveKey(request));
        headers.set("X-Correlation-Id", "corr-validation-001");
        var response = http.exchange("/internal/v1/reservations", HttpMethod.POST,
                new HttpEntity<>(invalid, headers), ContractErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var error = response.getBody().error();
        assertThat(error.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.correlationId()).isEqualTo("corr-validation-001");
        assertThat(error.retryable()).isFalse();
        assertThat(error.details()).extracting(ErrorDetail::field).contains("storeId");
        assertThat(count("inventory_operations")).isZero();
    }

    @Test
    void internalInvalidIdentifierAndMissingHeaderUseContractErrors() {
        var malformed = http.getForEntity("/internal/v1/reservations/by-order/invalid!id", ContractErrorResponse.class);
        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(malformed.getBody().error().code()).isEqualTo("INVALID_IDENTIFIER");
        var missingHeader = http.postForEntity("/internal/v1/reservations", reserveRequest(), ContractErrorResponse.class);
        assertThat(missingHeader.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missingHeader.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void internalIdempotencyConflictUsesContractErrorAndPreservesStock() {
        var request = reserveRequest();
        inventory.reserve(request, reserveKey(request));
        var changed = new ReserveRequest(request.orderId(), request.reservationId(), request.storeId(), List.of(new Item("A", 9)));
        var response = http.exchange("/internal/v1/reservations", HttpMethod.POST,
                entity(changed, reserveKey(request)), ContractErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().error().code()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(response.getBody().error().retryable()).isFalse();
        assertStock(2);
        assertThat(count("inventory_operations")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 255, 256, 278, 512})
    void internalOperationLookupSupportsContractKeyLengths(int length) {
        String order = UUID.randomUUID().toString();
        String key = "k".repeat(length);
        jdbc.update("INSERT INTO inventory_order_guards(order_id) VALUES (?)", order);
        jdbc.update("INSERT INTO inventory_operations(idempotency_key, order_id, operation, payload_hash, response_code, response_payload) "
                + "VALUES (?, ?, 'RESERVE', ?, 200, '{\"status\":\"REJECTED\"}'::jsonb)", key, order, "a".repeat(64));
        var response = http.getForEntity("/internal/v1/operations?idempotencyKey=" + key, OperationResult.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().idempotencyKey()).isEqualTo(key);
        assertThat(response.getBody().responsePayload().get("status").asText()).isEqualTo("REJECTED");
        assertStock(0);
    }

    @Test
    void internalOperationLookupRejectsOversizedKeyAndDistinguishesMissingOperation() {
        var invalid = http.getForEntity("/internal/v1/operations?idempotencyKey=" + "k".repeat(513), ContractErrorResponse.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
        var missing = http.getForEntity("/internal/v1/operations?idempotencyKey=" + "k".repeat(512), ContractErrorResponse.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody().error().code()).isEqualTo("OPERATION_NOT_FOUND");
        assertThat(count("inventory_operations")).isZero();
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
        String wrongOrder = UUID.randomUUID().toString();
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
        String order = UUID.randomUUID().toString();
        var body = new CancelRequest(order, "EXPIRED");
        String path = "/api/v1/inventory/orders/" + order + "/cancel";
        var response = http.exchange(path, HttpMethod.POST, entity(body, cancelKey(order)), CancelResult.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().reservationStatus()).isEqualTo("NOT_CREATED");
        assertThat(response.getBody()).isEqualTo(cancel(order));
        assertThat(lifecycle.operation(cancelKey(order)).operation()).isEqualTo("CANCEL");
        assertThat(http.exchange("/api/v1/inventory/orders/" + UUID.randomUUID().toString() + "/cancel", HttpMethod.POST,
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
        assertThat(http.exchange(releasePath(UUID.randomUUID().toString()), HttpMethod.POST, entity(body, releaseKey(request)), ApiError.class)
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
        legacy.update("INSERT INTO inventory_order_guards(order_id) VALUES (?)", UUID.fromString(request.orderId()));
        legacy.update("INSERT INTO reservations(reservation_id, order_id, reserve_key, payload_hash, status, store_id, created_at) "
                        + "VALUES (?, ?, ?, ?, 'RESERVED', 'STORE', ?)", UUID.fromString(request.reservationId()), UUID.fromString(request.orderId()),
                reserveKey(request), hash, java.sql.Timestamp.from(original.createdAt()));
        for (Item item : request.items()) {
            UUID product = UUID.randomUUID();
            legacy.update("INSERT INTO inventory(product_id, sku, store_id, product_name, on_hand_quantity, reserved_quantity) "
                    + "VALUES (?, ?, 'STORE', ?, 10, ?)", product, item.sku(), item.sku(), item.quantity());
            legacy.update("INSERT INTO reservation_items(reservation_id, sku, product_id, quantity) VALUES (?, ?, ?, ?)",
                    UUID.fromString(request.reservationId()), item.sku(), product, item.quantity());
        }
        org.flywaydb.core.Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).load().migrate();
        var migrated = new com.example.orderworkflow.inventory.repository.InventoryOperations(legacy, mapper);
        assertThat(migrated.replay(reserveKey(request), "RESERVE", hash, Reservation.class)).contains(original);
        assertThat(legacy.queryForObject("SELECT SUM(reserved_quantity) FROM inventory", Integer.class)).isEqualTo(5);
        assertThat(legacy.queryForObject("SELECT payload_hash FROM reservations", String.class)).isEqualTo(hash);
    }

    @Test
    void v004PreservesExistingResultsAndExpandsBothKeyColumnsTo512Characters() {
        String schema = "upgrade_v3_keys";
        var flyway = org.flywaydb.core.Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema);
        flyway.target("3").load().migrate();
        var upgraded = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword()));
        UUID orderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        String hash = "a".repeat(64);
        upgraded.update("INSERT INTO inventory_order_guards(order_id) VALUES (?)", orderId);
        upgraded.update("INSERT INTO reservations(reservation_id, order_id, reserve_key, payload_hash, status, store_id, rejection_reason) "
                + "VALUES (?, ?, 'legacy-key', ?, 'REJECTED', 'STORE', 'INSUFFICIENT_STOCK')", reservationId, orderId, hash);
        upgraded.update("INSERT INTO inventory_operations(idempotency_key, order_id, operation, payload_hash, response_code, response_payload) "
                + "VALUES ('legacy-key', ?, 'RESERVE', ?, 200, '{\"status\":\"REJECTED\"}'::jsonb)", orderId, hash);

        flyway.target("4").load().migrate();

        assertThat(upgraded.queryForObject("SELECT reserve_key FROM reservations", String.class)).isEqualTo("legacy-key");
        assertThat(upgraded.queryForObject("SELECT payload_hash FROM reservations", String.class)).isEqualTo(hash);
        assertThat(upgraded.queryForObject("SELECT response_payload->>'status' FROM inventory_operations", String.class))
                .isEqualTo("REJECTED");
        String longestKey = "k".repeat(512);
        assertThat(upgraded.update("UPDATE reservations SET reserve_key = ?", longestKey)).isEqualTo(1);
        assertThat(upgraded.update("UPDATE inventory_operations SET idempotency_key = ?", longestKey)).isEqualTo(1);
        assertThat(upgraded.queryForObject("SELECT reserve_key FROM reservations", String.class)).isEqualTo(longestKey);
        assertThat(upgraded.queryForObject("SELECT idempotency_key FROM inventory_operations", String.class)).isEqualTo(longestKey);
        assertThatThrownBy(() -> upgraded.update("UPDATE reservations SET reserve_key = ?", longestKey + "x"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> upgraded.update("UPDATE inventory_operations SET idempotency_key = ?", longestKey + "x"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {7, 128})
    void stringIdentifiersSupportReserveReleaseCancelAndReplay(int length) {
        String order = "ORD-001" + "x".repeat(length - 7);
        String reservation = "RES-001" + "x".repeat(length - 7);
        var request = new ReserveRequest(order, reservation, "STORE", List.of(new Item("A", 2), new Item("B", 3)));
        var reserved = http.exchange("/api/v1/inventory/reservations", HttpMethod.POST,
                entity(request, reserveKey(request)), Reservation.class);
        assertThat(reserved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reserved.getBody().orderId()).isEqualTo(order);
        assertThat(reserved.getBody().reservationId()).isEqualTo(reservation);
        var found = http.getForEntity("/api/v1/inventory/reservations/by-order/" + order, Reservation.class);
        assertThat(found.getBody()).isEqualTo(reserved.getBody());
        var released = http.exchange("/api/v1/inventory/reservations/" + reservation + "/release", HttpMethod.POST,
                entity(new ReleaseRequest(order, reservation, "FAILED"), releaseKey(request)), ReleaseResult.class);
        assertThat(released.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(released.getBody().reservationId()).isEqualTo(reservation);
        assertThat(inventory.reserve(request, reserveKey(request))).isEqualTo(reserved.getBody());
        assertThat(release(request)).isEqualTo(released.getBody());
        assertThat(cancel(order).reservationId()).isEqualTo(reservation);
        assertStock(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad!id", "_leading", "-leading", "has space", "khóa"})
    void invalidStringIdentifiersReturn400WithoutWrites(String id) {
        var request = new ReserveRequest(id, "RES-001", "STORE", List.of(new Item("A", 2)));
        var response = http.exchange("/internal/v1/reservations", HttpMethod.POST,
                entity(request, "reserve:" + id + ":v1"), ContractErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var invalidReservation = new ReserveRequest("ORD-001", id, "STORE", request.items());
        assertThat(http.exchange("/internal/v1/reservations", HttpMethod.POST,
                entity(invalidReservation, "reserve:ORD-001:v1"), ContractErrorResponse.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(count("inventory_order_guards")).isZero();
        assertStock(0);
    }

    @Test
    void identifiersLongerThan128AreRejectedInBodyAndPath() {
        String tooLong = "x".repeat(129);
        var request = new ReserveRequest(tooLong, "RES-001", "STORE", List.of(new Item("A", 2)));
        assertThat(http.exchange("/internal/v1/reservations", HttpMethod.POST,
                entity(request, "reserve:" + tooLong + ":v1"), ContractErrorResponse.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        var path = http.getForEntity("/internal/v1/reservations/by-order/" + tooLong, ContractErrorResponse.class);
        assertThat(path.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(path.getBody().error().code()).isEqualTo("INVALID_IDENTIFIER");
        assertThat(count("inventory_order_guards")).isZero();
    }

    @Test
    void v005PreservesUuidDataSnapshotsAndForeignKeys() throws Exception {
        String schema = "upgrade_v4_identifiers";
        var config = org.flywaydb.core.Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema);
        config.target("4").load().migrate();
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
        var legacy = new JdbcTemplate(source);
        UUID order = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        var request = new ReserveRequest(order.toString(), reservation.toString(), "STORE", List.of(new Item("A", 2)));
        String key = reserveKey(request);
        // Reproduce #28's UUID-based JSON hash, not a hash generated by the new code.
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsBytes(List.of("RESERVE", order.toString(), reservation.toString(), "STORE", request.items()))));
        legacy.update("INSERT INTO inventory_order_guards(order_id) VALUES (?)", order);
        legacy.update("INSERT INTO inventory(product_id, sku, store_id, product_name, on_hand_quantity, reserved_quantity) "
                + "VALUES (?, 'A', 'STORE', 'Legacy', 10, 2)", product);
        legacy.update("INSERT INTO reservations(reservation_id, order_id, reserve_key, payload_hash, status, store_id) "
                + "VALUES (?, ?, ?, ?, 'RESERVED', 'STORE')", reservation, order, key, hash);
        legacy.update("INSERT INTO reservation_items(reservation_id, sku, product_id, quantity) VALUES (?, 'A', ?, 2)", reservation, product);
        var original = new Reservation(reservation.toString(), order.toString(), "STORE", key, "RESERVED", null,
                request.items(), legacy.queryForObject("SELECT created_at FROM reservations", java.sql.Timestamp.class).toInstant());
        String snapshot = mapper.writeValueAsString(original);
        legacy.update("INSERT INTO inventory_operations(idempotency_key, order_id, operation, payload_hash, response_code, response_payload) "
                + "VALUES (?, ?, 'RESERVE', ?, 200, ?::jsonb)", key, order, hash, snapshot);
        String storedSnapshot = legacy.queryForObject("SELECT response_payload::text FROM inventory_operations", String.class);

        config.target("5").load().migrate();

        var repository = new com.example.orderworkflow.inventory.repository.InventoryRepository(legacy);
        var operations = new com.example.orderworkflow.inventory.repository.InventoryOperations(legacy, mapper);
        var service = new InventoryService(repository, operations);
        var lifecycleService = new InventoryLifecycleService(repository, operations);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
        assertThat(repository.byId(reservation.toString())).contains(original);
        var replayBeforeRelease = transaction.execute(status -> service.reserve(request, key));
        assertThat(replayBeforeRelease).isEqualTo(original);
        assertThat(legacy.queryForObject("SELECT response_payload::text FROM inventory_operations", String.class)).isEqualTo(storedSnapshot);
        assertThat(legacy.queryForObject("SELECT payload_hash FROM reservations", String.class)).isEqualTo(hash);
        var result = transaction.execute(status -> lifecycleService.release(reservation.toString(),
                new ReleaseRequest(order.toString(), reservation.toString(), "FAILED"), releaseKey(request)));
        assertThat(result.status()).isEqualTo("RELEASED");
        assertThat(legacy.queryForObject("SELECT reserved_quantity FROM inventory", Integer.class)).isZero();
        var replayAfterRelease = transaction.execute(status -> service.reserve(request, key));
        assertThat(replayAfterRelease).isEqualTo(original);
        assertThatThrownBy(() -> legacy.update("DELETE FROM inventory_order_guards WHERE order_id = ?", order.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> legacy.update("DELETE FROM reservations WHERE reservation_id = ?", reservation.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> legacy.update("INSERT INTO inventory_operations(idempotency_key, order_id, operation, payload_hash, response_code, response_payload) "
                + "VALUES ('orphan', 'MISSING', 'RESERVE', ?, 200, '{}'::jsonb)", hash))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> legacy.update("INSERT INTO inventory_order_guards(order_id) VALUES (?)", "x".repeat(129)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {21, 100})
    void reserveAcceptsUpTo100ItemsWithoutPartialAllocation(int size) {
        var items = java.util.stream.IntStream.range(0, size)
                .mapToObj(i -> new Item("MISSING-" + i, 1)).toList();
        var request = new ReserveRequest("ORD-100", "RES-100", "STORE", items);
        var response = http.exchange("/api/v1/inventory/reservations", HttpMethod.POST,
                entity(request, reserveKey(request)), Reservation.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("REJECTED");
        assertThat(response.getBody().rejectionReason()).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(response.getBody().items()).hasSize(size);
        assertThat(inventory.reserve(request, reserveKey(request))).isEqualTo(response.getBody());
        assertStock(0);
    }

    @Test
    void reserveRejects101ItemsBeforeWriting() {
        var items = java.util.stream.IntStream.range(0, 101).mapToObj(i -> new Item("ITEM-" + i, 1)).toList();
        var request = new ReserveRequest("ORD-101", "RES-101", "STORE", items);
        var response = http.exchange("/internal/v1/reservations", HttpMethod.POST,
                entity(request, reserveKey(request)), ContractErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(count("inventory_order_guards")).isZero();
        assertThat(count("inventory_operations")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"2\"", "\"2.0\"", "2.0", "2.5", "true", "null", "2147483648"})
    void reserveRejectsNonIntegerQuantityWithoutWrites(String quantity) {
        String body = "{\"orderId\":\"ORD-QTY\",\"reservationId\":\"RES-QTY\",\"storeId\":\"STORE\","
                + "\"items\":[{\"sku\":\"A\",\"quantity\":" + quantity + "}]}";
        var response = http.exchange("/internal/v1/reservations", HttpMethod.POST,
                entity(body, "reserve:ORD-QTY:v1"), ContractErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(count("inventory_order_guards")).isZero();
        assertThat(count("inventory_operations")).isZero();
        assertStock(0);
    }

    @Test
    void internalReserveAndLookupUseContractFieldsAndPreserveReplayAfterRelease() {
        var request = reserveRequest();
        var reserved = http.exchange("/internal/v1/reservations", HttpMethod.POST,
                entity(request, reserveKey(request)), com.fasterxml.jackson.databind.JsonNode.class);
        assertThat(reserved.getStatusCode()).isEqualTo(HttpStatus.OK);
        var original = reserved.getBody();
        assertThat(original.properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("reservationId", "orderId", "status", "reservedAt", "reasonCode");
        assertThat(original.get("reservationId").asText()).isEqualTo(request.reservationId());
        assertThat(original.get("orderId").asText()).isEqualTo(request.orderId());
        assertThat(original.get("status").asText()).isEqualTo("RESERVED");
        assertThat(java.time.Instant.parse(original.get("reservedAt").asText())).isNotNull();
        assertThat(original.get("reasonCode").isNull()).isTrue();
        var query = http.getForEntity("/internal/v1/reservations/by-order/" + request.orderId(),
                com.fasterxml.jackson.databind.JsonNode.class);
        assertThat(query.getBody().properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("reservationId", "orderId", "status", "idempotencyKey", "reasonCode");
        assertThat(query.getBody().get("idempotencyKey").asText()).isEqualTo(reserveKey(request));
        assertThat(query.getBody().get("status").asText()).isEqualTo("RESERVED");
        release(request);
        var replay = http.exchange("/internal/v1/reservations", HttpMethod.POST,
                entity(request, reserveKey(request)), com.fasterxml.jackson.databind.JsonNode.class);
        assertThat(replay.getBody()).isEqualTo(original);
        var current = http.getForEntity("/internal/v1/reservations/by-order/" + request.orderId(),
                com.fasterxml.jackson.databind.JsonNode.class);
        assertThat(current.getBody().get("status").asText()).isEqualTo("RELEASED");
        assertThat(current.getBody().get("reasonCode").isNull()).isTrue();
        assertStock(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"INVENTORY_NOT_AVAILABLE", "PRODUCT_NOT_FOUND", "ORDER_ALREADY_RELEASED"})
    void internalRejectedReserveReturnsNullResourceFieldsAndContractReason(String reason) {
        var base = reserveRequest();
        if (reason.equals("ORDER_ALREADY_RELEASED")) { cancel(base.orderId()); }
        var request = new ReserveRequest(base.orderId(), base.reservationId(), "STORE",
                List.of(new Item(reason.equals("PRODUCT_NOT_FOUND") ? "MISSING" : "A",
                        reason.equals("INVENTORY_NOT_AVAILABLE") ? 11 : 1)));
        var response = http.exchange("/internal/v1/reservations", HttpMethod.POST,
                entity(request, reserveKey(request)), com.fasterxml.jackson.databind.JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status").asText()).isEqualTo("REJECTED");
        assertThat(response.getBody().get("reservationId").isNull()).isTrue();
        assertThat(response.getBody().get("reservedAt").isNull()).isTrue();
        assertThat(response.getBody().get("reasonCode").asText()).isEqualTo(reason);
        var query = http.getForEntity("/internal/v1/reservations/by-order/" + request.orderId(),
                com.fasterxml.jackson.databind.JsonNode.class);
        assertThat(query.getBody().get("reservationId").isNull()).isTrue();
        assertThat(query.getBody().get("reasonCode").asText()).isEqualTo(reason);
        assertThat(query.getBody().get("idempotencyKey").asText()).isEqualTo(reserveKey(request));
        var replay = http.exchange("/internal/v1/reservations", HttpMethod.POST,
                entity(request, reserveKey(request)), com.fasterxml.jackson.databind.JsonNode.class);
        assertThat(replay.getBody()).isEqualTo(response.getBody());
        assertStock(0);
    }

    @Test
    void internalReleaseUsesContractBodyAndReplaysOriginalTimestamp() {
        var request = reserveRequest();
        inventory.reserve(request, reserveKey(request));
        var body = new InternalReleaseRequest("cmd-release-001", request.orderId(), "corr-001");
        var response = http.exchange("/internal/v1/reservations/" + request.reservationId() + "/release",
                HttpMethod.POST, entity(body, releaseKey(request)), com.fasterxml.jackson.databind.JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var original = response.getBody();
        assertThat(original.properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("reservationId", "orderId", "status", "releasedAt");
        assertThat(original.get("reservationId").asText()).isEqualTo(request.reservationId());
        assertThat(original.get("orderId").asText()).isEqualTo(request.orderId());
        assertThat(original.get("status").asText()).isEqualTo("RELEASED");
        assertThat(java.time.Instant.parse(original.get("releasedAt").asText())).isNotNull();
        for (int i = 0; i < 3; i++) {
            var replay = http.exchange("/internal/v1/reservations/" + request.reservationId() + "/release",
                    HttpMethod.POST, entity(body, releaseKey(request)), com.fasterxml.jackson.databind.JsonNode.class);
            assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(replay.getBody()).isEqualTo(original);
        }
        assertStock(0);
        assertThat(count("inventory_operations")).isEqualTo(2);
    }

    @Test
    void changedInternalReleasePayloadConflictsWithoutReturningStockTwice() {
        var request = reserveRequest();
        inventory.reserve(request, reserveKey(request));
        lifecycle.releaseInternal(request.reservationId(),
                new InternalReleaseRequest("cmd-001", request.orderId(), "corr-001"), releaseKey(request));
        for (var changed : List.of(new InternalReleaseRequest("cmd-002", request.orderId(), "corr-001"),
                new InternalReleaseRequest("cmd-001", request.orderId(), "corr-002"))) {
            var response = http.exchange("/internal/v1/reservations/" + request.reservationId() + "/release",
                    HttpMethod.POST, entity(changed, releaseKey(request)), ContractErrorResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().error().code()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        }
        assertStock(0);
        assertThat(count("inventory_operations")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"commandId\":\"cmd-001\",\"orderId\":\"ORD-001\"}",
            "{\"commandId\":\"cmd-001\",\"orderId\":\"ORD-001\",\"correlationId\":\"corr-001\",\"reason\":\"FAILED\"}"})
    void malformedInternalReleaseCannotCreateGuardOrOperation(String body) {
        var response = http.exchange("/internal/v1/reservations/RES-001/release", HttpMethod.POST,
                entity(body, "inventory:release:ORD-001:RES-001:v1"), ContractErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(count("inventory_order_guards")).isZero();
        assertThat(count("inventory_operations")).isZero();
    }

    @Test
    void internalReleaseChecksOwnerMissingAndRejectedReservations() {
        var request = reserveRequest();
        var body = new InternalReleaseRequest("cmd-001", request.orderId(), "corr-001");
        assertError(() -> lifecycle.releaseInternal(request.reservationId(), body, releaseKey(request)), 404, "RESERVATION_NOT_FOUND");
        assertThat(count("inventory_order_guards")).isZero();
        inventory.reserve(request, reserveKey(request));
        assertError(() -> lifecycle.releaseInternal(request.reservationId(),
                new InternalReleaseRequest("cmd-001", "WRONG-ORDER", "corr-001"),
                "inventory:release:WRONG-ORDER:" + request.reservationId() + ":v1"), 409, "RESERVATION_ORDER_MISMATCH");
        var rejected = new ReserveRequest("ORD-REJECTED", "RES-REJECTED", "STORE", List.of(new Item("A", 100)));
        inventory.reserve(rejected, reserveKey(rejected));
        assertError(() -> lifecycle.releaseInternal(rejected.reservationId(),
                new InternalReleaseRequest("cmd-002", rejected.orderId(), "corr-002"), releaseKey(rejected)), 409, "RESERVATION_NOT_RESERVED");
        assertStock(2);
        assertThat(count("inventory_operations")).isEqualTo(2);
        assertThat(count("inventory_order_guards")).isEqualTo(2);
    }

    @Test
    void internalReleaseAfterCancelKeepsFirstReleaseTimestamp() {
        var request = reserveRequest();
        inventory.reserve(request, reserveKey(request));
        cancel(request.orderId());
        var firstReleasedAt = jdbc.queryForObject("SELECT released_at FROM reservations", java.sql.Timestamp.class).toInstant();
        var result = lifecycle.releaseInternal(request.reservationId(),
                new InternalReleaseRequest("cmd-001", request.orderId(), "corr-001"), releaseKey(request));
        assertThat(result.releasedAt()).isEqualTo(firstReleasedAt);
        assertStock(0);
    }

    @Test
    void concurrentInternalReleaseReturnsStockOnlyOnce() throws Exception {
        var request = reserveRequest();
        inventory.reserve(request, reserveKey(request));
        var body = new InternalReleaseRequest("cmd-001", request.orderId(), "corr-001");
        var tasks = new ArrayList<Callable<Object>>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> lifecycle.releaseInternal(request.reservationId(), body, releaseKey(request)));
        }
        var results = concurrently(tasks);
        assertThat(results).allMatch(results.get(0)::equals);
        assertStock(0);
        assertThat(count("inventory_operations")).isEqualTo(2);
    }

    @Test
    void internalReleaseSnapshotFailureRollsBackAndAllowsRetry() {
        var request = reserveRequest();
        inventory.reserve(request, reserveKey(request));
        var body = new InternalReleaseRequest("cmd-001", request.orderId(), "corr-001");
        jdbc.execute("ALTER TABLE inventory_operations ADD CONSTRAINT test_internal_release_failure CHECK (operation <> 'RELEASE')");
        try {
            assertThatThrownBy(() -> lifecycle.releaseInternal(request.reservationId(), body, releaseKey(request)))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertStock(2);
            assertThat(inventory.reservation(request.reservationId()).status()).isEqualTo("RESERVED");
            assertThat(jdbc.queryForObject("SELECT cancellation_recorded FROM inventory_order_guards", Boolean.class)).isFalse();
            assertThat(jdbc.queryForObject("SELECT released_at FROM reservations", java.sql.Timestamp.class)).isNull();
            assertThat(count("inventory_operations")).isEqualTo(1);
        } finally {
            jdbc.execute("ALTER TABLE inventory_operations DROP CONSTRAINT test_internal_release_failure");
        }
        assertThat(lifecycle.releaseInternal(request.reservationId(), body, releaseKey(request)).status()).isEqualTo("RELEASED");
        assertStock(0);
    }

    @Test
    void internalReleaseDoesNotRewriteLegacyReleaseSnapshot() {
        var request = reserveRequest();
        inventory.reserve(request, reserveKey(request));
        var original = release(request);
        assertError(() -> lifecycle.releaseInternal(request.reservationId(),
                new InternalReleaseRequest("cmd-001", request.orderId(), "corr-001"), releaseKey(request)),
                409, "IDEMPOTENCY_KEY_CONFLICT");
        assertThat(release(request)).isEqualTo(original);
        assertStock(0);
    }

    @Test
    void missingInternalCorrelationIsRejectedBeforeWritesButLegacyRouteStillWorks() {
        var caller = new TestRestTemplate();
        var request = reserveRequest();
        var response = caller.exchange(http.getRootUri() + "/internal/v1/reservations", HttpMethod.POST,
                entity(request, reserveKey(request)), ContractErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(UUID.fromString(response.getBody().error().correlationId())).isNotNull();
        assertThat(response.getHeaders().getFirst("X-Correlation-Id"))
                .isEqualTo(response.getBody().error().correlationId());
        assertThat(count("inventory_order_guards")).isZero();
        assertThat(count("inventory_operations")).isZero();
        var legacy = caller.exchange(http.getRootUri() + "/api/v1/inventory/reservations", HttpMethod.POST,
                entity(request, reserveKey(request)), Reservation.class);
        assertThat(legacy.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void invalidTracingHeadersCannotCreateSideEffects() {
        var request = reserveRequest();
        for (String field : List.of("X-Correlation-Id", "X-Command-Id")) {
            var headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Idempotency-Key", reserveKey(request));
            headers.set("X-Correlation-Id", "corr-001");
            headers.set(field, "x".repeat(129));
            var response = http.exchange("/internal/v1/reservations", HttpMethod.POST,
                    new HttpEntity<>(request, headers), ContractErrorResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(count("inventory_order_guards")).isZero();
        assertThat(count("inventory_operations")).isZero();
        assertStock(0);
    }

    @Test
    void internalQueriesRequireAndEchoCorrelationIncludingOnErrors() {
        var caller = new TestRestTemplate();
        var missing = caller.getForEntity(http.getRootUri() + "/internal/v1/reservations/by-order/ORD-UNKNOWN",
                ContractErrorResponse.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String trace = "trace:" + "x".repeat(122);
        var headers = new HttpHeaders();
        headers.set("X-Correlation-Id", trace);
        var notFound = caller.exchange(http.getRootUri() + "/internal/v1/reservations/by-order/ORD-UNKNOWN",
                HttpMethod.GET, new HttpEntity<>(headers), ContractErrorResponse.class);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notFound.getBody().error().correlationId()).isEqualTo(trace);
        assertThat(notFound.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(trace);
    }

    @Test
    void transportCorrelationDoesNotChangeReserveHashOrPersistedSnapshot() {
        var request = reserveRequest();
        var original = http.exchange("/internal/v1/reservations", HttpMethod.POST,
                entity(request, reserveKey(request)), com.fasterxml.jackson.databind.JsonNode.class);
        String hash = jdbc.queryForObject("SELECT payload_hash FROM inventory_operations", String.class);
        String snapshot = jdbc.queryForObject("SELECT response_payload::text FROM inventory_operations", String.class);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", reserveKey(request));
        headers.set("X-Correlation-Id", "corr-query-002");
        var replay = http.exchange("/internal/v1/reservations", HttpMethod.POST,
                new HttpEntity<>(request, headers), com.fasterxml.jackson.databind.JsonNode.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(original.getBody());
        assertThat(replay.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("corr-query-002");
        assertThat(jdbc.queryForObject("SELECT payload_hash FROM inventory_operations", String.class)).isEqualTo(hash);
        assertThat(jdbc.queryForObject("SELECT response_payload::text FROM inventory_operations", String.class)).isEqualTo(snapshot);
        assertStock(2);
    }

    @Test
    void releaseJsonFieldOrderAndWhitespaceDoNotChangeHashOrReplay() {
        var request = reserveRequest();
        inventory.reserve(request, reserveKey(request));
        var body = new InternalReleaseRequest("cmd-001", request.orderId(), "corr-001");
        var first = http.exchange("/internal/v1/reservations/" + request.reservationId() + "/release", HttpMethod.POST,
                entity(body, releaseKey(request)), com.fasterxml.jackson.databind.JsonNode.class);
        String hash = jdbc.queryForObject("SELECT payload_hash FROM inventory_operations WHERE operation = 'RELEASE'", String.class);
        String reordered = "{ \"correlationId\": \"corr-001\", \"orderId\": \"" + request.orderId()
                + "\", \"commandId\": \"cmd-001\" }";
        var replay = http.exchange("/internal/v1/reservations/" + request.reservationId() + "/release", HttpMethod.POST,
                entity(reordered, releaseKey(request)), com.fasterxml.jackson.databind.JsonNode.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(replay.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("corr-001");
        assertThat(jdbc.queryForObject("SELECT payload_hash FROM inventory_operations WHERE operation = 'RELEASE'", String.class)).isEqualTo(hash);
        assertThat(count("inventory_operations")).isEqualTo(2);
        assertStock(0);
    }

    private static class DefaultTraceHeader implements org.springframework.http.client.ClientHttpRequestInterceptor {
        @Override
        public org.springframework.http.client.ClientHttpResponse intercept(org.springframework.http.HttpRequest request,
                byte[] body, org.springframework.http.client.ClientHttpRequestExecution execution) throws java.io.IOException {
            if (!request.getHeaders().containsKey("X-Correlation-Id")) {
                request.getHeaders().set("X-Correlation-Id", "corr-001");
            }
            return execution.execute(request, body);
        }
    }

    private ReserveRequest reserveRequest() {
        return new ReserveRequest(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "STORE", List.of(new Item("A", 2), new Item("B", 3)));
    }
    private String reserveKey(ReserveRequest r) { return "reserve:" + r.orderId() + ":v1"; }
    private String releaseKey(ReserveRequest r) { return "inventory:release:" + r.orderId() + ":" + r.reservationId() + ":v1"; }
    private String cancelKey(String order) { return "inventory:cancel:" + order + ":v1"; }
    private String releasePath(String reservationId) { return "/api/v1/inventory/reservations/" + reservationId + "/release"; }
    private ReleaseResult release(ReserveRequest r) {
        return lifecycle.release(r.reservationId(), new ReleaseRequest(r.orderId(), r.reservationId(), "FAILED"), releaseKey(r));
    }
    private CancelResult cancel(String order) {
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
