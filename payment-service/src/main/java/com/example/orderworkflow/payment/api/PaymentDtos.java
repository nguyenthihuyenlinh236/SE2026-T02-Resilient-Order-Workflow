package com.example.orderworkflow.payment.api;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class PaymentDtos {
    private PaymentDtos() { }

    public static final String IDENTIFIER_PATTERN = "^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$";

    public record CaptureRequest(
            @NotBlank @Size(max = 128) @JsonDeserialize(using = StrictStringDeserializer.class)
            String commandId,
            @NotBlank @Pattern(regexp = IDENTIFIER_PATTERN) @JsonDeserialize(using = StrictStringDeserializer.class)
            String orderId,
            @NotBlank @JsonDeserialize(using = StrictStringDeserializer.class)
            String amount,
            @NotBlank @JsonDeserialize(using = StrictStringDeserializer.class)
            String currency,
            @NotBlank @Size(max = 128) @JsonDeserialize(using = StrictStringDeserializer.class)
            String correlationId) { }

    public record CaptureResponse(
            String paymentId,
            String orderId,
            String status,
            String idempotencyKey,
            String amount,
            String currency,
            Instant capturedAt) { }

    public record ErrorResponse(ErrorBody error) { }

    public record ErrorBody(
            String code,
            String message,
            List<ErrorDetail> details,
            String correlationId,
            boolean retryable) { }

    public record ErrorDetail(String field, String reason) { }
}
