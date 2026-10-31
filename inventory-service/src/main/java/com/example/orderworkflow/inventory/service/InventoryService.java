package com.example.orderworkflow.inventory.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.orderworkflow.inventory.api.InventoryDtos.Item;
import com.example.orderworkflow.inventory.api.InventoryDtos.Reservation;
import com.example.orderworkflow.inventory.api.InventoryDtos.ReserveRequest;
import com.example.orderworkflow.inventory.api.InventoryDtos.Stock;
import com.example.orderworkflow.inventory.repository.InventoryRepository;

@Service
public class InventoryService {
    private final InventoryRepository repository;

    public InventoryService(InventoryRepository repository) { this.repository = repository; }

    @Transactional
    public Reservation reserve(ReserveRequest request, String key) {
        if (request == null || request.orderId() == null) { throw invalid("orderId is required"); }
        if (!("reserve:" + request.orderId() + ":v1").equals(key)) {
            throw invalid("Idempotency-Key must be reserve:{orderId}:v1");
        }
        List<Item> items = normalize(request.items());
        String hash = hash(request.orderId(), items);
        repository.lockOrder(request.orderId());
        var previousHash = repository.payloadHash(request.orderId());
        if (previousHash.isPresent()) {
            if (!previousHash.get().equals(hash)) {
                throw new InventoryException(409, "IDEMPOTENCY_KEY_CONFLICT", "Order was reserved with a different payload");
            }
            return repository.byOrder(request.orderId()).orElseThrow();
        }

        String reason = null;
        // Consistent lock order across all multi-product requests prevents lock cycles.
        for (Item item : items) {
            var stock = repository.stock(item.productId(), true);
            if (stock.isEmpty()) { reason = "PRODUCT_NOT_FOUND"; }
            else if (reason == null && stock.get().availableQuantity() < item.quantity()) {
                reason = "INSUFFICIENT_STOCK";
            }
        }
        if (reason == null) { items.forEach(repository::allocate); }
        return repository.insert(request.orderId(), key, hash, items, reason);
    }

    @Transactional(readOnly = true)
    public Stock stock(UUID id) {
        return repository.stock(id, false).orElseThrow(() ->
                new InventoryException(404, "PRODUCT_NOT_FOUND", "Product not found"));
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
        var quantities = new TreeMap<UUID, Integer>();
        try {
            for (Item item : items) {
                if (item == null || item.productId() == null || item.quantity() == null || item.quantity() <= 0) {
                    throw invalid("Each item requires productId and positive quantity");
                }
                quantities.merge(item.productId(), item.quantity(), Math::addExact);
            }
        } catch (ArithmeticException e) { throw invalid("Combined quantity is too large"); }
        return quantities.entrySet().stream().map(e -> new Item(e.getKey(), e.getValue())).toList();
    }

    private static String hash(UUID orderId, List<Item> items) {
        StringBuilder payload = new StringBuilder("RESERVE:").append(orderId);
        items.forEach(item -> payload.append('|').append(item.productId()).append(':').append(item.quantity()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static InventoryException invalid(String message) {
        return new InventoryException(400, "INVALID_REQUEST", message);
    }

    private static InventoryException notFound() {
        return new InventoryException(404, "RESERVATION_NOT_FOUND", "Reservation not found; this does not cancel an in-flight Reserve");
    }
}
