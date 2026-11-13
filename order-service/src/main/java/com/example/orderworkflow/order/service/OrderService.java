package com.example.orderworkflow.order.service;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.orderworkflow.order.api.OrderDtos.CreateOrderRequest;
import com.example.orderworkflow.order.api.OrderDtos.OrderResponse;
import com.example.orderworkflow.order.repository.OrderRecords.CatalogProduct;
import com.example.orderworkflow.order.repository.OrderRecords.OrderRecord;
import com.example.orderworkflow.order.repository.OrderRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class OrderService {
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$");
    private static final Pattern KEY = Pattern.compile("^[!-~]{1,512}$");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");

    private final OrderRepository repository;
    private final Duration ttl;
    private final ObjectMapper objectMapper;

    public OrderService(OrderRepository repository, ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value("${order.ttl:15m}") Duration ttl) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("order.ttl must be positive");
        }
    }

    @Transactional
    public OrderResponse create(CreateOrderRequest request, String clientId, String key,
            String requestedCorrelationId) {
        validateIdentifier(clientId, "X-Client-Id");
        validateKey(key);
        var quantities = normalize(request);
        String requestPayload = canonicalRequest(request.customerId(), request.storeId(), quantities);
        String payloadHash = hash(requestPayload);

        if (!repository.claimIdempotencyKey(clientId, key, payloadHash, requestPayload)) {
            var existing = repository.findIdempotency(clientId, key)
                    .orElseThrow(() -> new IllegalStateException("Conflicting idempotency record is unavailable"));
            if (!existing.payloadHash().equals(payloadHash)) {
                throw new OrderException(409, "IDEMPOTENCY_KEY_CONFLICT",
                        "Idempotency-Key was already used with a different payload");
            }
            if (existing.orderId() == null) {
                throw new IllegalStateException("Idempotency result is incomplete");
            }
            if (existing.responsePayload() == null) {
                throw new IllegalStateException("Idempotent Order response is unavailable");
            }
            return readResponse(existing.responsePayload());
        }

        List<String> skus = List.copyOf(quantities.keySet());
        Map<String, CatalogProduct> products = new LinkedHashMap<>();
        for (var product : repository.lockCatalog(request.storeId(), skus)) {
            products.put(product.sku(), product);
        }
        validateCatalog(skus, products);

        String currency = products.values().iterator().next().currency();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (var item : quantities.entrySet()) {
            BigDecimal line = products.get(item.getKey()).unitPrice()
                    .multiply(BigDecimal.valueOf(item.getValue()));
            total = total.add(line);
            if (line.scale() > 2 || total.compareTo(MAX_AMOUNT) > 0) {
                throw validation("Order amount exceeds the supported decimal range");
            }
        }

        var createdAt = repository.databaseTime();
        var expireAt = createdAt.plus(ttl);
        String orderId = "ORD-" + UUID.randomUUID().toString().replace("-", "");
        String reservationId = "RES-" + UUID.randomUUID().toString().replace("-", "");
        String correlationId = correlationId(requestedCorrelationId);
        repository.insertOrder(orderId, request.customerId(), clientId, request.storeId(), total, currency,
                reservationId, correlationId, createdAt, expireAt);
        repository.insertItems(orderId, quantities, products);
        var response = new OrderResponse(orderId, "PENDING", createdAt, expireAt, correlationId);
        repository.completeIdempotency(clientId, key, orderId, writeJson(response));
        return response;
    }

    @Transactional(readOnly = true)
    public OrderResponse get(String orderId) {
        validateIdentifier(orderId, "orderId");
        return repository.findOrder(orderId).map(OrderService::response)
                .orElseThrow(() -> new OrderException(404, "ORDER_NOT_FOUND", "Order was not found"));
    }

    private static TreeMap<String, Integer> normalize(CreateOrderRequest request) {
        validateIdentifier(request.customerId(), "customerId");
        var quantities = new TreeMap<String, Integer>();
        for (var item : request.items()) {
            try {
                quantities.merge(item.sku(), item.quantity(), Math::addExact);
            } catch (ArithmeticException exception) {
                throw validation("Combined quantity exceeds the supported integer range");
            }
        }
        return quantities;
    }

    private static void validateCatalog(List<String> skus, Map<String, CatalogProduct> products) {
        if (products.size() != skus.size()) {
            throw validation("One or more products do not exist in the requested store");
        }
        String currency = null;
        for (String sku : skus) {
            var product = products.get(sku);
            if (product == null || !product.active()) {
                throw validation("One or more products are not available for ordering");
            }
            if (product.unitPrice().signum() <= 0 || product.unitPrice().scale() > 2) {
                throw validation("Product price is invalid");
            }
            if (currency == null) {
                currency = product.currency();
            } else if (!currency.equals(product.currency())) {
                throw validation("All Order items must use the same currency");
            }
        }
        if (!"VND".equals(currency)) {
            throw validation("Only VND products are supported");
        }
    }

    private String canonicalRequest(String customerId, String storeId, Map<String, Integer> quantities) {
        var items = quantities.entrySet().stream()
                .map(entry -> {
                    var item = new LinkedHashMap<String, Object>();
                    item.put("sku", entry.getKey());
                    item.put("quantity", entry.getValue());
                    return item;
                })
                .toList();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("customerId", customerId);
        payload.put("storeId", storeId);
        payload.put("items", items);
        return writeJson(payload);
    }

    private static String hash(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Canonical JSON could not be written", exception);
        }
    }

    private OrderResponse readResponse(String value) {
        try {
            return objectMapper.readValue(value, OrderResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored idempotency response is invalid", exception);
        }
    }

    private static String correlationId(String value) {
        if (value == null || value.isBlank()) {
            return UUID.randomUUID().toString();
        }
        validateIdentifier(value, "X-Correlation-Id");
        return value;
    }

    private static void validateKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new OrderException(400, "VALIDATION_ERROR",
                    "Idempotency-Key must contain 1 to 512 printable ASCII characters without whitespace");
        }
    }

    private static void validateIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new OrderException(400, "INVALID_IDENTIFIER", field + " is invalid");
        }
    }

    private static OrderException validation(String message) {
        return new OrderException(400, "VALIDATION_ERROR", message);
    }

    private static OrderResponse response(OrderRecord order) {
        return new OrderResponse(order.orderId(), order.status(), order.createdAt(), order.expireAt(),
                order.correlationId());
    }
}
