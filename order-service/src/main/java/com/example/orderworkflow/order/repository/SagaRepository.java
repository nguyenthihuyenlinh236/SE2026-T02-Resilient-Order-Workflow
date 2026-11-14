package com.example.orderworkflow.order.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.orderworkflow.order.repository.SagaRecords.Attempt;
import com.example.orderworkflow.order.repository.SagaRecords.Saga;
import com.example.orderworkflow.order.repository.SagaRecords.SagaStep;

@Repository
public class SagaRepository {
    private final JdbcTemplate jdbc;

    public SagaRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public UUID insertSaga(String orderId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sagas(saga_id,order_id,status,branch,current_operation) "
                + "VALUES (?,?,'RUNNING','FORWARD','RESERVE')", id, orderId);
        return id;
    }

    public UUID insertStep(UUID sagaId, String operation, String correlationId, String key, String requestPayload) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO saga_steps(step_id,saga_id,operation,command_id,correlation_id,idempotency_key,"
                + "request_payload,payload_hash,status) VALUES (?,?,?,?,?,?,CAST(? AS jsonb),?,'NOT_STARTED')",
                id, sagaId, operation, UUID.randomUUID(), correlationId, key, requestPayload, sha256(requestPayload));
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

    public UUID insertAttempt(UUID stepId, String kind, int sequenceNo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO saga_attempts(attempt_id,step_id,kind,sequence_no) VALUES (?,?,?,?)",
                id, stepId, kind, sequenceNo);
        if ("SEND".equals(kind)) {
            jdbc.update("UPDATE saga_steps SET attempt_count=attempt_count+1,updated_at=clock_timestamp() WHERE step_id=?", stepId);
        }
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

    public int updateStep(UUID stepId, String expected, String target, String resultPayload,
            Instant nextAttemptAt, Instant unknownSince, Instant nextQueryAt, String error) {
        return jdbc.update("UPDATE saga_steps SET status=?,result_payload=CAST(? AS jsonb),next_attempt_at=?,"
                + "unknown_since=?,next_query_at=?,last_error=?,updated_at=clock_timestamp(),"
                + "completed_at=CASE WHEN ? IN ('SUCCEEDED','FAILED') THEN clock_timestamp() ELSE NULL END "
                + "WHERE step_id=? AND status=?", target, resultPayload, ts(nextAttemptAt), ts(unknownSince),
                ts(nextQueryAt), error, target, stepId, expected);
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
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
