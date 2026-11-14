package com.example.orderworkflow.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.orderworkflow.order.service.OrderStateMachine;
import com.example.orderworkflow.order.service.OrderStateMachine.Transition;
import com.example.orderworkflow.order.repository.SagaRepository;

@Testcontainers
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.sql.init.mode=never",
        "management.health.rabbit.enabled=false"})
class OrderStateMachineIntegrationTests {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        p.add("spring.datasource.username", POSTGRES::getUsername);
        p.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired OrderStateMachine machine;
    @Autowired SagaRepository sagas;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper json;

    @BeforeEach
    void reset() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_outbox_trigger ON outbox_events");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_outbox_insert()");
        jdbc.execute("DROP TRIGGER IF EXISTS fail_attempt_trigger ON saga_attempts");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_attempt_insert()");
        jdbc.execute("TRUNCATE saga_attempts,saga_steps,sagas,outbox_events,order_items,idempotency_records,orders,product_catalog");
    }

    @Test
    void verifiedReservationRejectsStaleUnknownAndNotCreated() {
        String id = order(Instant.now().plusSeconds(300));
        machine.recordReservation(id, "RESERVED", true, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                machine.recordReservation(id, "UNKNOWN", false, "RESERVE"))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                machine.recordReservation(id, "NOT_CREATED", true, null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT reservation_status FROM orders WHERE order_id=?", String.class, id))
                .isEqualTo("RESERVED");
        machine.recordReservation(id, "RELEASED", true, null);
    }

    @Test
    void verifiedCaptureRefundAndCompletedStepRejectStaleUpdates() {
        String captureOrder = order(Instant.now().plusSeconds(300));
        machine.recordCapture(captureOrder, captureOrder, "PAY-1", "FAILED", true, null, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> machine.recordCapture(
                captureOrder, captureOrder, "PAY-1", "UNKNOWN", false, null, null));

        String refundOrder = order(Instant.now().plusSeconds(300));
        machine.recordRefund(refundOrder, "REF-1", "FAILED", true);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                machine.recordRefund(refundOrder, "REF-1", "UNKNOWN", false));

        UUID step = compensationStep(refundOrder, "REFUND", "SUCCEEDED", 1, Instant.now());
        assertThat(sagas.updateStep(step, "SUCCEEDED", "UNKNOWN", "UNKNOWN", "{}", null,
                Instant.now(), Instant.now(), "late timeout")).isZero();
    }

    @Test
    void identicalVerifiedResultsReplayButConflictingResultsDoNotOverwrite() {
        String id = order(Instant.now().plusSeconds(300));
        machine.recordReservation(id, "RESERVED", true, null);
        machine.recordReservation(id, "RESERVED", true, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                machine.recordReservation(id, "REJECTED", true, null));

        machine.recordCapture(id, id, "PAY-1", "SUCCEEDED", true, new BigDecimal("100.00"), "VND");
        machine.recordCapture(id, id, "PAY-1", "SUCCEEDED", true, new BigDecimal("100.00"), "VND");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                machine.recordCapture(id, id, "PAY-1", "FAILED", true, null, null));

        machine.recordRefund(id, "REF-1", "SUCCEEDED", true);
        machine.recordRefund(id, "REF-1", "SUCCEEDED", true);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                machine.recordRefund(id, "REF-1", "FAILED", true));

        assertThat(machine.confirm(id)).isEqualTo(Transition.APPLIED);
        machine.recordReservation(id, "RESERVED", true, null);
        machine.recordCapture(id, id, "PAY-1", "SUCCEEDED", true, new BigDecimal("100.00"), "VND");
    }

    @Test
    void notCreatedMustBeVerifiedBeforeFailedOrExpiredCanBeFinalized() {
        String failed = order(Instant.now().plusSeconds(300));
        machine.recordCapture(failed, failed, "PAY-1", "FAILED", true, null, null);
        assertThat(machine.startCompensation(failed, "PAYMENT_FAILED")).isEqualTo(Transition.APPLIED);
        assertThat(machine.fail(failed)).isEqualTo(Transition.REJECTED);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                machine.recordReservation(failed, "NOT_CREATED", false, null));
        machine.recordReservation(failed, "NOT_CREATED", true, null);
        assertThat(machine.fail(failed)).isEqualTo(Transition.APPLIED);

        String expired = order(Instant.now().minusSeconds(1));
        UUID token = machine.claimExpired(expired);
        assertThat(machine.expire(expired, token)).isEqualTo(Transition.REJECTED);
        machine.recordReservation(expired, "NOT_CREATED", true, null);
        assertThat(machine.expire(expired, token)).isEqualTo(Transition.APPLIED);
    }

    @Test
    void confirmationRequiresVerifiedEvidenceAndWritesOneOutboxEvent() {
        String id = order(Instant.now().plusSeconds(300));
        assertThat(machine.confirm(id)).isEqualTo(Transition.REJECTED);
        machine.recordReservation(id, "RESERVED", true, null);
        assertThat(machine.confirm(id)).isEqualTo(Transition.REJECTED);
        machine.recordCapture(id, id, "PAY-1", "SUCCEEDED", true, new BigDecimal("100.00"), "VND");
        UUID captureStep = compensationStep(id, "CAPTURE", "SUCCEEDED", 1, Instant.now());
        UUID commandId = jdbc.queryForObject("SELECT command_id FROM saga_steps WHERE step_id=?", UUID.class, captureStep);

        assertThat(machine.confirm(id)).isEqualTo(Transition.APPLIED);
        assertThat(machine.confirm(id)).isEqualTo(Transition.REJECTED);
        assertThat(status(id)).isEqualTo("CONFIRMED");
        assertThat(count("outbox_events")).isOne();
        assertThat(sagaStatus(id)).isEqualTo("COMPLETED");
        assertEvent(id, "OrderConfirmed", "CONFIRMED", "confirmedAt", null);
        assertThat(jdbc.queryForObject("SELECT command_id FROM outbox_events WHERE aggregate_id=?",
                UUID.class, id)).isEqualTo(commandId);
    }

    @Test
    void captureForWrongOrderOrWrongMoneyCannotConfirm() {
        String id = order(Instant.now().plusSeconds(300));
        machine.recordReservation(id, "RESERVED", true, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> machine.recordCapture(
                id, "ORD-other", "PAY-1", "SUCCEEDED", true, new BigDecimal("100.00"), "VND"));
        machine.recordCapture(id, id, "PAY-1", "SUCCEEDED", true, new BigDecimal("99.00"), "VND");
        assertThat(machine.confirm(id)).isEqualTo(Transition.REJECTED);
    }

    @Test
    void unknownIsNotFailureAndBlocksExpiration() {
        String id = order(Instant.now().minusSeconds(1));
        machine.recordCapture(id, id, "PAY-1", "UNKNOWN", false, null, null);
        assertThat(machine.claimExpired(id)).isNull();
        assertThat(status(id)).isEqualTo("PENDING");
    }

    @Test
    void competingConfirmAndExpireAllowOnlyOneBranch() throws Exception {
        String id = order(Instant.now().plusSeconds(300));
        machine.recordReservation(id, "RESERVED", true, null);
        machine.recordCapture(id, id, "PAY-1", "SUCCEEDED", true, new BigDecimal("100.00"), "VND");
        var start = new CountDownLatch(1);
        var entered = new CountDownLatch(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var confirm = pool.submit(() -> { start.await(); entered.countDown(); return machine.confirm(id); });
            var expire = pool.submit(() -> { start.await(); entered.countDown(); return machine.claimExpired(id); });
            new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
                jdbc.queryForObject("SELECT order_id FROM orders WHERE order_id=? FOR UPDATE", String.class, id);
                start.countDown();
                try { entered.await(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                jdbc.update("UPDATE orders SET expire_at=clock_timestamp() WHERE order_id=?", id);
            });
            var confirmation = confirm.get();
            var token = expire.get();
            int winners = (confirmation == Transition.APPLIED ? 1 : 0) + (token != null ? 1 : 0);
            assertThat(winners).isLessThanOrEqualTo(1);
            if (winners == 0) token = machine.claimExpired(id);
            assertThat((confirmation == Transition.APPLIED) ^ (token != null)).isTrue();
            assertThat(status(id)).isIn("CONFIRMED", "EXPIRING");
        } finally { pool.shutdownNow(); }
    }

    @Test
    void confirmWinsWhenCommittedBeforeTtl() {
        String id = order(Instant.now().plusSeconds(300));
        machine.recordReservation(id, "RESERVED", true, null);
        machine.recordCapture(id, id, "PAY-1", "SUCCEEDED", true, new BigDecimal("100.00"), "VND");
        assertThat(machine.confirm(id)).isEqualTo(Transition.APPLIED);
        jdbc.update("UPDATE orders SET expire_at=clock_timestamp()-interval '1 second' WHERE order_id=?", id);
        assertThat(machine.claimExpired(id)).isNull();
        assertThat(status(id)).isEqualTo("CONFIRMED");
    }

    @Test
    void expirationWinsAfterTtl() {
        String id = order(Instant.now().minusSeconds(1));
        machine.recordReservation(id, "RESERVED", true, null);
        machine.recordCapture(id, id, "PAY-1", "SUCCEEDED", true, new BigDecimal("100.00"), "VND");
        assertThat(machine.claimExpired(id)).isNotNull();
        assertThat(machine.confirm(id)).isEqualTo(Transition.REJECTED);
        assertThat(status(id)).isEqualTo("EXPIRING");
    }

    @Test
    void attemptInsertAndCounterAreAtomicOnRollbackAndUnderConcurrency() throws Exception {
        String id = order(Instant.now().plusSeconds(300));
        UUID step = compensationStep(id, "RELEASE", "IN_PROGRESS", 0, Instant.now());
        jdbc.execute("CREATE FUNCTION fail_attempt_insert() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'attempt failure'; END $$");
        jdbc.execute("CREATE TRIGGER fail_attempt_trigger BEFORE INSERT ON saga_attempts "
                + "FOR EACH ROW EXECUTE FUNCTION fail_attempt_insert()");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sagas.insertAttempt(step, "SEND"));
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM saga_steps WHERE step_id=?", Integer.class, step)).isZero();
        jdbc.execute("DROP TRIGGER fail_attempt_trigger ON saga_attempts");
        jdbc.execute("DROP FUNCTION fail_attempt_insert()");

        int workers = 8;
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(workers);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<UUID>>();
            for (int i = 0; i < workers; i++) futures.add(pool.submit(() -> {
                start.await(); return sagas.insertAttempt(step, "SEND");
            }));
            start.countDown();
            for (var future : futures) assertThat(future.get()).isNotNull();
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM saga_steps WHERE step_id=?", Integer.class, step))
                .isEqualTo(workers);
        assertThat(jdbc.queryForList("SELECT sequence_no FROM saga_attempts WHERE step_id=? ORDER BY sequence_no",
                Integer.class, step)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
    }

    @Test
    void compensationFailureAndRetryRequireExhaustedDueStep() {
        String id = order(Instant.now().plusSeconds(300));
        machine.recordReservation(id, "RESERVED", true, null);
        machine.recordCapture(id, id, "PAY-1", "FAILED", true, null, null);
        assertThat(machine.startCompensation(id, "PAYMENT_FAILED")).isEqualTo(Transition.APPLIED);
        UUID step = compensationStep(id, "RELEASE", "IN_PROGRESS", "TRANSIENT", 4, Instant.now().minusSeconds(1));
        assertThat(machine.compensationFailed(id)).isEqualTo(Transition.REJECTED);
        jdbc.update("UPDATE saga_steps SET attempt_count=5,last_error=NULL WHERE step_id=?", step);
        assertThat(machine.compensationFailed(id)).isEqualTo(Transition.REJECTED);
        jdbc.update("UPDATE saga_steps SET last_error='connection timeout',next_attempt_at=NULL WHERE step_id=?", step);
        assertThat(machine.compensationFailed(id)).isEqualTo(Transition.REJECTED);
        jdbc.update("UPDATE saga_steps SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE step_id=?", step);
        assertThat(machine.compensationFailed(id)).isEqualTo(Transition.APPLIED);
        jdbc.update("UPDATE saga_steps SET next_attempt_at=clock_timestamp()+interval '1 hour' WHERE step_id=?", step);
        assertThat(machine.retryCompensation(id)).isEqualTo(Transition.REJECTED);
        jdbc.update("UPDATE saga_steps SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE step_id=?", step);
        assertThat(machine.retryCompensation(id)).isEqualTo(Transition.APPLIED);

        String business = compensatingOrder();
        compensationStep(business, "RELEASE", "FAILED", "BUSINESS", 5, Instant.now().minusSeconds(1));
        assertThat(machine.compensationFailed(business)).isEqualTo(Transition.REJECTED);

        String unknown = compensatingOrder();
        compensationStep(unknown, "RELEASE", "UNKNOWN", "UNKNOWN", 5, Instant.now().minusSeconds(1));
        assertThat(machine.compensationFailed(unknown)).isEqualTo(Transition.REJECTED);
    }

    @Test
    void expirationKeepsOrderExpiringWhenCompensationNeedsBackgroundRetry() {
        String id = order(Instant.now().minusSeconds(1));
        UUID token = machine.claimExpired(id);
        assertThat(token).isNotNull();
        compensationStep(id, "RELEASE", "IN_PROGRESS", "TRANSIENT", 5, Instant.now().minusSeconds(1));
        assertThat(machine.compensationFailed(id)).isEqualTo(Transition.APPLIED);
        assertThat(status(id)).isEqualTo("EXPIRING");
        assertThat(sagaStatus(id)).isEqualTo("COMPENSATION_FAILED");
        assertThat(sagaBranch(id)).isEqualTo("EXPIRATION");
        assertThat(machine.retryCompensation(id)).isEqualTo(Transition.APPLIED);
        assertThat(status(id)).isEqualTo("EXPIRING");
        assertThat(sagaStatus(id)).isEqualTo("COMPENSATING");
        assertThat(sagaBranch(id)).isEqualTo("EXPIRATION");
    }

    @Test
    void twoWorkersCannotClaimAndStaleWorkerCannotComplete() throws Exception {
        String id = order(Instant.now().minusSeconds(1));
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        UUID first;
        UUID second;
        try {
            var a = pool.submit(() -> { start.await(); return machine.claimExpired(id); });
            var b = pool.submit(() -> { start.await(); return machine.claimExpired(id); });
            start.countDown(); first = a.get(); second = b.get();
        } finally { pool.shutdownNow(); }
        assertThat((first == null ? 0 : 1) + (second == null ? 0 : 1)).isOne();
        UUID old = first != null ? first : second;
        jdbc.update("UPDATE orders SET claimed_at=clock_timestamp()-interval '2 seconds',"
                + "lease_until=clock_timestamp()-interval '1 second' WHERE order_id=?", id);
        UUID replacement = machine.takeOverExpired(id, old);
        assertThat(replacement).isNotNull().isNotEqualTo(old);
        assertThat(machine.expire(id, old)).isEqualTo(Transition.REJECTED);
    }

    @Test
    void outboxFailureRollsBackTerminalOrderAndSaga() {
        String id = order(Instant.now().plusSeconds(300));
        machine.recordReservation(id, "RESERVED", true, null);
        machine.recordCapture(id, id, "PAY-1", "SUCCEEDED", true, new BigDecimal("100.00"), "VND");
        jdbc.execute("CREATE FUNCTION fail_outbox_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fail'; END $$");
        jdbc.execute("CREATE TRIGGER fail_outbox_trigger BEFORE INSERT ON outbox_events FOR EACH ROW EXECUTE FUNCTION fail_outbox_insert()");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> machine.confirm(id));
        assertThat(status(id)).isEqualTo("PENDING");
        assertThat(sagaStatus(id)).isEqualTo("RUNNING");
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    void compensationCannotFinishUntilAllRequiredEvidenceIsVerified() {
        String id = order(Instant.now().plusSeconds(300));
        machine.recordReservation(id, "RESERVED", true, null);
        machine.recordCapture(id, id, "PAY-1", "FAILED", true, null, null);
        assertThat(machine.startCompensation(id, "PAYMENT_FAILED")).isEqualTo(Transition.APPLIED);
        assertThat(machine.fail(id)).isEqualTo(Transition.REJECTED);

        machine.recordReservation(id, "RELEASED", true, null);
        assertThat(machine.fail(id)).isEqualTo(Transition.APPLIED);
        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(count("outbox_events")).isOne();
        assertEvent(id, "OrderFailed", "FAILED", "failedAt", "PAYMENT_FAILED");
    }

    @Test
    void orderFailedPreservesInventoryReasonFromClaimedBranch() {
        String id = order(Instant.now().plusSeconds(300));
        machine.recordReservation(id, "REJECTED", true, null);
        assertThat(machine.startCompensation(id, "INVENTORY_REJECTED")).isEqualTo(Transition.APPLIED);
        assertThat(machine.fail(id)).isEqualTo(Transition.APPLIED);
        assertEvent(id, "OrderFailed", "FAILED", "failedAt", "INVENTORY_REJECTED");
    }

    @Test
    void expirationRequiresCurrentLeaseAndCompletedRefundForCapturedPayment() {
        String id = order(Instant.now().minusSeconds(1));
        machine.recordReservation(id, "RELEASED", true, null);
        machine.recordCapture(id, id, "PAY-1", "SUCCEEDED", true, new BigDecimal("100.00"), "VND");
        UUID token = machine.claimExpired(id);
        assertThat(token).isNotNull();
        assertThat(machine.expire(id, token)).isEqualTo(Transition.REJECTED);
        machine.recordRefund(id, "REF-1", "SUCCEEDED", true);
        assertThat(machine.expire(id, token)).isEqualTo(Transition.APPLIED);
        assertThat(status(id)).isEqualTo("EXPIRED");
        assertEvent(id, "OrderExpired", "EXPIRED", "expiredAt", null);
    }

    @Test
    void lateCaptureReopensSameSagaForIdempotentRefundTracking() {
        String id = order(Instant.now().minusSeconds(1));
        machine.recordReservation(id, "NOT_CREATED", true, null);
        UUID token = machine.claimExpired(id);
        assertThat(machine.expire(id, token)).isEqualTo(Transition.APPLIED);
        UUID sagaId = jdbc.queryForObject("SELECT saga_id FROM sagas WHERE order_id=?", UUID.class, id);

        machine.recordCapture(id, id, "PAY-LATE", "SUCCEEDED", true, new BigDecimal("100.00"), "VND");
        assertThat(status(id)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM sagas WHERE saga_id=?", String.class, sagaId))
                .isEqualTo("COMPENSATING");
        assertThat(jdbc.queryForObject("SELECT completed_at IS NULL FROM sagas WHERE saga_id=?", Boolean.class, sagaId))
                .isTrue();
        assertThat(count("sagas")).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM saga_steps WHERE saga_id=? AND operation='REFUND'",
                Integer.class, sagaId)).isOne();
        String request = jdbc.queryForObject("SELECT request_payload::text FROM saga_steps "
                + "WHERE saga_id=? AND operation='REFUND'", String.class, sagaId);
        assertThat(request).contains("LATE_CAPTURE").doesNotContain("schemaVersion");
        assertThat(jdbc.queryForObject("SELECT idempotency_key FROM saga_steps WHERE saga_id=? AND operation='REFUND'",
                String.class, sagaId)).isEqualTo("payment:refund:" + id + ":PAY-LATE:v1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=?",
                Integer.class, id)).isOne();

        machine.recordCapture(id, id, "PAY-LATE", "SUCCEEDED", true, new BigDecimal("100.00"), "VND");
        assertThat(count("sagas")).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM saga_steps WHERE saga_id=? AND operation='REFUND'",
                Integer.class, sagaId)).isOne();

        jdbc.update("UPDATE saga_steps SET status='IN_PROGRESS',failure_class='TRANSIENT',attempt_count=5,"
                + "last_error='payment unavailable',next_attempt_at=clock_timestamp()-interval '1 second' "
                + "WHERE saga_id=? AND operation='REFUND'", sagaId);
        assertThat(machine.compensationFailed(id)).isEqualTo(Transition.APPLIED);
        assertThat(status(id)).isEqualTo("EXPIRED");
        assertThat(machine.retryCompensation(id)).isEqualTo(Transition.APPLIED);
        assertThat(status(id)).isEqualTo("EXPIRED");

        machine.recordRefund(id, "REF-LATE", "SUCCEEDED", true);
        assertThat(jdbc.queryForObject("SELECT status FROM sagas WHERE saga_id=?", String.class, sagaId))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM sagas WHERE saga_id=?", Boolean.class, sagaId))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM saga_steps WHERE saga_id=? AND operation='REFUND'",
                String.class, sagaId)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=? AND event_type='OrderConfirmed'",
                Integer.class, id)).isZero();
    }

    private String order(Instant expiry) {
        String id = "ORD-" + UUID.randomUUID().toString().replace("-", "");
        Instant created = expiry.minusSeconds(900);
        jdbc.update("INSERT INTO orders(order_id,customer_id,client_id,store_id,total_amount,currency,reservation_id,"
                + "correlation_id,created_at,updated_at,expire_at) VALUES (?,?,?,?,100.00,'VND',?,?,?,?,?)",
                id, "CUSTOMER-1", "CLIENT-1", "STORE-1", "RES-" + id.substring(4), "corr",
                Timestamp.from(created), Timestamp.from(created), Timestamp.from(expiry));
        jdbc.update("INSERT INTO sagas(saga_id,order_id,status,branch,current_operation) VALUES (?,?,'RUNNING','FORWARD','RESERVE')",
                UUID.randomUUID(), id);
        return id;
    }

    private String compensatingOrder() {
        String id = order(Instant.now().plusSeconds(300));
        machine.recordCapture(id, id, "PAY-1", "FAILED", true, null, null);
        assertThat(machine.startCompensation(id, "PAYMENT_FAILED")).isEqualTo(Transition.APPLIED);
        return id;
    }

    private UUID compensationStep(String orderId, String operation, String status, int attempts, Instant nextAttempt) {
        String failureClass = "IN_PROGRESS".equals(status) ? "NONE"
                : "FAILED".equals(status) ? "BUSINESS" : "NONE";
        return compensationStep(orderId, operation, status, failureClass, attempts, nextAttempt);
    }

    private UUID compensationStep(String orderId, String operation, String status, String failureClass,
            int attempts, Instant nextAttempt) {
        UUID saga = jdbc.queryForObject("SELECT saga_id FROM sagas WHERE order_id=?", UUID.class, orderId);
        UUID step = UUID.randomUUID();
        String body = "{\"orderId\":\"" + orderId + "\"}";
        jdbc.update("INSERT INTO saga_steps(step_id,saga_id,operation,command_id,correlation_id,idempotency_key,"
                + "saga_payload,request_payload,payload_hash,status,failure_class,attempt_count,next_attempt_at,last_error,completed_at) "
                + "VALUES (?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),"
                + "encode(sha256(convert_to(CAST(? AS jsonb)::text,'UTF8')),'hex'),?,?,?,?,?,"
                + "CASE WHEN ? IN ('SUCCEEDED','FAILED') THEN clock_timestamp() END)",
                step, saga, operation, UUID.randomUUID(), "corr", operation.toLowerCase() + ":" + orderId,
                body, body, body, status, failureClass, attempts, Timestamp.from(nextAttempt),
                "NONE".equals(failureClass) ? null : "simulated " + failureClass.toLowerCase(), status);
        return step;
    }

    private String status(String id) { return jdbc.queryForObject("SELECT status FROM orders WHERE order_id=?", String.class, id); }
    private String sagaStatus(String id) { return jdbc.queryForObject("SELECT status FROM sagas WHERE order_id=?", String.class, id); }
    private String sagaBranch(String id) { return jdbc.queryForObject("SELECT branch FROM sagas WHERE order_id=?", String.class, id); }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }

    private void assertEvent(String orderId, String eventType, String expectedStatus,
            String timestampField, String reasonCode) {
        var row = jdbc.queryForMap("SELECT event_id,command_id,correlation_id,schema_version,event_type,"
                + "occurred_at,producer,payload::text payload FROM outbox_events "
                + "WHERE aggregate_id=? AND event_type=?", orderId, eventType);
        assertThat(row.get("event_id")).isInstanceOf(UUID.class);
        assertThat(row).containsKey("command_id");
        assertThat(row.get("correlation_id")).isEqualTo("corr");
        assertThat(row.get("schema_version")).isEqualTo(1);
        assertThat(row.get("event_type")).isEqualTo(eventType);
        assertThat(row.get("producer")).isEqualTo("order-service");
        try {
            var payload = json.readTree((String) row.get("payload"));
            assertThat(payload.get("orderId").asText()).isEqualTo(orderId);
            assertThat(payload.get("status").asText()).isEqualTo(expectedStatus);
            assertThat(Instant.parse(payload.get(timestampField).asText()))
                    .isEqualTo(((Timestamp) row.get("occurred_at")).toInstant());
            if (reasonCode == null) assertThat(payload.has("reasonCode")).isFalse();
            else assertThat(payload.get("reasonCode").asText()).isEqualTo(reasonCode);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
