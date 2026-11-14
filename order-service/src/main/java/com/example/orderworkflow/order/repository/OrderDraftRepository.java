package com.example.orderworkflow.order.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderDraftRepository {
    private final JdbcTemplate jdbc;

    public OrderDraftRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StoredDraft> saveNewer(String clientId, String draftId, long revision,
            String payload, String hash, String correlationId) {
        List<StoredDraft> rows = jdbc.query("""
                INSERT INTO order_drafts
                    (client_id,draft_id,revision,payload,payload_hash,correlation_id)
                VALUES (?,?,?,CAST(? AS jsonb),?,?)
                ON CONFLICT (client_id,draft_id) DO UPDATE SET
                    revision=EXCLUDED.revision,
                    payload=EXCLUDED.payload,
                    payload_hash=EXCLUDED.payload_hash,
                    correlation_id=EXCLUDED.correlation_id,
                    saved_at=clock_timestamp()
                WHERE order_drafts.revision < EXCLUDED.revision
                RETURNING client_id,draft_id,revision,payload::text,payload_hash,
                    correlation_id,saved_at,(xmax = 0) AS inserted
                """, OrderDraftRepository::map, clientId, draftId, revision, payload, hash,
                correlationId);
        return rows.stream().findFirst();
    }

    public Optional<StoredDraft> find(String clientId, String draftId) {
        return jdbc.query("""
                SELECT client_id,draft_id,revision,payload::text,payload_hash,
                    correlation_id,saved_at,false AS inserted
                FROM order_drafts WHERE client_id=? AND draft_id=?
                """, OrderDraftRepository::map, clientId, draftId).stream().findFirst();
    }

    private static StoredDraft map(ResultSet rs, int row) throws SQLException {
        return new StoredDraft(rs.getString("client_id"), rs.getString("draft_id"),
                rs.getLong("revision"), rs.getString("payload"), rs.getString("payload_hash").trim(),
                rs.getString("correlation_id"), rs.getObject("saved_at", OffsetDateTime.class).toInstant(),
                rs.getBoolean("inserted"));
    }

    public record StoredDraft(String clientId, String draftId, long revision, String payload,
            String payloadHash, String correlationId, Instant savedAt, boolean inserted) { }
}
