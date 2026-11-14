package com.example.orderworkflow.order.api;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class OrderDraftDtos {
    private OrderDraftDtos() { }

    public record DraftItem(@Size(max = 128) String sku, Integer quantity) { }

    public record SaveDraftRequest(
            @NotNull @Positive Long revision,
            @Size(max = 128) String customerId,
            @Size(max = 100) String storeId,
            @Size(max = 100) List<@Valid DraftItem> items) { }

    public record DraftResponse(String draftId, long revision, String customerId, String storeId,
            List<DraftItem> items, String outcome, Instant savedAt, String correlationId) { }
}
