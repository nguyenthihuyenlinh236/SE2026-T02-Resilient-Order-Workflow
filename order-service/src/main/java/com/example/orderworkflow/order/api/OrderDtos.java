package com.example.orderworkflow.order.api;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class OrderDtos {
    private OrderDtos() { }

    public record ItemRequest(
            @NotBlank @Size(max = 128) @JsonDeserialize(using = StrictStringDeserializer.class) String sku,
            @NotNull @Positive @JsonDeserialize(using = StrictQuantityDeserializer.class) Integer quantity) { }

    public record CreateOrderRequest(
            @NotBlank @Size(max = 128) @JsonDeserialize(using = StrictStringDeserializer.class) String customerId,
            @NotBlank @Size(max = 100) @JsonDeserialize(using = StrictStringDeserializer.class) String storeId,
            @NotEmpty @Size(max = 100) List<@NotNull @Valid ItemRequest> items) { }

    public record OrderResponse(String orderId, String status, Instant createdAt,
            Instant expireAt, String correlationId) { }

    public record ErrorDetail(String field, String reason) { }
    public record OrderPage(List<OrderResponse> items, int page, int size,
            long totalElements, long totalPages) { }
    public record ErrorBody(String code, String message, List<ErrorDetail> details,
            String correlationId, boolean retryable) { }
    public record ErrorResponse(ErrorBody error) { }
}
