package com.example.orderworkflow.payment.service;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.orderworkflow.payment.api.PaymentDtos.CaptureRequest;
import com.example.orderworkflow.payment.api.PaymentDtos.CaptureResponse;
import com.example.orderworkflow.payment.repository.CaptureRecord;
import com.example.orderworkflow.payment.repository.CaptureRepository;

@Service
public class PaymentCaptureService {
    private static final Pattern AMOUNT = Pattern.compile("^(?:0|[1-9][0-9]{0,11})(?:\\.[0-9]{1,2})?$");
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$");
    private static final Pattern KEY = Pattern.compile("^[!-~]{1,512}$");

    private final CaptureRepository repository;
    private final String configuredOutcome;

    public PaymentCaptureService(CaptureRepository repository,
            @Value("${payment.mock.outcome:IN_PROGRESS}") String configuredOutcome) {
        this.repository = repository;
        this.configuredOutcome = validateOutcome(configuredOutcome);
    }

    @Transactional
    public CaptureResponse capture(CaptureRequest request, String key) {
        BigDecimal amount = validateRequest(request, key);
        String payloadHash = hash(request, amount);
        String paymentId = UUID.randomUUID().toString();
        UUID transactionId = UUID.randomUUID();

        if (repository.insert(paymentId, request.orderId(), key, payloadHash, amount, "VND",
                configuredOutcome, transactionId)) {
            return response(requiredByKey(key));
        }

        var sameKey = repository.findByIdempotencyKey(key);
        if (sameKey.isPresent()) {
            if (!sameKey.get().payloadHash().equals(payloadHash)) {
                throw conflict("Idempotency key was already used with a different payload");
            }
            return response(sameKey.get());
        }

        var sameOrder = repository.findByOrderId(request.orderId());
        if (sameOrder.isPresent()) {
            throw conflict("Order already has a Capture under a different idempotency key");
        }
        throw new IllegalStateException("Capture insert lost without a conflicting record");
    }

    @Transactional(readOnly = true)
    public CaptureResponse getByPaymentId(String paymentId) {
        requireIdentifier(paymentId, "paymentId");
        return repository.findByPaymentId(paymentId).map(PaymentCaptureService::response)
                .orElseThrow(PaymentCaptureService::notFound);
    }

    @Transactional(readOnly = true)
    public CaptureResponse getByIdempotencyKey(String key) {
        requireKey(key);
        return repository.findByIdempotencyKey(key).map(PaymentCaptureService::response)
                .orElseThrow(PaymentCaptureService::notFound);
    }

    private CaptureRecord requiredByKey(String key) {
        return repository.findByIdempotencyKey(key)
                .orElseThrow(() -> new IllegalStateException("Inserted Capture cannot be read"));
    }

    private static BigDecimal validateRequest(CaptureRequest request, String key) {
        requireKey(key);
        if (request == null) {
            throw invalid("Request body is required");
        }
        requireIdentifier(request.orderId(), "orderId");
        if (!("payment:capture:" + request.orderId() + ":v1").equals(key)) {
            throw invalid("Idempotency-Key must be payment:capture:{orderId}:v1");
        }
        if (request.commandId() == null || request.commandId().isBlank() || request.commandId().length() > 128) {
            throw invalid("commandId must be a non-blank string of at most 128 characters");
        }
        if (request.correlationId() == null || request.correlationId().isBlank()
                || request.correlationId().length() > 128) {
            throw invalid("correlationId must be a non-blank string of at most 128 characters");
        }
        if (!"VND".equals(request.currency())) {
            throw invalid("currency must be VND");
        }
        if (request.amount() == null || !AMOUNT.matcher(request.amount()).matches()) {
            throw invalid("amount must be a positive decimal string with at most 12 integer and 2 fraction digits");
        }
        BigDecimal amount = new BigDecimal(request.amount());
        if (amount.signum() <= 0) {
            throw invalid("amount must be greater than zero");
        }
        return amount.setScale(2);
    }

    private static String hash(CaptureRequest request, BigDecimal amount) {
        String canonical = String.join("\n", "CAPTURE", request.commandId(), request.orderId(),
                amount.toPlainString(), request.currency(), request.correlationId());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static CaptureResponse response(CaptureRecord record) {
        return new CaptureResponse(record.paymentId(), record.orderId(), record.status(),
                record.idempotencyKey(), record.amount().setScale(2).toPlainString(), record.currency(),
                record.capturedAt());
    }

    private static String validateOutcome(String value) {
        String normalized = value == null ? "" : value.toUpperCase(Locale.ROOT);
        if (!normalized.equals("IN_PROGRESS") && !normalized.equals("SUCCEEDED")) {
            throw new IllegalArgumentException("payment.mock.outcome must be IN_PROGRESS or SUCCEEDED");
        }
        return normalized;
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new PaymentException(400, "INVALID_IDENTIFIER", field + " is invalid");
        }
    }

    private static void requireKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new PaymentException(400, "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key is invalid");
        }
    }

    private static PaymentException invalid(String message) {
        return new PaymentException(400, "VALIDATION_ERROR", message);
    }

    private static PaymentException conflict(String message) {
        return new PaymentException(409, "IDEMPOTENCY_KEY_CONFLICT", message);
    }

    private static PaymentException notFound() {
        return new PaymentException(404, "PAYMENT_NOT_FOUND", "Payment Capture was not found");
    }
}
