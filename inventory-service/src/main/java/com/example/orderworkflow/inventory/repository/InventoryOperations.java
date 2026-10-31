package com.example.orderworkflow.inventory.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.orderworkflow.inventory.api.InventoryDtos.OperationResult;
import com.example.orderworkflow.inventory.service.InventoryException;

@Repository
public class InventoryOperations {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public InventoryOperations(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public <T> Optional<T> replay(String key, String operation, String hash, Class<T> type) {
        var rows = jdbc.query("SELECT operation, payload_hash, response_payload::text FROM inventory_operations WHERE idempotency_key = ?",
                (rs, row) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, key);
        if (rows.isEmpty()) { return Optional.empty(); }
        var saved = rows.get(0);
        if (!saved[0].equals(operation) || !saved[1].equals(hash)) {
            throw new InventoryException(409, "IDEMPOTENCY_KEY_CONFLICT", "Key was used with a different payload");
        }
        try { return Optional.of(mapper.readValue(saved[2], type)); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Invalid persisted command response", e); }
    }

    public void save(UUID orderId, String key, String operation, String hash, Object response) {
        try {
            jdbc.update("INSERT INTO inventory_operations(idempotency_key, order_id, operation, payload_hash, response_code, response_payload) "
                            + "VALUES (?, ?, ?, ?, 200, CAST(? AS jsonb))",
                    key, orderId, operation, hash, mapper.writeValueAsString(response));
        } catch (JsonProcessingException e) { throw new IllegalStateException("Cannot persist command response", e); }
    }

    public Optional<OperationResult> find(String key) {
        return jdbc.query("SELECT * FROM inventory_operations WHERE idempotency_key = ?", (rs, row) -> {
            try {
                return new OperationResult(rs.getString("operation"), rs.getString("idempotency_key"),
                        rs.getInt("response_code"), mapper.readTree(rs.getString("response_payload")),
                        rs.getTimestamp("completed_at").toInstant());
            } catch (JsonProcessingException e) { throw new IllegalStateException("Invalid persisted response", e); }
        }, key).stream().findFirst();
    }
}
