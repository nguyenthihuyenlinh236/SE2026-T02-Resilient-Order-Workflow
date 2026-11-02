package com.example.orderworkflow.inventory.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

public final class InventoryDtos {
    private InventoryDtos() { }

    public record Item(@NotBlank @Size(max = 128) String sku,
            @NotNull @Positive @JsonDeserialize(using = StrictQuantityDeserializer.class) Integer quantity) { }

    public record ReserveRequest(@Identifier String orderId, @Identifier String reservationId,
            @NotBlank @Size(max = 100) String storeId,
            @NotEmpty @Size(max = 100) List<@NotNull @Valid Item> items) { }

    public record Reservation(String reservationId, String orderId, String storeId, String reserveKey,
            String status, String rejectionReason, List<Item> items, Instant createdAt) { }

    public record Stock(UUID productId, String sku, String storeId, String productName, int onHandQuantity,
            int reservedQuantity, int otherCommitments, int availableQuantity) { }

    public record ApiError(String code, String message) { }

    public record ContractErrorResponse(ContractError error) { }
    public record ContractError(String code, String message, List<ErrorDetail> details,
            String correlationId, boolean retryable) { }
    public record ErrorDetail(String field, String reason) { }

    public record ReleaseRequest(@Identifier String orderId, @Identifier String reservationId,
            @NotBlank @Size(max = 255) String reason) { }

    public record CancelRequest(@Identifier String orderId, @NotBlank @Size(max = 255) String reason) { }

    public record ReleaseResult(String orderId, String reservationId, String idempotencyKey,
            String status, Instant releasedAt) { }

    public record CancelResult(String orderId, String idempotencyKey, String status,
            String reservationId, String reservationStatus, Instant cancelledAt) { }

    public record OperationResult(String operation, String idempotencyKey, int responseCode,
            JsonNode responsePayload, Instant completedAt) { }
}
