package com.example.orderworkflow.order.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.example.orderworkflow.order.repository.SagaRecords.Attempt;
import com.example.orderworkflow.order.repository.SagaRecords.Saga;
import com.example.orderworkflow.order.repository.SagaRecords.SagaStep;

@Repository
public class SagaRepository {
    private final JdbcTemplate jdbc;

    public SagaRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record ClaimedReserve(UUID stepId, UUID sagaId, String orderId, String idempotencyKey,
            String requestPayload, UUID claimToken, UUID attemptId, int attemptCount) { }

    /** Atomically claims one due RESERVE step and starts its durable SEND attempt. */
    @Transactional
    public Optional<ClaimedReserve> claimReserve(long leaseMillis) {
        UUID token = UUID.randomUUID();
        var rows = jdbc.query("""
                    WITH candidate AS (
                        SELECT st.step_id
                        FROM saga_steps st
                        JOIN sagas s ON s.saga_id=st.saga_id
                        JOIN orders o ON o.order_id=s.order_id
                        WHERE st.operation='RESERVE'
                          AND s.status='RUNNING' AND s.branch='FORWARD'
                          AND o.status='PENDING'
                          AND ((st.status IN ('NOT_STARTED','UNKNOWN')
                                AND (st.next_attempt_at IS NULL OR st.next_attempt_at<=clock_timestamp()))
                               OR (st.status='IN_PROGRESS' AND st.lease_until<=clock_timestamp()))
                        ORDER BY st.next_attempt_at NULLS FIRST,st.created_at,st.step_id
                        FOR UPDATE OF st SKIP LOCKED
                        LIMIT 1
                    ), claimed AS (
                        UPDATE saga_steps st SET status='IN_PROGRESS',failure_class='NONE',
                            claim_token=?,claimed_at=clock_timestamp(),
                            lease_until=clock_timestamp()+(? * interval '1 millisecond'),
                            attempt_count=attempt_count+1,next_attempt_at=NULL,last_error=NULL,
                            updated_at=clock_timestamp()
                        FROM candidate c WHERE st.step_id=c.step_id
                        RETURNING st.step_id,st.saga_id,st.idempotency_key,st.request_payload::text,
                                  st.claim_token,st.attempt_count
                    )
                    SELECT c.step_id,c.saga_id,s.order_id,c.idempotency_key,c.request_payload,
                           c.claim_token,c.attempt_count
                    FROM claimed c JOIN sagas s ON s.saga_id=c.saga_id
                    """, (rs, row) -> {
                UUID stepId = rs.getObject("step_id", UUID.class);
                int sequence = rs.getInt("attempt_count");
                return new ClaimedReserve(stepId, rs.getObject("saga_id", UUID.class), rs.getString("order_id"),
                        rs.getString("idempotency_key"), rs.getString("request_payload"),
                        rs.getObject("claim_token", UUID.class), null, sequence);
            }, token, leaseMillis);
        if (rows.isEmpty()) return Optional.empty();
        var claimed = rows.get(0);
        UUID attemptId = UUID.randomUUID();
        jdbc.update("INSERT INTO saga_attempts(attempt_id,step_id,kind,sequence_no) VALUES (?,?,'SEND',?)",
                attemptId, claimed.stepId(), claimed.attemptCount());
        return Optional.of(new ClaimedReserve(claimed.stepId(), claimed.sagaId(), claimed.orderId(),
                claimed.idempotencyKey(), claimed.requestPayload(), claimed.claimToken(), attemptId,
                claimed.attemptCount()));
    }

    /** Persists a worker result only while its claim token still owns the row. */
    public int finishReserveClaim(ClaimedReserve claim, String status, String failureClass,
            String resultPayload, String error, long retryDelayMillis) {
        return jdbc.update("UPDATE saga_steps SET status=?,failure_class=?,result_payload=CAST(? AS jsonb),"
                + "unknown_since=CASE WHEN ?='UNKNOWN' THEN COALESCE(unknown_since,clock_timestamp()) ELSE NULL END,"
                + "next_attempt_at=CASE WHEN ?='UNKNOWN' THEN clock_timestamp()+(? * interval '1 millisecond') ELSE NULL END,"
                + "last_error=?,claim_token=NULL,claimed_at=NULL,lease_until=NULL,updated_at=clock_timestamp(),"
                + "completed_at=CASE WHEN ? IN ('SUCCEEDED','FAILED') THEN clock_timestamp() ELSE NULL END "
                + "WHERE step_id=? AND status='IN_PROGRESS' AND claim_token=?",
                status, failureClass, resultPayload, status, status, retryDelayMillis, error, status,
                claim.stepId(), claim.claimToken());
    }

    public void finishSendAttempt(ClaimedReserve claim, String outcome, String errorCode, String message) {
        jdbc.update("UPDATE saga_attempts SET finished_at=clock_timestamp(),outcome=?,error_code=?,error_message=? "
                + "WHERE attempt_id=? AND finished_at IS NULL", outcome, errorCode, message, claim.attemptId());
    }

    public UUID insertSaga(String orderId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sagas(saga_id,order_id,status,branch,current_operation) "
                + "VALUES (?,?,'RUNNING','FORWARD','RESERVE')", id, orderId);
        return id;
    }

    public UUID insertStep(UUID sagaId, String operation, String correlationId, String key,
            String sagaPayload, String requestPayload) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO saga_steps(step_id,saga_id,operation,command_id,correlation_id,idempotency_key,"
                + "saga_payload,request_payload,payload_hash,status) VALUES (?,?,?,?,?,?,CAST(? AS jsonb),"
                + "CAST(? AS jsonb),encode(sha256(convert_to(CAST(? AS jsonb)::text,'UTF8')),'hex'),'NOT_STARTED')",
                id, sagaId, operation, UUID.randomUUID(), correlationId, key, sagaPayload, requestPayload, requestPayload);
        return id;
    }

    public Optional<Saga> findByOrderId(String orderId) {
        return jdbc.query("SELECT * FROM sagas WHERE order_id=?", (rs, n) -> saga(rs), orderId)
                .stream().findFirst();
    }

    public Optional<Saga> findById(UUID sagaId) {
        return jdbc.query("SELECT * FROM sagas WHERE saga_id=?", (rs, n) -> saga(rs), sagaId)
                .stream().findFirst();
    }

    public List<SagaStep> findSteps(UUID sagaId) {
        return jdbc.query("SELECT * FROM saga_steps WHERE saga_id=? ORDER BY created_at,step_id",
                (rs, n) -> step(rs), sagaId);
    }

    public List<Saga> findUnfinished() {
        return jdbc.query("SELECT * FROM sagas WHERE status<>'COMPLETED' ORDER BY updated_at,saga_id",
                (rs, n) -> saga(rs));
    }

    public List<Saga> findByStatus(String status) {
        return jdbc.query("SELECT * FROM sagas WHERE status=? ORDER BY updated_at,saga_id",
                (rs, n) -> saga(rs), status);
    }

    public List<SagaStep> findRetryable(int limit) {
        return jdbc.query("SELECT * FROM saga_steps WHERE status IN ('NOT_STARTED','IN_PROGRESS') "
                + "AND (next_attempt_at IS NULL OR next_attempt_at<=clock_timestamp()) "
                + "ORDER BY next_attempt_at NULLS FIRST,created_at LIMIT ?", (rs, n) -> step(rs), limit);
    }

    public List<SagaStep> findReconciliationDue(int limit) {
        return jdbc.query("SELECT * FROM saga_steps WHERE status IN ('UNKNOWN','IN_PROGRESS') "
                + "AND next_query_at<=clock_timestamp() ORDER BY next_query_at LIMIT ?",
                (rs, n) -> step(rs), limit);
    }

    public List<SagaStep> findExpiredLeases(int limit) {
        return jdbc.query("SELECT * FROM saga_steps WHERE claim_token IS NOT NULL "
                + "AND lease_until<=clock_timestamp() ORDER BY lease_until LIMIT ?",
                (rs, n) -> step(rs), limit);
    }

    @Transactional
    public UUID insertAttempt(UUID stepId, String kind) {
        UUID id = UUID.randomUUID();
        Integer sequenceNo;
        if ("SEND".equals(kind)) {
            sequenceNo = jdbc.queryForObject("UPDATE saga_steps SET attempt_count=attempt_count+1,"
                    + "updated_at=clock_timestamp() WHERE step_id=? AND status NOT IN ('SUCCEEDED','FAILED') "
                    + "RETURNING attempt_count", Integer.class, stepId);
        } else if ("QUERY".equals(kind)) {
            sequenceNo = jdbc.queryForObject("UPDATE saga_steps SET query_count=query_count+1,"
                    + "updated_at=clock_timestamp() WHERE step_id=? AND status IN ('UNKNOWN','IN_PROGRESS') "
                    + "AND query_count<30 RETURNING query_count", Integer.class, stepId);
        } else if ("BACKGROUND_QUERY".equals(kind)) {
            sequenceNo = jdbc.queryForObject("UPDATE saga_steps SET background_query_count=background_query_count+1,"
                    + "updated_at=clock_timestamp() WHERE step_id=? AND status='UNKNOWN' "
                    + "RETURNING background_query_count", Integer.class, stepId);
        } else {
            throw new IllegalArgumentException("Unsupported attempt kind: " + kind);
        }
        jdbc.update("INSERT INTO saga_attempts(attempt_id,step_id,kind,sequence_no) VALUES (?,?,?,?)",
                id, stepId, kind, sequenceNo);
        return id;
    }

    public void finishAttempt(UUID attemptId, String outcome, String errorCode, String errorMessage) {
        jdbc.update("UPDATE saga_attempts SET finished_at=clock_timestamp(),outcome=?,error_code=?,error_message=? "
                + "WHERE attempt_id=? AND finished_at IS NULL", outcome, errorCode, errorMessage, attemptId);
    }

    public List<Attempt> findAttempts(UUID stepId) {
        return jdbc.query("SELECT * FROM saga_attempts WHERE step_id=? ORDER BY kind,sequence_no",
                (rs, n) -> new Attempt(rs.getObject("attempt_id", UUID.class),
                        rs.getObject("step_id", UUID.class), rs.getString("kind"), rs.getInt("sequence_no"),
                        instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at")),
                        rs.getString("outcome"), rs.getString("error_code"), rs.getString("error_message")), stepId);
    }

    public int updateStep(UUID stepId, String expected, String target, String failureClass, String resultPayload,
            Instant nextAttemptAt, Instant unknownSince, Instant nextQueryAt, String error) {
        return jdbc.update("UPDATE saga_steps SET status=?,result_payload=CAST(? AS jsonb),next_attempt_at=?,"
                + "unknown_since=?,next_query_at=?,failure_class=?,last_error=?,updated_at=clock_timestamp(),"
                + "completed_at=CASE WHEN ? IN ('SUCCEEDED','FAILED') THEN clock_timestamp() ELSE NULL END "
                + "WHERE step_id=? AND status=? AND status NOT IN ('SUCCEEDED','FAILED')", target, resultPayload, ts(nextAttemptAt), ts(unknownSince),
                ts(nextQueryAt), failureClass, error, target, stepId, expected);
    }

    private static Saga saga(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Saga(rs.getObject("saga_id", UUID.class), rs.getString("order_id"), rs.getString("status"),
                rs.getString("branch"), rs.getString("current_operation"), rs.getString("last_error"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")),
                instant(rs.getTimestamp("completed_at")), rs.getLong("version"));
    }

    private static SagaStep step(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SagaStep(rs.getObject("step_id", UUID.class), rs.getObject("saga_id", UUID.class),
                rs.getString("operation"), rs.getObject("command_id", UUID.class), rs.getString("correlation_id"),
                rs.getString("idempotency_key"), rs.getString("request_payload"), rs.getString("payload_hash"),
                rs.getString("result_payload"), rs.getString("status"), rs.getInt("attempt_count"),
                instant(rs.getTimestamp("next_attempt_at")), instant(rs.getTimestamp("unknown_since")),
                rs.getInt("query_count"), rs.getInt("background_query_count"),
                instant(rs.getTimestamp("next_query_at")), rs.getObject("claim_token", UUID.class),
                instant(rs.getTimestamp("claimed_at")), instant(rs.getTimestamp("lease_until")),
                rs.getString("last_error"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")), instant(rs.getTimestamp("completed_at")));
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
}
