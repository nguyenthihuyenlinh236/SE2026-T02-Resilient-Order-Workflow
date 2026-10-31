package com.example.orderworkflow.inventory.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.orderworkflow.inventory.api.InventoryDtos.Item;
import com.example.orderworkflow.inventory.api.InventoryDtos.Reservation;
import com.example.orderworkflow.inventory.api.InventoryDtos.ReserveRequest;
import com.example.orderworkflow.inventory.api.InventoryDtos.Stock;
import com.example.orderworkflow.inventory.repository.InventoryRepository;
import com.example.orderworkflow.inventory.repository.InventoryOperations;

@Service
public class InventoryService {
    private final InventoryRepository repository;
    private final InventoryOperations operations;

    public InventoryService(InventoryRepository repository, InventoryOperations operations) {
        this.repository = repository;
        this.operations = operations;
    }

    @Transactional
    public Reservation reserve(ReserveRequest request, String key) {
        if (request == null || request.orderId() == null) { throw invalid("orderId is required"); }
        if (request.reservationId() == null || request.storeId() == null || request.storeId().isBlank()
                || request.storeId().length() > 100) { throw invalid("reservationId and storeId are required"); }
        if (!("reserve:" + request.orderId() + ":v1").equals(key)) {
            throw invalid("Idempotency-Key must be reserve:{orderId}:v1");
        }
        List<Item> items = normalize(request.items());
        String hash = hash(request, items);
        repository.lockOrder(request.orderId());
        var saved = operations.replay(key, "RESERVE", hash, Reservation.class);
        if (saved.isPresent()) { return saved.get(); }
        var previousHash = repository.payloadHash(request.orderId());
        if (previousHash.isPresent()) {
            if (!previousHash.get().equals(hash)) {
                throw new InventoryException(409, "IDEMPOTENCY_KEY_CONFLICT", "Order was reserved with a different payload");
            }
            throw new InventoryException(409, "RESERVE_HISTORY_UNAVAILABLE",
                    "Original Reserve response is unavailable; query the Reservation instead");
        }

        String reason = repository.cancelled(request.orderId()) ? "ORDER_CANCELLED" : null;
        // Consistent lock order across all multi-product requests prevents lock cycles.
        for (Item item : reason == null ? items : List.<Item>of()) {
            var stock = repository.stock(request.storeId(), item.sku(), true);
            if (stock.isEmpty()) { reason = "PRODUCT_NOT_FOUND"; }
            else if (reason == null && stock.get().availableQuantity() < item.quantity()) {
                reason = "INSUFFICIENT_STOCK";
            }
        }
        if (reason == null) { items.forEach(item -> repository.allocate(request.storeId(), item)); }
        var result = repository.insert(request, key, hash, items, reason);
        operations.save(request.orderId(), key, "RESERVE", hash, result);
        return result;
    }

    @Transactional(readOnly = true)
    public Stock stock(UUID id) {
        return repository.stock(id, false).orElseThrow(() ->
                new InventoryException(404, "PRODUCT_NOT_FOUND", "Product not found"));
    }

    @Transactional(readOnly = true)
    public Stock stock(String storeId, String sku) {
        if (storeId == null || storeId.isBlank() || sku == null || sku.isBlank()) {
            throw invalid("storeId and sku are required");
        }
        return repository.stock(storeId, sku, false).orElseThrow(() ->
                new InventoryException(404, "PRODUCT_NOT_FOUND", "Product not found in store"));
    }

    @Transactional(readOnly = true)
    public Reservation reservation(UUID id) { return repository.byId(id).orElseThrow(InventoryService::notFound); }

    @Transactional(readOnly = true)
    public Reservation lookup(UUID orderId, String key) {
        if ((orderId == null) == (key == null)) { throw invalid("Provide exactly one of orderId or reserveKey"); }
        return (orderId != null ? repository.byOrder(orderId) : repository.byKey(key))
                .orElseThrow(InventoryService::notFound);
    }

    static List<Item> normalize(List<Item> items) {
        if (items == null || items.isEmpty() || items.size() > 20) { throw invalid("items must contain 1 to 20 lines"); }
        var quantities = new TreeMap<String, Integer>();
        try {
            for (Item item : items) {
                if (item == null || item.sku() == null || item.sku().isBlank() || item.sku().length() > 128
                        || item.quantity() == null || item.quantity() <= 0) {
                    throw invalid("Each item requires sku and positive quantity");
                }
                quantities.merge(item.sku(), item.quantity(), Math::addExact);
            }
        } catch (ArithmeticException e) { throw invalid("Combined quantity is too large"); }
        return quantities.entrySet().stream().map(e -> new Item(e.getKey(), e.getValue())).toList();
    }

    private static String hash(ReserveRequest request, List<Item> items) {
        try {
            // JSON structure avoids delimiter collisions in arbitrary store/SKU strings.
            byte[] payload = new ObjectMapper().writeValueAsBytes(List.of("RESERVE", request.orderId().toString(),
                    request.reservationId().toString(), request.storeId(), items));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload));
        } catch (NoSuchAlgorithmException | JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private static InventoryException invalid(String message) {
        return new InventoryException(400, "INVALID_REQUEST", message);
    }

    private static InventoryException notFound() {
        return new InventoryException(404, "RESERVATION_NOT_FOUND", "Reservation not found; this does not cancel an in-flight Reserve");
    }
}
