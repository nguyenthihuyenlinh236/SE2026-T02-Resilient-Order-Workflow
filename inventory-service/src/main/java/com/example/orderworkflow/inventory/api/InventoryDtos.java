package com.example.orderworkflow.inventory.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class InventoryDtos {
    private InventoryDtos() { }

    public record Item(@NotNull UUID productId, @NotNull @Positive Integer quantity) { }

    public record ReserveRequest(@NotNull UUID orderId,
            @NotEmpty @Size(max = 20) List<@NotNull @Valid Item> items) { }

    public record Reservation(UUID reservationId, UUID orderId, String reserveKey,
            String status, String rejectionReason, List<Item> items, Instant createdAt) { }

    public record Stock(UUID productId, String productName, int onHandQuantity,
            int reservedQuantity, int otherCommitments, int availableQuantity) { }

    public record ApiError(String code, String message) { }
}
